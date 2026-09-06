# Task 61: trunc with a format column

## 1. Where this came from

`PLAN_MILESTONE_4.md` row 61 and section 2.28, from the coverage survey of 4
September 2026. Task 35 covers `trunc(d, 'MONTH')`: the format resolves at
compile time through `DateTimeUtils.parseTruncLevel`, and the level chooses
which code is emitted - `YEAR`, `MONTH` and `QUARTER` are one `TruncDate`
node with the level as a shape-bearing field, and `WEEK` is rewritten onto
`next_day(date_sub(d, 7), 'MONDAY')`, Spark's own definition. A format
**column** declines today with "trunc with a non-foldable format". This is
the last of the three string-argument date functions; it is worth doing
because task 59's derived leaf (a string column re-encoded per batch into an
int32 code column the kernel reads) makes it cheap to finish the family,
and section 2.28 says so. The owner's directive of 5 September 2026: after
task 60, take 61. It is built on `varka-task-59` (PR #126), which it needs,
and rebases onto master when that merges.

## 2. The admission check, done

**What the row engine does per row.** `TruncDate.eval` runs
`TruncInstant.evalHelper` (checked in this worktree): when the format is not
foldable it calls `parseTruncLevel(format.eval(input))` for every row, and
if the parsed level is below `MIN_LEVEL_OF_DATE_TRUNC` (which is
`TRUNC_TO_WEEK`, code 6) the result is **null** - the same null for a null
format (`parseTruncLevel(null)` is `TRUNC_INVALID`), an unrecognised
spelling, and every sub-day level (`'DAY'`, `'HOUR'`, ...). There is no
error path: `TruncDate` has no `failOnError`, in either evaluation mode. So,
unlike task 59, the derived column's validity *is* the output's validity for
the format's part, and nothing ever declines a batch for the format.

**The codes.** `parseTruncLevel` returns `TRUNC_TO_WEEK = 6`, `TRUNC_TO_MONTH
= 7`, `TRUNC_TO_QUARTER = 8`, `TRUNC_TO_YEAR = 9`, and `TRUNC_INVALID = -1`
or a code below 6 for everything else. The leaf writes the parser's own code
into the lane where it is one of 6..9 and a null lane otherwise; the kernel
selects on those four values and never re-maps them.

**The four results, from one prefix and one mod-7.** `truncDate(days, level)`
(checked): `MONTH` is `days - getDayOfMonth(days) + 1`, `YEAR` is `days -
getDayInYear(days) + 1`, `QUARTER` is the quarter's first day through
`LocalDate`, and `WEEK` is `getNextDateForDayOfWeek(days - 7, MONDAY)` =
`(days - 7) + 1 + ((MONDAY - 1 - (days - 7)) mod 7)`, which is the Monday on
or before `days`, i.e. `days - weekday0(days)` with Monday as 0 - and
`weekday0` is exactly `floorMod(days + 3, 7)`, the emitter's `WeekDay` tail
(1970-01-01 was a Thursday: `(0 + 3) mod 7 = 3`). Verified in section 5 by
the sweep against `truncDate` itself over every day of the covered range for
all four codes; the identity was checked here over 1970-01-01 +- 20000 days
in `jshell` with zero mismatches. The three calendar levels are task 35's
subtract form unchanged; the week result needs no prefix at all.

**The cost is all four levels per row.** The row picks after the fact, so
the tail computes every result and blends: task 35's register has `YEAR` at
45, `MONTH` at 36 and `QUARTER` at 62 dense-loop `IntVector` calls, sharing
one prefix and one `dayofyear`, and the week form is `weekday`'s 17 plus a
subtract. Section 3.3 registers the sum as emitted. This is the price of not
knowing the level at compile time; the literal form stays the shape a query
should write, and the doc says so.

**What the check would have rejected:** a row engine that raised on an
invalid format (it does not), a `WEEK` definition that was not `days -
weekday0` (it is), or a level code the kernel would have to re-map (it does
not).

## 3. The design

### 3.1 The leaf, the node, the arm

**The leaf.** `TruncLevelLeaf.fill(formats, length, dstData, dstValidity)`
in the engine package beside `WeekdayLeaf`, on its contract: reads the
`VarCharVector` through the accessor the evaluator trusts, writes
`parseTruncLevel(format)` into the lane where it is in 6..9 and clears the
validity bit otherwise, and returns the null count. It never declines: there
is no ANSI route because there is no error. One parser only, the row
engine's, because the value set is nine spellings with case folding and no
measurement was promised for an ASCII fast path; task 59's numbers say the
leaf is the parse and the parse is `toUpperCase`, so a fast path would be a
separate measured task if the throughput row asks for one.

**The kind.** `VarkaDerivedKind.TRUNC_LEVEL(false)`: the third kind, the
first with no ANSI twin. The evaluator's `fillSources` dispatches on the
kind to the leaf; `isArrowBacked` accepts a `VarCharVector` at its source as
for `WEEKDAY`.

**The node.** `TruncDateDynamic(VarkaVectorIR days, VarkaVectorIR level)`, a
`Chrono` member. Its word is the AND of the date's and the level's
(`planWordRef` via `andRef`, `emitAndWord` by hand as task 60 does for
`AddMonths`), so a null format nulls the row exactly as the row engine's
null does. `chronoChild` is `days`; `tailReadsMarchMonth` is true (the
`MONTH` and `QUARTER` results read it); `dayRange` treats it as `TruncDate`,
`shifted(days, -365, 0)`; its weight is a new `TRUNC_DYNAMIC_WEIGHT`, the sum
section 3.3 registers. It takes the subtract form's thirteen chrono slots plus
the two `dowTmp` scratch slots `emitFloorMod7` needs and one more of its own
for the level vector (`TRUNC_DYNAMIC_TMP_COUNT`); the four results ride the
operand stack, since the helpers between them only load and store named
locals (built that way in commit 3; the plan first said three own slots).

**The tail**, after `emitChronoPrefixOnce`: the `SUBTRACT` form's three
results - `MONTH` off `emitZeroBasedDayOfMonth`, `YEAR` off
`emitJanuaryDayOfYear`, `QUARTER` off the same day of year plus the quarter
start - factored out of `emitChronoTrunc` so both callers emit the same
bytes (task 35's literal node must not move: asserted on sizes); the `WEEK`
result as `days - weekday0(days)` through `emitFloorMod7` and
`emitModOffset(3)` on a second copy of the date kept on the operand stack,
`NextDay`'s pattern; then the select: starting from the `YEAR` result,
`blend(QUARTER, level == 8)`, `blend(MONTH, level == 7)`, `blend(WEEK, level
== 6)` - three compares and three blends on the level lanes. Invalid codes
never reach the select as valid lanes: the leaf nulled them, and the node's
word carries that.

**The compiler arm**, beside the literal one:

    case TruncDate(date, br: BoundReference) if br.dataType is a StringType =>
      calendarInput(date, expr, inputs, literals, sink)
        .map(new TruncDateDynamic(_, derivedRef(br, TRUNC_LEVEL, inputs)))

Any other non-foldable format still declines with the existing reason. The
`WEEK` literal rewrite and the three literal levels are untouched.

**What a user observes.** `trunc(d, fmt)` with `fmt` a string column of any
collation fuses; rows whose format is null, unrecognised or below a day are
NULL, as before; the literal form's plan and bytes are unchanged.

### 3.2 What is deliberately unchanged

* The literal `TruncDate` node, its two lowerings behind `TruncDateForm`,
  and the `WEEK` rewrite - byte for byte, asserted.
* `WeekdayLeaf`, the derived-input plumbing (`VarkaDerivedInput`, the
  synthetic keys, `fillSources`' scratch discipline), and the filter's
  string-column compaction limitation task 59 recorded: a stacked
  `trunc(d, fmt)` projection over a Varka filter is refused per batch the
  same way, correct on the row path.
* No `RECOMPOSE` form for the dynamic node: task 35 shipped `SUBTRACT`, and
  a second form here would double a tail that already computes four results.
* The IR fuzzer's `TruncDate` arm stays literal: its columns hold
  day-magnitude values, so a level column would be null in every lane.

### 3.3 Registered op counts

Dense-loop `IntVector` calls in `loopDense0`, from `dev/varka_emit.sh`
(`--table`, columns `d:date,fmt:string`) - the before column from task 35's
committed register, the after column filled from the emitted bytes before
commit 2 and asserted by the register test in section 5:

| kernel | before | after (predicted) | after (measured) |
|---|---|---|---|
| `trunc(d, 'MONTH')` | 36 | 36 (unmoved, asserted) | 36 |
| `trunc(d, 'YEAR')` | 45 | 45 (unmoved) | 45 |
| `trunc(d, 'QUARTER')` | 62 | 62 (unmoved) | 62 |
| `trunc(d, fmt)`, `fmt` a column | - | about 100: the `QUARTER` tail (62, which contains `YEAR`'s and the prefix), plus `MONTH`'s two ops, plus the week's 18, plus three compares and three blends | 91 |

The measured 91 is under the prediction because the week's 18 counted
`weekday`'s whole kernel - its load, its own subtract and the store - where the
tail only pays `emitFloorMod7` and `emitModOffset` (12 and 3 ops) plus one
subtract, and because the year and quarter results share `emitTruncYearParts`
once rather than `QUARTER`'s 62 containing a second copy of `YEAR`'s. The
`WEEK` literal rewrite registers at 20 (`next_day` over `date_sub`), for the
record; it is untouched.

The prediction assumes the factoring shares the prefix, the year, the leap
flag and the day of year across the three calendar results, which
`emitChronoTrunc`'s `QUARTER` arm already does for two of them.

## 4. Files

| file | what |
|---|---|
| `VarkaDerivedKind.java` | `TRUNC_LEVEL(false)` |
| `TruncLevelLeaf.java` (+ `TruncLevelLeafSuite.scala`) | the fill, on `WeekdayLeaf`'s contract; every spelling in three cases, invalid, sub-day, null, empty |
| `VarkaVectorIR.java` | `TruncDateDynamic` in `Chrono`'s `permits`; the rendering |
| `VarkaLoopEmitter.java` | the weight, `childrenOf`, `analyze`, `planWordRef`, `planSlots`, `chronoChild`, `tailReadsMarchMonth`, the tail; `emitChronoTrunc`'s three results factored for two callers |
| `VarkaReferenceEvaluator.scala` | `truncDate(days, level)` per row over the level lane, null where the lane is null |
| `VarkaLoopEmitterSuite.scala` | the matrix, the sweep, the literal node's sizes, the register; both pinned fixtures re-pinned |
| `VarkaIrFuzzSuite.scala` | a comment on why the arm stays literal |
| `VarkaExpressionCompiler.scala` (+ suite) | the arm; the collated column; the decline of a computed format |
| `VarkaKernelEvaluator.scala` (+ suite) | the kind dispatched in `fillSources`; the source check |
| `VarkaSharedSessions.scala`, `VarkaDifferentialSuite.scala` | `cacheDatesTruncFormats`; the differential |
| `VarkaEmitterParityBenchmark.scala` + files, `VarkaThroughputBenchmark.scala` + files | section 6 |
| `docs/sql-varka.md`, `SKILLS.md` | the surface line; a note under task 59's lesson |
| `PLAN_MILESTONE_4.md`, this file | row 61, section 9 |

## 5. Tests, and what each is for

The oracle is `DateTimeUtils.truncDate(days, level)` over the parsed level,
the definition, with null where `parseTruncLevel` gives a code below 6.

* **The leaf.** Every accepted spelling (`YEAR`/`YYYY`/`YY`, `MON`/`MONTH`/
  `MM`, `QUARTER`, `WEEK`) in upper, lower and mixed case maps to its code;
  `DAY`/`DD`, `HOUR`, `MICROSECOND` and friends, `QTR`, the empty string, an
  untrimmed `' YEAR'` and a non-ASCII string are null lanes; the fill over a
  hand-built `VarCharVector` returns the null count and zeroes the validity
  tail. The failure it catches: a code written for a sub-day level, which
  the kernel would then truncate to something.
* **Emitter, the matrix.** `checkMatrix` over `TruncDateDynamic(col0, col1)`
  with the level column cycling 6, 7, 8, 9 and the date over
  `calendarBoundaryDays` plus every day of 2023 and 2024 (task 35's sweep
  span, so every month, quarter and week start is crossed in both year
  kinds), every null pattern of both columns, both widths, both `FloorMod7`
  variants that ship: the failure is a blend on the wrong code, a week
  result off by the Monday, or a word that ignores the level's nulls.
* **Emitter, the bytes.** `codeSize` of `TruncDate(col, MONTH|YEAR|QUARTER)`
  under both `TruncDateForm`s before and after the factoring: identical. The
  register asserts 3.3's after column.
* **Emitter, the sweep** (opt-in, `-Dvarka.sweep=true`, task 35's pattern):
  every day of the covered range at every level against `truncDate`.
* **Compiler.** `trunc(d, fmt)` with a string column compiles to
  `TruncDateDynamic(ColumnRef(0), ColumnRef(1))` with `inputOrdinals` naming
  the string source and a `VarkaDerivedInput` of kind `TRUNC_LEVEL`; a
  collated column (`UTF8_LCASE`) compiles the same; `trunc(d, upper(fmt))`
  and `trunc(d, concat(fmt, ''))` decline with the existing reason; the
  literal spellings compile to the unchanged literal nodes.
* **Evaluator.** Three batch sizes with allocator accounting; an on-heap
  string source refused; a batch whose formats are all invalid yields an
  all-null output with the kernel still counted as run.
* **Differential**, over `varka_dates_trunc_formats` (`d` and `fmt` mixing
  `'YEAR'`, `'yy'`, `'Month'`, `'mm'`, `'QUARTER'`, `'week'`, `'HOUR'`,
  `'QTR'`, `''`, null, beside null dates; one partition): `trunc(d, fmt)`
  matches the row engine with zero fallbacks of any kind; the same beside
  `trunc(d, 'MONTH')` in one projection (the literal node unchanged next to
  the dynamic one); the filter route `WHERE trunc(d, fmt) = trunc(d,
  'MONTH')`; the compaction limitation asserted as task 59 asserts it.
* **Pinned fixtures**: the shallow rendering and the `everyNode` hash move
  by one line and one hash, re-pinned from the failing output.

## 6. The measurement

`VarkaEmitterParityBenchmark`, a `trunc` section beside task 35's: the
dynamic kernel over a level column cycling the four codes, null-free and
mixed nulls on the date, with `trunc(d, 'QUARTER')` (the widest literal) and
`trunc(d, 'MONTH')` (the narrowest) as the controls that must not move, the
leaf alone over valid spellings and over a tenth invalid, and a per-row
anchor (`parseTruncLevel` then `truncDate`). `VarkaThroughputBenchmark`:
`trunc(d, fmt)` over a new 2M-row fixture with a format column cycling the
accepted spellings, beside `trunc(d, 'MONTH')` on the same fixture as the
control. Both widths, regenerated with `dev/varka_bench_regen.sh` on the idle
machine.

### 6.1 Predictions, registered before the run

1. The register: about 100 for the dynamic kernel; the three literal rows
   unmoved.
2. The dynamic kernel runs at 0.55x-0.7x of `trunc(d, 'QUARTER')`'s rate at
   256 bits (about 100 ops against 62, the extra ops the cheap kind) and no
   lower than 0.5x at 128 bits.
3. The leaf on valid spellings runs within 2x of task 59's row-engine parser
   (both are `toUpperCase` and a small match); the fused form is the parse,
   as task 59 found, so the parity ratio against the per-row anchor lands
   between 1.5x and 3x.
4. Throughput: `trunc(d, fmt)` Varka over Janino between 2x and 4x - lower
   than the literal row's ratio, since both engines parse per row and only
   the arithmetic is fused.
5. The literal `trunc` rows do not move beyond the machine-day variance
   recorded in #121's two runs.

## 7. Risks

1. **The factoring moves task 35's bytes.** The size assertions on the
   literal node under both forms, and the register.
2. **The week result off by one Monday** (a `days - 7` copied from the
   rewrite that does not belong in the direct form). The whole-week matrix
   rows and the sweep.
3. **A null level lane condemning or computing a row.** The word is the AND
   of both inputs; the matrix's null patterns on the level column, and the
   all-invalid batch test.
4. **Slot collisions** between the chrono temporaries and `emitFloorMod7`'s
   scratch in one node. The matrix at every null pattern is what shows a
   clobbered word, as task 37 recorded.
5. **The base branch.** Built on `varka-task-59`; if #126 changes before it
   merges, this branch re-merges it. Conflicts with task 60 (the `AddMonths`
   arm's neighbourhood in `emitValue`) are additive.

## 8. Sequencing

1. This plan and the milestone row.
2. The leaf and its suite; the kind; the evaluator dispatch.
3. The node: IR, emitter (the factoring first, sizes asserted, then the
   tail), the reference arm, the matrix, the sweep, the register; the
   fixtures re-pinned.
4. The compiler arm and suite, the differential, the docs.
5. The benchmark cases, one regeneration at both widths, section 9, row 61.

## 9. Outcome

Measured 5 September 2026 on the idle machine under the performance profile
(the provenance files beside each results file record the governor, the load
and the canary), regenerated with `dev/varka_bench_regen.sh` at both widths.
Two parity runs were made; the committed file is the second, and every task
row of the first agreed with it within 2% at both widths. The numbers below
are from `sql/catalyst/benchmarks/VarkaEmitterParityBenchmark-jdk25-results.txt`
and its `-128bit` companion, and from
`sql/core/benchmarks/VarkaThroughputBenchmark-jdk25-results.txt` and its
companion; M rows/s throughout.

### 9.1 The parity harness

| row | 256-bit | 128-bit |
|---|---|---|
| `trunc(d, 'QUARTER')`, literal kernel (control), null-free | 1575.8 | 567.8 |
| `trunc(d, 'MONTH')`, literal kernel (control), null-free | 3329.3 | 1264.5 |
| `trunc(d, level)`, dynamic kernel, null-free | 1172.0 (0.75x of `QUARTER`) | 445.6 (0.79x) |
| `trunc(d, 'QUARTER')`, literal kernel (control), mixed nulls | 1328.8 | 500.4 |
| `trunc(d, level)`, dynamic kernel, mixed nulls on the date | 976.2 (0.73x) | 383.9 (0.77x) |
| trunc-level leaf, valid formats | 27.1 | 31.7 |
| trunc-level leaf, a tenth sub-day or unrecognised | 27.5 | 33.0 |
| `trunc(d, fmt)` per row, `parseTruncLevel` then `truncDate` (the row engine) | 22.9 | 25.2 |
| for scale: weekday leaf, row-engine parser, valid names (task 59) | 26.5 | 31.6 |

The fused path is the leaf plus the kernel: at 256 bits about 36.9 ns for
the parse and 0.85 ns for the arithmetic per row, against the row engine's
43.7 ns - the parse is the cost on both sides, and the arithmetic was never
it. The dynamic kernel against the widest literal tail lands at three quarters
of its rate, for a kernel that computes four periods instead of one.

### 9.2 Throughput

| query | 256-bit, Janino to Varka | 128-bit |
|---|---|---|
| `trunc(d, 'MONTH')` (task 61 control) | 38.1 to 308.8 (8.1x) | 37.1 to 262.0 (7.1x) |
| `trunc(d, fmt)`, format column | 13.3 to 25.9 (1.9x) | 12.6 to 27.8 (2.2x) |
| for scale: `next_day(d, s)`, weekday column (task 59) | 14.4 to 59.8 | 14.2 to 60.2 |

### 9.3 The predictions of 6.1, scored

1. **The register: about 100; the literal rows unmoved.** 91, and unmoved:
   the task 35 register holds at 36/45/62 and the SHA-256 of the emitted class
   for every literal level under both forms, plus the sibling extractions, is
   identical before and after the factoring (checked in a scratch run before
   commit 3; the register test is what stays). Hit, under the estimate for
   the reasons 3.3 records.
2. **0.55x-0.7x of `QUARTER` at 256 bits, no lower than 0.5x at 128.** 0.75x
   and 0.73x at 256, 0.79x and 0.77x at 128: better than the band on both
   counts. The extra ops are compares and blends, the cheapest kind, and the
   tail shares the year parts between its year and quarter results rather
   than paying `QUARTER`'s copy of `YEAR`'s.
3. **The leaf within 2x of task 59's row-engine parser; the parity ratio
   against the per-row anchor between 1.5x and 3x.** The first half holds
   exactly - 27.1 against 26.5, the same `toUpperCase` and a match - and the
   second **missed**: the ratio is about 1.2x at both widths (37.7 ns fused
   against 43.7 per row at 256 bits). The prediction counted the arithmetic
   as a real share of the row engine's cost; it is a few nanoseconds under a
   forty-nanosecond parse.
4. **Throughput 2x-4x Janino.** 1.9x at 256 bits, 2.2x at 128: at the floor
   of the band, for the reason prediction 3 missed. The row engine parses per
   row too, so fusion removes only the arithmetic and the row-by-row
   evaluation overhead, and the literal control's 8.1x is what the parse
   costs against a kernel.
5. **The literal `trunc` rows unmoved beyond the machine-day variance.** The
   three subtract-form controls in the parity file moved by at most 3.3%
   (`trunc YEAR`, mixed nulls) against the #121 baseline, inside the variance
   #121's two runs recorded.

### 9.4 What moved that the plan did not list

* The node's slots: the plan said three own slots; the build needs one (the
  level vector), because the four results ride the operand stack and the
  factored helpers only load and store named locals between them (3.1,
  amended).
* `emitChronoTrunc`'s three `SUBTRACT` results became four helpers
  (`emitTruncMonth`, `emitTruncYearParts`, `emitTruncYear`,
  `emitTruncQuarter`) that both nodes emit; the literal node's bytes did not
  move, which the hash check established and the register keeps.
* `TruncLevelLeaf` restates the four level codes as Java constants: the
  Scala values are `private[sql]` with no static forwarder, and its suite pins
  each to its definition.
* The compiler suite's task 35 decline test spelled its non-foldable case as
  a bare string column, which now fuses; it is `upper(fmt)` now.
* The parity run's unrelated rows carried the depressed-row artifacts every
  regeneration has shown since task 52 - one cluster per run, different rows
  each time (`sequential kernels`, the depth-4 chain, `datediff` hand-written,
  a shared-decomposition row) - while the task rows reproduced within 2%
  across the two runs. Recorded in the debt register.

### 9.5 What the task leaves for later

* **An ASCII fast path for the level leaf.** Task 59's ASCII weekday parser
  ran about twice its row-engine parser; the same shape here would take the
  fused row from about 38 ns to about 20 and the throughput ratio from 1.9x
  toward 3x. A measured change of its own, for the debt register, not built
  here on a guess.
* **A constant format column.** When every live lane of a batch carries one
  level, the dynamic tail still computes four; a per-batch check that picks
  the literal kernel instead is a scope note, since it needs a batch-level
  shape decision the evaluator does not make today.
* The filter's string-column compaction limitation (task 59) applies to
  this node the same way and is pinned by the same test; its entry stands.
