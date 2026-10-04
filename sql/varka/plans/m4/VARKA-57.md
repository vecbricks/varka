# VARKA-57: extract(DAYOFWEEK_ISO) as one narrow node

## 1. Where this came from

`m4/PLAN.md` row 57 and section 2.24, from the coverage survey taken
after the milestone was re-scoped to the date family (4 September 2026).
`extract(DAYOFWEEK_ISO FROM d)` and `date_part('DOW_ISO', d)` are Monday 1 to
Sunday 7, and the analyzer resolves both to `Add(WeekDay(d), Literal(1))`
(`datetimeExpressions.scala`, the `Extract` arm). `WeekDay` compiles; the
integer `Add` over its result does not, on purpose - a general int-arithmetic
arm would admit `datediff(a, b) + 1`, whose overflow semantics are milestone
5's VARKA-30 - so the two spellings decline today with "unsupported
expression". The value here cannot overflow: a constant one over `0..6`.

## 2. The admission check, done

**The analyzer's spelling.** `Extract.parseExtractField` (checked in this
worktree) resolves `DAYOFWEEK_ISO` and `DOW_ISO` to
`Add(WeekDay(source), Literal(1))`, the literal an `IntegerType` one, the
`WeekDay` on the left. `date_part('DOW_ISO', d)` goes through the same
function. A user writing `weekday(d) + 1` by hand produces the identical tree
after analysis, and the optimizer does not reorder the operands of an `Add`
with a literal on the right, so one operand order is what reaches the
compiler; the arm accepts both anyway, since the cost of the second pattern is
one line and the failure it prevents is a silent decline.

**The value.** `DateTimeUtils.getWeekDay(d) + 1` is `1..7` for every int day,
and `getWeekDay` is `floorMod(d + 3, 7)`, which the emitter's `WeekDay` tail
already computes and VARKA-37's register pins at 17 dense-loop `IntVector`
calls. One lanewise add on top is the whole of this node: 18, the same single
op that separates `dayofweek` from `weekday` today.

**What the check would have rejected:** an analyzer that folded the add into
a different node, or a spelling that reached the compiler as something other
than `Add(WeekDay, 1)`; neither is the case, so the arm is exactly as narrow as
section 2.24 says.

## 3. The design

### 3.1 One node, one arm

**`DayOfWeekIso(VarkaVectorIR days)`**, a plain record in the IR's top-level
`permits` beside `WeekDay`, rendering as `(dayOfWeekIso <days>)`. The emitter
arm is `WeekDay`'s (`emitFloorMod7`, then `emitModOffset(cb, s, 3)`) followed
by `add 1`, exactly as `DayOfWeek`'s arm adds its one after the offset of four;
`planSlots` gives it `emitFloorMod7`'s two `dowTmp` scratch slots as it gives
`DayOfWeek`, `WeekDay`, `NextDay` and VARKA-37's `ThursdayOf`; `planWordRef`
aliases its word to the date's; `weightOf` returns a `DAY_OF_WEEK_ISO_WEIGHT`
equal to the `WeekDay` count plus one, counted the way `NEXT_DAY_WEIGHT` is.
No prefix, so `chronoChild` and `tailReadsMarchMonth` are not involved.

**The compiler arm**, placed with the other narrow rewrites, before the
catch-all:

    case Add(WeekDay(child), Literal(1, IntegerType)) => ... new DayOfWeekIso(_)
    case Add(Literal(1, IntegerType), WeekDay(child)) => ... new DayOfWeekIso(_)

Any other `Add` still declines: the arm matches the constant one over a
`WeekDay` and nothing else, so `weekday(d) + 2` and `datediff(a, b) + 1` are
unchanged residuals, which the compiler suite pins.

**Not a rewrite onto `DayOfWeek`.** `dayofweek` is Sunday 1 to Saturday 7, so
`dayofweek(d) - 1` with a Sunday wrap would need a select; the one-op node is
cheaper and says what it is.

### 3.2 What is deliberately unchanged

* `emitFloorMod7` and its variants; the reciprocal form is its own task.
* `DayOfWeek` and `WeekDay`: bytes unchanged, which the register asserts.
* General integer arithmetic over outputs: VARKA-30, milestone 5.
* VARKA-37's `ThursdayOf` and `WeekOfYear` (open branch): the two tasks add
  cases to the same switches and import lines; whichever merges second takes
  the additive conflict.

### 3.3 Registered op counts

Dense-loop `IntVector` calls, the register test's metric.

| kernel | before | after |
|---|---|---|
| `DayOfWeekIso(col)` | - | 18 (predicted; `weekday`'s 17 plus one) |
| `weekday(col)` | 17 | 17 |
| `dayofweek(col)` | 18 (predicted from `weekday` + 1) | unchanged |

## 4. Files

| file | what |
|---|---|
| `VarkaVectorIR.java` | the record in `permits`; both renderings |
| `VarkaLoopEmitter.java` | the weight; `childrenOf`, `analyze`, `planWordRef`, `planSlots`, `emitValue` |
| `VarkaReferenceEvaluator.scala` | `DateTimeUtils.getWeekDay(v) + 1`, the definition |
| `VarkaLoopEmitterSuite.scala` | the matrix over a whole week and the boundaries under the three `FloorMod7` variants; the register; both pinned fixtures re-pinned |
| `VarkaIrFuzzSuite.scala` | a generator arm, bound 7 |
| `VarkaShapeCacheSuite.scala` | the `everyNode` hash re-pinned |
| `VarkaExpressionCompiler.scala` (+ suite) | the two arms; `weekday(d) + 2` and `datediff + 1` still declining |
| `VarkaDifferentialSuite.scala` | the three spellings in one query against every null pattern |
| `VarkaEmitterParityBenchmark.scala` + files | one case beside `weekday`'s; regenerated at both widths |
| `docs/sql-varka.md` | the surface line |
| `m4/PLAN.md`, this file | row 57, section 9 |

## 5. Tests, and what each is for

* The oracle is `DateTimeUtils.getWeekDay(v) + 1`, the definition; never the
  lowering.
* `checkMatrix` over `calendarBoundaryDays` plus every day of one week and of
  the week around 1970-01-01, every length and null pattern, the three
  `FloorMod7` variants: the failure it catches is an off-by-one in the offset
  or the add, which a boundary list without a full week would miss on the
  wrap day.
* The register: 18, with `weekday` and `dayofweek` unmoved.
* The compiler: both operand orders compile to `DayOfWeekIso(ColumnRef(0))`
  with an `IntegerType` output; `weekday(d) + 2`, `dayofweek(d) + 1` and
  `datediff(a, b) + 1` decline with "unsupported expression".
* The differential: `extract(DAYOFWEEK_ISO FROM d)`, `date_part('DOW_ISO', d)`
  and `weekday(d) + 1` over the shared dates table with a null, all three
  fused, matching the row engine; a filter `WHERE extract(DAYOFWEEK_ISO FROM
  d) = 7` counted, which is one kernel once VARKA-37's int-literal comparison
  arm is on master (until then the differential asserts the projection only,
  and the filter line joins after the merge).
* Both pinned fixtures move by one line and one hash, re-pinned from the
  failing output.

## 6. The measurement

`VarkaEmitterParityBenchmark`, the dayofweek section: one `dayofweek_iso`
case beside `weekday`'s, null-free, both widths, regenerated with
`dev/varka_bench_regen.sh catalyst VarkaEmitterParityBenchmark` on an idle
machine. The control rows are `weekday` and `dayofweek`, which must not move.

### 6.1 Predictions, registered before the run

1. The register: 18, siblings unmoved.
2. The `dayofweek_iso` row runs within 3% of `weekday`'s rate at both widths:
   one add on a 17-op body.
3. No other row moves beyond the machine-day variance the last regenerations
   recorded.

## 7. Risks

1. **The analyzer changes the spelling** (a future Spark folds
   `weekday + 1` differently): the compiler suite builds the tree through
   `Extract` itself, not by hand, so the arm fails there first.
2. **The wrap day**: Sunday is 7, not 0; the full-week matrix rows hold it.
3. **The merge with VARKA-37**: additive conflicts in the emitter's switches
   and import lines only.

## 8. Sequencing

1. This plan and the milestone row.
2. The node, the emitter arm, the oracle, the tests, the fixtures re-pinned.
3. The compiler arms and suite, the differential, the docs.
4. The benchmark case, one regeneration, section 9.

## 9. Outcome

Built as planned: one node, one arm each way, the register at 18. The
measurement is `VarkaEmitterParityBenchmark`, regenerated at both widths by
`dev/varka_bench_regen.sh catalyst VarkaEmitterParityBenchmark` on the idle
machine (load 0.90 at start, canary compute +0.2%, cache +4.1%, memory
-1.0%, governor `performance`), against the baseline #121 re-measured on
unchanged master under the same profile the same morning. The row and its
control sit in the dayofweek section, both run the same way (one call over
the whole buffer). Rates in M rows/s from the committed files.

| case | 256-bit | 128-bit |
|---|---|---|
| `dayofweek_iso (VARKA-57), null-free` | 7561.6 | 3485.9 |
| `magic multiply (shipped), null-free` (`dayofweek`, 18 ops) | 7550.7 | 3487.4 |

**Predictions scored.**

1. *The register: 18, siblings unmoved.* Held; the suite asserts it
   (`dayofweek` 18, `weekday` 17, `dayofweek_iso` 18).
2. *Within 3% of the sibling's rate at both widths.* Held with room:
   +0.1% at 256 bits and -0.0% at 128 - the same 18 ops at the same rate,
   which is what "one add on `weekday`'s tail" should cost. Section 6 named
   `weekday` as the comparison; the benchmark has no `weekday` row, so the
   shipped `dayofweek` (same op count) is the one used, and the case's own
   comment already said so.
3. *No other row moves beyond the machine-day variance.* Held: against
   #121's second run, 32 rows moved by 3% or more, between -19% and +8%,
   with the five compute-bound controls within 0.5%. #121's own two runs of
   identical master code, the same morning, moved 47 rows by 3-13% against
   each other, so this is the run-to-run floor, not the node - which touches
   none of those rows' bytes (the suite pins `dayofweek` and `weekday`).

What moved that the plan did not list: nothing in the code. The measurement
itself was delayed by two things outside this task, recorded so the numbers
read right: the first overnight regeneration ran under a power profile
different from every committed baseline, which is why #121 exists, and a
first master run had one row at a third of its value (a JIT artifact its
second run did not reproduce). Nothing left for later.
