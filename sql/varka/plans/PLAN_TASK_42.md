# Task 42: `make_date(year, month, day)`

<!-- Rewritten on 4 September 2026 against master and PR #115. The recipe
     below the line is kept as the record: its three-outcome rule, its
     lowering and its tests held; what changed is the guard channel it
     assumed, the dense fast path it did not know about, the year limit it
     left open, and the compiler's int leaf. -->

## 1. Where this came from

`PLAN_MILESTONE_4.md` row 42. `make_date(y, m, d)` is the one date constructor
the corpus's helpers write, and the first expression in this engine that reads
three integer columns and can return null for non-null inputs. The recipe was
written before task 40 landed `emitDaysFromCivil`, before task 51 removed the
per-lane range guard it planned to reuse, and before task 52 (#115, mergeable
today) rebuilt that guard as a per-body accumulator at the producer. This plan
is the recipe re-read against those three facts.

## 2. The admission check, done

**The validity rule is the calendar's.** Over 2,184,000 `(year, month, day)`
triples - every day of six complete 400-year cycles and of the top of the
range, with months `-3..16` and days `-3..35` - "month in 1..12 and day in
1..length of the clamped month" agrees with `LocalDate.of`'s acceptance on
every one, with the month length as the closed form `30 | (m ^ (m >>> 3))`
for every month but February and `28 + leap` for February (`SKILLS.md`, the
`datealgo-rs` review; the twelve lengths checked). Clamping the month before
the length is what keeps the day test meaningful when the month is out of
range, and the month test has already condemned that lane.

**The recompose arithmetic is exact where this node will run it.**
`emitDaysFromCivil`'s scalar twin round-trips with `narrowed` over every day
of years 1 to 9999 and over the wider range `add_months` reaches, roughly
years -14848 to 35181 (`PLAN_TASK_40.md` 7, `verify_days_from_civil.py`,
after the task's correction of the `/100` and `/400` magics). The leap flag
is exact over reported years -15200..87299. The year limit this node guards
to (3.1) is the calendar range's whole years, -12799 to 33133, which lies
inside both.

**What the check would have rejected:** a validity rule that needed a month
table, or a year limit outside the recompose's proven range, in which case
a valid-looking date past the limit would have been a wrong day rather than
a declined batch.

**Found while checking, not in the recipe:** the dense fast path. Since task
45 a kernel whose inputs are null-free runs a dense body that writes no
per-lane validity - the driver fills the destination validity once - on
task 11's invariant that every node maps valid inputs to valid outputs. A
non-ANSI `make_date` breaks that invariant: `make_date(2024, 2, 30)` over
three non-null ints is null. Section 3.3 is the consequence.

## 3. The design

### 3.1 The node, its validity, and where its two masks go

**`MakeDate(VarkaVectorIR year, VarkaVectorIR month, VarkaVectorIR day,
boolean failOnError)`**, a plain record in the top-level `permits` (a
producer of a date, not a `Chrono` extraction), the flag shape-bearing on
`TruncDate`'s precedent, rendered `(makeDate:ANSI ...)` /
`(makeDate:NULL ...)`. Three children is `IfElse`'s shape: `childrenOf`,
`analyze` and the fuzzer's bound bookkeeping follow it.

**The lowering**, with the three inputs in slots after their `emitValue`:

    mc     = min(max(m, 1), 12)                          2 ops
    L      = emitLeapFlag(y)                             4 ops, a mask
    len    = blend(30 | (mc ^ (mc >>> 3)), 28 + L, mc == 2)   6 ops
    okM    = (m >= 1) & (m <= 12)                        3 mask ops
    okD    = (d >= 1) & (d <= len)                       3
    okY    = (y >= MIN_YEAR) & (y <= MAX_YEAR)           3
    valid  = okM & okD                                   1
    out    = emitDaysFromCivil(y, mc, d, ...)            about 30, task 40's

`out` is garbage wherever `valid` or `okY` is false and that is the contract:
a null lane's data is undefined, and a declined batch is recomputed whole.
`emitDaysFromCivil` takes its three operands from slots and leaves the day on
the stack, so the node's own slots are the three inputs, `mc`, `len`, the
two masks and the helper's eleven scratch locals: `MAKE_DATE_TMP_COUNT = 18`,
fresh named slots (task 36's lesson), and a weight of about 60, read off the
register.

**Two masks, two destinations.**

* The **decline mask** is `!okY` in both modes, plus `!valid` in ANSI mode -
  ANDed with the inputs' validity word in a masked body (a null input is not
  an invalid date; task 52's guard does the same AND for the same reason),
  ANDed with the epilogue mask when there is one, and ORed into the body's
  guard accumulator. The batch returns `STATUS_CHRONO_RANGE`, the evaluator
  recomputes it on the row engine, which returns the far date or raises
  Spark's own `SparkDateTimeException` (`DATETIME_FIELD_OUT_OF_BOUNDS`) at
  the same row - task 56's error-identity rule, no error machinery here.
* The **validity** of the output is the inputs' word AND `valid` in non-ANSI
  mode - the node computes its own word, as `IfElse` and `Greatest` do - and
  just the inputs' word in ANSI mode, where an invalid date never reaches
  the output.

**The year limit** is the calendar range's whole years: `MAKE_DATE_MIN_YEAR`
and `MAKE_DATE_MAX_YEAR` in `VarkaChrono`, derived in source from
`NARROW_MIN_DAYS` and `NARROW_MAX_DAYS` (the first whole year after the
range's start, the last whole year before its end: -12799 and 33133), not
from the recompose's wider exact range. Two reasons: every date the node
publishes then lies inside the narrow range, so under #115's analysis
`dayRange(MakeDate) = Bounded(first day of MIN_YEAR, last day of MAX_YEAR)`
and `year(make_date(y, m, d))` is admitted at compile time with no runtime
guard of its own; and a user asking for year 50,000 gets the row engine's
answer, which is the designed failure mode for a value the lowering does
not cover. Spark itself accepts years to about five million; declining past
twelve thousand costs no query in the corpus.

### 3.2 The guard accumulator: this task starts after #115

On master `Slots.guardAcc` is always null (task 51). #115 allocates it when a
body reaches a guarded producer and gives the emitter `emitProducerGuard`,
whose block - compare, AND with the validity word in masked bodies, AND with
the epilogue mask, OR into the accumulator - is the decline mask's shape
exactly. This task extends #115's `guardedProducers` rule by one clause: a
body containing a `MakeDate` allocates the accumulator whatever its
consumers, and the node's arm ORs its mask in through the same helper,
refactored to take the mask off the stack rather than compute the range
compare itself. Building a second accumulator on master and merging the two
would be the wrong order; the row's dependency is #115, and section 8 says so.

### 3.3 The dense path: a body with a non-ANSI `MakeDate` is masked

The dense body assumes valid in, valid out. Rather than teach it per-lane
validity stores, the analysis gains one flag, `nullsFromValidInputs`, set
when a body reaches a `MakeDate` whose flag is `NULL`; for such a body the
emitter generates the dense loop method as the masked body with every input
word all-true (`loadWord` already emits the constant for `WORD_ALL_TRUE`),
so the output word is stored per lane group as in any masked body, and the
driver's once-only validity fill is skipped for that kernel (the option
`denseValidityOnce` is answered false by the flag). Every other shape's
bytes are unchanged - the flag is false - which the suite asserts on method
sizes as task 52 did. The ANSI form never nulls a valid input and keeps the
dense body.

### 3.4 The compiler: an int operand, and a bounded date producer

`compileNode`'s column leaf stays `DateType`-only (task 38's rule); the three
children go through a new `compileIntOperand`: a foldable int literal becomes
a `LiteralSlot`, a bare `IntegerType` `BoundReference` a `ColumnRef`, and
anything else declines with "make_date argument is not an int column or
literal" - `compileOffset`'s shape without its interval cases. Task 63 will
widen what an int operand can be; this helper is what it widens. The arm:

    case MakeDate(y, m, d, failOnError) =>
      for (yy <- int(y); mm <- int(m); dd <- int(d))
        yield new IRMakeDate(yy, mm, dd, failOnError)

`failOnError` is captured on the expression from `spark.sql.ansi.enabled` at
analysis, so the mode is a compile-time fact and two modes are two shapes.
The output type is `DateType`, from the expression, as `LastDay`'s is. Under
#115, `dayRange` gains the `Bounded` arm of 3.1.

### 3.5 What is deliberately unchanged

* `emitDaysFromCivil`, `emitLeapFlag`, `emitMonthStart`: called, not edited.
* `try_make_date`: does not exist in this Spark; nothing to do.
* `make_timestamp` and the interval constructors: int64 lanes, milestone 5.
* The year limit is not widened past the calendar range; task 49's long lanes
  are where a wider constructor would live.

### 3.6 Registered op counts

Dense-loop `IntVector` calls, the register's metric, to be read off the
emitted bytes: `make_date` about 55 to 60 (the lowering above), the ANSI and
`NULL` forms within two ops of each other (the `NULL` form stores a word the
ANSI form does not), `add_months` and `trunc` unmoved.

*Read off the bytes, the same day:* 57 for both forms in the dense loop and
57 in the masked loop as well - the forms differ by a `long` AND and a mask
`not`, neither an `IntVector` call, so the counter cannot tell them apart -
and `add_months` at 112 (the 117 the register first carried was task 40's
count before tasks 53 and 54), `dayofyear` at 43, both unmoved.

### 3.7 Corrected while building: the masked dispatch, not a re-emitted body

Section 3.3 had the dense loop method re-emitted as a masked body with
all-true words. The survey of the body mechanics showed a shorter route: the
masked body already treats a null-free input as a constant all-true word
without touching its buffer (its per-input null state decides that, per
batch), so a kernel whose analysis says `nullsFromValidInputs` simply emits
no dense methods and its `run` dispatches every batch to `runMasked`. The
ANSI form keeps the dense path. The test in section 5 - a null-free batch
with one invalid date under the `NULL` form yielding a null lane - holds
either way, and the method-layout test pins that the `NULL` form's class has
no `runDense`.

## 4. Files

| file | what |
|---|---|
| `VarkaChrono.java` (+ suite) | `MAKE_DATE_MIN_YEAR`/`MAX_YEAR` derived from the narrow range; a scalar twin `makeDate(y, m, d)` returning the day or a sentinel, swept against `LocalDate.of` |
| `VarkaVectorIR.java` | the record, both renderings with the mode |
| `VarkaLoopEmitter.java` | `MAKE_DATE_WEIGHT`, `MAKE_DATE_TMP_COUNT`; `childrenOf`, `analyze` (three int children), `planWordRef` (own word in `NULL` mode, the AND of three in ANSI), `planSlots`, `emitValue`; the guard helper taking a mask; the `nullsFromValidInputs` flag and the masked dense body |
| `VarkaReferenceEvaluator.scala` | `LocalDate.of` in a `try`, `None` on `DateTimeException`, the definition |
| `VarkaLoopEmitterSuite.scala` | the validity matrix in both modes; the decline tests on status; the dense null-free invalid-date test; the year-limit test; the register; byte-identity of every other shape; both fixtures re-pinned |
| `VarkaIrFuzzSuite.scala` | the round-trip arm `MakeDate(Year(a), Month(a), DayOfMonth(a))` in both modes, and a `NULL`-mode arm with a literal day |
| `VarkaShapeCacheSuite.scala` | the hash re-pinned |
| `VarkaExpressionCompiler.scala` (+ suite) | `compileIntOperand`, the arm, the `dayRange` arm (#115), the decline reason |
| `VarkaSharedSessions.scala`, `VarkaDifferentialSuite.scala` | a `withAnsi` helper over both sessions; the differential in both modes, the error identity under ANSI |
| `VarkaEmitterParityBenchmark.scala` + files | the three-input runner; `make_date` null-free and mixed, both modes, and the per-row `LocalDate.of` anchor |
| `docs/sql-varka.md` | the surface bullet, with the year limit and the two modes |
| `PLAN_MILESTONE_4.md`, this file | row 42, section 9 |

## 5. Tests, and what each is for

* **Oracle**: `LocalDate.of(y, m, d)` in a `try`, `toEpochDay` on success,
  `None` on `DateTimeException` - the definition; the fuzzer and matrices
  inherit it.
* **The validity matrix, both modes**: valid dates; month 0, 13 and negative;
  day 0, 32, and 29/30/31 in months that lack them; 29 February in a leap
  year and in a common year and in 1900 and 2000; nulls in each input alone
  and together; every length and null pattern. In `NULL` mode the invalid
  rows are null outputs and the status is 0; in ANSI mode the same rows make
  the kernel return `STATUS_CHRONO_RANGE`, asserted on the status the way
  task 52's guard tests are, with no output published.
* **The dense path**: a null-free batch with one invalid date under the
  `NULL` form returns that lane null - the test the old recipe could not have
  written, because the dense body did not exist.
* **The year limit declines in both modes**, at `MIN_YEAR - 1` and
  `MAX_YEAR + 1`, and `MIN_YEAR-01-01` and `MAX_YEAR-12-31` are answered;
  and a year past the limit with an invalid month declines rather than nulls,
  which is the recipe's three-outcome distinction as a test.
* **Byte identity**: every kernel without a `MakeDate` has the same method
  sizes with the flag machinery in place.
* **Constants and twin**: the two year constants against `LocalDate`; the
  scalar twin over every day of the covered years, opt-in, and at the
  invalid corners.
* **Compiler**: columns and literals in each position, both modes, the
  `DateType` output; a non-int argument declining with the reason; under
  #115, `year(make_date(y, m, d))` admitted and its range arm.
* **Differential**: a cached table of three int columns with nulls and
  invalid combinations; in `NULL` mode the projection, the same date fed to
  `year` and `date_add`, and a filter on the result; in ANSI mode the valid
  table matching, and the invalid table raising the same `getCondition` and
  `getMessage` through both sessions (task 56's pattern).
* Both pinned fixtures move once, re-pinned from their output.

## 6. The measurement

`VarkaEmitterParityBenchmark`, a `make_date` section: the `NULL` and ANSI
forms as an adjacent A/B, null-free and mixed nulls, over three int columns
of valid dates (a three-input `chunked` walk beside the two-input one), the
`add_months` row as the sibling control, and a per-row
`DateTimeUtils.localDateToDays(LocalDate.of(...))` anchor; both widths,
one regeneration on an idle machine. The throughput benchmark gains a
`make_date(y, m, d)` row over a three-int-column table.

### 6.1 Predictions, registered before the run

1. The register: 55 to 60 for either form, within two of each other.
2. Both forms run at 1.6x to 2x `add_months`'s rate at 256 bits (about half
   the ops) and no lower than 1.4x at 128 bits.
3. At least 8x the per-row `LocalDate.of` anchor at 256 bits: the row path
   allocates a `LocalDate` per row.
4. The ANSI form is within 3% of the `NULL` form null-free, and the `NULL`
   form is the slower one with mixed nulls, by the word store.
5. No other row moves beyond the machine-day variance already recorded.

## 7. Risks

1. **The dense-path flag reaches a shape it should not.** Asserted by the
   byte-identity test over every other node; the flag is set by one record.
2. **A garbage lane's arithmetic traps.** No lane op traps; the only
   division is a magic multiply. The matrix over out-of-range inputs holds it.
3. **`emitLeapFlag` off its domain.** Only lanes `okY` rejects can be
   there, and they decline; the year-limit test at `MIN_YEAR - 1` covers the
   edge.
4. **#115's merge order.** This task starts after it, per 3.2.
5. **The ANSI differential's session state.** `withAnsi` sets and restores
   the conf on both sessions in a `finally`; a leaked setting would show as
   every later differential failing, which is loud.

## 8. Sequencing

0. #115 merged.
1. Constants, twin and suite; the record and renderings.
2. The emitter: the arm, the guard helper taking a mask, the dense flag; the
   oracle; the matrix, status, dense, limit and identity tests; fixtures.
3. The compiler and its suite; `withAnsi`; the differential; the docs.
4. The benchmark section, one regeneration, section 9, row 42.

## 9. Outcome

Built as sections 3-5 describe. `VarkaEmitterParityBenchmark` regenerated at
both widths overnight by `dev/varka_bench_regen.sh` on the idle machine under
the `performance` governor (load 0.71 at start, canary ok), read against the
#121 baseline measured on unchanged master under the same profile;
`VarkaThroughputBenchmark` regenerated the same morning with the corrected
class path. Rates in M rows/s from the committed files.

| case | 256-bit | 128-bit |
|---|---|---|
| `make_date, NULL form (task 42 A/B), null-free` | 1667.4 | 606.7 |
| `make_date, ANSI form (task 42 A/B), null-free` | 1692.1 | 717.7 |
| `make_date, NULL form (task 42 A/B), mixed nulls` | 1484.6 | 569.8 |
| `make_date, ANSI form (task 42 A/B), mixed nulls` | 1453.5 | 543.6 |
| `add_months(d, 13), null-free` (the sibling control) | 733.5 | 254.9 |
| per-row `LocalDate.of` `make_date` | 224.6 | 226.3 |
| throughput `make_date(y, m, d)`, varka / Janino | 181.1 / 27.7 (6.5x) | 169.4 / 28.7 (5.9x) |

End to end (`VarkaThroughputBenchmark`, the 2M-row three-int-column table,
load 0.83 at start, canary ok), `make_date` runs 6.5x the Janino row path at
256 bits and 5.9x at 128; 22 unrelated rows moved by 3% or more against
#121, between -11% and +22%, the run-to-run floor.

**Predictions scored.**

1. *The register: 55 to 60 for either form, within two of each other.* 57
   for both forms, dense and masked alike; the suite asserts it.
2. *1.6x to 2x `add_months`'s rate at 256 bits, no lower than 1.4x at 128.*
   Better than the band: 2.27x (`NULL`) and 2.31x (`ANSI`) at 256 bits, 2.38x
   and 2.82x at 128. About half the ops bought more than half the time,
   because `add_months` recomposes through the same `emitDaysFromCivil` after
   a decomposition this node does not need.
3. *At least 8x the per-row `LocalDate.of` anchor at 256 bits.* Missed, at
   7.4x and 7.5x: the anchor runs at 224.6 M rows/s, faster than the
   `LocalDate`-per-row paths the other anchors set (about 100 for
   `getDayOfWeek`, 53 for `getWeekOfYear`), so the ratio is the anchor's, not
   the kernel's. 2.7x and 3.2x at 128 bits.
4. *`ANSI` within 3% of `NULL` null-free; `NULL` the slower one with mixed
   nulls, by the word store.* Half held. Null-free at 256 bits, +1.5% - held;
   at 128 bits `ANSI` is 18% faster, outside the 3%. With mixed nulls `NULL`
   is the *faster* form at both widths, by 2% and 5%: the word store did not
   cost what the prediction charged it, and the `ANSI` form's second mask
   (the throw mask, computed either way) is what the mixed rows pay for.
   Neither gap changes the decision - the shipped default is what SQL mode
   selects, not what the A/B prefers.
5. *No other row moves beyond the machine-day variance already recorded.*
   Held: 37 rows moved by 3% or more against #121, between -17% and +9%,
   with the controls flat; #121's own two runs of identical master code
   moved 47 rows by 3-13% against each other.

What moved that the plan did not list: nothing in the code. The regen tool's
default class path does not reach `VarkaThroughputBenchmark`'s package, which
cost the overnight throughput run (the corrected invocation names the class
in full). Nothing left for later beyond the 128-bit `ANSI`/`NULL` gap in
prediction 4, which is an observation about the two masks' cost at the
narrow width, not a defect.

---

## The recipe before the re-plan (superseded, kept as the record)

# Task 42: `make_date(year, month, day)`

A recipe for a cheap agent, in the shape task 33 established. Read
`PLAN_TASK_33.md` section 3 for the mechanics of adding a node type.

**Depends on task 38** (an integer column has to be readable at all) and on
**task 40** (`emitDaysFromCivil`). It needs none of 28, 29 or 30 - everything
here is int32.

It is the first expression that reads **three** integer columns, and the first
whose result can be **null for a non-null input**. Both of those are why it is
worth writing down carefully.

If you find yourself making a design decision, stop and say so in the pull
request instead of choosing.

## 1. What you are building

`make_date(y, m, d)` builds a date from three integers. Spark's semantics:

```scala
if (failOnError) {
  DateTimeExpressionUtils.makeDateExact(year, month, day)   // throws
} else {
  try { localDateToDays(LocalDate.of(year, month, day)) }
  catch { case _: java.time.DateTimeException => null }
}
```

`LocalDate.of` rejects a month outside 1-12, a day outside 1 to the month's
length (28, 29, 30 or 31 as the case may be), and a year outside its own huge
range. `failOnError` is `SQLConf.get.ansiEnabled`, captured on the expression,
so it is **known at compile time**.

So there are two different behaviours for the same bad input, and the whole
task turns on keeping them apart.

## 2. Three outcomes, not two

This is the part to read twice. A lane can be in one of three states, and they
are not the same:

| state | example | what must happen |
|---|---|---|
| a null input | `make_date(NULL, 1, 1)` | null output, both modes - ordinary validity |
| a **valid** date | `make_date(2024, 2, 29)` | the date |
| an **invalid** date | `make_date(2024, 2, 30)` | **null** in non-ANSI, **throw** in ANSI |
| a year outside what this engine covers | `make_date(500000, 1, 1)` | **decline the batch**, in *both* modes |

The last two rows are the trap. An invalid date is a **semantic** result - SQL
says what it means, and the kernel must produce it. A year past what the
lowering's magic multiplies cover is an **engine limitation** - SQL has an
answer and this kernel cannot compute it, so the batch declines through task
26's status channel and the row engine answers it correctly. Confusing the two
gives wrong answers in one direction and spurious errors in the other.

In ANSI mode the invalid-date case *also* declines, because a lane cannot
throw: the row engine then raises the exception, at the right row, with the
right message, because it is the row engine. That is the same trick task 39
uses, and it means this task needs no error machinery of its own.

## 3. The lowering

`emitDaysFromCivil` from task 40 does the arithmetic. This task adds the
validity around it:

```
mp     = (m + 9) mod 12                    // March-based month; task 40 computes this
length = mp < 11 ? cum(mp + 1) - cum(mp) : 28 + L      // as task 36 does
okM    = m >= 1 && m <= 12
okD    = d >= 1 && d <= length
okY    = y >= YEAR_MIN && y <= YEAR_MAX    // the engine's own limit, section 3.1
valid  = okM && okD
out    = emitDaysFromCivil(y, m, d)        // computed unconditionally
```

`out` is garbage where `valid` is false, and that is fine: the kernel contract
says the data of a null output row is undefined. Do not branch, do not blend a
safe value in.

Then, by mode:

* **non-ANSI**: the output's validity word is the inputs' validity AND `valid`.
  This node therefore **computes its own word** rather than aliasing a child's
  - `Greatest` and `IfElse` already do that, so follow one of them in
  `planWordRef` and the word-emitting code.
* **ANSI**: the output's validity is just the inputs', and `!valid` (where the
  inputs are non-null) is ORed into the guard mask so the batch declines.

`!okY` is ORed into the guard mask in **both** modes.

`length` must be computed from the *given* month even when that month is out of
range, or `okD` reads a nonsense length. Clamping `m` into 1-12 before
computing `length` is the simplest way and is correct, because `okM` has
already recorded that the row is invalid.

### 3.1 The year limits

`emitDaysFromCivil`'s magic multiplies are exact only up to a bound; take the
limits from task 40's constants rather than inventing them, state them as named
constants in `VarkaChrono`, and **prove them in a test** rather than trusting
this file - task 40's own recipe gives the exactness bounds for each division.
If the bound turns out narrower than the range task 26 already covers, say so
in the pull request: that would be worth knowing for tasks 34-37 too.

## 4. The edits

Mechanics per `PLAN_TASK_33.md` section 3. Specifics:

* **IR**: `MakeDate(VarkaVectorIR year, VarkaVectorIR month, VarkaVectorIR day,
  boolean failOnError)` - three children plus a **shape-bearing** flag, the way
  task 35's `TruncDate` carries its level, because the flag chooses which code
  is emitted. Render as `(makeDate:<ANSI|NULL> <y> <m> <d>)`.
* **Emitter**: the four routine cases plus an `emitValue` arm; `planWordRef`
  returns "computes its own word" for the non-ANSI form and the AND of the
  three children for the ANSI form. Three children is a first for a value node
  - `IfElse` is the only other one - so copy its shape rather than a binary
  node's.
* **Compiler**: `case MakeDate(y, m, d, failOnError) =>` compiling all three
  children. Note that the children are `IntegerType` **columns or literals**;
  both must work, and a literal one goes through the same `compileNode` path.

## 5. The tests

1. `evalValue` gains a `MakeDate` arm whose oracle constructs `LocalDate.of`
   inside a `try` and yields `None` on `DateTimeException` - the definition,
   not your predicate.
2. **The validity matrix, which is the heart of the task**: valid dates; month
   0, 13 and negative; day 0, 32, and 29/30/31 in months that do not have them;
   29 February in a leap year (valid) and in a common year (invalid); nulls in
   each of the three inputs separately and together. Every one of these at both
   settings of `failOnError`.
3. **The two modes differ and must be tested apart**: in non-ANSI an invalid
   row is a null output and the batch still runs; in ANSI the batch declines
   and no output is published.
4. **The year limit declines in both modes**, and is not confused with an
   invalid date - a test with a year past the limit must decline, not null.
5. Differential: `SELECT make_date(y, m, d)` over a cached table with three
   int columns including nulls and invalid combinations, run under both
   `spark.sql.ansi.enabled` settings, with the ANSI run asserting the same
   exception as the row engine by running both rather than by naming a class.
6. The two pinned fixtures, extended and re-pinned.

Then task 33's section 4 command block, unchanged, at both widths.

## 6. Explicitly out of task 42

* **`make_timestamp`, `make_interval`, `make_dt_interval`** - int64 lanes and,
  for the timestamp forms, the timezone question.
* **`try_make_date`**, if it exists in this Spark version - it is a different
  expression with a third error behaviour, and one task should not carry three.
* **Widening the year range** beyond what task 40's magics support. Section 3.1
  says to record the limit, not to raise it.

## 7. Outcome

Filled in when the work lands, including which steps misled you. Say in
particular whether section 2's three-way distinction was clear before you hit
it, because that is the distinction this recipe exists to make.
