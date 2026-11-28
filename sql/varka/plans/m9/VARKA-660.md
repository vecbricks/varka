# VARKA-660: How precisions 7 to 9 enter the census

*Planned on 27 November 2026, revised the same day after two reviews, and decided the same day.
The owner chose (b), two census types with the precision a band; that the seven date functions
count over the two types, and over `ntz` and `ltz` too; and that a layout is no census type
(section 13 has his words). The row's done-when is met by this file's own pull request, which
also edits `VARKA-654.md` for the date functions (section 12). The plan stands alone: an agent
needs this file and the repository's `AGENTS.md` files. The row is a design and has no code
part. Nothing is built from it before the owner's word: milestone 9 waits for it (`m9/PLAN.md`
decision 18), and row 661, which builds this design, also waits for row 654, which waits for
milestone 8's close (decision 11). The code facts were checked on master `84d6b2aff10`, in
`/home/max/proj/varka-m9-read-w`. Every count below was computed by a command of this file on
that commit, over a scratch copy of the census as row 654's part b leaves it with section 12's
edits (section 3 says how the copy was made). Each count is a prediction: row 661 restates it
by the command beside it. The text of choices (a) and (c) stays, as the record of what was not
chosen.*

## 1. What this row is

**The row's text** (`m9/PLAN.md` section 3): "How precisions 7 to 9 enter the census." It is
"a design, no code", and its done-when is: "the plan states the census types and the cast pairs
each choice gives, with their counts, and the owner has chosen".

In plain words. Spark has six nanosecond timestamp types: `TIMESTAMP_NTZ(p)` and
`TIMESTAMP_LTZ(p)` for `p` of 7, 8 and 9. The census counts what Varka fuses by (entry, type)
pairs, and the cast matrix by ordered pairs of types. This row decides what a "type" is there
for these six: six census types, or two with the precision held some other way. It gives the
counts of each choice, recommends one, and writes down what row 661 builds from it.

The owner asked for this row by decision 7 of `m9/PLAN.md`: "How precisions 7, 8 and 9 enter
the census (one type per kind with a precision band, as `DECIMAL` has, or one type per
precision) is designed, not assumed: row 660's design answers it."

**What must be merged before it starts.** Nothing: the row reads code and writes this file.
Its counts rest on row 654's part b (`VARKA-654.md` 5.2), which is not built yet; section 3
says what stood in for it.

**What it gives the rows after it.**

| row | what it reads of this row | where in this file |
| :--- | :--- | :--- |
| 661, the census and the cast matrix with the nanosecond types | the census types and their names, order and forms; the rule that turns Spark's six types into them; the cast table's legality, kinds, proofs and rows; the admission table's format and the command that writes its lines; the band; the one-off script for the curated file; the build order; the commands and what they print | sections 4 and 6 |
| 662, the nanosecond layout | the two registry ids and their census names; the rule that a layout is not a census type; the 28 entries and the two casts whose cells name row 662 first | 4.4, 4.5, 4.7 |
| 673, the nanosecond fields | its 24 entries and 48 pairs, the seven date functions among them, each cell naming the row | 4.5 |
| 674, the nanosecond casts | its 5 cast entries and the 20 cast pairs, with the forms the probe holds for each | 4.5, 4.6 |
| 675, the nanosecond functions | its 12 entries and 23 pairs; the one entry whose answer depends on the precision, and what its fused claim needs | 4.4, 4.5 |
| 655, the baselines | which precision a nanosecond baseline class reads (section 11, a seam) | 11 |
| 677, the close | the denominators of item 4 of `m9/PLAN.md` 1.2 | 4.2 |

**The goals it serves** (`m9/PLAN.md` 1.1), each with the item of 1.2 it moves.

- **G4**, directly: "**G4. Nanosecond `TIMESTAMP_NTZ` and `TIMESTAMP_LTZ`, precision 7 to 9
  (phase 2).** Admitted while Spark's `spark.sql.timestampNanosTypes.enabled` is on, as Spark
  itself admits them; the functions Spark accepts over them fuse, and the casts among `DATE`,
  `TIME`, the microsecond and the nanosecond timestamps." Item 4 of 1.2: "**G4:** at least three
  quarters of each nanosecond type's pairs fused under the flag, and at least two thirds of the
  nanosecond types' cast pairs row 661 adds (about 20) fused or declined with their reason."
  This row fixes what "each nanosecond type" is and so both denominators. A reader checks it
  in 4.2: the choice the owner takes gives the two numbers that item 4 is read against.
- **Item 7 of 1.2**, directly: "Every pair left unfused carries its reason in the census: a
  declined band, an owner's decision, a function Spark itself rejects over the type, or a named
  row of the next milestone." This row says where "a function Spark itself rejects over the
  type" is written (the census's reason column, from Spark's own answer) and what "a declined
  band" is for a nanosecond type (4.4). Check: section 6.5, commands 5 and 7.
- **G5**, only through rows 673 and 674: the fan-out's tasks are one entry and one type each
  (`m9/PLAN.md` 2.7), so the number of census types is the number of near-identical task plans.
- **G6 and G7**, only through the rows above: the post quotes the census, and G7's nanosecond
  bars are read on row 655's classes.
- **G1, G2 and G3** are not served: the row moves no microsecond cell.

## 2. What an agent must know

**The census.** `dev/varka_census.py` reads every name of Spark's function registry and writes
one line a name into `census.tsv`. A name is *in scope* when its inputs and its result can all
be types of the census; any other name is *out*, with a reason. For each name in scope the
*curated file*, `census_curated.txt`, holds the types it admits, its result, the types Varka
fuses today, the *need* and the *rows*, the task rows that owe what is not fused. The script
fails when a type is claimed fused that no row of the coverage table (`sql/varka/coverage.json`)
shows. Row 654 gives milestone 9 files of its own, in `sql/varka/plans/m9/`.

**A census type** is a short name of a Spark type. Today there are thirteen, `bool byte short
int bigint float double dec date ntz time ym dt`; row 654 adds `ltz`, the microsecond
`TIMESTAMP`. The names and their order come from the type registry (`VarkaTypes`, Java): one
`VarkaTypeId` is one census type, through the committed table `sql/varka/types.tsv`. A **pair**
is one (entry, type): an entry and one type it admits. `python3 dev/varka_census.py --summary`
prints, for each type, the pairs fused of the pairs admitted; `m9/PLAN.md` 1.2 reads its items
there.

**A form** is one Spark `DataType` of a census type that the tests probe. Most types have one
form. `time` has three, `TIME(0)`, `TIME(3)` and `TIME(6)`; `dec` has two, `DECIMAL(10, 2)` and
`DECIMAL(30, 4)`; each interval has one for each field range. The registry's entry lists them
(`VarkaTypes.Info.forms()`).

**The cast matrix.** `casts.tsv` has one line for each ordered pair of census types that
Spark's `Cast` allows, with its legality under LEGACY, ANSI and TRY, its *kind* (what the cast
does), its *proof* and its rows. The legality is the script's function `legal()`, and the suite
`VarkaCastMatrixSuite` holds it to `Cast.canCast`, `Cast.canAnsiCast` and `Cast.canTryCast`
over every pair and every form. **The probe**, `casts_fused.tsv`, written by the same suite,
has one line for each legal cast between two forms: there a precision is already visible, as
`TIME(3)` to `TIME(0)` is a line of the pair `time` to `time`. A pair counts fused only when
every one of its form lines fuses (`pair_credit`).

**A band** is a part of one census type that the census tells apart without making it a
type. `dec` has two, precision up to 18 (one word a value) and 19 to 38 (two words). The
census sees a band in three places: the declared type of a coverage column
(`column_type("decimal(19,2)")` is `('dec', 'p38')`); two lines of the summary, "decimal
entries with a coverage row that reads a decimal column of the band"; and the mark `^` in the
curated file, "fused in part", for a type that fuses at one band only and so does not count.

**Spark's nanosecond timestamps** (SPARK-56822, a preview). Two classes, each with a
precision: `TimestampNTZNanosType(p)`, a local date and time, and `TimestampLTZNanosType(p)`,
an instant, with `p` of 7, 8 or 9. In this file a **kind** is one of the two classes. A value
of any of the six types is one `TimestampNanosVal`: `epochMicros`, a long, and
`nanosWithinMicro`, a short from 0 to 999. So the precision is in the type and not in the
value's layout: a value of precision 7 is the same two words with a second word that is a
multiple of 100, and of precision 8 a multiple of 10. A cast to a lower precision floors the
second word to that step; it never rounds. The types exist only while the internal flag
`spark.sql.timestampNanosTypes.enabled` is on; it is on under tests and off in production.
Since SPARK-60335 a function with no nanosecond support rejects such an argument in the
analyzer, in place of narrowing it to microseconds.

**A layout** is how a kernel holds a nanosecond value, which is row 662's subject: two words
as Spark has them, or one long (`m9/PLAN.md` 2.5). It is not a Spark type.

**The admission table** is a file that row 661's suite writes: for each census entry that
takes a timestamp, whether Spark's analyzer accepts it over each nanosecond type, asked with
the flag on (`m9/PLAN.md` 2.4: "admission is not a hand list"). This row fixes its format
(6.2), not its suite.

**The names this row reads.**

| name | where | meaning |
| :--- | :--- | :--- |
| `ORDER`, `GROUPS`, `MILESTONES[9]`, `NEW` | `dev/varka_census.py`, the last two after row 654 | the census types in the registry's order; the groups of the curated file; milestone 9's types and the ones it adds |
| `legal`, `cast_kind`, `ltz_cast_kind`, `cast_proof`, `ZONE_PROOF`, `cast_rows`, `pair_credit`, `CAST_ENTRY_MODES` | `dev/varka_census.py`, `ltz_cast_kind` and `ZONE_PROOF` after row 654 | the cast matrix's columns; the credit of a cast entry; the names of the cast entries |
| `row`, `build`, `summary`, `column_type`, `coverage_index`, `coverage_by_type`, `decimal_representation_lines` | `dev/varka_census.py` | one entry's census row and its claim rules; the summary; a coverage column's census type and band |
| `NANOS` | `dev/varka_census.py`, after row 654 | the reason that keeps `timestamp_nanos` and `unix_nanos` out until row 661 |
| `VarkaTypeId`, `VarkaTypes.Info`, `Info.forms()` | catalyst, `codegen/varka/` | a type's id and its registry entry, with its census name and forms |
| `TimestampNTZNanosType`, `TimestampLTZNanosType`, `AnyTimestampNanoType`, `TimestampNanosVal` | Spark, `sql/api` and `common/unsafe` | the two classes, the abstract type that accepts both at every precision, the value |

**The names this design adds**, each built by row 661 (section 6).

| name | where | meaning |
| :--- | :--- | :--- |
| the census names `ntz_ns` and `ltz_ns` | `VarkaTypes`, `sql/varka/types.tsv` | `TIMESTAMP_NTZ(p)` and `TIMESTAMP_LTZ(p)`, `p` from 7 to 9, in the census and the cast matrix |
| `VarkaTypeId.TIMESTAMP_NTZ_NANOS`, `VarkaTypeId.TIMESTAMP_LTZ_NANOS` | catalyst, `codegen/varka/VarkaTypeId.java` | the two ids, after `TIMESTAMP`, each with three forms |
| `NS`, `LTZ_KIND`, the group `NS` | `dev/varka_census.py` | the two names; the census types that hold an instant; the group for the curated file |
| `ns_cast_kind(src, dst)` | `dev/varka_census.py` | the kind and semantics of a cast pair with a nanosecond type |
| `NANOS_ADMISSION`, `ADMISSION_HEADER`, `ACCEPTED`, `UNASKED`, `nanos_admission(root)`, `admit_nanos(name, t, admission, errors)` | `dev/varka_census.py` | the admission table's path and header, its "yes", the cell not answered yet, its reader, and the rule that admits a type from it |
| `asked_entries()`, `admission_skeleton(asked, admission)`, `admission_errors(asked, admission)`, the option `--asked` | `dev/varka_census.py` | the entries the table has lines for, in the row order of `census.tsv`; the table's text for them; a line no entry asks and a wrong order; the command that rewrites the table's lines |
| `sql/varka/plans/m9/nanos_admission.tsv` | the plans folder | the admission table |
| `PRECISION`, `PRECISION_BANDS`, `nanos_bands(data)`, `precision_lines(scoped)` | `dev/varka_census.py` | the band of the entries: each entry whose answer depends on the precision, with the spellings of its call, the keyword that makes it depend and the place of its timestamp argument; the three bands; the precisions at which the coverage rows hold such a call over a column; the summary's lines |
| `reads_precision(src, dst)`, `CAST_TARGET`, `cast_bands(data)` | `dev/varka_census.py` | the band of the casts: the pairs whose answer depends on the target's precision, a target type as a coverage row's SQL writes it, the precisions to which the coverage rows cast a column of the pair's source |
| `sql_code(sql)`, `sql_calls(code, names)`, `bare_column(text, columns)` | `dev/varka_census.py` | a coverage row's SQL with strings and comments blanked; the calls of some functions in it, with their arguments; an argument that is one column and nothing more |

## 3. The admission check

Each fact was checked on master `84d6b2aff10`, in `/home/max/proj/varka-m9-read-w`. Below,
`C=sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst`,
`A=sql/api/src/main/scala/org/apache/spark/sql` and `E=$C/expressions/datetimeExpressions.scala`.
In the table a `\|` inside a command stands for `|`, escaped for the table.

| # | fact | command | prints |
| ---: | :--- | :--- | :--- |
| 1 | Each kind has the precisions 7 to 9, so Spark's distinct nanosecond types are six | `grep -h 'val M.._PRECISION' $A/types/TimestampNTZNanosType.scala $A/types/TimestampLTZNanosType.scala` | four lines: `val MIN_PRECISION: Int = 7` and `val MAX_PRECISION: Int = 9`, twice |
| 2 | One value layout for all six | `grep -h 'public final [ls]' common/unsafe/src/main/java/org/apache/spark/unsafe/types/TimestampNanosVal.java` | `public final long epochMicros;` and `public final short nanosWithinMicro;` |
| 3 | A lower precision floors the second word | `grep -h 'case [789] =>' $A/catalyst/util/SparkDateTimeUtils.scala` | `case 7 => (nanosWithinMicro / 100) * 100`, `case 8 => (nanosWithinMicro / 10) * 10`, `case 9 => nanosWithinMicro` |
| 4 | The flag is internal, on under tests and off in production | `grep -A15 'val TIMESTAMP_NANOS_TYPES_ENABLED' $C/../internal/SQLConf.scala \| grep -c -E 'internal\(\)\|Utils.isTesting'` | `3`: `.internal()`, the doc's "tests enable it by default via Utils.isTesting" and `.createWithDefaultFunction(() => Utils.isTesting)` |
| 5 | The casts among the six, and with the two microsecond timestamps, are legal in `canCast` and in `canAnsiCast` alike; no cast with a number or a `BOOLEAN` is | `grep -c 'case (_: AnyTimestampNanoType, _: AnyTimestampNanoType) => true' $C/expressions/Cast.scala`; `grep -c -E 'NumericType, _: AnyTimestampNanoType\|AnyTimestampNanoType, _: NumericType\|BooleanType, _: AnyTimestampNanoType\|AnyTimestampNanoType, BooleanType' $C/expressions/Cast.scala` | `2`; `0`. The full reading is 4.6 |
| 6 | No type check of an expression names a precision: every one takes `AnyTimestampNanoType`, which accepts all six | `git grep -h -E 'Timestamp(NTZ\|LTZ)NanosType\((7\|8\|9)\)' -- $C/expressions $C/analysis` | two lines: the result type of `NanosToTimestamp`, `override def dataType: DataType = TimestampLTZNanosType(9)`, and a comment in `UTCTimestamp` |
| 7 | The classes of the datetime expressions whose code reads a nanosecond type's precision | `awk '/^(case class\|object\|private\[[a-z]*\] object) /{c=$1" "$2" "$3; sub(/\(.*/,"",c)} /[a-z]\.precision\|precision: Int/{print c}' $E \| sort -u` | nine lines: `case class CurrentTimestampNanos`, `case class DateFormatClass`, `case class GetTimestamp`, `case class LocalTimestampNanos`, `case class MakeTimestampLTZNanos`, `case class MakeTimestampNTZNanos`, `case class TimestampAdd`, `object DateFormatClass {`, `private[expressions] object ParseToTimestampBuilder`. 4.4 reads them |
| 8 | SPARK-60335 is in the fork: no implicit narrowing | `git log --oneline -1 ec58b58440a`; `grep -c 'protected def isNanosNarrowing' $C/analysis/TypeCoercionHelper.scala` | `ec58b58440a [SPARK-60335][SQL] Do not implicitly narrow nanosecond-precision timestamps`; `1` |
| 9 | A subtraction with a nanosecond operand is rejected by a rule of its own | `grep -c 'UNSUPPORTED_FEATURE.TIMESTAMP_NANOS_SUBTRACTION' $C/analysis/NanosTimestampSubtractionCheck.scala` | `1` |
| 10 | `months_between` takes the microsecond `TIMESTAMP` alone | `awk '/^case class MonthsBetween/{f=1} f && /override def inputTypes/{print; exit}' $E` | `  override def inputTypes: Seq[AbstractDataType] = Seq(TimestampType, TimestampType, BooleanType)` |
| 11 | `convert_timezone` rejects a nanosecond `TIMESTAMP_LTZ` | `grep -c 'case TypeCheckSuccess if sourceTs.dataType.isInstanceOf\[TimestampLTZNanosType\] =>' $E` | `1`, in `ConvertTimezone.checkInputDataTypes` |
| 12 | The registry holds neither type | `grep -c 'Nanos' sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaTypes.java` | `0` |
| 13 | The census's types are the registry's | `grep -n '^ORDER' dev/varka_census.py` | one line, `ORDER = [r["census"] for r in types_rows() if r["census"] != "-"]` |
| 14 | The two nanosecond names are out of the census today | `grep -n -E '"timestamp_nanos"\|"unix_nanos"' dev/varka_census.py` | two lines of `NAME_EXCLUSIONS`, each ending `LTZ,`; row 654 turns them to `NANOS` (`VARKA-654.md` 4.6) |
| 15 | `TIME` is one census type whose own pair is a real cast, with its precision in the probe's forms | `python3 -c "import sys; sys.path.insert(0, 'dev'); import varka_census as c; print(c.cast_kind('time', 'time')[0])"`; `awk -F'\t' '$1=="time"&&$2=="time"' sql/varka/plans/m8/casts_fused.tsv \| wc -l` | `relabel or truncation`; `6` |
| 16 | `dec` is one census type with two bands | `grep -P "^DECIMAL\t" sql/varka/types.tsv \| cut -f10`; `python3 dev/varka_census.py --summary \| grep -c -E 'an unscaled long\|a lane pair'` | `p18:1,p38:2`; `2` |
| 17 | The summary prints a type's name in seven columns | `grep -c '{t:7s}' dev/varka_census.py` | `1`, in `summary` |
| 18 | Spark's cast suites under Varka already cast to and from the six types, and the record has no census name for them | `awk -F'\t' '$4 ~ /^TIMESTAMP_[NL]TZ\(/ \|\| $5 ~ /^TIMESTAMP_[NL]TZ\(/' sql/varka/plans/m8/casts_suites.tsv \| wc -l`; `awk -F'\t' '($4 ~ /^TIMESTAMP_[NL]TZ\(/ && $2 != "-") \|\| ($5 ~ /^TIMESTAMP_[NL]TZ\(/ && $3 != "-")' sql/varka/plans/m8/casts_suites.tsv \| wc -l` | `36`; `0` |
| 19 | A fused claim needs a coverage row of the entry's class over the type | `grep -c 'no coverage row has a' dev/varka_census.py` | `5`: the rule in `row`, twice, and its doctests |
| 20 | The coverage table has 868 rows, and there is one conformance suite a census type, 14 today | `python3 -c "import json; print(len(json.load(open('sql/varka/coverage.json'))['expressions']))"`; `ls sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka \| grep -c 'ConformanceSuite.java'` | `868`; `14` |
| 21 | Today's summary | `python3 dev/varka_census.py --summary \| grep -E '^in scope:\|pairs: \|^cast pairs'` | `in scope: 182 entries (156 classes)`, `(entry, type) pairs: 896, fused today 588`, `cast pairs over the 13 types: 169 ordered, 106 legal` |
| 22 | The helpers through which Spark's code floors or builds a nanosecond value at a precision are sixteen | `grep -h -B4 'precision: Int' $C/util/DateTimeUtils.scala $A/catalyst/util/SparkDateTimeUtils.scala \| grep -o 'def [A-Za-z]*Nanos[A-Za-z]*' \| sort -u \| wc -l` | `16`. Without `wc`, the names: `convertSpecialTimestampLTZNanos`, `convertSpecialTimestampNTZNanos`, `epochNanosToTimestampNanos`, `instantToTimestampNanos`, `localDateTimeToTimestampNanos`, `makeTimestampLTZNanos`, `makeTimestampNTZNanos`, `stringToTimestampLTZNanos`, `stringToTimestampLTZNanosAnsi`, `stringToTimestampNTZNanos`, `stringToTimestampNTZNanosAnsi`, `timestampAddNanos`, `timestampLTZNanosToNTZNanos`, `timestampNTZNanosToLTZNanos`, `truncateNanosWithinMicroToPrecision`, `truncateTimestampNanosToPrecision` |
| 23 | Their callers in every expression, analyzer and optimizer file are four files | `N=$(grep -h -B4 'precision: Int' $C/util/DateTimeUtils.scala $A/catalyst/util/SparkDateTimeUtils.scala \| grep -o 'def [A-Za-z]*Nanos[A-Za-z]*' \| sort -u \| cut -c5- \| paste -sd'\|'); git grep -c -E "\b($N)\b" -- $C/expressions $C/analysis $C/optimizer` | four lines, ending `expressions/Cast.scala:20`, `expressions/aggregate/percentiles.scala:1`, `expressions/datetimeExpressions.scala:6`, `optimizer/finishAnalysis.scala:6`: the casts, an aggregate, the classes of fact 7, and `ComputeCurrentTime`, which folds `current_timestamp(p)` and rewrites a cast from `TIME` |
| 24 | Seven entries of the census take a `DATE` and list no timestamp | `awk -F'\t' '$4=="in" && $2 ~ /^(AddMonths\|DateDiff\|DateAdd\|DateSub\|UnixDate)$/ {print $1, $6}' sql/varka/plans/m8/census.tsv` | seven lines: `add_months int,date`, `datediff date`, `date_diff date`, `date_add byte,short,int,bigint,date`, `dateadd byte,short,int,bigint,date`, `date_sub byte,short,int,bigint,date`, `unix_date date` |
| 25 | Spark's golden files call five of the seven over a nanosecond timestamp | `grep -c -i -E '(date_add\|date_sub\|dateadd\|datediff\|add_months)\(' sql/core/src/test/resources/sql-tests/inputs/timestamp-ntz-nanos.sql sql/core/src/test/resources/sql-tests/inputs/timestamp-ltz-nanos.sql` | `...timestamp-ntz-nanos.sql:14` and `...timestamp-ltz-nanos.sql:18`; the results files hold a result for each (SPARK-60543 for the first three) |
| 26 | A coverage row is run against Spark, not only compiled | `grep -c 'a coverage row is also a differential query' sql/varka/skills/testing-and-debugging.md` | `1`; the suite is `VarkaCoverageDifferentialSuite` |

**Which entries Spark accepts over a nanosecond type** was read from the code, class by class
(4.5 names each class), and checked against Spark's own golden files
`sql/core/src/test/resources/sql-tests/results/timestamp-ntz-nanos.sql.out` and
`timestamp-ltz-nanos.sql.out`: of the entries that have a call there, each accepted one returns
a result and each rejected one an analysis error. For example this prints `2`, one under each
ANSI setting:

    grep -A8 '^SELECT months_between' sql/core/src/test/resources/sql-tests/results/timestamp-ntz-nanos.sql.out | grep -c UNEXPECTED_INPUT_TYPE

**How the counts were made.** Row 654's planner left a copy of the census as its part b
leaves it: the script, the registry's table, the curated file, the probe and the suites'
record, which pass `--check` and print the summary of `VARKA-654.md` 4.10 (198 entries in
scope, 1012 pairs, 129 legal cast pairs). This row first ran row 654's own one-off script
again, with the two insertions of 12.2 that count the seven date functions for `ntz` and
`ltz`, on the curated file that planner ran it on: 198 entries, 1026 pairs, 129 legal cast
pairs, and `--check` passes. That is the base of every count here. Without the insertions
the script gives the planner's curated file byte for byte, which shows the rerun is the
planner's run. This row then copied the base three times and applied
each choice to one: the script's edits, the two or six lines of the registry's table, the
admission table as this row reads Spark, the probe's lines, and the curated file. Each of the
three was made in the order of 6.0 and passes the script's doctests and `--check`. The edits
of the recommended choice are section 6, as text that was generated from what ran; taken out
of this file again and applied to a fresh copy, they give the prototype's script, curated
file, census, cast table and summary byte for byte. Choice (c) is those edits less the band's
(6.1 says which). The prototype of (a) differs from (b) in this and nothing else: six names in
`NS`, each with one form; a type's own pair is an identity, and a pair of two precisions of
one kind is "relabel or truncation"; `column_type` gives a column's type with its precision in
the name and no band; the admission table has a line a type; and the band's edits are absent.
Its counts need no prototype: 4.2 derives each by arithmetic.

Three files of the copies stand in for what only a JVM suite writes: the two or six lines of
`sql/varka/types.tsv`, the probe's 102 lines, and the 36 renamed lines of the suites' record.
6.0 names the suite that writes each at the build. The admission table's lines were written by
the script's own `--asked`, and its answers were filled in from 4.5's reading, where row
661's suite asks Spark.

**What I could not check.**

- No JVM ran. The admission of 4.5 is a reading of Spark's sources and golden files, not the
  answer of Spark's analyzer; row 661's suite asks the analyzer. Nor could `ruff format` run:
  it is not installed here, so the new lines are wrapped by hand within 100 columns. The
  probe's 102 lines were written as `declined`, which is what the compiler answers for a
  type the registry declines (`VARKA-654.md` fact 22), not asked of it.
- Row 654 is not built. The base of every count is its planner's prototype, and milestone 8's
  census moves until its close. Row 656 is planned on top of row 654 and moves fused cells of
  `ltz`, not the types.
- Whether Spark's answer for an entry differs between the two ANSI settings. SPARK-60335 says
  it does not for the functions it lists; the admission table has one answer a precision, and
  row 661's suite asks under both settings and fails if they differ (6.2).
- What row 653 does to the curated file and to `casts.tsv` ("one file an entry"). Section 11.

## 4. The design

### 4.1 The choices

**(a) Six census types**, one a kind and a precision: `ntz_ns7`, `ntz_ns8`, `ntz_ns9`,
`ltz_ns7`, `ltz_ns8`, `ltz_ns9`. Six registry ids, each with one form. A cast between two
precisions is a pair of two types. An entry admits each type by itself.

**(b) Two census types, the precision a band where an answer depends on it**: `ntz_ns` and
`ltz_ns`. Two registry ids, each with three forms, `TIMESTAMP_NTZ(7)`, `(8)` and `(9)`. The
precision is seen in three places and nowhere else: as a form in the cast probe; as a band on
the one entry and the six cast pairs whose answer depends on it, each of which then needs, at
each precision, a coverage row that shows the dependence; and in the reason of a type Spark
accepts at some precisions only. This is `m9/PLAN.md` 2.5's lean.

**(c) Two census types, the precision in the cast table alone.** As (b), without the band: an
entry counts fused for `ntz_ns` from a coverage row at any one precision, and a cast pair
when the probe's lines of every precision fuse and one coverage row casts it at any precision.

**A fourth choice is not offered:** no new type, with precisions 7 to 9 as forms of `ntz` and
`ltz`, which is the closest reading of "as `DECIMAL` has". It fails on three facts. The
entries differ: `from_utc_timestamp` takes a nanosecond `TIMESTAMP_NTZ` and not the
microsecond one, and `months_between` takes the microsecond `TIMESTAMP` and no nanosecond
type (4.5), while an entry has one list of types.
Item 4 of `m9/PLAN.md` 1.2 counts "each nanosecond type's pairs" apart from items 1 and 3,
which count `ltz` and `ntz`. And the types exist only under a flag that is off in production,
while `ntz` and `ltz` always do.

### 4.2 What each choice gives

Every number of (b) and (c) is the prototype's, by the command of 6.5 named in the last
column, run on the copy. Every number of (a) follows from (b)'s by the arithmetic in the last
column, and (a)'s prototype prints the same. "Asked" are the entries that take a timestamp; a
cast entry is not asked, since the cast table is held to `Cast` by its own suite.

| | (a) six types | (b) two types, with the band | (c) two types, no band | how it is derived |
| :--- | ---: | ---: | ---: | :--- |
| census types added | 6 | 2 | 2 | 4.1 |
| census types in all | 20 | 16 | 16 | row 654's 14 (`VARKA-654.md` 4.10), plus the line above |
| entries that come into scope | 2 | 2 | 2 | `timestamp_nanos` and `unix_nanos`: command 6 |
| entries in scope | 200 | 200 | 200 | row 654's 198, plus 2: command 2 |
| entries asked of Spark | 66 | 66 | 66 | command 5, its first line; 4.5 lists them: 59, and the seven date functions |
| lines of the admission table | 396 | 132 | 132 | 66 entries times the new types: 66 x 6, 66 x 2 |
| pairs Spark rejects, each with its reason | 15 | 5 | 5 | command 5: 2 cells of `ntz_ns` and 3 of `ltz_ns`; (a) has each at three precisions, 5 x 3 |
| (entry, type) pairs admitted for each new type | 69 for each `ntz_ns<p>`, 68 for each `ltz_ns<p>` | `ntz_ns` 69, `ltz_ns` 68 | `ntz_ns` 69, `ltz_ns` 68 | command 2. 66 asked less 2 rejected is 64, less 3 is 63; the 5 cast entries admit both (4.5): 64 + 5 and 63 + 5 |
| (entry, type) pairs admitted over the new types | 411 | 137 | 137 | 69 + 68; (a): 3 x 69 + 3 x 68 |
| pairs the older types gain | 5 | 5 | 5 | `timestamp_nanos` over `byte`, `short`, `int`, `bigint` and `dec` (4.5) |
| all (entry, type) pairs | 1442 | 1168 | 1168 | row 654's 1026 with the date functions (12.2), plus the two lines above: command 2's summary |
| legal cast pairs added | 84 | 20 | 20 | command 3. (b): each new type with `date`, `time`, `ntz` and `ltz` both ways, 2 x 4 x 2 = 16, plus the 2 x 2 = 4 pairs among the two. (a): 6 x 4 x 2 = 48, plus the 6 x 6 = 36 among the six |
| of them, legal under LEGACY, under ANSI, under TRY | 84, 84, 84 | 20, 20, 20 | 20, 20, 20 | command 3, its second line: `Cast`'s cases are the same in `canCast` and `canAnsiCast` (4.6) |
| of them, identities the analyzer removes | 6 | 0 | 0 | (a): each of the six types to itself. (b): a type's own pair is a change of precision |
| of them, proven by the zone sweep | 42 | 10 | 10 | command 3, its fourth line. The pairs with one side an instant and the other a local time (4.6): between the two kinds 2, `ntz` with `ltz_ns` 2, `ltz` with `ntz_ns` 2, `date` with `ltz_ns` 2, `time` with `ltz_ns` 2. (a): 2 x 9 between the kinds, and 2 x 3 for each of the other four |
| all legal cast pairs | 213 | 149 | 149 | row 654's 129, plus the pairs added |
| lines the probe gains (one a legal cast of two forms) | 102 | 102 | 102 | command 4; 4.6 has the sum |
| what carries a band | nothing: a precision is a type | 1 entry, `timestampadd` (2 pairs), and 6 cast pairs | nothing | 4.4; command 8 |
| item 4 of 1.2, three quarters of each type's pairs | 52, 52, 52, 51, 51, 51: 309 pairs | 52 and 51: 103 pairs | 52 and 51: 103 pairs | three quarters of 69 is 51 and three quarters, rounded up to 52; of 68 it is 51 |
| item 4 of 1.2, two thirds of the cast pairs | 56 of 84 | 14 of 20 | 14 of 20 | 84 x 2 / 3 is 56; 20 x 2 / 3 is 13.3, rounded up |
| pairs whose kernel another pair of the same entry already is | 274 | 0 | 0 | (a)'s 411 less (b)'s 137: two of every three |
| cast pairs whose kernel another cast pair already is, but for one literal | 64 | 0 | 0 | (a)'s 84 less (b)'s 20 |

The probe is the same 102 lines in each choice, because the probe is over forms and Spark's
forms are six either way. The choices differ in how those lines group into pairs.

"About 20" cast pairs in `m9/PLAN.md` 1.2 and 2.4 is exactly 20 under (b) and (c).

**The asked entries by a command.** On the census after row 654 this prints 65: the 58 that
listed a timestamp before, and the seven date functions. `unix_nanos` is the 66th:

    awk -F'\t' '$4=="in" && (","$6",") ~ /,(ntz|ltz),/ {print $1}' sql/varka/plans/m9/census.tsv | grep -v -x -E 'cast|try_cast|boolean|tinyint|smallint|int|bigint|float|double|decimal|date|time|timestamp' | wc -l

After row 661's edits, `python3 dev/varka_census.py --asked` prints 66 (6.5, command 5).

**The seven date functions are inside every number above**, by the owner's decisions of 27
November 2026 (section 13, questions 3 and 8). `add_months`, `datediff`, `date_diff`,
`date_add`, `dateadd`, `date_sub` and `unix_date` take a `DATE`, and Spark casts a timestamp
to it (4.5). They are 7 pairs of each new type: 14 pairs under (b) and (c), 42 under (a).
Without them the numbers were 59 entries asked, 62 and 61 pairs a type, 123 pairs, and 47
and 46 for item 4.

### 4.3 What `--summary` prints

Under (b), the lines that differ from row 654's summary (`VARKA-654.md` 4.10, with its
correction for the date functions):

    in scope: 200 entries (173 classes)
      datetimeExpressions: 85 / 58
    in scope, by need: done 58, plumbing 33, semantics 99, ulp 10, hard 0
    (entry, type) pairs: 1168, fused today 585
      byte    existing   54 / 82
      short   existing   54 / 82
      int     existing   68 / 101
      bigint  existing   62 / 92
      dec     existing    4 / 75
      ntz_ns  new         0 / 69
      ltz_ns  new         0 / 68
      up to 18, an unscaled long     45 / 75
      above 18, a lane pair          35 / 75
    nanosecond entries and cast pairs whose answer depends on the precision, with a matching coverage row at each of 7, 8 and 9:
      ntz_ns                          0 / 1
      ltz_ns                          0 / 1
      cast pairs                      0 / 6
    cast pairs over the 16 types: 256 ordered, 149 legal
    legal under LEGACY: 149, under ANSI and TRY: 139
    by proof: exhaustion 68, SMT 50, zone 16, sampled 15

The line of the excluded names with the reason "nanosecond timestamps, precision 7 to 9: phase
2 of milestone 9, rows 661 and 675" is gone, and the line "by kind" changes with the kinds of
4.6. Under (c) the line that begins "nanosecond entries" and the three under it are absent,
and nothing else differs from (b). Under (a) these lines differ from (b):

    (entry, type) pairs: 1442, fused today 585
      ntz_ns7 new         0 / 69
      ntz_ns8 new         0 / 69
      ntz_ns9 new         0 / 69
      ltz_ns7 new         0 / 68
      ltz_ns8 new         0 / 68
      ltz_ns9 new         0 / 68
    cast pairs over the 20 types: 400 ordered, 213 legal
    legal under LEGACY: 213, under ANSI and TRY: 203
    by proof: SMT 80, exhaustion 70, zone 48, sampled 15

### 4.4 The chosen design: (b)

**The types.** `ntz_ns` is `TimestampNTZNanosType(p)` and `ltz_ns` is
`TimestampLTZNanosType(p)`, for every `p` from 7 to 9. Their registry ids come after
`TIMESTAMP`, so the census's order ends `..., dt, ltz, ntz_ns, ltz_ns`. Each name is six
characters, inside the summary's seven (fact 17). Each id has three forms, one a precision.
The registry's lookup from a `DataType` gives the same id for the three precisions of a kind,
as it gives `TIME` for every `TIME(p)`.

**The rule from Spark's six types to the two.** For an entry and a kind, Spark gives three
answers, one a precision. The census admits the type when all three are "accepted". When at
least one is not, the type is not among the entry's types, and the entry's reason says
`Spark rejects ntz_ns: <error class>`, or `Spark rejects ntz_ns at precision 7, 8: <error
class>` when only some precisions are rejected. That second form is what "a declined band"
means for a nanosecond type. No entry has it today (fact 6): every type check takes
`AnyTimestampNanoType`.

**The precision as a form.** The cast probe compiles every legal cast between two forms, so
`TIMESTAMP_NTZ(9)` to `TIMESTAMP_NTZ(7)` is a line of the pair `ntz_ns` to `ntz_ns`. A
nanosecond type's own pair is not an identity: like `time` to `time` it is "relabel or
truncation" (fact 15). The probe compiles; it runs no value.

**Where an answer depends on the precision.** An answer *depends on the precision* when two
columns that hold the same two words, declared at two precisions, can give different
answers. Three readings of Spark's code agree on where that is.

- Fact 7 lists every class of the datetime expressions that reads a precision. Six of its
  nine lines are out of the census or never reach a kernel: `CurrentTimestampNanos` and
  `LocalTimestampNanos` fold to literals; `DateFormatClass` (two lines), `GetTimestamp` and
  `ParseToTimestampBuilder` read or write a string. `MakeTimestampNTZNanos` and
  `MakeTimestampLTZNanos` are how a cast from `TIME` to a nanosecond type is evaluated.
  `TimestampAdd` is the entry `timestampadd`.
- Facts 22 and 23 list every caller of a helper that takes a precision, in every expression,
  analyzer and optimizer file. They are `Cast` (the casts), `percentiles` (an aggregate, out
  of scope), the classes of fact 7, and `ComputeCurrentTime`.
- The other entries were read one by one (4.5's table says what each reads).

So the answer depends on the precision in two places.

1. **`timestampadd` with the unit `NANOSECOND`.** The sum is floored to the type's step
   (`DateTimeUtils.timestampAddNanos`: "the resulting fraction is then floored to `p`").
   Adding 5 nanoseconds to a value of precision 7 gives the value back; at precision 9 it
   does not. With any other unit the second word is carried through, at every precision.
2. **Six cast pairs**, the ones with a nanosecond target and a source that can hold digits
   below the target's step: `time` to each of the two types, and each of the two types to
   each. A cast from `date`, `ntz` or `ltz` writes a second word of zero, which is on every
   step; a cast to another type reads no nanosecond precision (4.6).

**The band of the entry.** The census holds a dictionary `PRECISION`. For each entry whose
answer depends on the precision it has four things: the spellings of the entry's call in SQL,
the keyword its first argument must be for the answer to depend on the precision, the place
of its timestamp argument, and what reads the precision in Spark. Today it has one line:
`timestampadd`, spelled `TIMESTAMPADD`, `DATEADD` or `DATE_ADD`, which are the three names of
the parser's rule `#timestampadd` in `SqlBaseParser.g4`; the keyword `NANOSECOND`, the only
spelling of that unit in the rule's `datetimeUnit`; and the third argument.

A precision counts for the entry, over a nanosecond type, when a coverage row meets all of
this (`nanos_bands`):

1. Its SQL (the field `executable`), with string literals and comments blanked and in any
   case, holds a call of one of the three spellings, at any depth of nesting.
2. The call's first argument is the keyword `NANOSECOND`.
3. The call's timestamp argument is a bare column: one column's name, with or without
   brackets around it, and nothing more. Its declared type (the table's `columns`) is the
   nanosecond type at that precision.
4. The row has an operand of the entry's class that takes that type (the field `operands`,
   `TimestampAdd:ntz_ns`).

Everything else counts for nothing, on purpose. A timestamp argument that is a cast, a call
or a sum has a precision the census cannot know, so it is refused; row 675's rows put the
column there bare. The word `NANOSECOND` in a string or a comment is not code. A row that
adds nanoseconds to a column of precision 9 and hours to one of precision 7 counts 9 only.

The rule: a fused claim of a listed entry over a nanosecond type needs each of the
precisions 7, 8 and 9 counted. With fewer the claim must be the mark `^`, fused in part,
which does not count.

**The band of the casts.** A cast pair that reads its target's precision
(`reads_precision`) counts fused only when, beside every probe line fusing, each of the
precisions 7, 8 and 9 counts for it. A precision counts when a coverage row meets all of
this (`cast_bands`):

1. The row performs the pair's cast (the field `casts` holds `time>ntz_ns`, say).
2. Its SQL, with strings and comments blanked and in any case, holds a cast written
   `CAST(x AS T)`, `TRY_CAST(x AS T)` or `x::T`.
3. `T` is the pair's target type at that precision, `TIMESTAMP_NTZ(7)` say, and nothing more.
4. `x` is a bare column whose declared type is the pair's source type.

A cast of another source to that precision, a typed literal `TIMESTAMP_NTZ(8) '...'`, a cast
the analyzer adds, `to_timestamp_ntz`, and a cast of a call or a sum count for nothing. With
fewer than three precisions the pair is fused in part.

**What the band checks, and what it does not.** It checks that the dependent shape is applied
to a column of each precision, outside strings and comments: the `NANOSECOND` call with a
column of precision 7, of 8 and of 9 as its timestamp, and each of the six casts from a
column of its source to its target at 7, at 8 and at 9. A coverage row is a shape the real
compiler fuses, and it is also a query of the coverage differential, which runs it against
Spark's row path over the fixture (fact 26). So each of those shapes has a kernel, and the
kernel is run. The band does not check the values the row runs over: a row that adds a
literal 0, or only multiples of 100 nanoseconds, or casts only values already on the step,
counts and tests no floor. Values are the row's fixture, which the census cannot see. Rows
674 and 675 therefore take this as a rule of their sheets: the quantity of such a
`timestampadd` row is a column that holds 5, -5 and 1005 among its values, and the source
column of such a cast row holds a second word of 999 or a nanosecond of day that ends in 9.
Section 7 lists it.

The summary prints how many listed entries and how many of the six pairs have all three
precisions (4.3). Every other entry reads the two words alone, so a coverage row at one
precision shows all three, and no band is asked of it.

**Why not the registry's bands.** `dec`'s bands are also in the registry
(`Admission.ByPrecision`), because the words a value takes depend on the precision. A
nanosecond value takes the same words at every precision, so the registry needs no band, and
the two types are admitted as one.

**The upkeep of the list.** After each sync with apache/spark the commands of facts 7, 22
and 23 run. Fact 7 covers the datetime expressions; facts 22 and 23 cover every expression,
analyzer and optimizer file, by the helpers that take a precision. A class of fact 7 that is
not one of the nine, a helper of fact 22 that is not one of the sixteen, or a file or a count
of fact 23 that differs, is read. If it is an entry of the census whose answer depends on the
precision, its line joins `PRECISION` in the sync's pull request; if it is a cast,
`reads_precision` is read again.

### 4.5 The entries

**The entries asked.** An entry is asked of Spark when its types in the curated file hold
`ntz` or `ltz`, or name a nanosecond type, and it is not a cast entry. That makes 66
entries (`asked_entries`, 6.1): the 58 that listed a timestamp in the census before, the
seven date functions, and `unix_nanos`.

**The seven date functions are asked, by the owner's decisions.** They take a `DATE`, and
milestone 8's census lists no timestamp for them (fact 24). The owner counted them over the
nanosecond types, and then over `ntz` and `ltz` too, so that every timestamp type reads the
same way. Row 654 therefore writes `ntz,ltz` into their types (12.2, its rule 4), and they
are asked here for the same reason as `year` or `last_day`: they list a timestamp. The
one-off script of 6.3 has a guard for a census in which they list none: it would write the
group `NS` into their types, which asks Spark and admits nothing by itself. On the base of
this file it writes none.

| entry | class | what Spark does with a nanosecond timestamp | where it is seen |
| :--- | :--- | :--- | :--- |
| `date_add`, `dateadd` | `DateAdd` | accepted over both kinds: `DateTimeOperationsTypeCoercion` casts the start to `DATE` | fact 25: results in both golden files (SPARK-60543) |
| `date_sub` | `DateSub` | accepted over both kinds, by the same rule | fact 25 |
| `datediff`, `date_diff` | `DateDiff` | accepted over both kinds: a `DATE` parameter, and the implicit cast to `DATE` stands | fact 25, for `datediff`; `date_diff` is the same class |
| `add_months` | `AddMonths` | accepted over both kinds, by the same rule | fact 25 |
| `unix_date` | `UnixDate` | accepted over both kinds by the same rule (`UnixDate.inputTypes` is a `DATE`) | read from the code; the golden files have no call |

Each of the seven over a timestamp is the `DATE` kernel behind a cast to `DATE`: the analyzer
puts the cast under the node, so the entry's own node takes a `DATE`. A cell of this kind is
claimed as `year` over `ntz` is claimed today: by the mark `~` and a note that quotes the
coverage row, as in `last_day|...|date,ntz~|...|ntz through its date, the cast to DATE under
the node: `last_day(ntz)``. So a claim of `ntz_ns~` for `add_months` quotes a coverage row
`add_months(c, i)` over a column `c` of a nanosecond type.

**Which row owes the seven.** Row 673, by the rule of this section for an entry on no list:
not a cast, not an entry of every type, and its result is no timestamp, so it is "a field of a
timestamp". That is also where its nearest siblings are: `last_day`, `next_day` and `trunc`
take a `DATE` in the same way and are row 673's, and row 673 builds the kernel all of them
share, the date of the microsecond word.

**Every timestamp type of milestone 9's census then reads the same way.** The seven count
over `ntz`, `ltz`, `ntz_ns` and `ltz_ns`. Milestone 8's census does not count them over `ntz`
and stays so, since its files are frozen at its close; section 16 says where a reader of the
two summaries finds that.

| entries | count | Spark's answer over `ntz_ns` and `ltz_ns`, and where it is in the code | row that owes them |
| :--- | ---: | :--- | :--- |
| `coalesce if ifnull nullif nvl nvl2 when between isnull isnotnull equal_null in <=> = == > >= < <= greatest least hash xxhash64 in_set known_not_null try_eval` | 26 | accepted: they take any type that has an ordering or a hash, and both exist (`PhysicalTimestampNTZNanosType.ordering`; `HashExpression.computeHashWithTailRec`, its case `AnyTimestampNanoType`). They compare or hash both words, at any precision alike | 662: "the zone-free entries fuse" |
| `hour` | 1 | accepted: `HourExpressionBuilder` casts to the microsecond type (`NanosTimestampCast.castToMicros`) | 662, then 673: "a field over the microsecond word" |
| `extract` | 1 | accepted: its `SECOND` is `SecondWithFractionNanos`, a `DECIMAL(11, 9)` at every precision; its other fields are the ones below | 662, then 673: "`second` with its fraction" |
| `minute second` | 2 | accepted, as `hour` | 673 |
| `day dayofmonth dayofyear month quarter dayofweek weekday weekofyear year` | 9 | accepted: `GetDateField` takes a `DATE`, and `AnsiGetDateFieldOperationsTypeCoercion` casts a nanosecond timestamp to it | 673 |
| `add_months datediff date_diff date_add dateadd date_sub unix_date` | 7 | accepted: a `DATE` parameter, and a nanosecond timestamp is cast to it (the table above) | 673 |
| `last_day next_day trunc` | 3 | accepted: a `DATE` parameter, and the implicit cast of a nanosecond timestamp to `DATE` stands (`TypeCoercionHelper.isNanosNarrowing` is false for `DATE`) | 673 |
| `date_part datepart` | 2 | accepted, as `extract` | 673 |
| `time_bucket` | 1 | accepted: `TimeBucket.inputTypes`; the result has the origin's second word | 673 |
| `date_trunc` | 1 | accepted: `TruncTimestamp.inputTypes`; the result keeps the input's type | 675 |
| `from_utc_timestamp to_utc_timestamp` | 2 | accepted over both kinds: `UTCTimestamp.inputTypes`. The microsecond form takes `ltz` alone, so these two admit `ntz_ns` and not `ntz` | 675 |
| `convert_timezone` | 1 | `ntz_ns` accepted; `ltz_ns` rejected, `DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE` (fact 11) | 675 |
| `unix_seconds unix_millis unix_micros` | 3 | accepted: `TimestampToLongBase.inputTypes`; they read the microsecond word | 675 |
| `unix_nanos` | 1 | accepted, and nothing else is: `UnixNanos.inputTypes` is `AnyTimestampNanoType` | 675 |
| `ntz_plus_dt ntz_plus_ym datetime_sub` | 3 | accepted: `TimestampAddInterval`, `TimestampAddYMInterval`, and `BinaryArithmeticWithDatetimeResolver` for the subtraction of an interval; the second word is carried through | 675: "the sums that carry into the microsecond word" |
| `timestampadd` | 1 | accepted: `TimestampAdd.inputTypes`. The one entry with the band (4.4) | 675 |
| `months_between` | 1 | rejected over both, `DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE` (fact 10) | none: Spark's rejection is the reason |
| `ntz_minus_ntz` | 1 | rejected over both, `UNSUPPORTED_FEATURE.TIMESTAMP_NANOS_SUBTRACTION` (fact 9) | none |

So 64 of the 66 are accepted over `ntz_ns` and 63 over `ltz_ns`, and five (entry, type)
cells are rejected.

**The cast entries** are not asked. `cast` and `try_cast` admit every type with a legal pair,
and the aliases `date`, `time` and `timestamp` admit each source `legal()` allows: five
entries, each admitting both types. Row 674 owes them. With them `ntz_ns` has 69 pairs and
`ltz_ns` 68.

**The entries that come into scope**, two, both row 675's:

- `timestamp_nanos` (`NanosToTimestamp`): an integral or decimal count of nanoseconds to a
  `TIMESTAMP_LTZ(9)`. It takes no timestamp, so it is not asked; it admits `byte`, `short`,
  `int`, `bigint` and `dec`, which is where the older types gain 5 pairs, and its result is
  `ltz_ns`. Its result is always precision 9: a fact of its note, not a band.
- `unix_nanos` (`UnixNanos`): both types to a `DECIMAL(21, 0)`.

**The rows by their pairs**, each entry counted at the first row its cell names:

| row | entries | pairs under (b) and (c) | pairs under (a) |
| ---: | ---: | ---: | ---: |
| 662 | 28 | 56 | 168 |
| 673 | 24 | 48 | 144 |
| 674 | 5, and the cast pairs | 10, and 20 cast pairs | 30, and 84 cast pairs |
| 675 | 12 | 23 | 69 |
| all | 69 | 137 | 411 |

**An entry on no list, at the build.** Spark's surface and milestone 8's census both move, so
row 661 may find an accepted entry that the table above does not name. The one-off script of
6.3 gives it the row of its kind and prints the line; the builder copies the line into the
pull request. A cast entry goes to 674; an entry whose types are `ALL` to 662; any other entry
whose result is a timestamp to 675; any other to 673.

### 4.6 The cast table

**Legality**, from `Cast.canCast` and `Cast.canAnsiCast`, whose cases for these types are the
same lines in both; `canTryCast` is `canAnsiCast` for them. So every pair below is legal in
the three modes.

| pair | the case in `Cast` | pairs under (b) |
| :--- | :--- | ---: |
| a nanosecond type to itself or to the other, at any two precisions | `(_: AnyTimestampNanoType, _: AnyTimestampNanoType)` | 4 |
| `ntz` or `ltz` to a nanosecond type | `(t, _: AnyTimestampNanoType) if AnyTimestampType.acceptsType(t)` | 4 |
| a nanosecond type to `ntz` or `ltz` | `(_: AnyTimestampNanoType, t) if AnyTimestampType.acceptsType(t)` | 4 |
| `date` to and from each | `(DateType, _: TimestampLTZNanosType)` and its three siblings | 4 |
| `time` to and from each | `(_: TimeType, _: TimestampNTZNanosType)` and its three siblings | 4 |
| a number, a `BOOLEAN` or an interval, either way | no case (fact 5) | 0 |

**Kind and semantics**, from `Cast.castToTimestampNTZNanos`, `Cast.castToTimestampLTZNanos`
and the nanosecond cases of `castToTimestamp`, `castToTimestampNTZ`, `castToDate` and
`castToTime`. "Zone" marks a pair that reads the session time zone, as `Cast.needsTimeZone`
has it; the one `needsTimeZone` case left unmarked is `time` to `ntz_ns`, whose date is
`CURRENT_DATE`, a literal after `ComputeCurrentTime`, as for `time` to `ntz` today.

| from | to | kind | what it does | zone | reads the target's precision |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `date` | `ntz_ns` | scale up | days times micros per day; second word 0 | | no |
| `date` | `ltz_ns` | local to instant | midnight in the session zone; second word 0 | yes | no |
| `ntz` | `ntz_ns` | widening | the microseconds; second word 0 | | no |
| `ntz` | `ltz_ns` | local to instant | `convertTz`; second word 0 | yes | no |
| `ltz` | `ntz_ns` | instant to local | `convertTz`; second word 0 | yes | no |
| `ltz` | `ltz_ns` | widening | the microseconds; second word 0 | | no |
| `time` | `ntz_ns` | session date | `CURRENT_DATE` and the time; the nanoseconds floored to the step | | yes |
| `time` | `ltz_ns` | session date | the same, then local to instant | yes | yes |
| `ntz_ns` | `date` | floor division | the microsecond word by micros per day | | no |
| `ntz_ns` | `ntz` | floor division | the microsecond word alone | | no |
| `ntz_ns` | `time` | floor modulo | the time of day in nanoseconds, truncated to `TIME`'s precision | | no |
| `ntz_ns` | `ltz` | local to instant | `convertTz` of the microsecond word; second word dropped | yes | no |
| `ntz_ns` | `ntz_ns` | relabel or truncation | a wider precision keeps the value; a narrower floors the second word | | yes |
| `ntz_ns` | `ltz_ns` | local to instant | `convertTz` of the microsecond word; second word floored | yes | yes |
| `ltz_ns` | `date` | instant to local | the local date of the microsecond word | yes | no |
| `ltz_ns` | `ntz` | instant to local | `convertTz` of the microsecond word; second word dropped | yes | no |
| `ltz_ns` | `time` | instant to local | the local time of day in nanoseconds | yes | no |
| `ltz_ns` | `ltz` | floor division | the microsecond word alone | | no |
| `ltz_ns` | `ntz_ns` | instant to local | `convertTz` of the microsecond word; second word floored | yes | yes |
| `ltz_ns` | `ltz_ns` | relabel or truncation | as `ntz_ns` to itself | | yes |

By kind: instant to local 5, local to instant 4, floor division 3, widening 2, session date
2, relabel or truncation 2, scale up 1, floor modulo 1. Six pairs read the target's
precision: one kernel each, with the step (1, 10 or 100) a literal.

**Proof.** A pair that reads the zone is proven by the zone sweep, as row 654's six are
(`ZONE_PROOF`): 10 pairs. `date` to `ntz_ns` is exhaustion (a 32-bit source). The other 9 are
integer rules over 64-bit words: SMT.

**Rows.** Every pair is row 674's. The two casts to the microseconds of the same kind,
`ntz_ns` to `ntz` and `ltz_ns` to `ltz`, are row 662's first ("a cast to microseconds"), so
their cells read `662,674`.

**The probe** gains 102 lines: 6 within each type, 9 each way between the two, 9 each way
between each type and `time`'s three forms, and 3 each way between each type and each of
`date`, `ntz` and `ltz`. That is 2 x (6 + 18 + 18) + 18. Each reads `declined` until its row
fuses it. For the six pairs that read the target's precision the probe is not enough to
count the pair fused: 4.4's band asks a coverage row at each precision.

**The suites' record.** 36 lines of `casts_suites.tsv` cast to or from one of the six forms
(fact 18). `VarkaSuiteRecord.census` reads the registry, so their `from` or `to` changes from
`-` to `ntz_ns` or `ltz_ns` when the registry has the names, and the file is sorted again.
No verdict moves.

### 4.7 The two layouts of row 662, and the library's two shapes

Row 662 builds two layouts (`m9/PLAN.md` 2.5): two words, "the cache's own form", and "One
long at the column's precision". The library has both shapes first, by the owner's decision
of 27 November 2026 (`VARKA-679.md` 4.1): the two words are "the contract and the reference
over the whole range", and one long of nanoseconds is "an arm of each form over the years
1677 to 2262, a measured fast path".

**The census does not count a layout as a type, and does not see the layouts at all.**

1. A census type is a Spark type, which a user writes in a schema. A layout is how a kernel
   holds the value. No `DataType` says which layout a column has: an emit option and a
   batch's range choose it.
2. The layouts are an option's arms (`m9/PLAN.md` row 662: "both layouts are built and
   measured on row 655's classes, the slower kept as an option arm"). The option matrix
   already runs every coverage row under each arm of every option, and
   `sql/varka/emitted_bytes.json` pins each. A census type a layout would count each of
   Spark's pairs twice, and item 4 of 1.2 would then depend on an option's arms.
3. `dec` shows the difference. Its one word and its two words are chosen by the precision in
   the type, which the user wrote. So they are bands of the type, and the registry holds them
   (`Admission.ByPrecision`). A nanosecond layout is chosen by neither the type nor the
   precision.
4. The one long does not hold the type: Spark's range is the years 1 to 9999, and a long of
   nanoseconds is 1677 to 2262.

**The owner agreed on 27 November 2026** (section 13, question 6): a layout is no census
type, and a fused cell holds over the type's whole range.

**What follows for a fused cell.** A cell of `ntz_ns` or `ltz_ns` is fused when the entry
fuses under the default options and answers over the type's whole range. Row 662 keeps that
true whatever its measurement says: the default arm is the two words, or it is the one long
with the two-word kernel behind it for a batch outside the range. A cell fused in the
one-long arm alone would be fused for six centuries of Spark's hundred. This is a seam with
row 662, which has no plan yet (section 11).

*28 November 2026:* the owner changed this for the nanosecond timestamps (`VARKA-662.md` section
15, answer 2; the kernels are row 699's). The one long may be built where it is the fastest, and
a batch that holds a value outside 1677 to 2262 then goes to the row path: no two-word kernel
has to stand behind it. So a cell's default arm may be the one long, and such a cell is fused
for the years 1677 to 2262 and answers on the row path outside them. The rule that a fused cell
holds over the type's whole range no longer binds these two types; the census rule above stands,
a layout is no census type. How the census marks a cell whose default arm is the one long is for
row 699's plan to say.

**Where the precision and the one long meet.** In the two words a precision changes no word.
In "One long at the column's precision" the long's unit is 10 to the power 9 minus `p`
nanoseconds, so the precision is a literal of the load and of the store. The library holds
one long at precision 9 only, and leaves 7 and 8 to row 662's measurement (`VARKA-679.md`
10.4, question 13). Neither changes this design: under (b) a precision is a form, and a
kernel that differs by one literal across forms is one entry and one pair. It would be three
pairs under (a) in either layout.

### 4.8 Edge cases and declines

The row emits no kernel, so null rows, the ends of a long, an empty batch and the vector
widths do not arise. Nothing new declines in the compiler. Three edges are the census's own:

- **A nanosecond column while the flag is off** cannot exist: Spark rejects the type
  (`TypeUtils`, `timestampNanosTypesNotEnabledError`). The census counts the types as Spark
  admits them with the flag on, which G4 says: "Admitted while Spark's
  `spark.sql.timestampNanosTypes.enabled` is on".
- **`TIMESTAMP_NTZ(6)`** is the microsecond type (`TimestampFamily.fractionalPrecision` is 6
  for it). It stays `ntz`.
- **A cast between two precisions that the analyzer adds**, as in `ntz7 = ntz9`, is the pair
  `ntz_ns` to `ntz_ns`, a relabel when the target is wider. It is row 674's like any other.

The script's new refusals are five, each an error of `--check`. `<remedy>` stands for this
text:

    run python3 dev/varka_census.py --asked, then VARKA_NANOS_ADMISSION_REGEN=true build/sbt 'catalyst/testOnly *VarkaNanosAdmissionSuite'


    census: <entry>: no line in sql/varka/plans/m9/nanos_admission.tsv says whether Spark takes a <type>; <remedy>
    census: <entry>: no answer yet in sql/varka/plans/m9/nanos_admission.tsv says whether Spark takes a <type>; <remedy>
    census: sql/varka/plans/m9/nanos_admission.tsv: a line for <entry> over <type>, which no entry asks
    census: sql/varka/plans/m9/nanos_admission.tsv: its lines are not in the row order of census.tsv; <remedy>
    census: <entry>: its answer over <type> depends on the precision, and no coverage row holds its <keyword> call over a column of precision <list>: mark it <type>^

A cast pair of the band that lacks a precision raises no error: it reads fused in part.

### 4.9 What is out of this row

| excluded | the row that holds it |
| :--- | :--- |
| any code: the registry's ids, the script, the admission suite, the files | 661 |
| the suite that asks Spark's type check and writes the admission table | 661 |
| the registry's storage of the two types, the Arrow struct in and out, the conformance suites, the coverage columns, the choice of the default layout | 662 |
| any fused cell of the two types | 662, 673 to 675 |
| `timestampdiff` and `ts - ts` over nanoseconds, which Spark rejects | none (`m9/PLAN.md` 1.3) |
| the seven date functions over `ntz` and `ltz`, in the census | 654, by the edits of 12.2; their fused cells: 672 for `ntz`, 668 for `ltz` |
| one long at precisions 7 and 8 in the library | `VARKA-679.md` question 13, row 662 |
| the nanosecond baseline classes | 655 |

## 5. The decision, and what each choice costs the rows after it

The row has no code part. In place of parts it has one decision, and this section says what
each choice costs. Section 6 is the sheet of the chosen one, (b), which row 661 takes.

### 5.1 The decision

| choice | census types | (entry, type) pairs | legal cast pairs | what the census checks about the precision | pairs that repeat another pair's kernel |
| :--- | :--- | ---: | ---: | :--- | ---: |
| (a) | 6: `ntz_ns7` `ntz_ns8` `ntz_ns9` `ltz_ns7` `ltz_ns8` `ltz_ns9` | 411 | 84 | every cell at its own precision: a coverage row over a column of each of the six types, for every entry | 274, and 64 cast pairs |
| **(b), chosen** | 2: `ntz_ns` `ltz_ns` | 137 | 20 | for `timestampadd`, a coverage row that holds its call with the `NANOSECOND` unit and a bare column of each precision as the timestamp; for each of the six cast pairs, a coverage row that casts a bare column of the source to the target at each precision; strings and comments do not count; for the rest, one row at any precision | 0 |
| (c) | 2: `ntz_ns` `ltz_ns` | 137 | 20 | for every entry and cast pair, one coverage row at any precision; for a cast pair, also that the probe's line of each precision compiles | 0 |

None of the three checks the values a coverage row runs over (4.4).

### 5.2 Near-identical kernels

Under (a) every one of the four nanosecond rows carries them. Of an entry's three pairs of
one kind, two repeat the kernel of the third: the entry reads the two words, and the two
words do not show the precision (4.5). Counted from 4.5's table as (a)'s pairs less (b)'s:

| row | pairs under (a) | of them, a kernel another pair already is |
| ---: | ---: | ---: |
| 662 | 168 | 112 |
| 673 | 144 | 96 |
| 674 | 30, and 84 cast pairs | 20, and 64 cast pairs |
| 675 | 69 | 46 |
| all | 411, and 84 cast pairs | 274, and 64 cast pairs |

Under (b) and (c) no pair repeats another. The only kernels that differ by precision are the
six cast pairs of 4.6 and `timestampadd` with the `NANOSECOND` unit, each one kernel with
its step a literal.

### 5.3 What each choice costs rows 661, 662 and 673 to 675

The three columns of the choices are counts, and the last column says how each is derived.
"Hand-written" is what counts toward the cap of 1000 lines a pull request. **A "coverage row"
count assumes one coverage row for each fused pair.** That is the most a pair needs: a fused
claim needs a coverage row of the entry's class over the type (fact 19), and one row can
serve two entries, so fewer rows can do.

| row | what scales with the choice | (a) | (b) | (c) | how it is derived |
| ---: | :--- | ---: | ---: | ---: | :--- |
| 661 | registry ids, and cases in the four switches that have one case an id (`VarkaTypes.entry`, `VarkaFuzzTypes`, `VarkaLaneArrow`, `VarkaConditionCompiler`) | 6, 24 | 2, 8 | 2, 8 | `git grep -l 'case TIMESTAMP ->' -- sql/catalyst/src sql/core/src` prints the four files; ids times 4 |
| 661 | lines the census script gains, hand-written | 311 | 551 | 305 | on each copy, `diff <the script before> <the script after> \| grep -c '^>'`; at the build, the first number of `git diff --numstat origin/master -- dev/varka_census.py`. (b) less (c) is the band: 246 lines |
| 661 | lines of `casts.tsv`, each with a kind, a proof and rows | 84 | 20 | 20 | 4.2 |
| 661 | lines of the admission table, generated | 396 | 132 | 132 | 4.2 |
| 661 | cast tests in `VarkaTypeConformance`, one a census type, each run by every conformance suite | 6 | 2 | 2 | one a census type (`VARKA-654.md` fact 10) |
| 661 | columns of each of the three cast matrices in `docs/sql-varka.md` | 20 | 16 | 16 | the census types of 4.2 |
| 662 | conformance suites, one a census type, about 80 lines each | 6 | 2 | 2 | fact 20: one a census type; `wc -l` of `VarkaTimestampNtzConformanceSuite.java` prints 79 |
| 662 | first coverage rows of a type: a load, a comparison, a choice, an output | 24 | 8 | 8 | 4 a type (`sql/varka/ADDING_A_TYPE.md`, step 11) |
| 662 | cells to claim, and coverage rows | 168 | 56 | 56 | 4.5's table of rows |
| 673 | cells to claim, and coverage rows | 144 | 48 | 48 | 4.5's table of rows |
| 674 | cells; cast pairs, each with its line and its proof kind | 30; 84 | 10; 20 | 10; 20 | 4.5; 4.2 |
| 674 | coverage rows for the cast pairs, if all fuse | 78 | 32 | 20 | (a): 84 less the 6 identities, which need none. (c): one a pair. (b): the six pairs of the band need three each, 14 + 6 x 3 |
| 675 | cells to claim; coverage rows | 69; 69 | 23; 27 | 23; 23 | 4.5; (b) adds two rows a type for `timestampadd` with `NANOSECOND` at the other two precisions |
| all | coverage rows that item 4's three quarters of the pairs need, beside today's 868 | 309 | 107 | 103 | 4.2's 309 and 103; (b) adds the 4 rows of `timestampadd`; 868 is fact 20 |

Three things follow that the table does not count.

- **The fan-out's task plans.** A fan-out part is "two to four sibling entries", and a task
  plan is generated "For one entry and one type" (`m9/PLAN.md` section 3 and 2.7). Rows 673
  and 674 have 29 entries (24 and 5, 4.5): 58 (entry, type) tasks under (b) and (c), 174
  under (a), where 116 are a sibling's kernel with another type name.
- **Every coverage row is compiled by every probe of the width audit**, whose cost is row
  695's subject. The nanosecond pairs alone add 309 rows to 868 under (a), 107 under (b) and
  103 under (c), before the cast pairs.
- **The tests that tell three precisions apart** exist under each choice: the probe's 102
  form lines, and the conformance suite's fixture, which holds the forms of its type. So (b)
  loses no test that (a) has. What (a) adds is three census cells where one kernel is.

### 5.4 The recommendation, and the choice

**The owner chose (b) on 27 November 2026.** The recommendation was (b), for these reasons,
each a fact above:

1. **(b) and (c) over (a).** The value is the same two words at every precision (fact 2). Of
   the 64 entries that are not casts, 63 read the words alone. Six types make 274 pairs and
   64 cast pairs out of kernels that already exist.
2. **No new idea in the cast table.** The census already holds a precision in a type this
   way: `time` is one type with three forms, and its own pair is a real cast (fact 15).
3. **(b) over (c): what each really checks.** Under (c), `timestampadd` counts fused from one
   coverage row of any unit at any precision, and a cast pair from the probe's compiled lines
   and one coverage row at any precision. So under (c) the census can call `timestampadd`
   fused when no kernel of the `NANOSECOND` unit was ever run over a column of precision 7 or
   8, the only place where the floor does anything. Under (b) the claim needs a coverage row
   whose `NANOSECOND` call has a column of precision 7 as its timestamp, one with 8 and one
   with 9, and each of the six casts needs a row that casts a column of its source to its
   target at each precision; the coverage differential runs each of those rows against Spark.
   What (b) cannot check is the row's values (4.4): the band proves that the dependent shape
   has a kernel and that the kernel is run at each precision, not that the run meets a value
   off the step. Rows 674 and 675 carry that by their fixture rules (section 7).
4. **The cost of (b) over (c)** is 246 lines of the census script, most of them doctests and
   the reading of a row's SQL, 4 coverage rows for `timestampadd` and at most 12 for the six
   casts.
5. **Spark's surface moves** (`m9/PLAN.md` row 661: "Spark's set of functions over them
   moved in six commits since May"). A function that starts to read the precision joins one
   dictionary under (b), with the shape of its call. Under (c) nothing records it.

Against (b): its band is one entry and six cast pairs today, so it is machinery for seven
lines of the census, and it checks shapes, not values. If the owner prefers the least
machinery, (c) is (b) without edits 30 to 37 of 6.1, and the rest of this file stands. (a)
would change the names and most of section 6; the prototype of (a) exists and passes the
same checks, and row 661's plan would be written from it.

## 6. What row 661 builds from the chosen design

This section is not a part of row 660: row 660 builds nothing. It is the design's half of row
661's sheet, the half that follows from the choice: the names, the build order, the script,
the admission table and the command that writes its lines, the curated file, and the commands
with what they print. Row 661's own plan takes it as it is, adds the Java that 6.6 lists,
checks every old text again on the master of its day, and owns the size and the tests of its
pull requests.

**The base.** Row 654's part b is merged, so that `grep -c '^CURRENT = 9' dev/varka_census.py`
prints `1` and `grep -P "^TIMESTAMP\t" sql/varka/types.tsv | cut -f3` prints `ltz`. Every old
text of 6.1 was found exactly once in the script of row 654's planner after its part b. Where
the build does not find an old text exactly once, the step ends there, and the builder reports
the old text and how many times it was found.

**The premises**, each run on that base:

    grep -c '^    9: Milestone(9, M8_TYPES + \["ltz"\], \["ltz"\], None),$' dev/varka_census.py   # 1
    grep -c '^NANOS = ' dev/varka_census.py                             # 1
    grep -c '": NANOS,$' dev/varka_census.py                            # 2
    grep -c '_NANOS' sql/varka/types.tsv                                # 0
    grep -c '^# (dec is DECIMAL up to precision 38' sql/varka/plans/m9/census_curated.txt   # 1
    python3 dev/varka_census.py --check; echo $?                        # 0

### 6.0 The order, and where each file comes from

*27 November 2026, from `VARKA-661.md`:* row 661 builds this section in four pull requests,
and its sheets are the ones to follow where they differ from the text below. The order
becomes: the registry and the cast matrix (steps 1 to 3 in part, 8 to 10); the admission
table (steps 5 and 6); the census's cells (steps 4 and 7); the band (edits 30 to 37). The
one-off script of 6.3 gets a third step, `casts`, for the cast entries. A difference
between the two ANSI settings does not fail the suite of 6.2. By the owner's answer of
27 November 2026 the cell then reads `ANSI:<condition>` or `LEGACY:<condition>`, after the
setting that rejects; the type is admitted where Spark takes it under at least one setting
at every precision; the census's reason column says under which setting alone; and a
fused claim over such a cell owes a kernel that declines under the other setting and a
test of that decline (`VARKA-661.md` 4.3). The script gets nine edits of row 661's own
beside the 38 of 6.1. The suite asks `timestampadd` with the unit `HOUR`, since Spark
rejects `NANOSECOND` over a microsecond timestamp and the suite's control asks every call
over one. And the first run of step 8 fails three tests, not two: the planted legality
test reads the same file. `unix_date` is not asked (the lead, 27 November 2026, on a run of
Spark's analyzer on master `1924c7a5afb`): Spark rejects it over the microsecond and the
nanosecond timestamps alike, with `DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE`, where 4.5 read
it as accepted. So the date functions of 4.5, of 6.3 and of section 16 are six, row 661's
tool takes the name out of 6.3's `DATE_FUNCTIONS`, and each count of this file that holds
the entry is smaller: 68 and 67 pairs a type for 69 and 68, 135 for 137, 1164 pairs for
1168, and 1024 after row 654 for 1026 (`VARKA-661.md` 4.3 and 4.5).

Five files are written only by a JVM suite, and the census and those suites need each other's
output. The order below breaks each circle. It was run on the prototype in this order, with a
stand-in for each suite (section 3); the state after each step is the prototype's.

| step | what | writes | the state after it |
| ---: | :--- | :--- | :--- |
| 1 | The Java of 6.6: the two ids of `VarkaTypeId`, their two cases in `VarkaTypes.entry` and in the three other switches, `VarkaTypes.idOf`, the two cast tests of `VarkaTypeConformance`, the admission suite `VarkaNanosAdmissionSuite` with its table of calls (6.2), the pinned test | Java sources | `build/sbt catalyst/Test/compile` passes: a switch with no case for a new id does not compile |
| 2 | `VARKA_TYPES_REGEN=true build/sbt 'catalyst/testOnly *VarkaTypesSuite'` | the two lines of `sql/varka/types.tsv` | `grep -c '_NANOS' sql/varka/types.tsv` prints `2`. Without this step the edited script's doctests fail, and `--asked` stops with `census: sql/varka/types.tsv has no ntz_ns or no ltz_ns: regenerate it first` |
| 3 | The 38 edits of 6.1 | `dev/varka_census.py` | `python3 dev/varka_census.py --self-test; echo $?` prints `0`. `--check` fails until step 10 |
| 4 | `python3 target/varka-661/ns_curated.py new sql/varka/plans/m9/census_curated.txt` (6.3) | in the curated file: the two entries that come into scope and two header lines | it prints `2 entries added, 2 header lines added, 0 date functions now ask the two types` |
| 5 | `python3 dev/varka_census.py --asked` | `sql/varka/plans/m9/nanos_admission.tsv`: its header and a line for each asked entry and type, every answer `?` | it prints `sql/varka/plans/m9/nanos_admission.tsv: 66 entries, 132 lines, 132 to ask`. `--check` now prints one error a line of the table, each `<entry>: no answer yet in ...` |
| 6 | `VARKA_NANOS_ADMISSION_REGEN=true build/sbt 'catalyst/testOnly *VarkaNanosAdmissionSuite'` (6.2) | the answers of `nanos_admission.tsv`, in place | no `?` is left: `grep -c -P '\t\?\t' sql/varka/plans/m9/nanos_admission.tsv` prints `0` |
| 7 | `python3 target/varka-661/ns_curated.py rows sql/varka/plans/m9/census_curated.txt sql/varka/plans/m9/nanos_admission.tsv` (6.3) | the row that owes each cell, in the curated file | it prints `68 entries edited, ...` (6.3). `--check` now prints one error, `census: cast date to ntz_ns is legal under legacy and the probe has no form of it` |
| 8 | `VARKA_CASTS_REGEN=true build/sbt 'catalyst/testOnly *VarkaCastMatrixSuite'` | the probe's 102 lines in `sql/varka/plans/m9/casts_fused.tsv` | the suite writes the file and fails its legality test and the pinned test, because `casts.tsv` has no nanosecond pair yet: the same planned failure as `VARKA-654.md` 5.2, step 7. Any other failure ends the step |
| 9 | `python3 dev/varka_census.py > /dev/null` | `sql/varka/plans/m9/census.tsv`, `casts.tsv`, and the support table of `docs/sql-varka.md` | it exits 0 |
| 10 | `dev/varka_regen_generated.sh --all` | every other generated file; among them `sql/varka/plans/m9/casts_suites.tsv`, whose 36 lines get the two names from Spark's three cast suites under Varka in sql/core | it exits 0, and `python3 dev/varka_census.py --check; echo $?` prints `0` |
| 11 | The commands of 6.5 | nothing | each prints what 6.5 says, or the day's number |

Every `build/sbt` above runs under the lock and the environment of
`sql/varka/plans/m8/BUILD_SHEETS.md`, as row 654's sheet has them. Nothing of the order needs
a file of this row's scratch: the table's lines are `--asked`'s, its answers the suite's, and
every other file a suite's or the script's.

**How the suite and the census meet.** The census decides which entries are asked
(`asked_entries`: the curated file and the registry, no other input), and `--asked` writes
them into the table as its lines. The suite never computes that set: it reads the table,
asks Spark for every line it finds, and writes the answers back into the same lines. So the
table's keys are the census's and its cells are the suite's. When a later row or a sync adds
an asked entry, `--check` fails with `no line in ...` and its remedy: `--asked` adds the
line with `?`, keeping every answer the table has, and the suite fills it.

### 6.1 The script's edits

Thirty-eight edits of `dev/varka_census.py`, in this order: 37 by their text, and one by a
rule. The text below was generated from the edits that ran on the prototype. Every block is
indented eight columns for the page: remove eight, and what is left is the file's own text,
its indentation included. A block's first and last lines are whole lines of the file. The new
lines are wrapped within 100 columns. After the edits run `ruff format dev/varka_census.py`,
as row 654's sheet does, and commit what it leaves: this row could not run it.

1. The two names, immediately before the milestones' table. Old:

        MILESTONES = {

   New:

        # The nanosecond timestamps, TIMESTAMP_NTZ(p) and TIMESTAMP_LTZ(p) with p from 7 to 9
        # (m9/VARKA-660.md): one census type a kind, the precision a form of it, as TIME's is.
        NS = ["ntz_ns", "ltz_ns"]
        # The types that hold an instant; DATE, TIME and the NTZ timestamps hold a local time.
        LTZ_KIND = {"ltz", "ltz_ns"}
        MILESTONES = {

2. The group `NS`, in `GROUPS`. Old:

            "IVL": ["ym", "dt"],

   New:

            "IVL": ["ym", "dt"],
            "NS": NS,

3. Milestone 9's entry. The comment above it is edit 38's. Old:

            9: Milestone(9, M8_TYPES + ["ltz"], ["ltz"], None),

   New:

            9: Milestone(9, M8_TYPES + ["ltz"] + NS, ["ltz"] + NS, None),

4. The reason `NANOS` goes: its two names come into scope. Old:

        NANOS = "nanosecond timestamps, precision 7 to 9: phase 2 of milestone 9, rows 661 and 675"

   New: nothing. The old text is deleted with its line.

5. `NAME_EXCLUSIONS`, the line of `timestamp_nanos`. Old:

            "timestamp_nanos": NANOS,

   New: nothing. The old text is deleted with its line.

6. `NAME_EXCLUSIONS`, the line of `unix_nanos`. Old:

            "unix_nanos": NANOS,

   New: nothing. The old text is deleted with its line.

7. `legal`: the nanosecond branch, before the `ltz` branch. Old:

            num = NUMERIC

   New:

            num = NUMERIC
            if src in NS or dst in NS:
                other = dst if src in NS else src
                # A nanosecond timestamp casts to and from DATE, TIME and the four timestamps, in every
                # mode alike; to no number, BOOLEAN or interval.
                ok = other in NS + ["date", "ntz", "time", "ltz"]
                return ok, ok

8. `cast_kind`, inside its first block (`if src == dst:`): a nanosecond type's own pair. Old:

                if src == "dec":

   New:

                if src in NS:
                    return (
                        "relabel or truncation",
                        "a wider precision keeps the nanoseconds; a narrower one floors them to its step",
                    )
                if src == "dec":

9. `cast_kind`: the call, after the first block and before the `ltz` call. Old:

                return "identity", ""
            if "ltz" in (src, dst):

   New:

                return "identity", ""
            if src in NS or dst in NS:
                return ns_cast_kind(src, dst)
            if "ltz" in (src, dst):

10. `ns_cast_kind`, new, immediately before `def ltz_cast_kind(`. Old:

        def ltz_cast_kind(src, dst):

   New:

        def ns_cast_kind(src, dst):
            """cast_kind for a pair of two types with a nanosecond timestamp on one side or on both, from
            Cast.castToTimestampNTZNanos, castToTimestampLTZNanos and the nanosecond cases of
            castToTimestamp, castToTimestampNTZ, castToDate and castToTime. A value is two words: the
            microseconds, and the nanoseconds within the microsecond, 0 to 999. Only a cast to a
            nanosecond type reads a precision, the target's.

            >>> [ns_cast_kind(*p)[0] for p in (("ntz", NS[0]), (NS[-1], "ltz"), (NS[0], NS[-1]))]
            ['widening', 'floor division', 'local to instant']
            >>> cast_kind("date", NS[-1])[0], cast_kind(NS[0], "time")[0], cast_kind("time", NS[0])[0]
            ('local to instant', 'floor modulo', 'session date')
            """
            zoned = (src in LTZ_KIND) != (dst in LTZ_KIND)
            if src == "time":
                return (
                    "session date",
                    "the date comes from CURRENT_DATE in the session zone, a literal after"
                    " ComputeCurrentTime; the nanoseconds of the day are floored to the target precision"
                    + ("; then local to instant" if zoned else ""),
                )
            if dst == "time":
                if zoned:
                    return (
                        "instant to local",
                        "the local time of day in the session zone, in nanoseconds, truncated to the"
                        " precision",
                    )
                return (
                    "floor modulo",
                    "floorMod of the microsecond word by micros per day, to nanos, plus the second word;"
                    " truncated to the precision",
                )
            if src == "date":
                if zoned:
                    return (
                        "local to instant",
                        "midnight of the day in the session zone, with a second word of zero; a midnight"
                        " inside a gap takes the gap's end (daysToMicros)",
                    )
                return (
                    "scale up",
                    "days times micros per day, with a second word of zero; the overflow is DATE to"
                    " TIMESTAMP_NTZ's",
                )
            if dst == "date":
                if zoned:
                    return (
                        "instant to local",
                        "the local date of the microsecond word in the session zone (microsToDays)",
                    )
                return "floor division", "floorDiv of the microsecond word by micros per day, exact"
            if zoned:
                way = "local to instant" if dst in LTZ_KIND else "instant to local"
                if src in NS and dst in NS:
                    words = "the second word floored to the target precision"
                else:
                    words = "a second word of zero" if dst in NS else "the second word dropped"
                return way, "the microsecond word converted in the session zone (convertTz); " + words
            if dst in NS:
                return "widening", "the microseconds, with a second word of zero: exact"
            return (
                "floor division",
                "the microsecond word alone: the floor of the nanoseconds by a thousand, exact in every"
                " mode",
            )


        def ltz_cast_kind(src, dst):

11. `cast_proof`: the pairs that read the session zone. Old:

            if "ltz" in (src, dst) and {src, dst} & {"date", "ntz", "time"}:
                return ZONE_PROOF

   New:

            if (src in LTZ_KIND) != (dst in LTZ_KIND) and {src, dst} & {"date", "ntz", "time", "ntz_ns"}:
                return ZONE_PROOF

12. `cast_proof`: the 64-bit sources. Old:

            if src in ("bigint", "dt", "time", "ntz", "ltz", "dec") and dst not in ("float", "double"):

   New:

            if src in ("bigint", "dt", "time", "ntz", "ltz", "dec", *NS) and dst not in ("float", "double"):

13. `cast_rows`: the rows that owe the pairs, immediately after its first line. Old:

            lanes = {src, dst}

   New:

            lanes = {src, dst}
            # The nanosecond types' pairs are row 674's (m9/PLAN.md 3); the cast to the microseconds of
            # the same kind is row 662's first kernel over the two words.
            if lanes & set(NS):
                return "662,674" if (src, dst) in (("ntz_ns", "ntz"), ("ltz_ns", "ltz")) else "674"

14. `column_type`: a coverage column of a nanosecond type, with its precision as the band. Old:

            if d in SIMPLE_COLUMN_TYPES:
                return SIMPLE_COLUMN_TYPES[d], None

   New:

            if d in SIMPLE_COLUMN_TYPES:
                return SIMPLE_COLUMN_TYPES[d], None
            m = re.fullmatch(r"timestamp_(ntz|ltz)\(([789])\)", d)
            if m:
                return m.group(1) + "_ns", "p" + m.group(2)

15. `column_type`: its doctest. Old:

            >>> column_type("time(6)"), column_type("interval year")
            (('time', None), ('ym', None))

   New:

            >>> column_type("time(6)"), column_type("interval year")
            (('time', None), ('ym', None))
            >>> column_type("timestamp_ntz(7)"), column_type("TIMESTAMP_LTZ(9)")
            (('ntz_ns', 'p7'), ('ltz_ns', 'p9'))

16. `coverage_by_type`: the decimal bands are the decimal columns' alone. Old:

                bands = {columns[c][1] for c in read if columns[c][1]}
                if bands:

   New:

                bands = {columns[c][1] for c in read if columns[c][0] == "dec"}
                if bands:

17. The admission table's functions, new, immediately before `def read(path):`. Old:

        def read(path):

   New:

        # Spark's own answer, per entry and nanosecond type, to "does the entry take a column of the
        # type": written by row 661's suite from Spark's type check with
        # spark.sql.timestampNanosTypes.enabled on (m9/PLAN.md 2.4), one answer a precision. The census
        # takes a nanosecond type's admission from this file and from no hand list, so a sync of Spark
        # moves the cells by itself.
        NANOS_ADMISSION = MILESTONE.path("nanos_admission.tsv")
        ADMISSION_HEADER = ["entry", "type", "p7", "p8", "p9", "result"]
        ACCEPTED = "accepted"
        # A cell that --asked wrote and the suite has not answered yet.
        UNASKED = "?"
        # The suite that asks Spark and writes the table's cells, and the switch that makes it write.
        ADMISSION_SUITE = "VarkaNanosAdmissionSuite"
        ADMISSION_REMEDY = (
            "run python3 dev/varka_census.py --asked, then VARKA_NANOS_ADMISSION_REGEN=true build/sbt"
            f" 'catalyst/testOnly *{ADMISSION_SUITE}'"
        )


        def nanos_admission(root=ROOT):
            """The admission table as {(entry, type): (the answers at precision 7, 8 and 9, result)}: an
            answer is "accepted" or the error class Spark rejects the call with; the result is the census
            type of the entry's result over that type, or "-".

            >>> import tempfile
            >>> with tempfile.TemporaryDirectory() as d:
            ...     path = os.path.join(d, NANOS_ADMISSION)
            ...     os.makedirs(os.path.dirname(path))
            ...     with open(path, "w") as f:
            ...         _ = f.write("entry\\ttype\\tp7\\tp8\\tp9\\tresult\\n")
            ...         _ = f.write("year\\tntz_ns\\taccepted\\taccepted\\taccepted\\tint\\n")
            ...     nanos_admission(d)
            {('year', 'ntz_ns'): (('accepted', 'accepted', 'accepted'), 'int')}
            """
            path = os.path.join(root, NANOS_ADMISSION)
            if not os.path.exists(path):
                return {}
            out = {}
            with open(path, encoding="utf-8") as f:
                for line in f.read().splitlines()[1:]:
                    entry, t, p7, p8, p9, result = line.split("\t")
                    out[(entry, t)] = ((p7, p8, p9), result)
            return out


        def asked_entries():
            """The entries the admission table has lines for, in the row order of census.tsv (the
            registered names in the registry's order, then the implicit operators in the curated file's):
            every curated entry that is not a cast entry and whose types hold a timestamp. It reads the
            registry and the curated file alone, so it works before the table exists."""
            sections = curated_sections()
            curated = {r[0]: r[1] for r in parse_table(sections["registered"], 7)}
            names = [(name, curated[name]) for _, name, _ in registry() if name in curated]
            names += [(r[0], r[3]) for r in parse_table(sections["implicit"], 9)]
            stamps = {"ntz", "ltz", *NS}
            asked = []
            for name, types in names:
                if name in CAST_ENTRY_MODES or types == "legal" or name in asked:
                    continue
                if stamps & set(expand(",".join(x for x in types.split(",") if not x.endswith("!")))):
                    asked.append(name)
            return asked


        def admission_skeleton(asked, admission):
            """The admission table's text for the entries `asked`, in their order: the answers
            `admission` has, and UNASKED where it has none (--asked writes this, and the suite then
            asks Spark for every line).

            >>> adm = {("year", NS[0]): ((ACCEPTED,) * 3, "-"), ("gone", NS[0]): ((ACCEPTED,) * 3, "-")}
            >>> print(admission_skeleton(["hour", "year"], adm).replace("\\t", " "), end="")
            entry type p7 p8 p9 result
            hour ntz_ns ? ? ? -
            hour ltz_ns ? ? ? -
            year ntz_ns accepted accepted accepted -
            year ltz_ns ? ? ? -
            """
            lines = ["\t".join(ADMISSION_HEADER)]
            for entry in asked:
                for x in NS:
                    answers, result = admission.get((entry, x), ((UNASKED,) * 3, "-"))
                    lines.append("\t".join([entry, x, *answers, result]))
            return "\n".join(lines) + "\n"


        def admission_errors(asked, admission):
            """The admission table's lines that no entry asks, and a table whose lines are not in the
            order of `asked`. A missing line is admit_nanos's to report, with the entry.

            >>> adm = {("year", NS[0]): 1, ("abs", NS[0]): 1}
            >>> [e.split(": ", 1)[1] for e in admission_errors(["year"], adm)]
            ['a line for abs over ntz_ns, which no entry asks']
            >>> adm = {("year", NS[0]): 1, ("hour", NS[0]): 1}
            >>> admission_errors(["hour", "year"], adm)[0].split(": ", 1)[1].split(";")[0]
            'its lines are not in the row order of census.tsv'
            >>> admission_errors(["hour", "year"], {("year", NS[0]): 1})
            []
            """
            want = [(entry, x) for entry in asked for x in NS]
            errors = [
                f"{NANOS_ADMISSION}: a line for {entry} over {x}, which no entry asks"
                for entry, x in admission
                if (entry, x) not in want
            ]
            if not errors and list(admission) != [k for k in want if k in admission]:
                errors.append(
                    f"{NANOS_ADMISSION}: its lines are not in the row order of census.tsv; "
                    + ADMISSION_REMEDY
                )
            return errors


        def admit_nanos(name, t, admission, errors):
            """An entry's types `t` with the nanosecond types as Spark's own answer has them: (types,
            results, rejected). A nanosecond type is admitted when Spark accepts the entry over it at
            every precision; otherwise it leaves the types, and `rejected` says at which precisions
            Spark rejects it and with which error class. Only an entry that takes a timestamp is asked.

            >>> adm = {("year", NS[0]): ((ACCEPTED,) * 3, "int"),
            ...        ("year", NS[-1]): ((ACCEPTED, "X.Y", "X.Y"), "-")}
            >>> admit_nanos("year", ["date", "ntz"], adm, [])
            (['date', 'ntz', 'ntz_ns'], ['int'], ['ltz_ns at precision 8, 9: X.Y'])
            >>> errors = []
            >>> admit_nanos("hour", ["ntz"], {}, errors)[0], errors[0].startswith("hour: no line in")
            (['ntz'], True)
            >>> admit_nanos("abs", ["int"], adm, [])
            (['int'], [], [])
            """
            results, rejected = [], []
            if not set(t) & {"ntz", "ltz", *NS}:
                return t, results, rejected
            for x in NS:
                answers, result = admission.get((name, x), ((UNASKED,) * 3, "-"))
                if UNASKED in answers:
                    missing = "no line" if (name, x) not in admission else "no answer yet"
                    errors.append(
                        f"{name}: {missing} in {NANOS_ADMISSION} says whether Spark takes a {x}; "
                        + ADMISSION_REMEDY
                    )
                    t = [y for y in t if y != x]
                    continue
                no = [str(7 + i) for i, a in enumerate(answers) if a != ACCEPTED]
                if not no:
                    t = sorted(set(t) | {x}, key=ORDER.index)
                    if result != "-" and result not in results:
                        results.append(result)
                else:
                    t = [y for y in t if y != x]
                    at = "" if len(no) == 3 else f" at precision {', '.join(no)}"
                    why = next(a for a in answers if a != ACCEPTED)
                    rejected.append(f"{x}{at}: {why}")
            return t, results, rejected


        def read(path):

18. `row`: its signature. Old:

        def row(section, name, cls, types, result, fused, need, rows, note, cov, errors, probe):

   New:

        def row(
            section, name, cls, types, result, fused, need, rows, note, cov, errors, probe, admission=None
        ):

19. `row`: the nanosecond types come from the admission table. Old:

            t = expand(",".join(p for p in types.split(",") if p and not p.endswith("!")))
            res = t if result == "same" else expand(result)

   New:

            t = expand(",".join(p for p in types.split(",") if p and not p.endswith("!")))
            # A nanosecond type is admitted by Spark's own answer (nanos_admission), not by this file;
            # a cast entry's types are the cast table's, which VarkaCastMatrixSuite holds to Cast.
            more, rejected = [], []
            if admission is not None and name not in CAST_ENTRY_MODES:
                t, more, rejected = admit_nanos(name, t, admission, errors)
            res = t if result == "same" else expand(",".join([result] + more))

20. `row`: the reason column. Old:

                f"declined {','.join(declined)}: {DECLINED}" if declined else "",

   New:

                "; ".join(
                    ([f"declined {','.join(declined)}: {DECLINED}"] if declined else [])
                    + ["Spark rejects " + r for r in rejected]
                ),

21. `build`: read the admission table once. Old:

            probe = casts_fused()
            classes = defined_classes()
            errors = []

   New:

            probe = casts_fused()
            admission = nanos_admission()
            classes = defined_classes()
            errors = []

22. `build`: the call of `row` for a registered entry. Old:

                    r = row(section, name, cls, types, result, fused, need, rows, note, cov, errors, probe)

   New:

                    r = row(
                        section, name, cls, types, result, fused, need, rows, note, cov, errors, probe,
                        admission,
                    )

23. `build`: the call of `row` for an implicit entry. Old:

                r = row("implicit", name, cls, types, result, fused, need, rows, note, cov, errors, probe)

   New:

                r = row(
                    "implicit", name, cls, types, result, fused, need, rows, note, cov, errors, probe,
                    admission,
                )

24. `build`: the table's own errors, before the function returns. Old:

            return out, errors


        def row(

   New:

            errors += admission_errors(asked_entries(), admission)
            return out, errors


        def row(

25. `main`: the option `--asked`. Old:

            ap.add_argument("--self-test", action="store_true", help="run the doctests and stop")

   New:

            ap.add_argument("--self-test", action="store_true", help="run the doctests and stop")
            ap.add_argument(
                "--asked",
                action="store_true",
                help="rewrite the admission table's lines for the entries the census asks, and stop",
            )

26. `main`: `--asked` rewrites the table's lines and stops, before the census is built. Old:

            out, errors = build()
            # A census that read no registry or no curated row would agree with an empty committed file.

   New:

            if args.asked:
                if not set(NS) <= set(ORDER):
                    missing = " or no ".join(NS)
                    print(f"census: {TYPES} has no {missing}: regenerate it first", file=sys.stderr)
                    return 1
                asked = asked_entries()
                text = admission_skeleton(asked, nanos_admission())
                with open(os.path.join(ROOT, NANOS_ADMISSION), "w", encoding="utf-8") as f:
                    f.write(text)
                new = sum(UNASKED in line.split("\t") for line in text.splitlines())
                lines = len(asked) * len(NS)
                print(f"{NANOS_ADMISSION}: {len(asked)} entries, {lines} lines, {new} to ask")
                return 0
            out, errors = build()
            # A census that read no registry or no curated row would agree with an empty committed file.

27. `legal`: its doctests. Old:

            >>> sum(legal(a, b)[1] for a in types for b in types if "ltz" in (a, b))
            21

   New:

            >>> sum(legal(a, b)[1] for a in types for b in types if "ltz" in (a, b))
            21
            >>> [legal(NS[0], t) for t in ("int", "bool", "dt", "date", "time", "ltz", NS[-1], NS[0])]
            [(False, False), (False, False), (False, False), (True, True), (True, True), (True, True), \
        (True, True), (True, True)]
            >>> types = M8_TYPES + ["ltz"] + NS
            >>> sum(all(legal(a, b)) for a in types for b in types if {a, b} & set(NS))
            20

28. The comment above `CASTS_FUSED`: the probe's forms. Old:

        # every legal cast between two forms of the census's types (each interval field range, three time
        # precisions, two decimals) and fails when the compiler's answers move from the file (VARKA-486).

   New:

        # every legal cast between two forms of the census's types (each interval field range, three time
        # precisions, two decimals, three precisions of each nanosecond timestamp) and fails when the
        # compiler's answers move from the file (VARKA-486).

29. `column_type`: its docstring's first two lines. Old:

            """The census type of a coverage column's declared type, and its band for a decimal, read
            from the type rather than the column's name.

   New:

            """The census type of a coverage column's declared type, and its band for a decimal and for
            a nanosecond timestamp, read from the type rather than the column's name.

30. The band's names, new, immediately before `def pair_counts(`. Old:

        def pair_counts(scoped):

   New:

        # The entries whose answer over a nanosecond timestamp depends on the type's precision. For
        # each: the spellings of its call in SQL, the keyword its first argument must be for the answer
        # to depend on the precision, the position of its timestamp argument, and what reads the
        # precision in Spark. Every other entry reads the two words alone, so a kernel at one precision
        # is the kernel at all three. A fused claim of a listed entry needs, for each precision 7, 8 and
        # 9, a coverage row that holds such a call over a column of that precision (nanos_bands;
        # m9/VARKA-660.md 4.4); until then it is fused in part. A coverage row is also a query of the
        # coverage differential, so such a call is run against Spark's row path.
        PRECISION = {
            # SqlBaseParser.g4, #timestampadd: name=(TIMESTAMPADD | DATEADD | DATE_ADD), unit=datetimeUnit.
            "timestampadd": (
                ("timestampadd", "dateadd", "date_add"),
                "NANOSECOND",
                2,
                "the NANOSECOND unit floors the sum to the type's step (DateTimeUtils.timestampAddNanos)",
            ),
        }
        PRECISION_BANDS = ("p7", "p8", "p9")
        # A nanosecond type with its precision, as a cast's target is written in a coverage row's SQL.
        CAST_TARGET = re.compile(r"timestamp_(ntz|ltz)\s*\(\s*([789])\s*\)", re.IGNORECASE)


        def reads_precision(src, dst):
            """Whether a cast's answer depends on the target's precision: a nanosecond target, from a
            source that can hold digits below its step, which are TIME and the nanosecond types. Such a
            pair counts fused only with a coverage row that casts to each precision (cast_bands).

            >>> [(s, d) for s in ORDER for d in ORDER if reads_precision(s, d)]
            [('time', 'ntz_ns'), ('time', 'ltz_ns'), ('ntz_ns', 'ntz_ns'), ('ntz_ns', 'ltz_ns'), \
        ('ltz_ns', 'ntz_ns'), ('ltz_ns', 'ltz_ns')]
            """
            return dst in NS and src in ("time", *NS)


        def sql_code(sql):
            """A coverage row's SQL with its string literals and its comments blanked, so that a word
            inside one is no code.

            >>> sql_code("f(a, 'it''s NANOSECOND') /* NANOSECOND */ > b -- NANOSECOND").split()
            ['f(a,', ')', '>', 'b']
            """
            return re.sub(r"'(?:[^']|'')*'|\"[^\"]*\"|/\*.*?\*/|--[^\n]*", " ", sql, flags=re.DOTALL)


        def sql_calls(code, names):
            """The calls of the functions `names` in `code` (sql_code's result), in any case and at any
            depth: (name in lower case, the call's arguments). A call whose brackets do not close is
            left out.

            >>> sql_calls("g(TimestampAdd(HOUR, f(i, 1), n7)) > dateadd(DAY, 1, d", ("timestampadd",
            ...           "dateadd"))
            [('timestampadd', ['HOUR', 'f(i, 1)', 'n7'])]
            """
            out = []
            for m in re.finditer(r"\b(" + "|".join(names) + r")\s*\(", code, re.IGNORECASE):
                depth, start, args = 1, m.end(), []
                for i in range(m.end(), len(code)):
                    if code[i] == "(":
                        depth += 1
                    elif code[i] == ")":
                        depth -= 1
                        if depth == 0:
                            out.append((m.group(1).lower(), args + [code[start:i].strip()]))
                            break
                    elif code[i] == "," and depth == 1:
                        args.append(code[start:i].strip())
                        start = i + 1
            return out


        def bare_column(text, columns):
            """The column `text` is, when it is one column's name and nothing more, with or without
            brackets around it; else None. An argument that is a cast or a call or a sum has a type the
            census does not know, so it is no column here.

            >>> [bare_column(t, {"n7", "i"}) for t in ("n7", " ( n7 ) ", "n7 + i", "f(n7)", "x")]
            ['n7', 'n7', None, None, None]
            """
            name = text.strip()
            while name.startswith("(") and name.endswith(")"):
                name = name[1:-1].strip()
            return name if name in columns else None


        def nanos_bands(data):
            """Per listed entry, Catalyst class and nanosecond type, the precisions at which a coverage
            row shows the entry's dependence: {(entry, class, type): {"p7", ...}}. A precision counts
            when the row's SQL, its strings and comments apart, holds a call of the entry (PRECISION's
            spellings, in any case) whose first argument is the keyword and whose timestamp argument is
            a bare column of that precision. The class is an operand of the row that takes the
            column's type. Any other row counts for nothing, and so does any other argument: a cast, a
            call or a sum in the timestamp's place has a precision the census does not know.

            >>> def bands(*sqls):
            ...     data = {"columns": {"n7": "timestamp_ntz(7)", "n8": "timestamp_ntz(8)",
            ...                         "n9": "timestamp_ntz(9)", "i": "bigint"},
            ...             "expressions": [{"executable": s, "operands": ["TimestampAdd:ntz_ns"]}
            ...                             for s in sqls]}
            ...     return sorted(nanos_bands(data).get(("timestampadd", "TimestampAdd", "ntz_ns"), ()))
            >>> bands("timestampadd(HOUR, i, n7) = n7 AND concat('NANOSECOND', 'x') IS NOT NULL")
            []
            >>> bands("timestampadd(HOUR,i,n8) /* NANOSECOND */")
            []
            >>> bands("timestampadd(NANOSECOND, i, n9) > timestampadd(HOUR, i, n7)")
            ['p9']
            >>> bands("timestampadd(NANOSECOND, i, n9) = n7")
            ['p9']
            >>> bands("timestampadd(NANOSECOND, i, CAST(n9 AS TIMESTAMP_NTZ(7)))")  # not a bare column
            []
            >>> bands("timestampadd(NANOSECOND, i, timestampadd(HOUR, i, n7))")
            []
            >>> bands("timestampadd(nanosecond, i, n7)", "date_trunc('DAY', DateAdd(NANOSECOND, i, n8))",
            ...       "DATE_ADD(NANOSECOND, 0, (n9))")  # every spelling; nested; a value is not seen
            ['p7', 'p8', 'p9']
            """
            columns = {n: column_type(t) for n, t in data["columns"].items()}
            columns = {n: tb for n, tb in columns.items() if tb[0] in NS}
            bands = collections.defaultdict(set)
            for row in data["expressions"]:
                code = sql_code(row["executable"])
                for entry, (names, keyword, at, _) in PRECISION.items():
                    for _, args in sql_calls(code, names):
                        column = bare_column(args[at], columns) if len(args) > at else None
                        if column is None or args[0].upper() != keyword:
                            continue
                        t, band = columns[column]
                        for operand in row["operands"]:
                            cls, takes = operand.split(":")
                            if takes == t:
                                bands[(entry, cls, t)].add(band)
            return bands


        def cast_bands(data):
            """Per cast pair that reads its target's precision, the precisions the coverage rows cast
            to: {(from, to): {"p7", ...}}. A precision counts when the row performs the pair's cast (its
            `casts`) and its SQL, its strings and comments apart, holds a cast written CAST(x AS T),
            TRY_CAST(x AS T) or x::T, where T is the target type at that precision and x is a bare
            column of the pair's source type. A cast of another source, a typed literal, a cast the
            analyzer adds and a cast of anything but a column count for nothing.

            >>> def bands(sql, *casts):
            ...     data = {"columns": {"t": "time(6)", "d": "date", "n9": "timestamp_ntz(9)"},
            ...             "expressions": [{"executable": sql, "casts": [c + ":ansi" for c in casts]}]}
            ...     return {k: sorted(v) for k, v in cast_bands(data).items()}
            >>> bands("CAST(t AS TIMESTAMP_NTZ(9)) <> '2020 TIMESTAMP_NTZ(7)'", "time>ntz_ns")
            {('time', 'ntz_ns'): ['p9']}
            >>> bands("CAST(t AS TIMESTAMP_NTZ(9)) = CAST(n9 AS TIMESTAMP_NTZ(7))", "time>ntz_ns",
            ...       "ntz_ns>ntz_ns")
            {('time', 'ntz_ns'): ['p9'], ('ntz_ns', 'ntz_ns'): ['p7']}
            >>> bands("CAST(t AS TIMESTAMP_NTZ(9)) = CAST(d AS TIMESTAMP_NTZ(7))", "time>ntz_ns",
            ...       "date>ntz_ns")
            {('time', 'ntz_ns'): ['p9']}
            >>> bands("CAST(t AS TIMESTAMP_NTZ(9)) < TIMESTAMP_NTZ(8) '2020-01-01'", "time>ntz_ns")
            {('time', 'ntz_ns'): ['p9']}
            >>> bands("t::TIMESTAMP_NTZ(8) < try_cast((t) AS timestamp_ntz ( 7 ))", "time>ntz_ns")
            {('time', 'ntz_ns'): ['p7', 'p8']}
            >>> bands("CAST(t AS TIMESTAMP_LTZ(7)) IS NULL", "time>ntz_ns")  # another pair's target
            {}
            >>> bands("to_timestamp_ntz(t, 7) = n9 OR CAST(n9 + t AS TIMESTAMP_NTZ(7)) = n9",
            ...       "time>ntz_ns", "ntz_ns>ntz_ns")
            {}
            """
            columns = {n: column_type(t)[0] for n, t in data["columns"].items()}
            bands = collections.defaultdict(set)
            for row in data["expressions"]:
                code = sql_code(row["executable"])
                found = []
                for _, args in sql_calls(code, ("cast", "try_cast")):
                    parts = re.split(r"\s+AS\s+", args[0], flags=re.IGNORECASE) if len(args) == 1 else []
                    if len(parts) == 2:
                        found.append(parts)
                found += re.findall(r"(\w+)\s*::\s*(timestamp_(?:ntz|ltz)\s*\(\s*[789]\s*\))", code, re.I)
                performed = {tuple(cast.split(":")[0].split(">")) for cast in row["casts"]}
                for operand, target in found:
                    m = CAST_TARGET.fullmatch(target.strip())
                    src = columns.get(bare_column(operand, columns))
                    if m and src:
                        pair = (src, m.group(1).lower() + "_ns")
                        if reads_precision(*pair) and pair in performed:
                            bands[pair].add("p" + m.group(2))
            return bands


        def precision_lines(scoped):
            """The summary lines of what depends on the precision: for each nanosecond type, how many
            listed entries have a matching coverage row at every precision, and how many of the cast
            pairs that read their target's precision have one."""
            data = json.loads(read(COVERAGE))
            bands, casts = nanos_bands(data), cast_bands(data)
            every = set(PRECISION_BANDS)
            lines = ["nanosecond entries and cast pairs whose answer depends on the precision, with a"]
            lines[0] += " matching coverage row at each of 7, 8 and 9:"
            for t in NS:
                held = [r for r in scoped if r[0] in PRECISION and t in r[5].split(",")]
                n = sum(
                    1
                    for r in held
                    if any(bands.get((r[0], c, t), set()) >= every for c in check_cls_of(r[0], r[1]))
                )
                lines.append(f"  {t:28s} {n:4d} / {len(held)}")
            pairs = [(s, d) for s in ORDER for d in ORDER if reads_precision(s, d)]
            n = sum(casts.get(pair, set()) >= every for pair in pairs)
            lines.append(f"  {'cast pairs':28s} {n:4d} / {len(pairs)}")
            return lines


        def pair_counts(scoped):

31. `summary`: the band's lines, after the decimal's. Old:

                lines.extend(decimal_representation_lines(scoped))

   New:

                lines.extend(decimal_representation_lines(scoped))
                lines.extend(precision_lines(scoped))

32. `row`: a fused claim of a listed entry needs a matching row at each precision. Old:

            for x in partial:
                if x not in t:

   New:

            for x in f:
                if x in NS and name in PRECISION:
                    held = cov.get("#bands", {})
                    seen = set().union(*(held.get((name, c, x), set()) for c in check_classes))
                    missing = [b[1:] for b in PRECISION_BANDS if b not in seen]
                    if missing:
                        errors.append(
                            f"{name}: its answer over {x} depends on the precision, and no coverage row"
                            f" holds its {PRECISION[name][1]} call over a column of precision"
                            f" {', '.join(missing)}: mark it {x}^"
                        )
            for x in partial:
                if x not in t:

33. `coverage_index`: the bands the coverage rows show. Old:

            index["#sql"] = {}
            index["#casts"] = set()

   New:

            index["#sql"] = {}
            index["#casts"] = set()
            index["#bands"] = nanos_bands(data)
            index["#cast_bands"] = cast_bands(data)

34. `row`: the doctest of the band's rule. Old:

            >>> claim("date", "", "unplanned")  # a need the census does not know
            ["year: unknown need 'unplanned'"]

   New:

            >>> claim("date", "", "unplanned")  # a need the census does not know
            ["year: unknown need 'unplanned'"]
            >>> def stamp(bands):  # an entry whose answer depends on the precision (PRECISION)
            ...     errors = []
            ...     c = {"TimestampAdd": {"ntz_ns"}, "#sql": {},
            ...          "#bands": {("timestampadd", "TimestampAdd", "ntz_ns"): bands}}
            ...     row("implicit", "timestampadd", "TimestampAdd", "ntz_ns", "same", "ntz_ns", "done", "",
            ...         "", c, errors, {})
            ...     return errors
            >>> stamp({"p7", "p8", "p9"})
            []
            >>> stamp({"p9"})[0].endswith("call over a column of precision 7, 8: mark it ntz_ns^")
            True

35. `summary`: its docstring. Old:

            """The counts of a census's rows. `bands` adds the decimal lines, which read today's
            coverage table and so are left out of a closed milestone's summary."""

   New:

            """The counts of a census's rows. `bands` adds the decimal and the nanosecond lines, which
            read today's coverage table and so are left out of a closed milestone's summary."""

36. `pair_credit`: a cast pair that reads its target's precision. Old:

            if all(0 < fused.get(m, 0) == legal_forms.get(m) for m in admitted):
                return "all"

   New:

            if all(0 < fused.get(m, 0) == legal_forms.get(m) for m in admitted):
                # A pair that reads its target's precision is fused only when a coverage row casts to
                # each precision: the probe compiles each form, and only a coverage row is run.
                cast_to = cov.get("#cast_bands", {}).get((src, dst), set())
                if reads_precision(src, dst) and not cast_to >= set(PRECISION_BANDS):
                    return "part"
                return "all"

37. `pair_credit`: the doctest of the casts' band. Old:

            >>> pair_credit(probe, cov, "int", "int", ("ansi",), read={"date"})  # no kernel reads it
            ''

   New:

            >>> pair_credit(probe, cov, "int", "int", ("ansi",), read={"date"})  # no kernel reads it
            ''
            >>> probe = {("time", "ntz_ns"): ({"ansi": 9}, {"ansi": 9})}  # every form fuses
            >>> cov = {"#casts": {("time", "ntz_ns", "ansi")}, "#cast_bands": {("time", "ntz_ns"): {"p9"}}}
            >>> pair_credit(probe, cov, "time", "ntz_ns", ("ansi",))  # no row casts to 7 or to 8
            'part'
            >>> cov["#cast_bands"][("time", "ntz_ns")] = {"p7", "p8", "p9"}
            >>> pair_credit(probe, cov, "time", "ntz_ns", ("ansi",))
            'all'

38. The comment above milestone 9's entry. It is row 654's, and its line breaks are its
   builder's, so it is not matched as text. Find the line below, which edit 3 wrote; delete
   every line directly above it that begins with four spaces and `#`, up to the first line
   that does not; then put the new comment in their place. Old:

            9: Milestone(9, M8_TYPES + ["ltz"] + NS, ["ltz"] + NS, None),

   New:

            # Milestone 9 adds TIMESTAMP_LTZ, "ltz", and the nanosecond timestamps, NS, to milestone
            # 8's thirteen (m9/PLAN.md 2.4, m9/VARKA-660.md).
            9: Milestone(9, M8_TYPES + ["ltz"] + NS, ["ltz"] + NS, None),

Under choice (c) edits 30 to 37 are left out, and nothing else changes.

### 6.2 The admission table

`sql/varka/plans/m9/nanos_admission.tsv`. Its lines are written by `python3
dev/varka_census.py --asked`, its answers by row 661's suite, and it is read by
`nanos_admission`. What this design fixes of it:

- **The header**, tab-separated: `entry`, `type`, `p7`, `p8`, `p9`, `result`
  (`ADMISSION_HEADER`).
- **One line for each asked entry and each of `ntz_ns` and `ltz_ns`.** The asked entries are
  `asked_entries()`: the curated entries that are not cast entries (`CAST_ENTRY_MODES`:
  `cast`, `try_cast` and the cast aliases) and whose types hold `ntz` or `ltz` or name a
  nanosecond type.
- **The order** is the row order of `census.tsv`: the registered names in the order of
  Spark's function registry, then the implicit operators in the order of the curated file;
  within an entry, `ntz_ns` before `ltz_ns`. `--asked` writes that order, and the script
  enforces it: `--check` fails on a line no entry asks, on a missing line, and on lines out
  of order (4.8). The suite keeps the order it reads.
- **`p7`, `p8`, `p9`**: Spark's answer over a column of the type at that precision: the word
  `accepted`, or the error class of the analysis error, such as
  `DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE`. `?` is a cell `--asked` wrote and the suite has
  not answered; the script treats it as an error.
- **`result`**: the census name of the entry's result type over that input when it is a
  nanosecond type (`ntz_ns` for `date_trunc` over `ntz_ns`), and `-` otherwise. The census adds
  it to the entry's result column. For an entry whose curated result is `same` the census does
  not read it: such a result follows the types.
- **The suite's names.** Its class is `VarkaNanosAdmissionSuite`, a JUnit 5 suite of the
  catalyst tests beside `VarkaTypesSuite`, and its regeneration switch is the environment
  variable `VARKA_NANOS_ADMISSION_REGEN`, set to `true`. Step 6 of 6.0 and the script's
  remedy text use these names, so row 661's plan may not rename them.
- **The call the suite builds for an entry.** One call an entry, from a table in the suite
  that row 661's plan gives whole, one line for each of the 66 entries (its section on the
  admission suite). The rule of each line: the entry's SQL as a user writes it, with a column
  `c` of the line's type in every timestamp parameter, a second column `c2` of the same type
  where the entry takes two, and a literal in every other parameter: `year(c)`, `c < c2`,
  `date_trunc('HOUR', c)`, `timestampadd(NANOSECOND, 1, c)`, `add_months(c, 1)`.
- **What the suite does.** With its regeneration switch on, it reads the table's lines, and
  for each line builds the entry's call over a column of the line's type at each of the three
  precisions, analyzes it, and writes the three answers and the result. With the switch off,
  as in every Build, it does the same in memory and fails where the file differs. It sets
  `spark.sql.timestampNanosTypes.enabled` to true itself, not by the default (`m9/PLAN.md`
  2.4). It asks under both settings of `spark.sql.ansi.enabled` and fails where the two
  answers differ, since the table has one. An entry it has no call for fails the suite by
  name; it guesses none.
- **Every argument of a call has the line's own type.** `time_bucket` rejects an origin
  whose type is not the timestamp's (`TimeBucket.checkInputDataTypes`), so its origin is a
  literal of the same kind and the same precision as the column; a comparison, `coalesce`,
  `greatest` and the like take two columns of the same type. A call with mixed precisions
  would test Spark's widening, which is the cast table's, not the entry.

What the table holds at the build is Spark's answer, not this file's. This file predicts it
(section 8): 132 lines for 66 entries, every cell `accepted` but the five of 4.5, and `result`
read on the nine entries that keep their input's type and are not curated as `same`:
`from_utc_timestamp`, `to_utc_timestamp`, `date_trunc`, `convert_timezone`, `time_bucket`,
`datetime_sub`, `ntz_plus_dt`, `ntz_plus_ym` and `timestampadd`.

### 6.3 The curated file

The types an entry admits are not typed into the curated file for the two new types: the
census takes them from the admission table (edit 19). What is typed is the judgement: the two
entries that come into scope, and the row that owes each cell. A one-off script does both, in
two steps, because the first must come before the table and the second after it (6.0, steps 4
and 7). Save the block below as `target/varka-661/ns_curated.py` with the four leading
spaces of every line removed, so that `#!/usr/bin/env python3` is its first line at column 0.

- `ns_curated.py new <curated file>` adds the lines of `timestamp_nanos` and `unix_nanos` at
  the end of the section `[registered]`, and two lines to the file's header, after the line
  that explains `dec`. It also has a guard for the seven date functions (4.5): one that lists
  neither `ntz` nor `ltz` nor `NS` gets the group `NS` at the end of its types, so that it is
  asked. Row 654 lists `ntz,ltz` for all seven (12.2), so on this file's base the guard
  writes nothing, and the step prints `2 entries added, 2 header lines added, 0 date
  functions now ask the two types`. A number other than 0 there means row 654 was built
  without 12.2's edits: the builder reports it and goes on, since the census is right either
  way. The step writes nothing and exits non-zero when the entries are already there, when a
  date function has no line, or when it does not find exactly one header line that begins
  `# (dec is DECIMAL up to precision 38` and exactly one line `[implicit]`.
- `ns_curated.py rows <curated file> <admission table>` gives each entry that Spark accepts
  over a nanosecond type, and each cast entry, the row that owes it (4.5). On the prototype
  it prints `68 entries edited, 0 of them no longer done; 0 by default, 0 absent`. The 68
  are the 64 accepted entries less `unix_nanos`, which came with its row in the first step,
  and the five cast entries. At the build the numbers are the day's: an entry that row 656
  or a later row has made `done` by then loses it here, since an entry is `done` only when
  every type it admits is fused (the script sets `plumbing` for a cell of row 662's
  zone-free entries and `semantics` for the others). It exits non-zero when the table still
  holds a `?`, when the file already names the rows, and on a line with a wrong number of
  fields.

Each `default:` and `absent:` line the second step prints goes into the pull request's
description. The script is not committed.

    #!/usr/bin/env python3
    """One-off: the nanosecond types in milestone 9's curated census (VARKA-660 6.3, run by row
    661), in two steps. Each rewrites <census_curated.txt> in place.

    usage: ns_curated.py new <census_curated.txt>
               before the admission table exists: the two entries that come into scope, and two
               lines of the file's header
           ns_curated.py rows <census_curated.txt> <nanos_admission.tsv>
               after the admission suite has written the table: the row that owes each cell
    Not committed. The types an entry admits are not written here: the census takes a nanosecond
    type from the admission table. The second step gives each entry that Spark accepts over one,
    and each cast entry, the row that owes it. An entry that no list names gets the row of its
    kind (default_row) and is printed.
    """
    import sys

    OWNER = {}
    for row, names in {
        "662": "coalesce if ifnull nullif nvl nvl2 when between isnull isnotnull equal_null in <=> = =="
        " > >= < <= greatest least hash xxhash64 in_set known_not_null try_eval",
        "662,673": "hour extract",
        "673": "day dayofyear dayofmonth last_day month next_day quarter trunc dayofweek weekday"
        " weekofyear year minute second date_part datepart time_bucket"
        " add_months datediff date_diff date_add dateadd date_sub unix_date",
        "674": "cast try_cast date time timestamp",
        "675": "date_trunc timestampadd from_utc_timestamp to_utc_timestamp convert_timezone"
        " unix_seconds unix_millis unix_micros ntz_plus_dt ntz_plus_ym datetime_sub",
    }.items():
        for n in names.split():
            OWNER[n] = row
    CASTS = {n for n, r in OWNER.items() if r == "674"}
    UNDONE, DEFAULTED = [], []
    NEW = """\
    # The nanosecond timestamps' own entries, in scope since row 661 (m9/VARKA-660.md 4.5).
    timestamp_nanos|INTEGRAL,dec|ltz_ns||semantics|675|nanoseconds since the epoch to the two words by floor; always precision 9; a count outside the range raises
    unix_nanos|NS|dec||semantics|675|the microsecond word times a thousand plus the second word, as DECIMAL(21, 0); reads no zone
    """


    # Two lines for the file's header, after the line that explains dec.
    HEADER = [
        "# (ntz_ns and ltz_ns are TIMESTAMP_NTZ(p) and TIMESTAMP_LTZ(p), p from 7 to 9; NS is the two. NS in",
        "# an entry's types asks Spark; the census admits the two types from nanos_admission.tsv alone)",
    ]


    # The date functions that Spark takes over a nanosecond timestamp through a cast to DATE, and
    # that count over the two types by the owner's word of 27 November 2026 (VARKA-660 4.5).
    DATE_FUNCTIONS = "add_months datediff date_diff date_add dateadd date_sub unix_date".split()
    DATE_NOTE = (
        "a nanosecond timestamp through its date, the cast to DATE under the node; the microsecond"
        " timestamps are not listed here (VARKA-654.md 4.13, question 5)"
    )


    def split(line):
        return ["|"] + line[2:].split("|") if line.startswith("||") else line.split("|")


    def default_row(fields, first):
        """The row that owes the nanosecond types for an entry no list names, by its kind: (row,
        kind). An implicit entry (`first` is 3) has its family in the third field."""
        types, result = fields[first].split(","), fields[first + 1].split(",")
        if types == ["legal"] or (first == 3 and fields[2] == "conversion"):
            return "674", "a cast"
        if "ALL" in types:
            return "662", "an entry that takes every type and reads no zone"
        if {"ntz", "ltz", "same"} & set(result):
            return "675", "a function with a timestamp result"
        return "673", "a field of a timestamp"


    def edit(fields, first, accepted):
        name = fields[0]
        need, rows, note = (first + i for i in (3, 4, 5))
        if name not in accepted and name not in CASTS:
            return fields
        if name in OWNER:
            row = OWNER[name]
        else:
            row, kind = default_row(fields, first)
            DEFAULTED.append(name)
            print(f"default: {name} -> row {row} ({kind})")
        if fields[need] == "done":
            fields[need] = "plumbing" if row == "662" else "semantics"
            UNDONE.append(name)
        held = [r for r in fields[rows].split(",") if r]
        fields[rows] = ",".join(held + [r for r in row.split(",") if r not in held])
        owes = "rows 662 and 673" if "," in row else f"row {row}"
        add = f"the nanosecond types are {owes}'s"
        fields[note] = (fields[note] + "; " if fields[note] else "") + add
        return fields


    def add_new(path):
        lines = open(path, encoding="utf-8").read().split("\n")
        if any(line.startswith("unix_nanos|") for line in lines):
            sys.exit(f"{path} already has the entries: restore it (git checkout -- {path}) and run once")
        anchor = [i for i, line in enumerate(lines) if line.startswith("# (dec is DECIMAL up to precision 38")]
        close = [i for i, line in enumerate(lines) if line == "[implicit]"]
        if len(anchor) != 1 or len(close) != 1:
            sys.exit(
                f"{path}: found {len(anchor)} header lines that begin '# (dec is DECIMAL up to precision"
                f" 38' and {len(close)} lines '[implicit]', and need one of each: nothing was written"
            )
        k = close[0]
        while lines[k - 1] == "":
            k -= 1
        # A date function is asked of Spark when its types name the nanosecond types. One that
        # already lists a timestamp (ntz or ltz) is asked without it, and is left as it is.
        asked, found = 0, set()
        for i, line in enumerate(lines[: close[0]]):
            fields = line.split("|")
            if fields[0] in DATE_FUNCTIONS and len(fields) == 7 and not line.startswith("#"):
                found.add(fields[0])
                if not {"ntz", "ltz", "NS"} & set(fields[1].split(",")):
                    fields[1] += ",NS"
                    fields[6] = (fields[6] + "; " if fields[6] else "") + DATE_NOTE
                    lines[i] = "|".join(fields)
                    asked += 1
        if len(found) != len(DATE_FUNCTIONS):
            gone = ", ".join(sorted(set(DATE_FUNCTIONS) - found))
            sys.exit(f"{path}: no line of [registered] for {gone}: nothing was written")
        lines[k:k] = NEW.rstrip("\n").split("\n")
        lines[anchor[0] + 1 : anchor[0] + 1] = HEADER
        open(path, "w", encoding="utf-8").write("\n".join(lines))
        print(f"2 entries added, 2 header lines added, {asked} date functions now ask the two types")


    def name_rows(path, admission):
        accepted = set()
        for line in open(admission, encoding="utf-8").read().splitlines()[1:]:
            entry, _, p7, p8, p9, _ = line.split("\t")
            if "?" in (p7, p8, p9):
                sys.exit(f"{admission}: {entry} has no answer yet: run the admission suite first")
            if (p7, p8, p9) == ("accepted",) * 3:
                accepted.add(entry)
        lines = open(path, encoding="utf-8").read().split("\n")
        if any("the nanosecond types are" in line for line in lines):
            sys.exit(f"{path} already names the rows: restore it (git checkout -- {path}) and run once")
        out, section, seen = [], None, set()
        for i, line in enumerate(lines):
            if line.startswith("[") and line.endswith("]"):
                section = line[1:-1]
            elif line and not line.startswith("#") and section in ("registered", "implicit"):
                first = 1 if section == "registered" else 3
                fields = split(line)
                if len(fields) != first + 6:
                    sys.exit(f"line {i + 1}: {len(fields)} fields")
                before = list(fields)
                # unix_nanos came with its row in the first step
                fields = fields if fields[0] == "unix_nanos" else edit(fields, first, accepted)
                if fields != before:
                    seen.add(fields[0])
                    line = "||" + "|".join(fields[1:]) if fields[0] == "|" else "|".join(fields)
            out.append(line)
        absent = sorted((accepted | CASTS) - seen - {"unix_nanos"})
        for name in absent:
            print(f"absent: {name} is accepted or a cast entry, and not in the file")
        open(path, "w", encoding="utf-8").write("\n".join(out))
        print(
            f"{len(seen)} entries edited, {len(UNDONE)} of them no longer done;"
            f" {len(DEFAULTED)} by default, {len(absent)} absent"
        )


    if __name__ == "__main__":
        if sys.argv[1:2] == ["new"] and len(sys.argv) == 3:
            add_new(sys.argv[2])
        elif sys.argv[1:2] == ["rows"] and len(sys.argv) == 4:
            name_rows(sys.argv[2], sys.argv[3])
        else:
            sys.exit(__doc__)

### 6.4 The other files

| file | what changes | by, in step |
| :--- | :--- | :--- |
| `sql/varka/types.tsv` | two lines after `TIMESTAMP`'s, with `ntz_ns` and `ltz_ns` in the column `census`, `declined` in `admission` and `-` in `lanes`, `conformance` and `conformance_row` | `VarkaTypesSuite`, from the registry; 2 |
| `sql/varka/plans/m9/nanos_admission.tsv` | new, 132 lines | `--asked` and the admission suite; 5 and 6 |
| `sql/varka/plans/m9/casts_fused.tsv` | 102 lines, each `declined` in the three modes | `VarkaCastMatrixSuite`; 8 |
| `sql/varka/plans/m9/census.tsv`, `casts.tsv` | 72 census lines change: 2 move from out to in, 68 gain the types and the row that owes them, and 3 gain a reason, one of them among the 68; 20 cast lines are new | `python3 dev/varka_census.py`; 9 |
| `docs/sql-varka.md`, the support table | two lines of the by-type table, two columns and two lines of each cast matrix | `python3 dev/varka_census.py`; 9 |
| `sql/varka/plans/m9/casts_suites.tsv` | 36 lines get `ntz_ns` or `ltz_ns` in `from` or `to`, and the file is sorted again | Spark's three cast suites under Varka; 10 |

### 6.5 The commands, each with what it prints on the prototype

Below, `M9=sql/varka/plans/m9`. A number that the day's census moves is restated by the
builder with the command; the ones marked "fixed" are Spark's and move only with a sync.

    # 1. the doctests and the check
    python3 dev/varka_census.py --self-test; echo $?                    # 0
    python3 dev/varka_census.py --check; echo $?                        # 0
    # 2. the two types
    python3 dev/varka_census.py --summary | grep -E '^  (ntz_ns|ltz_ns)  new'
                                    #   ntz_ns  new         0 / 69
                                    #   ltz_ns  new         0 / 68
    python3 dev/varka_census.py --summary | grep -E '^in scope:|pairs: '
                                    # in scope: 200 entries (173 classes)
                                    # (entry, type) pairs: 1168, fused today 585
    # 3. the cast pairs (fixed)
    awk -F'\t' '$1 ~ /_ns$/ || $2 ~ /_ns$/' $M9/casts.tsv | wc -l                                  # 20
    awk -F'\t' '($1 ~ /_ns$/ || $2 ~ /_ns$/) && $3=="yes" && $4=="yes" && $5=="yes"' $M9/casts.tsv | wc -l   # 20
    awk -F'\t' '$1 ~ /_ns$/ || $2 ~ /_ns$/ {print $6}' $M9/casts.tsv | sort | uniq -c
                                    # eight lines, each a count and a kind:
                                    #   3 floor division      1 floor modulo
                                    #   5 instant to local    4 local to instant
                                    #   2 relabel or truncation   1 scale up
                                    #   2 session date        2 widening
    awk -F'\t' '$1 ~ /_ns$/ || $2 ~ /_ns$/ {print substr($8, 1, 4)}' $M9/casts.tsv | sort | uniq -c
                                    # three lines: 1 exha, 9 "SMT ", 10 zone
    awk -F'\t' '($1 ~ /_ns$/ || $2 ~ /_ns$/) && $10 != "674" {print $1, $2, $10}' $M9/casts.tsv
                                    # ntz_ns ntz 662,674
                                    # ltz_ns ltz 662,674
    python3 dev/varka_census.py --summary | grep '^cast pairs'          # cast pairs over the 16 types: 256 ordered, 149 legal
    # 4. the probe (fixed)
    awk -F'\t' '$1 ~ /_ns$/ || $2 ~ /_ns$/' $M9/casts_fused.tsv | wc -l                            # 102
    awk -F'\t' '($1 ~ /_ns$/ || $2 ~ /_ns$/) && /fused/' $M9/casts_fused.tsv | wc -l               # 0
    # 5. the admission table (the day's Spark; this file's prediction beside it)
    python3 dev/varka_census.py --asked                                 # sql/varka/plans/m9/nanos_admission.tsv: 66 entries, 132 lines, 0 to ask
    git diff --stat -- $M9/nanos_admission.tsv                          # nothing: --asked changed no line
    awk -F'\t' 'NR>1 && !($3=="accepted" && $4=="accepted" && $5=="accepted") {print $1, $2, $3}' $M9/nanos_admission.tsv
                                    # months_between ntz_ns DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE
                                    # months_between ltz_ns DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE
                                    # convert_timezone ltz_ns DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE
                                    # ntz_minus_ntz ntz_ns UNSUPPORTED_FEATURE.TIMESTAMP_NANOS_SUBTRACTION
                                    # ntz_minus_ntz ltz_ns UNSUPPORTED_FEATURE.TIMESTAMP_NANOS_SUBTRACTION
    awk -F'\t' '$5 ~ /Spark rejects/' $M9/census.tsv | wc -l            # 3: one entry a line
    # 6. the two names in scope
    grep -c "nanosecond timestamps" $M9/census.tsv                      # 0
    grep -P "^(timestamp_nanos|unix_nanos)\t" $M9/census.tsv | cut -f1,4,6,7,11
                                    # timestamp_nanos  in  byte,short,int,bigint,dec  ltz_ns  675
                                    # unix_nanos       in  ntz_ns,ltz_ns              dec     675
    # 7. every cell of the two types names a row of phase 2
    awk -F'\t' 'NR>1 && $4=="in" && (","$6",") ~ /,(ntz_ns|ltz_ns),/ && $11 !~ /(^|,)(662|67[345])(,|$)/' $M9/census.tsv | wc -l   # 0
    # 8. the band: what carries it, and what has a matching coverage row at each precision
    python3 dev/varka_census.py --summary | grep -A3 '^nanosecond entries'
                                    # nanosecond entries and cast pairs whose answer depends on the precision, with a matching coverage row at each of 7, 8 and 9:
                                    #   ntz_ns                          0 / 1
                                    #   ltz_ns                          0 / 1
                                    #   cast pairs                      0 / 6
    grep -A8 '^PRECISION = ' dev/varka_census.py
                                    # nine lines: the dictionary, with the one entry "timestampadd", its three spellings, "NANOSECOND" and 2
    # 9. the suites' record (the day's)
    awk -F'\t' '$2 ~ /_ns$/ || $3 ~ /_ns$/' $M9/casts_suites.tsv | wc -l                           # 36
    # 10. the docs' cast matrices have a column for each type
    awk '/BEGIN generated support table/,/END generated support table/' docs/sql-varka.md | grep -c '^| from .* `ntz_ns` | `ltz_ns` |$'   # 3
    # 11. the upkeep of the band: the commands of facts 7, 22 and 23 print what section 3 says

### 6.6 The tests of this half, and what is row 661's own

**The doctests** the edits bring, all run by `--self-test` and by `--check`:

| function | cases, with their inputs | expected | the failure only it catches |
| :--- | :--- | :--- | :--- |
| `legal` | `ntz_ns` with `int`, `bool`, `dt`, `date`, `time`, `ltz`, `ltz_ns` and itself; the count of pairs legal in both modes over the 16 types | three times `(False, False)`, then five times `(True, True)`; `20` | a wrong transcription of `Cast` that the next regeneration would write into `casts.tsv` before any JVM runs |
| `ns_cast_kind` | `ntz` to `ntz_ns`; `ltz_ns` to `ltz`; `ntz_ns` to `ltz_ns`; through `cast_kind`: `date` to `ltz_ns`, `ntz_ns` to `time`, `time` to `ntz_ns` | `widening`, `floor division`, `local to instant`; `local to instant`, `floor modulo`, `session date` | the call of edit 9 placed inside the identity block, where it never runs; the two kinds of a zone pair swapped |
| `nanos_admission` | a file of one header and one line, `year`, `ntz_ns`, three times `accepted`, `int` | `{('year', 'ntz_ns'): (('accepted', 'accepted', 'accepted'), 'int')}` | a column read in the wrong order |
| `admission_skeleton` | the entries `hour` and `year`, with a table that has `year` over `ntz_ns` accepted and a line of an entry `gone` | five lines: the header; `hour` twice with `? ? ? -`; `year` over `ntz_ns` with its three answers; `year` over `ltz_ns` with `? ? ? -` | `--asked` losing an answer the table has, or keeping a line no entry asks |
| `admission_errors` | a table with a line of `abs`; a table whose two lines are in the other order; a table with one of two lines | `a line for abs over ntz_ns, which no entry asks`; `its lines are not in the row order of census.tsv`; no error | a stale line or a shuffled table passing the check; a missing line reported twice |
| `admit_nanos` | `year` over `date,ntz` with `ntz_ns` accepted three times and `ltz_ns` accepted at 7 only; `hour` with an empty table; `abs` over `int` | `(['date', 'ntz', 'ntz_ns'], ['int'], ['ltz_ns at precision 8, 9: X.Y'])`; the types unchanged and one error that begins `hour: no line in`; `(['int'], [], [])` | a type admitted from one accepted precision; a missing line read as a rejection; an entry with no timestamp asked |
| `column_type` | `timestamp_ntz(7)`, `TIMESTAMP_LTZ(9)` | `('ntz_ns', 'p7')`, `('ltz_ns', 'p9')` | a nanosecond coverage column with no census type, which stops the script |
| `reads_precision` | every ordered pair of the 16 types | the six pairs of 4.4, `time` to each type and each type to each | a seventh pair in the band, or one of the six out of it |
| `sql_code` | `f(a, 'it''s NANOSECOND') /* NANOSECOND */ > b -- NANOSECOND` | the words `f(a,`, `)`, `>`, `b`: no `NANOSECOND` is left | the unit word in a string, a block comment or a line comment read as code |
| `sql_calls` | `g(TimestampAdd(HOUR, f(i, 1), n7)) > dateadd(DAY, 1, d`, asked for `timestampadd` and `dateadd` | one call, `('timestampadd', ['HOUR', 'f(i, 1)', 'n7'])`: the nested call is found, its arguments are split at its own commas, and the call that does not close is left out | an argument split at a nested call's comma, which would put another expression in the timestamp's place |
| `bare_column` | `n7`, ` ( n7 ) `, `n7 + i`, `f(n7)`, `x`, with the columns `n7` and `i` | `'n7'`, `'n7'`, `None`, `None`, `None` | a sum or a call taken for the column inside it |
| `nanos_bands` | columns `n7`, `n8`, `n9` of `timestamp_ntz(7)`, `(8)`, `(9)` and `i` of `bigint`; rows with the operand `TimestampAdd:ntz_ns`. Seven cases: (1) `timestampadd(HOUR, i, n7) = n7 AND concat('NANOSECOND', 'x') IS NOT NULL`; (2) `timestampadd(HOUR,i,n8) /* NANOSECOND */`; (3) `timestampadd(NANOSECOND, i, n9) > timestampadd(HOUR, i, n7)`; (4) `timestampadd(NANOSECOND, i, n9) = n7`; (5) `timestampadd(NANOSECOND, i, CAST(n9 AS TIMESTAMP_NTZ(7)))`; (6) `timestampadd(NANOSECOND, i, timestampadd(HOUR, i, n7))`; (7) three rows, `timestampadd(nanosecond, i, n7)`, `date_trunc('DAY', DateAdd(NANOSECOND, i, n8))`, `DATE_ADD(NANOSECOND, 0, (n9))` | (1) `[]`; (2) `[]`; (3) `['p9']`; (4) `['p9']`; (5) `[]`; (6) `[]`; (7) `['p7', 'p8', 'p9']` | (1), (2) the unit in a string or a comment; (3), (4) a column of another precision elsewhere in the row; (5), (6) a timestamp argument that is not a bare column; (7) a lower-case unit, an alias, a nested call or a bracketed column refused, which Spark accepts |
| `cast_bands` | columns `t` of `time(6)`, `d` of `date`, `n9` of `timestamp_ntz(9)`. Seven cases, each with the casts the row performs: (1) `CAST(t AS TIMESTAMP_NTZ(9)) <> '2020 TIMESTAMP_NTZ(7)'`, `time>ntz_ns`; (2) `CAST(t AS TIMESTAMP_NTZ(9)) = CAST(n9 AS TIMESTAMP_NTZ(7))`, `time>ntz_ns` and `ntz_ns>ntz_ns`; (3) `CAST(t AS TIMESTAMP_NTZ(9)) = CAST(d AS TIMESTAMP_NTZ(7))`, `time>ntz_ns` and `date>ntz_ns`; (4) `CAST(t AS TIMESTAMP_NTZ(9)) < TIMESTAMP_NTZ(8) '2020-01-01'`, `time>ntz_ns`; (5) `t::TIMESTAMP_NTZ(8) < try_cast((t) AS timestamp_ntz ( 7 ))`, `time>ntz_ns`; (6) `CAST(t AS TIMESTAMP_LTZ(7)) IS NULL`, `time>ntz_ns`; (7) `to_timestamp_ntz(t, 7) = n9 OR CAST(n9 + t AS TIMESTAMP_NTZ(7)) = n9`, `time>ntz_ns` and `ntz_ns>ntz_ns` | (1) `time` to `ntz_ns`: `['p9']`; (2) `time` to `ntz_ns`: `['p9']`, and `ntz_ns` to `ntz_ns`: `['p7']`; (3) `['p9']`; (4) `['p9']`; (5) `['p7', 'p8']`; (6) nothing; (7) nothing | (1) a target type in a string; (2), (3) another source's cast to the precision; (4) a typed literal; (5) the `::` form, `try_cast`, spaces and a bracketed column refused; (6) another pair's target; (7) a function that is no cast, and a cast of a sum |
| `row`, the band's rule | `timestampadd` claimed fused over `ntz_ns` with matching rows at the bands `p7, p8, p9`, and with `p9` alone | no error; one error that ends `reads a column of precision 7, 8: mark it ntz_ns^` | a claim over the dependent entry with no matching row at a precision |
| `pair_credit`, the casts' band | `time` to `ntz_ns`, every probe form fused and a coverage row casting it, with rows to precision 9 alone and then to 7, 8 and 9 | `'part'`, then `'all'` | a cast pair of the band counted fused from the probe and one precision |

**The planted faults**, each run on the prototype by hand and reverted:

| plant | the check that fails | with |
| :--- | :--- | :--- |
| in `legal`, edit 7's list gains `"dt"` | `--self-test`, in `legal`'s doctests | `Expected: [(False, False), (False, False), (False, False), ...` and `Got: [(False, False), (False, False), (True, True), ...`; then `Expected: 20`, `Got: 24` |
| in `PRECISION`, the keyword `"NANOSECOND"` becomes `"HOUR"` | `--self-test`: six examples of `nanos_bands` fail | the first: `bands("timestampadd(HOUR, i, n7) = n7 AND concat('NANOSECOND', 'x') IS NOT NULL")`, `Expected: []`, `Got: ['p7']` |
| in `cast_rows`, edit 13's line returns `"674"` for every pair | `--check` exits 1 | `census: sql/varka/plans/m9/casts.tsv is stale; run dev/varka_census.py`; regenerated, command 3's fifth command prints nothing |
| delete the line of `year` and `ntz_ns` from the admission table | `--check` exits 1 | `census: year: no line in sql/varka/plans/m9/nanos_admission.tsv says whether Spark takes a ntz_ns; run python3 dev/varka_census.py --asked, then VARKA_NANOS_ADMISSION_REGEN=true build/sbt 'catalyst/testOnly *VarkaNanosAdmissionSuite'`. Then `--asked` prints `... 66 entries, 132 lines, 1 to ask` and the line is back with `?` |
| add a line of `abs` and `ntz_ns`, accepted, at the table's end | `--check` exits 1 | `census: sql/varka/plans/m9/nanos_admission.tsv: a line for abs over ntz_ns, which no entry asks`. Then `--asked` removes it |
| reverse the table's lines under its header | `--check` exits 1 | `census: sql/varka/plans/m9/nanos_admission.tsv: its lines are not in the row order of census.tsv; run python3 dev/varka_census.py --asked, then VARKA_NANOS_ADMISSION_REGEN=true build/sbt 'catalyst/testOnly *VarkaNanosAdmissionSuite'` |
| turn `months_between`'s line of `ntz_ns` to accepted | `--check` exits 1 | `census: sql/varka/plans/m9/census.tsv is stale; run dev/varka_census.py` and the same for `docs/sql-varka.md` |
| in a copy of the curated file, change the header line `# (dec is DECIMAL up to precision 38` to `# (dec is a DECIMAL up to precision 38`, and run `ns_curated.py new` on it | the script exits 1 and writes nothing | `found 0 header lines that begin '# (dec is DECIMAL up to precision 38' and 1 lines '[implicit]', and need one of each: nothing was written` |

**What is row 661's own**, with the values this design fixes for each:

| item | fixed here | left to row 661's plan |
| :--- | :--- | :--- |
| `VarkaTypeId` | two members after `TIMESTAMP`, in this order: `TIMESTAMP_NTZ_NANOS`, `TIMESTAMP_LTZ_NANOS` | nothing |
| `VarkaTypes.entry`, the two cases | census names `ntz_ns` and `ltz_ns`; SQL spellings `TIMESTAMP_NTZ(9)` and `TIMESTAMP_LTZ(9)`; three forms each, precisions 7, 8 and 9 in this order; no lanes; `Conformance.NotLanded`; an admission that declines | the decline's text, and the `Storage` of a struct of a long and a short, which it settles with row 662 |
| `VarkaTypes.idOf` | every precision of a class gives the class's one id | the code |
| the admission suite | its class `VarkaNanosAdmissionSuite`, its switch `VARKA_NANOS_ADMISSION_REGEN`, the file, who writes its lines and who its cells, what the suite reads and writes, the rule of its calls, and its rules (6.0, 6.2) | the Java, and the table of the 66 calls by 6.2's rule |
| `VarkaTypeConformance` | `CAST_TESTS` gains `"ltz_ns"` and `"ntz_ns"`, and two tests call `castTo("ntz_ns")` and `castTo("ltz_ns")`: without them every cast test of every conformance suite fails (`VARKA-654.md` fact 10) | their names and display names |
| `VarkaCastMatrixSuite` | a pinned test: 20 pairs with a nanosecond type, all legal in the three modes; `ntz_ns>int` and `dt>ltz_ns` absent | the code, on the model of `timestampPairs` in `VARKA-654.md` 5.2, step 6 |
| the new file among the generated files | it is generated, so it gets what `casts_fused.tsv` has: a line of `sql/varka/.gitattributes`, a `want` line of `dev/varka_regen_generated.sh`, and its name in `FILES` | the edits |
| `m9/PLAN.md` | row 660's line names this file | row 661's line |

## 7. Tests, proofs, fuzzing and coverage

Row 660 adds no test: it adds no code. What the design asks of the rows after it:

| what | the row | what it holds | the failure only it catches |
| :--- | :--- | :--- | :--- |
| the doctests of 6.6 | 661 | the transcription of `Cast`, the admission rule, the table's lines and order, the two bands | 6.6, line by line |
| `VarkaCastMatrixSuite`, unchanged: "casts.tsv's legality agrees with Cast over every ordered pair, in every mode" | 661 | `legal()` over the 16 types and every form, against `Cast.canCast`, `canAnsiCast` and `canTryCast` | a pair this file read wrong in 4.6 |
| the admission suite | 661 | Spark's analyzer over every asked entry, at each of the six types, under both ANSI settings | an entry this file read wrong in 4.5, and every later move of Spark |
| the probe, 102 lines | 661 writes them, 674 fuses them | every legal cast of two forms compiles to a kernel or declines | a cast that fuses at precision 9 and declines at 7, unrecorded |
| the band of the entry: for each type, three coverage rows `timestampadd(NANOSECOND, q, c)`, with `c` a column of precision 7, 8 and 9. `q` is a `BIGINT` column of the fixture whose values hold 5, -5, 1005, 0 and a null | 675 | Spark's row path, through the coverage differential: at precision 7 the sum of a value and 5 is the value, of -5 the value less 100 nanoseconds, of 1005 the value plus 1000 | a kernel that adds and carries into the microsecond word and does not floor to the step: it is right at precision 9 and wrong at 7 and 8 |
| the band of the casts: for each of the six pairs, three coverage rows, one to each precision. The source column holds values whose second word is 999 (a nanosecond type) or whose nanosecond of day ends in 9 (`TIME(9)`), a value before 1970, and a null | 674 | Spark's row path, through the coverage differential: the second word floored to 900 at precision 7 and to 990 at 8 | a cast kernel that copies the second word: it is right to precision 9 and wrong to 7 and 8 |
| the conformance suite of each type, whose fixture holds its three forms | 662 | every fused entry over a column of each form, with nulls and both ends of the type | an entry that reads the precision though this file says it does not |

The census checks that the two bands' rows exist, each with the dependent shape applied to a
column of the precision. It cannot check their values: the fifth and sixth lines above are rules
of rows 675's and 674's sheets, and their reviews hold them.

No proof file, no fuzz production, no coverage row and no IR record come from this row. The
option matrix and the width audit read nothing of it.

## 8. Measurement and predictions

The row times nothing. Its predictions are counts, registered on 27 November 2026 before row
661 is built. Each is scored by the command named, in row 661's outcome.

1. Under (b), row 661's summary reads `ntz_ns  new  0 / 69` and `ltz_ns  new  0 / 68`, give or
   take the entries that milestone 8's close and the syncs before row 661 add. Scored by
   command 2 of 6.5.
2. The cast table gains exactly 20 legal pairs, each legal in the three modes, and
   `VarkaCastMatrixSuite` agrees with no edit of 4.6's table. Scored by command 3 and by the
   suite.
3. Spark's analyzer gives the answers of 4.5: of 132 cells, 5 are rejections, with the two
   error classes named there, and no entry has two answers at two precisions or under the two
   ANSI settings. Scored by command 5.
4. The band is one entry, `timestampadd`, and six cast pairs when row 661 merges. Scored by
   command 8: its `grep -A8 '^PRECISION = '` prints one entry, and its summary lines read
   `/ 1`, `/ 1` and `/ 6`.
5. At the close, no entry outside `PRECISION` and the casts has a kernel that differs between
   two precisions: for every such entry, the emitted bytes of its coverage rows over two forms
   of one type are equal under the two-word layout. Yes or no; scored by row 662's first two
   coverage rows over a column of precision 7 and one of precision 9, in
   `sql/varka/emitted_bytes.json`.
6. Between this file and the milestone's close, at most 6 cells of the admission table move
   by syncs. Scored by `git log -p` of the file at the close.
7. Test time. The probe's 102 new form lines add under 0.2 s to `VarkaCastMatrixSuite`. The
   calibration table (`python3 dev/varka_calibration.py`) has the suite's one probe test at
   0.15 s for 169 pairs and every form (VARKA-328, ratio about 0.03), and 102 lines beside 393
   are about a quarter more. Scored by the suite's time in row 661's Build.

## 9. Who builds it

The row is a design, written by the planner, and it has no part to hand to a cheaper model.
Row 661 is not a fan-out row either (`m9/PLAN.md` section 3 names rows 667 to 669 and 672 to
674 as the fan-out). Rows 673 and 674 are the cheaper models' (G5). What this design does for
them is in 5.3: 58 tasks of one entry and one type, not 174.

## 10. Risks

1. **Spark's surface moves** between this file and row 661. Held by the design itself: the
   admission is Spark's answer, and this file's table is a prediction (section 8, item 3).
2. **Spark starts to accept an entry at one precision only.** Then the rule of 4.4 leaves the
   type out and the reason names the precisions. If such entries become common, two types hide
   real cells, and choice (a) should be read again. Held by prediction 3 and by the reason
   text; question 2 of section 13 is the rule.
3. **An entry that depends on the precision is missing from `PRECISION`,** or a cast pair
   from `reads_precision`. Its claim would then pass from one precision. Held by the commands
   of facts 7, 22 and 23 at each sync (4.4), and by the conformance fixture's three forms
   (section 7).
4. **The band's rows exist and test nothing,** because their values are all on the step. The
   census cannot see values. Held by the two fixture rules of section 7, which rows 674 and
   675 take into their sheets.
5. **The band refuses a row that does test the floor.** It reads a row's SQL by a few rules
   (4.4), and fails closed: a timestamp argument under a cast or a call, a cast of an
   expression, or a spelling of the call the parser gains later counts for nothing, and the
   claim stays fused in part. Nothing is then counted that is not shown. Held by rows 674 and
   675 writing the band's rows in the accepted shapes, which section 7 gives, and by the
   doctests of `nanos_bands` and `cast_bands`, which hold what is refused and what is taken.
6. **The owner chooses (a).** Section 6 is then replaced: the prototype of (a) exists, and
   row 661's plan is written from it. Rows 662 and 673 to 675 grow as 5.3 says.
7. **Row 654 lands differently from its planner's prototype.** Every old text of 6.1 is
   checked by the build, and each count by its command. Edit 38 does not depend on how row
   654's builder wrapped a comment.
8. **Row 653 splits the curated file and `casts.tsv`** into one file an entry before row 661
   starts. The one-off script of 6.3 then edits those files. Section 11.
9. **The one long becomes the default layout.** A fused cell would then hold for part of the
   range. Held by the rule of 4.7, which row 662's plan takes or answers.
10. **A sixth generated file.** The admission table is one more file that a regeneration
    writes and a merge driver merges. It is 132 lines and changes only at a sync or when an
    asked entry comes or goes.

## 11. Seams with the plans written beside this one

**Row 653** (the files every type row appends to, one file an entry). Assumed: when row 661
starts, the curated file is either one file, as today, or row 653's one file an entry with
the same seven fields a line. In the second case the one-off script of 6.3 runs over each
file, `asked_entries` reads them through `curated_sections` as the rest of the script does,
and `casts.tsv`'s 20 lines are written where row 653 puts a cast pair. This design does not
depend on which: it adds two types, not a file layout.

**Row 655** (the baselines, with the nanosecond classes). Assumed: a nanosecond baseline
class reads columns of precision 9, and adds a column of precision 7 only in the sections
that 4.4 names: `timestampadd` with the `NANOSECOND` unit, and the six casts. Every other
section's kernel is the same at the three precisions, so a second precision would time the
same code twice.

**Row 690** (`ToInstant` and `ToInstantKept`). Assumed: the ten zone pairs of 4.6 use its
nodes and row 682's on the microsecond word, as `m9/PLAN.md` 2.5 says ("an offset is whole
seconds, so the nanosecond word is untouched"). The one place where the second word changes
under a zone rule is a midnight inside a gap, which Spark reaches only from a `DATE`, whose
second word is 0 (`VARKA-679.md` 4.9). Nothing of row 690's names is fixed here.

**The merged plans this row meets.**

- `VARKA-654.md`: this design extends exactly what it makes (`MILESTONES[9]`, `legal`,
  `cast_kind`, `cast_proof`, `cast_rows`, `NANOS`). Nothing of it must change. Its 4.5 says
  "the nanosecond types join both lists with row 661", which edit 3 does. Its question 5
  kept the date functions out of `ltz`'s count; the owner has now counted them for every
  timestamp type, and 12.2 has the edits of `VARKA-654.md` that follow.
- `VARKA-656.md`: its registry has "two predicates (`isNtz`, `isLtz`); `isLtz` is false for the
  nanosecond types". This design keeps that: the two new ids are their own.
- `VARKA-679.md`: its 10.1 assumes "Row 660 decides how precisions 7 to 9 enter the census.
  The library does not depend on the answer". That holds for each choice. Nothing of it must
  change.

**Row 662** has no plan yet. What this row assumes of it is 4.7's rule and the registry
line of 6.6's last table.

## 12. Edits to other plans, made by this plan's own pull request

The pull request records the owner's answers of 27 November 2026, so row 660 goes from Filed to
Done in it. The 22 edits are also a script, `apply_section12.py`, which holds the same old and
new texts in a table; the lead applies them with it, and it changes nothing unless every old
text and anchor occurs exactly once. In a block below, the four leading spaces are the page's:
what follows them is the file's text, its own leading spaces included. Each old text below was
found exactly once in its file at `84d6b2aff10`, which is `origin/master` (`git -C
/home/max/proj/vecbricks-varka show origin/master:<file>`); a line is quoted whole, with its
leading spaces.

### 12.1 `sql/varka/plans/m9/PLAN.md`: seven edits

**1. Row 660's line (section 3).** The old text, which is part of the line:

    How precisions 7 to 9 enter the census. **Filed** (25 November 2026; the admission check:

The new text in its place, on the same line:

    How precisions 7 to 9 enter the census. **Done** (`VARKA-660.md`, 27 November 2026: a design with three choices and their counts; the owner chose (b) the same day, two census types, `ntz_ns` and `ltz_ns`, with the precision a band on `timestampadd` and on six cast pairs, and chose that the seven date functions that take a `DATE` count over them: 137 (entry, type) pairs and 20 cast pairs; row 661 builds it from the plan's section 6; filed 25 November 2026; the admission check:

**2. Decision 7 (section 1).** After the line that ends the decision,

       it.

two new lines:

       *27 November 2026:* the owner chose two types with a band, choice (b) of `VARKA-660.md`, and
       that the seven date functions that take a `DATE` count over the two types.

**3. Item 4 of 1.2.** Old, two lines:

       least two thirds of the nanosecond types' cast pairs row 661 adds (about 20) fused or
       declined with their reason.

New, three lines:

       least two thirds of the nanosecond types' cast pairs row 661 adds (20, `VARKA-660.md` 4.2)
       fused or declined with their reason. On the counts of `VARKA-660.md` 4.2 that is 52 of
       `ntz_ns`'s 69 pairs, 51 of `ltz_ns`'s 68, and 14 of the 20 cast pairs.

**4. Section 2.4, the bullet "The nanosecond types (row 661)".** Old, three lines:

      census sets it and does not rely on the default. The nanosecond types add about 20 cast pairs:
      with `DATE`, `TIME`, the two microsecond timestamps and each other. Row 681's
      register of upstream behaviours takes SPARK-56822, the preview, as a moving dependency.

New, three lines:

      census sets it and does not rely on the default. The nanosecond types add 20 cast pairs
      (`VARKA-660.md` 4.6): with `DATE`, `TIME`, the two microsecond timestamps and each other. Row
      681's register of upstream behaviours takes SPARK-56822, the preview, as a moving dependency.

**5. Section 2.5, the bullet "Precision".** After its last line,

      rows whose kernels are the same.

four new lines:

      *27 November 2026:* the owner chose two types with a band (`VARKA-660.md`): 137 (entry, type)
      pairs and 20 cast pairs, where six types give 411 and 84. The answer depends on the
      precision in `timestampadd` with the `NANOSECOND` unit and in six cast pairs. The seven date
      functions that take a `DATE` count over the two types, by his word of the same day.

**6. Row 654's line (section 3).** Old, part of the line:

    and 70 entries that admit the type

New:

    and 77 entries that admit the type

**7. Item 3 of 1.2.** After its last line,

       `m8/casts.tsv` with a temporal side, plus `TIMESTAMP_LTZ`'s pairs row 654 adds (23).

three new lines:

       *27 November 2026:* milestone 9's census counts the seven date functions that take a `DATE`
       over `TIMESTAMP_NTZ` and `TIMESTAMP_LTZ` too (`VARKA-660.md` 16): 7 pairs more in this
       item's count, and 7 more in item 1's, which then reads 77 entries.

### 12.2 `sql/varka/plans/m9/VARKA-654.md`: twelve edits

They make the seven date functions count for `ntz` and `ltz` in milestone 9's census, by the
owner's answer to question 8. `<the date>` in them is 27 November 2026. The file's other
mentions of the old numbers stay as registered, under the correction of edit 5: the house
does not retro-edit a prediction, and row 654's build restates each count by its command.

**1. Section 4.4, where a reader of the two summaries looks.** After the line that ends the
section's last bullet,

      first two in milestone 9's file.

four new lines:

    - **The seven date functions read two ways across the two milestones, on purpose.** Milestone
      8's files are frozen, so `--milestone 8 --summary` counts no timestamp for them; milestone
      9's summary counts `ntz` and `ltz` (4.7, rule 4). For this reason alone `ntz` admits 7 pairs
      more in milestone 9's summary than in milestone 8's.

**2. Section 4.7, a fourth rule.** After the line

    3. **A cast alias** (types `legal`) takes `ltz` as a source from `legal()`.

five new lines:

    4. **A date function that Spark takes over a timestamp through a cast to `DATE`** (`add_months`,
       `datediff`, `date_diff`, `date_add`, `dateadd`, `date_sub`, `unix_date`) gets `ntz,ltz` at the
       end of its types (the owner, 27 November 2026; `VARKA-660.md` 16). Milestone 8's census
       lists no timestamp for these seven, and stays so (4.4). Row 672 owes `ntz`, and row 668
       owes `ltz`.

**3. Section 4.7, the table of rows.** After the table's line that begins `| 671 |`, one new
line:

    | 668, and 672 for `ntz` | `add_months datediff date_diff date_add dateadd date_sub unix_date` (7) | rule 4. `ltz`: row 668, by the rule of this section for an entry that "lists `ntz` otherwise", a field of a timestamp, and beside `last_day`, `next_day` and `trunc`, which take a `DATE` the same way. `ntz`: row 672, "the existing temporal types' tail", named because its done-when is item 3 of `m9/PLAN.md` 1.2, which these pairs join |

**4. Section 4.7, the count.** Old, two lines:

    That is 62 entries on master `a0e3a62cf2b`: 28 by rule 1 (24 registered and 4 implicit), 24 by
    rule 2, 10 by rule 3. The implicit entries keep their names (`ntz_minus_ntz`, `ntz_plus_dt`,

New, three lines:

    That is 69 entries on master `a0e3a62cf2b`: 28 by rule 1 (24 registered and 4 implicit), 24 by
    rule 2, 10 by rule 3 and 7 by rule 4. The implicit entries keep their names (`ntz_minus_ntz`,
    `ntz_plus_dt`,

**5. Section 4.10, a correction.** After the line

      fused of 282 before and 210 of 297 after part b.

six new lines:

    - ***27 November 2026*, a correction: the date functions count for `ntz` and `ltz`** (4.7, rule
      4; `VARKA-660.md` 16). Part b's numbers, here and wherever this file repeats them, read: 77
      `ltz` pairs for 70; 1026 pairs for 1012; 53 pairs gained by the other types for 46, `ntz`
      12 of them for 5; 69 entries edited for 62; 27 no longer `done` for 23, the four more
      being `add_months`, `datediff`, `date_diff` and `unix_date`; 58 entries `done` for 62; and
      210 of 304 for 210 of 297 in item 3. The pairs fused stay 585.

**6. Section 4.12, the table's line of the date functions.** Old, the whole line:

    | a date function over a timestamp that milestone 8's census does not list for `ntz` (`date_add`, `add_months`, `datediff`, `unix_date`) | none: the census keeps milestone 8's reading (4.13, question 5) |

New:

    | a fused cell of a date function over a timestamp (4.7, rule 4) | 672 for `ntz`, 668 for `ltz` |

**7. Section 4.13, question 5.** After its last line,

       `DATE` parameter. Recommended: keep that reading for `ltz`; a row that fuses one adds it.

three new lines:

       *27 November 2026:* the owner chose the other answer, after he counted them for the
       nanosecond types (`VARKA-660.md` 13): the seven date functions, these four and `date_diff`,
       `dateadd` and `date_sub`, count for `ntz` and `ltz` in milestone 9's census (4.7, rule 4).

**8. Section 1, the check of G1.** Old, the whole line:

      pairs fused and admitted, `0 70` on master `a0e3a62cf2b`.

New:

      pairs fused and admitted, `0 77` on master `a0e3a62cf2b`.

**9. Section 5.2, step 4, what the script prints.** Old, the whole line:

       `a0e3a62cf2b` it prints one line, `62 entries edited, 23 of them no longer done, 16 added; 0

New:

       `a0e3a62cf2b` it prints one line, `69 entries edited, 27 of them no longer done, 16 added; 0

**10. Section 5.2, command 3's comment.** Old, part of the line:

    (the line itself is "  ltz     new         0 / 70" on a0e3a62cf2b)

New:

    (the line itself is "  ltz     new         0 / 77" on a0e3a62cf2b)

**11. Section 7, prediction P3.** Old, the start of the table's line:

    | P3 | `ltz` admits 70 pairs and fuses none | `  ltz     new         0 / 70` |

New:

    | P3 | `ltz` admits 77 pairs and fuses none | `  ltz     new         0 / 77` |

**12. Section 5.2, the script of step 4,** two insertions. After the line

        ZONE_FREE = {"656", "667"}  # their kernels read no zone: what is left is plumbing

five new lines:

        # The date functions that Spark takes over a timestamp through a cast to DATE. Milestone 8's
        # census lists no timestamp for them; milestone 9's counts them for both microsecond timestamps
        # (VARKA-660.md 16). ntz is row 672's, the existing temporal types' tail; ltz is row 668's, with
        # the fields.
        DATE_FUNCTIONS = "add_months datediff date_diff date_add dateadd date_sub unix_date".split()

And in `edit`, after the line

            types, result, need, rows, note = (first + i for i in (0, 1, 3, 4, 5))

twelve new lines:

            if name in DATE_FUNCTIONS and first == 1:
                if {"ntz", "ltz"} & set(fields[types].split(",")):
                    sys.exit(f"already has a timestamp: {name}")
                fields[types] += ",ntz,ltz"
                if fields[need] == "done":
                    fields[need] = "semantics"
                    UNDONE.append(name)
                held = [r for r in fields[rows].split(",") if r]
                fields[rows] = ",".join(held + ["672", "668"])
                add = "a timestamp through its date, the cast to DATE under the node: ntz is row 672's,"
                fields[note] = (fields[note] + "; " if fields[note] else "") + add + " ltz row 668's"
                return fields

**These edits were run.** Row 654's script, taken out of `VARKA-654.md` as merged, gives its
planner's curated file byte for byte. With the two insertions, on the same input, it prints
`69 entries edited, 27 of them no longer done, 16 added; 0 by default, 0 skipped, 0 absent`.
The census then passes `--check`; its summary reads `  ltz     new         0 / 77`,
`  ntz     existing   49 / 66`, `(entry, type) pairs: 1026, fused today 585` and `in scope, by
need: done 58, plumbing 33, semantics 97, ulp 10, hard 0`; and command 9 of `VARKA-654.md`
5.2, "every ltz cell names a row of milestone 9", still prints `0`. This row's own prototype
was then run on that census (section 3).

### 12.3 `sql/varka/plans/m9/VARKA-656.md`: three edits

It quotes `ltz`'s count as `N`, "70 on the plan's commit". `N` is 77 with the date functions.
Its `23 / N` keeps its 23: row 656 fuses no date function.

**1. Part A, B0, line 5's comment.** Old, part of the line:

    note N (70 on the plan's commit)

New:

    note N (77 on the plan's commit)

**2. The predictions, item 3.** Old, two lines:

       read `0 / N`: the 22 cells of 4.8 and the alias's identity. With `N` at 70, as row 654's
       plan has it on the plan's commit, that is 23 of the 53 that G1's three quarters ask.

New, two lines:

       read `0 / N`: the 22 cells of 4.8 and the alias's identity. With `N` at 77, as row 654's
       plan has it with the date functions, that is 23 of the 58 that G1's three quarters ask.

**3. The seams' table.** Old, the start of the line:

    | the summary's line `  ltz     new         0 / N`, `N` 70 on the plan's commit |

New:

    | the summary's line `  ltz     new         0 / N`, `N` 77 on the plan's commit |

### 12.4 `VARKA-690.md`, which is not merged: nothing moves

It quotes `VARKA-654.md` 4.10's 585 pairs fused, and "585 to 590" after its own casts. The
date functions add admitted pairs and no fused one: the census with them still reads `fused
today 585` (12.2). So no number of `VARKA-690.md` moves. The lead applies nothing there.

### 12.5 The record of what was not chosen

Had the owner chosen (c), the edits of 12.1 would say "two types and no band", with the same
numbers. Had he chosen (a), they would say "six types, one a kind and a precision", with 84
cast pairs for 20, 411 pairs for 137, and 52, 52, 52, 51, 51 and 51 of 69, 69, 69, 68, 68 and
68 for item 4. Had he left the date functions out of the nanosecond types, the pairs would be
123, and item 4 would read 47 of 62 and 46 of 61. Had he left them out of `ntz` and `ltz`,
12.2 and 12.3 would not be, and the census would read two ways (section 16).
## 13. Questions for the owner: what he decided, and what is open

**Decided on 27 November 2026.**

1. **How do precisions 7 to 9 enter the census?** The owner: "7. b". Choice (b): two census
   types, `ntz_ns` and `ltz_ns`, with the precision a band. The recommendation was (b).

| choice | census types | (entry, type) pairs | legal cast pairs | what the census really checks about the precision | what it costs the rows after it |
| :--- | :--- | ---: | ---: | :--- | :--- |
| **(b), chosen** | 2: `ntz_ns`, `ltz_ns` | 137 (69 and 68) | 20 | `timestampadd`: a coverage row whose call has the `NANOSECOND` unit and a bare column of each precision as its timestamp. The six casts that read the target's precision: a coverage row that casts a bare column of the source to the target at each precision. Strings and comments do not count. Everything else: one row at any precision. It cannot check the rows' values: the fixture rules of rows 674 and 675 carry them | 107 coverage rows for item 4's pairs, and up to 32 for the casts; 246 lines of the census script for the band |
| (c) | 2: `ntz_ns`, `ltz_ns` | 137 (69 and 68) | 20 | one coverage row at any precision for every entry and cast pair; for a cast pair, also that the probe's line of each precision compiles. `timestampadd` and the six casts can count fused with no kernel run at precision 7 or 8 | 103 coverage rows, and up to 20 for the casts |
| (a) | 6: `ntz_ns7` to `ltz_ns9` | 411 (69 or 68 each) | 84 | a coverage row over a column of each of the six types, for every entry; for `timestampadd`, of any unit | 309 coverage rows, and up to 78 for the casts; 274 pairs and 64 cast pairs repeat a kernel; 174 fan-out tasks in place of 58 |

3. **Do the seven date functions count over a nanosecond timestamp?** The owner: "8. are
   counted". `add_months`, `datediff`, `date_diff`, `date_add`, `dateadd`, `date_sub` and
   `unix_date` count over `ntz_ns` and `ltz_ns`. The recommendation was no, to read as row
   654 read `ltz`; the owner chose the other answer, and this file is written on his. It
   adds 14 pairs.
6. **The census does not count a layout as a type, and a fused cell holds over the type's
   whole range** (4.7). The owner: yes. It asks of row 662 that its default arm answers the
   whole range.
8. **Do the seven date functions count for `ntz` and `ltz` too,** so that every timestamp
   type reads the same way? The owner: yes. It adds 7 pairs to each in milestone 9's census,
   by the edits of `VARKA-654.md` in 12.2. Milestone 8's census is not touched.

**Open when the plan was written**, each with its recommendation; the plan is written on the
first answer. *27 November 2026, later:* the owner approved the plan as written, so the first
answer of each stands as his.

2. **A type is admitted for an entry when Spark accepts it at all three precisions** (4.4).
   Recommended: yes. The other rule, any one precision, would count a cell that fails for two
   thirds of the type. No entry is affected today.
4. **The names and their order**: `ntz_ns` and `ltz_ns`, after `ltz`. Recommended: yes; they
   are `m9/PLAN.md` 2.5's names, and each fits the summary's seven columns.
5. **Which row owes which cell** (4.5): the 26 zone-free entries, `hour` and `extract` to row
   662 first; `time_bucket` and the seven date functions to row 673 with the fields; the
   three sums with an interval to row 675. Recommended: as 4.5. A row that disagrees moves
   its cell in its own pull request.
7. **Item 4 of `m9/PLAN.md` 1.2 is read on two types**: 52 of `ntz_ns`'s 69 pairs, 51 of
   `ltz_ns`'s 68, and 14 of the 20 cast pairs. Recommended: yes; it follows from the
   decisions above. Edit 3 of 12.1 writes these numbers into the item.

## 14. The house rules that bind this row

From `sql/varka/AGENTS.md`:

- "Predictions written before a measurement stay in the file after it, scored honestly - the
  project keeps count. Do not retro-edit a task file to look prescient; add the correction and
  say what it corrects." Section 8's counts stay as registered; row 661 scores them.
- "The generated files every type row rewrites (`coverage.json`, `emitted_bytes.json`,
  `width_audit.json`, `types.tsv`, the census and cast tables, the docs blocks) are never
  merged by hand." The admission table is one more of them.
- "A pull request stays under 1000 changed lines of hand-written files - source, plans and
  notes, and hand-curated data such as `census_curated.txt`". The owner lifted the cap for a
  plan-only pull request; it binds every pull request of row 661.

What not to do with this file:

- Do not build section 6 before row 654's part b is merged.
- Do not type `ntz_ns` or `ltz_ns` into an entry's types in the curated file. The group `NS`
  there, as `unix_nanos` has it, only asks Spark; the admission table is the only source of
  what is admitted.
- Do not write a line of the admission table by hand: `--asked` writes its lines and the
  suite its cells.
- Do not add a census type for a layout or for an option's arm.
- Do not read this file's counts as the day's: restate each by its command.

## 15. Outcome

The owner chose on 27 November 2026, the day the plan was written. Choice (b), in his words
"7. b". For the date functions over the nanosecond types, "8. are counted". Later the same
day: yes, they count for `ntz` and `ltz` too; and yes, a layout is no census type and a fused
cell holds over the whole range. The row's done-when, "the plan states the census types and
the cast pairs each choice gives, with their counts, and the owner has chosen", is met by
this file's own pull request, which sets the row to Done (12.1).

What the answers changed in this file. The choice of (b) changed nothing of sections 4 to 6,
which were written on it. The date functions changed the admission of 4.5 (66 entries asked
for 59, and row 673 owes the seven) and the counts: 137 pairs for 123, 69 and 68 a type for
62 and 61, 52 and 51 for item 4 where it was 47 and 46, 107 coverage rows for 97. Counting
them for `ntz` and `ltz` changed row 654's plan (12.2), the base of this file's counts (1026
pairs after row 654 for 1012, so 1168 after row 661) and the reason the seven are asked: they
list a timestamp. The script's 38 edits did not change. The predictions of section 8 are
registered on the new numbers; nothing was built between, so none was scored.

## 16. The date functions over `ntz` and `ltz`

**Decided: they count** (section 13, question 8), so that every timestamp type of milestone
9's census reads the same way.

| | row 654 as merged | with the date functions (12.2) |
| :--- | ---: | ---: |
| `ltz` pairs admitted | 70 | 77 |
| `ntz` pairs admitted | 59 | 66 |
| all (entry, type) pairs after row 654 | 1012 | 1026 |
| pairs fused after row 654 | 585 | 585 |
| entries row 654's script edits; of them no longer `done` | 62; 23 | 69; 27 |
| entries with need `done` after row 654 | 62 | 58 |
| pairs over `date`, `ntz`, `time`, `ym` and `dt`, fused of admitted (item 3 of 1.2) | 210 of 297 | 210 of 304 |
| all pairs after row 661 | 1154 | 1168 |
| `ntz_ns` and `ltz_ns` pairs | 69 and 68 | 69 and 68 |

Every number of the right column was printed by the two prototypes, row 654's and then this
row's on top of it (section 3, 12.2).

**Which rows owe the new cells.** `ltz`: row 668, by row 654's own rule for an entry on no
list. Once the seven list `ntz`, that rule's line "it lists `ntz` otherwise" gives 668, "a
field of a timestamp", and 668 already has `last_day`, `next_day` and `trunc`, which take a
`DATE` in the same way. `ntz`: row 672, a named row, because row 654's rule speaks of `ltz`
alone. Row 672 is "Fan-out: the existing temporal types' tail", and its done-when is item 3
of 1.2, the item these 7 pairs join. `ntz_ns` and `ltz_ns`: row 673 (4.5). Each cell is
claimed with the mark `~` and a quoted coverage row.

**Milestone 8's census is not touched.** Its files are frozen at its close, and there the
seven entries keep milestone 8's reading: a `DATE` and no timestamp. A reader who holds the
two summaries side by side finds the reason in `VARKA-654.md` 4.4, where edit 1 of 12.2 puts
it: `ntz` admits 7 pairs more in milestone 9's summary than in milestone 8's for this reason
alone.

**The record of the other answer.** Had the owner said no, nothing of `VARKA-654.md` would
change, and the census would read two ways. The one-off script's guard (6.3) would then write
`NS` into the types of the seven and this sentence into the note of each, where `census.tsv`
shows it:

    a nanosecond timestamp through its date, the cast to DATE under the node; the microsecond timestamps are not listed here (VARKA-654.md 4.13, question 5)

That path was run on the prototype too: it gives the same 69 and 68 pairs, with 1154 pairs
in all.
