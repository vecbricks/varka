# VARKA-656: `TIMESTAMP_LTZ` on the long lane

*Planned on 27 November 2026. This file stands alone: a builder needs it and the repository's
`AGENTS.md` files. The row is not started, since milestone 9 waits for the owner's word
(`m9/PLAN.md` decision 18), and the row itself waits for milestone 8's close (decision 11). Its
code facts were checked on master `a0e3a62cf2b`. One run of `dev/varka_emit.sh` was made on an
older worktree, at `93625e9c721`; section 3 says which fact it gave. Revised the same day
after a builder's review and against row 654's plan, `VARKA-654.md` of `sql/varka/plans/m9/`,
which merges first and is the reference for the census.*

## 1. What this row is

**The row's text** (`m9/PLAN.md` section 3): "`TIMESTAMP_LTZ` on the long lane. **Filed** (25
November 2026; the admission check: the registry declines the type, and `types.tsv` reads
`TIMESTAMP declined`) Section 2.3. **Done when** the registry has the type on the long lane; a
`TimeStampMicroTZVector` is read and written, with the session zone in the output's field; the
load, the comparisons, the null tests, `IN`, `greatest`, `least`, the choices and the output
fuse with coverage rows; the conformance suite passes; `dev/varka_new_lane.py --check` passes;
and no existing shape's bytes move".

Spark's `TIMESTAMP` (its SQL alias is `TIMESTAMP_LTZ`, its class `TimestampType`) is a long of
microseconds since the epoch in UTC. Varka declines it today by a recorded decision. This row
puts it on the long lane, where `BIGINT`, `TIME`, the day-time interval and `TIMESTAMP_NTZ`
already are, and fuses every expression over it that reads no time zone and needs no new IR
node. Every other expression over it declines with a reason that names the row that fuses it.

**What it gives the rows after it.**

| row | what it reads of this row |
| :--- | :--- |
| 657, the session time zone in a kernel | a `TIMESTAMP` column leaf on the long lane; the type's Arrow vector in and out; the columns `ts` and `ts2` of the coverage table and of both fixtures; `VarkaTimestampConformanceSuite`; the column types `timestamp` and `timestamp_ntz` of `dev/varka_emit.sh`; the four declines it takes over, with their texts (4.3) |
| 667, the zone-free fan-out | the lane and the fixtures; the declines by name of the six epoch conversions, the two hashes, `known_not_null` and `try_eval`, each of which the task turns into an arm |
| 669, the casts | the lane; the decline by name of each of the 22 cast pairs that are no identity; the identity cast's arm |
| 668, 670, 671 | the lane; their declines by name |
| 682 to 690 | nothing directly: they test over a `BIGINT` column or through row 657 |
| 684, the proof | the column `ts` of the Spark fuzzer's fixture, derived from `d`; the type's entry in `VarkaFuzzTypes` |

**What must be merged before it starts.** Milestone 8's close (decision 11). Row 663, the
upstream sync, which is merged. Row 654, both parts: the census and the cast table of
milestone 9 with the type `ltz` (section 12 cites what this plan reads of `VARKA-654.md`).
Row 655: the baseline class with the zone-free `TIMESTAMP` sections and both widths' results.
This plan itself, merged by the lead before part A. Rows 678 and 679 are not needed.

**The goals it serves** (`m9/PLAN.md` 1.1), each quoted, with the item of 1.2 that it moves and
how a reader checks the move.

| goal, as `m9/PLAN.md` has it | the item of 1.2, quoted | what the row moves, and the check |
| :--- | :--- | :--- |
| "**G1. `TIMESTAMP_LTZ` at microseconds (phase 1).** It is a census type. The entries that read no time zone fuse over it: choice, null tests, comparisons, `IN`, `greatest`, `least` and the hashes, as they do over `TIMESTAMP_NTZ`, and the conversions between a timestamp and a count since the epoch (`timestamp_seconds` and its kin, and the `unix_*` functions)." | "**G1:** at least three quarters of `TIMESTAMP_LTZ`'s (entry, type) pairs fused (about 66 entries are expected to admit it)." | 23 of the type's pairs become fused: the 22 cells of 4.8, and the cast alias `timestamp`, which the census credits with its identity pair once a kernel reads the type (`VARKA-654.md` 4.9). Check: the line of `ltz` in `python3 dev/varka_census.py --summary` reads `0 / N` before part B and `23 / N` after it; `N` is 70 on the plan's commit |
| the same goal | "Every pair left unfused carries its reason in the census: a declined band, an owner's decision, a function Spark itself rejects over the type, or a named row of the next milestone." | every unfused expression over a `TIMESTAMP` declines in the compiler with a text that names its row (4.3). Check: `VarkaZonedDeclinesSuite` and `VarkaTimestampTwinSuite` pass. The census's own reason column is row 654's |
| "**G3. The remaining datetime features (phase 1).**" | "at least two thirds of the microsecond temporal casts fused or declined with their reason: the 42 legal pairs of `m8/casts.tsv` with a temporal side, plus `TIMESTAMP_LTZ`'s pairs row 654 adds (23)." | the 22 pairs that are no identity decline with a reason that names the row `VARKA-654.md` 4.8 gives the pair: 657 for the cast to `DATE`, 690 for the casts from `DATE` and from `TIMESTAMP_NTZ`, 669 for the others. The identity is no cast in any plan; a tree built by hand that holds one compiles to its child. Check: `VarkaZonedDeclinesSuite`'s cast test (all 22 forms by exact text), the identity's test in `VarkaExpressionCompilerSuite`, and the record of Spark's cast suites after part A |
| "**G7. Speed where a time zone is read.** A fused `TIMESTAMP_LTZ` expression runs significantly faster than vanilla Spark: than the row path of the same build, section by section, and than the newest stock Spark release on a timestamp surface. No fused shape is slower than the row path on a measured host; a shape that would be declines there." | "every `TIMESTAMP_LTZ` section whose kernel reads the zone runs at least five times the row path's rate and every other section at least twice; no section is slower than the row path at 256 or at 128 bits" | the zone-free sections of row 655's class are the "every other section" of this item. Part D measures them and scores section 7's predictions. Check: the committed results files of both widths |
| "**G2. Every time zone, proven.**" | item 2 | only through later rows. This row's kernels read no zone, so their answers are the same in every zone; `VarkaTimestampSuite` holds that under three zones |
| "**G5. Delivery by cheaper models.**" | item 8 | only through row 667, whose tasks start from this lane |
| G4, G6 | items 4 and 9 | only through later rows: a nanosecond `TIMESTAMP_LTZ` takes this lane's forms on its microsecond word (rows 660 to 662), and the post quotes the census (row 676) |

## 2. What an agent must know

### 2.1 The idea

A `TIMESTAMP` value is an instant: a signed long of microseconds since 1970-01-01 00:00:00 UTC.
A `TIMESTAMP_NTZ` value is a local date and time with no zone: a signed long of microseconds
since 1970-01-01 00:00:00 of that local time. The two are the same eight bytes, and Varka's IR
does not know which one a column holds: a column is an ordinal and a lane.

Comparing two instants, testing one for null, choosing between two and storing one are
operations on the long. They read no time zone, and the kernel that does them for a `BIGINT`
does them for a `TIMESTAMP`. So this row writes almost no kernel code. It changes one registry
entry, names the type's Arrow vector, carries the session zone's name to the output vector, and
adds the rows, fixtures and suites that make the type count.

Reading a field of an instant (`hour(ts)`), casting it to a date, adding a day to it or
subtracting two instants reads the session time zone: Spark does those on the local time in
that zone. A `TIMESTAMP_NTZ` kernel computes them on the count as it is, which is right at UTC
only. So the danger of this row is one: **an expression over a `TIMESTAMP` that reads the zone
must never be compiled by the `TIMESTAMP_NTZ` arm of the same expression.** Each such
expression declines by name here, and row 657 and the rows behind it fuse them.

### 2.2 Terms

- **Lane**: the vector element type a kernel computes a value in. `LaneType.LONG` is 64 bits.
  **Kernel**: the emitted vector loop of a projection or a filter. **Row path** or **row
  engine**: Spark's own evaluation, row by row.
- **Entry**: one expression of a projection, or one conjunct of a filter. **Fused**: an entry a
  kernel computes. **Declines by name**: the compiler refuses an entry with a reason text; the
  entry runs on the row path, and verbose `EXPLAIN` shows the text. **A batch declines**: at run
  time a kernel refuses one batch, and the row path answers that batch alone. No kernel of this
  row declines a batch: it has no guard.
- **The registry**: `VarkaTypes`, one entry for each type Varka knows. An entry's **admission**
  says how a column of the type is read: `OnLane(lane)`, `Owed(row)` (planned, the row builds
  it), `Declined(decision)`, and two more this row does not use. `VarkaTypesSuite` writes the
  registry to `sql/varka/types.tsv`, which the Python tools read.
- **Census**: `dev/varka_census.py`, the table of (entry, type) pairs and which are fused. Its
  name for this type is `ltz`; `ntz` is `TIMESTAMP_NTZ`.
- **Coverage row**: a row of `VarkaCoverageSuite`'s table, generated into
  `sql/varka/coverage.json` and `docs/sql-varka.md`. Without one, an entry does not count as
  fused. A **family** there is a titled group of rows.
- **Conformance suite**: `Varka<Type>ConformanceSuite`, the contract of `VarkaTypeConformance`
  (load, round trip, nulls, ordering, choice, casts, overflow) run over one type.
- **Zone-free**: an expression whose answer does not depend on the session time zone.
  **Zone-reading**: one whose answer does.
- **Twin**: the same SQL with `ts` for `ntz`, `ts2` for `ntz2` and `TIMESTAMP` for
  `TIMESTAMP_NTZ`.
- **Shape**: the IR of a kernel with its options. Two projections with one shape share one
  emitted class. A shape holds no Spark type.

### 2.3 The names this row reads, and the names it adds

Read, as they are on master:

| name | where | meaning |
| :--- | :--- | :--- |
| `VarkaTypeId.TIMESTAMP`, `VarkaTypes.info`, `VarkaTypes.lane`, `VarkaTypes.isNtz` | `<ir>/VarkaTypes.java` | the registry and its questions |
| `VarkaLaneArrow.of`, `Allocate`, `allocate`, `reads` | `<exec>/VarkaLaneArrow.java` | the Arrow vector of each type, in and out |
| `VarkaNodeCompiler.fallback`, `floatingDecline`, `arithmeticArm` | `<gen>/VarkaNodeCompiler.java` | what no arm claimed; the floating gate; the arm that holds the identity casts |
| `VarkaConditionCompiler.compileCond`, `nullLeaf`, `inArm` | `<gen>/VarkaConditionCompiler.java` | a condition's compile; a choice's null; `IN` |
| `VarkaFuzzTypes.of` | `<gent>/varka/VarkaFuzzTypes.java` | which fuzzer draws which type |
| `VarkaTypeConformance` | `<gent>/varka` | the conformance contract |

Added by this row:

| name | where | part | meaning |
| :--- | :--- | :--- | :--- |
| `VarkaTypes.isLtz(DataType)` | `<ir>/VarkaTypes.java` | A | whether a type is the microsecond `TIMESTAMP`; the twin of `isNtz` |
| `VarkaZonedDeclines.reason(Expression)`, `notYet(String, int)` | `<gen>/VarkaZonedDeclines.java`, new, package-private | A | the reason an unclaimed expression over a `TIMESTAMP` declines with (4.3) |
| the registry's entry: `Owed("656")`, then `OnLane(LaneType.LONG)` with `Conformance.Suite("Timestamp")` | `<ir>/VarkaTypes.java` | A, then B | the type's admission. Its census name `"ltz"` is row 654's |
| `Allocate.create(String, DataType, String timeZone, BufferAllocator)`, `VarkaLaneArrow.allocate(String, DataType, String timeZone, BufferAllocator)` | `<exec>/VarkaLaneArrow.java` | B | an output vector built under a zone's name |
| `VarkaKernelEvaluator(..., String sessionTimeZone)`, a tenth constructor argument | `<exec>/VarkaKernelEvaluator.java` | B | the zone's name, carried to `allocateVector` |
| the columns `ts`, `ts2` | the coverage table and the two fixtures | B | two `TIMESTAMP` columns, the same counts as `ntz` and `ntz2` |
| the column types `timestamp`, `timestamp_ntz` | `dev/varka_emit.sh` | A | `--columns ts:timestamp` |
| `VarkaZonedDeclinesSuite` | catalyst, Java, JUnit 5, in `<gent>`, package `org.apache.spark.sql.catalyst.expressions.codegen` | A | the declines by name |
| `VarkaTimestampTwinSuite` | the same module, language, directory and package | A2 | no zone-reading form computed as its twin |
| `VarkaTimestampConformanceSuite` | catalyst, Java, JUnit 5, in `<gent>/varka`, package `org.apache.spark.sql.catalyst.expressions.codegen.varka` | B | the type held to the contract; the fifteenth conformance suite |
| `VarkaTimestampSuite` | sql/core, ScalaTest, in `<execs>`, package `org.apache.spark.sql.execution` | C | the lane through queries, under three zones |

Paths: `<gen>` is `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen`,
`<ir>` is `<gen>/varka`, `<exec>` is `sql/core/src/main/java/org/apache/spark/sql/execution`,
`<plan>` is `sql/core/src/main/scala/org/apache/spark/sql/execution`. `<gent>` and `<gens>` are
`<gen>` under `src/test/java` and `src/test/scala`; `<exect>` and `<execs>` are `<exec>` under
`src/test/java` and `src/test/scala`. `<spark>` is
`sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst`. `$M9` is `sql/varka/plans/m9`.

## 3. The admission check

Each fact was checked on `a0e3a62cf2b` by the command beside it, run from the repository's
root. In these tables a `\|` inside a quoted grep pattern is grep's "or" and is typed as
printed; a `\|` between two commands stands for the shell's pipe, `|`.

**The type today.**

| fact | command | prints |
| :--- | :--- | :--- |
| The registry declines the type, with no census name | `grep -P "^TIMESTAMP\t" sql/varka/types.tsv` | `TIMESTAMP`, `TimestampType`, `-`, `TIMESTAMP`, `declined`, then `-`, `-`, `64`, `plain` and four `-` |
| Four switches over `VarkaTypeId` hold a `TIMESTAMP` arm | `grep -n "case TIMESTAMP ->" <ir>/VarkaTypes.java <gen>/VarkaConditionCompiler.java <exec>/VarkaLaneArrow.java <gent>/varka/VarkaFuzzTypes.java` | four lines: the entry, `nullLeaf`, `VarkaLaneArrow.of`, `VarkaFuzzTypes.of` |
| The fuzz entry reads the registry's decision and throws for any other admission | `grep -c "private static <T> Site<T> declined" <gent>/varka/VarkaFuzzTypes.java` | `1` |
| `dev/varka_new_lane.py` knows a type only once it is on a lane | `python3 dev/varka_new_lane.py --check \| grep -c TimestampType` | `0` |
| On a lane, a type needs a coverage column and a census entry fused today | `grep -n "so a coverage row can read one\|an entry fused today for" dev/varka_new_lane.py` | two lines |
| A type has an Arrow vector exactly when a lane reads it | `grep -n "a type has an Arrow vector exactly when a lane reads it" <execs>/VarkaLaneArrowSuite.scala` | one line, a test's name |

The last three facts set the order of the parts: the type cannot go on the lane before its
Arrow vector, a coverage column and a fused census entry exist, and it cannot have an Arrow
vector while it is off the lane. So part A keeps it off the lane, as `Owed("656")`, and part B
moves everything at once.

**What is keyed by the lane, and so fuses when the entry changes.**

| fact | command | prints |
| :--- | :--- | :--- |
| The long lane's column leaf and literal ask the registry for the lane, not for a type | `grep -n "VarkaTypes.lane(br.dataType()).orElse(null) == LaneType.LONG\|VarkaTypes.lane(l.dataType()).orElse(null) == LaneType.LONG" <gen>/VarkaTimeCompiler.java` | two lines, in `VarkaTimeCompiler.arm` |
| `IN` asks the entry's admission; an owed type is not claimed | `grep -n "case VarkaTypes.Admission.Owed owed -> null" <gen>/VarkaConditionCompiler.java` | one line, in `inArm` |
| A shape holds no Spark type | `grep -n "record VarkaShapeKey" -A3 <ir>/VarkaShapeKey.java` | `outputs`, `numInputs`, `numLiterals` |
| The harness reads a `TIMESTAMP` cell as a long already | `grep -n "t instanceof TimestampType" <ir>/VarkaSameAnswer.java` | one line, in `valueOf` |

**What is keyed by the type, and so does not fuse.**

| fact | command | prints |
| :--- | :--- | :--- |
| Every `TIMESTAMP_NTZ` arm asks `VarkaTypes.isNtz` | `grep -c "VarkaTypes.isNtz" <gen>/VarkaNodeCompiler.java <gen>/VarkaChronoCompiler.java <gen>/VarkaTimeCompiler.java` | `4`, `3` and `9` |
| The integral, floating and decimal cast families ask for exact types | `grep -n "DataTypes.LongType.equals(type)" <gen>/VarkaIntegralCastCompiler.java <gen>/VarkaDecimalCastCompiler.java` | two lines, each in `integralLane` |
| `BIGINT` arithmetic asks for `LongType`, not for the lane | `grep -n -A2 "private static boolean isLong" <gen>/VarkaNodeCompiler.java` | `DataTypes.LongType.equals(e.dataType())` |
| The hash's word has no arm for the type | `grep -n "case TimestampNTZType n" <gen>/VarkaHashCompiler.java` | one line; no `TimestampType` beside it |
| What no arm claims declines with a general text | `grep -n 'sink.decline("unsupported expression", e)' <gen>/VarkaNodeCompiler.java; grep -n 'sink.decline("unsupported predicate", expr)' <gen>/VarkaConditionCompiler.java` | one line each: `fallback` and `compileCond` |
| A cast with a floating side declines before any arm, and a floating source is named before a general cast | `grep -n "case Cast c when isFloating(c.child().dataType())" <gen>/VarkaNodeCompiler.java` | one line, in `floatingDecline` |
| `months_between` answers a `DOUBLE`, and `timestamp_seconds` takes any numeric operand, a `DOUBLE` among them; so the floating check meets both before any family | `grep -n -A20 "^case class MonthsBetween" <spark>/expressions/datetimeExpressions.scala \| grep -c "DataType = DoubleType"; grep -n -A12 "^case class SecondsToTimestamp" <spark>/expressions/datetimeExpressions.scala \| grep -c "Seq(NumericType)"` | `1` and `1` |

**What Spark does** (`<spark>/expressions/datetimeExpressions.scala`, `Cast.scala`).

| fact | command | prints |
| :--- | :--- | :--- |
| The six epoch conversions are no `TimeZoneAwareExpression` | `grep -n -A1 "abstract class TimestampToLongBase\|abstract class IntegralToTimestampBase" <spark>/expressions/datetimeExpressions.scala` | `extends UnaryExpression`, `with ExpectsInputTypes`, twice |
| The difference and the sums are | `grep -n -A6 "^case class SubtractTimestamps\|^case class TimestampAddInterval" <spark>/expressions/datetimeExpressions.scala \| grep -c TimeZoneAwareExpression` | `2` |
| Spark itself says which casts read the zone | `grep -n -A8 "def needsTimeZone(from: DataType, to: DataType)" <spark>/expressions/Cast.scala` | `(DateType, TimestampType)`, `(TimestampType, DateType)`, `(TimestampType, TimestampNTZType)`, `(TimestampNTZType, TimestampType)` among the arms; the arms for `TIME` are further down |
| Arrow needs a zone's name for the type, and has none for `TIMESTAMP_NTZ` | `grep -n "Missing timezoneId" sql/api/src/main/scala/org/apache/spark/sql/util/ArrowUtils.scala` | one line, in `toArrowTypeDefault` |
| Varka builds an output's field with no zone today | `grep -n "toArrowField(name, type, true, null" <exec>/VarkaLaneArrow.java` | one line, in `fromField` |
| Spark reads the zone's name as a label: two fields match when both have one or neither has | `grep -n "a.getUnit == d.getUnit" sql/api/src/main/scala/org/apache/spark/sql/util/ArrowUtils.scala` | one line, in `sameLayoutArrowType` |
| Spark reads a `TimeStampMicroTZVector` back by its own accessor | `grep -c "TimeStampMicroTZVector" sql/catalyst/src/main/java/org/apache/spark/sql/vectorized/ArrowColumnVector.java` | `3` |

**Tests and generated files that hold the old state.**

| fact | command | prints |
| :--- | :--- | :--- |
| Two tests assert the decision's text | `git grep -n "session time zone, which is not" -- sql/catalyst/src/test` | two files: `VarkaTypesSuite.java`, `VarkaExpressionCompilerSuite.scala` |
| Two tests are named for the zoned type staying out | `git grep -n "a zoned timestamp" -- sql/catalyst/src/test sql/core/src/test` | `VarkaExpressionCompilerSuite.scala` and `VarkaLongLaneSuite.scala`, one test each |
| One test asserts the general text for a `TIMESTAMP` sum | `grep -n 'intervalDeclineReason(plus) === "unsupported expression"' <gens>/VarkaExpressionCompilerSuite.scala` | one line |
| The record of Spark's cast suites holds the general text for casts to the type | `grep -c -P "\tTIMESTAMP\t" sql/varka/plans/m8/casts_suites.tsv` | `19` |
| The two-lane refusal's reason lists the types of the 64-bit lanes, and two generated files quote it | `grep -c "TIMESTAMP_NTZ, TIME, day-time interval, DECIMAL" docs/sql-varka.md sql/varka/width_audit.json` | `188` and `188` |
| The emit tool knows no timestamp column | `grep -c -i "timestamp" <gent>/varka/VarkaEmitDump.java` | `0` |
| The coverage table holds 77 rows over a `TIMESTAMP_NTZ` column | `grep -c -P '"executable" : ".*\bntz2?\b' sql/varka/coverage.json` | `77` |

**What the tests' resolver and Spark's rewrites do to four forms.**

| fact | command | prints |
| :--- | :--- | :--- |
| The resolver strips a cast to its child's own type, as the optimizer does: no plan holds an identity cast | `grep -n "case Cast(child, dataType, _, _) if child.dataType == dataType => child" <gens>/varka/VarkaSqlResolve.scala` | one line, in `resolveAsWritten` |
| `fallback` compiles the replacement of a `RuntimeReplaceable` before its last statement | `grep -n -A2 "if (e instanceof RuntimeReplaceable r)" <gen>/VarkaNodeCompiler.java` | `return compileNode(r.replacement(), inputs, literals, sink);` |
| `make_timestamp` and `make_timestamp_ltz` of a date and a time, and their `try_` forms, are `RuntimeReplaceable`: what the compiler sees is a `StaticInvoke` of `makeTimestamp` | `grep -c '"makeTimestamp",' <spark>/expressions/datetimeExpressions.scala` | `2`: in `MakeTimestampLTZ` and in `MakeTimestampFromDateTime` |
| That `StaticInvoke` is no TIME function of `VarkaTimeCompiler` | `grep -c '"makeTimestamp"' <gen>/VarkaTimeCompiler.java` | `0` |
| The six-field forms are `MakeTimestamp`, which is not replaced; `TryMakeTimestamp`'s replacement is a `MakeTimestamp` | `grep -n -A12 "^case class MakeTimestamp(" <spark>/expressions/datetimeExpressions.scala \| grep -c RuntimeReplaceable` | `0` |
| A cast of a `TIME` to `TIMESTAMP` is rewritten for the query's date into the same `StaticInvoke` | `grep -n "MakeTimestampLTZ(dateLit, c.child, c.timeZoneId).replacement" <spark>/optimizer/finishAnalysis.scala` | one line, in `ComputeCurrentTime` |
| The decimal compiler declines a cast from a type that is no decimal with its own text, and has one text builder for a decimal of two words | `grep -n "to a decimal is row 350's\|private static String wideReason" <gen>/VarkaDecimalCompiler.java` | two lines: in `cast`, and `wideReason` |
| The IN order test lists the types of the long literal table | `grep -n "val longTable = Seq(DoubleType, TimestampNTZType, LongType" <gens>/VarkaExpressionCompilerSuite.scala` | one line |

**One run of the emit tool,** on the older worktree at `93625e9c721`, over a `BIGINT` column
(the tool has no timestamp column there):

    dev/varka_emit.sh "timestamp_micros(l) < TIMESTAMP '2021-06-01 12:00:00Z'" \
      "hour(timestamp_micros(l))" "CAST(timestamp_micros(l) AS BIGINT)" \
      "CAST(timestamp_micros(l) AS DOUBLE)" --columns l:bigint

It printed `unsupported expression: timestamp_micros(l)` for the comparison,
`unsupported expression: hour(timestamp_micros(l))`, `unsupported expression:
CAST(timestamp_micros(l) AS BIGINT)`, and `a cast to or from a floating type that no floating
lowering serves` for the last. So an unclaimed node declines at itself, before its operand is
compiled; and a literal written with `Z` or ` UTC` after its time parses, which the fixtures
of 4.8 use.

**What I could not check.** Row 655 is not built and its plan could not be read, so each
name of its is a premise that part B's B0 greps (section 12). Row 654's plan was read; its code
is not built, so B0 greps what this row reads of it. No kernel over a `TIMESTAMP` column was
emitted: the tool has no such column until part A. The resolved class of each form of
`VarkaZonedDeclinesSuite` was read in Spark's source and not run; a form that prints another
text than the suite expects is a stop (5.1). Whether a twin of each of the 77 rows passes
Spark's analyzer was not run; `VarkaTimestampTwinSuite` says. The script of 4.8 was run on a
scratch copy of the curated file (4.8 says how); `python3 dev/varka_census.py --check` over
its result was not, since the check needs part B's coverage rows. No timing was taken.

## 4. The design

### 4.1 The registry entry, in two states

- **Part A:** `Declined("its arithmetic is in the session time zone, which is not lane
  arithmetic")` becomes `Owed("656")`. A column of the type then declines with the registry's
  own text for an owed type, built by `VarkaTypes.declineReason`: `a timestamp column: the
  TIMESTAMP lane is row 656's`. Nothing fuses. `types.tsv` reads `owed` and `656`.
- **Part B:** `Owed("656")` becomes `OnLane(LaneType.LONG)`, the entry's lanes become
  `List.of(LaneType.LONG)`, and its conformance `new Conformance.Suite("Timestamp")`. The
  storage stays `plain(64)` and the form `DataTypes.TimestampType`.

The census name `ltz` is set by row 654's part b, on the entry while it still declines
(`VARKA-654.md` 4.5). This row does not touch it.

### 4.2 What fuses in part B, and by which code

No arm is written for these. Each is served by code that asks the registry for the lane.

| form | served by | zone |
| :--- | :--- | :--- |
| a `TIMESTAMP` column | the long lane's column leaf in `VarkaTimeCompiler.arm` | none |
| a `TIMESTAMP` literal | the long literal's slot in the same method; the value is a `Long` | none: the analyzer fixed the instant |
| `<`, `<=`, `>`, `>=`, `=` | `VarkaConditionCompiler.condArm`, through `compare` | none |
| `<=>` | `condArm`, through `nullSafeEqual` | none |
| `IS NULL`, `IS NOT NULL` | `condArm`, through `compileValidity` | none |
| `IN` over literals, and the `InSet` the optimizer makes of a long list | `inArm`, which claims a type whose admission is `OnLane` | none |
| `greatest`, `least` | `VarkaNodeCompiler.arithmeticArm`, through `foldPick` | none |
| `CASE`, `IF`, `coalesce`, and the forms the optimizer rewrites into them (`nvl`, `ifnull`, `nvl2`, `nullif`, `BETWEEN`, `equal_null`) | `VarkaConditionCompiler.arm`; a missing `ELSE` through `nullLeaf`, which gets its `TIMESTAMP` arm | none |
| `CAST(ts AS TIMESTAMP)`, the identity, in a tree built by hand; no plan holds one | one new arm beside the `TIMESTAMP_NTZ` identity (part A) | none |
| the output | `VarkaLaneArrow.allocate`, with the zone's name (4.4) | a label |

### 4.3 What declines by name, and with which text

An expression that no arm claims reaches `VarkaNodeCompiler.fallback`, or the end of
`VarkaConditionCompiler.compileCond` for a condition. Both ask `VarkaZonedDeclines.reason(e)`
before they give the general text. `reason` answers for an expression that has a `TIMESTAMP`
operand or result (`VarkaTypes.isLtz` of its type or of a child's type). It also answers for
every `ConvertTimezone`: its operand is a `TIMESTAMP_NTZ`, a `TIMESTAMP` reaches it under a
cast, and row 670 owns the function for both (`VARKA-654.md` 4.6 curates it so).

`fallback` compiles the replacement of a `RuntimeReplaceable` before it asks `reason`. So the
table below names the classes the compiler really sees, not the classes SQL writes.

Every text but the last two is `<what> is row <n>'s: not fused yet`, the form
`VarkaMathCompiler` uses. The four of row 657 are letter for letter the texts row 657's first
part gives from its own arm, so that row moves no reason. The rows of the casts are the ones
`cast_rows` of `VARKA-654.md` 4.8 gives: where it gives two, the first, which is the row that
builds the pair's first kernel.

| what the compiler sees | from the SQL | reads the zone | `<what>` | row |
| :--- | :--- | :--- | :--- | ---: |
| `Hour`, `Minute`, `Second` | `hour(ts)`, `minute(ts)`, `second(ts)`, `extract(HOUR FROM ts)` | yes | `hour of a TIMESTAMP`, `minute of a TIMESTAMP`, `second of a TIMESTAMP` | 657 |
| `Cast` of a `TIMESTAMP` to `DATE` | `CAST(ts AS DATE)`, and under every calendar field: `year(ts)`, `last_day(ts)`, `date_part('YEAR', ts)` | yes | `CAST(TIMESTAMP AS DATE)` | 657 |
| `Cast` of a `DATE` or a `TIMESTAMP_NTZ` to `TIMESTAMP` | `CAST(d AS TIMESTAMP)`, `CAST(ntz AS TIMESTAMP)`, and the cast under `d < ts` and `ntz < ts` | yes | `CAST(DATE AS TIMESTAMP)`, `CAST(TIMESTAMP_NTZ AS TIMESTAMP)` | 690 |
| `Cast` of a `TIMESTAMP` to `TIMESTAMP_NTZ` or `TIME`; `Cast` of a `TIME` to `TIMESTAMP` as written | `CAST(ts AS TIMESTAMP_NTZ)`, `CAST(ts AS TIME)`; `CAST(t AS TIMESTAMP)` before the optimizer rewrites it | yes | `CAST(<from> AS <to>)`, each type as `DataType.sql()` prints it | 669 |
| `Cast` between a `TIMESTAMP` and `TINYINT`, `SMALLINT`, `INT`, `BIGINT`, `FLOAT`, `DOUBLE`, `DECIMAL`, `BOOLEAN` | the 16 casts | no | the same form | 669 |
| `SecondWithFraction` | `extract(SECOND FROM ts)` | yes | `the fractional second of a TIMESTAMP` | 668 |
| `TimeBucket` | `time_bucket(INTERVAL '1' HOUR, ts)` | yes | `time_bucket of a TIMESTAMP` | 668 |
| `TruncTimestamp`, `MonthsBetween`, `FromUTCTimestamp`, `ToUTCTimestamp`, `ConvertTimezone` | `date_trunc`, `months_between`, `from_utc_timestamp`, `to_utc_timestamp`, `convert_timezone` | yes | the function's name | 670 |
| `MakeTimestamp` | `make_timestamp` and `make_timestamp_ltz` of six or seven fields; `try_make_timestamp` of six or seven, whose replacement is a `MakeTimestamp` | yes | `make_timestamp` | 670 |
| `StaticInvoke` of `makeTimestamp` | `make_timestamp(d, t)`, `make_timestamp_ltz(d, t)`, `try_make_timestamp(d, t)`; and `CAST(t AS TIMESTAMP)` as a query hands it, after `ComputeCurrentTime` | yes | `make_timestamp` | 670 |
| `SubtractTimestamps` | `ts - ts2`, `ts - d` | yes | `TIMESTAMP minus TIMESTAMP` | 671 |
| `TimestampAddInterval` | `ts + dt`, `ts - dt` | yes | `a TIMESTAMP plus a day-time interval` | 671 |
| `TimestampAddYMInterval` | `ts + ym` | yes | `a TIMESTAMP plus a year-month interval` | 671 |
| `TimestampAdd` | `timestampadd(HOUR, i, ts)` | yes | `timestampadd of a TIMESTAMP` | 671 |
| `SecondsToTimestamp`, `MillisToTimestamp`, `MicrosToTimestamp` | `timestamp_seconds(l)`, `timestamp_seconds(x)` over a `DOUBLE`, `timestamp_millis(l)`, `timestamp_micros(l)` | no | the function's name | 667 |
| `UnixSeconds`, `UnixMillis`, `UnixMicros` | `unix_seconds(ts)`, `unix_millis(ts)`, `unix_micros(ts)` | no | the function's name | 667 |
| `Murmur3Hash`, `XxHash64` over a `TIMESTAMP` child | `hash(ts)`, `xxhash64(ts)` | no | `a hash of a TIMESTAMP` (declined inside `VarkaHashCompiler.word`, which claims the node) | 667 |
| `KnownNotNull`, `TryEval` | no SQL: the optimizer's tag, and the wrapper of a `try_` function | no | `known_not_null of a TIMESTAMP`, `try_eval of a TIMESTAMP` | 667 |
| `Literal`, `In`, `InSet`, in part A only | `TIMESTAMP '...' < ts`, `ts IN (...)` | no | `a TIMESTAMP literal`, `IN over a TIMESTAMP` | 656 |
| a cast with a `STRING` side | `CAST(ts AS STRING)` | - | the whole text is `CAST(TIMESTAMP AS STRING): a STRING side is out by the owner's decision of 14 October 2026` | - |
| anything else with a `TIMESTAMP` operand or result | `date_format(ts, 'yyyy')`, a cast to a type the registry does not hold | - | the whole text is `<nodeName> over a TIMESTAMP: no row of milestone 9 fuses it`, for example `DateFormatClass over a TIMESTAMP: no row of milestone 9 fuses it` | - |

Two notes on the table.

- `try_make_timestamp` prints `make_timestamp is row 670's: not fused yet`: the compiler sees
  the replacement, and row 670 owns both names.
- `CAST(t AS TIMESTAMP)` has two texts. Written by hand with no rewrite it is a `Cast`, and
  names row 669, as `cast_rows` has the pair. A query hands the compiler the optimizer's
  rewrite, a `StaticInvoke` of `makeTimestamp` over the query's date, and that names row 670,
  which builds `makeTimestamp`. Row 669 claims the pair's census cell when that kernel exists.

The fallback has two ends, `VarkaNodeCompiler.fallback` and the last statement of
`compileCond`. Six places answer before either and are edited so that they give the same
texts. The first is the only check `compileNode` and `compileCond` make before any family is
asked:

- `VarkaNodeCompiler.floatingDecline` runs before every arm, on every expression that has a
  `FLOAT` or `DOUBLE` operand or result and that no floating lowering serves. Three kinds of
  the table's expressions are such: a cast between a `TIMESTAMP` and a floating type;
  `months_between`, whose result is a `DOUBLE`; and `timestamp_seconds` over a floating
  operand. It would name each "an operation over floating values ...". So, before it picks
  its own text, it asks `reason` and returns the table's text where there is one.
- `VarkaHashCompiler.word` gets a `TimestampType` case with the hash's text.
- `VarkaDecimalCompiler.cast` declines a cast from any type that is no decimal with "a cast
  from ... to a decimal is row 350's". A cast from a `TIMESTAMP` to a decimal of at most 18
  digits gets the table's text there.
- `VarkaDecimalCompiler.wideReason` builds the text of every decline of a decimal of more than
  18 digits. It asks `reason` first, so a cast from a `TIMESTAMP` gets the table's text.
- `VarkaDecimalCompiler.value` ends in a `default` with the text "a decimal operator no arm
  builds yet: ...". A decimal-valued expression over a `TIMESTAMP` reaches it and not the
  fallback: `extract(SECOND FROM ts)`, whose result is a `DECIMAL(8, 6)`. The `default` asks
  `reason` first, as the fallback does.
- A column of the type, in part A, declines with the registry's text of 4.1.

**The trace behind that list** (27 November 2026, every class of the table from the top of
`VarkaExpressionCompiler.compileRoot`, not from a family). `compileRoot` sends a `BOOLEAN`
root to `compileCond` and a `DECIMAL` root to `VarkaDecimalCompiler.output`; every other root
goes to `compileNode`. `compileNode` and `compileCond` each run `floatingDecline`, then ask
the families' claims. No claim takes a class of the table over a `TIMESTAMP`: each claim is
guarded by a class the table does not hold, by an exact type, or by `VarkaTypes.isNtz`; the
`StaticInvoke` claim of `VarkaTimeCompiler` asks for a TIME function, which `makeTimestamp`
is not; the hash's claim is the second place above. So a class of the table ends in one of:
the floating check (a floating operand or result), the decimal compiler (a `DECIMAL` result:
`SecondWithFraction`, a cast to a decimal), `VarkaHashCompiler.word`, the end of
`compileCond` (a `BOOLEAN` result: a cast to `BOOLEAN`, `IN`), or `fallback`. Each of the five
gives the table's text.

A zone column (a zone that is not a literal) is row 670's to decline. A cast whose other side
is a nanosecond timestamp falls to the last line of the table: rows 673 to 675 name it.

Later rows claim their nodes in arms. A claim wins before the fallback, so a row that fuses a
form changes no line of `VarkaZonedDeclines`; it may delete its entries. The suite of this
table judges a form only while it declines (5.1), and `VarkaTimestampTwinSuite` is the test
that fails when a form fuses wrongly.

### 4.4 Arrow in and out, and the session zone's name

- **In.** `VarkaLaneArrow.of(TIMESTAMP)` names `TimeStampMicroTZVector`, a sibling class of the
  `TimeStampMicroVector` that holds a `TIMESTAMP_NTZ`. `VarkaEvaluatorBase.isArrowBacked` reads
  the table through `VarkaLaneArrow.reads`, so it needs no edit. The zoned vectors of other
  units (`TimeStampSecTZVector`, `TimeStampMilliTZVector`, `TimeStampNanoTZVector`) are in no
  entry: a batch that holds one takes the row path.
- **Out.** Arrow's field of a `TIMESTAMP` must name a zone. The zone there is a label: the
  values are instants, and Spark compares two such fields only by whether each has a label.
  The row's text asks for the session zone, so the kernel evaluator carries its name:
  - `VarkaProjectExec` and `VarkaColumnarToRowExec` pass `conf.sessionLocalTimeZone` to their
    evaluator factories, beside `conf.offHeapColumnVectorEnabled`.
  - `VarkaKernelEvaluator` keeps it in a final field and passes it to
    `VarkaLaneArrow.allocate` in `allocateVector`. Its constructor of nine arguments stays and
    passes `"UTC"`, and so does the one of four: the suites and the harness that build an
    evaluator by hand need no edit.
  - `VarkaLaneArrow.fromField` passes the name to `ArrowUtils.toArrowField`, which reads it for
    a `TIMESTAMP` and ignores it for every other type.
  - The filter evaluators allocate no typed output and get no zone.

### 4.5 How the two timestamp types are kept apart

| where | what holds them apart |
| :--- | :--- |
| the registry | two ids, two census names (`ntz`, `ltz`), two predicates (`isNtz`, `isLtz`); `isLtz` is false for the nanosecond types |
| the compiler's arms | every `TIMESTAMP_NTZ` arm keeps its `isNtz` guard; this row widens none. The only arm that names `TIMESTAMP` is the identity cast |
| the fallback | `VarkaZonedDeclines` names each expression the `TIMESTAMP_NTZ` arms would have claimed |
| `VarkaTimestampTwinSuite` | for every `TIMESTAMP_NTZ` coverage row, the twin over a `TIMESTAMP`, resolved under `America/Los_Angeles`: if the twin reads the zone, it declines or its IR is not the row's IR. Whether it reads the zone is asked of Spark (`TimeZoneAwareExpression`, `Cast.needsTimeZone`), not listed by hand |
| a comparison of the two | Spark's analyzer casts the `TIMESTAMP_NTZ` side to `TIMESTAMP`, and the cast declines naming row 690; a test pins that tree |
| Arrow | two vector classes, each in one entry of `VarkaLaneArrow.of`; neither is an instance of the other |
| literals | both are longs in one table. Two literals with one count share a slot. That is right: the kernel reads bits |
| the fuzzers | `VarkaFuzzTypes` has an entry for each; the Catalyst grammar has `Type.NTZ` and `Type.LTZ` with their own columns, literals and productions |
| the coverage table and the docs | families titled `TIMESTAMP ...` beside `TIMESTAMP_NTZ ...`; columns `ts`, `ts2` beside `ntz`, `ntz2` |

What is shared on purpose: the kernel class. `ts < ts2` and `ntz < ntz2` are one shape, since a
shape holds no Spark type (prediction 2 of section 7).

A tree built by hand that compares a `TIMESTAMP` with a `TIMESTAMP_NTZ` with no cast is not a
query: `BinaryComparison` rejects it at analysis. The compiler has no guard for it, as it has
none for a `BIGINT` beside a `TIME` (open question 1 of 4.9).

### 4.6 Edge cases

- **Null rows.** A comparison is unknown where an operand is null, `<=>` is not, `greatest`
  and `least` skip nulls, `coalesce` takes the first value. These are the long lane's rules
  and the conformance contract checks each null pattern.
- **The ends of a long.** A comparison is right for every long, `Long.MIN_VALUE` and
  `Long.MAX_VALUE` too. No kernel of this row has a guard, so no batch declines. Spark's own
  range ends at `-62135596800000000` and `253402300799999999`; the values hold both ranges'
  ends.
- **A batch with no live row.** A filter selects nothing and a projection writes nulls. The
  session suite holds a batch whose every `ts` is null.
- **Two lanes of 64 bits** (128 bits on x86). A filter whose kernel is driven at the long lane
  is refused there by the host's rule, `VarkaKernelPlan.TWO_LANE_CONDITION_REASON`; that rule
  is by lane, so it covers a `TIMESTAMP` filter with no edit. Its text lists the types of the
  lane, and part B adds `TIMESTAMP` to the list (the owner's decision, 4.9). The projections
  fuse.
- **AVX2 against AVX-512.** The same IR at every width. The kernels are the long lane's, which
  the width audit already holds at each width; the new rows join it.
- **The session zone.** No answer of this row depends on it. A literal's instant is fixed by
  the analyzer; a kernel holds the count in a slot. Two sessions in two zones share one class.
- **The zone of a test JVM.** A ScalaTest JVM runs under `America/Los_Angeles`, a JUnit JVM
  under the machine's zone. A `TIMESTAMP '2021-06-01 12:00:00'` literal is then two instants.
  So every literal this row writes ends in `Z`, and both fixtures derive `ts` with
  `timestamp_micros`, which reads no zone.

### 4.7 Every place that names `TIMESTAMP_NTZ`, and what `TIMESTAMP` needs there

Production code:

| place | what it holds for `TIMESTAMP_NTZ` | what `TIMESTAMP` needs | part |
| :--- | :--- | :--- | :--- |
| `VarkaTypeId` | the id | it has one; the enum's comment loses "which it does not read by decision" | A |
| `VarkaTypes.idOf` | `TimestampNTZType` to the id | nothing: `TimestampType` is mapped | - |
| `VarkaTypes.entry` | `OnLane(LONG)`, `plain(64)`, suite `TimestampNtz` | 4.1 | A, B |
| `VarkaTypes.isNtz` | the arms' shared test | `isLtz` beside it | A |
| `VarkaLaneArrow.of` | `TimeStampMicroVector`, `fromField` | `TimeStampMicroTZVector`, `fromField` with the zone | B |
| `VarkaTimeCompiler.arm`, the column leaf and the literal | by lane | no code: they follow the registry. The leaf's comment names the type | B |
| `VarkaTimeCompiler.arm`, nine `isNtz` lines: `SubtractTimestamps`, `TimestampAddInterval`, `TimestampAdd`, `Hour`, `Minute`, `Second`, the cast to `TIME`, the cast from `TIME` | the arithmetic and fields at UTC | no edit of an arm; each form declines by 4.3. The comment "A zoned TIMESTAMP is no lane's" is reworded | A, B |
| `VarkaNodeCompiler.arithmeticArm`, four `isNtz` lines: the casts with `DATE`, the identity | UTC casts | an identity arm for `TIMESTAMP`; the others decline by 4.3 | A |
| `VarkaChronoCompiler`, three `isNtz` lines: `TimestampAddYMInterval`, `TimestampAdd` by months, `isNtzDate` | month sums and the day guard | no edit; 4.3 | A |
| `VarkaConditionCompiler.nullLeaf` | `LaneType.LONG` | the same | B |
| `VarkaConditionCompiler.inArm` | by admission | nothing | B, by the entry |
| `VarkaHashCompiler.word` | `case TimestampNTZType`, the long's hash | a case that declines naming row 667 | A |
| `VarkaDecimalCompiler.cast` and `wideReason` | nothing: a cast of the type to a decimal is not legal Spark | a cast from a `TIMESTAMP` declines naming row 669, not row 350 or 359 | A |
| `VarkaKernelPlan.TWO_LANE_CONDITION_REASON` and the comment of `hostRefusal` | the type in the list | the type in the list; the sentence "The zoned `TIMESTAMP` is not among them only because no lane reads it yet" goes | B |
| `VarkaSameAnswer.valueOf` | `getLong` | nothing: it names both | - |
| `VarkaRangeAnalysis` | `NTZ_MIN_DAYS`, the day guard of a field | nothing. Its kinds are `DAY` and `INT`; a long-lane column has no range under either, whatever its Spark type. The fields are row 668's |
| `VarkaFamily`, the `covers` texts | the forms of each family | nothing: no family gains a node |
| `VarkaReproduction`, `VarkaShapeKey`, the emitter, the warm-up, the cost model, the operator census | nothing: they are by lane or by node | nothing: no node, no lane, no operator is new | - |

Tests, tools and generated files:

| place | what it holds for `TIMESTAMP_NTZ` | what `TIMESTAMP` needs | part |
| :--- | :--- | :--- | :--- |
| `VarkaFuzzTypes.of` | the IR leaf `LONG_COLUMN`, columns `ntz`, `ntz2`, `NTZ_VALUES` | `Owed("656")` three times, then `LONG_COLUMN`, `ts`, `ts2`, `LTZ_VALUES` | A, B |
| `VarkaCatalystGrammar` | `Type.NTZ`, its columns, literals, productions | `Type.LTZ`: columns, two literals, the comparisons and null tests through `VALUE_TYPES`, the choices | B |
| `VarkaReference`, `VarkaIrGrammar` | nothing: the IR has no Spark type | nothing | - |
| `VarkaSparkOracle`, `VarkaKernelChecks` | values through `VarkaTypes.Info.sparkValue` | nothing: a long-lane value is a `Long`, which is Spark's value of a `TIMESTAMP` | - |
| `VarkaTypeConformance`, `CAST_TESTS` | `"ntz"`, `castToTimestampNtz` | nothing: `"ltz"` and `castToTimestamp` are row 654's (`VARKA-654.md` 5.2, step 5); the list then has 14 entries | - |
| `VarkaTimestampNtzConformanceSuite` | the suite | `VarkaTimestampConformanceSuite` | B |
| `VarkaTimestampNtzArithmeticSuite`, "the legacy CalendarInterval difference and a zoned TIMESTAMP decline" | three zoned forms that must decline | they still decline; the failure's message loses "which no lane carries" | B |
| `VarkaTypesSuite` | the decline's text; the forms | the texts of 4.1 | A, B |
| `VarkaCoverageSuite` | `ntz`, `ntz2`; seven families; `ntzHeld`; the domains `ntzDayBound`, `ntzFieldDays`, `tryDomain` | `ts`, `ts2`; four families; two held literals; no domain, since no kernel of this row has a guard | B |
| `VarkaCoverageCompositionFuzzSuite.drawLong` | "drawn as a `BIGINT` is" | nothing: its last arm draws every other long-lane type so; one sentence of its comment | B |
| `VarkaCoverageRows`, the filters | none for the type | none: both fuzzers can build the columns | - |
| `VarkaEmittedBytesSuite`, `VarkaWidthAuditShapes` | the columns, read from `coverage.json` | nothing; they regenerate | B |
| `VarkaExpressionCompilerSuite` | the fused half of "a zoned timestamp column declines ..." | the declined half's text, then the test flipped; a test of the identity cast | A, B |
| `VarkaExpressionCompilerSuite`, the IN order test | `inNtz`, its values in `inCases`, `TimestampNTZType` in `longTable` | `inLtz`, three values, `TimestampType` in `longTable` | C |
| `VarkaCoverageDifferentialSuite.fixtureView` | `ntz`, `ntz2` derived from a date and a time | `ts`, `ts2` derived the same way (4.8) | B |
| `VarkaSparkFuzz.fixtureRows` | the same | the same | B |
| `VarkaDomains.laneValue`, `VarkaAnsiForm.standIn` | `getLong`; `ntz2` | `TimestampType` beside it; `ts2` | B |
| `VarkaLongLaneSuite` | the fused half of "a zoned timestamp comparison stays ..." | the test flipped | B |
| `VarkaVectorProjectionSuite` | in its type lists | nothing: it lists both | - |
| `VarkaTimestampNtzSuite` | the lane through queries | `VarkaTimestampSuite` | C |
| `VarkaLaneArrowSuite` | the table held to the registry | the zone argument; three tests | B |
| the harness: `VarkaExpressionHarness`, `VarkaCacheBatches`, `VarkaHarnessStore` | nothing by type; the cache batches and the store are keyed by the conf's zone already | nothing: the harness builds its evaluator with four arguments, so its outputs are labelled `UTC` | - |
| `VarkaArrowSource.varkaTyped` | by the registry | nothing; after part B Spark's own suites feed `TIMESTAMP` relations to kernels (risk 3) | - |
| `VarkaTwoLaneDefault128Suite` | the long-lane checks at 128 bits | nothing: the refusal is by lane | - |
| the three `VarkaTimestampNtz*BaselineBenchmark` classes | the baselines and their fuse classes | row 655's class; its zone-free sections' classes | B, D |
| `sql/varka/matrix/skips.tsv` | lines under `checkIntOverflow=false` | none: no form of this row has an overflow check | - |
| `dev/varka_census.py`, `SIMPLE_COLUMN_TYPES` | `"timestamp_ntz": "ntz"` | nothing: row 654 added `"timestamp": "ltz"` (`VARKA-654.md` 4.5). This row never edits the script | - |
| `$M9/census_curated.txt` | `ntz` in the fused types of 22 entries | `ltz` in the same 22, by the script of 4.8 | B |
| `dev/varka_new_lane.py` | reads `types.tsv` | nothing; it must pass | B |
| `sql/varka/types.tsv`, `sql/varka/fuzz/types.tsv` | generated | regenerated | A, B |
| the record of Spark's cast suites (`$M9/casts_suites.tsv`) | generated | regenerated twice: in part A the reasons of the lines whose target is `TIMESTAMP`; in part B new lines whose source is `ltz`, since the suites lift a literal of a type a kernel reads into a column (`VARKA-654.md` 11) | A, B |
| `coverage.json`, `emitted_bytes.json`, `width_audit.json`, the census, the docs' blocks | generated | regenerated: new rows only, and the reworded refusal | B |
| `docs/sql-varka.md`, five hand-written passages, and `README.md`, one | three counts of "thirteen" types a kernel reads; "`TIMESTAMP` with the local time zone" out of scope, twice; the decline that names a decision; the README's "which no kernel reads yet" | reworded, line by line (5.1 step 10, 5.2 step 11) | A, B |
| `VarkaEmitDump`, `dev/varka_emit.sh` | no column type | `timestamp`, `timestamp_ntz` | A |

### 4.8 The coverage rows, the columns and the census cells

**The columns.** `ts` and `ts2`, of `TimestampType`, after `ntz2` in `VarkaCoverageSuite`'s
`columns`. Both fixtures derive them from the columns that `ntz` and `ntz2` come from, with no
zone:

    timestamp_micros(unix_date(d) * 86400000000L + time_to_micros(t)) AS ts
    timestamp_micros(unix_date(d2) * 86400000000L + time_to_micros(t2)) AS ts2

So `ts` holds the count that `ntz` holds, row for row, and is null where `d` or `t` is. In
`VarkaCoverageDifferentialSuite.fixtureView` the expression sits under the `CASE WHEN
abs(unix_date(d)) < lastNtzDay` that `ntz` has, since the multiply overflows past that day;
`VarkaSparkFuzz.fixtureRows` needs no guard, as its `ntz` has none.

**The literals.** `TIMESTAMP '2021-06-01 12:00:00Z'` and `TIMESTAMP '1969-12-31 18:00:00Z'`:
the counts of `ntzHeld` and `ntzHeld2`, so the fixtures hold them.

**The rows,** 23 in four families, placed after "Null family predicates on TIMESTAMP_NTZ
columns". None has a `domain`.

| family (`predicates`) | rows |
| :--- | :--- |
| `TIMESTAMP columns` (true) | `ts < ts2`; `ts <= ts2`; `ts > ts2`; `ts >= ts2`; `ts = ts2`; `ts = TIMESTAMP '2021-06-01 12:00:00Z'`; `ts <=> ts2`; `ts IN (` the two literals `)`; `ts IN (11 to 16 TIMESTAMP literals)`, built as `InSet(ts, ...)` over the eleven counts of the `TIMESTAMP_NTZ` row, its `executable` the eleven literals `TIMESTAMP '1970-01-01 hh:00:00Z'`; `ts IS NULL`; `ts IS NOT NULL` |
| `TIMESTAMP columns choosing values` (false) | `greatest(ts, ts2)`; `least(ts, ts2)`; `CASE WHEN ts < ts2 THEN ts ELSE ts2 END`; `if(ts IS NULL, ts2, ts)`; `coalesce(ts, ts2)` |
| `Null family on TIMESTAMP columns` (false) | `nvl(ts, ts2)`; `ifnull(ts, ts2)`; `nvl2(ts, ts2, ts)`; `nullif(ts, ts2)`; `CASE WHEN ts < ts2 THEN ts END` |
| `Null family predicates on TIMESTAMP columns` (true) | `ts BETWEEN ts2 AND TIMESTAMP '2100-01-01 00:00:00Z'`; `equal_null(ts, ts2)` |

Each row is the twin of a row of the `TIMESTAMP_NTZ` families, so the census finds a node of
each entry's class over an `ltz` operand exactly where it finds one over `ntz`.

The first row's note: "an instant is its microseconds since the epoch on the long lane; a
comparison reads no time zone, so this is the kernel of ntz < ntz2". The literal row's note:
"a TIMESTAMP literal takes a slot in the long lane's table; the analyzer fixed its instant".

**The census cells,** 22, in `$M9/census_curated.txt`. Row 654 is the reference for that file
(`VARKA-654.md` 4.7 and 11). When this row starts, the file holds:

- 21 entries that admit `ltz` through `ALL`, carry `656` in their rows and "ltz is row 656's"
  in their note, and have no `ltz` in their fused types: `coalesce if ifnull nullif nvl nvl2
  when between isnull isnotnull equal_null in <=> = == > >= < <= greatest least`. Two of them,
  `isnull` and `isnotnull`, were `done` and are `plumbing` since row 654.
- `in_set`, an implicit entry, the same but with `667` in its rows and "ltz is row 667's" in
  its note. `m9/PLAN.md` names it under row 667. It fuses in this row by the arm that fuses
  `IN`, with no code of its own, so this row marks it and adds `656` to its rows; `667` stays.

Part B makes exactly these edits, by the one-off script below and by nothing else:

1. In each of the 22 entries, `ltz` joins the fused types, immediately after `ntz`.
2. In `in_set`, `656` joins the rows, at the end. The other 21 already hold it.
3. In each note, "ltz is row 656's" (in `in_set`: "ltz is row 667's") becomes "ltz on the long
   lane (VARKA-656)".
4. The need becomes `done` where every type the entry admits is now fused in full: `isnull`
   and `isnotnull`. `python3 dev/varka_census.py --check` fails an entry whose need is `done`
   while a type is unfused, and one whose need is not `done` while every type is fused. The
   other 20 keep the need row 654 left: each still lacks a type or holds one fused in part
   (`dec^`).

The script, saved as `$T/ltz_fused.py` and not committed:

    #!/usr/bin/env python3
    """One-off: mark TIMESTAMP_LTZ fused in the 22 entries row 656 fuses (VARKA-656, part B).

    usage: ltz_fused.py <census_curated.txt>   # rewrites the file in place
    Not committed. It stops, changing nothing, on an entry that is absent, whose types are not
    ALL, whose fused types hold no ntz, or that already holds ltz.
    """
    import sys

    NAMES = (
        "coalesce if ifnull nullif nvl nvl2 when between isnull isnotnull equal_null in <=> = =="
        " > >= < <= greatest least in_set"
    ).split()
    ORDER = "bool byte short int bigint float double dec date ntz time ym dt ltz".split()
    OWED = ("ltz is row 656's", "ltz is row 667's")
    FUSED = "ltz on the long lane (VARKA-656)"


    def main(path):
        lines = open(path, encoding="utf-8").read().split("\n")
        section, seen, done, rows_gained = None, [], [], []
        for i, line in enumerate(lines):
            if line.startswith("[") and line.endswith("]"):
                section = line[1:-1]
                continue
            if not line or line.startswith("#") or section not in ("registered", "implicit"):
                continue
            # The entry named "||" starts with two bars: its name is not one of the 22.
            if line.startswith("||"):
                continue
            fields = line.split("|")
            name = fields[0]
            if name not in NAMES:
                continue
            first = 1 if section == "registered" else 3
            if len(fields) != first + 6:
                sys.exit(f"line {i + 1}: {name} has {len(fields)} fields, not {first + 6}")
            types, fused, need, rows, note = (first + k for k in (0, 2, 3, 4, 5))
            held = fields[fused].split(",")
            if fields[types] != "ALL":
                sys.exit(f"{name}: its types are {fields[types]}, not ALL")
            if "ltz" in held:
                sys.exit(f"{name} already holds ltz: restore the file (git checkout -- {path})")
            if "ntz" not in held:
                sys.exit(f"{name}: its fused types hold no ntz: {fields[fused]}")
            at = held.index("ntz") + 1
            held[at:at] = ["ltz"]
            fields[fused] = ",".join(held)
            owners = [r for r in fields[rows].split(",") if r]
            if "656" not in owners:
                fields[rows] = ",".join(owners + ["656"])
                rows_gained.append(name)
            for owed in OWED:
                fields[note] = fields[note].replace(owed, FUSED)
            if FUSED not in fields[note]:
                sys.exit(f"{name}: its note names no row for ltz: {fields[note]}")
            # An entry is done exactly when every type it admits is fused in full (no ^ mark).
            if sorted(t.rstrip("~") for t in held) == sorted(ORDER) and fields[need] != "done":
                fields[need] = "done"
                done.append(name)
            lines[i] = "|".join(fields)
            seen.append(name)
        missing = [n for n in NAMES if n not in seen]
        if missing:
            sys.exit(f"not in the file, and nothing was written: {' '.join(missing)}")
        open(path, "w", encoding="utf-8").write("\n".join(lines))
        print(f"{len(seen)} entries fused for ltz; done again: {' '.join(done) or 'none'};"
              f" 656 joined the rows of: {' '.join(rows_gained) or 'none'}")


    if __name__ == "__main__":
        main(sys.argv[1])

A line of the curated file is `name|types|result|fused|need|rows|note` under `[registered]`
and `name|class|family|types|result|fused|need|rows|note` under `[implicit]`; the script
reads the section and takes the fields by it. An example, `isnull` before and after (the note
cut short here):

    isnull|ALL|bool|bool,int,bigint,date,time,ym,dt,byte,short,ntz,float,double,dec|plumbing|333,334,359,329,347,348,656|...; ltz is row 656's
    isnull|ALL|bool|bool,int,bigint,date,time,ym,dt,byte,short,ntz,ltz,float,double,dec|done|333,334,359,329,347,348,656|...; ltz on the long lane (VARKA-656)

**How the script was tested** (27 November 2026, on scratch copies, with no JVM): milestone
8's curated file of `a0e3a62cf2b` was copied; the script of `VARKA-654.md` 5.2 was run on the
copy and printed its line, "62 entries edited, 23 of them no longer done, 16 added; 0 by
default, 0 skipped, 0 absent"; then this script was run on the result. It printed

    22 entries fused for ltz; done again: isnull isnotnull; 656 joined the rows of: in_set

and changed 22 lines and no other. A second run on its own output stopped with "coalesce
already holds ltz". What was not tested: the census's check over the result, which needs part
B's coverage rows.

**What the census then counts.** 23 fused pairs for `ltz`: the 22 cells, and one for the cast
alias `timestamp`, whose identity pair `pair_credit` counts once the registry's admission is
neither `declined` nor `owed` (`VARKA-654.md` 4.9). Row 656 writes nothing for that one: it
follows from part B's registry entry. The alias's need stays as row 654 left it, since its
other pairs are unfused.

### 4.9 The owner's decisions, and what is still open

**Decided by the owner on 27 November 2026.**

- **The two-lane refusal's text names `TIMESTAMP`: yes.** Part B adds the type to the list in
  `VarkaKernelPlan.TWO_LANE_CONDITION_REASON`. It moves the 188 lines of `docs/sql-varka.md`
  and the 188 of `sql/varka/width_audit.json` that quote the text, all generated.
- **Row 656 fuses `in_set`: yes.** Row 654 curates it with row 667 in its rows; this row marks
  it fused and adds `656` (4.8).

**Open when the plan was written.** Each has the recommendation first; the plan is written on
it. *27 November 2026, later:* the owner approved the plan as written, so the recommendation
of each stands as his answer.

1. **A hand-built comparison of a `TIMESTAMP` with a `TIMESTAMP_NTZ`.** *Recommended: no guard
   in the compiler.* Spark's analyzer never makes that tree; the compiler guards no other pair
   of types on one lane; and a guard would be code with no query behind it. A test pins the
   tree the analyzer does make.
2. **Prediction 2 of `m9/PLAN.md` 6** says this row "lands in one plan and at most two code
   pull requests". *Recommended: score it as missed, by two.* The row is four code pull
   requests, two of them tests alone, and one of results: the size cap, which row 338 did
   not have, splits the declines from their twin suite and the lane from its session suite.

### 4.10 What is out of the row

| what | whose |
| :--- | :--- |
| the census name `ltz`, the cast table's 23 pairs, milestone 9's census and cast files, `castToTimestamp` in the conformance contract, `"timestamp": "ltz"` in the census's column types | row 654 |
| the baseline classes over a `TIMESTAMP` and their results before any kernel | row 655 |
| `hour`, `minute`, `second`, `CAST(ts AS DATE)`; the family `ZONE`, `VarkaZoneCompiler`, the `--zone` argument of the emit tool; the resolver's zone in catalyst tests | row 657 |
| `ToLocal`, the batch arguments, every form of a zone node | rows 682 to 692 |
| the six epoch conversions, `hash`, `xxhash64`, `known_not_null`, `try_eval` | row 667 |
| the calendar fields, `extract`, `time_bucket`, the fractional second | row 668 |
| every cast but the identity; the casts from `DATE` and `TIMESTAMP_NTZ` first in row 690 | rows 669 and 690 |
| `date_trunc`, `months_between`, the zone functions, `make_timestamp` | row 670 |
| the difference, the interval sums, `timestampadd` | row 671 |
| the session zone as a drawn setting of the fuzzers | row 684 |
| the nanosecond `TIMESTAMP_LTZ` | rows 660 to 662 and 673 to 675 |
| a guard on a column's range, a new IR node, an emit option, a family switch | no row of this plan: this row adds none |

## 5. The parts

Five pull requests: four of code, two of them tests alone (A2 and C), and one of results.

| part | title | branch | counted lines, estimate |
| :--- | :--- | :--- | ---: |
| A | `[VARKA-656] Name TIMESTAMP in every decline, with nothing fused yet` | `varka-656a-named-declines` | 680 |
| A2 | `[VARKA-656] Check that no TIMESTAMP form is computed as its TIMESTAMP_NTZ twin` | `varka-656a2-twin-suite` | 270 |
| B | `[VARKA-656] Read, compare, choose and write a TIMESTAMP on the long lane` | `varka-656b-long-lane` | 720 |
| C | `[VARKA-656] Hold the TIMESTAMP lane through sessions under three zones` | `varka-656c-sessions` | 250 |
| D | `[VARKA-656] Results: the zone-free TIMESTAMP sections against the row path` | `varka-656d-results` | 40 |

The estimates were recounted on 27 November 2026, file by file. Part A: about 230 lines of
production code (`VarkaZonedDeclines` about 130 with its header), about 350 of
`VarkaZonedDeclinesSuite`, about 100 of other edits. Part A2: about 255 of the suite. Part B:
about 140 of production code, about 245 of catalyst tests, about 170 of sql/core tests, about
100 of the curated census, the prose and this file, and a margin. The record of Spark's cast
suites, `$M9/casts_suites.tsv`, counts in none of them: row 654 marks it `merge=varka-generated`
in `sql/varka/.gitattributes` (`VARKA-654.md` 4.3), and `dev/varka_msg_check.py` counts no file
so marked. `$M9/census_curated.txt` does count: it is curated by hand.

| part | needs | gives |
| :--- | :--- | :--- |
| A | row 654's two parts merged; this plan merged | the type owed to this row in the registry; every expression over it declining by name; `VarkaZonedDeclines`; `isLtz`; the identity cast's arm; `VarkaZonedDeclinesSuite`; the emit tool's two column types. Master is green and nothing fuses |
| A2 | A merged | `VarkaTimestampTwinSuite`. Master is green; the suite's zone test bites from part B on. Part B reads it; no other row waits for it |
| B | A2 merged; row 655 merged | the type on the lane; Arrow in and out; 23 coverage rows; 22 census cells; the conformance suite; the fixtures' `ts` and `ts2`. Rows 657 and 667 to 671 can start |
| C | B merged | `VarkaTimestampSuite`; the IN order test over the type. Only the row's close reads it: no later row waits for it |
| D | B and C merged | the results of row 655's zone-free class at both widths, and section 7 scored. Only the row's close reads it |

**The cut between A and A2 is fixed here too.** A holds the production code and the suite of
the exact texts. A2 holds the twin suite alone: it passes on master after A, since every twin
declines there, and it needs no edit when a later row fuses a form.

**The cut between B and C is fixed here, not left to the builder.** B holds every line that
master needs to be green with the type on the lane: the production code, the registers, the
fixtures, and the tests that fail without them or guard them directly (the conformance suite,
the Arrow table's three tests, the evaluator's test, the flipped tests). C holds what only
adds proof: the session suite and the IN order test. If a part still passes 900 counted lines,
the builder stops and reports the size; the lead decides.

The parts are built one after the other, A, A2, B, C, D, and no two side by side: A2 tests
A's hooks, B edits the files A and A2 add, C needs B's lane, and every part writes section 11
of this file, so each starts from master with the part before it merged.

### 5.0 What every sheet shares

- Work in the worktree and on the branch the lead names. Each part starts from master after
  the part it needs has merged.
- Every shell that builds or tests starts with:

      export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
      export SPARK_LOCAL_IP=127.0.0.1
      export PATH=$HOME/.venv-ruff/bin:$PATH
      export VARKA_HEAVY_LOCK=<the lock file the lead names>
      M9=sql/varka/plans/m9; T=target/varka-656; mkdir -p $T
      GEN=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen
      IR=$GEN/varka
      GENT=sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen
      GENS=sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen
      EXEC=sql/core/src/main/java/org/apache/spark/sql/execution
      PLAN=sql/core/src/main/scala/org/apache/spark/sql/execution
      EXECT=sql/core/src/test/java/org/apache/spark/sql/execution
      EXECS=sql/core/src/test/scala/org/apache/spark/sql/execution

  `$GEN` is the `<gen>` of 2.3, and so on for each. `$T` is scratch under `target/`, which
  git ignores.

- Wrap every `build/sbt`, `dev/varka_emit.sh`, `dev/varka_matrix.sh`, `dev/lint-java`,
  `dev/lint-scala` and `dev/varka_errorprone.sh` call in `flock "$VARKA_HEAVY_LOCK"`. Call
  `dev/varka_regen_generated.sh` and `dev/varka_push_check.py` bare: they take the lock.
- B0 and the commands of each sheet are code blocks, typed as printed. Inside a quoted grep
  pattern `\|` is grep's "or"; a bare `|` between two commands is the shell's pipe; inside
  single quotes over the curated file a `|` is the file's field separator.
- Run B0 before any edit. A line that does not print what the sheet says is a stop: report the
  command and its output, and edit nothing.
- **Row 653's layout.** This plan is cut for one file a list: one curated file, one
  `VarkaCoverageSuite.scala` that holds every coverage row, one `casts_suites.tsv`. Row 653
  turns such lists into one file an entry. B0 of parts A and B checks the three; where a check
  fails, the part ends, and the builder reports "row 653 has split a list; VARKA-656 is cut for
  one file a list" with the output of the check. The lead re-cuts the plan.
- Where a sheet gives the lines of a comment or of code, type them as given: each was counted
  and is within 100 columns at the indentation shown. Where it gives a comment as a sentence,
  wrap it within 100 columns. Run `dev/lint-java` and `dev/lint-scala` before the commit; a
  line over 100 columns fails them.
- A test command passes when sbt exits 0 and its summary reads `Failed 0, Errors 0`.
- `dev/varka_emit.sh` exits 1 when no entry fuses. That is not a failure of the command where
  the sheet expects `nothing fused`.
- Regenerate with `dev/varka_regen_generated.sh --all --full`: without `--full` the script
  may skip its JVM steps.
- Before the commit: `dev/varka_precommit.sh`; `dev/varka_errorprone.sh --master "$(git
  rev-parse origin/master)"` and `dev/lint-java` for Java; `dev/lint-scala` for Scala;
  `python3 dev/varka_plan_lint.py --check --offline`. After it: `python3
  dev/varka_push_check.py`, which prints the size and must exit 0.
- The commit's subject is the part's title. Its body ends with one attribution line,
  `Generated-by: <tool> (<model>)`, and no other trailer.
- Edit the row's line in `$M9/PLAN.md`: `**Planned**` becomes `**Started**` in part A and
  `**Done**` in part D. Add what was built to section 11 of this file.
- Stop at the local commit. No push, no pull request, no dispatch: the lead does those. Report
  the branch, the commit, the commands with their results and the size.
- Stop also on: a test that should pass and fails; a file in `git status --short` that the
  sheet's list does not hold, or a listed file that is missing from it; a size over the
  estimate by half or over 900; anything the sheet does not decide.
- **What runs only in CI.** The option matrix (the `varka-option-matrix` workflow, every
  configuration over the Varka suites); the width audit's runner columns (the
  `varka-width-audit.yml` dispatch, part B); the Maven build; the benchmark runs of part D; the
  nightly's run of Spark's golden files under Varka. The lead dispatches them. What the builder
  runs first is each sheet's commands, and, in part B, the width audit once more under
  `JDK_JAVA_OPTIONS=-XX:UseAVX=2`, as `sql/varka/AGENTS.md` asks of a change to what the
  emitter is given.

### 5.1 Part A: the type named, every expression declining by name

**B0, the premises.** Run each line; its comment is what it must print. A line that prints
otherwise is a stop, but for the two cases under the block.

    test -f $M9/VARKA-656.md && test -f $M9/VARKA-654.md && echo merged   # 1: merged
    grep -P "^TIMESTAMP\t" sql/varka/types.tsv | cut -f3,5                # 2: ltz, declined
    git ls-files $M9/census_curated.txt $M9/casts_suites.tsv | wc -l      # 3: 2
    grep -c 'Family("TIMESTAMP_NTZ columns"' $GENS/varka/VarkaCoverageSuite.scala   # 4: 1
    python3 dev/varka_census.py --summary | grep '^  ltz '                # 5: "  ltz     new         0 / N"; note N (77 on the plan's commit)
    grep -c -P "\tTIMESTAMP\t" $M9/casts_suites.tsv                       # 6: a count above 0; note it as S (19 on the plan's commit)
    grep -n -A3 "case TIMESTAMP ->" $IR/VarkaTypes.java                   # 7: new Info(id, "ltz", "TIMESTAMP", and new Admission.Declined(
    grep -c "private static <T> Site<T> declined" $GENT/varka/VarkaFuzzTypes.java   # 8: 1
    git grep -n "VarkaZonedDeclines\|VarkaTypes.isLtz" -- sql/catalyst/src sql/core/src   # 9: nothing
    grep -c "isLtz" $IR/VarkaTypes.java                                   # 10: 0
    grep -n 'sink.decline("unsupported expression", e)' $GEN/VarkaNodeCompiler.java       # 11: one line, the last of fallback
    grep -n 'sink.decline("unsupported predicate", expr)' $GEN/VarkaConditionCompiler.java   # 12: one line, in compileCond
    grep -c "^    String what = switch (e) {" $GEN/VarkaNodeCompiler.java   # 13: 1, in floatingDecline
    grep -n -A1 "case Cast c when VarkaTypes.isNtz(c.dataType())" $GEN/VarkaNodeCompiler.java   # 14: two arms of arithmeticArm; the second, with isNtz(c.child().dataType()), is the identity
    grep -n "case TimestampNTZType n" $GEN/VarkaHashCompiler.java         # 15: one line, in word
    grep -n "to a decimal is row 350's" $GEN/VarkaDecimalCompiler.java    # 16: one line, in cast
    grep -n -A1 "private static String wideReason" $GEN/VarkaDecimalCompiler.java   # 17: the method's head and "return switch (e) {"
    grep -c 'default -> sink.decline("a decimal operator no arm builds yet' $GEN/VarkaDecimalCompiler.java   # 18: 1, the default of value
    grep -n 'intervalDeclineReason(plus) === "unsupported expression"' $GENS/VarkaExpressionCompilerSuite.scala   # 19: one line
    grep -n "the zoned .TIMESTAMP. the$" docs/sql-varka.md                # 20: one line
    grep -c -i "timestamp" $GENT/varka/VarkaEmitDump.java                 # 21: 0
    git branch --list 'varka-656a*'                                       # 22: nothing
    grep -n "and the zoned {@code TIMESTAMP}, which it does$" $IR/VarkaTypeId.java   # 23: one line
    grep -n "// Declined by decision (VarkaTypes)." $EXEC/VarkaLaneArrow.java        # 24: one line
    grep -n "// lane, by decision (VarkaTypes): a predicate on it alone" $EXECS/VarkaLongLaneSuite.scala   # 25: one line

| line | it prints | what the builder does |
| :--- | :--- | :--- |
| 2 | `-` in the first field | stop, and report "row 654's part b is not merged" |
| 2 | `owed` or `on_lane` in the second | part A is merged: report it and end |
| 3 or 4 | another number | stop: row 653 (5.0) |
| 21 | another count | no stop: row 657's first part merged first and added the two column types. Leave step 8 out |

**The steps.**

1. `<ir>/VarkaTypes.java`. After `isNtz`, add:

       /**
        * Whether {@code dataType} is the microsecond {@code TIMESTAMP}, an instant: the test
        * the zoned declines share. False for the nanosecond types and for
        * {@code TIMESTAMP_NTZ}.
        */
       public static boolean isLtz(DataType dataType) {
         return DataTypes.TimestampType.equals(dataType);
       }

   In `entry`, the `TIMESTAMP` case: the admission becomes `new Admission.Owed("656")`; every
   other argument stays as B0 printed it. Its comment becomes: "The zoned type is the same
   eight bytes as the others on the long lane. Its comparisons, null tests and choices read no
   time zone; its fields, its casts with DATE and its arithmetic read the session time zone
   (`m9/PLAN.md` 2.1). Row 656 puts it on the lane."
   `<ir>/VarkaTypeId.java`, the first four lines of the class comment. Old:

        * The Spark types Varka reads or plans to read, and the zoned {@code TIMESTAMP}, which it does
        * not read by decision: one id per type, whatever its forms (every precision of a decimal or a
        * {@code TIME}, every field range of an interval). {@link VarkaTypes#of} maps a Spark type to
        * its id, and {@link VarkaTypes#info} holds the facts about it.

   New:

        * The Spark types Varka reads or plans to read: one id per type, whatever its forms (every
        * precision of a decimal or a {@code TIME}, every field range of an interval).
        * {@link VarkaTypes#of} maps a Spark type to its id, and {@link VarkaTypes#info} holds the facts
        * about it.

   `<exec>/VarkaLaneArrow.java`, one comment line, above `case TIMESTAMP -> null;`: `//
   Declined by decision (VarkaTypes).` becomes `// Owed to row 656 (VarkaTypes): no lane reads
   it yet.` Nothing else of that file in this part.
2. `<gen>/VarkaZonedDeclines.java`, new, package `org.apache.spark.sql.catalyst.expressions
   .codegen`, `final class`, package-private, with the license header of `VarkaDecline.java`
   and a private constructor. Its class comment says what 4.3 says in its first two
   paragraphs. Its members, whole:

       /** The text of an expression that a later row fuses: what it is, and the row. */
       static String notYet(String what, int row) {
         return what + " is row " + row + "'s: not fused yet";
       }

       /** Whether {@code e} has a TIMESTAMP operand or result. */
       private static boolean touches(Expression e) {
         if (VarkaTypes.isLtz(e.dataType())) {
           return true;
         }
         var children = e.children().iterator();
         while (children.hasNext()) {
           if (VarkaTypes.isLtz(children.next().dataType())) {
             return true;
           }
         }
         return false;
       }

       /**
        * Why {@code e}, which no arm claimed, declines, where it is an expression over a
        * TIMESTAMP or convert_timezone; empty for any other expression.
        */
       static Optional<String> reason(Expression e) {
         // Row 670 owns convert_timezone whichever timestamp it is given: its operand is a
         // TIMESTAMP_NTZ, and a TIMESTAMP reaches it under a cast.
         if (e instanceof ConvertTimezone) {
           return Optional.of(notYet("convert_timezone", 670));
         }
         if (!touches(e)) {
           return Optional.empty();
         }
         return Optional.of(switch (e) {
           // A literal and an IN list are read once the type is on the long lane.
           case Literal _ -> notYet("a TIMESTAMP literal", 656);
           case In _ -> notYet("IN over a TIMESTAMP", 656);
           case InSet _ -> notYet("IN over a TIMESTAMP", 656);
           case Hour _ -> notYet("hour of a TIMESTAMP", 657);
           case Minute _ -> notYet("minute of a TIMESTAMP", 657);
           case Second _ -> notYet("second of a TIMESTAMP", 657);
           case SecondWithFraction _ -> notYet("the fractional second of a TIMESTAMP", 668);
           case TimeBucket _ -> notYet("time_bucket of a TIMESTAMP", 668);
           case Cast c -> cast(c);
           case TruncTimestamp _ -> notYet("date_trunc", 670);
           case MonthsBetween _ -> notYet("months_between", 670);
           case FromUTCTimestamp _ -> notYet("from_utc_timestamp", 670);
           case ToUTCTimestamp _ -> notYet("to_utc_timestamp", 670);
           case MakeTimestamp _ -> notYet("make_timestamp", 670);
           // make_timestamp of a date and a time, and a TIME cast to TIMESTAMP after the
           // optimizer's rewrite, arrive as this call.
           case StaticInvoke si when "makeTimestamp".equals(si.functionName()) ->
               notYet("make_timestamp", 670);
           case SubtractTimestamps _ -> notYet("TIMESTAMP minus TIMESTAMP", 671);
           case TimestampAddInterval _ -> notYet("a TIMESTAMP plus a day-time interval", 671);
           case TimestampAddYMInterval _ ->
               notYet("a TIMESTAMP plus a year-month interval", 671);
           case TimestampAdd _ -> notYet("timestampadd of a TIMESTAMP", 671);
           case SecondsToTimestamp _ -> notYet("timestamp_seconds", 667);
           case MillisToTimestamp _ -> notYet("timestamp_millis", 667);
           case MicrosToTimestamp _ -> notYet("timestamp_micros", 667);
           case UnixSeconds _ -> notYet("unix_seconds", 667);
           case UnixMillis _ -> notYet("unix_millis", 667);
           case UnixMicros _ -> notYet("unix_micros", 667);
           case KnownNotNull _ -> notYet("known_not_null of a TIMESTAMP", 667);
           case TryEval _ -> notYet("try_eval of a TIMESTAMP", 667);
           default -> e.nodeName() + " over a TIMESTAMP: no row of milestone 9 fuses it";
         });
       }

       /** A cast with a TIMESTAMP side: the row of the pair, as the cast matrix has it. */
       private static String cast(Cast c) {
         DataType from = c.child().dataType();
         DataType to = c.dataType();
         String spelled = "CAST(" + from.sql() + " AS " + to.sql() + ")";
         if (from instanceof StringType || to instanceof StringType) {
           return spelled + ": a STRING side is out by the owner's decision of 14 October 2026";
         }
         DataType other = VarkaTypes.isLtz(from) ? to : from;
         if (VarkaTypes.idOf(other) == null) {
           return c.nodeName() + " over a TIMESTAMP: no row of milestone 9 fuses it";
         }
         if (VarkaTypes.isLtz(from) && DataTypes.DateType.equals(to)) {
           return notYet(spelled, 657);
         }
         boolean toInstant = VarkaTypes.isLtz(to)
             && (DataTypes.DateType.equals(from) || VarkaTypes.isNtz(from));
         return notYet(spelled, toInstant ? 690 : 669);
       }

   The imports are the classes named, from `org.apache.spark.sql.catalyst.expressions`
   (`StaticInvoke` from its `objects` package), `org.apache.spark.sql.types` and
   `...codegen.varka.VarkaTypes`.
3. `<gen>/VarkaNodeCompiler.java`, three edits.
   - `fallback`: its last statement becomes
     `return sink.decline(VarkaZonedDeclines.reason(e).orElse("unsupported expression"), e);`.
     The comment above `fallback` gains one sentence: "An expression over a TIMESTAMP that no
     arm claims declines naming the row that fuses it (VarkaZonedDeclines)."
   - `floatingDecline`: immediately before its line `String what = switch (e) {` (B0's line
     13), these lines:

           // An expression over a TIMESTAMP is named by the row that fuses it, whether or not it
           // has a floating side: a cast, months_between, timestamp_seconds of a double.
           Optional<String> zoned = VarkaZonedDeclines.reason(e);
           if (zoned.isPresent()) {
             return zoned;
           }

     They come after the method's first `if`, which returns empty for every form a floating
     lowering serves, so they answer only for a form that would decline here anyway.
   - `arithmeticArm`: immediately after the `TIMESTAMP_NTZ` identity case (B0's second arm),
     the same case with `isLtz` twice and `() -> compileNode(c.child(), inputs, literals,
     sink)`, under the comment "The identity cast of a TIMESTAMP is its child, as the
     TIMESTAMP_NTZ one is, and reads no time zone. The optimizer removes it from every plan,
     so only a tree built by hand holds one."
4. `<gen>/VarkaConditionCompiler.java`, `compileCond`: where `arm == null`, the statement
   becomes this one line of 97 columns:

             return sink.decline(VarkaZonedDeclines.reason(expr).orElse("unsupported predicate"), expr);

5. `<gen>/VarkaHashCompiler.java`, `word`: before `default`, with the import of
   `TimestampType`:

             case TimestampType _ ->
                 sink.decline(VarkaZonedDeclines.notYet("a hash of a TIMESTAMP", 667), child);

6. `<gen>/VarkaDecimalCompiler.java`, three edits.
   - `cast`: a new first statement, and one clause in the method's comment, "a cast from a
     TIMESTAMP names row 669":

           if (VarkaTypes.isLtz(c.child().dataType())) {
             return sink.decline(VarkaZonedDeclines.reason(c).orElseThrow(), c);
           }

   - `wideReason`: new first statements, before `return switch (e) {`:

           // A cast from a TIMESTAMP is named by its own row, whatever the target's width.
           Optional<String> zoned = VarkaZonedDeclines.reason(e);
           if (zoned.isPresent()) {
             return zoned.get();
           }

   - `value`: the `default` of its last switch asks the table first and keeps its text. Its
     two lines become three:

             default -> sink.decline(VarkaZonedDeclines.reason(e).orElse("a decimal operator no arm "
                 + "builds yet: the rounding functions are row 351's, and a quotient or a remainder is "
                 + "no row's in this milestone"), e);

7. `<gent>/varka/VarkaFuzzTypes.java`: the `TIMESTAMP` case becomes `entry(id, new
   Owed<>("656"), new Owed<>("656"), new Owed<>("656"))`. Delete the method `declined`, which
   has no caller left, and an import that only it used.
8. `<gent>/varka/VarkaEmitDump.java`, `columnType`: `case "timestamp" ->
   DataTypes.TimestampType;` and `case "timestamp_ntz" -> DataTypes.TimestampNTZType;` after
   `"time"`, the comment above them widened to "the long lane's types"; one example line in the
   class comment and one in the header of `dev/varka_emit.sh`:
   `dev/varka_emit.sh "ts < ts2" --columns ts:timestamp,ts2:timestamp`.
9. The tests of the table below: one new suite, and edits of three.
10. `docs/sql-varka.md`, two lines that hold one sentence:

    | the old line | the new line |
    | :--- | :--- |
    | ``  column: precision above 18 is row ..."), and the zoned `TIMESTAMP` the`` | ``  column: precision above 18 is row ..."), and a `TIMESTAMP` column the`` |
    | ``  decision that keeps it out (VARKA-494). Since VARKA-38, a `date_add`/`` | ``  row that puts it on the long lane (VARKA-656). Since VARKA-38, a `date_add`/`` |

11. Regenerate: `dev/varka_regen_generated.sh --all --full`.
12. `$M9/PLAN.md`: row 656's cell, `**Planned**` becomes `**Started**`. This file, section 11:
    what part A built.

**The tests.** The new suite, `VarkaZonedDeclinesSuite`, is Java, JUnit 5, under
`@VarkaSuite`, in `<gent>` (package `org.apache.spark.sql.catalyst.expressions.codegen`),
beside `VarkaMathCompilerSuite`, since it calls the package-private
`VarkaZonedDeclines.reason` and `VarkaExpressionCompiler`. Each `@Test` carries the case's
sentence as its `@DisplayName`. Every compile starts from `VarkaMatrixRuns.BASE`. The twin
suite is part A2's.

`VarkaZonedDeclinesSuite` copies from `VarkaMathCompilerSuite` the method `outcome(Expression
e, Set<VarkaFamily> off, VarkaEmitOptions options)`, which calls nothing else of that suite
but its `COLUMNS`, and calls it with `Set.of()` and `VarkaMatrixRuns.BASE`; the resolver of a
form is the caller's (the tables below name it). Its
columns: `ts`, `ts2` (`TIMESTAMP`), `ntz` (`TIMESTAMP_NTZ`), `d` (`DATE`), `l` (`BIGINT`), `i`
(`INT`), `y1` (`TINYINT`), `s1` (`SMALLINT`), `f` (`FLOAT`), `x` (`DOUBLE`), `d18`
(`DECIMAL(18, 2)`), `b` (`BOOLEAN`), `t` (`TIME(6)`), `dt` (`INTERVAL DAY TO SECOND`), `ym`
(`INTERVAL YEAR TO MONTH`). A form is resolved by `VarkaSqlResolve.parse` unless the table
says otherwise. **A form that prints another text than its line says is a stop:** report the
form and the text; do not change the expectation.

| suite | case | forms | expected, and its oracle | the failure only it catches |
| :--- | :--- | :--- | :--- | :--- |
| `VarkaZonedDeclinesSuite` | each cast of a TIMESTAMP that is no identity declines naming the row of its pair | the 22 pairs of `VARKA-654.md` 4.8, one form each, and the second width of the decimal: the table "The 22 cast forms" below | the text beside each form, exactly | a pair named by another site's text: the floating gate's, the decimal compiler's, or the general one |
| the same | every other expression that reads the session time zone over a TIMESTAMP declines naming its row, while it declines | `hour(ts)`, `minute(ts)`, `second(ts)`, `extract(HOUR FROM ts)`: the three `... of a TIMESTAMP is row 657's` texts, `extract` the hour's. `year(ts)`, `last_day(ts)`, `date_part('YEAR', ts)`, `d < ts`, `ntz < ts`: the text of the cast under each (657 for the first three, `CAST(DATE AS TIMESTAMP)` and `CAST(TIMESTAMP_NTZ AS TIMESTAMP)` with 690 for the last two). `extract(SECOND FROM ts)`, `time_bucket(INTERVAL '1' HOUR, ts)`: the two texts of row 668. `date_trunc('HOUR', ts)`, `months_between(ts, ts2)`, `from_utc_timestamp(ts, 'Asia/Kathmandu')`, `to_utc_timestamp(ts, 'Asia/Kathmandu')`, `convert_timezone('Asia/Kathmandu', ts)`, and `convert_timezone('UTC', 'Asia/Kathmandu', ntz)`, which holds no `TIMESTAMP` at all: `<name> is row 670's: not fused yet`. `make_timestamp(i, i, i, i, i, i)`, `try_make_timestamp(i, i, i, i, i, i)`, `make_timestamp_ltz(i, i, i, i, i, i)`, `make_timestamp(d, t)`, `make_timestamp_ltz(d, t)`, `try_make_timestamp(d, t)`: `make_timestamp is row 670's: not fused yet`, all six. `ts - ts2`, `ts - d`: `TIMESTAMP minus TIMESTAMP is row 671's: not fused yet`. `ts + dt`, `ts - dt`: `a TIMESTAMP plus a day-time interval is row 671's: not fused yet`. `ts + ym`: the year-month text. `timestampadd(HOUR, i, ts)`: `timestampadd of a TIMESTAMP is row 671's: not fused yet` | for each form that declines, the reason equals the text, exactly. A form that fuses is not judged here: `VarkaTimestampTwinSuite`, part A2's, judges it. Oracle: the table of 4.3 | a zone-reading form that declines as `unsupported expression`; a `RuntimeReplaceable` form named by its replacement's class |
| the same | every expression over a TIMESTAMP that reads no time zone and is a later row's declines naming that row, while it declines | `timestamp_seconds(l)`, `timestamp_seconds(x)` and `timestamp_seconds(f)` (a `DOUBLE` and a `FLOAT` operand, which the floating check meets first), `timestamp_millis(l)`, `timestamp_micros(l)`, `unix_seconds(ts)`, `unix_millis(ts)`, `unix_micros(ts)`: `<name> is row 667's: not fused yet`. `hash(ts)`, `xxhash64(ts)`: `a hash of a TIMESTAMP is row 667's: not fused yet`. `date_format(ts, 'yyyy')`: `DateFormatClass over a TIMESTAMP: no row of milestone 9 fuses it`. `CAST(ts AS STRING)`: the STRING text of 4.3 | exact texts | the hash named by its family's general text |
| the same | a TIMESTAMP column, a literal and an IN list decline naming row 656 | `ts < ts2`, `ts = ts2`, `ts <=> ts2`, `ts IS NULL`, `greatest(ts, ts2)`, `coalesce(ts, ts2)`, `if(ts IS NULL, ts2, ts)`: `a timestamp column: the TIMESTAMP lane is row 656's`. `TIMESTAMP '2021-06-01 12:00:00Z' < ts`: `a TIMESTAMP literal is row 656's: not fused yet`. `ts IN (TIMESTAMP '2021-06-01 12:00:00Z', TIMESTAMP '1969-12-31 18:00:00Z')`: `IN over a TIMESTAMP is row 656's: not fused yet` | exact texts | a form that fuses before the lane has its Arrow vector; a literal named as "Literal over a TIMESTAMP" |
| the same | the arms that no SQL reaches answer by their class | `VarkaZonedDeclines.reason`, called on trees built by hand over the column `ts`: `new KnownNotNull(ts)`, `new TryEval(ts)`, `new InSet(ts, <a Scala set of two longs>)`, and `new Cast(ts, DataTypes.BinaryType, Option.empty(), EvalMode.LEGACY())` | `known_not_null of a TIMESTAMP is row 667's: not fused yet`; `try_eval of a TIMESTAMP is row 667's: not fused yet`; `IN over a TIMESTAMP is row 656's: not fused yet`; `Cast over a TIMESTAMP: no row of milestone 9 fuses it` | an arm of the table with no test |
| the same | an expression with no TIMESTAMP operand or result is not this table's | `VarkaZonedDeclines.reason` of the resolved `hour(ntz)`, `hour(t)`, `ntz - ntz`, `i % 7`, `CAST(ntz AS DATE)`; and of the resolved `convert_timezone('UTC', 'Asia/Kathmandu', ntz)` | empty for the first five. For the last, `convert_timezone is row 670's: not fused yet`: the one answer the table gives with no `TIMESTAMP` in the tree, on purpose (4.3) | a table that names a `TIMESTAMP_NTZ` form as a zoned one; the exception for `convert_timezone` lost |
| `VarkaTypesSuite` (existing, `<gent>/varka`) | a column of an owed, declined or condition-only type declines naming its type | `DataTypes.TimestampType` | the expected string becomes `a timestamp column: the TIMESTAMP lane is row 656's` | the registry's text |
| `VarkaExpressionCompilerSuite` (existing, ScalaTest, `<gens>`) | a zoned timestamp column declines with its type's reason, and an NTZ one fuses | as it is | the value `reason` becomes the text above. The comment's first two lines, `// The zoned type is out by decision (VarkaTypes, VARKA-494), and its reason names the type,` and `// so EXPLAIN tells a decision from a gap. NTZ is read on the long lane (VARKA-338): its`, become `// The zoned type is owed to row 656 (VarkaTypes), and its reason names the type and the row,` and `// so EXPLAIN tells a plan from a gap. NTZ is read on the long lane (VARKA-338): its` | - |
| the same | `i * INTERVAL '1' DAY leaves the date lane ...` | as it is | `intervalDeclineReason(plus)` equals `a TIMESTAMP plus a day-time interval is row 671's: not fused yet` | the fallback's hook, seen through an old test |
| the same | new, after the zoned test: "the identity cast of a TIMESTAMP is its child" | `Greatest(Seq(Cast(ts, TimestampType), ts))` over `withLong`, built by hand: the resolver would strip the cast | `declineReason` of it equals `a timestamp column: the TIMESTAMP lane is row 656's`: the arm compiled the cast as its child, and the child is the column | the identity arm, which no SQL reaches. Without it the text is `CAST(TIMESTAMP AS TIMESTAMP) is row 669's: not fused yet` |
| `VarkaFuzzTypesSuite`, `VarkaFamilyChainSuite`, `VarkaFamilySwitchSuite`, `VarkaLaneArrowSuite` (existing) | all | - | pass with no edit | a second claimant of a node; a type on a lane with no vector |
| `VarkaLongLaneSuite` (existing, `<execs>`) | a zoned timestamp comparison stays on the row engine, and an NTZ one fuses | as it is | passes; one comment line changes: `// lane, by decision (VarkaTypes): a predicate on it alone fuses nothing, and beside a date` becomes `// lane until row 656's (VarkaTypes): a predicate on it alone fuses nothing, and beside a date` | - |

**The 22 cast forms.** Each is `CAST(<column> AS <type>)`, resolved by `VarkaSqlResolve.parse`
under the suite's default conf, but the two marked LEGACY, which are illegal under ANSI and
are resolved with `spark.sql.ansi.enabled` set to `false` on a conf passed to
`SQLConf$.MODULE$.withExistingConf`. `<P>` in a text stands for `is row N's: not fused yet`
with the row of the last column.

| pair | form | text | row |
| :--- | :--- | :--- | ---: |
| `bool` to `ltz`, LEGACY | `CAST(b AS TIMESTAMP)` | `CAST(BOOLEAN AS TIMESTAMP) <P>` | 669 |
| `ltz` to `bool`, LEGACY | `CAST(ts AS BOOLEAN)` | `CAST(TIMESTAMP AS BOOLEAN) <P>` | 669 |
| `byte` to `ltz` | `CAST(y1 AS TIMESTAMP)` | `CAST(TINYINT AS TIMESTAMP) <P>` | 669 |
| `ltz` to `byte` | `CAST(ts AS TINYINT)` | `CAST(TIMESTAMP AS TINYINT) <P>` | 669 |
| `short` to `ltz` | `CAST(s1 AS TIMESTAMP)` | `CAST(SMALLINT AS TIMESTAMP) <P>` | 669 |
| `ltz` to `short` | `CAST(ts AS SMALLINT)` | `CAST(TIMESTAMP AS SMALLINT) <P>` | 669 |
| `int` to `ltz` | `CAST(i AS TIMESTAMP)` | `CAST(INT AS TIMESTAMP) <P>` | 669 |
| `ltz` to `int` | `CAST(ts AS INT)` | `CAST(TIMESTAMP AS INT) <P>` | 669 |
| `bigint` to `ltz` | `CAST(l AS TIMESTAMP)` | `CAST(BIGINT AS TIMESTAMP) <P>` | 669 |
| `ltz` to `bigint` | `CAST(ts AS BIGINT)` | `CAST(TIMESTAMP AS BIGINT) <P>` | 669 |
| `float` to `ltz` | `CAST(f AS TIMESTAMP)` | `CAST(FLOAT AS TIMESTAMP) <P>` | 669 |
| `ltz` to `float` | `CAST(ts AS FLOAT)` | `CAST(TIMESTAMP AS FLOAT) <P>` | 669 |
| `double` to `ltz` | `CAST(x AS TIMESTAMP)` | `CAST(DOUBLE AS TIMESTAMP) <P>` | 669 |
| `ltz` to `double` | `CAST(ts AS DOUBLE)` | `CAST(TIMESTAMP AS DOUBLE) <P>` | 669 |
| `dec` to `ltz` | `CAST(d18 AS TIMESTAMP)` | `CAST(DECIMAL(18,2) AS TIMESTAMP) <P>` | 669 |
| `ltz` to `dec`, one word | `CAST(ts AS DECIMAL(18, 6))` | `CAST(TIMESTAMP AS DECIMAL(18,6)) <P>` | 669 |
| `ltz` to `dec`, two words | `CAST(ts AS DECIMAL(20, 6))` | `CAST(TIMESTAMP AS DECIMAL(20,6)) <P>` | 669 |
| `date` to `ltz` | `CAST(d AS TIMESTAMP)` | `CAST(DATE AS TIMESTAMP) <P>` | 690 |
| `ltz` to `date` | `CAST(ts AS DATE)` | `CAST(TIMESTAMP AS DATE) <P>` | 657 |
| `ntz` to `ltz` | `CAST(ntz AS TIMESTAMP)` | `CAST(TIMESTAMP_NTZ AS TIMESTAMP) <P>` | 690 |
| `ltz` to `ntz` | `CAST(ts AS TIMESTAMP_NTZ)` | `CAST(TIMESTAMP AS TIMESTAMP_NTZ) <P>` | 669 |
| `time` to `ltz`, as written | `CAST(t AS TIMESTAMP)`, resolved by `VarkaSqlResolve.parseAsWritten` | `CAST(TIME(6) AS TIMESTAMP) <P>` | 669 |
| `time` to `ltz`, as a query hands it | `CAST(t AS TIMESTAMP)`, resolved by `VarkaSqlResolve.parse` | `make_timestamp <P>` | 670 |
| `ltz` to `time` | `CAST(ts AS TIME)` | `CAST(TIMESTAMP AS TIME(6)) <P>` | 669 |

That is 24 lines for 22 pairs: the decimal target has two widths and the `TIME` source two
forms.

**Planted faults** (commit first; plant one, rerun the first command, revert it).

| fault | the test that must fail |
| :--- | :--- |
| `fallback` gives `"unsupported expression"` again | "every other expression that reads the session time zone ..." |
| `touches` returns true for every expression | "an expression with no TIMESTAMP operand or result is not this table's" |
| the new statements of `floatingDecline` deleted | "each cast of a TIMESTAMP that is no identity ...", on `CAST(x AS TIMESTAMP)`; "every other expression that reads the session time zone ...", on `months_between(ts, ts2)`; and the zone-free test, on `timestamp_seconds(x)` |
| the new statements of `wideReason` deleted | the cast test, on `CAST(ts AS DECIMAL(20, 6))` |
| the `default` of `VarkaDecimalCompiler.value` gives its old text alone | "every other expression that reads the session time zone ...", on `extract(SECOND FROM ts)` |
| in `cast`, `toInstant ? 690 : 669` becomes `669` | the same test, on `CAST(d AS TIMESTAMP)` |
| the case of `StaticInvoke` deleted | "every other expression that reads the session time zone ...", on `make_timestamp(d, t)` |
| the identity arm of `arithmeticArm` deleted | `VarkaExpressionCompilerSuite`, "the identity cast of a TIMESTAMP is its child" |
| the entry's admission is `new Admission.OnLane(LaneType.LONG)` and nothing else changes | "a TIMESTAMP column, a literal and an IN list decline naming row 656"; and `VarkaLaneArrowSuite`'s "a type has an Arrow vector exactly when a lane reads it, or it is an output vector" in command 5 |

**The commands,** in order; each comment is what the line must print.

    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaZonedDeclinesSuite *VarkaTypesSuite *VarkaFuzzTypesSuite *VarkaExpressionCompilerSuite *VarkaFamilyChainSuite *VarkaFamilySwitchSuite *VarkaCoverageSuite'
                                                # 1: Failed 0, Errors 0
    dev/varka_regen_generated.sh --all --full; echo $?                    # 2: 0
    grep -P "^TIMESTAMP\t" sql/varka/types.tsv | cut -f3,5,7              # 3: ltz, owed, 656
    grep -P "^TIMESTAMP\t" sql/varka/fuzz/types.tsv | grep -o "owed:656" | wc -l   # 4: 3
    flock "$VARKA_HEAVY_LOCK" build/sbt 'sql/testOnly *VarkaLongLaneSuite *VarkaLaneArrowSuite *VarkaCastWithAnsiOffSuite *VarkaCastWithAnsiOnSuite *VarkaTryCastSuite'
                                                # 5: Failed 0, Errors 0
    git diff -U0 -- $M9/casts_suites.tsv | grep -c '^+[^+]'               # 6: S, the count of B0's line 6
    git diff -U0 -- $M9/casts_suites.tsv | grep '^[-+][^-+]' | grep -v -c -P "\tTIMESTAMP\t"   # 7: 0: no other line moved
    git diff --stat -- sql/varka/coverage.json sql/varka/emitted_bytes.json sql/varka/width_audit.json $M9/census.tsv $M9/casts.tsv $M9/casts_fused.tsv   # 8: nothing
    python3 dev/varka_bytes_moved.py origin/master                        # 9: nothing
    flock "$VARKA_HEAVY_LOCK" dev/varka_emit.sh "hour(ts)" "ts < ts2" "CAST(ts AS BIGINT)" --columns ts:timestamp,ts2:timestamp
                                                # 10: "nothing fused"; entry 0 with "hour of a TIMESTAMP is row 657's: not fused yet";
                                                #     entry 1 with "a timestamp column: the TIMESTAMP lane is row 656's";
                                                #     entry 2 with "CAST(TIMESTAMP AS BIGINT) is row 669's: not fused yet"
    python3 dev/varka_census.py --check; echo $?                          # 11: 0
    python3 dev/varka_census.py --summary | grep '^  ltz '                # 12: B0's line 5 again: 0 / N
    python3 dev/varka_new_lane.py --check | grep -c TimestampType         # 13: 0
    git status --short                                                    # 14: the list below, and no other line
    python3 dev/varka_push_check.py; echo $?                              # 15: a size under 900, then 0;
                                                #     casts_suites.tsv is not in the count (section 5)

**The expected `git status --short`** before the commit, 21 lines. The census files, the docs'
generated blocks, `coverage.json`, `emitted_bytes.json` and `width_audit.json` are not in it:
the census prints no admission (`VARKA-654.md` 11), and the docs' Lanes cell reads `plain` for
an owed type as for a declined one.

     M dev/varka_emit.sh
     M docs/sql-varka.md
     M <gen>/VarkaConditionCompiler.java
     M <gen>/VarkaDecimalCompiler.java
     M <gen>/VarkaHashCompiler.java
     M <gen>/VarkaNodeCompiler.java
     M <ir>/VarkaTypeId.java
     M <ir>/VarkaTypes.java
     M <exec>/VarkaLaneArrow.java
     M <gent>/varka/VarkaEmitDump.java
     M <gent>/varka/VarkaFuzzTypes.java
     M <gent>/varka/VarkaTypesSuite.java
     M <gens>/VarkaExpressionCompilerSuite.scala
     M <execs>/VarkaLongLaneSuite.scala
     M sql/varka/fuzz/types.tsv
     M sql/varka/plans/m9/PLAN.md
     M sql/varka/plans/m9/VARKA-656.md
     M sql/varka/plans/m9/casts_suites.tsv
     M sql/varka/types.tsv
    ?? <gen>/VarkaZonedDeclines.java
    ?? <gent>/VarkaZonedDeclinesSuite.java

Where B0 found the emit tool's column types already there, the lines of `dev/varka_emit.sh`
and `VarkaEmitDump.java` are absent, and the list has 19 lines.

**What exists after it** (a list to tick).

- [ ] `VarkaTypes.isLtz(DataType)`, public and static.
- [ ] `VarkaZonedDeclines`, with `reason(Expression)` and `notYet(String, int)`.
- [ ] The identity arm for `TIMESTAMP` in `VarkaNodeCompiler.arithmeticArm`.
- [ ] `types.tsv` reads `owed` and `656` for `TIMESTAMP`; `fuzz/types.tsv` reads `owed:656`.
- [ ] `dev/varka_emit.sh` takes `--columns ts:timestamp` and `ntz:timestamp_ntz`.
- [ ] One new suite in `<gent>`, `VarkaZonedDeclinesSuite`.
- [ ] What does not change: `coverage.json`, `emitted_bytes.json`, `width_audit.json`, the
  census files (`ltz` still reads `0 / N`), `casts.tsv`, `casts_fused.tsv`, every benchmark,
  every answer.
- [ ] Prediction 1 of section 7, scored in section 11.

**Done when** the commands print what the table says, each planted fault fails its test, and
the review of the diff (`/code-review high`) has no open finding. **Not here:** the Arrow
table, but for its one comment line; a coverage row or column; a census cell; `nullLeaf`; the
fixtures; the conformance
suite; the two-lane text; any edit of `VarkaTimeCompiler` or `VarkaChronoCompiler`; any edit
of `dev/varka_census.py`.

### 5.1a Part A2: the twin suite

Tests alone: one new file. It holds every `TIMESTAMP_NTZ` coverage row's twin over a
`TIMESTAMP` to one rule: a twin that reads the session time zone is never computed as its row.

**B0, the premises.** Run each line; its comment is what it must print. A line that prints
otherwise is a stop, but for the case under the block.

    grep -P "^TIMESTAMP\t" sql/varka/types.tsv | cut -f3,5,7              # 1: ltz, owed, 656 (part A merged, part B not)
    grep -c "static Optional<String> reason(Expression e)" $GEN/VarkaZonedDeclines.java   # 2: 1
    grep -c "Optional<String> zoned = VarkaZonedDeclines.reason(e);" $GEN/VarkaNodeCompiler.java   # 3: 1: the floating check's hook
    grep -c 'VarkaZonedDeclines.reason(e).orElse("unsupported expression")' $GEN/VarkaNodeCompiler.java   # 4: 1: the fallback's hook
    ls $GENT/VarkaTimestampTwinSuite.java 2>/dev/null | wc -l             # 5: 0
    grep -c -P '"executable" : ".*\bntz2?\b' sql/varka/coverage.json      # 6: 70 or more (77 on the plan's commit)
    grep -c "private record Row(String sql, String censusName, boolean predicate, Expression expr)" $GENT/VarkaFamilySwitchSuite.java   # 7: 1
    grep -c "private static Outcome compile(Row row, Set<VarkaFamily> off, Seq<Attribute> columns)" $GENT/VarkaFamilySwitchSuite.java   # 8: 1
    grep -c "static void readCoverage() throws IOException" $GENT/VarkaFamilySwitchSuite.java   # 9: 1
    git branch --list 'varka-656a2*'                                      # 10: nothing

| line | it prints | what the builder does |
| :--- | :--- | :--- |
| 1 | `on_lane` | part B merged first: stop and report; the suite's fourth test then needs part B's count (5.2, step 8) |

**The steps.**

1. `<gent>/VarkaTimestampTwinSuite.java`, new, package
   `org.apache.spark.sql.catalyst.expressions.codegen`, Java, JUnit 5, `@VarkaSuite public
   final class VarkaTimestampTwinSuite`, with the license header of `VarkaFamilySwitchSuite`.
   Its class comment says the rule above and why the zone is `America/Los_Angeles`: under UTC
   a kernel that read the zone would answer as the `TIMESTAMP_NTZ` kernel does.
   - **Copied from `VarkaFamilySwitchSuite`, whole:** the records `Row` and `Outcome`; the
     constant `OPTIONS`; the static method `compile(Row row, Set<VarkaFamily> off,
     Seq<Attribute> columns)`, which calls nothing else of that suite; and the body of
     `readCoverage` from its first line to the loop that fills `attrs` and the line that
     assigns `columns`. Not copied: the loop that builds `rows` (this suite builds its own,
     below), `readRecordedDeclines`, and everything else.
   - **The imports** are those lines of `VarkaFamilySwitchSuite`'s import block that the
     copied code and the code below use: `assertTrue` and `assertEquals`; `IOException`,
     `Files`, `Path`, `ArrayList`, `List`, `Optional`, `Set`; `JsonNode`, `ObjectMapper`;
     `BeforeAll`, `DisplayName`, `Test`; `Seq`, `CollectionConverters`; `Attribute`, `Cast`,
     `Expression`, `NamedExpression`, `RuntimeReplaceable`; `VarkaAdmission`,
     `VarkaEmitOptions`, `VarkaFamily`, `VarkaMatrixRuns`, `VarkaSqlResolve`, `VarkaSuite`;
     `CatalystSqlParser$`, `SQLConf`, `SQLConf$`. And four more:
     `org.apache.spark.sql.catalyst.expressions.TimeZoneAwareExpression`,
     `org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes`,
     `org.apache.spark.sql.types.DataTypes`, and whatever `compile` names of the package's own
     classes, which need no import.
   - **The columns.** After the copied lines assign `columns`: where the table has no column
     named `ts`, the suite adds `VarkaSqlResolve.column("ts", DataTypes.TimestampType)` and
     the same for `ts2`. After part B the table holds both, and nothing is added.
   - **The conf.** `private static final SQLConf LA = new SQLConf();` with, in a static
     block, `LA.setConfString("spark.sql.session.timeZone", "America/Los_Angeles");`.
   - **The pairs.** In `@BeforeAll`, for every expression of `coverage.json` whose
     `executable` (its `sql` where the `executable` is empty) matches the regular expression
     `\bntz2?\b`: the twin's text is the row's with `ntz2` replaced by `ts2`, then `ntz` by
     `ts` (both by that expression with word boundaries), then `TIMESTAMP_NTZ` by
     `TIMESTAMP`. The row and the twin are each resolved by `SQLConf$.MODULE$
     .withExistingConf(LA, () -> VarkaSqlResolve.parse(text, columns))` into a `Row` whose
     `predicate` is the coverage row's `form` being `predicate`. A twin whose resolution
     throws is kept with the exception's message, and no `Row`.
   - **Whether a twin reads the zone:**

           private static boolean readsZone(Expression e) {
             if (e instanceof RuntimeReplaceable r && readsZone(r.replacement())) {
               return true;
             }
             boolean zoned = VarkaTypes.isLtz(e.dataType());
             var children = CollectionConverters.asJava(e.children());
             for (Expression child : children) {
               zoned |= VarkaTypes.isLtz(child.dataType());
             }
             boolean aware = e instanceof TimeZoneAwareExpression
                 && !(e instanceof Cast c && !c.needsTimeZone());
             if (zoned && aware) {
               return true;
             }
             for (Expression child : children) {
               if (readsZone(child)) {
                 return true;
               }
             }
             return false;
           }

   - The four tests of the table below, each a loop over the pairs that collects the texts
     that break its rule and ends in `assertEquals(List.of(), broken)`.
2. This file, section 11: what part A2 built, and prediction 7 scored.

**The tests.**

| case (`@DisplayName`) | inputs | expected, and its oracle | the failure only it catches | before part B |
| :--- | :--- | :--- | :--- | :--- |
| every TIMESTAMP_NTZ coverage row has a twin over a TIMESTAMP that Spark analyses | the pairs; the test first asserts that there are at least 70, that no twin's text matches `\bntz2?\b` and that each matches `\bts2?\b` | no twin threw. A twin that throws is a stop for the builder: report the row and the analyzer's message | a row left out of the next three tests in silence | holds |
| a twin that reads the session time zone is never computed as its TIMESTAMP_NTZ row | the twins for which `readsZone` is true | `compile(twin, Set.of(), columns)` has no kernel, or its `kernel` differs from the kernel of `compile(row, Set.of(), columns)`. Oracle: Spark's own `TimeZoneAwareExpression` and `Cast.needsTimeZone` | a `TIMESTAMP_NTZ` arm widened to the lane, which answers right at UTC and wrong elsewhere | passes because every twin declines: the column is owed. It bites from part B on, whose planted fault shows it failing |
| a twin never declines as unsupported | the twins | a declining twin's `reason` is neither `unsupported expression` nor `unsupported predicate`. This test is a net over forms `VarkaZonedDeclinesSuite` does not list; it does not check which text | an unnamed decline in a form no list holds | holds: a twin declines at its column or by the table |
| a twin that reads no time zone and is a coverage row fuses to the kernel of its TIMESTAMP_NTZ row | the twins for which `readsZone` is false and whose text is the `executable` of some coverage row | the twin's `kernel` is present and equals its row's | a zone-free form that fuses to another kernel than its twin's | no twin is a coverage row yet, so the loop judges none and the test passes. Part B adds the rows and one assertion, that at least 15 were judged (5.2, step 8) |

So the suite needs one edit after this part, part B's one line, and none when a later row
fuses a form: a zone-reading twin that fuses rightly has a kernel that is not its row's.

**Planted faults** (commit first; plant one, rerun, revert).

| fault | the test that must fail |
| :--- | :--- |
| `VarkaNodeCompiler.fallback` gives `"unsupported expression"` again | "a twin never declines as unsupported" |
| in the suite, the replacement of `ntz` by `ts` left out, so that a twin is its own row | "every TIMESTAMP_NTZ coverage row has a twin ...": that test also asserts that no twin's text matches `\bntz2?\b` and that each matches `\bts2?\b` |

**The commands,** in order; each comment is what the line must print.

    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaTimestampTwinSuite *VarkaFamilySwitchSuite'
                                                # 1: Failed 0, Errors 0; the new suite ran 4 tests
    git status --short                                                    # 2: two lines, below
    python3 dev/varka_push_check.py; echo $?                              # 3: a size under 400, then 0

The two lines of `git status --short`:

     M sql/varka/plans/m9/VARKA-656.md
    ?? <gent>/VarkaTimestampTwinSuite.java

**What exists after it** (a list to tick).

- [ ] `VarkaTimestampTwinSuite` in `<gent>`, four tests, green on master with nothing fused.
- [ ] What does not change: every production file, every generated file, every other suite.
- [ ] Prediction 7 of section 7, scored in section 11.

**Done when** the commands print what the block says, the planted faults behave as the table
says, and the review of the diff has no open finding. **Not here:** any production code; any
edit of `VarkaFamilySwitchSuite` or `VarkaZonedDeclinesSuite`; a coverage row.

### 5.2 Part B: the lane

**B0, the premises.** Run each line; its comment is what it must print. A line that prints
otherwise is a stop, but for the cases under the block.

    grep -P "^TIMESTAMP\t" sql/varka/types.tsv | cut -f3,5,7              # 1: ltz, owed, 656
    git ls-files $M9/census_curated.txt $M9/casts_suites.tsv | wc -l      # 2: 2
    grep -c 'Family("TIMESTAMP_NTZ columns"' $GENS/varka/VarkaCoverageSuite.scala   # 3: 1
    grep -c "class VarkaTimestampTwinSuite" $GENT/VarkaTimestampTwinSuite.java   # 3a: 1 (part A2 merged)
    grep -c "case Literal _\|case In _\|case InSet _" $GEN/VarkaZonedDeclines.java   # 4: 3
    grep -c '"timestamp": "ltz"' dev/varka_census.py                      # 5: 2: CAST_ALIASES and SIMPLE_COLUMN_TYPES (VARKA-654.md 5.2, command 8)
    grep -c '^ntz_minus_ntz|SubtractTimestamps|datetime|ntz,ltz|dt|' $M9/census_curated.txt   # 6: 1
    grep '^isnull|' $M9/census_curated.txt | grep -c "ltz is row 656's"   # 7: 1
    grep '^in_set|' $M9/census_curated.txt | grep -c "ltz is row 667's"   # 8: 1
    grep -c "ltz on the long lane" $M9/census_curated.txt                 # 9: 0: the script of step 10 has not run
    python3 dev/varka_census.py --summary | grep '^  ltz '                # 10: "  ltz     new         0 / N"; note N
    grep -c '"ltz"' $GENT/varka/VarkaTypeConformance.java                 # 11: 2: CAST_TESTS and castToTimestamp
    ls $EXECT/benchmark/Varka*BaselineBenchmark.java | xargs grep -l "TimestampType\|TIMESTAMP_LTZ\|timestamp_micros\|TIMESTAMP '" | xargs grep -l "greatest("
                                                # 12: exactly one file: row 655's class with the zone-free sections. BASE is its path
    grep -c "record Section(String title, String query, Fusion fusion)" $BASE   # 13: 1
    grep -c "Fusion.NONE" $BASE                                           # 14: a count above 0
    grep -c "boolean declinesLongConditions" $BASE                        # 14a: 1 or more: sections(boolean declinesLongConditions)
    grep -c "Fusion.WHOLE" $BASE                                          # 14b: 1 or more
    grep -n "AS ntz2," $EXECS/VarkaCoverageDifferentialSuite.scala $EXECS/VarkaSparkFuzz.scala   # 15: one line each
    grep -n "sessionTimeZone\|sessionLocalTimeZone" $EXEC/VarkaKernelEvaluator.java $PLAN/VarkaProjectExec.scala $PLAN/VarkaColumnarToRowExec.scala   # 16: nothing
    git grep -n "new VarkaProjectEvaluatorFactory(\|new VarkaColumnarToRowEvaluatorFactory(" -- 'sql/core/src/test/java'   # 17: one line, in VarkaEvaluatorOverheadBenchmark.java
    grep -n "The long lane's four:" $EXEC/VarkaLaneArrow.java             # 18: one line
    grep -n "// Owed to row 656 (VarkaTypes): no lane reads it yet." $EXEC/VarkaLaneArrow.java   # 18a: one line
    grep -n "A zoned TIMESTAMP is no lane's, so these arms ask for NTZ operands." $GEN/VarkaTimeCompiler.java   # 18b: one line
    grep -n "which no lane carries" $GENT/varka/VarkaTimestampNtzArithmeticSuite.java   # 18c: one line
    grep -n "and the two timestamps the compiler declines." $EXECS/VarkaLongLaneSuite.scala   # 18d: one line
    grep -n '+ "at a 64-bit lane (BIGINT, DOUBLE, TIMESTAMP_NTZ, TIME, day-time interval, DECIMAL), on "' $IR/VarkaKernelPlan.java   # 18e: one line
    grep -c "TIMESTAMP_NTZ, TIME, day-time interval, DECIMAL" docs/sql-varka.md sql/varka/width_audit.json   # 19: two equal counts; note the count as M (188 on the plan's commit)
    grep -n -i "thirteen\|local time zone\|no kernel reads yet" docs/sql-varka.md README.md   # 20: every old line of step 11, and README.md's "over all thirteen"
    flock "$VARKA_HEAVY_LOCK" dev/varka_emit.sh "ntz < ntz2" "greatest(ntz, ntz2)" --columns ntz:timestamp_ntz,ntz2:timestamp_ntz > $T/ntz.txt; grep -c FusedOutput $T/ntz.txt   # 21: 2
    git branch --list 'varka-656b*'                                       # 22: nothing
    grep -o "wanted.size === [0-9]*" $EXECS/VarkaSparkFuzzSuite.scala     # 23: wanted.size === 18, or another number: note it

| line | it prints | what the builder does |
| :--- | :--- | :--- |
| 1 | `declined` | stop: part A is not merged |
| 3a | `0` or an error | stop: part A2 is not merged |
| 1 | `on_lane` | part B is merged: report it and end |
| 2 or 3 | another number | stop: row 653 (5.0) |
| 9 | not `0` | the script has run: `git checkout -- $M9/census_curated.txt`, then go on |
| 12 | no file | stop: row 655 has not merged |
| 12 | more than one file | stop: report the list |
| 13, 14, 14a or 14b | `0` | stop: the class is not shaped as `VarkaTimestampNtzBaselineBenchmark` is (a `Section` record, `sections(boolean declinesLongConditions)`, the classes `Fusion.NONE` and `Fusion.WHOLE`); report how it declares a section and its fusion |
| 17 | more lines | no stop: each is a Java caller, and step 7 edits each; the status list gains each file |

`<Base>` below is the class of the file `$BASE`.

**The steps.**

1. `<ir>/VarkaTypes.java`, the `TIMESTAMP` case: `new Admission.OnLane(LaneType.LONG)`; the
   lanes `List.of(LaneType.LONG)`; the conformance `new Conformance.Suite("Timestamp")`. The
   comment's last sentence becomes "It is read on the long lane (VARKA-656)."
2. `<gen>/VarkaConditionCompiler.java`, `nullLeaf`: `case TIMESTAMP_NTZ, TIMESTAMP ->
   Optional.of(LaneType.LONG);` with the comment "Both timestamps ride the long lane as their
   microseconds."; the `TIMESTAMP` case and its comment "No lane reads it as values." go. In
   the method's comment, the two lines

          * types the narrow, int, long and floating lanes carry as plain values, and a TIMESTAMP_NTZ on
          * the long lane; every other type declines with its name.

   become

          * types the narrow, int, long and floating lanes carry as plain values, and the two timestamps
          * on the long lane; every other type declines with its name.
3. `<gen>/VarkaZonedDeclines.java`: delete the cases of `Literal`, `In` and `InSet` and the
   comment above them.
   `<gen>/VarkaTimeCompiler.java`, two comments and no code. Above the long lane's column
   leaf, the six lines from "The long lane's column leaf:" to "the int lane anyway." become:

             // The long lane's column leaf: a `bigint`, a `TIME(p)`, a day-time interval, a
             // `TIMESTAMP_NTZ` and a `TIMESTAMP` are one eight-byte lane, holding the value, nanoseconds
             // of day, microseconds, and microseconds since the epoch for the two timestamps (VARKA-29,
             // VARKA-338, VARKA-656). As with the interval leaf, Spark's own typing decides where such a
             // value may appear - never in a date or an int position - so the leaf cannot put one there,
             // and the IR's constructors refuse a tree that mixes it with the int lane anyway.

   Above the `SubtractTimestamps` arm, the line `// kernel carries. A zoned TIMESTAMP is no
   lane's, so these arms ask for NTZ operands.` becomes two:

             // kernel carries. A zoned TIMESTAMP computes these in the session time zone, so these arms
             // ask for NTZ operands, and the zoned forms decline naming row 671.
4. `<ir>/VarkaKernelPlan.java`, two edits, each given whole. The constant's four lines keep
   the first; the last three become (the text is the old one with `TIMESTAMP, ` put in, and
   the breaks moved so that no line passes 100 columns):

             + "at a 64-bit lane (BIGINT, DOUBLE, TIMESTAMP, TIMESTAMP_NTZ, TIME, day-time interval, "
             + "DECIMAL), on an x86 host whose 64-bit species have two lanes or fewer: C2 lowers no mask "
             + "there, and the selection would run the Vector API's Java fallback";

   In the comment of the refusal, the five lines from "on a 64-bit lane" to "no lane reads it
   yet." are, old:

          * on a 64-bit lane ({@link VarkaTypes#lane}): {@code BIGINT}, {@code TIMESTAMP_NTZ},
          * {@code TIME}, the day-time intervals and a {@code DECIMAL} of up to 18 digits on the long
          * lane, {@code DOUBLE} on its own. Only {@code BIGINT} and {@code DOUBLE} were timed; the others
          * are refused by the mechanism, since their compares and null tests are the same long-lane
          * masks. The zoned {@code TIMESTAMP} is not among them only because no lane reads it yet.

   New:

          * on a 64-bit lane ({@link VarkaTypes#lane}): {@code BIGINT}, {@code TIMESTAMP},
          * {@code TIMESTAMP_NTZ}, {@code TIME}, the day-time intervals and a {@code DECIMAL} of up to 18
          * digits on the long lane, {@code DOUBLE} on its own. Only {@code BIGINT} and {@code DOUBLE}
          * were timed; the others are refused by the mechanism, since their compares and null tests are
          * the same long-lane masks.

5. `<exec>/VarkaLaneArrow.java`.
   - `Allocate.create` becomes `ValueVector create(String name, DataType dataType, String
     timeZone, BufferAllocator allocator)`; each lambda takes the fourth parameter and
     ignores it.
   - `fromField(String name, DataType type, String timeZone, BufferAllocator allocator)` passes
     `timeZone` where it passes `null` to `ArrowUtils.toArrowField`. `decimalFromField` takes
     and forwards it.
   - `case TIMESTAMP -> new Vector(id, TimeStampMicroTZVector.class,
     VarkaLaneArrow::fromField);`, placed after `TIMESTAMP_NTZ`, with the import. The line
     `case TIMESTAMP -> null;` and the comment above it go. The six comment lines above the
     long lane's entries, from "The long lane's four:" to "admits no zoned column.", become
     these eight:

             // The long lane's five: a bigint, a TIME(p) - nanoseconds of day at every precision - and
             // a day-time interval, a TIMESTAMP_NTZ and a TIMESTAMP, each in microseconds, each built
             // from the Arrow field Spark's own writer builds for the type, so TimeNanoVector carries the
             // precision in its field metadata and the row path reads all five back through
             // ArrowColumnVector. A TIMESTAMP_NTZ column is a TimeStampMicroVector and a TIMESTAMP column
             // a TimeStampMicroTZVector, two classes of which neither extends the other, so each entry
             // admits its own type's columns. A TIMESTAMP field names a time zone, a label over
             // instants: an output takes the session zone's name.

   - `allocate(String name, DataType dataType, String timeZone, BufferAllocator allocator)`
     passes it on.
6. `<exec>/VarkaKernelEvaluator.java`: a field `private final String sessionTimeZone;`; the
   constructor of nine arguments gains a tenth, `String sessionTimeZone`, last; a constructor
   of the old nine stays and calls it with `"UTC"`; the one of four is as it is. `@param
   sessionTimeZone the session time zone's name, which a TIMESTAMP output's Arrow field
   carries as a label`. In `allocateVector` the call becomes two lines,

           ValueVector vector =
               VarkaLaneArrow.allocate("varka" + ordinal, dataType, sessionTimeZone, allocator);

   and in the comment above it the line that begins `// three from the Arrow field` begins
   `// types from the Arrow field` instead.
7. `<plan>/VarkaProjectExec.scala` and `<plan>/VarkaColumnarToRowExec.scala`: each evaluator
   factory gains a last parameter `sessionTimeZone: String = "UTC"` and passes it as the
   tenth argument of `new VarkaKernelEvaluator`; each node passes `sessionTimeZone =
   conf.sessionLocalTimeZone`. Each Java caller B0 printed passes `"UTC"` as its last
   argument.
8. Catalyst tests.
   - `<gent>/varka/VarkaFuzzTypes.java`. After `NTZ_VALUES`:

           /** Spark's first and last instant, and the two changes of 2021 in America/Los_Angeles. */
           private static final ValueSource LTZ_VALUES = integral(-62135596800000000L, -1, 0, 1,
               1615716000000000L, 1636275600000000L, 253402300799999999L);

     The case:

               case TIMESTAMP -> entry(id, new Present<>(LONG_COLUMN),
                   columns(derived("ts", "TIMESTAMP"), derived("ts2", "TIMESTAMP")),
                   new Present<>(LTZ_VALUES));

     Above `longCorpus`, the line `// has no node of TIMESTAMP_NTZ, TIME or DT_INTERVAL: they
     share the lane's column untyped.` becomes two: `// has no node of TIMESTAMP,
     TIMESTAMP_NTZ, TIME or DT_INTERVAL: they share the lane's column` and `// untyped.`
     `<gent>/varka/VarkaTimestampNtzArithmeticSuite.java`, in `declines`: the message
     `" fused over a zoned TIMESTAMP, which no lane carries"` becomes `" fused over a zoned
     TIMESTAMP, which reads the session time zone here"`. The three forms still decline.
   - `<gent>/varka/VarkaCatalystGrammar.java`: `Type.LTZ` after `NTZ` ("A TIMESTAMP, an
     instant."); `COLUMNS`: `Type.LTZ, List.of("ts", "ts2")`; `LITERALS`:
     `List.of("TIMESTAMP'2021-06-01 12:00:00Z'", "TIMESTAMP'1969-12-31 18:00:00.000001Z'")`;
     `VALUE_TYPES` gains `Type.LTZ` last; in `productions`, `choices(out, Type.LTZ, Type.LTZ,
     1);` after the `TIMESTAMP_NTZ` choices, with the comment "TIMESTAMP: comparison, null
     tests and choice read no time zone (VARKA-656); what reads one is row 684's to draw."
     The class comment names the columns. `COLUMNS` and `LITERALS` are `Map.of` calls of
     eight pairs each and take a ninth.
   - `<gens>/varka/VarkaCoverageSuite.scala`: `ts` and `ts2` as 4.8 has them, with the
     comment "TIMESTAMP columns (VARKA-656), instants on the long lane; the fixtures give
     them the counts of ntz and ntz2."; two values `ltzHeld` and `ltzHeld2`; the four
     families of 4.8. A comment above the first family: "TIMESTAMP on the long lane
     (VARKA-656): what reads no time zone, by the long lane's nodes. Every literal ends in Z,
     so a row is one tree under every session zone."
     `<gens>/varka/VarkaCoverageCompositionFuzzSuite.scala`, the comment of `drawLong`: its
     last two lines, after the line that ends "then nearer at each step. A",

            * `TIMESTAMP_NTZ` is drawn as a `BIGINT` is, within about twelve days of the epoch; its
            * extremes are `VarkaTimestampNtzConformanceSuite`'s and `VarkaTimestampNtzCastSuite`'s.

     become three:

            * `TIMESTAMP_NTZ` or a `TIMESTAMP` is drawn as a `BIGINT` is, within about twelve days of the
            * epoch; their extremes are `VarkaTimestampNtzConformanceSuite`'s,
            * `VarkaTimestampNtzCastSuite`'s and `VarkaTimestampConformanceSuite`'s.
   - `<gent>/varka/VarkaTimestampConformanceSuite.java`, new, package
     `org.apache.spark.sql.catalyst.expressions.codegen.varka`, modelled on
     `VarkaTimestampNtzConformanceSuite`: `@VarkaSuite(ownJvm = true)` (row 657 gives it a
     cast to `DATE` whose kernel is of both lanes). `type()` returns `new
     ConformanceType("ltz", DataTypes.TimestampType, "TIMESTAMP",
     VarkaTimestampConformanceSuite::literal, values, new long[] {1, -1}, new PairForm("x -
     y", "ntz_minus_ntz", halfRange(Long.MIN_VALUE, Long.MAX_VALUE)))`. `values` is
     `integralValues(Long.MIN_VALUE, Long.MAX_VALUE)`, then `-62135596800000000L`,
     `253402300799999999L`, `1615715999999999L`, `1615716000000000L`, `1636275599999999L`,
     `1636275600000000L`: 13 values. `literal` is the model's with `"TIMESTAMP '"` and a `Z`
     before the closing quote. No `overflowForms`. Its class comment says: the values; that
     the pair `x - y` (the census entry `ntz_minus_ntz`, which admits `ltz`) reads the session
     zone and is owed to row 671, so the null handling check runs its other three forms and
     is cancelled naming the pair; that every cast is owed to the row `casts.tsv` names.
   - `VarkaZonedDeclinesSuite`: the fourth test becomes "a TIMESTAMP column, a literal and
     an IN list fuse": the same nine forms, `outcome` empty for each. In the fifth test the
     `InSet` line goes.
   - `VarkaTimestampTwinSuite`, one line: its fourth test, "a twin that reads no time zone
     and is a coverage row fuses to the kernel of its TIMESTAMP_NTZ row", now also asserts
     that it judged at least 15 twins (19 are expected: the rows of 4.8 with no literal).
     Before this part it judged none.
   - `VarkaTypesSuite`, "a column of an owed, declined or condition-only type ...": the
     `TimestampType` line becomes `assertTrue(VarkaTypes.declineReason(
     DataTypes.TimestampType).isEmpty());`.
   - `VarkaExpressionCompilerSuite`, three tests. "a zoned timestamp column declines ..."
     is renamed "a zoned timestamp column fuses at the long lane, as an NTZ one does"; its
     first half becomes the second half's four assertions over `ts` and `Literal(0L,
     TimestampType)`, the value `reason` goes, and its three comment lines become two: `// Both
     timestamp types are read on the long lane (VARKA-338, VARKA-656): a comparison and a` and
     `// greatest of each fuse into a kernel driven at the long lane.` "the
     identity cast of a TIMESTAMP is its child": the projection `Seq(out(Greatest(Seq(Cast(ts,
     TimestampType), ts))))` over `withLong` has the specs `Seq(new FusedOutput(0))`, and its
     fused output equals the fused output of `Seq(out(Greatest(Seq(ts, ts))))`. One new test,
     "a TIMESTAMP beside a TIMESTAMP_NTZ reaches the compiler under a cast of the
     TIMESTAMP_NTZ side": `resolved(LessThan(ts, ntz))` equals `LessThan(ts, c)` where `c` is
     a `Cast` whose child is `ntz` and whose type is `TimestampType`. It asserts the tree
     alone; the cast's reason is `VarkaZonedDeclinesSuite`'s.
9. sql/core tests.
   - `<execs>/VarkaCoverageDifferentialSuite.scala`, `fixtureView`: a helper `ltz(day, time)`
     beside `ntz`, the `CASE` of `ntz` around 4.8's expression, and `${ltz("d", "t")} AS ts,`
     and `${ltz("d2", "t2")} AS ts2,` after `ntz2`; one sentence in the method's comment.
   - `<execs>/VarkaSparkFuzz.scala`, `fixtureRows`: 4.8's two lines after `ntz2`; the comment.
     `<execs>/VarkaSparkFuzzSuite.scala`, the test "the fixture holds every column of
     coverage.json its draws read, at its type": the number in `wanted.size === 18` becomes
     the number B0's line 23 printed plus two.
   - `<exect>/VarkaDomains.java`, `laneValue`: `t instanceof TimestampType` joins the
     long-lane arm. `<exect>/VarkaAnsiForm.java`, `standIn`: `TimestampType` gives `"ts2"`.
   - `<execs>/VarkaLaneArrowSuite.scala`: the three calls of `allocate` pass `"UTC"`; the
     three tests of the table below.
   - `<execs>/VarkaKernelEvaluatorSuite.scala`: the test of the table below. Its batch is
     built in the test, since `VarkaColumnarToRowExecSuite.buildBatch` writes ints alone:

         def zoned(name: String, values: Seq[java.lang.Long]): ArrowColumnVector = {
           val v = new TimeStampMicroTZVector(name, allocator, "UTC")
           v.allocateNew(values.length)
           values.zipWithIndex.foreach {
             case (null, i) => v.setNull(i)
             case (x, i) => v.set(i, x)
           }
           v.setValueCount(values.length)
           new ArrowColumnVector(v)
         }

     inside a body shaped as the suite's `withTask` is: a child allocator of
     `ArrowUtils.rootAllocator`, `TaskContext.setTaskContext(TaskContext.empty())`, the batch
     `new ColumnarBatch(Array(zoned("ts", a), zoned("ts2", b)))` with `setNumRows(4)`, closed
     in a `finally`, and the allocator's memory asserted back to its start.
   - `<execs>/VarkaLongLaneSuite.scala`: the test "a zoned timestamp comparison stays on the
     row engine, and an NTZ one fuses" is renamed "a zoned timestamp comparison fuses as an
     NTZ one does, across a change of the session zone's offset". In its loop, the `if (col ==
     "ntz") ... else assertNotFused(...)` becomes `assertFused(aloneActual.queryExecution
     .executedPlan)` for both columns, and `assert(residual.isEmpty === (col == "ntz"),
     plan.treeString)` becomes `assert(residual.isEmpty, plan.treeString)`. Its six comment
     lines become four:

             // Both timestamp types are the same eight-byte count physically, and both are read on the
             // long lane (VARKA-338, VARKA-656): a comparison reads the count and no time zone, so each
             // predicate fuses alone and beside the date conjunct. The rows straddle a change of the
             // session zone's offset, which a kernel that read the zone would show.

     And in the comment of `fixtures`, the line that ends "and the two timestamps the compiler
     declines." ends "and the two timestamps, `ts` and `ntz`." instead.
   - `<exect>/benchmark/<Base>.java`, in `sections` (`VARKA-655.md` 4.5 and 4.6), three
     lines: `Fusion comparison = Fusion.NONE;` becomes `Fusion comparison = filter;`, where
     `filter` is that method's `declinesLongConditions ? Fusion.NONE : Fusion.WHOLE`, as
     `VarkaTimestampNtzBaselineBenchmark`'s comparison has it; `Fusion pick = Fusion.NONE;`
     and `Fusion choice = Fusion.NONE;` become `Fusion pick = Fusion.WHOLE;` and `Fusion
     choice = Fusion.WHOLE;`. `epoch` and `hashes` stay `Fusion.NONE`, and the sections
     titled `TIMESTAMP_NTZ twin` keep the classes they have. The class comment gains the
     sentence "The comparison, `greatest` and the choice fuse (VARKA-656)."
10. The census cells: `python3 $T/ltz_fused.py $M9/census_curated.txt | tee $T/fused.txt`,
    with the script of 4.8 saved as `$T/ltz_fused.py`. It prints one line, `22 entries fused
    for ltz; done again: isnull isnotnull; 656 joined the rows of: in_set`. Any other line,
    or a non-zero exit, is a stop: report it. No other edit of the curated file, and none of
    `dev/varka_census.py`.
11. The prose, by hand. Each sentence spans lines of its file, so each line is quoted apart;
    search for one line at a time. An old line that is not found is a stop: report B0's
    output of the line that greps these words.

    | file | the old line | the new line |
    | :--- | :--- | :--- |
    | `docs/sql-varka.md` | ``Varka reads thirteen Spark types: `BooleanType` as a bitmap; `ByteType` and`` | ``Varka reads fourteen Spark types: `BooleanType` as a bitmap; `ByteType` and`` |
    | the same, two lines below | `` `YearMonthIntervalType` on 32-bit lanes; `LongType`, `TimestampNTZType`, `TimeType` `` | `` `YearMonthIntervalType` on 32-bit lanes; `LongType`, `TimestampType`, `TimestampNTZType`, `` |
    | the same, the next line | ``and `DayTimeIntervalType` on 64-bit lanes; `FloatType` and `DoubleType` on floating`` | `` `TimeType` and `DayTimeIntervalType` on 64-bit lanes; `FloatType` and `DoubleType` on floating`` |
    | `docs/sql-varka.md` | ``cast to or from it, `TIMESTAMP` with the local time zone, and decimal division;`` | `cast to or from it, and decimal division;` |
    | `docs/sql-varka.md` | ``over each of the thirteen types, a bare `IntegerType` column included (`i > 0`,`` | the same with `fourteen` |
    | `docs/sql-varka.md` | ``  column: precision above 18 is row ..."), and a `TIMESTAMP` column the`` | ``  column: precision above 18 is row ..."), and an expression that reads the`` |
    | the same, the next line | ``  row that puts it on the long lane (VARKA-656). Since VARKA-38, a `date_add`/`` | ``  session time zone over a `TIMESTAMP` the row that fuses it. Since VARKA-38, a `date_add`/`` |
    | `docs/sql-varka.md` | ``* **Thirteen types, and not the rest.** Varka reads `BOOLEAN`, `TINYINT`,`` | ``* **Fourteen types, and not the rest.** Varka reads `BOOLEAN`, `TINYINT`,`` |
    | the same, two lines below | ``  `DATE`, `TIMESTAMP_NTZ`, `TIME` and both interval types, and *What fuses, by`` | ``  `DATE`, `TIMESTAMP`, `TIMESTAMP_NTZ`, `TIME` and both interval types, and *What fuses, by`` |
    | the same, the next line | ``  type* says how much of each fuses. `STRING` and its casts, `TIMESTAMP` with`` | ``  type* says how much of each fuses. `STRING` and its casts and decimal division`` |
    | the same, the next line | `  the local time zone and decimal division are out of scope by the owner's` | `  are out of scope by the owner's` |
    | `README.md` (as row 654 left it) | `DECIMAL, DATE, TIME, TIMESTAMP_NTZ, the year-month and day-time intervals, and TIMESTAMP,` | `DECIMAL, DATE, TIME, TIMESTAMP_NTZ, TIMESTAMP and the year-month and day-time intervals. For` |
    | the same, the next line | `which no kernel reads yet. For` | the line is deleted |

    The sentence of `docs/sql-varka.md` that row 654 reworded ("over its fourteen" types of
    the census) stays, and so does `README.md`'s "over all thirteen", which tells of
    milestone 8. `sql/varka/ADDING_A_TYPE.md`, step 10 ("**10. Admit it in and out of
    Arrow.**"), one sentence before its `Check:` line: "A vector whose Arrow field needs more
    than the Spark type, as a `TIMESTAMP`'s needs a time zone's name, takes it through
    `VarkaLaneArrow.allocate`."
12. Regenerate: `dev/varka_regen_generated.sh --all --full`. Then the emit tool on the twins
    of B0's command, `dev/varka_emit.sh "ts < ts2" "greatest(ts, ts2)" --columns
    ts:timestamp,ts2:timestamp > $T/ts.txt`: the lines of `$T/ts.txt` that hold `IR` or
    `shape hash` equal those of `$T/ntz.txt` letter for letter.
13. The width audit under AVX2, as `sql/varka/AGENTS.md` asks:
    `JDK_JAVA_OPTIONS=-XX:UseAVX=2 build/sbt 'catalyst/testOnly *VarkaWidthAuditSuite'`.
14. This file, section 11: what part B built, and predictions 2 to 6 scored.

**The tests.**

| suite | case | inputs | expected, and its oracle | the failure only it catches |
| :--- | :--- | :--- | :--- | :--- |
| `VarkaTimestampConformanceSuite` (catalyst, Java, JUnit 5, `<gent>/varka`, new) | load; round trip; SQL ordering; choice | every pair of the 13 values: both ends of a long and their neighbours, -1, 0, 1, Spark's first and last instant, the microsecond before and at each of the two changes of 2021 in `America/Los_Angeles`; every null pattern; vectors of every length the contract runs; a tail batch of one null row | the kernel equals `VarkaReference` and Spark's own evaluation of the same expression on every row | a comparison done unsigned or on 32 bits, which the mid-range values of the coverage fixture cannot see |
| the same | null handling | the pair `x - y` and `x = y`, `x <=> y`, `x IS NULL` | the three conditions hold; the test is then cancelled naming the pair's rows, `339,671` | - |
| the same | the 14 cast tests; overflow | - | `castToTimestamp` cancelled as the identity the analyzer removes; the two interval targets cancelled as no cast; each of the other 11 cancelled naming the rows `casts.tsv` gives the pair (`657,669` for `DATE`, `669` for the rest); overflow has no form and returns | a register that marks a cast fused that the compiler declines |
| `VarkaCoverageSuite`, `VarkaCoverageHarnessSuite`, `VarkaCoverageDifferentialSuite` (existing) | the 23 rows | the fixtures' `ts` and `ts2`: the calendar from 1969 to 9999 at a time of the day, nulls, an all-null view | every row compiles, is documented, and equals the row engine on every batch, in a projection and a filter; the first row of each family through two sessions | a row that is documented and does not fuse |
| `VarkaZonedDeclinesSuite`, `VarkaTimestampTwinSuite` | as 5.1 and 5.1a, with the changes of step 8 | - | - | the first planted fault below |
| `VarkaLaneArrowSuite` (sql/core, ScalaTest, `<execs>`) | new: a TIMESTAMP is written to a TimeStampMicroTZVector whose field names the zone it is given, and a TIMESTAMP_NTZ's field names none | `allocate("x", TimestampType, "Asia/Kathmandu", allocator)` and the same for `TimestampNTZType` | the first vector's field type is `Timestamp(MICROSECOND, "Asia/Kathmandu")`, the second's has a null zone | a zone dropped on the way, which fails inside Arrow as an internal error |
| the same | new: neither timestamp vector is the other's | one vector of each class | `of(TIMESTAMP).vectorClass().isInstance` is false for the `TIMESTAMP_NTZ` vector and the other way round; `reads` is true for both | an entry that admits its sibling's columns |
| the same | new: a zoned timestamp vector of another unit is read by no lane | `TimeStampSecTZVector`, `TimeStampMilliTZVector`, `TimeStampNanoTZVector`, each built with the zone `UTC` | `reads` is false | a millisecond column read as microseconds |
| `VarkaKernelEvaluatorSuite` (sql/core, ScalaTest, `<execs>`) | new: a kernel's TIMESTAMP output carries the zone the evaluator was built with | `ts`: `Long.MinValue`, null, `1615716000000000`, `Long.MaxValue`; `ts2`: `0`, `1`, null, `-1`. The projection `greatest(ts, ts2)` over two attributes of `TimestampType`. One evaluator of ten arguments with `"Asia/Kathmandu"`, one of four arguments | the output column's vector is a `TimeStampMicroTZVector`; its field's zone is `Asia/Kathmandu` for the first evaluator and `UTC` for the second; the four values are `0`, `1`, `1615716000000000`, `Long.MaxValue` | the name lost between the constructor and the allocator |
| `VarkaLongLaneSuite`, `VarkaExpressionCompilerSuite`, `VarkaTypesSuite` (existing) | the flipped tests of steps 8 and 9 | rows on both sides of the gap of 14 March 2021 | fused, and equal to the row engine | the old state asserted |
| `VarkaSparkFuzzSuite`, `VarkaCoverageCompositionFuzzSuite`, `VarkaIrFuzzSuite` (existing) | their runs | the new rows drawn as compositions; `Type.LTZ` drawn in outputs and conjuncts; the catalyst fuzzers draw a `TIMESTAMP` input as a `BIGINT` | no disagreement | a shape no row lists |
| `<Base>`'s queries suite (row 655's) | each section fuses as its class lists it | its table | pass | a section that fuses unlisted, or one listed that does not fuse |

**Planted faults** (commit first; plant one, rerun, revert).

| fault | the test that must fail |
| :--- | :--- |
| `VarkaTimeCompiler.arm`: the guard of `Hour`'s `TIMESTAMP_NTZ` arm reads `VarkaTypes.isNtz(...) \|\| VarkaTypes.isLtz(...)` | `VarkaTimestampTwinSuite`, "a twin that reads the session time zone is never computed as its TIMESTAMP_NTZ row", on `hour(ts)` |
| `nullLeaf` gives `TIMESTAMP` no lane | the coverage row `CASE WHEN ts < ts2 THEN ts END` in `VarkaCoverageSuite` |
| the identity arm of `arithmeticArm` deleted | `VarkaExpressionCompilerSuite`, "the identity cast of a TIMESTAMP is its child" |
| `VarkaLaneArrow.of(TIMESTAMP)` names `TimeStampMicroVector.class` | `VarkaLaneArrowSuite`, "neither timestamp vector is the other's" |
| `fromField` passes `null` for the zone | `VarkaLaneArrowSuite`, "a TIMESTAMP is written to a TimeStampMicroTZVector ..." |
| `allocateVector` passes `"UTC"` | `VarkaKernelEvaluatorSuite`, "a kernel's TIMESTAMP output carries the zone ..." |
| in `$M9/census_curated.txt`, `isnull`'s need set back to `plumbing` | `python3 dev/varka_census.py --check` exits 1 with `isnull: need plumbing disagrees with fused` |

No test fails when an exec node passes another zone's name than the session's: Spark reads the
label's presence and not its value. The command with `conf.sessionLocalTimeZone` below holds
the two call sites.

**The commands,** in order; each comment is what the line must print.

    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaTimestampConformanceSuite *VarkaZonedDeclinesSuite *VarkaTimestampTwinSuite *VarkaTypesSuite *VarkaFuzzTypesSuite *VarkaExpressionCompilerSuite *VarkaFamilyChainSuite *VarkaFamilySwitchSuite *VarkaCastMatrixSuite'
                                                # 1: Failed 0, Errors 0; VarkaTimestampConformanceSuite reports its 14 cast tests
                                                #    and its null handling test as cancelled, not failed
    dev/varka_regen_generated.sh --all --full; echo $?                    # 2: 0
    grep -P "^TIMESTAMP\t" sql/varka/types.tsv | cut -f3,5,6,10,11,12     # 3: ltz, on_lane, LONG, 1, LONG, Timestamp
    grep -P "^TIMESTAMP\t" sql/varka/fuzz/types.tsv | cut -f2,3,7,8,9     # 4: LONG, present, present, "ts,ts2", present
    python3 dev/varka_new_lane.py --check | grep TimestampType            # 5: LONG/TimestampType: no site is missing (2 checked)
    python3 dev/varka_census.py --check; echo $?                          # 6: 0
    python3 dev/varka_census.py --summary | grep '^  ltz '                # 7: "  ltz     new        23 / N": the 22 cells and the alias's identity
    git diff -U0 -- $M9/census_curated.txt | grep -c '^+[^+]'             # 8: 22
    python3 dev/varka_bytes_moved.py origin/master --ignore option_arms   # 9: nothing
    grep -c '"family" : "TIMESTAMP columns"\|"family" : "TIMESTAMP columns choosing values"\|"family" : "Null family on TIMESTAMP columns"\|"family" : "Null family predicates on TIMESTAMP columns"' sql/varka/coverage.json   # 10: 23
    grep -c "TIMESTAMP, TIMESTAMP_NTZ, TIME, day-time interval, DECIMAL" docs/sql-varka.md sql/varka/width_audit.json   # 11: two counts, each at least M
    grep -c "DOUBLE, TIMESTAMP_NTZ" docs/sql-varka.md sql/varka/width_audit.json   # 12: 0 and 0: no line keeps the old text
    git diff -U0 -- $M9/casts_suites.tsv | grep -c '^-[^-]'               # 13: 0: no line left or changed
    git diff -U0 -- $M9/casts_suites.tsv | grep '^+[^+]' | cut -f2 | sort -u   # 14: ltz alone: every new line has ltz as its source
    git diff --stat -- $M9/casts.tsv $M9/casts_fused.tsv                  # 15: nothing
    grep -c "conf.sessionLocalTimeZone" $PLAN/VarkaProjectExec.scala $PLAN/VarkaColumnarToRowExec.scala   # 16: 1 and 1
    flock "$VARKA_HEAVY_LOCK" build/sbt 'sql/testOnly *VarkaLaneArrowSuite *VarkaKernelEvaluatorSuite *VarkaLongLaneSuite *VarkaCoverageHarnessSuite *VarkaCoverageDifferentialSuite *VarkaSparkFuzzSuite *VarkaTimestampNtzSuite *VarkaWarmupEndToEndSuite *VarkaMemorySanitizerEndToEndSuite *VarkaCastWithAnsiOffSuite *VarkaCastWithAnsiOnSuite *VarkaTryCastSuite *BaselineQueriesSuite'
                                                # 17: Failed 0, Errors 0; the queries suite of <Base> is among them
    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaCoverageCompositionFuzzSuite *VarkaIrFuzzSuite *VarkaEmittedBytesSuite *VarkaWidthAuditSuite *VarkaCoverageSuite *ConformanceSuite'
                                                # 18: Failed 0, Errors 0; 15 conformance suites ran
    flock "$VARKA_HEAVY_LOCK" dev/varka_emit.sh "ts < ts2" "greatest(ts, ts2)" --columns ts:timestamp,ts2:timestamp > $T/ts.txt
    diff <(grep "IR\|shape hash" $T/ntz.txt) <(grep "IR\|shape hash" $T/ts.txt)   # 19: nothing: one kernel
    flock "$VARKA_HEAVY_LOCK" dev/varka_emit.sh "hour(ts)" --columns ts:timestamp   # 20: "nothing fused"; "hour of a TIMESTAMP is row 657's: not fused yet"
    git status --short                                                    # 21: the list below, and no other line
    python3 dev/varka_push_check.py; echo $?                              # 22: a size under 900, then 0

**The expected `git status --short`** before the commit, 41 lines.

     M README.md
     M docs/sql-varka.md
     M <gen>/VarkaConditionCompiler.java
     M <gen>/VarkaZonedDeclines.java
     M <gen>/VarkaTimeCompiler.java
     M <ir>/VarkaKernelPlan.java
     M <ir>/VarkaTypes.java
     M <gent>/VarkaTimestampTwinSuite.java
     M <gent>/VarkaZonedDeclinesSuite.java
     M <gent>/varka/VarkaCatalystGrammar.java
     M <gent>/varka/VarkaFuzzTypes.java
     M <gent>/varka/VarkaTimestampNtzArithmeticSuite.java
     M <gent>/varka/VarkaTypesSuite.java
     M <gens>/VarkaExpressionCompilerSuite.scala
     M <gens>/varka/VarkaCoverageCompositionFuzzSuite.scala
     M <gens>/varka/VarkaCoverageSuite.scala
     M <exec>/VarkaKernelEvaluator.java
     M <exec>/VarkaLaneArrow.java
     M <plan>/VarkaColumnarToRowExec.scala
     M <plan>/VarkaProjectExec.scala
     M <exect>/VarkaAnsiForm.java
     M <exect>/VarkaDomains.java
     M <exect>/benchmark/<Base>.java
     M <exect>/benchmark/VarkaEvaluatorOverheadBenchmark.java
     M <execs>/VarkaCoverageDifferentialSuite.scala
     M <execs>/VarkaKernelEvaluatorSuite.scala
     M <execs>/VarkaLaneArrowSuite.scala
     M <execs>/VarkaLongLaneSuite.scala
     M <execs>/VarkaSparkFuzz.scala
     M <execs>/VarkaSparkFuzzSuite.scala
     M sql/varka/ADDING_A_TYPE.md
     M sql/varka/coverage.json
     M sql/varka/emitted_bytes.json
     M sql/varka/fuzz/types.tsv
     M sql/varka/plans/m9/VARKA-656.md
     M sql/varka/plans/m9/casts_suites.tsv
     M sql/varka/plans/m9/census.tsv
     M sql/varka/plans/m9/census_curated.txt
     M sql/varka/types.tsv
     M sql/varka/width_audit.json
    ?? <gent>/varka/VarkaTimestampConformanceSuite.java

A second Java caller of a factory, where B0's line 17 printed one, adds its file to the list.
`$M9/PLAN.md` is not in it: part B does not edit the row's cell.

What each generated file gains: `types.tsv` and `fuzz/types.tsv`, the line of `TIMESTAMP`;
`coverage.json`, the 23 rows and two columns; `emitted_bytes.json`, the rows' keys and
`option_arms`; `width_audit.json`, the rows, and the refusal's word in `M` lines;
`docs/sql-varka.md`, the hand edits of step 11, the coverage table's 23 rows, the refusal's
word in `M` lines, and in the support table the row of `ltz` (its Lanes cell reads `LONG`,
its fused count 23) and the cells of the 22 entries; `$M9/census.tsv`, `ltz` in the fused
column of the 22 entries and of the alias `timestamp`, and the need of `isnull` and
`isnotnull`; `$M9/casts_suites.tsv`, new lines whose source is `ltz`: Spark's cast suites lift
a `TIMESTAMP` literal into a column once a kernel reads the type (`VARKA-654.md` 11).

**What exists after it** (a list to tick).

- [ ] `types.tsv`: `TIMESTAMP` on the long lane with the suite `Timestamp`.
- [ ] `VarkaLaneArrow.of(TIMESTAMP)` is a `TimeStampMicroTZVector`; `allocate` takes a zone.
- [ ] `VarkaKernelEvaluator` has a constructor of ten arguments; both exec nodes pass the
  session zone.
- [ ] 23 coverage rows in four families; columns `ts` and `ts2` in `coverage.json`'s map, in
  `fixtureView` and in `fixtureRows`.
- [ ] `VarkaTimestampConformanceSuite`, the fifteenth conformance suite.
- [ ] 22 census cells; `ltz` reads `23 / N`; `dev/varka_new_lane.py --check` passes for
  `LONG/TimestampType`.
- [ ] `<Base>`'s comparison and choice sections listed as fused.
- [ ] What does not change: the bytes of every existing shape (`option_arms` aside, which
  moves with every new row); the census cells of every other type; `casts.tsv` and
  `casts_fused.tsv`; every `TIMESTAMP_NTZ` row of `coverage.json`; the answers of every
  existing test. In `docs/sql-varka.md` and `width_audit.json`, the `M` lines of the
  refusal's text change by one word and nothing else of an existing row moves.
- [ ] Predictions 2 to 6 of section 7, scored in section 11.

**Done when** the commands print what the table says, each planted fault fails its test, the
review has no open finding, and the lead's matrix run over the new suites reads `fail 0`,
`unfused 0`. **Not here:** an arm for any class of 4.3's table; a guard; a `domain`; the zone
as a drawn setting; `VarkaSqlResolve`; `VarkaFamily`; the code of any `TIMESTAMP_NTZ` arm
(two comments of `VarkaTimeCompiler` change); any edit of
`dev/varka_census.py`; `VarkaTimestampSuite` and the IN order test, which are part C's.

### 5.3 Part C: the lane through sessions

**B0, the premises.** Run each line; its comment is what it must print. A line that prints
otherwise is a stop.

    grep -P "^TIMESTAMP\t" sql/varka/types.tsv | cut -f5,6                # 1: on_lane, LONG (part B merged)
    ls $EXECS/VarkaTimestampSuite.scala 2>/dev/null | wc -l               # 2: 0
    grep -n "val longTable = Seq(DoubleType, TimestampNTZType, LongType" $GENS/VarkaExpressionCompilerSuite.scala   # 3: one line
    grep -c "private val inNtz" $GENS/VarkaExpressionCompilerSuite.scala  # 4: 1
    grep -n "private def checkServed" $EXECS/VarkaTimestampNtzSuite.scala # 5: one line
    grep -n "protected def withBothConf" $EXECS/VarkaSharedSessions.scala # 6: one line
    git branch --list 'varka-656c*'                                       # 7: nothing

**The steps.**

1. `<execs>/VarkaTimestampSuite.scala`, new, package `org.apache.spark.sql.execution`, `class
   VarkaTimestampSuite extends VarkaMaybeThrows with VarkaTestWatchdog`, as
   `VarkaTimestampNtzSuite` is. It copies four things from that suite, whole: the value
   `batchKey`; the variable `batchesBefore` with `beforeAll` and `afterAll`, where `"2"`
   becomes `"4"` and `cache(session)` builds the one table below; and the two helpers

         private def varkaNode(plan: SparkPlan): SparkPlan =
           collectFirst(plan) { case v if isVarkaNode(v) => v }.getOrElse(fail(s"no Varka node:\n$plan"))

         /** `query` fused and answered as the row engine answers, with no batch declined. */
         private def checkServed(query: String): Unit = {
           val actual = varkaSpark.sql(query)
           val plan = actual.queryExecution.executedPlan
           assertFused(plan)
           checkAnswer(actual, expectedSql(query, actual))
           assertKernelsRan(plan)
           val declined = varkaNode(plan).metrics("numFallbackBatchesDeclined").value
           assert(declined === 0L, s"$query: $declined batch(es) declined")
         }

   The imports, whole (`SparkPlan` is of the suite's own package):

         import org.apache.spark.sql.{Row, SparkSession}
         import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTestWatchdog
         import org.apache.spark.sql.internal.SQLConf
         import org.apache.spark.sql.types.{IntegerType, LongType, StructField, StructType}

   `collectFirst` is `AdaptiveSparkPlanHelper`'s, and `isVarkaNode`, `assertFused`,
   `assertKernelsRan`, `expectedSql` and `withBothConf` are `VarkaSharedSessions`'s; both come
   with `VarkaMaybeThrows`, as they do in the model, so nothing more is copied.
   - **The table.** A DataFrame of the schema `i INT, v BIGINT, w BIGINT`, built from rows,
     then `selectExpr("i", "timestamp_micros(v) AS ts", "timestamp_micros(w) AS ts2")`,
     registered as the view `varka_ts` and cached on both sessions, in batches of four rows:
     `cache(session)` is `session.createDataFrame(session.sparkContext.parallelize(rows, 1),
     schema).selectExpr(...).createOrReplaceTempView("varka_ts")` and then
     `session.catalog.cacheTable("varka_ts")`, as the model's `cache` does for each of its
     tables. The cache is built once,
     under the suite JVM's zone, and is not rebuilt when a test sets a zone: the cached
     vectors keep the label of the zone they were written under, and the kernels must answer
     alike whatever the label and the session zone are.
   - **Its 20 rows.** `v` is, in this order: `Long.MinValue`, `Long.MinValue + 1`, `-1`, `0`,
     `1`, `Long.MaxValue - 1`, `Long.MaxValue`, `-62135596800000000`, `253402300799999999`,
     `1615715999999999`, `1615716000000000`, `1636275599999999`, `1636275600000000`, null,
     null, null, and then four more nulls. `w` is the same list of the first 16 moved by one
     row (row `k` takes the `v` of row `k + 1`, the sixteenth takes the first), and for the
     last four rows `0`, `1`, null, `-1`. `i` is the row's number from 0. So every value
     meets its neighbour, a null meets a value and a null, and the last batch has no live
     `ts`.
   - Every query runs with `spark.sql.datetime.java8API.enabled` set to `true` on both
     sessions (`withBothConf`), so that a collected row holds a `java.time.Instant`, which
     every long of microseconds converts to.
   - The three tests of the table below.
2. `<gens>/VarkaExpressionCompilerSuite.scala`, the IN order test's columns: `private val
   inLtz = AttributeReference("inLtz", TimestampType)()` after `inNtz`; `inLtz` joins
   `inOutput` after `inNtz`; `inCases` gains `inLtz -> Seq(1615716000000000L, -1L,
   253402300799999999L),` after the line of `inNtz`; in the test, `TimestampType` joins
   `longTable` after `TimestampNTZType`.
3. This file, section 11: what part C built, and the test time of prediction 6. Part D writes
   the same section after this part has merged (section 5).

**The tests.**

| suite | case | inputs | expected, and its oracle | the failure only it catches |
| :--- | :--- | :--- | :--- | :--- |
| `VarkaTimestampSuite` (sql/core, ScalaTest, `<execs>`, new) | a TIMESTAMP filter fuses and answers as the row engine does, under UTC, a fixed offset and a zone with changes | the six queries `SELECT i FROM varka_ts WHERE <c>`, with `<c>`: `ts < ts2`; `ts = TIMESTAMP '2021-03-14 10:00:00Z'`; `ts <=> ts2`; `ts IN (TIMESTAMP '2021-03-14 09:59:59.999999Z', TIMESTAMP '2021-11-07 09:00:00Z')`; `ts IS NULL`; `ts BETWEEN ts2 AND TIMESTAMP '2100-01-01 00:00:00Z'`. Each under `spark.sql.session.timeZone` set on both sessions to `UTC`, `+05:45` and `America/Los_Angeles` | `checkServed`: the plan is fused, no batch declined, the answer is the row engine's. The test does not compare a query's rows across the zones: each zone's answer is held to the row engine's, which reads no zone here, and a `collect()` for each zone would take the suite past the session lint's 40 answer checks. Oracle: Spark's row path | a comparison that reads the zone; a kernel that reads the vector's label |
| the same | a TIMESTAMP choice is written by the kernel and read back as the row engine's | the six queries `SELECT i, <e> AS g FROM varka_ts`, with `<e>`: `greatest(ts, ts2)`; `least(ts, ts2)`; `CASE WHEN ts < ts2 THEN ts ELSE ts2 END`; `CASE WHEN ts < ts2 THEN ts END`; `coalesce(ts, ts2)`; `nullif(ts, ts2)`. Under `America/Los_Angeles` | `checkServed`, at both ends of a long and on the batch with no live `ts` | a store at another width; a null written as a value |
| the same | a cached Varka projection of a TIMESTAMP reads back as the row engine's | `val q = "SELECT i, greatest(ts, ts2) AS g FROM varka_ts"`, under `Asia/Kathmandu` on both sessions: `val df = varkaSpark.sql(q); df.cache(); try checkAnswer(df, spark.sql(q)) finally df.unpersist()`, the `cache`, `checkAnswer`, `unpersist` of `VarkaTimeArrowCacheSuite`, with the `try` added so that a failure leaves no cache behind | the row session's rows of the same query | an output vector that Spark's cache refuses for its field |
| `VarkaExpressionCompilerSuite` (existing) | IN keeps each value once in ascending bit order, whatever order its list or set has | the case of `inLtz` | the test's own assertions: the three longs take the long table's slots in ascending order, an `InSet` is the same kernel | a `TIMESTAMP` IN list ordered or stored as an int table's |

`VarkaTimestampSuite` makes 25 answer checks: 18, 6 and 1. `dev/varka_session_lint.py` allows
40.

**Planted faults** (commit first; plant one, rerun, revert).

| fault | the test that must fail |
| :--- | :--- |
| `VarkaConditionCompiler.nullLeaf` gives `TIMESTAMP` the lane `LaneType.INT` | "a TIMESTAMP choice is written by the kernel ...", on `CASE WHEN ts < ts2 THEN ts END` or `nullif(ts, ts2)`: no kernel, or a declined batch |
| in the test's own table, the zone of the first test's third run is `UTC` again | nothing fails: the three zones are then two. This plant shows what the test cannot see; it is listed so that nobody takes the zone loop for a proof of the zone's absence. The twin suite is that proof |
| `longTable` without `TimestampType` | the IN order test, on `inLtz` |

**The commands,** in order; each comment is what the line must print.

    flock "$VARKA_HEAVY_LOCK" build/sbt 'sql/testOnly *VarkaTimestampSuite'   # 1: All tests passed., 3 tests
    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaExpressionCompilerSuite'   # 2: All tests passed.
    python3 dev/varka_session_lint.py; echo $?                            # 3: 0
    git status --short                                                    # 4: three lines, below
    python3 dev/varka_push_check.py; echo $?                              # 5: a size under 350, then 0

The three lines of `git status --short`:

     M <gens>/VarkaExpressionCompilerSuite.scala
     M sql/varka/plans/m9/VARKA-656.md
    ?? <execs>/VarkaTimestampSuite.scala

After the commit the lead runs Spark's golden files under Varka over the files that hold
timestamps (section 6) and the option matrix over `VarkaTimestampSuite`.

**Done when** the commands print what the table says and the review has no open finding.
**Not here:** any production code; a coverage row; a generated file.

### 5.4 Part D: the measurement

The lead pushes, opens the pull request and dispatches; the builder makes two local commits.

1. **The builder, first commit.** On `varka-656d-results`, from master with parts B and C: in this
   file's section 11, the line "Part D: the zone-free sections of `<Base>`, dispatched on
   <the date>; results pending." Nothing else. Report the branch and the commit.
2. **The lead.** Pushes the branch, opens the pull request `<n>` as a draft with the part's
   title, and dispatches both widths on it:

       gh workflow run benchmark.yml --repo vecbricks/varka --ref master -f pr=<n> \
         -f create-commit=false -f class=<Base's full name> -f jdk=25 \
         -f expected-cpu="AMD EPYC 7763"
       gh workflow run benchmark.yml --repo vecbricks/varka --ref master -f pr=<n> \
         -f create-commit=false -f class=<Base's full name> -f jdk=25 \
         -f expected-cpu="AMD EPYC 7763" -f extra-java-options=-XX:MaxVectorSize=16

   A run on another CPU is dispatched again. The lead downloads each run's artifact, whose
   two files are named `<Base>-jdk25-results.txt` and `<Base>-jdk25-provenance.txt` at both
   widths, and renames `<Base>-jdk25-` to `<Base>-jdk25-runner-epyc7763-` for the first run
   and to `<Base>-jdk25-runner-epyc7763-128bit-` for the second (`VARKA-655.md` 4.9). It
   gives the builder one directory, `$R`, that holds the four files under those names, which
   are the names row 655 committed.
3. **The builder, second commit.** For each of the four files: `cp $R/<file>
   sql/core/benchmarks/<file>`, over the file of the same name that row 655 committed (`ls
   sql/core/benchmarks/<Base>-*` lists the four before the copy; a file of `$R` with no file
   of that name there is a stop). Then, for each of the two results files, `python3
   dev/varka_bench_diff.py --git HEAD <file>` gives each section against row 655's baseline,
   controls first; the same command with `--requote` must list nothing. Score predictions 8
   to 12 of section 7 in section 11, each with its number from the files. In `$M9/PLAN.md`,
   row 656's cell: `**Started**` becomes `**Done**`, with the five pull request numbers.
4. A zone-free section under twice the row path at 256 bits, or slower than it at either
   width, is a finding and not a failure of the part: the builder names the section and its
   ratio in section 11, and the lead adds it to row 680's list, which holds what is under the
   bar with the row that owns it.
5. `git status --short` then prints six lines: the four files of `sql/core/benchmarks`,
   `$M9/PLAN.md` and this file. The builder reports the commit; the lead pushes and marks the
   pull request ready.

**Done when** both widths' results are committed and each prediction is scored. **Not here:**
a change of code or of a default.

## 6. Tests, proofs, fuzzing and coverage

Every suite runs at both vector widths through the gate, under each option configuration
through the matrix, and with the memory sanitizer on.

| suite (language, module, directory) | part | what it holds | the failure only it catches |
| :--- | :--- | :--- | :--- |
| `VarkaZonedDeclinesSuite` (Java, JUnit 5, catalyst, `<gent>`) | A | the reason of each expression over a `TIMESTAMP` that no kernel answers, by exact text; the 22 cast pairs | a decline that names no row, or the wrong one |
| `VarkaTimestampTwinSuite` (Java, JUnit 5, catalyst, `<gent>`) | A2, B | the twin of each `TIMESTAMP_NTZ` coverage row, under a zone with changes | a zone-reading form computed by a `TIMESTAMP_NTZ` arm: right at UTC, wrong elsewhere, and invisible to every test of one zone |
| `VarkaExpressionCompilerSuite` (ScalaTest, catalyst, `<gens>`, existing) | A, B, C | the identity cast's arm; the flipped zoned test; the tree of `ts < ntz`; the IN order over the type | an arm no SQL reaches; an IN list in the wrong table |
| `VarkaTimestampConformanceSuite` (Java, JUnit 5, catalyst, `<gent>/varka`) | B | the type under the contract, at both ends of a long and of Spark's range | a wrong answer at an extreme |
| `VarkaLaneArrowSuite`, `VarkaKernelEvaluatorSuite` (ScalaTest, sql/core, `<execs>`, existing) | B | the vector classes and the zone's name | a column of the sibling type or of another unit admitted; a field with no zone |
| `VarkaTimestampSuite` (ScalaTest, sql/core, `<execs>`) | C | the lane through sessions, the cache and three zones | a path only a session has |
| the coverage suites, the census, `dev/varka_new_lane.py` (existing) | B | the registers agree with the compiler | a register that drifts |
| `VarkaEmittedBytesSuite`, `VarkaWidthAuditSuite` (existing) | B | no byte of an existing shape moves; the new rows are pinned at each width | a change that reached another type's kernel |

**Proofs.** None. The row adds no lowering: a comparison, a blend and a load of a long are
exact for every long, and their kernels are the ones `BIGINT` runs. `sql/varka/proofs` is not
touched.

**Registry suites of an IR record.** None applies: the row adds no IR record, no emit option
and no family.

**Fuzzing.** `VarkaFuzzTypes` gives the type its three sites in part B. The IR fuzzer's long
sequence already draws a long column, which is all the IR knows of the type, so no committed
corpus moves and no new sequence is pinned. The Spark fuzzer's fixture gains `ts` and `ts2`,
its grammar `Type.LTZ`, and the 23 rows join the compositions both fuzzers draw. The session
zone as a drawn setting is row 684's; until then the Spark fuzzer runs under the test JVM's
zone, which no form drawn here reads.

**Coverage and mutation.** The lead runs `dev/varka_gen_coverage.sh` after parts A and B and
reports the branches of `VarkaZonedDeclines` and of `VarkaLaneArrow` in each pull request:
every branch reached, or the test that reaches it added. Every case of `reason`'s switch has
a form in part A's tables. The mutation run (`dev/varka_pit.sh`) mutates classes of
`codegen/varka` against ScalaTest suites and does not reach `VarkaZonedDeclines` or
`VarkaLaneArrow`; the planted faults of each sheet stand in for it.

**Checks that need volume.** After part B merges, the lead runs Spark's golden files under
Varka over the files that hold timestamps, since `VarkaArrowSource` now feeds such relations
to kernels:

    dev/varka_golden_suite.sh --attribute --file timestamp --file datetime --file date.sql

A test that fails only with Varka on is a red master: the lead reverts part B or fixes
forward, as `sql/varka/AGENTS.md` has it. The nightly then runs every file and regenerates
`sql/varka/spark_suites/served.json`.

## 7. Measurement

**The sections** are row 655's zone-free ones in `<Base>`: the comparisons and the choices over
two `TIMESTAMP` columns, under the zones and data orders that class has. No section of this
row reads a zone, so the zone and the order are controls here: they must not move a ratio. The
numbers are the AMD EPYC 7763's at 256 and at 128 bits; the laptop adds 512 where the owner
asks. There is no A/B arm: the row adds no lowering and no option.

**Predictions, registered on 27 November 2026, before any run.** A ratio under 1.3 is run
again and read by minimums.

1. Part A moves no byte, no coverage row and no census count: `python3
   dev/varka_bytes_moved.py origin/master` prints nothing, and `ltz` reads `0 / N` as
   before. Yes or no; scored by part A's commands 8, 9 and 12.
2. The 23 rows add no kernel class. Each row whose twin is a `TIMESTAMP_NTZ` row has that
   row's shape hash in `sql/varka/emitted_bytes.json`. Yes or no; scored by part B's command
   19 and by the two rows' entries in that file.
3. `python3 dev/varka_census.py --summary` reads `23 / N` for `ltz` after part B, where it
   read `0 / N`: the 22 cells of 4.8 and the alias's identity. With `N` at 77, as row 654's
   plan has it with the date functions, that is 23 of the 58 that G1's three quarters ask.
4. `dev/varka_new_lane.py --check` prints `LONG/TimestampType: no site is missing (2
   checked)` after part B, and nothing for the type after part A.
5. Sizes: part A within 680 counted lines by half, part A2 within 270, part B within 720,
   part C within 250, each by half and each under 900. Scored by `python3
   dev/varka_msg_check.py size`.
6. Test time, read against `python3 dev/varka_calibration.py`. The two compile-only suites of
   parts A and A2 add under 10 s together: VARKA-318's compile-only suite over the coverage
   rows took
   about 2 s, and the twin suite resolves 77 rows twice. `VarkaTimestampConformanceSuite`
   adds under 20 s in its own JVM: the `TIMESTAMP_NTZ` suite is the nearest, and the narrow
   suites of VARKA-484 ran at 0.37 of an estimate of 30 s. `VarkaTimestampSuite` adds under
   15 s: VARKA-365's nine tests over one-row cached views took 5 s, at 0.3 of their
   estimate. The 23 rows add under 5 s to the coverage harness and differential: VARKA-333's
   43 rows ran at 0.14 of their estimate.
7. No `TIMESTAMP_NTZ` twin fails Spark's analyzer. Yes or no; scored by the twin suite's
   first test.
8. At 256 bits every choice section (`greatest`, `least`, `CASE`, `coalesce`) runs at least
   twice the row path's rate, G7's bar for a section that reads no zone. Yes or no for each
   section.
9. At 256 bits the filter sections run faster than the row path, and not all reach twice
   it. `VarkaKernelPlan`'s comment on the two-lane refusal records what milestone 8 measured
   for the `BIGINT` twins of these filters at four lanes: between one and a half times and a
   little over twice the row path. A filter under twice is a finding for row 680's list, not
   a failure of this row: the kernel is the long lane's.
10. At 128 bits the filter sections are refused by the host's rule (`Fusion.NONE`) and their
    two arms tie within 1.1; the choice sections are not slower than the row path.
11. A `TIMESTAMP` section's `varka on` time is within 1.1 of its `TIMESTAMP_NTZ` twin's, the
    section of `<Base>` titled `TIMESTAMP_NTZ twin` with the same form over the same
    instants (`VARKA-655.md` 4.7): it is one kernel class over another Arrow vector
    class. Scored for the comparison, `greatest` and the choice over a year in no order.
12. The zone of a section does not move its ratio: under each zone `<Base>` runs a section
    in, the section's ratios are within 1.1 of each other.

## 8. Who builds it

`m9/PLAN.md` 1.4 has rows 656, 657 and 682 to 692 built at full strength, and so is this row.

| part | builder | why |
| :--- | :--- | :--- |
| A2 | full strength by `m9/PLAN.md` 1.4 | it is one test file, but its rule is the row's main guard against a wrong answer |
| A | full strength | the table of 4.3 must hold every class the `TIMESTAMP_NTZ` arms claim, and six places answer before the fallback; a miss is a decline with no row, or, after part B, a wrong answer outside UTC |
| B | full strength | it changes what every reader of the registry sees at once, and its stops are the seams with rows 654 and 655 |
| C | full strength by `m9/PLAN.md` 1.4; a cheaper model only on the owner's word, recorded with its date | it is tests alone, from a sheet that names every query and value |
| D | the builder of C for the two commits; the lead for the push, the pull request and the dispatches | results |

What the cheaper models take from this row is row 667: one entry of 4.3's table turned into
an arm, with its coverage row.

## 9. Risks

1. **A `TIMESTAMP_NTZ` arm reaches a `TIMESTAMP`.** It would answer right at UTC and wrong in
   every zone with an offset, and no fallback catches a wrong answer. No arm is widened; the
   twin suite asks Spark which twins read the zone and fails one that shares its row's
   kernel; the planted fault of part B shows the test failing.
2. **A premise on row 654 or 655 fails.** Row 654's plan is the reference and was read; row
   655's was not. B0 of each part greps each name and stops.
3. **Spark's own suites now run `TIMESTAMP` comparisons on kernels,** through
   `VarkaArrowSource`. The lead runs the golden files with timestamps after part B merges
   (section 6); the whole set is the nightly's, and its record `served.json` moves there.
4. **A form resolves to another class than this plan read.** Part A's suite asserts exact
   texts, and a text that differs is a stop with the form named, never an edit of the
   expectation.
5. **The pin test's and the width audit's time.** 23 rows join 868, and
   `VarkaEmittedBytesSuite`'s pin test has run close to its limit on AVX2 runners
   (`sql/varka/AGENTS.md`, "Master stays green"). The rows add no shape (prediction 2), but
   each is emitted under every option arm. The lead reads the test's time in the Build of
   part B; past nine tenths of its limit, the pull request waits for row 695.
6. **The parts' sizes.** A is 680 by estimate after the twin suite moved to A2, and B is 720
   after the session suite moved to C. Over 900 a builder stops and reports; nothing more
   can leave part B without leaving master red.
7. **The reworded refusal** moves 188 generated lines in each of two files, and every open
   pull request that regenerated them must merge master. Wave 1 holds few such pull requests,
   and the owner decided for the rewording (4.9).
8. **A literal with no `Z`.** A `TIMESTAMP` literal is two instants under two test JVMs. Every
   literal of 4.8 and of the suites ends in `Z`; a coverage row with a bare literal would
   pass in a ScalaTest JVM and miss the fixture's value in a JUnit one.
9. **The label on a kernel's output.** Spark ignores its value, so a wrong name passes every
   test. The evaluator's test and part B's command 16 hold the path.
10. **Row 653 merges first.** Its layout is one file an entry; B0 of each part stops then
    (5.0), and the plan is re-cut.
11. **The census check after the script.** The script of 4.8 was run on a scratch copy, but
    `--check` over its result was not. Part B's command 6 is that check; its planted fault
    shows the need rule failing.

## 10. The house rules that bind this row

From `sql/varka/AGENTS.md`, the rules a builder of this row is most likely to break:

- "**The Varka code carries no `TODO` or `FIXME` comments, and should not start.**" The
  declines of part A name their rows in a reason text; they carry no marker.
- "**A comment describes the code as it is.**" No "used to", "the old" or "since VARKA-656".
  The comment of the registry's entry says what the type is, not that it was declined; the
  three cases part B deletes from `VarkaZonedDeclines` leave no comment behind.
- "A new catalyst suite whose helpers are Java is a JUnit 5 class under `@VarkaSuite`", each
  `@Test` with its sentence as a `@DisplayName`. The session suite stays ScalaTest.
- "A test starts from `VarkaMatrix.base`, not `VarkaEmitOptions.DEFAULTS`".
- "A switch over a sealed type or an enum has no `default`". The switch of
  `VarkaZonedDeclines` is over `Expression`, which is not sealed, so it has one; the switches
  over `VarkaTypeId` keep none.
- "The generated files every type row rewrites" ... "are never merged by hand."
- "**A pull request stays under 1000 changed lines** of hand-written files"; the curated
  census counts.
- "**Review your own diff before opening the pull request**" with `/code-review high`.
- "The ghost fallback is a correctness contract". What can return a wrong answer here is a
  zone-reading form on a `TIMESTAMP_NTZ` arm, and the twin suite is its differential.
- "No allocation, streams, lambdas or boxing on a path that runs per batch or per row". The
  zone's name is a field read in `allocateVector`; nothing is built per batch.
- "**Never kill by name pattern**", "**Never `git stash`.**", "**Write only inside your own
  worktree and scratch area.**"

From the root `AGENTS.md`: a test pins the configs its assertions depend on. Every test of
`VarkaTimestampSuite` sets the session zone itself and reads no default.

Do not: widen a `TIMESTAMP_NTZ` arm; add an arm for a class of 4.3's table; add an IR node,
an emit option, a family or a default; edit a generated file by hand; edit
`dev/varka_census.py`; change a value of an existing fixture column; write a `TIMESTAMP`
literal with no `Z`; change an expected text of `VarkaZonedDeclinesSuite` to make a form
pass; print the environment; open `/home/max/proj/runtime-master`.

## 11. Outcome

*To be written when the row closes.*

## 12. Seams with the plans written beside this one

**Row 654.** Its plan is `VARKA-654.md` of `sql/varka/plans/m9/`. It merges first and is the
reference for the census. What this row reads of it, each with where that plan gives it and
where this plan checks it:

| what this row reads | `VARKA-654.md` | checked in |
| :--- | :--- | :--- |
| the registry's `TIMESTAMP` entry has the census name `"ltz"` while its admission is `Declined`; `types.tsv` reads `ltz` and `declined` | 4.5; 5.2 step 2 | part A, B0 lines 2 and 7 |
| `CAST_TESTS` holds `"ltz"`, 14 entries, with the test `castToTimestamp` | 5.2 step 5 | part B, B0 line 11 |
| milestone 9's five files in `sql/varka/plans/m9/`, read there by every reader | 4.1 | part A, B0 line 3 |
| 21 entries admit `ltz` through `ALL`, carry `656` in their rows and "ltz is row 656's" in their note, with no `ltz` fused; `isnull` and `isnotnull` are `plumbing` | 4.7; 5.2, the script | part B, B0 lines 7 and 9 |
| `in_set` the same, with `667` in its rows and "ltz is row 667's" | 4.7; 11 | part B, B0 line 8 |
| the entry `ntz_minus_ntz` of class `SubtractTimestamps`, types `ntz,ltz`, rows `339,671` | 4.7; 11 | part B, B0 line 6 |
| `"timestamp": "ltz"` in `SIMPLE_COLUMN_TYPES` | 4.5 | part B, B0 line 5 |
| the 23 cast pairs with their rows: `657,669` for `ltz` to `date`, `690,669` for `date` and `ntz` to `ltz`, `669` for the rest; each `declined` in the probe | 4.8, `cast_rows` | 4.3 and part A's table of the 22 forms take the first row of each |
| the summary's line `  ltz     new         0 / N`, `N` 77 on the plan's commit | 4.10; 5.2 command 3 | part A, B0 line 5 |
| an identity cast over a type a kernel reads counts as fused: one more than this row's cells, by itself | 4.9; 11 | part B, command 7 |
| the record of Spark's cast suites: 19 lines whose target is `ltz`; lines whose source is `ltz` once a kernel reads the type | 4.8; 11 | part A, commands 6 and 7; part B, commands 13 and 14 |
| the docs' and the README's sentences that count the census's types, "fourteen", with "which no kernel reads yet" in the README | 5.2 step 8 | part B, B0 line 20 and step 11 |

What this row gives back to that file: the 22 fused cells, `656` in the rows of `in_set`, and
`done` for `isnull` and `isnotnull` (4.8).

**Row 655.** Its plan was written beside this one and could not be read.

| what this plan assumes | who adds it | where this plan checks it |
| :--- | :--- | :--- |
| exactly one baseline class holds the zone-free sections over two `TIMESTAMP` columns, a `greatest` among them, in `<exect>/benchmark`, named `Varka...BaselineBenchmark` | row 655 | part B, B0 line 12 |
| the class declares `record Section(String title, String query, Fusion fusion)` and lists its sections as `Fusion.NONE`, as `VarkaTimestampNtzBaselineBenchmark` does, and has a queries suite named `...BaselineQueriesSuite` that fails a section fusing unlisted | row 655 | part B, B0 lines 13 and 14, command 17 |
| its four results and provenance files of the EPYC 7763 at both widths are in `sql/core/benchmarks` before part B | row 655 | part D, step 3 |
| the class has twins of the `TIMESTAMP_NTZ` baseline's comparison and choice sections | row 655 | part D; prediction 11 is not scored where it has none |

**Rows 678 and 679.** No seam: this row reads no zone data and no form of `java.time`.

**Row 657,** the plan that waits for this row (`VARKA-657-PARTS.md`, merged). What this row
hands it is in section 1's first table and in 4.3. `hour`, `minute`, `second` and `CAST(ts AS
DATE)` decline here with the texts row 657's first part gives from its own arm, so that part
changes no reason. `VarkaZonedDeclinesSuite` judges a form only while it declines, and
`VarkaTimestampTwinSuite` passes a zone-reading twin whose kernel is not its `TIMESTAMP_NTZ`
row's, so neither suite needs an edit when a row fuses a form.

**Deviations from the shared names of the zone family:** none. The row reads and adds no name
of rows 657 and 682 to 692.
