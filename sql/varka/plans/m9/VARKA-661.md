# VARKA-661: The census and the cast matrix with the nanosecond types

*Planned on 27 November 2026. The plan stands alone: a builder needs this file, the repository's
`AGENTS.md` files and `sql/varka/plans/m9/VARKA-660.md`, whose section 6.1 holds the text of the
census script's edits and is read by a tool this plan gives. The row is not started: milestone
9 waits for the owner's word (`m9/PLAN.md` decision 18), and this row also waits for row 654's
two parts, which wait for milestone 8's close (decision 11). The code facts were checked on
master `e0aee0a14b1`, in `/home/max/proj/varka-m9-read-w`. Every count below is a prediction,
computed on a scratch copy of the census as row 654's part b leaves it (the copy
`VARKA-660.md` 3 describes, with `unix_date` over a `DATE` alone, as edit 4 of section 12
leaves it): the four parts of section 5 were applied to it in order, and each state passes
the script's doctests and `--check`; 5.0 says how the last state differs from row 660's
prototype. The planner ran no JVM: the Java and the Scala of this plan were never compiled.
Spark's analyzer was asked once, by the lead's probe of 27 November 2026 (section 3, "The
lead's probe"); where it measured a prediction of this plan, the plan says so and keeps the
prediction as registered. Each sheet says what the builder does where the compiler or
Spark answers otherwise. The owner answered the plan's four questions on 27 November 2026
(section 13), and the plan is written on his answers: a nanosecond type is admitted for an
entry where Spark takes it under at least one of the two ANSI settings.*

## 1. What this row is

**The row's text** (`m9/PLAN.md` section 3): "The census and the cast matrix with the nanosecond
types." Its done-when: "the two types are census types as row 660 decides; each entry's
admission is read from Spark's own type check with the flag on; an entry Spark rejects carries
that reason; and the cast table holds the nanosecond pairs".

In plain words. Spark has two nanosecond timestamp classes, `TIMESTAMP_NTZ(p)` and
`TIMESTAMP_LTZ(p)` with `p` of 7, 8 or 9, behind a preview flag. Row 660 decided that they are
two census types, `ntz_ns` and `ltz_ns`. This row builds that: the two types enter the type
registry, still declined; the cast matrix gains their 20 pairs; a new suite asks Spark's
analyzer, with the flag set on, whether each census entry that takes a timestamp takes a
nanosecond one, and commits the answers as a table; the census admits the two types for an
entry from that table alone, where Spark takes the type under at least one ANSI setting; and
the one entry and the six cast pairs whose answer depends on the precision carry a band. No
kernel is built and no emitted byte moves.

**What must be merged before it starts.**

| what | why | how the builder checks it |
| :--- | :--- | :--- |
| this plan on master | the build cites it and writes its outcome into it | `test -f sql/varka/plans/m9/VARKA-661.md && echo merged` prints `merged` |
| milestone 8's close | `m9/PLAN.md` decision 11, through row 654 | `git tag -l varka-m8` prints `varka-m8` |
| row 654, parts a and b | milestone 9's files, `MILESTONES[9]`, `ltz`, `NANOS`, `VarkaCensusFiles` | `grep -c '^CURRENT = 9$' dev/varka_census.py` prints `1`; `grep -P "^TIMESTAMP\t" sql/varka/types.tsv \| cut -f3` prints `ltz` |
| row 660, the design | **Done**: the plan is `VARKA-660.md` | `grep -c '^### 6.1 The script' sql/varka/plans/m9/VARKA-660.md` prints `1` |
| row 663, the sync | **Done**, #1773: Spark's surface of the sync's day | `git merge-base --is-ancestor d1286ba2c16 HEAD; echo $?` prints `0` |

Row 656 (`TIMESTAMP_LTZ` on the long lane) and row 653 (the shared files) are not needed and
do not block: 5.0 says what each sheet does where they have merged first. Row 696 (the
census's files, one file an entry) must not have merged: part A's B0 stops if it has
(section 11).

**What it gives the rows after it.**

| row | what it reads of this row | where |
| :--- | :--- | :--- |
| 699, the nanosecond layout | the two registry ids, declined, with three forms each; the 28 entries and the two casts whose cells name row 699 first | `VarkaTypeId`, `VarkaTypes.entry`; `census_curated.txt`, `casts.tsv` |
| 673, 674, 675 | the cells and the cast pairs that name each as the row that owes them; the band's rule for `timestampadd` and for six cast pairs; for a cell that Spark takes under one ANSI setting alone, the clause that says so and what a fused claim over it owes (4.3) | `census.tsv` columns `rows` and `reason`; `casts.tsv`; `PRECISION`, `reads_precision`, `alone` in `dev/varka_census.py` |
| 658, the fan-out's template | for one entry and one nanosecond type: whether Spark takes it, under which ANSI settings, and the row that owes it (section 11) | `census.tsv` columns `types` and `reason` |
| 677, the close | the denominators of item 4 of `m9/PLAN.md` 1.2 | `python3 dev/varka_census.py --summary` |
| 681, the register of upstream behaviours | the pinning test of SPARK-56822 and SPARK-60335 | `VarkaNanosAdmissionSuite` |
| every upstream sync after it | the command that says which cells Spark moved | 4.3, "When Spark moves" |

**The goals it serves** (`m9/PLAN.md` 1.1), each with the item of 1.2 it moves.

- **G4**, directly: "**G4. Nanosecond `TIMESTAMP_NTZ` and `TIMESTAMP_LTZ`, precision 7 to 9
  (phase 2).** Admitted while Spark's `spark.sql.timestampNanosTypes.enabled` is on, as Spark
  itself admits them; the functions Spark accepts over them fuse, and the casts among `DATE`,
  `TIME`, the microsecond and the nanosecond timestamps." Item 4 of 1.2: "**G4:** at least three
  quarters of each nanosecond type's pairs fused under the flag, and at least two thirds of the
  nanosecond types' cast pairs row 661 adds (20, `VARKA-660.md` 4.2) fused or declined with
  their reason." This row makes both denominators real. A reader checks it after part C:
  `python3 dev/varka_census.py --summary | grep -E '^  (ntz_ns|ltz_ns) '` prints the two lines,
  and `awk -F'\t' '$1 ~ /_ns$/ || $2 ~ /_ns$/' sql/varka/plans/m9/casts.tsv | wc -l` prints `20`.
- **Item 7 of 1.2**, directly: "Every pair left unfused carries its reason in the census: a
  declined band, an owner's decision, a function Spark itself rejects over the type, or a named
  row of the next milestone." After part C a function Spark rejects under both ANSI settings
  carries `Spark rejects <type>: <condition>` in the census's reason column, and every other
  cell of the two types names row 699, 673, 674 or 675. Check: 5.3, commands 5 and 6.
- **G5**, only through rows 658, 673 and 674: a fan-out task is one entry and one type, and
  this row fixes which (entry, type) cells exist and who owes each.
- **G6 and G7**, only through later rows: the post quotes the census, and G7's nanosecond bars
  are read on row 655's classes.
- **G1 and G3 are moved, downward, by a counting rule** (4.5): `cast` and `try_cast` count a
  source type fused only when every legal pair from it fuses, and each of `date`, `ntz`, `time`
  and `ltz` gains two legal pairs that nothing fuses yet. The owner accepted it (section 13,
  question 1).
- **G2** is not served.

## 2. What an agent must know

**The census.** `dev/varka_census.py` reads every name of Spark's function registry and writes
one line a name into `sql/varka/plans/m9/census.tsv`. A name is *in scope* when its inputs and
its result can all be census types. For each name in scope the *curated file*,
`census_curated.txt`, holds the types it admits, its result, the types Varka fuses today, the
*need* and the *rows* that owe what is not fused. A **census type** is a short name of a Spark
type; the names and their order come from the type registry (`VarkaTypes`, Java), through its
committed table `sql/varka/types.tsv`. A **pair** is one (entry, type). `--summary` prints, for
each type, the pairs fused of the pairs admitted. `--check` runs the script's doctests and
fails where a committed file differs from a fresh one.

**A form** is one Spark `DataType` of a census type that the tests probe
(`VarkaTypes.Info.forms()`): `time` has three, and each nanosecond type will have three, one a
precision.

**The cast matrix.** `casts.tsv` has one line for each ordered pair of census types that
Spark's `Cast` allows, with its legality under LEGACY, ANSI and TRY, its kind, its proof and
its rows. The legality is the script's function `legal()`, and `VarkaCastMatrixSuite` holds it
to `Cast.canCast`, `Cast.canAnsiCast` and `Cast.canTryCast` over every pair and every form.
**The probe**, `casts_fused.tsv`, written by the same suite, has one line for each legal cast
between two forms, with the compiler's answer in each mode. **The suites' record**,
`casts_suites.tsv`, is written by Spark's three cast suites run under Varka in sql/core.

**A cast entry** is `cast`, `try_cast` or a cast alias (`date`, `time`, `timestamp` and the
others of `CAST_ENTRY_MODES`). Its types are the cast table's, not the curated file's.

**Spark's nanosecond timestamps** (SPARK-56822, a preview). Two classes of `sql/api`,
`TimestampNTZNanosType(p)`, a local date and time, and `TimestampLTZNanosType(p)`, an instant.
A value of either, at any precision, is one `TimestampNanosVal`: a long of microseconds and a
short of nanoseconds within the microsecond. `AnyTimestampNanoType` is the abstract type that
accepts both at every precision. The types are gated by `spark.sql.timestampNanosTypes.enabled`
(fact 2). Since SPARK-60335 a function with no nanosecond support rejects such an argument in
the analyzer, in place of narrowing it.

**The flag is not read where a column's type is built in code.** It is read by the parser of
a type's name, by `Cast`'s type check and at schema entry points (fact 3). So the suite of
this row sets the flag on in the configuration its analyzer and parser read, and one of its
tests shows that the setting is the one that is read.

**Spark's own type check** has three places that can reject a call over a type, and a tool
that asks only one of them misses the others: a function's builder or an analyzer rule, which
raise while the plan is analyzed; an expression's `checkInputDataTypes`, which
`CheckAnalysis.checkAnalysis` reports after the rules; and a check of the whole plan, which
`checkAnalysis` also runs (the subtraction of two nanosecond timestamps is rejected there,
fact 8). So this row asks by analyzing a one-column projection and then calling
`checkAnalysis` on it, and reads the *condition* (the error class) of what is raised.

**The control.** The same call over a column of a microsecond timestamp, `TIMESTAMP_NTZ` or
`TIMESTAMP`, under the same ANSI setting. Where Spark takes the call over one of the two and
rejects it over a nanosecond column, the only thing that differs is the column's type, so the
rejection is the type's and not a fault of the call's text (4.3).

**The admission table**, `sql/varka/plans/m9/nanos_admission.tsv`, holds Spark's answers: one
line for each *asked entry* (a curated entry that is not a cast entry and whose types hold a
timestamp) and each of the two types, with an answer at each precision and the census name of
the result. `python3 dev/varka_census.py --asked` writes its lines; the suite
`VarkaNanosAdmissionSuite` writes its answers; the census reads it.

**A band** is a part of a census type that the census tells apart without making it a type.
For the nanosecond types the band is the precision, and it is asked only of the entry
`timestampadd` and of six cast pairs (`VARKA-660.md` 4.4).

**The names this row reads.**

| name | where | meaning |
| :--- | :--- | :--- |
| `MILESTONES[9]`, `NANOS`, `NAME_EXCLUSIONS`, `GROUPS`, `FILES`, `READER_NAMES` | `dev/varka_census.py`, after row 654 | milestone 9's types; the reason of `timestamp_nanos` and `unix_nanos`; the exclusions by name; the groups of the curated file; the census's file names; the pattern that finds a reader's milestone |
| `legal`, `cast_kind`, `ltz_cast_kind`, `cast_proof`, `cast_rows`, `pair_credit`, `matrix_fused`, `CAST_ENTRY_MODES`, `row`, `build`, `summary`, `column_type` | `dev/varka_census.py` | the cast matrix's columns; a cast entry's credit; one entry's row and its claim rules |
| `VarkaTypeId`, `VarkaTypes.entry`, `VarkaTypes.idOf`, `Admission.Declined`, `Conformance.NotLanded`, `Storage.Plain` | catalyst, `codegen/varka/` | the registry: an id, its entry, the lookup from a `DataType` |
| `VarkaSqlResolve` | catalyst tests, Scala, `codegen/varka/` | the tests' one resolver of SQL text over declared columns, through `SimpleAnalyzer` |
| `VarkaCensusFiles.of(home, name)` | catalyst tests, Java, from row 654 | a file of the current milestone's census |
| `VarkaCastMatrixSuite.fusionMoves`, `castExpression`, `committed`, `Legality` | catalyst tests, Java | the lines two versions of a file do not share; the probe's cast; `casts.tsv`'s legality |
| `VarkaTypeConformance.CAST_TESTS`, `castTo(String)` | catalyst tests, Java | one cast test for each census type |
| `SimpleAnalyzer.execute`, `SimpleAnalyzer.checkAnalysis`, `SparkThrowable.getCondition` | Spark | the analyzer's rules; the check of an analyzed plan; an error's class |

**The names this row adds.**

| name | part | where | meaning |
| :--- | :--- | :--- | :--- |
| `VarkaTypeId.TIMESTAMP_NTZ_NANOS`, `TIMESTAMP_LTZ_NANOS` | A | `VarkaTypeId.java` | the two ids, after `TIMESTAMP` |
| the census names `ntz_ns`, `ltz_ns` | A | `VarkaTypes`, `sql/varka/types.tsv` | `TIMESTAMP_NTZ(p)` and `TIMESTAMP_LTZ(p)`, `p` from 7 to 9 |
| `NS`, `LTZ_KIND`, the group `NS`, `ns_cast_kind(src, dst)` | A | `dev/varka_census.py` | the two names; the types that hold an instant; the kind of a cast pair with a nanosecond side |
| `castToTimestampNtzNanos()`, `castToTimestampLtzNanos()` | A | `VarkaTypeConformance` | the two cast tests |
| `nanosPairs()` | A | `VarkaCastMatrixSuite` | the 20 pairs, pinned |
| `VarkaSqlResolve.check(text, columns)`, `VarkaChecked(dataType, condition, operands)` | B | catalyst tests, Scala | Spark's answer to one call: its result's type and the timestamp types its nodes read two operands at, or the condition it is rejected with |
| `VarkaNanosAdmissionSuite`, its `CALLS`, `MIXED_CALLS`, `ask`, `controlTakes`, `rejection`, `cell`, `admits`, `answer`, `answered` | B | catalyst tests, Java | the suite that asks Spark and writes the table's answers; 4.3's rule, one method a step |
| `VARKA_NANOS_ADMISSION_REGEN` | B | the environment | set to `true`, the suite writes the file |
| `sql/varka/plans/m9/nanos_admission.tsv` | B | the plans folder | the admission table |
| `NANOS_ADMISSION`, `ADMISSION_HEADER`, `ACCEPTED`, `UNASKED`, `ADMISSION_REMEDY`, `nanos_admission(root)`, `asked_entries()`, `admission_skeleton`, `admission_errors`, `admit_nanos`, the option `--asked` | B | `dev/varka_census.py` | the table's path, header and cells; its reader; the entries it has lines for; its text; its errors; the rule that admits a type from it |
| `one_setting(answer)`, `alone(name, admission)`, `names_decline(note, setting)` | C | `dev/varka_census.py` | a cell that one ANSI setting alone rejects; for an entry, the admitted types with such a cell, each with its clause of the reason column; whether an entry's note names the suite and the test of a decline under a setting |
| `PRECISION`, `PRECISION_BANDS`, `CAST_TARGET`, `reads_precision`, `sql_code`, `sql_calls`, `bare_column`, `nanos_bands`, `cast_bands`, `precision_lines` | D | `dev/varka_census.py` | the band (`VARKA-660.md` 4.4) |

## 3. The admission check

Each fact was checked on master `e0aee0a14b1`, in `/home/max/proj/varka-m9-read-w`. Below,
`C=sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst`,
`A=sql/api/src/main/scala/org/apache/spark/sql`, `E=$C/expressions/datetimeExpressions.scala`
and `G=sql/core/src/test/resources/sql-tests`. In a table of this plan a `\|` inside a command
stands for `|`, escaped for the table: type `|`. Each such command runs as written with that
one replacement made. Where the `|` is an alternation of a pattern and not a pipe, the `grep`
has `-E`, so no command of a table needs a `\|` typed.

| # | fact | command | prints |
| ---: | :--- | :--- | :--- |
| 1 | The two classes, each with precisions 7 to 9, default 9 | `grep -h -E 'val M.._PRECISION\|val DEFAULT' $A/types/TimestampNTZNanosType.scala $A/types/TimestampLTZNanosType.scala` | six lines: `MIN_PRECISION: Int = 7`, `MAX_PRECISION: Int = 9` and `DEFAULT_PRECISION: Int = NANOS_PRECISION`, twice |
| 2 | The flag: its key, internal, off in production and on under tests | `grep -A15 'val TIMESTAMP_NANOS_TYPES_ENABLED' $C/../internal/SQLConf.scala \| grep -c -E 'buildConf\("spark.sql.timestampNanosTypes.enabled"\)\|internal\(\)\|createWithDefaultFunction\(\(\) => Utils.isTesting\)'` | `3` |
| 3 | Who reads the flag: the parser of a type's name and of a literal, `TypeUtils.failUnsupportedDataType` (called by `Cast.checkInputDataTypes` and by schema checks), and the XML reader. No rule rejects a column whose type was built in code | `git grep -c 'timestampNanosTypesEnabled' -- sql/api/src/main sql/catalyst/src/main` | six files: `DataTypeErrors.scala:1`, `SqlApiConf.scala:2`, `AstBuilder.scala:1`, `TypeUtils.scala:1`, `XmlInferSchema.scala:2`, `SQLConf.scala:1` |
| 4 | With the flag off the parser raises `FEATURE_NOT_ENABLED` for `TIMESTAMP_NTZ(8)` | `grep -A8 'def timestampNanosTypesNotEnabledError' $A/errors/DataTypeErrors.scala \| grep -c 'errorClass = "FEATURE_NOT_ENABLED"'` | `1`; `parseTimestampNtzNanosPrecision` in `DataTypeAstBuilder.scala` calls `checkTimestampNanosTypesEnabled()` after its range check |
| 5 | The datetime expressions name `AnyTimestampNanoType` 52 times: an expression accepts the types by listing it in `inputTypes`, or through a cast to `DATE` that coercion adds | `grep -c 'AnyTimestampNanoType' $E` | `52` |
| 6 | "Spark's set of functions over them moved in six commits since May": the coercion rules, which decide for every function with no check of its own, changed six times | `git log --since=2026-05-01 --format='%h %s' -G'Nanos' -- $C/analysis/TypeCoercionHelper.scala $C/analysis/AnsiTypeCoercion.scala $C/analysis/TypeCoercion.scala` | six lines, listed below the table |
| 7 | Beside them, 14 commits since May gave single functions a nanosecond input type | `git log --since=2026-05-01 --format=%h -G'AnyTimestampNanoType' -- $E \| wc -l` | `14` |
| 8 | The subtraction's rejection is a check of the plan, not an expression's type check | `grep -c 'NanosTimestampSubtractionCheck' $C/analysis/CheckAnalysis.scala`; `grep -c 'errorClass = "UNSUPPORTED_FEATURE.TIMESTAMP_NANOS_SUBTRACTION"' $C/analysis/NanosTimestampSubtractionCheck.scala` | `1`; `1` |
| 9 | The public methods a test can ask through | `grep -c 'def checkAnalysis(plan: LogicalPlan): Unit' $C/analysis/CheckAnalysis.scala`; `grep -c 'override def getCondition: String = errorClass.orNull' $A/AnalysisException.scala`; `grep -c '^object SimpleAnalyzer extends Analyzer($' $C/analysis/Analyzer.scala` | `1`; `1`; `1` |
| 10 | The analyzer picks its coercion rules by the ANSI setting on every run | `grep -c '  override def batches: Seq\[Batch\] = earlyBatches' $C/analysis/Analyzer.scala`; `grep -c 'private def typeCoercionRules(): List\[Rule\[LogicalPlan\]\] = if (conf.ansiEnabled)' $C/analysis/Analyzer.scala` | `1`; `1`: `batches` is a `def`, so a suite that changes the setting between two calls gets each setting's rules |
| 11 | The tests' resolver analyzes one projection over declared columns and does not check it | `grep -c 'SimpleAnalyzer.execute(Project(Seq(Alias(bound, "a")()), LocalRelation(columns)))' sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaSqlResolve.scala`; `grep -c 'checkAnalysis' <the same file>` | `1`; `0` |
| 12 | `Cast`'s cases for the types are the same lines in `canCast` and in `canAnsiCast`; no case has a number, a `BOOLEAN` or an interval on the other side | `grep -c 'case (_: AnyTimestampNanoType, _: AnyTimestampNanoType) => true' $C/expressions/Cast.scala`; `grep -c -E 'NumericType, _: AnyTimestampNanoType\|AnyTimestampNanoType, _: NumericType\|BooleanType, _: AnyTimestampNanoType\|AnyTimestampNanoType, BooleanType' $C/expressions/Cast.scala` | `2`; `0`. 4.4 has the reading |
| 13 | `canTryCast` is `canAnsiCast` for every type that is not an array, a map or a struct | `awk '/def canTryCast/{f=1} f && /case _ =>/{getline; print; exit}' $C/expressions/Cast.scala` | `      Cast.canAnsiCast(from, to)` |
| 14 | The registry holds neither type, and the enum ends with `TIMESTAMP` | `grep -c 'Nanos' sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaTypes.java`; `grep -c '^  TIMESTAMP$' <the folder>/VarkaTypeId.java` | `0`; `1` |
| 15 | Four switches have one case an id and no `default` | `git grep -l 'case TIMESTAMP ->' -- sql/catalyst/src sql/core/src` | `VarkaConditionCompiler.java`, `VarkaTypes.java`, `VarkaFuzzTypes.java`, `VarkaLaneArrow.java` |
| 16 | The fuzz descriptor takes a declined type's reason from the registry and throws for a type that is not declined | `grep -c 'throw new IllegalStateException(id + " is not declined in the registry");' sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaFuzzTypes.java` | `1`, in `declined(VarkaTypeId)` |
| 17 | `VarkaTypesSuite` holds an entry's storage to the Arrow type of `ArrowUtils.toArrowType`, which for both classes is a 64-bit nanosecond timestamp | `grep -c 'new ArrowType.Timestamp(TimeUnit.NANOSECOND' $A/catalyst/types/ops/TimestampNanosTypeApiOps.scala` | `2`; the cache's struct of two vectors is another mapping, `toTimestampNanosStructField` under `losslessInternalTypes` |
| 18 | A conformance cast test is cancelled, before it parses its target, where `casts.tsv` has no pair from the suite's type | `grep -c 'assumeTrue(pair.isPresent(), "casts.tsv has no cast from "' sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaTypeConformance.java` | `1`, in `castTo`, above `DataType.fromDDL(target)` |
| 19 | Spark's own golden files set the flag themselves and ask under both ANSI settings | `grep -c '^--SET spark.sql.timestampNanosTypes.enabled=true$' $G/inputs/timestamp-ntz-nanos.sql`; `grep -c '^SET spark.sql.ansi.enabled=' $G/inputs/timestamp-ntz-nanos.sql` | `1`; `10` |
| 20 | `timestampadd` with the `NANOSECOND` unit is rejected over a microsecond timestamp, so it cannot be the call the suite's control uses | `grep -A7 "^SELECT timestampadd(NANOSECOND, 1, TIMESTAMP_NTZ '2020-01-01 00:00:00')" $G/results/timestamp-ntz-nanos.sql.out \| grep -c 'INVALID_PARAMETER_VALUE.DATETIME_UNIT'` | `1` |
| 21 | `time_bucket` with two arguments builds an origin of the timestamp's own type, which its type check requires | `grep -c 'TimeBucket(bucketSize, ts, defaultOrigin(tsType))' $E` | `1` |
| 22 | The generated files' three lists know the cast tables by path or by name | `grep -c 'plans/m8/casts' sql/varka/.gitattributes dev/varka_regen_generated.sh`; `grep -c '"casts_suites.tsv": 6,' dev/varka_generated_merge.py` | `.gitattributes:3`, `varka_regen_generated.sh:2`; `1`. Row 654 turns `m8` to `m9` in the first two |
| 23 | Today's summary, before row 654 | `python3 dev/varka_census.py --summary \| grep -E '^in scope:\|pairs: \|^cast pairs'` | `in scope: 182 entries (156 classes)`, `(entry, type) pairs: 896, fused today 588`, `cast pairs over the 13 types: 169 ordered, 106 legal` |

**The six commits of fact 6**, oldest first, each with what it changed for the set of
functions:

| commit | issue | what it changed |
| :--- | :--- | :--- |
| `fd2080cdde7` | SPARK-57454 | `findWiderDateTimeType` covers the nanosecond types: a comparison, `coalesce`, `IN`, `CASE WHEN`, `greatest` and a set operation over two timestamps of mixed precision or kind resolve to the wider nanosecond type, where they failed before |
| `3791cb90687` | SPARK-57303 | the up-cast and store-assignment rules: a cast to a higher precision is an up-cast and one to a lower precision is not; `TimestampFamily` comes in |
| `54dce691f53` | SPARK-57811 | with ANSI off, a string compared with a nanosecond timestamp is cast to that type |
| `e29825ff8de` | SPARK-57832 | `ts - ts` accepted over nanosecond operands, on the microsecond grid |
| `ec58b58440a` | SPARK-60335 | no implicit narrowing: `months_between`, `timestampdiff`, `window`, `time_trunc`, `time_diff` and the percentiles, which ran on narrowed values, now fail with `DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE`; a `DATE` parameter still takes the type |
| `653db2957ac` | SPARK-60546 | `ts - ts` rejected again, by `NanosTimestampSubtractionCheck`, with `UNSUPPORTED_FEATURE.TIMESTAMP_NANOS_SUBTRACTION` |

The row's line gives no command for its "six", so this reading is the planner's: it is the one
command that prints six on this master. Two of the six reversed an answer (`ts - ts` twice),
and one turned five functions from accepted to rejected. That is the case this row is built
for: a hand list would have been wrong three times in five months.

**The conditions Spark's analysis can give a call.** 4.3's rule rests on these. `P` is
`$A/catalyst/parser/parsers.scala`.

| # | fact | command | prints |
| ---: | :--- | :--- | :--- |
| 24 | A failed `checkInputDataTypes` is reported as `DATATYPE_MISMATCH.<subclass>`, and a failed format as `INVALID_FORMAT.<subclass>` | `grep -c -F 's"DATATYPE_MISMATCH.${mismatch.errorSubClass}"' $C/analysis/package.scala`; `grep -c 'errorClass = "DATATYPE_MISMATCH.TYPE_CHECK_FAILURE_WITH_HINT",' $C/analysis/TypeCoercionValidation.scala`; `grep -c -F 's"INVALID_FORMAT.${invalidFormat.errorSubClass}"' $C/analysis/package.scala` | `1`; `1`; `1` |
| 25 | `checkAnalysis` walks each expression from its leaves, so it reports an unresolved column before the type check of the node above it | `grep -c 'failUnresolvedAttribute(operator, a, "UNRESOLVED_COLUMN")' $C/analysis/CheckAnalysis.scala`; `grep -c 'case e: Expression if e.checkInputDataTypes().isFailure =>' $C/analysis/CheckAnalysis.scala` | `1`; `1`, both under `getAllExpressions(operator).foreach(_.foreachUp {` |
| 26 | The conditions that Spark's own golden files record for calls over the nanosecond types | `grep -h -o '"errorClass" : "[^"]*"' $G/results/timestamp-ntz-nanos.sql.out $G/results/timestamp-ltz-nanos.sql.out \| LC_ALL=C sort \| uniq -c` | eight lines: 2 `CANNOT_PARSE_TIMESTAMP`, 2 `CAST_INVALID_INPUT`, 36 `DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE`, 1 `DATETIME_OVERFLOW`, 2 `INVALID_PARAMETER_VALUE.DATETIME_UNIT`, 6 `INVALID_TIMESTAMP_PRECISION`, 11 `UNSUPPORTED_FEATURE.TIMESTAMP_NANOS_SUBTRACTION`, 1 `WRONG_NUM_ARGS.WITHOUT_SUGGESTION` |
| 27 | The flag's error is a `SparkException` with the condition `FEATURE_NOT_ENABLED`; the parser rethrows only an error that carries an origin, and keeps its condition | `grep -A2 'def timestampNanosTypesNotEnabledError' $A/errors/DataTypeErrors.scala \| grep -c 'new SparkException('`; `grep -c 'case e: SparkThrowable with WithOrigin =>' $P` | `1`; `1`. Spark's `ExpressionParserSuite` pins the same condition for `cast(a as TIMESTAMP_NTZ(9))` with the flag off |
| 28 | Two timestamps of two types are widened to the larger precision, and to the type with a zone where either has one | `grep -c 'val p = math.max(precisionOf(d1), precisionOf(d2))' $C/analysis/TypeCoercionHelper.scala`; `grep -c 'Some(if (p <= MicrosPrecision) TimestampType else TimestampLTZNanosType(p))' $C/analysis/TypeCoercionHelper.scala` | `1`; `1`, in `findWiderDateTimeType`, under `if (isLtz(d1) \|\| isLtz(d2))` |
| 29 | Spark's own tests run the analyzer and then `checkAnalysis` on its result, as `check` does | `grep -c 'SimpleAnalyzer.checkAnalysis(analyzed)' sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/analysis/ExpressionTypeCheckingSuite.scala` | `1`, in `assertSuccess` |
| 30 | The push check runs a fixed list of suites where a branch touches a generated file | `grep -c '^    "VarkaCastMatrixSuite",$' dev/varka_push_check.py`; `grep -c 'names = GENERATED_SUITES' dev/varka_push_check.py` | `1`; `1` |
| 31 | The index of `SKILLS.md` is generated from the skills folder | `python3 dev/varka_toc.py --check SKILLS.md --from sql/varka/skills; echo $?` | `0`; without `--from` the same command prints `SKILLS.md: the contents block is stale; ...` and `1` |
| 32 | `unix_date` takes a `DATE` and declares no implicit cast of its input | `grep -A1 '^case class UnixDate' $E \| grep -c 'with ExpectsInputTypes {'`; `awk '/^case class UnixDate/{f=1} f && /override def inputTypes/{print; exit}' $E` | `1`; `  override def inputTypes: Seq[AbstractDataType] = Seq(DateType)`. `DateDiff` beside it is `with ImplicitCastInputTypes`. The lead's probe, below, measured the rejection |

Fact 26 names every condition of an analysis seen so far over these types: the two that
reject a type (`DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE`,
`UNSUPPORTED_FEATURE.TIMESTAMP_NANOS_SUBTRACTION`), the unit's
(`INVALID_PARAMETER_VALUE.DATETIME_UNIT`, fact 20), a type name's
(`INVALID_TIMESTAMP_PRECISION`) and an argument count's (`WRONG_NUM_ARGS.WITHOUT_SUGGESTION`).
The other three are raised when a query runs, never by the analysis. Beyond the golden files,
the analysis of a call can raise: the parser's conditions (`PARSE_SYNTAX_ERROR` and its
kin); `UNRESOLVED_COLUMN.WITH_SUGGESTION` and `UNRESOLVED_ROUTINE` for a name it does not
know; any `DATATYPE_MISMATCH.<subclass>` of fact 24 (among the subclasses in the files of the
asked entries: `UNEXPECTED_INPUT_TYPE`, `BINARY_OP_DIFF_TYPES`, `BINARY_OP_WRONG_TYPE`,
`DATA_DIFF_TYPES`, `NON_FOLDABLE_INPUT`, `VALUE_OUT_OF_RANGE`, `INVALID_ORDERING_TYPE` and
`HASH_MAP_TYPE`); `INTERNAL_ERROR` from `SparkException.internalError`; and an exception with
no condition at all. 4.3's rule names what the suite does with each, and with any condition
this list misses.

**The prototype.** 5.0 says how the four parts were run on a scratch copy. Its numbers are
this plan's predictions: after part A the summary reads `ntz_ns  new  0 / 5` and
`ltz_ns  new  0 / 5` and 149 legal cast pairs; after part B nothing of the census moves and
the table has 128 lines; after part C it reads `0 / 68` and `0 / 67`, 200 entries in scope and
1164 pairs; part D adds four lines to the summary. These are the numbers of the rerun of
27 November 2026 without `unix_date` (5.0); the plan's first two versions had 130 lines,
`0 / 69`, `0 / 68` and 1168 pairs.

**The lead's probe of 27 November 2026.** The lead ran Spark's analyzer once, on master
`1924c7a5afb`, outside this plan's sheets and with none of this plan's code. It took the 66
calls that the first version of `CALLS` held (5.2's 65 and `unix_date(c)`) and 16 pairs of
operand types. For each call and pair it made a local relation of two columns `c` and `c2`
of the pair's types, parsed the call, ran `SimpleAnalyzer.execute` over the projection of the
call on the relation and then `checkAnalysis` on the result, with
`spark.sql.timestampNanosTypes.enabled` on, once with `spark.sql.ansi.enabled` on and once
off. That is the method of `VarkaSqlResolve.check` (4.3). It recorded, for each of the 1056
asks of a call over a pair, the result's type or the condition under each setting. The pairs:
both columns of `TIMESTAMP_NTZ(9)`, of `TIMESTAMP_NTZ(7)`, of `TIMESTAMP_LTZ(9)` and of
`TIMESTAMP_LTZ(7)`; both of `TIMESTAMP_NTZ` and both of `TIMESTAMP`, which are the controls;
and ten pairs of two types: `TIMESTAMP_NTZ(9)` with `TIMESTAMP_NTZ(7)`, `TIMESTAMP_NTZ`,
`TIMESTAMP_LTZ(9)`, `DATE` and `STRING`; `TIMESTAMP_NTZ` with `TIMESTAMP_NTZ(9)`;
`TIMESTAMP_LTZ(9)` with `TIMESTAMP`, `DATE` and `STRING`; and `TIMESTAMP` with
`TIMESTAMP_LTZ(9)`.

| # | what the probe measured | what it is to this plan |
| ---: | :--- | :--- |
| 1 | In none of the 1056 asks is a call taken under one ANSI setting and rejected under the other | prediction 5's "no cell differs between the ANSI settings" is measured at precisions 7 and 9 |
| 2 | Over two columns of one nanosecond type, at precisions 7 and 9 alike, four calls are rejected under both settings: `months_between` and `unix_date` over both types with `DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE`, `c - c2` over both with `UNSUPPORTED_FEATURE.TIMESTAMP_NANOS_SUBTRACTION`, and `convert_timezone` over `TIMESTAMP_LTZ(p)` with the first condition. Every other call is taken | prediction 5's five rejected lines and two conditions are measured; `unix_date` was not predicted (4.3, "The two calls that no microsecond timestamp takes") |
| 3 | Over the controls, two calls are rejected, each over both microsecond types under both settings with `DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE`: `unix_nanos(c)` and `unix_date(c)`. Each other call is taken over both types under both settings | 4.3's line 5 would have stopped the suite at `unix_date`; the lead's decision takes the entry out of the asked set |
| 4 | Over a nanosecond type, 20 calls return a nanosecond type: the nine that prediction 5 names (eight over `TIMESTAMP_LTZ(p)`, which `convert_timezone` rejects) and eleven whose curated result is `same`: `coalesce`, `if`, `ifnull`, `nullif`, `nvl`, `nvl2`, `when`, `greatest`, `least`, `known_not_null` and `try_eval`. The result has the operands' precision | prediction 5's "nine entries name a nanosecond result" is measured wrong: the table's last column names 20 entries. No cell moves by it: the census reads that column only for an entry whose curated result is not `same` (5.2, command 3) |
| 5 | Over the pairs of two timestamp types, `coalesce`, `greatest` and `CASE WHEN` return, under both settings: `TIMESTAMP_NTZ(9)` for `TIMESTAMP_NTZ(9)` with `TIMESTAMP_NTZ(7)` and with `TIMESTAMP_NTZ`, in either order of the last; `TIMESTAMP_LTZ(9)` for `TIMESTAMP_LTZ(9)` with `TIMESTAMP`, in either order, and for `TIMESTAMP_NTZ(9)` with `TIMESTAMP_LTZ(9)`. The comparison and `IN` are taken and return `BOOLEAN` | prediction 11's widened type is measured for three of its five pairs, in part (4.3's table says which). The probe read result types, not the operand types that the fifth test reads |
| 6 | With a `STRING` beside a nanosecond timestamp, `coalesce`, `if`, `ifnull`, `nvl`, `nvl2` and `CASE WHEN` return the nanosecond type under ANSI and `STRING` under LEGACY, and `c - c2` is rejected with a different condition under each setting | nothing here: no call of `CALLS` or `MIXED_CALLS` has a string operand beside a timestamp. Rows 674 and 675 meet it (fact 6, SPARK-57811) |

What the probe did not ask: precision 8; the pairs `TIMESTAMP_LTZ(8)` with `TIMESTAMP_LTZ(7)`
and `TIMESTAMP` with `TIMESTAMP_NTZ(7)`; any pair with `TIMESTAMP_NTZ(8)`; and the operand
types of a plan's nodes. So the suite's first run still settles each of these, and the
table of part B is still the suite's and not the probe's.

**What I could not check.**

- No JVM ran. `VarkaNanosAdmissionSuite`, the method `VarkaSqlResolve.check`, the registry's
  two entries and every other Java line of section 5 are uncompiled text, and no parser of
  Java or Scala is installed here to read them. Each name they use was opened in the tree
  (5.2, "The names, checked"). The answers of Spark's analyzer are `VARKA-660.md` 4.5's
  reading of the sources and golden files, and the analyzer's own only where the lead's
  probe asked (above).
- Whether the probe compiles a cast over a nanosecond column to a plain decline and raises
  nothing, and whether each conformance suite cancels its two new tests. Row 654's part b
  shows both for `TIMESTAMP`, a declined type with the same storage, before this row starts.
- Whether an entry's answer differs between the two ANSI settings at precision 8, and at
  which types a node reads two timestamps of two types (fact 28). The lead's probe measured
  no difference at precisions 7 and 9, and the widened result type for three pairs. The suite
  records the first and pins the second (4.3).
- `ruff format` is not installed here: the Python is wrapped by hand, and each sheet runs it.
- `dev/varka_regen_generated.sh` with part B's edits passes `bash -n`, and its `--all --full
  --dry-run` printed the steps in order on the copy, which is no git repository: a stand-in
  answered its one `git rev-parse`. Its real run needs sbt. `dev/varka_generated_merge.py
  --list` ran with part B's edits; its `--self-test`, which makes a git repository,
  `dev/varka_push_check.py --self-test` and `dev/varka_new_lane.py --check` did not.
- Rows 654, 656 and 653 are not built. Every old text and every count is checked by the
  sheets' premises on the master of the build's day.

## 4. The design

### 4.1 Four parts, and the one rule that exists only between them

The row is four pull requests, built in this order and in no other.

| part | what master holds after it |
| :--- | :--- |
| A | the two types in the registry, declined, and in the cast matrix: 20 pairs, 102 probe lines. In the census only the cast entries admit them, 5 pairs each |
| B | Spark's answers, as a committed table that a suite holds to Spark in every Build. The census does not read it yet |
| C | the census admits the two types for an entry from the table; `timestamp_nanos` and `unix_nanos` come into scope; every cell names its row; a rejection is a reason |
| D | the band of the precision; the upkeep written down; the row **Done** |

**The interim rule.** The curated file's group `ALL` is every census type, so from the moment
the registry names the two types, 26 entries would admit them by a list, which is what the
row forbids. Part A therefore puts one rule into `row`: no entry but a cast entry admits a
nanosecond type. Part C replaces it with the rule that reads the table. A cast entry is not
asked in either: its types are the cast table's, which `VarkaCastMatrixSuite` holds to `Cast`.
Part D cannot be built before part C: a doctest of the band claims `ntz_ns` for `timestampadd`,
which the interim rule strips.

### 4.2 The registry (part A)

- **Two ids** after `TIMESTAMP`: `TIMESTAMP_NTZ_NANOS`, `TIMESTAMP_LTZ_NANOS`. The census's
  order then ends `..., dt, ltz, ntz_ns, ltz_ns`.
- **Each entry**: census name `ntz_ns` or `ltz_ns`; SQL spelling `TIMESTAMP_NTZ(9)` or
  `TIMESTAMP_LTZ(9)`; three forms, precision 7, 8 and 9 in this order; no lanes;
  `Conformance.NotLanded`; the admission `Declined`, with the decision text
  `its value is two words, the microseconds and the nanoseconds within one, which no kernel
  reads before row 699`. A column of the type then declines by name, as `TIMESTAMP` did:
  `a timestamp_ntz(7) column: its value is two words, ...`.
- **`Declined`, not `Owed`.** `VARKA-660.md` 6.4 fixes `declined` in `types.tsv`, and the fuzz
  descriptor's `declined(id)` needs it (fact 16). Row 699 turns it, as row 656 turned
  `TIMESTAMP`.
- **The storage is `plain(64)`.** `VARKA-660.md` 6.6 left the storage to this plan, and its
  4.9 gives "the registry's storage of the two types, the Arrow struct in and out" to row 699.
  So this row adds no storage kind. It registers the one layout that Spark's standard mapping
  has, eight bytes (fact 17), which is what `VarkaTypesSuite` checks an entry against, and its
  comment says that the cache's struct of two vectors is row 699's to register. The docs'
  support table then prints `plain` in the Lanes column of the two types, as it printed for
  `ltz` before row 656. A new `Storage` record now would add a case to `Storage.bits()`, to
  `VarkaTypes.outputWidth` and to `VarkaTypesSuite.storageName` that no kernel and no test
  reads.
- **`idOf`** gives the class's one id for every precision. `TIMESTAMP_NTZ(6)` is
  `TimestampNTZType` and keeps the id `TIMESTAMP_NTZ`.
- **Every switch with one case an id** gets the two ids on the arm of a type no lane reads
  (5.1, step 2).

### 4.3 The admission: who asks Spark, how, and how the answer reaches the census (parts B and C)

**Which side asks.** The JVM side: a JUnit 5 suite of the catalyst tests,
`VarkaNanosAdmissionSuite`, beside `VarkaTypesSuite`. The Python side cannot ask Spark; it
decides *what* is asked and reads the answers.

**Through which method.** `VarkaSqlResolve.check(text, columns)`, new in the tests' resolver.
It parses `text` with `CatalystSqlParser.parseExpression`, binds the columns by name, runs
`SimpleAnalyzer.execute` over `Project(Alias(expr), LocalRelation(columns))` as the resolver's
other methods do, and then calls `SimpleAnalyzer.checkAnalysis` on the result. It returns the
type of the projection's one output, or the condition of the `SparkThrowable` that any of the
three steps raised (`getCondition`). An error with no condition is thrown as it is. With the
type it returns `operands`: the timestamp types at which the nodes that have two or more
timestamp operands read them, which is one type where the analyzer brought them to one.

**For which input types.** For a line of the table, an entry and a census type, the suite
builds two columns `c` and `c2` of the type's form at precision 7, then 8, then 9
(`VarkaTypes.info(id).forms()`), and asks the entry's call three times. Each time it asks
twice, with `spark.sql.ansi.enabled` false and true, and both times with
`spark.sql.timestampNanosTypes.enabled` set to true on a copy of the session's configuration
that `SQLConf.withExistingConf` installs. Neither setting is left to a default.

**The operand beside the type under test.** A cell is one (entry, type), so in a binary or a
ternary entry every timestamp operand has the cell's own type at the cell's own precision:
`c2` has `c`'s type, and where the entry needs a timestamp literal Spark builds it from `c`'s
type (`time_bucket`'s origin). Every other operand is a literal of the type the entry's
signature names: an integer, a string, a unit or an interval. This is `VARKA-660.md` 6.2's
rule: "Every argument of a call has the line's own type", and "A call with mixed precisions
would test Spark's widening, which is the cast table's, not the entry."

**Why a cell cannot depend on a call over two types.** Take a call over two timestamps of two
types: two precisions, two kinds, or a microsecond type beside a nanosecond one. Spark does
one of two things with it. It rejects the call, and then no plan holds it. Or its coercion
puts a cast over the narrower operand (`findWiderDateTimeType`, fact 28), and then the entry's
node reads two operands of one type: the plan is the entry's same-type cell behind a cast, and
the cast is a pair of `casts.tsv`, which row 674 owes. So rows 699, 673 and 675 fuse an entry
over one type, and a mixed call fuses when its cell and its cast pair both do; nothing of it
is a cell of its own. The suite holds the premise in a test of its own, "two timestamps of
two types reach an entry as one type, behind a cast": it asks the five calls that SPARK-57454
names (`MIXED_CALLS`: a comparison, `IN`, `coalesce`, `greatest`, `CASE WHEN`) over the five
pairs of types below, under both ANSI settings, 50 asks, and fails where a node is left with
timestamp operands of two types or where the one type is not the pair's. It writes nothing
into the table, which stays at two lines an entry. The third column is a prediction, read
from `findWiderDateTimeType` (section 7, prediction 11). The last column says what the lead's
probe of 27 November 2026 measured of it (section 3): the type that `coalesce`, `greatest`
and `CASE WHEN` return over the pair under both ANSI settings, which is the widened type. The
probe did not read the operand types of the nodes, and those are what the test asserts. So
every line's third column stays a prediction that the first run of the suite settles, and
the second and the fifth line have no measurement at all. The rule for a difference at the
first run is line 13 of 5.2's "The first run": where the ten asks of a pair all print one
other type, the builder writes that type into the pair's line of the test, and prediction 11
is scored wrong for the pair; any other difference ends the part.

| `c` | `c2` | the one type both reach the entry at (predicted) | the cast pair that Spark adds | the lead's probe |
| :--- | :--- | :--- | :--- | :--- |
| `TIMESTAMP_NTZ(7)` | `TIMESTAMP_NTZ(9)` | `TIMESTAMP_NTZ(9)` | `ntz_ns` to `ntz_ns` | the result type is `TIMESTAMP_NTZ(9)`; asked with the two types in the other order |
| `TIMESTAMP_LTZ(8)` | `TIMESTAMP_LTZ(7)` | `TIMESTAMP_LTZ(8)` | `ltz_ns` to `ltz_ns` | not asked: the first run settles it |
| `TIMESTAMP_NTZ(9)` | `TIMESTAMP_LTZ(9)` | `TIMESTAMP_LTZ(9)` | `ntz_ns` to `ltz_ns` | the result type is `TIMESTAMP_LTZ(9)` |
| `TIMESTAMP_NTZ` | `TIMESTAMP_NTZ(8)` | `TIMESTAMP_NTZ(8)` | `ntz` to `ntz_ns` | asked at precision 9 and not at 8, in both orders: the result type is `TIMESTAMP_NTZ(9)` |
| `TIMESTAMP` | `TIMESTAMP_NTZ(7)` | `TIMESTAMP_LTZ(7)` | `ltz` to `ltz_ns`, and `ntz_ns` to `ltz_ns` | not asked: the first run settles it |

**The calls.** One SQL text an entry, in the suite's map `CALLS`, 65 today (5.2 has the
file). The rule of each: the entry as a user writes it, with `c` in every timestamp parameter,
`c2` where the entry takes two timestamps, and a literal in every other parameter. Five lines
are not the obvious spelling, each for a reason:

| entry | call | why |
| :--- | :--- | :--- |
| `timestampadd` | `timestampadd(HOUR, 1, c)` | the unit `NANOSECOND` is rejected over a microsecond timestamp (fact 20), so it cannot pass the control below. The entry's admission does not depend on the unit |
| `time_bucket` | `time_bucket(INTERVAL '1' HOUR, c)` | the origin must be foldable and of the column's own type; the two-argument form builds that origin itself (fact 21) |
| `in_set` | `c IN (c2)` | `InSet` has no SQL: the optimizer makes it from an `In` that passed analysis |
| `known_not_null` | `c` | the optimizer puts `KnownNotNull` over any expression; the question is whether a query may hold the column |
| `try_eval` | `try_add(c, INTERVAL '1' DAY)` | `TryEval` is built by the `try_` functions; `try_add` over a datetime is `TryEval(Add(...))` |

**Spark's answer under one ANSI setting: the rule.** One ask is one call over one form under
one setting. Its outcome is one of the five lines below, taken in this order, and the suite
applies the rule itself (`rejection`), so no reader judges a condition. The *control* of the
ask is the same call over `TIMESTAMP_NTZ` and over `TIMESTAMP` under the same setting; it
*takes* the call where Spark raises nothing over at least one of the two.

| # | the outcome over the nanosecond column | the answer under that setting | the suite | what the builder does |
| ---: | :--- | :--- | :--- | :--- |
| 1 | Spark raises nothing: the text parses, the analyzer and `checkAnalysis` pass. A cast that the analyzer put in, to `DATE` or to a microsecond type, changes nothing | taken | goes on; the control is not asked | nothing |
| 2 | an exception with no condition: not a `SparkThrowable`, or one whose `getCondition` is null. The same holds where an ask of the control raises such an exception while line 4 or line 5 is decided | none | the test ends in that exception; no file is written | the part ends: report the entry, the call, the type, the setting and the exception's first line |
| 3 | a condition that starts with `INTERNAL_ERROR` or with `FEATURE_NOT_ENABLED` (`NO_ANSWER`) | none | fails: "... which is no answer of Spark's about a type" | the part ends: report the line. The first is a fault inside Spark; the second means the flag the suite sets was not read |
| 4 | any other condition, of any class, and the control takes the call | rejected, with that condition | goes on | nothing at the ask; the description lists the cell (5.2, command 3) |
| 5 | any other condition, and the control does not take the call | none | fails: "... so the rejection says nothing of the type: the call in CALLS is at fault" | the part ends: report the line. The builder does not reword a call this plan gives |

Line 1 is `VARKA-660.md`'s decision, not this plan's: its 4.5 reads `datediff` as "accepted
over both kinds: a `DATE` parameter, and the implicit cast to `DATE` stands", and `hour` as
"accepted: `HourExpressionBuilder` casts to the microsecond type". So an analysis that
succeeds is Spark taking the type, whatever it inserted. Line 4 has no list of conditions:
the call's text and the setting are the same in the ask and in its control, so a condition
that the nanosecond column alone raises is caused by the type. A text that does not parse, an
unknown column, an unknown function and a wrong number of arguments are raised over the
microsecond columns too, so each falls to line 5. An error that is not an
`AnalysisException` but carries a condition (a `SparkException`) is read by its condition,
on lines 3 to 5. An exception with no condition is never caught: `check` rethrows it, from
the ask over the nanosecond column, from an ask of `controlTakes` and from an ask of the test
of the calls alike, and the test ends in it (line 2).

**The two calls that no microsecond timestamp takes.** Line 5 needs a control that takes the
call. The lead's probe asked each call of the plan's first `CALLS` over `TIMESTAMP_NTZ` and
over `TIMESTAMP` under both settings (section 3). Two were rejected over both types under
both settings, each with `DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE`; every other call was
taken over both types under both settings. The two are handled in two ways.

- **`unix_nanos` stays asked.** It takes a nanosecond timestamp alone, by its meaning: its
  curated types are `NS`. Its control never takes the call, so it is in the suite's
  `NANOS_ONLY`, which the test of the calls skips; line 1 answers it, and a rejection of it
  would fall to line 5 and end the part.
- **`unix_date` is not asked over any timestamp type** (the lead's decision of 27 November
  2026). It takes a `DATE`, and Spark casts no timestamp to it (fact 32): the probe measured
  the same rejection over the two microsecond types and over the nanosecond types. The
  owner's decision that the date functions count for the timestamp types rests on Spark's
  implicit cast to `DATE`, which `unix_date` does not have, so the decision does not reach
  it. Its curated line keeps `date` alone, as it is on master:
  `unix_date|date|int|date|done||`. It gets no `ntz`, `ltz`, `ntz_ns` or `ltz_ns` cell. The
  fault is removed at its source: `VARKA-654.md`'s rule 4 and its script no longer name
  `unix_date` (section 12, edit 4), and the one-off script of 5.0 no longer names it among
  the date functions. So the entry leaves the asked set by the rule that chooses the asked
  entries, with no exception written into the census: its types hold no timestamp. And
  `CALLS` has no call for it, because the test of the calls asks every call of `CALLS` but
  those of `NANOS_ONLY` over the microsecond types, and would fail on `unix_date(c)`, which
  neither of them takes. The two then agree: `--asked` writes lines for 65 entries, and `CALLS` has
  65 calls. Part B's premise 21 stops the build where the curated line holds a timestamp.

**No other asked entry is in that case.** Of the 65 asked entries, `unix_nanos` is the only
one whose control the probe saw rejected. And of the entries that rows 654 and 660 count for
`ntz` or for `ltz`, `unix_date` was the only one that Spark rejects over the type it is
counted for: the other six date functions (`add_months`, `datediff`, `date_diff`, `date_add`,
`dateadd`, `date_sub`) are taken over both microsecond types under both settings.

**From two settings to a cell.** `cell(legacy, ansi)` writes the two answers of one precision
as one field:

| LEGACY | ANSI | the cell | at that precision |
| :--- | :--- | :--- | :--- |
| taken | taken | `accepted` | admits |
| taken | rejected with `c` | `ANSI:c` | admits: Spark takes the type under LEGACY alone |
| rejected with `c` | taken | `LEGACY:c` | admits: Spark takes the type under ANSI alone |
| rejected with `c` | rejected with `c` | `c` | does not admit |
| rejected with `c1` | rejected with `c2` | `ANSI:c2,LEGACY:c1` | does not admit |

**From three cells to the admission** (the owner's answer of 27 November 2026 to question 2 of
section 13: either setting admits). A type is admitted for an entry when each of its three
cells admits, that is, when at every precision Spark takes the call under at least one ANSI
setting. The census reads exactly this from a cell (`one_setting`, `admit_nanos`, `alone`,
from part C): `accepted` admits; a cell that is `ANSI:` or `LEGACY:` and one condition with no
comma admits and names the setting that rejects; any other cell does not admit. For a type
that is not admitted the reason column gets `Spark rejects <type>: <cell>`, or `Spark rejects
<type> at precision <p, ...>: <cell>` where only some precisions reject under both settings.
For a type that is admitted with a cell of one setting, the reason column gets one clause for
each setting that rejects:

    Spark takes <type> under LEGACY alone: ANSI raises <condition>
    Spark takes <type> under ANSI alone at precision <p, ...>: LEGACY raises <condition>

The words `at precision <p, ...>` are there where fewer than three precisions differ. This
departs from `VARKA-660.md` 6.2, where the suite failed on a difference between the settings
(section 12, edit 2).

**What such a cell demands of the row that fuses it.** By the owner's answer, the kernel
declines under the setting that rejects, with a named reason, and a test shows that decline.
The demand is recorded in two places that the later builder cannot pass by. The clause above
is generated into `census.tsv`'s reason column from the table, so it is never typed and never
stale. And `--check` enforces it from part C: a fused claim (a fused type, or one marked `^`
or `~`) over a type that has such a clause is an error until the entry's note in the curated
file names the test, in the words `declines under ANSI: <suite>, <test>` or `declines under
LEGACY: <suite>, <test>`. A program decides whether the note does (`names_decline`, part C):
after the words and the setting, `<suite>` is a class name that ends in `Suite`, then a
comma and a space, then `<test>`, which begins with a character that is neither a space nor
a `;` and runs to the note's next `;` or to its end. The words alone, a suite with no test
and a test with no suite do not pass. The census cannot tell whether the named test exists
or what it asserts: the fan-out's review reads that (section 11). The error says what is
owed:

    census: <entry>: Spark takes <type> under LEGACY alone: ANSI raises <condition>, so a fused claim over <type> needs a kernel that declines under ANSI by name and a test of that decline: name the test in the note as "declines under ANSI: <suite>, <test>"

The need field is not used: it is one hand-written word an entry, not a cell's, and its
values are kinds of work (`done`, `plumbing`, `semantics`, `ulp`, `hard`). No entry is
predicted to differ between the settings (prediction 5), so on the prediction no clause is
written and no claim is held.

**The result.** The table's last column is the census name of the call's result type where
that is a nanosecond type (`ntz_ns` for `date_trunc` over `ntz_ns`), asked at precision 9
under ANSI, or under LEGACY where ANSI rejects the call, and only where each of the three
cells admits; else `-`.

**How the answers get into the committed files.**

1. `python3 dev/varka_census.py --asked` computes the asked entries from the registry and the
   curated file and writes the table's lines, in the row order of `census.tsv`, keeping every
   answer the file has and writing `?` in a new line.
2. `VARKA_NANOS_ADMISSION_REGEN=true build/sbt 'catalyst/testOnly *VarkaNanosAdmissionSuite'`
   reads the lines, asks Spark for each and writes the answers back, in the lines' order.
   Without the variable, as in every Build, the suite computes the same answers and fails
   where the file differs, naming each line and the command.
3. `python3 dev/varka_census.py` reads the table (`nanos_admission`), admits each nanosecond
   type for each asked entry by `admit_nanos`, writes the clauses of `alone` into the reason
   column, and writes `census.tsv` and the docs' table.
   `--check` fails on a line no entry asks, on lines out of order, on a missing line and on a
   `?`, each with the two commands above as its remedy.

**When Spark moves.** After a sync that changes an answer, the Build's catalyst job fails in
`VarkaNanosAdmissionSuite` with the lines that moved, one `now:` and one `file:` a cell. The
sync's pull request runs steps 2 and 3; the census then moves the cells by itself: a newly
accepted (entry, type) joins the entry's types with no fused mark, and a newly rejected one
leaves them and gets its reason. A cell that turns to one setting alone keeps its type and
gets its clause; if the cell is fused, `--check` then fails with the demand above until the
note names the decline's test. Two cases need a hand, and the check names both: an entry
that newly takes a timestamp is a curated line the sync writes, after which `--check` prints
`no line in ...` and step 1 adds it; and a newly accepted cell names no row until the sync
adds one to the entry's `rows`. A new registered function is the sync's to curate, as today.
If the test of mixed operands fails after a sync, Spark changed its widening: 5.2, step 7,
has the rule.
The band's upkeep is `VARKA-660.md` 4.4's three commands, which part D writes into the skills.

### 4.4 The cast pairs

From `Cast.canCast` and `Cast.canAnsiCast`, whose cases for these types are the same lines in
both (fact 12); `canTryCast` is `canAnsiCast` for them (fact 13). So each pair below is legal
under LEGACY, ANSI and TRY alike, and no pair with a number, a `BOOLEAN` or an interval is
legal under any. `Cast`'s legality reads no flag; the flag gates the cast's own type check and
the parser, so the probe sets it (5.1, step 5).

| from | to | kind | reads the zone | reads the target's precision |
| :--- | :--- | :--- | :--- | :--- |
| `date` | `ntz_ns` | scale up | | |
| `date` | `ltz_ns` | local to instant | yes | |
| `ntz` | `ntz_ns` | widening | | |
| `ntz` | `ltz_ns` | local to instant | yes | |
| `ltz` | `ntz_ns` | instant to local | yes | |
| `ltz` | `ltz_ns` | widening | | |
| `time` | `ntz_ns` | session date | | yes |
| `time` | `ltz_ns` | session date | yes | yes |
| `ntz_ns` | `date` | floor division | | |
| `ntz_ns` | `ntz` | floor division | | |
| `ntz_ns` | `time` | floor modulo | | |
| `ntz_ns` | `ltz` | local to instant | yes | |
| `ntz_ns` | `ntz_ns` | relabel or truncation | | yes |
| `ntz_ns` | `ltz_ns` | local to instant | yes | yes |
| `ltz_ns` | `date` | instant to local | yes | |
| `ltz_ns` | `ntz` | instant to local | yes | |
| `ltz_ns` | `time` | instant to local | yes | |
| `ltz_ns` | `ltz` | floor division | | |
| `ltz_ns` | `ntz_ns` | instant to local | yes | yes |
| `ltz_ns` | `ltz_ns` | relabel or truncation | | yes |

20 pairs. A nanosecond type's own pair is no identity: it is a cast between two precisions.
By proof: the 10 pairs that read the zone are the zone sweep's, `date` to `ntz_ns` is
exhaustion, the other 9 are SMT. By row: every pair is row 674's, and `ntz_ns` to `ntz` and
`ltz_ns` to `ltz` read `699,674`. The semantics column of `casts.tsv` is `ns_cast_kind`'s
text, in `VARKA-660.md` 6.1, edit 10. The probe gains 102 lines: 6 within each type, 9 each
way between the two, 9 each way between each type and `time`'s three forms, and 3 each way
between each type and each of `date`, `ntz` and `ltz`.

### 4.5 What the census's totals become, by a rule

Write `E`, `P`, `F`, `L` and `T` for the entries in scope, the pairs, the pairs fused, the
legal cast pairs and the census types that `--summary` prints before part A. Write `a` and
`b` for the lines of the admission table over `ntz_ns` and over `ltz_ns` whose three cells
each admit the type (`accepted`, or one setting's rejection alone; 5.3, command 3), and `k`
for the fused sources `cast` and `try_cast` lose (below).

| after | entries in scope | pairs | pairs fused | `ntz_ns`, `ltz_ns` | legal cast pairs | types |
| :--- | ---: | ---: | ---: | :--- | ---: | ---: |
| part A | `E` | `P + 10` | `F - k` | `0 / 5`, `0 / 5` | `L + 20` | `T + 2` |
| part B | the same | the same | the same | the same | the same | the same |
| part C | `E + 2` | `P + 10 + a + b + 5` | `F - k` | `0 / (a + 5)`, `0 / (b + 5)` | `L + 20` | `T + 2` |
| part D | the same, and the summary's four lines of the band | | | | | |

On the prototype `E` is 198, `P` 1024, `F` 585, `L` 129 and `T` 14; `a` is 63, `b` 62 and
`k` 0. So part C reads 200 entries, 1164 pairs, `0 / 68` and `0 / 67`, 149 legal pairs over
16 types.

*27 November 2026, a correction after the lead's probe:* the plan's first two versions had
`P` 1026, `a` 64 and `b` 63, so 1168 pairs, `0 / 69` and `0 / 68`, as `VARKA-660.md` 4.3 has
them. Those counted `unix_date` over `ntz`, `ltz`, `ntz_ns` and `ltz_ns`: four pairs that
Spark does not take (4.3). The rule of the table is unchanged; `P`, `a` and `b` are each
what the build prints.

The owner's answer on the ANSI settings moves none of these numbers while no entry differs
between the settings, which is the prediction (prediction 5): every line is then three times
`accepted` or a rejection under both. Where a line does differ, it counts in `a` or `b` where
the plan's first version left it out: `a` or `b` is one greater for each (entry, type) line
that Spark takes under one setting alone at some precision and rejects under both at none,
and the pairs and the type's denominator follow by the table's rule.

- **The 10 of part A** are the five cast entries over each type: `cast`, `try_cast`, and the
  aliases `date`, `time` and `timestamp`.
- **The 5 of part C** are `timestamp_nanos` over `byte`, `short`, `int`, `bigint` and `dec`.
- **`k`, the fused sources that go.** `cast` and `try_cast` count a source fused only when
  every legal pair from it fuses (`matrix_fused`). Each of `date`, `ntz`, `time` and `ltz`
  gains two legal pairs, to `ntz_ns` and to `ltz_ns`, that nothing fuses. So each of those
  four that `cast` or `try_cast` counts fused before part A becomes fused in part: at most
  8 pairs. `k` is 0 on the prototype, where none of them counts; at the build it is what part
  A's step 1 prints. A source comes back when row 674 fuses its two pairs.

**The committed files that move**, by part: A, `sql/varka/types.tsv` (2 lines),
`sql/varka/fuzz/types.tsv` (2 lines), `casts_fused.tsv` (102 lines), `casts.tsv` (20 lines),
`casts_suites.tsv` (36 lines renamed on the prototype's record), `census.tsv` (5 lines, and 2
more for each source of `k`), `census_curated.txt` (5 lines). B, `nanos_admission.tsv` (new,
1 + 2 x 64 lines). C, `nanos_admission.tsv` (2 lines more), `census_curated.txt` (67 lines
added, 62 removed), `census.tsv` (66 lines). D, none.

**The docs' tables that move**, all inside the generated support table of `docs/sql-varka.md`:
in part A the by-type table gains two lines, `` `TimestampNTZNanosType` (`ntz_ns`) `` and
`` `TimestampLTZNanosType` (`ltz_ns`) ``, each of the three cast matrices gains two columns
and two lines, and the two `conversion` lines of the by-family table list the two types; in
part C the by-type lines of `byte`, `short`, `int`, `bigint`, `dec` and the two new types move,
and the by-family lines of the datetime and other families that admit them. Parts B and D
move no docs line.

### 4.6 Edge cases, declines and error texts

The row emits no kernel, so null rows, the ends of a long, an empty batch and the vector
widths do not arise. One thing declines newly, by name: a column of a nanosecond type, with
4.2's text; before part A it declined with the compiler's general reason. The script's new
refusals are `VARKA-660.md` 4.8's five, each an error of `--check`, the first four from part
C and the fifth from part D, and this plan's one, the claim over a cell of one setting (4.3),
from part C. The suite's failures are five texts:

    Spark's answers moved from nanos_admission.tsv:
    no call for the census entry <entry>: add it to CALLS
    <entry>: `<call>` over <type> with ANSI <on|off> is rejected with <condition>, which is no answer of Spark's about a type
    <entry>: `<call>` over <type> with ANSI <on|off> is rejected with <condition>, and Spark takes the call over neither microsecond timestamp under that setting, so the rejection says nothing of the type: the call in CALLS is at fault
    Spark no longer brings these operands to the one type:

### 4.7 What is out of this row

| excluded | the row that holds it |
| :--- | :--- |
| the Arrow struct in and out, the storage kind of two vectors, the conformance suites, the coverage columns, the layouts | 699 |
| any fused cell or cast pair of the two types | 699, 673, 674, 675 |
| the coverage rows that the band asks for at each precision, and their fixture values | 674, 675 (`VARKA-660.md` section 7) |
| the nanosecond baseline classes | 655 |
| the census's files as one file an entry | 696 |
| the register of upstream behaviours as a file | 681; part D adds this row's two entries where the file exists (5.4, step 4) |
| `ts - ts`, `months_between` and `timestampdiff` over nanoseconds | none: Spark rejects them (`m9/PLAN.md` 1.3) |

## 5. The parts

Four pull requests, one after the other. No two can be built side by side: each edits
`dev/varka_census.py` and this file, and part D's doctests need part C's rule (4.1).

| part | title | branch | needs | gives | counted lines, estimate |
| :--- | :--- | :--- | :--- | :--- | ---: |
| A | `[VARKA-661] Bring the nanosecond timestamps into the type registry and the cast matrix` | `varka-661a-nanos-types` | this plan merged; row 654's parts a and b | the two ids, declined; `ntz_ns` and `ltz_ns` in the census's order; the 20 cast pairs and the 102 probe lines. Rows 699 and 674 read it | 250 |
| B | `[VARKA-661] Ask Spark which census entries take a nanosecond timestamp` | `varka-661b-nanos-admission` | A | `VarkaSqlResolve.check`, `VarkaNanosAdmissionSuite`, `nanos_admission.tsv`, `--asked`. Only part C reads it | 700 |
| C | `[VARKA-661] Admit the nanosecond timestamps in the census from Spark's own answers` | `varka-661c-nanos-census` | B | the cells of the two types, each with its row or its reason; the rule that either ANSI setting admits; `timestamp_nanos` and `unix_nanos` in scope. Rows 699, 673, 674, 675, 658 and 677 read it | 280 |
| D | `[VARKA-661] Hold a nanosecond claim to each precision where the answer depends on it` | `varka-661d-nanos-band` | C | the band; the upkeep in the skills; the row **Done**. Rows 674 and 675 read it | 330 |

The estimates are file by file. A: the script 125 (116 added, 9 removed on the prototype),
`VarkaTypes.java` 32, the three other switches and the enum 10, `VarkaTypesSuite` 14,
`VarkaTypeConformance` 14, `VarkaCastMatrixSuite` 24, the curated file 10, the prose 6, this
file and `PLAN.md` 15. B: the script 177 (176 added, 1 removed on the prototype),
`VarkaNanosAdmissionSuite.java` 421 (its 404 lines and the 17 of the license header and the
empty line under it), `VarkaSqlResolve.scala` 57, the lists of generated files 19
(`dev/varka_regen_generated.sh` 16, `dev/varka_generated_merge.py` 2,
`dev/varka_push_check.py` 1; `.gitattributes` is not a counted kind of file), this file and
`PLAN.md` 27: 701. C: the script 136 (118 added, 18 removed), the curated file 129 (67 added,
62 removed), this file and `PLAN.md` 17: 282. D: the script 248, the skills 35, this file and
`PLAN.md` 45. The generated files count in none. No part is estimated at 750 or more, so no
sheet has a cut line, and no part is split further: part B, the nearest, is 299 lines under
the cap by the count above, and each of its five tests stays in it (5.2, "The size"). If a
part's size check prints 900 or more, the part ends: report the count and the three largest
files.

### 5.0 What every sheet shares

- **The worktree.** `git worktree add -b <branch> ../<branch> origin/master`, then, in it,
  `dev/varka_precommit.sh --install-hook`.
- **The environment** is `sql/varka/plans/m8/BUILD_SHEETS.md`'s: its three exports, and
  `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64`. Wrap every `build/sbt`,
  `dev/lint-java`, `dev/lint-scala` and `dev/varka_errorprone.sh` call in
  `flock "$VARKA_HEAVY_LOCK"`. Call `dev/varka_regen_generated.sh` bare: it takes the lock
  itself. Never print the environment, never kill by a name pattern, never `git stash`.
- **Names**, set once a shell. The shell is zsh, which does not split an unquoted variable.

        M9=sql/varka/plans/m9
        V=org/apache/spark/sql/catalyst/expressions/codegen
        MJ=sql/catalyst/src/main/java/$V
        TJ=sql/catalyst/src/test/java/$V/varka
        TS=sql/catalyst/src/test/scala/$V/varka
        CJ=sql/core/src/main/java/org/apache/spark/sql/execution
        T=target/varka-661; mkdir -p $T
        B=$(git merge-base HEAD origin/master)
        census() { python3 dev/varka_census.py > /dev/null && { ! grep -q '"--docs"' dev/varka_census.py || python3 dev/varka_census.py --docs; }; }

  `$T` is under `target/`, which git ignores; it is new in each worktree. `census` writes the
  census's files and the docs' support table in either state of the script: once row 653's
  part D has merged, the page is written by `--docs` alone (`VARKA-653.md` 4.5, which lets a
  type row write the page in its own pull request).
- **The tools.** Two one-off scripts, not committed. Save each block below under the name
  above it, with the four leading spaces of every line removed, and make
  `$T/ns_curated.py` from the second:

        python3 $T/mk_ns_curated.py $M9/VARKA-660.md $T/ns_curated.py     # target/varka-661/ns_curated.py: 8 replacements made

  `$T/edits660.py`:

    #!/usr/bin/env python3
    """One-off: apply numbered edits of VARKA-660.md section 6.1 to dev/varka_census.py (VARKA-661).

    usage: edits660.py <VARKA-660.md> <varka_census.py> <number> [<number> ...]
    Not committed. It takes each edit's old and new text out of the plan, where a block is indented
    eight columns, and applies the edits in the order given. An old text that is not in the script
    exactly once stops the run: nothing is written, and the line printed names the edit and the count.
    Edit 38 is applied by its rule: the comment lines directly above milestone 9's entry go, and the
    new comment comes in their place.
    """
    import re
    import sys

    def edits_of(plan):
        text = open(plan, encoding="utf-8").read()
        sec = text[text.index("### 6.1 The script's edits") : text.index("### 6.2 The admission table")]
        edits, mode, cur = {}, None, None
        for line in sec.split("\n"):
            m = re.match(r"(\d+)\. .* Old:$", line) or (
                (cur is None or mode == 1) and re.match(r"(\d+)\. The comment above milestone 9", line)
            )
            if m:
                cur = [[], []]
                edits[int(m.group(1))] = cur
                mode = 0
            elif line.startswith("   New:"):
                mode = 1
            elif line.startswith("   ") and not line.startswith("    "):
                pass  # an item's prose, continued
            elif cur is not None and (line.startswith("        ") or line == ""):
                cur[mode].append(line[8:])
            elif cur is not None:
                cur = None  # prose after the last edit

        def strip(lines):
            while lines and lines[0] == "":
                lines = lines[1:]
            while lines and lines[-1] == "":
                lines = lines[:-1]
            return "\n".join(lines)

        return {n: (strip(old), strip(new)) for n, (old, new) in edits.items()}

    def main(plan, path, numbers):
        edits = edits_of(plan)
        if sorted(edits) != list(range(1, 39)):
            sys.exit(f"{plan}: section 6.1 holds the edits {sorted(edits)}, not 1 to 38")
        script = open(path, encoding="utf-8").read()
        for n in numbers:
            old, new = edits[n]
            if n == 38:
                lines = script.split("\n")
                if lines.count(old) != 1:
                    sys.exit(f"edit 38: its line found {lines.count(old)} times: nothing was written")
                at = lines.index(old)
                first = at
                while lines[first - 1].startswith("    #"):
                    first -= 1
                lines[first : at + 1] = new.split("\n")
                script = "\n".join(lines)
                continue
            # A deleted line goes with its newline; any other old text is whole lines.
            if new == "":
                old += "\n"
            if script.count(old) != 1:
                sys.exit(f"edit {n}: old text found {script.count(old)} times: nothing was written")
            script = script.replace(old, new)
        open(path, "w", encoding="utf-8").write(script)
        print(f"{len(numbers)} edits applied: {' '.join(str(n) for n in numbers)}")

    if __name__ == "__main__":
        if len(sys.argv) < 4 or not all(a.isdigit() for a in sys.argv[3:]):
            sys.exit(__doc__)
        main(sys.argv[1], sys.argv[2], [int(a) for a in sys.argv[3:]])

  `$T/mk_ns_curated.py`:

    #!/usr/bin/env python3
    """One-off: write VARKA-661's three-step ns_curated.py from the one-off script that VARKA-660.md
    6.3 prints (VARKA-661 5.0).

    usage: mk_ns_curated.py <VARKA-660.md> <out: ns_curated.py>
    Not committed. It takes 6.3's block out of the plan, makes eight replacements, each of a text that
    must be in the block exactly once, and writes the result. With a text missing it writes nothing.
    """
    import sys

    plan, out = sys.argv[1:3]
    text = open(plan, encoding="utf-8").read()
    sec = text[text.index("### 6.3 The curated file") : text.index("### 6.4 The other files")]
    block = sec[sec.index("    #!/usr/bin/env python3") :].rstrip("\n").split("\n")
    src = "\n".join(line[4:] if line.strip() else "" for line in block) + "\n"

    EDITS = [
        # 1. the module's first lines: three steps for two
        (
            '''"""One-off: the nanosecond types in milestone 9's curated census (VARKA-660 6.3, run by row
    661), in two steps. Each rewrites <census_curated.txt> in place.

    usage: ns_curated.py new <census_curated.txt>
               before the admission table exists: the two entries that come into scope, and two
               lines of the file's header
           ns_curated.py rows <census_curated.txt> <nanos_admission.tsv>
               after the admission suite has written the table: the row that owes each cell
    ''',
            '''"""One-off: the nanosecond types in milestone 9's curated census (VARKA-660 6.3, as VARKA-661
    splits it), in three steps. Each rewrites <census_curated.txt> in place.

    usage: ns_curated.py casts <census_curated.txt>
               part A: the cast entries get row 674, which owes their nanosecond pairs
           ns_curated.py new <census_curated.txt>
               part C, before --asked: the two entries that come into scope, and two lines of the
               file's header
           ns_curated.py rows <census_curated.txt> <nanos_admission.tsv>
               part C, after the suite has answered every line: the row that owes each other cell
    ''',
        ),
        # 2. the cast entries are the first step's, every other accepted entry the last step's
        (
            '''def name_rows(path, admission):
        accepted = set()
        for line in open(admission, encoding="utf-8").read().splitlines()[1:]:
    ''',
            '''def name_rows(path, admission):
        """`admission` is the admission table's path, or None for the cast entries alone."""
        accepted = set()
        for line in open(admission, encoding="utf-8").read().splitlines()[1:] if admission else []:
    ''',
        ),
        (
            '''    lines = open(path, encoding="utf-8").read().split("\\n")
        if any("the nanosecond types are" in line for line in lines):
            sys.exit(f"{path} already names the rows: restore it (git checkout -- {path}) and run once")
        out, section, seen = [], None, set()
    ''',
            '''    mine = CASTS if admission is None else accepted - CASTS
        lines = open(path, encoding="utf-8").read().split("\\n")
        named = [line.split("|")[0] for line in lines if "the nanosecond types are" in line]
        if any((name in CASTS) == (admission is None) for name in named):
            sys.exit(f"{path} already names the rows: restore it (git checkout -- {path}) and run once")
        out, section, seen = [], None, set()
    ''',
        ),
        (
            '''            fields = fields if fields[0] == "unix_nanos" else edit(fields, first, accepted)
    ''',
            '''            fields = fields if fields[0] == "unix_nanos" else edit(fields, first, mine)
    ''',
        ),
        (
            '''    if name not in accepted and name not in CASTS:
            return fields
    ''',
            '''    if name not in accepted:
            return fields
    ''',
        ),
        (
            '''    absent = sorted((accepted | CASTS) - seen - {"unix_nanos"})
    ''',
            '''    absent = sorted(mine - seen - {"unix_nanos"})
    ''',
        ),
        # 7. the new step's name
        (
            '''    if sys.argv[1:2] == ["new"] and len(sys.argv) == 3:
            add_new(sys.argv[2])
    ''',
            '''    if sys.argv[1:2] == ["casts"] and len(sys.argv) == 3:
            name_rows(sys.argv[2], None)
        elif sys.argv[1:2] == ["new"] and len(sys.argv) == 3:
            add_new(sys.argv[2])
    ''',
        ),
        # 8. unix_date takes a DATE alone: Spark casts no timestamp to it (VARKA-661 4.3)
        (
            '''DATE_FUNCTIONS = "add_months datediff date_diff date_add dateadd date_sub unix_date".split()
    ''',
            '''DATE_FUNCTIONS = "add_months datediff date_diff date_add dateadd date_sub".split()
    ''',
        ),
    ]
    for n, (old, new) in enumerate(EDITS, 1):
        if src.count(old) != 1:
            sys.exit(f"replacement {n}: its text is in 6.3's block {src.count(old)} times: nothing written")
        src = src.replace(old, new)
    open(out, "w", encoding="utf-8").write(src)
    print(f"{out}: {len(EDITS)} replacements made")

- **The script's edits are `VARKA-660.md` 6.1's**, by their numbers, applied by
  `edits660.py` from the plan's own text, so that nothing is typed. Each run prints
  `<n> edits applied: <the numbers>`. If it prints `edit <n>: old text found <k> times: nothing
  was written`, the part ends: report the line. No rule decides a moved line of code. This
  plan's own edits of the script, eleven in all (one in part A, two in part B, eight in part
  C), are given as an old and a new text in the sheets; each old text is whole lines and is in
  the file once, or the part ends the same way.
- **A block of Python** in a sheet is printed as a block of Java is (the next item but one):
  remove the page's columns, and the block's first line goes at the column the step names,
  which is 0 for a line at the module's level and 4 for a line inside a function.
- **After the script's edits** of a part, run `ruff format dev/varka_census.py` and commit
  what it leaves.
- **A block of Java or Scala** in a sheet is indented by the page's four columns, or eight
  inside a numbered step, or twelve inside a numbered edit of a step; remove them, and the
  block's first line then goes at the column the step names, its other lines keeping their
  offset from it. Each line is within 100 columns there.
- **A compile, lint or first-run error in a line this plan gives: the closed procedure.** The
  Java and the Scala were not compiled (section 3). The builder makes a change of list 1 and
  no other; each is named in the description, and prediction 4 counts them.
  1. *Allowed, and only on a line that the compiler, checkstyle, scalastyle or Error Prone
     names:* add or delete an import; delete a variable reported unused; spell a member of
     Spark or of Varka as the tree spells it today (the same member, found by `grep` in the
     file that 5.2's table of names gives for it); add or drop a cast, a type witness or a
     `.toSeq()` between a Java and a Scala collection; re-wrap a line over 100 columns; add a
     `final`, an annotation or a suppression that Error Prone asks for by its name; and, in
     part A, a `switch` over `VarkaTypeId` that this plan does not list (5.1, step 2).
  2. *Not allowed:* the text of a call in `CALLS` or `MIXED_CALLS`; the types and the columns
     a call is asked over; the settings an ask sets; what `rejection` does with a condition;
     the table's header, the words of its cells and the order of its lines; an assertion or
     its expected value, but for the one case that 5.2, step 7, names; a test's
     `@DisplayName`; deleting, skipping or cancelling a test.
  3. *The stop.* At the first error whose repair is not on list 1, or after three rounds of
     compile and repair in one part, the part ends: report the error's text, its file and its
     line. The builder does not improvise a fourth round.
- **A command that prints something else.** The builder reads the step that the command checks
  again, corrects its own edit and reruns. If the output still differs and the sheet gives no
  rule for it, the part ends: report the command and its output.
- **The locale.** Every `sort` of a sheet is written `LC_ALL=C sort`, and the expected lines
  are in that order: under `en_US.UTF-8` a bare `sort` puts `datetime_sub` before `date_trunc`
  and `exha` before `SMT`, so the same sheet would print otherwise on a laptop and in CI.
- **A planted fault's check** prints the text that the table's last column quotes among its
  lines. It may print other lines beside it: the plant is good when the check fails and the
  quoted text is there.
- **`dev/varka_regen_generated.sh --all`**, wherever a sheet runs it. With a code change on
  the branch it makes one sbt call under the heavy lock, which it takes itself, over
  `VarkaTypesSuite`, `VarkaFuzzTypesSuite`, `VarkaCastMatrixSuite`,
  `VarkaNanosAdmissionSuite` (from part B on), Spark's three cast suites of sql/core,
  `VarkaCoverageSuite`, `VarkaWidthAuditSuite`, `VarkaEmittedBytesSuite` and
  `VarkaCoverageSuite` again; then it writes the census and checks it. It is the longest step
  of a part. On a CPU that is not the width audit's host it skips the audit, says so in a line
  that begins `varka regen: skipping width_audit.json`, and still exits 0: that is a pass.
  This row builds no kernel, so `sql/varka/coverage.json`, `sql/varka/emitted_bytes.json` and
  `sql/varka/width_audit.json` come out as they went in: if `git status --short` names one of
  the three after the run, the part ends: report `git diff --stat`.
- **A count this plan calls "on the prototype"** is the day's at the build: the sheet gives
  the rule that recomputes it, and the builder writes the day's number into the outcome.
- **How a run passes.** A JUnit run prints `Passed: Total N, Failed 0, Errors 0, Passed N`; a
  ScalaTest run exits 0 and prints `Tests: succeeded N, failed 0`.
- **Before the commit:** `dev/varka_precommit.sh`; `flock "$VARKA_HEAVY_LOCK"
  dev/varka_errorprone.sh --master "$(git rev-parse origin/master)"` and
  `flock "$VARKA_HEAVY_LOCK" dev/lint-java` where Java changed; `flock "$VARKA_HEAVY_LOCK"
  dev/lint-scala` where Scala changed; `ruff check dev/varka_census.py`;
  `python3 dev/varka_msg_check.py size --base origin/master`.
- **Before the pull request:** `/code-review high` on the diff, its findings fixed and named
  in the description; `python3 dev/varka_msg_check.py pr --title "<title>" --body-file <file>`.
  No push and no pull request without the lead's word.
- **Each part's last step** edits this file, a dated paragraph in section 14 with what was
  built, the commands' outputs and each prediction of section 7 that the part scores, and row
  661's status in `$M9/PLAN.md`.
- **Row 661's status in `$M9/PLAN.md`** is the status word and one parenthesis, the house
  form. After this plan merges the cell holds ``**Planned** (`VARKA-661.md`, 27 November
  2026: four parts, ...`` (section 12, edit 1). Each part puts its clause, and `; ` after it,
  immediately before the text `` `VARKA-661.md`, 27 November 2026: `` of that cell, which
  the file holds once: the command below prints `1` first.

        grep -c -F '`VARKA-661.md`, 27 November 2026:' $M9/PLAN.md

  Part A also turns `**Planned**` into `**Started**`, and part D turns `**Started**` into
  `**Done**`. So the cell begins, after part A,

        **Started** (part A, the two types and the cast matrix, #<number>; `VARKA-661.md`, 27 November 2026: four parts,

  and after part B,

        **Started** (part A, the two types and the cast matrix, #<number>; part B, Spark's answers, #<number>; `VARKA-661.md`, 27 November 2026: four parts,

  Part C adds its clause the same way, and no part changes anything else of the cell.
- **What runs only in CI:** the Build's catalyst and sql jobs at the defaults, its lint and
  docs jobs, and the Maven build. The option matrix and the width audit read nothing of this
  row; the lead dispatches no matrix for it. What the builder runs first is each sheet's
  commands.
- **Where another row merged first.** Row 656: `TIMESTAMP` is read on a lane, so the lines of
  `TIMESTAMP` this plan quotes from the three switches differ; the sheets place each new case
  by the switch's end, not by `TIMESTAMP`'s line. Row 653's parts B to F: the three generated
  JSON files are views that git ignores, and a sheet's `git status` list names none of them;
  nothing else of this row changes. Row 696: part A's B0 stops.

**How the prototype was run.** On the scratch copy that `VARKA-660.md` 3 describes (the
census after row 654's part b, with the seven date functions): the two registry lines were
appended to `types.tsv` as 5.1's command 2 prints them; the probe's 102 lines, the suites'
record and the table's answers were stand-ins written by row 660's own generator, since only
a JVM writes them; and everything else was this section's tools and steps, in order. After
each part the script passes `--self-test` and `--check`. After part D the script, the curated
file, the admission table, `census.tsv`, `casts.tsv` and the summary equal row 660's prototype
byte for byte. That first run left out this plan's own edits beside the interim rule. A
second run, after the first review and the owner's answers, applied every own edit of the
sheets in its part (part B's file list and readers' pattern and its edits of the lists of
generated files; part C's eight edits for the admission under either ANSI setting): each
state again passes `--self-test` and `--check`, and after part D the curated file, the
admission table, `census.tsv`, `casts.tsv` and the summary still equal row 660's prototype
byte for byte, since no stand-in answer differs between the settings. The script differs from
that prototype by the own edits alone. On those states every planted fault of parts B, C and
D that needs no JVM was run and printed what its sheet says; every command of the sheets that
needs no JVM and no git repository was rerun under `LC_ALL=C`; and the regeneration driver
passed `bash -n` and printed its steps in order with `--all --full --dry-run`.

A third run, of 27 November 2026 after the second review and the lead's probe, is the one
this plan's counts are from. Its copy differs from the one above in one curated line:
`unix_date` has `date` alone, which is what row 654's script leaves once edit 4 of section
12 is made (that script, with the edit, was run on milestone 8's curated file: it prints `68
entries edited, 26 of them no longer done, 16 added; 0 by default, 0 skipped, 0 absent`, and
its file equals the copy's). The table's answers were a stand-in written from the probe:
its answer at precisions 7 and 9, the answer of 9 at precision 8, which the probe did not
ask, and its result type. The four parts were applied in order with every own edit of the
sheets, cut out of this file's text, and with `ns_curated.py` as 5.0's tool makes it with
its eighth replacement. Each state passes `--self-test` and `--check`. The states differ
from the second run's in three things only: the line of `unix_date` in the curated file and
in `census.tsv`; the table, which has no line of `unix_date` and names a nanosecond result
for 20 entries where the stand-in of the second run named nine; and the script, by part
C's `names_decline`. So after part D the census's files equal row 660's prototype but for
`unix_date`'s four pairs. The planted faults of part C and its planted difference were run
again on this run's states.

### 5.1 Part A: the two types in the registry and the cast matrix

**B0, the premises.** Each line's comment is what it prints.

    test -f $M9/VARKA-661.md && echo merged                              # 1: merged
    git tag -l varka-m8                                                  # 2: varka-m8
    grep -c '^CURRENT = 9$' dev/varka_census.py                          # 3: 1
    grep -P "^TIMESTAMP\t" sql/varka/types.tsv | cut -f3                 # 4: ltz
    grep -c '_NANOS' sql/varka/types.tsv                                 # 5: 0
    grep -c '^    9: Milestone(9, M8_TYPES + \["ltz"\], \["ltz"\], None),$' dev/varka_census.py   # 6: 1
    grep -c '^NANOS = ' dev/varka_census.py                              # 7: 1
    git ls-files $M9/census_curated.txt $M9/casts.tsv | wc -l            # 8: 2
    grep -c '^  TIMESTAMP$' $MJ/varka/VarkaTypeId.java                   # 9: 1
    grep -c '^    return null;$' $MJ/varka/VarkaTypes.java               # 10: 1
    grep -c '^  private static Storage plain(int bits) {$' $MJ/varka/VarkaTypes.java   # 11: 1
    grep -c '^import org.apache.spark.sql.types.TimeType;$' $MJ/varka/VarkaTypes.java   # 12: 1
    grep -c 'private static <T> Site<T> declined(VarkaTypeId id) {' $TJ/VarkaFuzzTypes.java   # 13: 1
    grep -c '^  void castToTimestampNtz() {$' $TJ/VarkaTypeConformance.java   # 14: 1
    grep -c '"ltz"' $TJ/VarkaTypeConformance.java                        # 15: 2
    grep -c '^  void timestampPairs() throws IOException {$' $TJ/VarkaCastMatrixSuite.java   # 16: 1
    grep -c '^    SQLConf conf = SQLConf.get().clone();$' $TJ/VarkaCastMatrixSuite.java   # 17: 1
    grep -c 'assertTrue(VarkaTypes.declineReason(DataTypes.StringType).isEmpty());' $TJ/VarkaTypesSuite.java   # 18: 1
    python3 dev/varka_census.py --check; echo $?                         # 19: 0
    git branch --list 'varka-661a*'                                      # 20: nothing

| premise | it prints | what the builder does |
| :--- | :--- | :--- |
| 1, 2, 3 or 4 | anything else | the part ends: report which. The plan, milestone 8's close or row 654 is missing |
| 5 | `2` | part A is merged: go to 5.2 |
| 6 or 7 | `0` | the part ends: report `grep -n 'Milestone(9' dev/varka_census.py`. Another row changed milestone 9's entry |
| 8 | another number | the part ends: report `git ls-files $M9`. Row 696 has split the census's files, and this plan is cut for one file a list |
| 9 to 12 | not `1` | the part ends: report the line. The registry's file changed shape |
| 13 | `0` | no stop: row 656 removed the helper with its last user. Step 2 puts it back |
| 14 or 15 | another number | no stop: step 3's rule places the two tests and the two strings |
| 16 | `0` | no stop: step 3 puts `nanosPairs` after `plantedLegality`, and adds the import of `assertNull` |
| 17 or 18 | not `1` | no stop: step 3's rule |
| 19 | not `0` | the part ends: report its output. Master is red, which comes first |
| 20 | a branch | the part ends: report its name |

**The steps.**

1. Before any edit:

        python3 dev/varka_census.py --summary > $T/before.txt
        cp $M9/census.tsv $T/census-before.tsv; cp $M9/casts_suites.tsv $T/suites-before.tsv
        awk -F'\t' '$1=="cast"||$1=="try_cast"{n=split($8,a,","); for(i=1;i<=n;i++) if (a[i]=="date"||a[i]=="ntz"||a[i]=="time"||a[i]=="ltz") k++} END{print k+0}' $M9/census.tsv > $T/lost.txt

   `$T/lost.txt` is `k` of 4.5; it holds `0` on the prototype.
2. The registry and the switches, in the main sources and in `VarkaFuzzTypes`.
   - `$MJ/varka/VarkaTypeId.java`: the line `  TIMESTAMP` becomes three lines, `  TIMESTAMP,`,
     `  TIMESTAMP_NTZ_NANOS,` and `  TIMESTAMP_LTZ_NANOS`.
   - `$MJ/varka/VarkaTypes.java`, four edits. After the import of `TimeType`, two imports:
     `import org.apache.spark.sql.types.TimestampLTZNanosType;` and
     `import org.apache.spark.sql.types.TimestampNTZNanosType;`. In `idOf`, immediately before
     the line `    return null;`, at column 4:

            if (dataType instanceof TimestampNTZNanosType) {
              return VarkaTypeId.TIMESTAMP_NTZ_NANOS;
            }
            if (dataType instanceof TimestampLTZNanosType) {
              return VarkaTypeId.TIMESTAMP_LTZ_NANOS;
            }

     Immediately before the comment line `  // One case per id, with no default, so a new id
     does not compile until its facts are written.`, at column 2, with an empty line after it:

            // The decision that declines a nanosecond timestamp until its layout is built.
            private static final String NANOS_DECLINED = "its value is two words, the microseconds "
                + "and the nanoseconds within one, which no kernel reads before row 699";

     And in `entry`, as the switch's last two cases, immediately before the line `    };` that
     closes it (the third line above `  private static Storage plain(int bits) {`), at column 6:

            // A nanosecond timestamp, TIMESTAMP_NTZ(p) or TIMESTAMP_LTZ(p) with p from 7 to 9: one
            // id a kind, the precision a form (`m9/VARKA-660.md` 4.4). The storage is the eight
            // bytes of Arrow's nanosecond timestamp, which Spark's standard mapping writes
            // (`ArrowUtils.toArrowType`); the cache writes a struct of two vectors, and that
            // layout is row 699's to register.
            case TIMESTAMP_NTZ_NANOS -> new Info(id, "ntz_ns", "TIMESTAMP_NTZ(9)",
                new Admission.Declined(NANOS_DECLINED), plain(64),
                List.of(new TimestampNTZNanosType(7), new TimestampNTZNanosType(8),
                    new TimestampNTZNanosType(9)),
                List.of(), new Conformance.NotLanded());
            case TIMESTAMP_LTZ_NANOS -> new Info(id, "ltz_ns", "TIMESTAMP_LTZ(9)",
                new Admission.Declined(NANOS_DECLINED), plain(64),
                List.of(new TimestampLTZNanosType(7), new TimestampLTZNanosType(8),
                    new TimestampLTZNanosType(9)),
                List.of(), new Conformance.NotLanded());

   - `$MJ/VarkaConditionCompiler.java`, in `nullLeaf`: as the last case of the switch over
     `id`, immediately before the line `    });` that closes it, at column 6:

            // A nanosecond timestamp is two words (row 699): no lane holds its null as a value.
            case TIMESTAMP_NTZ_NANOS, TIMESTAMP_LTZ_NANOS -> Optional.empty();

   - `$CJ/VarkaLaneArrow.java`: as the last case of the switch that holds the line
     `      case BOOLEAN -> new Vector(id, BitVector.class,`, at column 6:

            // Two Arrow vectors a value in the cache, which no lane reads before row 699.
            case TIMESTAMP_NTZ_NANOS, TIMESTAMP_LTZ_NANOS -> null;

   - `$TJ/VarkaFuzzTypes.java`, in `of`: as the switch's last case, at column 6:

            case TIMESTAMP_NTZ_NANOS, TIMESTAMP_LTZ_NANOS ->
                entry(id, declined(id), declined(id), declined(id));

     Where premise 13 printed `0`, the helper goes back, immediately before the comment
     `/** The entry of {@code id}. Adding a type to {@link VarkaTypeId} fails to compile here. */`,
     at column 2, with an empty line after it (and the import of `VarkaTypes.Admission` if the
     compiler asks for it):

            /** The registry's decision for a declined type, which no fuzzer draws. */
            private static <T> Site<T> declined(VarkaTypeId id) {
              if (VarkaTypes.info(id).admission() instanceof Admission.Declined d) {
                return new NotApplicable<>("declined: " + d.decision());
              }
              throw new IllegalStateException(id + " is not declined in the registry");
            }

   - **A switch this sheet does not name.** Step 4's compile names it: `the switch expression
     does not cover all possible input values`. The two ids take the arm that the switch gives
     a type no lane reads: an empty `Optional`, a `null`, or a decline. If the switch has no
     such arm, the part ends: report the file and the switch.
3. The tests.
   - `$TJ/VarkaTypesSuite.java`: two imports, `org.apache.spark.sql.types.TimestampLTZNanosType`
     and `org.apache.spark.sql.types.TimestampNTZNanosType`, after the import of
     `org.apache.spark.sql.types.DecimalType`. In `declineNamesTheType`, after the line of
     premise 18 (where it printed `0`: as the method's last statements), at column 4:

            // A nanosecond timestamp declines by name at every precision, and each precision of a
            // kind has the kind's one id; precision 6 is the microsecond type, with its own id.
            for (int p = 7; p <= 9; p++) {
              assertEquals("a timestamp_ntz(" + p + ") column: its value is two words, the "
                  + "microseconds and the nanoseconds within one, which no kernel reads before row "
                  + "699", VarkaTypes.declineReason(new TimestampNTZNanosType(p)).orElseThrow());
              assertEquals(VarkaTypeId.TIMESTAMP_LTZ_NANOS,
                  VarkaTypes.of(new TimestampLTZNanosType(p)).orElseThrow());
            }
            assertEquals(VarkaTypeId.TIMESTAMP_NTZ,
                VarkaTypes.of(DataTypes.TimestampNTZType).orElseThrow());

   - `$TJ/VarkaTypeConformance.java`: the list `CAST_TESTS`, which is in alphabetical order
     and after row 654 holds `"ltz"` before `"ntz"`, gains two strings: `"ltz_ns"` directly
     after `"ltz"`, and `"ntz_ns"` directly after `"ntz"`. So the list reads `..., "int",
     "ltz", "ltz_ns", "ntz", "ntz_ns", "short", ...`, re-wrapped within 100 columns. After the
     method `castToTimestampNtz`, at column 2:

            @Test
            @DisplayName("the cast to TIMESTAMP_NTZ(9), in LEGACY, ANSI and TRY")
            void castToTimestampNtzNanos() {
              castTo("ntz_ns");
            }

            @Test
            @DisplayName("the cast to TIMESTAMP_LTZ(9), in LEGACY, ANSI and TRY")
            void castToTimestampLtzNanos() {
              castTo("ltz_ns");
            }

   - `$TJ/VarkaCastMatrixSuite.java`, three edits. In the comment above `TYPES`, the line
     `   * of them), a time's precision (three), an interval's start and end fields (all of them). A`
     becomes two lines, `   * of them), a time's precision (three), a nanosecond timestamp's
     (three), an interval's start and` and `   * end fields (all of them). A`; where the old
     line is not in the file, a row reworded the comment, and this edit is left out. In
     `castExpression`, after the line that sets
     `SQLConf.ANSI_ENABLED()` on `conf`, at column 4:

            // A nanosecond target's name parses only with the preview flag on: set here, and
            // not left to the tests' default.
            conf.setConfString(SQLConf.TIMESTAMP_NANOS_TYPES_ENABLED().key(), "true");

     And one test, after the method `timestampPairs` (premise 16), at column 2:

            @Test
            @DisplayName("the nanosecond timestamps' pairs are the 20 Cast allows, in every mode")
            void nanosPairs() throws IOException {
              Map<String, Legality> file = committed();
              long pairs = 0;
              for (var e : file.entrySet()) {
                List<String> ends = List.of(e.getKey().split(">"));
                if (ends.contains("ntz_ns") || ends.contains("ltz_ns")) {
                  pairs++;
                  assertEquals(new Legality(true, true, true), e.getValue(), e.getKey());
                }
              }
              assertEquals(20, pairs);
              assertEquals(new Legality(true, true, true), file.get("ntz_ns>ntz_ns"));
              assertEquals(new Legality(true, true, true), file.get("time>ltz_ns"));
              assertNull(file.get("ntz_ns>int"));
              assertNull(file.get("dt>ltz_ns"));
            }

4. Compile: `flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/Test/compile' 'sql/Test/compile'`
   prints `[success]` twice.
5. The registry's two tables:

        VARKA_TYPES_REGEN=true VARKA_FUZZ_TYPES_REGEN=true flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaTypesSuite *VarkaFuzzTypesSuite'
        grep '_NANOS' sql/varka/types.tsv

   The second command prints two lines, tab-separated:
   `TIMESTAMP_NTZ_NANOS TimestampNTZNanosType ntz_ns TIMESTAMP_NTZ(9) declined - - 64 plain - - - -`
   and the same with `LTZ` and `ltz_ns`. From here to step 9 the census script fails, since
   the registry names two types milestone 9's entry does not.
6. The script: `python3 $T/edits660.py $M9/VARKA-660.md dev/varka_census.py 1 2 3 38 7 8 9 10
   11 12 13 14 15 16 27 28 29` (one line; 38 comes after 3, which writes the line it finds),
   which prints `17 edits applied: ...`. Then this plan's interim rule, in `row`, the one
   text of part A's script that no tool applies. Old, two lines, the first at column 4:

            t = expand(",".join(p for p in types.split(",") if p and not p.endswith("!")))
            res = t if result == "same" else expand(result)

   New, seven lines, the first at column 4 and the two under `if` at columns 4 and 8:

            t = expand(",".join(p for p in types.split(",") if p and not p.endswith("!")))
            # A nanosecond type is admitted by Spark's own answer and by no list (m9/PLAN.md 2.4): until
            # the census reads that answer, no entry admits one but a cast entry, whose types are the
            # cast table's, which VarkaCastMatrixSuite holds to Cast.
            if name not in CAST_ENTRY_MODES:
                t = [x for x in t if x not in NS]
            res = t if result == "same" else expand(result)

   Then `ruff format dev/varka_census.py`, and `python3 dev/varka_census.py --self-test; echo
   $?` prints `0`. If `ruff format` was run before the interim rule was typed, nothing
   breaks: it changes no line of the rule's old text. Run it again after the rule.
7. The curated file: `python3 $T/ns_curated.py casts $M9/census_curated.txt | tee
   $T/casts.txt` prints `5 entries edited, 0 of them no longer done; 0 by default, 0 absent`
   on the prototype. The first number is 5 at the build too (`cast`, `try_cast`, `date`,
   `time`, `timestamp`); the second is the day's, an alias that was `done`. A line `absent:
   <name> ...` ends the part: report it.
8. The probe: `VARKA_CASTS_REGEN=true flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly
   *VarkaCastMatrixSuite'`. It writes `$M9/casts_fused.tsv` and fails exactly three tests by
   design, since `casts.tsv` has no nanosecond pair yet: "casts.tsv's legality agrees with
   Cast over every ordered pair, in every mode", "a pair whose legality is flipped in one mode
   fails the comparison, naming the pair" (it reads the same file) and "the nanosecond
   timestamps' pairs are the 20 Cast allows, in every mode". Any other failed test, or an
   error, ends the part: report that test's output. Do not catch an exception in the suite.
9. `census`, which writes `$M9/census.tsv`, `$M9/casts.tsv` and the docs' support table and
   exits 0.
10. `dev/varka_regen_generated.sh --all`, which exits 0. 5.0 says what it runs, how long it
    is and when the part ends. What it moves here: Spark's three cast suites of sql/core
    write the two names into `$M9/casts_suites.tsv`; every other generated file comes out as
    steps 5 to 9 left it. Then `python3 dev/varka_census.py --summary > $T/after.txt`.
11. The prose: the two sentences that count the census's types. `grep -n
    "plans/m9/census.tsv" README.md docs/sql-varka.md` finds them, taking in
    `docs/sql-varka.md` the hit that is not between the markers `<!-- BEGIN generated support
    table -->` and `<!-- END generated support table -->` (never edit a line between them).
    - `docs/sql-varka.md`: the line above the hit ends `over its fourteen`. It becomes `over
      its sixteen`. The sentence lists no type, and nothing else of it changes.
    - `README.md`: in the line below, `fourteen` becomes `sixteen`.

            tracks fourteen types: BOOLEAN, TINYINT, SMALLINT, INT, BIGINT, FLOAT, DOUBLE,

    - `README.md`, the end of the list, by what `grep -c 'which no kernel reads yet. For'
      README.md` prints. Where it prints `1` (row 654's text), old, two lines:

            DECIMAL, DATE, TIME, TIMESTAMP_NTZ, the year-month and day-time intervals, and TIMESTAMP,
            which no kernel reads yet. For

      New, two lines:

            DECIMAL, DATE, TIME, TIMESTAMP_NTZ, the year-month and day-time intervals, and three that no
            kernel reads yet: TIMESTAMP and the nanosecond TIMESTAMP_NTZ(p) and TIMESTAMP_LTZ(p). For

      Where it prints `0` (row 656's part B reworded it), old, one line:

            DECIMAL, DATE, TIME, TIMESTAMP_NTZ, TIMESTAMP and the year-month and day-time intervals. For

      New, two lines:

            DECIMAL, DATE, TIME, TIMESTAMP_NTZ, TIMESTAMP, the year-month and day-time intervals, and two
            that no kernel reads yet: the nanosecond TIMESTAMP_NTZ(p) and TIMESTAMP_LTZ(p). For

    - Where an old line of this step is not in its file, a later row reworded the sentence.
      Then the builder changes the count word to the number that is two greater, ends the
      list of types with `, and two that no kernel reads yet: the nanosecond TIMESTAMP_NTZ(p)
      and TIMESTAMP_LTZ(p)`, so that the list has one `and` before its last item, and quotes
      the sentence it wrote in the description.
    - A count in any other sentence counts the types a kernel reads, and stays.
12. `$M9/PLAN.md`, row 661's cell, by 5.0's rule: `**Planned**` becomes `**Started**`, and
    the clause is `part A, the two types and the cast matrix, #<number>`. This file's section
    14: part A's paragraph.

**The tests.**

| suite (language, module) | cases | inputs | expected, and its oracle | the failure only it catches |
| :--- | :--- | :--- | :--- | :--- |
| the doctests of `legal` (Python, `dev/varka_census.py`; `VARKA-660.md` 6.1, edit 27) | `ntz_ns` with `int`, `bool`, `dt`, `date`, `time`, `ltz`, `ltz_ns` and itself; the count of pairs legal in both modes over the 16 types | the type names | three times `(False, False)`, then five times `(True, True)`; `20`. The oracle is `Cast`, read in 4.4 | a wrong transcription that the next regeneration would write into `casts.tsv` before any JVM runs |
| the doctests of `ns_cast_kind` and `column_type` (Python; edits 10 and 15) | `ntz` to `ntz_ns`, `ltz_ns` to `ltz`, `ntz_ns` to `ltz_ns`; through `cast_kind`: `date` to `ltz_ns`, `ntz_ns` to `time`, `time` to `ntz_ns`; the column types `timestamp_ntz(7)` and `TIMESTAMP_LTZ(9)` | the names | `widening`, `floor division`, `local to instant`; `local to instant`, `floor modulo`, `session date`; `('ntz_ns', 'p7')`, `('ltz_ns', 'p9')` | the call of edit 9 nested under the identity block, where it never runs; a nanosecond coverage column with no census type |
| `VarkaCastMatrixSuite` (Java, catalyst), unchanged: "casts.tsv's legality agrees with Cast over every ordered pair, in every mode" | every ordered pair of the 16 types, every form of each: 3 forms of each new type against every form of every type | `$M9/casts.tsv` | pass; the oracle is `Cast.canCast`, `canAnsiCast` and `canTryCast` | `legal()` wrong for a pair or for one precision of it |
| the same suite, new: "the nanosecond timestamps' pairs are the 20 Cast allows, in every mode" | the count; each pair legal in the three modes; `ntz_ns>ntz_ns` and `time>ltz_ns` present; `ntz_ns>int` and `dt>ltz_ns` absent | `$M9/casts.tsv` | pass | the registry's census names and the census's types both dropped together, which every other test would pass |
| the same suite, unchanged: "casts_fused.tsv is what the compiler fuses, over every form of every legal cast" | every legal cast of two forms, 102 of them with a nanosecond form | a column `x` of the source form and `CAST(x AS <target>)`, `try_cast` in TRY, parsed with the flag set | 102 lines, each `declined` in the three modes, and no exception; the oracle is the compiler | a compiler that raises, or fuses, over a type the registry declines; a target that does not parse because the flag was left to a default |
| `VarkaTypesSuite` (Java, catalyst): "a column of an owed, declined or condition-only type declines naming its type", extended | `TimestampNTZNanosType(p)` and `TimestampLTZNanosType(p)` for `p` of 7, 8 and 9; `TimestampNTZType` | the types | the decline text of 4.2 with `timestamp_ntz(<p>)`; the id `TIMESTAMP_LTZ_NANOS` three times; the id `TIMESTAMP_NTZ` | a precision that maps to no id or to the other kind's; the microsecond type taken for a nanosecond one |
| the same suite, unchanged: "sql/varka/types.tsv is the registry as it is", "every form maps back to its own id, and a type outside the table to none", "each entry's storage is the width of the Arrow type Spark writes for every form" | all 16 entries, every form | the registry | pass: 64 bits for each of the six nanosecond forms | an entry whose storage is not what Spark's mapping writes |
| `VarkaFuzzTypesSuite` (Java, catalyst), unchanged | "a declined type is never drawn, and a type the registry reads is not left out" | the registry | pass; `sql/varka/fuzz/types.tsv` has the two ids with `n/a` | a fuzzer that draws a type no kernel reads |
| every `Varka<Type>ConformanceSuite` (Java, catalyst): the two new cast tests through the base class | the suite's type cast to `TIMESTAMP_NTZ(9)` and to `TIMESTAMP_LTZ(9)`, in each mode | each suite's fixture: its nulls, both ends of the type, its special values | cancelled, naming row 674 as owing, in the suites of `date`, `ntz`, `time` and, once row 656 has landed it, `ltz`; cancelled as "casts.tsv has no cast" in every other suite; failed in none | a census type with no cast test, which fails every cast test of every suite (fact 18's assertion above it) |
| `VarkaLaneArrowSuite` (Scala, sql/core), unchanged | "a type has an Arrow vector exactly when a lane reads it, or it is an output vector" | every id | pass: no vector for the two ids | a vector registered for a type no lane reads |
| Spark's three cast suites under Varka (Scala, sql/core), unchanged | all | Spark's own cast tests | pass; the record's lines over the six forms read `ntz_ns` or `ltz_ns` in `from` or `to` | the record and the matrix naming one type by two names |

**The planted faults**, each run by hand and reverted; each fails the named check by an
assertion.

| plant | the check that fails | with |
| :--- | :--- | :--- |
| in `legal`, the list of the nanosecond branch gains `"dt"` | `python3 dev/varka_census.py --self-test` exits 1 | `Expected:` three times `(False, False)` and `Got:` `(False, False), (False, False), (True, True), ...`; then `Expected: 20`, `Got: 24` |
| delete the two lines `if name not in CAST_ENTRY_MODES:` and `t = [x for x in t if x not in NS]` | `python3 dev/varka_census.py --check` exits 1 | `census: sql/varka/plans/m9/census.tsv is stale; run dev/varka_census.py`, or, where an `ALL` entry is `done` that day, lines that hold `need done disagrees with fused`. The summary then reads `0 / 31` for each type on the prototype |
| in `VarkaTypes.idOf`, return `TIMESTAMP_NTZ_NANOS` for a `TimestampLTZNanosType` | `build/sbt 'catalyst/testOnly *VarkaTypesSuite'` fails "every form maps back to its own id, and a type outside the table to none" | `expected: <TIMESTAMP_LTZ_NANOS> but was: <TIMESTAMP_NTZ_NANOS>` |
| take `"ntz_ns"` out of `CAST_TESTS` | `build/sbt 'catalyst/testOnly *VarkaIntConformanceSuite'` fails every cast test | "the cast tests are one per census type: add a test for each census type the registry names" |

**The commands, in order, each with what it must print.**

    # 1. the doctests and the check
    python3 dev/varka_census.py --self-test; echo $?                    # 0
    python3 dev/varka_census.py --check; echo $?                        # 0
    # 2. the registry's tables
    grep '_NANOS' sql/varka/types.tsv | cut -f1,3,4,5,8,9               # two lines: the id, ntz_ns or ltz_ns, TIMESTAMP_NTZ(9) or TIMESTAMP_LTZ(9), declined, 64, plain
    git diff $B -- sql/varka/types.tsv sql/varka/fuzz/types.tsv | grep -c '^+[^+]'   # 4
    git diff $B -- sql/varka/types.tsv sql/varka/fuzz/types.tsv | grep -c '^-[^-]'   # 0
    # 3. the two types in the summary: the cast entries alone
    grep -E '^  (ntz_ns|ltz_ns) ' $T/after.txt
                                    #   ntz_ns  new         0 / 5
                                    #   ltz_ns  new         0 / 5
    grep '(entry, type) pairs' $T/before.txt $T/after.txt; cat $T/lost.txt
                                    # the pairs: the second 10 above the first; fused today: the first less the number of lost.txt
    grep '^in scope:' $T/before.txt $T/after.txt                        # the same line twice
    # 4. the cast matrix
    awk -F'\t' '$1 ~ /_ns$/ || $2 ~ /_ns$/' $M9/casts.tsv | wc -l                                  # 20
    awk -F'\t' '($1 ~ /_ns$/ || $2 ~ /_ns$/) && $3=="yes" && $4=="yes" && $5=="yes"' $M9/casts.tsv | wc -l   # 20
    awk -F'\t' '$1 ~ /_ns$/ || $2 ~ /_ns$/ {print $6}' $M9/casts.tsv | LC_ALL=C sort | uniq -c
                                    # eight lines, each a count and a kind, in this order:
                                    #   3 floor division
                                    #   1 floor modulo
                                    #   5 instant to local
                                    #   4 local to instant
                                    #   2 relabel or truncation
                                    #   1 scale up
                                    #   2 session date
                                    #   2 widening
    awk -F'\t' '$1 ~ /_ns$/ || $2 ~ /_ns$/ {print substr($8, 1, 4)}' $M9/casts.tsv | LC_ALL=C sort | uniq -c
                                    # three lines, in this order: 9 "SMT ", 1 exha, 10 zone
    awk -F'\t' '($1 ~ /_ns$/ || $2 ~ /_ns$/) && $10 != "674" {print $1, $2, $10}' $M9/casts.tsv
                                    # ntz_ns ntz 699,674
                                    # ltz_ns ltz 699,674
    grep '^cast pairs' $T/before.txt $T/after.txt   # "over the N types: N*N ordered, L legal", then N+2, its square and L+20 (14, 196, 129, then 16, 256, 149 on the prototype)
    # 5. the probe
    awk -F'\t' '$1 ~ /_ns$/ || $2 ~ /_ns$/' $M9/casts_fused.tsv | wc -l                            # 102
    awk -F'\t' '($1 ~ /_ns$/ || $2 ~ /_ns$/) && /fused/' $M9/casts_fused.tsv | wc -l               # 0
    git diff $B -- $M9/casts_fused.tsv | grep -c '^-[^-]'               # 0: no line of another type moved
    # 6. the five cast entries name row 674, and no other entry admits the types
    awk -F'\t' 'NR>1 && $4=="in" && (","$6",") ~ /,(ntz_ns|ltz_ns),/ {print $1, $11}' $M9/census.tsv | LC_ALL=C sort
                                    # five lines, in this order: cast, date, time, timestamp, try_cast; after each name its rows, 674 among them
    # 7. the suites' record: the renamed lines and nothing else
    python3 - <<'EOF'
    old = open("target/varka-661/suites-before.tsv").read().splitlines()
    name = lambda form: {"TIMESTAMP_NTZ": "ntz_ns", "TIMESTAMP_LTZ": "ltz_ns"}.get(form[:13]) if form[13:] in ("(7)", "(8)", "(9)") else None
    body = []
    for l in old[1:]:
        f = l.split("\t")
        f[1], f[2] = name(f[3]) or f[1], name(f[4]) or f[2]
        body.append("\t".join(f))
    body.sort(key=lambda l: l.split("\t")[:6])
    new = open("sql/varka/plans/m9/casts_suites.tsv").read().splitlines()
    print(new == [old[0]] + body, sum("_ns\t" in l for l in new))
    EOF
                                    # True, and the count of renamed lines (36 on the prototype)
    # 8. the docs' cast matrices have a column for each type, and the by-type table two lines
    awk '/BEGIN generated support table/,/END generated support table/' docs/sql-varka.md | grep -c '^| from .* `ntz_ns` | `ltz_ns` |$'   # 3
    awk '/BEGIN generated support table/,/END generated support table/' docs/sql-varka.md | grep -c -E '^\| `Timestamp(NTZ|LTZ)NanosType` \(`(ntz|ltz)_ns`\) \| plain \| 5 \| 0 \| 0 \| '   # 2
    # 9. what the working tree holds: the files below and no other
    git status --short
    # 10. the planted faults of the table above, one at a time, each reverted
    # 11. the JVM
    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaCastMatrixSuite *VarkaTypesSuite *VarkaFuzzTypesSuite'
                                    # Failed 0, Errors 0; the cast suite has one test more than on master
    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *ConformanceSuite'
                                    # Failed 0, Errors 0; two lines "the cast to TIMESTAMP_NTZ(9) ..." and "the cast to TIMESTAMP_LTZ(9) ..." a suite, each cancelled
    flock "$VARKA_HEAVY_LOCK" build/sbt 'sql/testOnly *VarkaCastWithAnsiOffSuite *VarkaCastWithAnsiOnSuite *VarkaTryCastSuite *VarkaLaneArrowSuite'
                                    # All tests passed.
    # 12. the other tools
    dev/varka_new_lane.py --check; echo $?                              # 0
    python3 dev/varka_census.py --milestone 8 --check; echo $?          # 0

Command 9's list, each ` M`: `$MJ/varka/VarkaTypeId.java`, `$MJ/varka/VarkaTypes.java`,
`$MJ/VarkaConditionCompiler.java`, `$CJ/VarkaLaneArrow.java`, `$TJ/VarkaFuzzTypes.java`,
`$TJ/VarkaTypesSuite.java`, `$TJ/VarkaTypeConformance.java`, `$TJ/VarkaCastMatrixSuite.java`,
`dev/varka_census.py`, `sql/varka/types.tsv`, `sql/varka/fuzz/types.tsv`,
`$M9/census_curated.txt`, `$M9/census.tsv`, `$M9/casts.tsv`, `$M9/casts_fused.tsv`,
`$M9/casts_suites.tsv`, `docs/sql-varka.md`, `README.md`, `$M9/PLAN.md` and `$M9/VARKA-661.md`.
A file beyond the list is a stop, but a switch that step 2's last rule added.

**Expectations**, for the builder and the reviewer to tick.

- [ ] `VarkaTypeId` has 16 members; `VarkaTypes.of` gives one id for the three precisions of a
  kind; a nanosecond column declines with 4.2's text.
- [ ] `sql/varka/types.tsv` and `sql/varka/fuzz/types.tsv` gain two lines each and lose none.
- [ ] `casts.tsv` gains 20 lines, each legal in the three modes; `casts_fused.tsv` gains 102
  lines, none fused, and no line of another type moves.
- [ ] The summary reads `0 / 5` for each new type; the entries in scope do not move; the pairs
  fused fall by `$T/lost.txt`'s number and no other.
- [ ] What does not change: `nanos_admission.tsv` does not exist; no emitted byte, no coverage
  row, no benchmark, no width audit; milestone 8's files.
- [ ] The description states `k`, the number of renamed record lines, each switch the compile
  named beyond this sheet, what the author's review found, and the test time added (one test
  in `VarkaCastMatrixSuite`, 102 probes, two cancelled tests in each conformance suite).

**Done when** every command prints as above and the expectations are ticked.

**Not here.** No admission table, no `--asked`, no `VarkaNanosAdmissionSuite`. No change of
`TIMESTAMP`'s entry. No `ntz_ns` or `ltz_ns` in any fused field or in any entry's types in the
curated file. No new `Storage` kind, no Arrow vector, no coverage column, no fixture.

### 5.2 Part B: Spark's answers, as a table

**B0, the premises.**

    grep -c '_NANOS' sql/varka/types.tsv                                 # 1: 2
    grep -c '^NS = \["ntz_ns", "ltz_ns"\]$' dev/varka_census.py          # 2: 1
    grep -c 'if name not in CAST_ENTRY_MODES:' dev/varka_census.py       # 3: 1
    test ! -e $M9/nanos_admission.tsv && echo none                       # 4: none
    grep -c 'def check(' $TS/VarkaSqlResolve.scala                       # 5: 0
    grep -c '^import org.apache.spark.sql.catalyst.analysis.{SimpleAnalyzer, UnresolvedAttribute}$' $TS/VarkaSqlResolve.scala   # 6: 1
    test -f $TJ/VarkaCensusFiles.java && echo there                      # 7: there
    grep -c '^  static List<String> fusionMoves(List<String> committed, List<String> fresh) {$' $TJ/VarkaCastMatrixSuite.java   # 8: 1
    grep -c '^FILES = ("census.tsv", "census_curated.txt", "casts.tsv", "casts_fused.tsv", "casts_suites.tsv")$' dev/varka_census.py   # 9: 1
    grep -c -F '(?:census|casts)|MILESTONE = "m' dev/varka_census.py     # 10: 1
    grep -c '^/plans/m9/casts_suites.tsv merge=varka-generated$' sql/varka/.gitattributes   # 11: 1
    grep -c '^    "casts_suites.tsv": 6,$' dev/varka_generated_merge.py  # 12: 1
    grep -c -F -x 'want sql/varka/plans/m9/casts_fused.tsv && {' dev/varka_regen_generated.sh   # 13: 1
    grep -c -F -x 'if [ ${#steps[@]} -gt 0 ]; then' dev/varka_regen_generated.sh   # 14: 1
    python3 dev/varka_census.py --check; echo $?                         # 15: 0
    git branch --list 'varka-661b*'                                      # 16: nothing
    grep -c '^    "VarkaCastMatrixSuite",$' dev/varka_push_check.py       # 17: 1
    grep -c -F -x '# casts_fused.tsv and casts_suites.tsv (whose suites read it), then coverage.json and the docs' dev/varka_regen_generated.sh   # 18: 1
    grep -c -F -x '# `casts_suites.tsv` has one per suite, form pair and mode, its first six columns.' dev/varka_generated_merge.py   # 19: 1
    grep -c '^import org.apache.spark.sql.types.{DataType, DoubleType, FloatType, StringType}$' $TS/VarkaSqlResolve.scala   # 20: 1
    grep -c '^unix_date|date|' $M9/census_curated.txt                    # 21: 1

| premise | it prints | what the builder does |
| :--- | :--- | :--- |
| 1, 2 or 3 | anything else | the part ends: report "part A is not merged" |
| 4 | nothing | part B is merged: go to 5.3 |
| 21 | `0` | the part ends: report `grep -n '^unix_date' $M9/census_curated.txt`. Row 654 was built with `unix_date` over a timestamp, which Spark does not take (4.3): the entry would be asked, and `CALLS` has no call for it. The curated line is corrected by its own pull request first, not here |
| 5 to 14, 17 to 20 | not what the comment says | the part ends: report the line. A file changed shape since the plan |
| 15 | not `0` | the part ends: report its output |
| 16 | a branch | the part ends: report its name |

**The steps.**

1. `python3 dev/varka_census.py --summary > $T/before.txt`.
2. `$TS/VarkaSqlResolve.scala`, four edits. The imports: above the line of premise 6, the
   line `import scala.util.control.NonFatal`, an empty line, and the line
   `import org.apache.spark.SparkThrowable`. The line of premise 20, the import of the types,
   becomes two lines, the second at column 2:

        import org.apache.spark.sql.types.{AnyTimestampNanoType, DataType, DoubleType, FloatType,
          StringType, TimestampNTZType, TimestampType}

   Inside `object VarkaSqlResolve`, after the method `parseAsWritten` and an empty line, at
   column 2:

          /**
           * Spark's own answer to whether a query may hold `text` over `columns`: the type of its
           * result, or the condition (the error class) of the error Spark raises for it. The analyzer
           * runs as [[resolveAsWritten]] runs it, and then the check every analyzed plan passes
           * (`CheckAnalysis.checkAnalysis`: each expression's `checkInputDataTypes` and the checks of
           * whole operators, the nanosecond subtraction's among them), which no other method of this
           * object runs. An error that carries no condition is no answer, and is thrown as it is.
           */
          def check(text: String, columns: Seq[Attribute]): VarkaChecked = {
            try {
              val byName = columns.map(a => a.name -> a).toMap
              val bound = CatalystSqlParser.parseExpression(text).transformUp {
                case UnresolvedAttribute(Seq(name)) if byName.contains(name) => byName(name)
              }
              val analyzed =
                SimpleAnalyzer.execute(Project(Seq(Alias(bound, "a")()), LocalRelation(columns)))
              SimpleAnalyzer.checkAnalysis(analyzed)
              VarkaChecked(analyzed.output.head.dataType, null, operandTypes(analyzed))
            } catch {
              case NonFatal(e) if conditionOf(e) != null => VarkaChecked(null, conditionOf(e), "")
            }
          }

          private def conditionOf(e: Throwable): String = e match {
            case s: SparkThrowable => s.getCondition
            case _ => null
          }

          /**
           * The timestamp types at which the nodes of `plan` that have two or more timestamp operands
           * read them, as SQL names joined by commas: one name where the analyzer brought the operands of
           * every such node to one type, and the empty string where no node has two.
           */
          private def operandTypes(plan: LogicalPlan): String =
            plan.flatMap(_.expressions).flatMap(_.collect {
              case e if e.children.count(c => isTimestamp(c.dataType)) > 1 =>
                e.children.map(_.dataType).filter(isTimestamp).map(_.sql)
            }).flatten.distinct.mkString(",")

          private def isTimestamp(t: DataType): Boolean = t match {
            case TimestampType | TimestampNTZType | _: AnyTimestampNanoType => true
            case _ => false
          }

   And at the file's end, after the brace that closes the object and an empty line, at
   column 0:

        /**
         * [[VarkaSqlResolve.check]]'s answer: the type of the result where Spark takes the call, with
         * the timestamp types its two-timestamp nodes read (`operands`), or the condition it rejects the
         * call with. One of `dataType` and `condition` is null.
         */
        final case class VarkaChecked(dataType: DataType, condition: String, operands: String)

3. `$TJ/VarkaNanosAdmissionSuite.java`, new: the license header of a neighbouring Java file
   (the 16 comment lines above its `package` line, and an empty line), then, at column 0:

        package org.apache.spark.sql.catalyst.expressions.codegen.varka;

        import static org.junit.jupiter.api.Assertions.assertEquals;
        import static org.junit.jupiter.api.Assertions.assertThrows;
        import static org.junit.jupiter.api.Assertions.assertTrue;
        import static org.junit.jupiter.api.Assertions.fail;

        import java.io.IOException;
        import java.nio.charset.StandardCharsets;
        import java.nio.file.Files;
        import java.nio.file.Path;
        import java.util.ArrayList;
        import java.util.LinkedHashMap;
        import java.util.List;
        import java.util.Map;
        import java.util.Set;

        import org.junit.jupiter.api.DisplayName;
        import org.junit.jupiter.api.Test;
        import org.opentest4j.AssertionFailedError;

        import scala.jdk.javaapi.CollectionConverters;

        import org.apache.spark.sql.catalyst.expressions.Attribute;
        import org.apache.spark.sql.internal.SQLConf;
        import org.apache.spark.sql.internal.SQLConf$;
        import org.apache.spark.sql.types.DataType;
        import org.apache.spark.sql.types.DataTypes;

        /**
         * Spark's own answer to which census entries take a nanosecond timestamp (VARKA-661): for each
         * line of {@code nanos_admission.tsv}, an entry and one of the two nanosecond census types, the
         * entry's call is analyzed and checked over a column of the type at each precision, with
         * {@code spark.sql.timestampNanosTypes.enabled} set on and under both settings of
         * {@code spark.sql.ansi.enabled}. {@code dev/varka_census.py} admits a nanosecond type for an
         * entry from these answers and from no list, where Spark takes the call under at least one of the
         * two settings, so a sync of Spark that moves an answer fails this suite with the command that
         * writes the file again.
         *
         * <p>The file's lines are the census's: {@code python3 dev/varka_census.py --asked} writes one
         * for each entry it asks and each type. This suite answers the lines it finds and adds none.
         */
        @VarkaSuite
        public final class VarkaNanosAdmissionSuite {

          private static final String FILE = "nanos_admission.tsv";

          private static final String HEADER = "entry\ttype\tp7\tp8\tp9\tresult";

          /** A cell of an answer: Spark takes the call. Any other cell is the condition it raises. */
          private static final String ACCEPTED = "accepted";

          /** The regeneration command, which a failure names. */
          private static final String REGEN = "VARKA_NANOS_ADMISSION_REGEN=true build/sbt "
              + "'catalyst/testOnly *VarkaNanosAdmissionSuite'";

          /**
           * Each asked entry's call, over a column {@code c} of the line's type and, where the entry
           * takes two timestamps, a second column {@code c2} of the same type; every other argument is a
           * literal. An entry with no SQL of its own is asked through the SQL that builds its node:
           * {@code in_set} through the {@code IN} the optimizer rewrites, {@code try_eval} through
           * {@code try_add}, {@code known_not_null}, which the optimizer puts over any expression,
           * through the bare column. {@code timestampadd} is asked with a unit both the microsecond and
           * the nanosecond types take.
           */
          static final Map<String, String> CALLS = calls();

          private static Map<String, String> calls() {
            var m = new LinkedHashMap<String, String>();
            m.put("coalesce", "coalesce(c, c2)");
            m.put("if", "if(c IS NULL, c2, c)");
            m.put("ifnull", "ifnull(c, c2)");
            m.put("nullif", "nullif(c, c2)");
            m.put("nvl", "nvl(c, c2)");
            m.put("nvl2", "nvl2(c, c2, c)");
            m.put("when", "CASE WHEN c IS NULL THEN c2 ELSE c END");
            m.put("between", "c BETWEEN c2 AND c2");
            m.put("isnull", "isnull(c)");
            m.put("isnotnull", "isnotnull(c)");
            m.put("equal_null", "equal_null(c, c2)");
            m.put("in", "c IN (c2)");
            m.put("<=>", "c <=> c2");
            m.put("=", "c = c2");
            m.put("==", "c == c2");
            m.put(">", "c > c2");
            m.put(">=", "c >= c2");
            m.put("<", "c < c2");
            m.put("<=", "c <= c2");
            m.put("greatest", "greatest(c, c2)");
            m.put("least", "least(c, c2)");
            m.put("add_months", "add_months(c, 1)");
            m.put("datediff", "datediff(c, c2)");
            m.put("date_diff", "date_diff(c, c2)");
            m.put("date_add", "date_add(c, 1)");
            m.put("dateadd", "dateadd(c, 1)");
            m.put("date_sub", "date_sub(c, 1)");
            m.put("day", "day(c)");
            m.put("dayofyear", "dayofyear(c)");
            m.put("dayofmonth", "dayofmonth(c)");
            m.put("from_utc_timestamp", "from_utc_timestamp(c, 'Europe/Paris')");
            m.put("hour", "hour(c)");
            m.put("last_day", "last_day(c)");
            m.put("minute", "minute(c)");
            m.put("month", "month(c)");
            m.put("months_between", "months_between(c, c2)");
            m.put("next_day", "next_day(c, 'MO')");
            m.put("quarter", "quarter(c)");
            m.put("second", "second(c)");
            m.put("to_utc_timestamp", "to_utc_timestamp(c, 'Europe/Paris')");
            m.put("trunc", "trunc(c, 'MM')");
            m.put("date_trunc", "date_trunc('HOUR', c)");
            m.put("dayofweek", "dayofweek(c)");
            m.put("weekday", "weekday(c)");
            m.put("weekofyear", "weekofyear(c)");
            m.put("year", "year(c)");
            m.put("extract", "extract(SECOND FROM c)");
            m.put("date_part", "date_part('SECOND', c)");
            m.put("datepart", "datepart('SECOND', c)");
            m.put("unix_seconds", "unix_seconds(c)");
            m.put("unix_millis", "unix_millis(c)");
            m.put("unix_micros", "unix_micros(c)");
            m.put("unix_nanos", "unix_nanos(c)");
            m.put("convert_timezone", "convert_timezone('UTC', 'Europe/Paris', c)");
            m.put("time_bucket", "time_bucket(INTERVAL '1' HOUR, c)");
            m.put("hash", "hash(c)");
            m.put("xxhash64", "xxhash64(c)");
            m.put("in_set", "c IN (c2)");
            m.put("ntz_minus_ntz", "c - c2");
            m.put("datetime_sub", "c - INTERVAL '1' DAY");
            m.put("ntz_plus_dt", "c + INTERVAL '1' DAY");
            m.put("ntz_plus_ym", "c + INTERVAL '1' MONTH");
            m.put("timestampadd", "timestampadd(HOUR, 1, c)");
            m.put("known_not_null", "c");
            m.put("try_eval", "try_add(c, INTERVAL '1' DAY)");
            return m;
          }

          /** The entries no microsecond timestamp is a control for: they take a nanosecond one alone. */
          private static final Set<String> NANOS_ONLY = Set.of("unix_nanos");

          /** The two microsecond timestamps: the control that tells a rejection of a type from a fault. */
          private static final List<DataType> MICROS =
              List.of(DataTypes.TimestampNTZType, DataTypes.TimestampType);

          /**
           * The conditions that are never Spark's answer about a type, by their first word: a fault of
           * Spark itself, and the preview flag off, which this suite sets on.
           */
          private static final List<String> NO_ANSWER = List.of("INTERNAL_ERROR", "FEATURE_NOT_ENABLED");

          /** The calls over two timestamps that are asked with two types (the test of mixed operands). */
          static final List<String> MIXED_CALLS = List.of("c = c2", "c IN (c2)", "coalesce(c, c2)",
              "greatest(c, c2)", "CASE WHEN c IS NULL THEN c2 ELSE c END");

          /** The registry's two nanosecond census types, each with its forms at precision 7, 8 and 9. */
          static final Map<String, List<DataType>> NANOS = new LinkedHashMap<>();

          static {
            for (VarkaTypes.Info i : VarkaTypes.all()) {
              if (i.id() == VarkaTypeId.TIMESTAMP_NTZ_NANOS || i.id() == VarkaTypeId.TIMESTAMP_LTZ_NANOS) {
                NANOS.put(i.census(), i.forms());
              }
            }
          }

          private static String home() {
            String home = System.getProperty("spark.test.home", System.getenv("SPARK_HOME"));
            assertTrue(home != null, "spark.test.home or SPARK_HOME is not set.");
            return home;
          }

          /**
           * Spark's answer to {@code call} over two columns {@code c} and {@code c2} of {@code type},
           * with the preview flag {@code nanos} and ANSI {@code ansi}: both are set here, on a copy of
           * the session's configuration, and neither is left to a default.
           */
          static VarkaChecked ask(String call, DataType type, boolean nanos, boolean ansi) {
            return ask(call, type, type, nanos, ansi);
          }

          /** {@link #ask(String, DataType, boolean, boolean)} with {@code c2} of a type of its own. */
          static VarkaChecked ask(String call, DataType type, DataType type2, boolean nanos,
              boolean ansi) {
            SQLConf conf = SQLConf.get().clone();
            conf.setConfString(SQLConf.TIMESTAMP_NANOS_TYPES_ENABLED().key(), Boolean.toString(nanos));
            conf.setConfString(SQLConf.ANSI_ENABLED().key(), Boolean.toString(ansi));
            var cols = CollectionConverters.asScala(List.<Attribute>of(
                VarkaSqlResolve.column("c", type), VarkaSqlResolve.column("c2", type2))).toSeq();
            return SQLConf$.MODULE$.withExistingConf(conf, () -> VarkaSqlResolve.check(call, cols));
          }

          /** Whether Spark takes {@code call} over a microsecond timestamp of a kind under {@code ansi}. */
          static boolean controlTakes(String call, boolean ansi) {
            for (DataType micro : MICROS) {
              if (ask(call, micro, true, ansi).condition() == null) {
                return true;
              }
            }
            return false;
          }

          /**
           * Spark's answer to {@code call} over {@code type} under one ANSI setting: null where Spark
           * takes the call, with or without a cast that the analyzer put in; else the condition it
           * raises, whatever its class, which is a rejection of the type because the same call is taken
           * over a microsecond timestamp under the same setting. A condition of {@link #NO_ANSWER}, and
           * a condition over a call that no microsecond timestamp passes under that setting, fail: the
           * first is no answer, and the second says nothing of the type.
           */
          static String rejection(String entry, String call, DataType type, boolean ansi) {
            String condition = ask(call, type, true, ansi).condition();
            if (condition == null) {
              return null;
            }
            String what = entry + ": `" + call + "` over " + type.sql() + " with ANSI "
                + (ansi ? "on" : "off") + " is rejected with " + condition;
            for (String word : NO_ANSWER) {
              if (condition.startsWith(word)) {
                fail(what + ", which is no answer of Spark's about a type");
              }
            }
            if (!controlTakes(call, ansi)) {
              fail(what + ", and Spark takes the call over neither microsecond timestamp under that "
                  + "setting, so the rejection says nothing of the type: the call in CALLS is at fault");
            }
            return condition;
          }

          /**
           * One cell, from the condition under each ANSI setting, null where Spark takes the call:
           * {@code accepted} where both take it; {@code ANSI:} or {@code LEGACY:} and the condition where
           * that setting alone rejects; the condition alone where both raise the same one; and both, each
           * after its setting, where the two differ.
           */
          static String cell(String legacy, String ansi) {
            if (legacy == null && ansi == null) {
              return ACCEPTED;
            }
            if (legacy == null) {
              return "ANSI:" + ansi;
            }
            if (ansi == null) {
              return "LEGACY:" + legacy;
            }
            return legacy.equals(ansi) ? ansi : "ANSI:" + ansi + ",LEGACY:" + legacy;
          }

          /** Whether a cell admits the type at its precision: Spark takes the call under a setting. */
          static boolean admits(String cell) {
            return cell.equals(ACCEPTED)
                || (cell.indexOf(',') < 0 && (cell.startsWith("ANSI:") || cell.startsWith("LEGACY:")));
          }

          /** The cell of {@code entry}'s {@code call} over {@code type}: both settings asked. */
          static String answer(String entry, String call, DataType type) {
            return cell(rejection(entry, call, type, false), rejection(entry, call, type, true));
          }

          /**
           * The census name of {@code call}'s result over {@code type} where it is a nanosecond type,
           * asked under ANSI, and under LEGACY where ANSI rejects the call.
           */
          private static String result(String call, DataType type) {
            DataType r = ask(call, type, true, true).dataType();
            if (r == null) {
              r = ask(call, type, true, false).dataType();
            }
            String census = r == null ? null
                : VarkaTypes.of(r).map(id -> VarkaTypes.info(id).census()).orElse(null);
            return census != null && NANOS.containsKey(census) ? census : "-";
          }

          /**
           * The file's lines with Spark's answers of today: the header, then each line of
           * {@code committed} in its order, its three answers and its result asked again. The result is
           * asked at precision 9 and only where each of the three cells admits the type.
           */
          static List<String> answered(List<String> committed) {
            var out = new ArrayList<String>();
            out.add(HEADER);
            for (String line : committed.subList(1, committed.size())) {
              String[] f = line.split("\t", -1);
              List<DataType> forms = f.length == 6 ? NANOS.get(f[1]) : null;
              assertTrue(forms != null && forms.size() == 3, "not a line of " + FILE + ": " + line);
              String call = CALLS.get(f[0]);
              assertTrue(call != null, "no call for the census entry " + f[0] + ": add it to CALLS");
              var cells = new ArrayList<String>();
              for (DataType form : forms) {
                cells.add(answer(f[0], call, form));
              }
              boolean all = cells.stream().allMatch(VarkaNanosAdmissionSuite::admits);
              out.add(String.join("\t", f[0], f[1], cells.get(0), cells.get(1), cells.get(2),
                  all ? result(call, forms.get(2)) : "-"));
            }
            return out;
          }

          @Test
          @DisplayName("nanos_admission.tsv is Spark's answer for every entry the census asks")
          void committedFile() throws IOException {
            Path file = VarkaCensusFiles.of(home(), FILE);
            List<String> committed = Files.exists(file)
                ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
            assertTrue(committed.size() > 1 && committed.get(0).equals(HEADER), FILE + " holds no "
                + "line: python3 dev/varka_census.py --asked writes its lines");
            List<String> fresh = answered(committed);
            if ("true".equals(System.getenv("VARKA_NANOS_ADMISSION_REGEN"))) {
              Files.write(file, fresh, StandardCharsets.UTF_8);
              return;
            }
            List<String> moved = VarkaCastMatrixSuite.fusionMoves(committed, fresh);
            if (!moved.isEmpty()) {
              fail("Spark's answers moved from " + FILE + ":\n  " + String.join("\n  ", moved)
                  + "\nRegenerate it, then the census, with\n  " + REGEN + "\n  dev/varka_census.py");
            }
          }

          @Test
          @DisplayName("every call is taken over a microsecond timestamp, so a rejection is the type's")
          void callsAreWellFormed() {
            var broken = new ArrayList<String>();
            for (var e : CALLS.entrySet()) {
              if (NANOS_ONLY.contains(e.getKey())) {
                continue;
              }
              boolean taken = false;
              for (DataType micro : List.of(DataTypes.TimestampNTZType, DataTypes.TimestampType)) {
                for (boolean ansi : new boolean[] {false, true}) {
                  taken |= ask(e.getValue(), micro, true, ansi).condition() == null;
                }
              }
              if (!taken) {
                broken.add(e.getKey() + ": " + e.getValue());
              }
            }
            assertTrue(broken.isEmpty(), "Spark takes these calls over neither microsecond timestamp, "
                + "under either ANSI setting, so a rejection over a nanosecond type would say nothing "
                + "of the type:\n  " + String.join("\n  ", broken));
          }

          @Test
          @DisplayName("the preview flag is the one this suite sets, and a malformed call is no answer")
          void theFlagIsSetAndABrokenCallFails() {
            DataType ntz9 = NANOS.get("ntz_ns").get(2);
            // A cast to a nanosecond type is parsed and checked under the flag: on, Spark takes it.
            String cast = "CAST(c AS TIMESTAMP_NTZ(8))";
            assertEquals(NANOS.get("ntz_ns").get(1), ask(cast, ntz9, true, true).dataType());
            // Off, the same text is refused by the flag's own condition, whatever the tests' default.
            assertEquals("FEATURE_NOT_ENABLED", ask(cast, ntz9, false, true).condition());
            // A call over a column the fixture lacks, a function Spark lacks and a text that does not
            // parse are faults of the call: no microsecond timestamp passes them either, so each fails
            // by name and is never written into the table as an answer.
            for (String broken : List.of("year(no_such_column)", "no_such_function(c)", "year(c")) {
              AssertionFailedError e = assertThrows(AssertionFailedError.class,
                  () -> answer("planted", broken, ntz9));
              assertTrue(e.getMessage().contains("says nothing of the type"), e.getMessage());
            }
          }

          @Test
          @DisplayName("a cell names the ANSI setting that rejects, and one setting that takes admits")
          void cellsAndAdmission() {
            assertEquals("accepted", cell(null, null));
            assertEquals("ANSI:X.Y", cell(null, "X.Y"));
            assertEquals("LEGACY:X.Y", cell("X.Y", null));
            assertEquals("X.Y", cell("X.Y", "X.Y"));
            assertEquals("ANSI:Z,LEGACY:X.Y", cell("X.Y", "Z"));
            // Spark takes the call under at least one setting: the type is admitted at that precision.
            assertTrue(admits("accepted") && admits("ANSI:X.Y") && admits("LEGACY:X.Y"));
            // Both settings reject, or the cell is not answered yet: it is not.
            assertTrue(!admits("X.Y") && !admits("ANSI:Z,LEGACY:X.Y") && !admits("?"));
          }

          @Test
          @DisplayName("two timestamps of two types reach an entry as one type, behind a cast")
          void mixedOperandsReachAnEntryAsOneType() {
            List<DataType> ntz = NANOS.get("ntz_ns");
            List<DataType> ltz = NANOS.get("ltz_ns");
            // Each line: the type of c, the type of c2, and the one type Spark brings both to.
            List<List<DataType>> pairs = List.of(
                List.of(ntz.get(0), ntz.get(2), ntz.get(2)),
                List.of(ltz.get(1), ltz.get(0), ltz.get(1)),
                List.of(ntz.get(2), ltz.get(2), ltz.get(2)),
                List.of(DataTypes.TimestampNTZType, ntz.get(1), ntz.get(1)),
                List.of(DataTypes.TimestampType, ntz.get(0), ltz.get(0)));
            var wrong = new ArrayList<String>();
            for (List<DataType> p : pairs) {
              for (String call : MIXED_CALLS) {
                for (boolean ansi : new boolean[] {false, true}) {
                  VarkaChecked got = ask(call, p.get(0), p.get(1), true, ansi);
                  String now = got.condition() != null ? "rejected with " + got.condition()
                      : "[" + got.operands() + "]";
                  if (!now.equals("[" + p.get(2).sql() + "]")) {
                    wrong.add("`" + call + "` over " + p.get(0).sql() + " and " + p.get(1).sql()
                        + " with ANSI " + (ansi ? "on" : "off") + ": " + now + ", expected ["
                        + p.get(2).sql() + "]");
                  }
                }
              }
            }
            assertTrue(wrong.isEmpty(), "Spark no longer brings these operands to the one type:\n  "
                + String.join("\n  ", wrong));
          }
        }

4. The script: `python3 $T/edits660.py $M9/VARKA-660.md dev/varka_census.py 17 21 24 25 26`,
   which prints `5 edits applied: 17 21 24 25 26`. Then this plan's two edits, both at the
   module's level. After the line of premise 9, two new lines at column 0:

        # From milestone 9, Spark's answers over the nanosecond timestamps too (nanos_admission()).
        FILES += ("nanos_admission.tsv",)

   And in `READER_NAMES`, the line (at column 4)

            r'plans(?:/|",\s*")m(\d+)(?:/|",\s*")(?:census|casts)|MILESTONE = "m(\d+)"'

   becomes

            r'plans(?:/|",\s*")m(\d+)(?:/|",\s*")(?:census|casts|nanos_admission)|MILESTONE = "m(\d+)"'

   so that a reader which names the table's path is held to the current milestone as the
   readers of the other files are. Then `ruff format dev/varka_census.py`, and
   `python3 dev/varka_census.py --self-test; echo $?` prints `0`.
5. The lists that a generated file and the suite that writes it must join. They were found by
   searching the tree, outside the plans, for the names of the generated files that exist
   and of their suites (`casts_fused`, `casts_suites`, `VarkaCastMatrixSuite`,
   `VARKA_CASTS_REGEN`, `varka-generated`); the table under the steps, "Every consumer",
   has all of them and why each other one needs no edit. Four files are edited.
   - `sql/varka/.gitattributes`: after the line of premise 11, the line
     `/plans/m9/nanos_admission.tsv merge=varka-generated`.
   - `dev/varka_generated_merge.py`: after the line of premise 12, the line
     `    "nanos_admission.tsv": 2,`; and after the comment line of premise 19, the comment
     line `# `nanos_admission.tsv` has one row per entry and type.`, at column 0.
   - `dev/varka_push_check.py`: in `GENERATED_SUITES`, after the line of premise 17, the line
     `    "VarkaNanosAdmissionSuite",`, so that the push check runs the suite where a branch
     touches a generated file, as it runs the other five.
   - `dev/varka_regen_generated.sh`, three edits. After the two lines of the `want` of
     `casts_fused.tsv` (premise 13 and the line under it), three lines, the first at column 0:

            want sql/varka/plans/m9/nanos_admission.tsv && {
              envs+=(VARKA_NANOS_ADMISSION_REGEN=true)
              steps+=("catalyst/testOnly *VarkaNanosAdmissionSuite"); }

     Immediately before the line of premise 14, nine lines, the first at column 0:

            # The admission table's lines are the census's and its answers the suite's: the lines first,
            # so that the suite answers every entry the census asks, and a line that waits for its answer
            # owes the suite even where the other JVM steps are skipped.
            if want sql/varka/plans/m9/nanos_admission.tsv; then
              python3 dev/varka_census.py --asked
              if [ ${#steps[@]} -eq 0 ] && grep -q -P '\t\?\t' sql/varka/plans/m9/nanos_admission.tsv; then
                steps+=("catalyst/testOnly *VarkaNanosAdmissionSuite")
              fi
            fi

     And the comment at the file's head, which lists the order: the line of premise 18,

            # casts_fused.tsv and casts_suites.tsv (whose suites read it), then coverage.json and the docs

     becomes three lines:

            # casts_fused.tsv, nanos_admission.tsv (its lines from the census's --asked, its answers from
            # its suite) and casts_suites.tsv (whose suites read casts_fused.tsv), then coverage.json and
            # the docs

6. The table's lines: `python3 dev/varka_census.py --asked` prints
   `sql/varka/plans/m9/nanos_admission.tsv: 64 entries, 128 lines, 128 to ask` on the
   prototype; at the build the three numbers are `N`, `2N` and `2N`, with `N` the day's.
7. Compile, as part A's step 4. Then the table's answers:

        VARKA_NANOS_ADMISSION_REGEN=true flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaNanosAdmissionSuite'
        grep -c -P '\t\?\t' $M9/nanos_admission.tsv                          # 0

   The run prints `Passed: Total 5, Failed 0, Errors 0, Passed 5`. Where the compile or the
   run differs, the builder finds the line of "The first run" below that names what it sees,
   and does what that line says. A difference that no line names is 5.0's stop.
8. `census`, which changes no file: the census does not read the table yet.
9. `$M9/PLAN.md`, by 5.0's rule: the clause is `part B, Spark's answers, #<number>`. This
   file's section 14: part B's paragraph, with the output of command 3 below, which scores
   predictions 5 and 11 of section 7.

**The names, checked.** Every class, method, constructor and import that the Scala and the
Java above name was opened in the tree. The line numbers are those of master `e0aee0a14b1`
and move; find a name by `grep` in the file. No Java file of the tree uses the two names
marked "no use yet".

| the name in the new text | where the tree has it | how it was checked |
| :--- | :--- | :--- |
| `CatalystSqlParser.parseExpression`, `UnresolvedAttribute(Seq(name))`, `SimpleAnalyzer.execute(Project(Seq(Alias(bound, "a")()), LocalRelation(columns)))` | `VarkaSqlResolve.scala`, `parse` (line 51) and `resolveAsWritten` (148, 156) | the same expressions, already compiled in the same file with the same imports |
| `SimpleAnalyzer.checkAnalysis(analyzed)` | `CheckAnalysis.scala` 315, `def checkAnalysis(plan: LogicalPlan): Unit`; `Analyzer.scala` 76, `object SimpleAnalyzer extends Analyzer(` | Spark's `ExpressionTypeCheckingSuite.scala` 49 calls it the same way (fact 29) |
| `SparkThrowable`, `getCondition` | `common/utils/src/main/java/org/apache/spark/SparkThrowable.java` 42; `AnalysisException.scala` 223 | read |
| `plan.flatMap`, `_.expressions`, `_.collect`, `children`, `dataType`, `sql` | `TreeNode.scala` 357 (`def flatMap[A](f: BaseType => IterableOnce[A]): Seq[A]`) and 367 (`def collect[B](pf: PartialFunction[BaseType, B]): Seq[B]`); `QueryPlan.scala` 341 (`final def expressions: Seq[Expression]`); `DataType.scala` 85 (`def sql: String`) | read |
| the pattern `_: AnyTimestampNanoType` | `sql/api/.../types/AbstractDataType.scala` 166, `private[sql] abstract class`; the resolver's package is inside `org.apache.spark.sql` | matched the same way in `ToStringBase.scala` 296 |
| the pattern `TimestampType \| TimestampNTZType` | `literals.scala` 647 | read |
| `analyzed.output.head.dataType` | `basicLogicalOperators.scala` 75, `Project.output` | read |
| `SQLConf.get().clone()`, `conf.setConfString(...)`, `SQLConf.ANSI_ENABLED().key()`, `SQLConf$.MODULE$.withExistingConf(conf, () -> ...)` | `SQLConf.scala` 257, 307, 7046, 11693, 11920 | `VarkaCastMatrixSuite.java` 229 to 231 has the same four calls |
| `SQLConf.TIMESTAMP_NANOS_TYPES_ENABLED().key()` | `SQLConf.scala` 867, `val TIMESTAMP_NANOS_TYPES_ENABLED` | read; no use yet from Java, the form is `ANSI_ENABLED()`'s |
| `CollectionConverters.asScala(List.<Attribute>of(...)).toSeq()` | `scala.jdk.javaapi` | `VarkaTimestampNtzMonthSuite.java` 159 has `CollectionConverters.asScala(List.of(ntz)).toSeq()` |
| `VarkaSqlResolve.column("c", type)` from Java | `VarkaSqlResolve.scala` 46 | `VarkaTimestampNtzMonthSuite.java` 136 |
| `VarkaSqlResolve.check(call, cols)`, `VarkaChecked`'s `dataType()`, `condition()`, `operands()` | step 2 | no use yet: a Scala object's method and a case class's fields, as `column` and `parse` are reached |
| `VarkaTypes.all()`, `VarkaTypes.of(DataType)`, `VarkaTypes.info(id)`, `Info.id()`, `Info.census()`, `Info.forms()` | `VarkaTypes.java` 461, 374, 456, and the record at 256 | `VarkaCastMatrixSuite.java`'s static block reads `all()`, `census()` and `forms()` |
| `VarkaTypeId.TIMESTAMP_NTZ_NANOS`, `TIMESTAMP_LTZ_NANOS` | part A | premise 1 |
| `VarkaCastMatrixSuite.fusionMoves(List<String>, List<String>)`, static and package-private | `VarkaCastMatrixSuite.java` 289 | premise 8 |
| `VarkaCensusFiles.of(home, name)` | row 654's part a (`VARKA-654.md` 4.3 has its text); not on `e0aee0a14b1` | premise 7 |
| `home()` | copied from `VarkaCastMatrixSuite.java` 95 to 99 | read |
| `@VarkaSuite`, `@Test`, `@DisplayName` | `VarkaSuite.java` 57 | `VarkaCastMatrixSuite.java` 63 |
| `assertThrows(AssertionFailedError.class, ...)`, `org.opentest4j.AssertionFailedError` | JUnit 5 | `VarkaSuiteHarnessSuite.java` 130 |
| `DataTypes.TimestampNTZType`, `DataTypes.TimestampType`, `DataType.sql()` | `sql/api/src/main/java/org/apache/spark/sql/types/DataTypes.java` 60 and 55 | read |
| the 65 functions and operators of `CALLS` | `FunctionRegistry.scala` | each name is registered; the first review recounted them, and the lead's probe ran each call (section 3) |

**The first run: where it may differ, and what the builder does.** Each line is the whole
rule; none asks for a judgement.

| # | what the builder sees | why it can happen | what the builder does |
| ---: | :--- | :--- | :--- |
| 1 | javac: `cannot find symbol` at `TIMESTAMP_NANOS_TYPES_ENABLED()`, `check(`, `operands()`, `dataType()` or `condition()` | no Java file reached these before | `grep -n` the name in the file the table above gives. Where the tree spells the member otherwise, spell it so (list 1). Where it is spelled the same, the part ends: report javac's line |
| 2 | javac: `incompatible types` on the line of `withExistingConf` or of `.toSeq()` | a Java lambda to a Scala by-name parameter, a Java list to a Scala `Seq` | copy the form of `VarkaCastMatrixSuite.java`'s `castExpression` (a `var cols`, then the lambda) and of `VarkaTimestampNtzMonthSuite.java`'s `asScala(...).toSeq()`; add the type witness javac names (list 1). After that, the stop |
| 3 | scalac: a type error inside `operandTypes` | the inferred type of the nested `flatMap` and `collect` | give the two steps their types: `val each: Seq[Seq[String]] = plan.flatMap(_.expressions).flatMap(_.collect { ... })`, then `each.flatten.distinct.mkString(",")` (a type witness, list 1). After that, the stop |
| 4 | scalastyle or checkstyle: import order or a line over 100 columns | the order of `scala.util.control.NonFatal`, `org.apache.spark.SparkThrowable`, `org.opentest4j` | move the import where the linter says; re-wrap the line (list 1) |
| 5 | Error Prone names a check on a line of the suite | a house check this text was not run under | do what the check's message asks if it is on list 1; else the part ends: report the message |
| 6 | the third test: `expected: <FEATURE_NOT_ENABLED> but was: <null>` | with the flag set off the parser still took `TIMESTAMP_NTZ(8)`: the configuration `ask` installs is not the one the parser reads | the part ends: report the line. No table is written from a flag that is not read |
| 7 | the third test: `expected: <timestamp_ntz(8)>` or `<TimestampNTZNanosType(8)>` `but was: <null>` | with the flag on Spark rejects a cast between two precisions | the part ends: report the line and `ask(cast, ntz9, true, true).condition()` |
| 8 | the third test: `Expected org.opentest4j.AssertionFailedError to be thrown`, or a message that holds `which is no answer` | a broken call met another outcome than line 5 of 4.3's rule | the part ends: report the call and the message |
| 9 | the first or the second test: `... so the rejection says nothing of the type: the call in CALLS is at fault`, or `Spark takes these calls over neither microsecond timestamp` | a call of `CALLS` is not well formed for this Spark, under one setting or both | the part ends: report the line. The builder does not reword a call (list 2) |
| 10 | the first test: `... which is no answer of Spark's about a type` | Spark raised `INTERNAL_ERROR`, or `FEATURE_NOT_ENABLED`, over a nanosecond column | the part ends: report the line |
| 11 | a test ends in an exception that is not an assertion's | line 2 of 4.3's rule: an error with no condition, raised by an ask over a nanosecond column or by an ask of a control | the part ends: report the entry, the call, the type, the setting and the exception's first line |
| 12 | the first test: `no call for the census entry <entry>: add it to CALLS` | the day's census asks an entry this plan does not know | one attempt: add `m.put("<entry>", "<call>");` to `calls()` in the place the entry has in the table, the call written by 4.3's rule ("The calls"), with each literal taken from the first example of the function's `@ExpressionDescription`. Rerun. If lines 9 to 11 then name the new call, the part ends: report the entry and the call tried. The description names the call |
| 13 | the fifth test: `Spark no longer brings these operands to the one type:` and its lines | prediction 11 is wrong for a pair | where all ten lines of one pair of types print the same `[<one type>]`, replace the third element of that pair's line in the test by that type, written as the test writes types (`ntz.get(p - 7)` or `ltz.get(p - 7)` for precision `p`, `DataTypes.TimestampNTZType`, `DataTypes.TimestampType`), rerun, and list the pair in the description: prediction 11 is scored wrong. This is the one expected value the builder may change. Where a line prints `rejected with`, `[]`, a comma inside the brackets, or the lines of a pair differ, the part ends: report the lines |
| 14 | the first test, without the regeneration switch: `Spark's answers moved from nanos_admission.tsv:` | the file was not written, or was written before a call was added | run step 7's first command again |

**Every consumer of the lists.** Found by `git grep` for the five names of step 5 outside
`sql/varka/plans`, across the Python tools, the shell drivers, the workflows and the Scala and
Java suites.

| consumer | what it lists or reads | this row |
| :--- | :--- | :--- |
| `sql/varka/.gitattributes` | the generated files that the merge driver merges; through it, `dev/varka_generated_merge.py --list` | edited, step 5 |
| `dev/varka_generated_merge.py`, `TSV_KEYS` and its comment | the key columns of each generated TSV; a listed file with no key fails its `--check` | edited, step 5 |
| `dev/varka_regen_generated.sh` | a `want` step, its switch and its place in the order, for each generated file; the order in the head comment | edited, step 5 |
| `dev/varka_push_check.py`, `GENERATED_SUITES` | the suites it runs where a branch touches a generated file | edited, step 5 |
| `dev/varka_census.py`, `FILES` and `READER_NAMES` | the census's files of a milestone; the pattern that holds a reader to the current one | edited, step 4 |
| `dev/varka_msg_check.py size`; `dev/varka_push_check.py`'s rule R3 | the generated files, read from the driver's `--list` | no edit: they follow `.gitattributes` |
| `dev/varka_precommit.sh`, `census_inputs` | the paths whose change runs `dev/varka_census.py --check` | no edit: its pattern `sql/varka/plans/.*` holds the table |
| `dev/varka_regen_inputs.py`, `SAFE` | the paths that leave the regeneration alone | no edit: a generated file is caught before the list, and the suite is under `sql/catalyst/src/test`, which is not on it |
| `.github/workflows/varka-fuzz.yml`, `dev/varka_nightly.sh` | `git diff --exit-code` of `casts_suites.tsv` after Spark's cast suites | no edit: they hold the record that sql/core writes. The admission table is held by its own suite in every Build's catalyst job |
| `sql/varka/ci/suite-times.tsv`, `sql/varka/matrix/suite-seconds.tsv`, `sql/varka/matrix/config-suite-seconds.tsv` | the seconds of each suite, written by tools from runs | no edit: "a suite missing here takes the median" (`sql/varka/ci/suite-times.tsv`'s header) |
| `dev/varka_matrix.sh`, `dev/varka_scoped_suites.sh` | the Varka suites, found by the class name `Varka...Suite` under `@VarkaSuite` | no edit: the new class is found |
| `VarkaTypeConformance`, `VarkaSuiteRecord`, `VarkaCoverageSuite`, `VarkaDivisorProofSuite` (Java and Scala tests) | `casts.tsv`, `casts_fused.tsv`, `VarkaCastMatrixSuite`'s helpers | no edit: none reads the admission table |
| `.github/workflows/build_and_test.yml` | runs `./dev/varka_census.py --check` | no edit |

**The size.** Part B has no cut line: it is one pull request with the suite's five tests,
and part C adds no test to the suite. Its estimate is 701 counted lines, file by file:

| file | counted lines | from |
| :--- | ---: | :--- |
| `$TJ/VarkaNanosAdmissionSuite.java` | 421 | the 404 lines of step 3's text, the 16 lines of the license header and the empty line under it |
| `dev/varka_census.py` | 177 | 176 added and 1 removed on the prototype: the five edits of the tool and step 4's two |
| `$TS/VarkaSqlResolve.scala` | 57 | step 2: 44 lines inside the object and 7 at the file's end, each count with the empty line above the block; 3 lines of imports; and the import line that becomes two, 2 added and 1 removed |
| `dev/varka_regen_generated.sh` | 16 | step 5: 3 lines, 9 lines, and one comment line that becomes three |
| `dev/varka_generated_merge.py` | 2 | step 5 |
| `dev/varka_push_check.py` | 1 | step 5 |
| `$M9/VARKA-661.md` and `$M9/PLAN.md` | 27 | step 9: the outcome's paragraph and the row's cell |
| `$M9/nanos_admission.tsv`, `sql/varka/.gitattributes` | 0 | a generated file, and a kind of file the size check does not count |

That is 299 under the cap of 1000 and 49 under the 750 at which a sheet owes a cut line. If
`python3 dev/varka_msg_check.py size --base origin/master` prints 900 or more, the part
ends: report the count and the three largest files. The builder moves no test and no list
out of the part.

**The tests.**

| suite (language, module) | cases | inputs | expected, and its oracle | the failure only it catches |
| :--- | :--- | :--- | :--- | :--- |
| `VarkaNanosAdmissionSuite` (Java, catalyst), new: "nanos_admission.tsv is Spark's answer for every entry the census asks" | every line of the table: the entry's call over two columns of the line's type at precision 7, 8 and 9, under ANSI off and on, the flag set on; for a rejection, the same call over `TIMESTAMP_NTZ` and `TIMESTAMP` under the same setting | `$M9/nanos_admission.tsv`; `CALLS` | the file's lines are the fresh answers; the oracle is Spark's analyzer and `checkAnalysis` | a cell that Spark moved in a sync; a cell typed by hand; an entry with no call; a rejection that the control does not bear out |
| the same suite: "every call is taken over a microsecond timestamp, so a rejection is the type's" | each of the 65 calls but `unix_nanos`'s, over `TIMESTAMP_NTZ` and over `TIMESTAMP`, under ANSI off and on | `CALLS` | each is taken at least once; the oracle is the analyzer | a call with a wrong literal or a wrong argument order that Spark happens to take over a nanosecond column |
| the same suite: "the preview flag is the one this suite sets, and a malformed call is no answer" | `CAST(c AS TIMESTAMP_NTZ(8))` over a column of `TIMESTAMP_NTZ(9)` with the flag on and off; `year(no_such_column)`, `no_such_function(c)` and `year(c` over `TIMESTAMP_NTZ(9)` | the texts | the type `TIMESTAMP_NTZ(8)`; the condition `FEATURE_NOT_ENABLED`; each of the three broken calls fails with "says nothing of the type" | a setting the analyzer does not read, so that the suite would run on the tests' default; an unknown name or a parse error written into the table as an answer |
| the same suite: "a cell names the ANSI setting that rejects, and one setting that takes admits" | `cell` over the five pairs of (LEGACY, ANSI) answers: (taken, taken), (taken, `X.Y`), (`X.Y`, taken), (`X.Y`, `X.Y`), (`X.Y`, `Z`); `admits` over the five cells and `?` | the strings: a difference between the settings planted as a test double, since no real call is known to differ | `accepted`, `ANSI:X.Y`, `LEGACY:X.Y`, `X.Y`, `ANSI:Z,LEGACY:X.Y`; `admits` true for the first three and false for `X.Y`, `ANSI:Z,LEGACY:X.Y` and `?` | the rule of the plan's first version, both settings must accept, coming back; a cell that names the wrong setting |
| the same suite: "two timestamps of two types reach an entry as one type, behind a cast" | the five calls of `MIXED_CALLS` over the five pairs of 4.3's table, under ANSI off and on: 50 asks | the texts and the types | every ask is taken, and `operands` is the pair's one type; the oracle is the analyzer, the expected type a prediction (prediction 11) that the first run settles: the lead's probe measured the widened result type for three of the five pairs and the operand types for none (4.3's table) | Spark leaving a node with timestamp operands of two types, for which the census has no cell; a widening that changed in a sync |
| the doctests of `nanos_admission`, `admission_skeleton`, `admission_errors` and `admit_nanos` (Python; `VARKA-660.md` 6.1, edit 17, and its 6.6 for the cases) | as `VARKA-660.md` 6.6 lists them | temporary tables | as listed there | a column read in the wrong order; `--asked` losing an answer; a stale or shuffled table passing the check; a type admitted from one accepted precision |
| `python3 dev/varka_census.py --check` | the committed table against the asked entries | the table | no error | a line no entry asks; lines out of order; a reader of the table on another milestone |

What part B does not hold yet: a line that is missing from the table, and the census's side
of the admission under either setting. `admit_nanos` reports the first and part C's edits
bring the second. Until then command 2 below is the check.

**The planted faults**, each run by hand and reverted.

| plant | the check that fails | with |
| :--- | :--- | :--- |
| in the committed table, change one `accepted` of the line of `year` and `ntz_ns` to `x` | `build/sbt 'catalyst/testOnly *VarkaNanosAdmissionSuite'` fails its first test | `Spark's answers moved from nanos_admission.tsv:`, then a line that begins `now:  year` and holds three times `accepted`, and a line that begins `file: year` and holds `x` and twice `accepted` (the fields are tab-separated) |
| in `ask`, delete the line that sets `SQLConf.TIMESTAMP_NANOS_TYPES_ENABLED()` | the suite's third test fails | `expected: <FEATURE_NOT_ENABLED> but was: <null>` |
| in `calls()`, `year(c)` becomes `year(c, c)` | the suite's first and second tests fail | the first: `year: ... so the rejection says nothing of the type: the call in CALLS is at fault`, with the condition Spark raises for a wrong number of arguments; the second names `year: year(c, c)` |
| in `rejection`, delete the four lines of the block `if (!controlTakes(call, ansi)) {` | the suite's third test fails | `Expected org.opentest4j.AssertionFailedError to be thrown, but nothing was thrown` |
| in `admits`, the body becomes `return cell.equals(ACCEPTED);`, the rule of the plan's first version | the suite's fourth test fails | `expected: <true> but was: <false>` |
| in `cell`, `return "ANSI:" + ansi;` becomes `return ansi;` | the suite's fourth test fails | `expected: <ANSI:X.Y> but was: <X.Y>` |
| in the fifth test, the first pair's third element `ntz.get(2)` becomes `ntz.get(0)` | the suite's fifth test fails | `Spark no longer brings these operands to the one type:`, then ten lines; on the prediction each ends `[TIMESTAMP_NTZ(9)], expected [TIMESTAMP_NTZ(7)]` |
| add a line `abs`, `ntz_ns`, three times `accepted`, `-` at the table's end | `python3 dev/varka_census.py --check` exits 1 | `census: sql/varka/plans/m9/nanos_admission.tsv: a line for abs over ntz_ns, which no entry asks`; `--asked` then removes it |
| reverse the table's lines under its header | the same, exits 1 | `census: sql/varka/plans/m9/nanos_admission.tsv: its lines are not in the row order of census.tsv; run python3 dev/varka_census.py --asked, then VARKA_NANOS_ADMISSION_REGEN=true build/sbt 'catalyst/testOnly *VarkaNanosAdmissionSuite'` |
| in `sql/varka/.gitattributes`, the new line's `m9` becomes `m8` | the same, exits 1 | `census: sql/varka/.gitattributes names milestone 8's census files, and the census is milestone 9's`. Without step 4's edit of `READER_NAMES` the same plant passes: that edit is what this plant holds |

The last three plants were run on the prototype with this part's edits and printed these
lines. The first seven need the JVM.

**The commands, in order, each with what it must print.**

    # 1. the doctests and the check
    python3 dev/varka_census.py --self-test; echo $?                    # 0
    python3 dev/varka_census.py --check; echo $?                        # 0
    # 2. the table is complete and in order: --asked changes no line of it
    python3 dev/varka_census.py --asked                                 # ...: N entries, 2N lines, 0 to ask
    git status --short $M9/nanos_admission.tsv                          # "?? sql/varka/plans/m9/nanos_admission.tsv" before the first commit, nothing after it
    head -1 $M9/nanos_admission.tsv | tr '\t' ' '                       # entry type p7 p8 p9 result
    # 3. Spark's answers (this plan's prediction beside them)
    awk -F'\t' 'NR>1 && !($3=="accepted" && $4=="accepted" && $5=="accepted") {print $1, $2, $3, $4, $5}' $M9/nanos_admission.tsv
                                    # months_between ntz_ns, months_between ltz_ns and convert_timezone ltz_ns, each three times DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE;
                                    # ntz_minus_ntz ntz_ns and ntz_minus_ntz ltz_ns, each three times UNSUPPORTED_FEATURE.TIMESTAMP_NANOS_SUBTRACTION
                                    # (the lead's probe measured these five lines, and no other, at precisions 7 and 9)
    awk -F'\t' 'NR>1 && $6!="-" {print $1}' $M9/nanos_admission.tsv | LC_ALL=C sort -u | tr '\n' ' '
                                    # as registered (prediction 5), nine names: convert_timezone date_trunc datetime_sub from_utc_timestamp ntz_plus_dt ntz_plus_ym time_bucket timestampadd to_utc_timestamp
                                    # as the lead's probe measured, 20 names, which is what the run is expected to print:
                                    # coalesce convert_timezone date_trunc datetime_sub from_utc_timestamp greatest if ifnull known_not_null least ntz_plus_dt ntz_plus_ym nullif nvl nvl2 time_bucket timestampadd to_utc_timestamp try_eval when
    grep -c -E '\t(ANSI|LEGACY):' $M9/nanos_admission.tsv                # 0: no cell differs between the settings
    awk -F'\t' 'NR>1 {for (i=3;i<=5;i++) {n=split($i,c,","); for (j=1;j<=n;j++) {x=c[j]; sub(/^(ANSI|LEGACY):/,"",x); if (x!="accepted" && x!~/^(DATATYPE_MISMATCH\.|UNSUPPORTED_FEATURE\.TIMESTAMP_NANOS)/) print $1, $2, "p" i+4, $i}}}' $M9/nanos_admission.tsv
                                    # nothing: no condition outside the two that Spark has today
    # 4. the census did not move
    python3 dev/varka_census.py --summary | diff $T/before.txt -        # nothing
    git diff --stat $B -- $M9/census.tsv $M9/casts.tsv docs/sql-varka.md   # nothing
    # 5. the generated files' lists
    python3 dev/varka_generated_merge.py --list | grep -c plans/m9      # 5
    python3 dev/varka_generated_merge.py --self-test; echo $?           # 0
    grep -c '^    "VarkaNanosAdmissionSuite",$' dev/varka_push_check.py  # 1
    dev/varka_regen_generated.sh --all --full --dry-run | grep -c 'VarkaCastMatrixSuite.*VarkaNanosAdmissionSuite.*VarkaTryCastSuite'   # 1
    bash -n dev/varka_regen_generated.sh; echo $?                       # 0
    # 6. every regeneration writes what the files hold
    git add -N $M9/nanos_admission.tsv; git status --short > $T/status-before.txt
    dev/varka_regen_generated.sh --all; echo $?                         # 0
    git status --short | diff $T/status-before.txt -                    # nothing
    # 7. the planted faults of the table above, one at a time, each reverted
    # 8. the suite, as a Build runs it
    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaNanosAdmissionSuite'
                                    # Passed: Total 5, Failed 0, Errors 0, Passed 5

**Command 3 is Spark's answer, not a premise.** Where it prints other lines than the
prediction, the builder does not stop: the table is right and the prediction is scored wrong.
The description lists each cell that differs, and part C's counts follow the table by 4.5's
rule. Its last two lines print the cells the description must also name one by one: a cell
that one setting alone rejects (the type is still admitted, and part C writes its clause),
and a cell with a condition outside the two of today, each with the entry, the type, the
precision and the cell as the last command prints them. The suite has already held each to
its control (4.3, line 4), so the builder judges none. The second command's eleven names
beyond the nine registered are entries whose curated result is `same`: `row` takes such an
entry's result from its types and does not read the table's last column for it, so they
move no cell of the census. The description scores that clause of prediction 5 wrong.

Command 6's working tree, each ` M` but the two new files (`A ` or `??`):
`$TS/VarkaSqlResolve.scala`, `$TJ/VarkaNanosAdmissionSuite.java` (new), `dev/varka_census.py`,
`sql/varka/.gitattributes`, `dev/varka_generated_merge.py`, `dev/varka_push_check.py`,
`dev/varka_regen_generated.sh`, `$M9/nanos_admission.tsv` (new), `$M9/PLAN.md` and
`$M9/VARKA-661.md`.

**Expectations.**

- [ ] `VarkaSqlResolve.check(String, Seq[Attribute]): VarkaChecked` exists, public, and no
  other method of the object changed.
- [ ] `VarkaNanosAdmissionSuite` has five tests; `CALLS`, `MIXED_CALLS`, `NANOS`, `ask`,
  `controlTakes`, `rejection`, `cell`, `admits`, `answer` and `answered` are package-private
  and static.
- [ ] `nanos_admission.tsv` has a header and two lines for each asked entry, no `?`, and is
  generated by the attributes, so the size check does not count it.
- [ ] `--asked` exists; run twice, the second run changes nothing.
- [ ] The suite's name is in `GENERATED_SUITES`, and the driver's dry run names it between
  the cast matrix's suite and Spark's cast suites.
- [ ] What does not change: `census.tsv`, `casts.tsv`, the curated file, the docs page, the
  summary.
- [ ] The description gives the suite's time from sbt's output, each line of "The first run"
  that was met and what was done, each call added, each cell that differs from the
  prediction, and each pair of the fifth test that was changed.

**Done when** every command prints as above and the expectations are ticked.

**Not here.** The census does not read the table: no edit of `row`'s calls, no `Spark
rejects` reason, no `one_setting` and no `alone`, no change of the interim rule, no entry in
the curated file. No band.

### 5.3 Part C: the census admits the types from the table

**B0, the premises.**

    test -f $M9/nanos_admission.tsv && echo there                        # 1: there
    grep -c 'def admit_nanos(' dev/varka_census.py                       # 2: 1
    grep -c 'if name not in CAST_ENTRY_MODES:' dev/varka_census.py       # 3: 1
    grep -c '^NANOS = ' dev/varka_census.py; grep -c '": NANOS,$' dev/varka_census.py   # 4: 1 and 2
    grep -c '^# (dec is DECIMAL up to precision 38' $M9/census_curated.txt   # 5: 1
    grep -c '^unix_nanos|' $M9/census_curated.txt                        # 6: 0
    python3 dev/varka_census.py --asked                                  # 7: ...: N entries, 2N lines, 0 to ask
    python3 dev/varka_census.py --check; echo $?                         # 8: 0
    git branch --list 'varka-661c*'                                      # 9: nothing
    grep -c '^        no = \[str(7 + i) for i, a in enumerate(answers) if a != ACCEPTED\]$' dev/varka_census.py   # 10: 1
    grep -c '^            why = next(a for a in answers if a != ACCEPTED)$' dev/varka_census.py   # 11: 1
    grep -c '^    if need not in NEEDS:$' dev/varka_census.py            # 12: 1
    grep -c 'def one_setting(' dev/varka_census.py                       # 13: 0

| premise | it prints | what the builder does |
| :--- | :--- | :--- |
| 1 or 2 | anything else | the part ends: report "part B is not merged" |
| 3 | `0` | part C is merged: go to 5.4 |
| 4, 5 or 6, 10 to 13 | anything else | the part ends: report the line |
| 7 | a number other than `0` to ask | no stop: a row since part B added an asked entry. Steps 4 and 5 answer it with `unix_nanos` |
| 8 | not `0` | the part ends: report its output |
| 9 | a branch | the part ends: report its name |

**The steps.**

1. `python3 dev/varka_census.py --summary > $T/before.txt; cp $M9/census.tsv
   $T/census-before.tsv`.
2. The script: `python3 $T/edits660.py $M9/VARKA-660.md dev/varka_census.py 4 5 6 18 20 22
   23`, which prints `7 edits applied: 4 5 6 18 20 22 23`. Then this plan's eight edits, in
   the file's order from its top; each old text is in the file once (5.0). They bring the
   owner's answer into the census: a type is admitted where Spark takes it under at least one
   ANSI setting, and a claim over a cell of one setting owes the decline under the other.
   1. Immediately above the line `def admit_nanos(name, t, admission, errors):`, three new
      functions at column 0, each followed by two empty lines:

            def one_setting(answer):
                """(the ANSI setting that rejects, its error class) for an answer that Spark gives under one
                setting alone, the call being taken under the other; None for every other answer. The suite
                writes such an answer as "ANSI:<class>" or "LEGACY:<class>", and an answer that both settings
                reject as the one class or as "ANSI:<class>,LEGACY:<class>".

                >>> one_setting("ANSI:X.Y"), one_setting("LEGACY:X.Y")
                (('ANSI', 'X.Y'), ('LEGACY', 'X.Y'))
                >>> [one_setting(a) for a in (ACCEPTED, "X.Y", "ANSI:X.Y,LEGACY:Z", UNASKED)]
                [None, None, None, None]
                """
                m = re.fullmatch(r"(ANSI|LEGACY):([^,:]+)", answer)
                return m.groups() if m else None


            def alone(name, admission):
                """For the entry `name`, each nanosecond type that is admitted and that Spark takes under one
                ANSI setting alone at some precision: {type: [(the setting that rejects, the clause of the
                census's reason column)]}. A row that fuses such a cell owes a kernel that declines under
                the rejecting setting and a test of that decline (the owner's answer of 27 November 2026,
                VARKA-661.md 13); row() holds a fused claim to it.

                >>> adm = {("year", NS[0]): (("ANSI:X.Y",) * 3, "-"),
                ...        ("year", NS[-1]): ((ACCEPTED, "LEGACY:Z", "ANSI:X.Y"), "-")}
                >>> for x, clauses in alone("year", adm).items():
                ...     for clause in clauses:
                ...         print(x, clause)
                ntz_ns ('ANSI', 'Spark takes ntz_ns under LEGACY alone: ANSI raises X.Y')
                ltz_ns ('ANSI', 'Spark takes ltz_ns under LEGACY alone at precision 9: ANSI raises X.Y')
                ltz_ns ('LEGACY', 'Spark takes ltz_ns under ANSI alone at precision 8: LEGACY raises Z')
                >>> alone("year", {("year", NS[0]): (("X.Y", "ANSI:X.Y", ACCEPTED), "-")}), alone("abs", adm)
                ({}, {})
                """
                out = {}
                for x in NS:
                    answers = admission.get((name, x), ((UNASKED,) * 3, "-"))[0]
                    split = [one_setting(a) for a in answers]
                    if any(a != ACCEPTED and s is None for a, s in zip(answers, split)):
                        continue  # not admitted: no line, no answer yet, or rejected under both settings
                    clauses = []
                    for setting, other in (("ANSI", "LEGACY"), ("LEGACY", "ANSI")):
                        at = [str(7 + i) for i, s in enumerate(split) if s and s[0] == setting]
                        if at:
                            where = "" if len(at) == 3 else f" at precision {', '.join(at)}"
                            why = next(s[1] for s in split if s and s[0] == setting)
                            clauses.append(
                                (setting, f"Spark takes {x} under {other} alone{where}: {setting} raises {why}")
                            )
                    if clauses:
                        out[x] = clauses
                return out


            def names_decline(note, setting):
                """Whether an entry's note names the test of the kernel's decline under the ANSI `setting`, in
                the form row() asks for: "declines under <setting>: <suite>, <test>", the suite a class name
                that ends in Suite and the test its name or its sentence, up to the note's next ";". The
                words alone name no test.

                >>> names_decline('a relabel; declines under ANSI: VarkaYearSuite, "year declines"', "ANSI")
                True
                >>> notes = ("declines under ANSI: ", "declines under ANSI: VarkaYearSuite",
                ...          "declines under ANSI: VarkaYearSuite, ; x", "declines under ANSI: , a test",
                ...          "declines under ANSI: Varka, a test", "declines under LEGACY: VarkaYearSuite, t")
                >>> [names_decline(n, "ANSI") for n in notes]
                [False, False, False, False, False, False]
                """
                return re.search(rf"declines under {setting}: \w+Suite, [^\s;][^;]*", note) is not None

   2. In `admit_nanos`'s first lines. Old, three lines at column 4:

                results, rejected). A nanosecond type is admitted when Spark accepts the entry over it at
                every precision; otherwise it leaves the types, and `rejected` says at which precisions
                Spark rejects it and with which error class. Only an entry that takes a timestamp is asked.

      New, five lines:

                results, rejected). A nanosecond type is admitted when, at every precision, Spark accepts
                the entry over it under at least one ANSI setting; otherwise it leaves the types, and
                `rejected` says at which precisions Spark rejects it under both settings and with which
                error class. alone() says where one setting alone takes an admitted type. Only an entry that
                takes a timestamp is asked.

   3. In `admit_nanos`'s examples. Old, three lines at column 4:

                >>> admit_nanos("abs", ["int"], adm, [])
                (['int'], [], [])
                """

      New, nine lines:

                >>> admit_nanos("abs", ["int"], adm, [])
                (['int'], [], [])
                >>> adm[("year", NS[-1])] = ((ACCEPTED, "ANSI:X.Y", "LEGACY:X.Y"), "-")
                >>> admit_nanos("year", ["date", "ntz"], adm, [])
                (['date', 'ntz', 'ntz_ns', 'ltz_ns'], ['int'], [])
                >>> adm[("year", NS[-1])] = ((ACCEPTED, "ANSI:X.Y,LEGACY:Z", "ANSI:X.Y"), "-")
                >>> admit_nanos("year", ["date", "ntz"], adm, [])[2]
                ['ltz_ns at precision 8: ANSI:X.Y,LEGACY:Z']
                """

   4. In `admit_nanos`. Old, one line at column 8:

                    no = [str(7 + i) for i, a in enumerate(answers) if a != ACCEPTED]

      New, two lines:

                    both = [a for a in answers if a != ACCEPTED and one_setting(a) is None]
                    no = [str(7 + i) for i, a in enumerate(answers) if a in both]

   5. In `admit_nanos`. Old, one line at column 12:

                        why = next(a for a in answers if a != ACCEPTED)

      New:

                        why = both[0]

   6. In `row`: edit 19 of `VARKA-660.md` 6.1, on the text part A left, with the owner's
      answer in it. Old, the seven lines that part A's step 6 gives as new. New, eight lines,
      the first at column 4:

                t = expand(",".join(p for p in types.split(",") if p and not p.endswith("!")))
                # A nanosecond type is admitted by Spark's own answer (nanos_admission), not by this file;
                # a cast entry's types are the cast table's, which VarkaCastMatrixSuite holds to Cast.
                more, rejected, one = [], [], {}
                if admission is not None and name not in CAST_ENTRY_MODES:
                    t, more, rejected = admit_nanos(name, t, admission, errors)
                    one = alone(name, admission)
                res = t if result == "same" else expand(",".join([result] + more))

   7. In `row`. Old, two lines at column 4:

                if need not in NEEDS:
                    errors.append(f"{name}: unknown need {need!r}")

      New, thirteen lines:

                # A type that Spark takes under one ANSI setting alone is admitted, and a claim over it owes
                # the other setting: a kernel that declines there by name, and the test of that decline,
                # which the note names by its suite and its name (names_decline).
                for x in [*f, *partial]:
                    for setting, clause in one.get(x, []):
                        if not names_decline(note, setting):
                            errors.append(
                                f"{name}: {clause}, so a fused claim over {x} needs a kernel that declines"
                                f" under {setting} by name and a test of that decline: name the test in the"
                                f' note as "declines under {setting}: <suite>, <test>"'
                            )
                if need not in NEEDS:
                    errors.append(f"{name}: unknown need {need!r}")

   8. In `row`'s result, which edit 20 of the tool wrote. Old, one line at column 12:

                        + ["Spark rejects " + r for r in rejected]

      New, two lines:

                        + ["Spark rejects " + r for r in rejected]
                        + [clause for x in NS for _, clause in one.get(x, [])]

   Then `ruff format dev/varka_census.py`, and `python3 dev/varka_census.py --self-test; echo
   $?` prints `0`.
3. `python3 $T/ns_curated.py new $M9/census_curated.txt` prints `2 entries added, 2 header
   lines added, 0 date functions now ask the two types`. A number other than 0 in the third
   place means row 654 was built without the date functions: report it in the description and
   go on. The date functions are six here: the tool's eighth replacement took `unix_date` out
   of the script's list, so this step never writes a timestamp into its types (4.3).
4. `python3 dev/varka_census.py --asked` prints `...: N+1 entries, 2N+2 lines, 2 to ask`
   (`65 entries, 130 lines` on the prototype): `unix_nanos` is asked.
5. The answers of the new lines: part B's step 7, both commands. The suite prints `Passed:
   Total 5, Failed 0, Errors 0, Passed 5`.
6. `python3 $T/ns_curated.py rows $M9/census_curated.txt $M9/nanos_admission.tsv | tee
   $T/rows.txt` prints `62 entries edited, 0 of them no longer done; 0 by default, 0 absent`
   on the prototype. At the build: the first number is the entries for which at least one
   of the two types is admitted (4.3), less `unix_nanos`; the second is the
   day's, since an entry that row 656 or a later row made `done` loses it here; each
   `default:` line is an accepted entry that `VARKA-660.md` 4.5 does not name, with the row of
   its kind, and goes into the description; a line `absent:` ends the part: report it.
7. `census`; then `dev/varka_regen_generated.sh --all`, which exits 0 (5.0 says what it runs
   and when the part ends); then `python3 dev/varka_census.py --summary > $T/after.txt`.
8. `$M9/PLAN.md`, by 5.0's rule: the clause is `part C, the census's cells, #<number>`. This
   file's section 14: part C's paragraph, with `a`, `b` and the totals of 4.5 as the day
   printed them, and each clause that command 5 prints for a cell of one setting.

**The tests.**

| suite (language, module) | cases | inputs | expected, and its oracle | the failure only it catches |
| :--- | :--- | :--- | :--- | :--- |
| the doctest of `admit_nanos`, now reached from `row` (Python) | `year` over `date,ntz` with `ntz_ns` accepted three times and `ltz_ns` accepted at 7 only; `hour` with an empty table; `abs` over `int` | the table as a dict | `(['date', 'ntz', 'ntz_ns'], ['int'], ['ltz_ns at precision 8, 9: X.Y'])`; the types unchanged and one error that begins `hour: no line in`; `(['int'], [], [])` | a type admitted from one accepted precision; a missing line read as a rejection; an entry with no timestamp asked |
| the two new examples of `admit_nanos` (Python; step 2, edit 3): a difference between the settings, planted in the table's dict | `ltz_ns` with the cells `accepted`, `ANSI:X.Y`, `LEGACY:X.Y`; then with `accepted`, `ANSI:X.Y,LEGACY:Z`, `ANSI:X.Y` | the table as a dict | `(['date', 'ntz', 'ntz_ns', 'ltz_ns'], ['int'], [])`: admitted; then `['ltz_ns at precision 8: ANSI:X.Y,LEGACY:Z']`: rejected for the one precision that both settings reject | the rule of the plan's first version, both settings must accept: with it the first example prints `(['date', 'ntz', 'ntz_ns'], ['int'], ['ltz_ns at precision 8, 9: ANSI:X.Y'])` |
| the doctests of `one_setting` and `alone` (Python; step 2, edit 1) | the cells `ANSI:X.Y`, `LEGACY:X.Y`, `accepted`, `X.Y`, `ANSI:X.Y,LEGACY:Z` and `?`; `year` with `ntz_ns` three times `ANSI:X.Y` and `ltz_ns` with `accepted`, `LEGACY:Z`, `ANSI:X.Y`; a type with one cell `X.Y`; an entry with no line | the strings and the dict | `('ANSI', 'X.Y')`, `('LEGACY', 'X.Y')` and four times `None`; the three clauses `Spark takes ntz_ns under LEGACY alone: ANSI raises X.Y`, `Spark takes ltz_ns under LEGACY alone at precision 9: ANSI raises X.Y`, `Spark takes ltz_ns under ANSI alone at precision 8: LEGACY raises Z`, each with its setting; `{}` twice | a clause that names the setting that takes as the one that rejects; a clause for a type that is not admitted |
| the doctest of `names_decline` (Python; step 2, edit 1) | a note that holds `declines under ANSI: VarkaYearSuite, "year declines"` after another clause; then six notes asked for ANSI: the words alone; the words and a suite; the words, a suite, a comma and nothing before the next `;`; the words and a test with no suite; a first name that does not end in `Suite`; a full clause for LEGACY | the strings | `True`; then six times `False` | a note that holds the words and names no test, or names another setting's test, passing a fused claim: the substring test of the plan's second version |
| `python3 dev/varka_census.py --check` over the committed files | every asked entry and type | the table, the curated file | no error; `census.tsv` is what the table and the curated file give | a cell admitted that Spark rejects; a line missing from the table; a `?` left in it |
| `VarkaNanosAdmissionSuite`, unchanged | the table with `unix_nanos`'s two lines | as in part B | pass | an answer of the two new lines typed by hand |
| the conformance suites, unchanged | each suite's forms over the entries its type admits | each suite's fixture | the same passes and cancels as before part C: no suite's type is a nanosecond type | a census cell of a nanosecond type read as a claim over another type |

**The planted faults**, each run by hand and reverted.

| plant | the check that fails | with |
| :--- | :--- | :--- |
| in the table, turn the three answers of `months_between` over `ntz_ns` to `accepted` | `python3 dev/varka_census.py --check` exits 1 | `census: sql/varka/plans/m9/census.tsv is stale; run dev/varka_census.py` |
| delete the table's line of `year` and `ntz_ns` | the same, exits 1 | `census: year: no line in sql/varka/plans/m9/nanos_admission.tsv says whether Spark takes a ntz_ns; run python3 dev/varka_census.py --asked, then VARKA_NANOS_ADMISSION_REGEN=true build/sbt 'catalyst/testOnly *VarkaNanosAdmissionSuite'`; `--asked` then prints `... 1 to ask` and the line is back with `?` |
| in the curated file, the fused field of `isnull` gains `,ntz_ns` | the same, exits 1 | `census: isnull: no coverage row has a IsNull node over a ntz_ns operand` |
| delete the curated line that begins `unix_nanos\|` | the same, exits 1 | `census: unix_nanos (datetimeExpressions, UnixNanos): neither curated nor excluded` |
| in `admit_nanos`, the line `both = [...]` loses ` and one_setting(a) is None`: the rule of the plan's first version | `python3 dev/varka_census.py --self-test` exits 1 | `Expected:` `(['date', 'ntz', 'ntz_ns', 'ltz_ns'], ['int'], [])` and `Got:` `(['date', 'ntz', 'ntz_ns'], ['int'], ['ltz_ns at precision 8, 9: ANSI:X.Y'])` |
| in the table, turn the three answers of `year` over `ntz_ns` to `ANSI:X.Y`, and in the curated file add `,ntz_ns` to the fused field of `year` | `python3 dev/varka_census.py --check` exits 1 | `census: year: Spark takes ntz_ns under LEGACY alone: ANSI raises X.Y, so a fused claim over ntz_ns needs a kernel that declines under ANSI by name and a test of that decline: name the test in the note as "declines under ANSI: <suite>, <test>"`. With `; declines under ANSI: VarkaXSuite, "t"` added to the end of the same curated line that error goes, and the error of the missing coverage row stays |
| the last plant, and at the end of the same curated line `; declines under ANSI: ` (the words, a space and nothing else); then, in its place, `; declines under ANSI: VarkaXSuite` (a suite and no test) | the same, exits 1, both times | the same line `census: year: Spark takes ntz_ns under LEGACY alone: ANSI raises X.Y, so a fused claim over ntz_ns needs ...` is still printed: the words alone name no test |
| in `names_decline`, the pattern becomes `rf"declines under {setting}: "`: the substring test of the plan's second version | `python3 dev/varka_census.py --self-test` exits 1 | `Expected:` `[False, False, False, False, False, False]` and `Got:` `[True, True, True, True, True, False]` |

**The planted difference that must pass.** With the first half of the last plant alone (the
three answers of `year` over `ntz_ns` turned to `ANSI:X.Y`, the curated file as it is), run
`python3 dev/varka_census.py > /dev/null`. Then:

    grep -P '^year\t' $M9/census.tsv | cut -f5,6 | tr '\t' '|'
                                    # Spark takes ntz_ns under LEGACY alone: ANSI raises X.Y|<the types of year, ntz_ns still among them>
    python3 dev/varka_census.py --summary | diff $T/after.txt -         # nothing: the type is admitted as before
    python3 dev/varka_census.py --check; echo $?                        # 0

Revert the table and `census.tsv` after it. On the prototype the first command printed
`Spark takes ntz_ns under LEGACY alone: ANSI raises X.Y|date,ntz,ltz,ntz_ns,ltz_ns`.

**The commands, in order, each with what it must print.**

    # 1. the doctests and the check
    python3 dev/varka_census.py --self-test; echo $?                    # 0
    python3 dev/varka_census.py --check; echo $?                        # 0
    # 2. the table: complete, answered, unmoved by --asked
    python3 dev/varka_census.py --asked                                 # ...: N+1 entries, 2N+2 lines, 0 to ask
    grep -c -P '\t\?\t' $M9/nanos_admission.tsv                          # 0
    # 3. a and b of 4.5, and the two types' lines
    awk -F'\t' 'function ok(c) {return c=="accepted" || c ~ /^(ANSI|LEGACY):[^,]+$/} NR>1 && ok($3) && ok($4) && ok($5) {n[$2]++} END{print n["ntz_ns"]+0, n["ltz_ns"]+0}' $M9/nanos_admission.tsv
                                    # a and b: the lines whose three cells each admit (63 62 on the prototype)
    grep -E '^  (ntz_ns|ltz_ns) ' $T/after.txt
                                    #   ntz_ns  new         0 / a+5      (68 on the prototype)
                                    #   ltz_ns  new         0 / b+5      (67 on the prototype)
    # 4. the totals, by 4.5's rule
    grep -E '^in scope:|pairs: ' $T/before.txt $T/after.txt
                                    # in scope: the second 2 entries above the first; pairs: the second a+b+5 above the first, fused today the same
                                    # (198 to 200 entries, 1034 to 1164 pairs, fused today 585 twice, on the prototype)
    # 5. Spark's rejections are reasons, one entry a line; and no cell is one setting's alone
    awk -F'\t' '$5 ~ /Spark takes/ {print $1": "$5}' $M9/census.tsv
                                    # nothing on the prediction; else one line for each entry with such a cell, which the description quotes
    awk -F'\t' '$5 ~ /Spark rejects/ {print $1": "$5}' $M9/census.tsv
                                    # months_between: Spark rejects ntz_ns: DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE; Spark rejects ltz_ns: DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE
                                    # convert_timezone: Spark rejects ltz_ns: DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE
                                    # ntz_minus_ntz: Spark rejects ntz_ns: UNSUPPORTED_FEATURE.TIMESTAMP_NANOS_SUBTRACTION; Spark rejects ltz_ns: UNSUPPORTED_FEATURE.TIMESTAMP_NANOS_SUBTRACTION
    # 6. every cell of the two types names a row of phase 2
    awk -F'\t' 'NR>1 && $4=="in" && (","$6",") ~ /,(ntz_ns|ltz_ns),/ && $11 !~ /(^|,)(699|67[345])(,|$)/' $M9/census.tsv | wc -l   # 0
    # 7. the two names in scope
    grep -c "nanosecond timestamps, precision 7 to 9" $M9/census.tsv    # 0
    grep -P "^(timestamp_nanos|unix_nanos)\t" $M9/census.tsv | cut -f1,4,6,7,11 | tr '\t' ' '
                                    # timestamp_nanos in byte,short,int,bigint,dec ltz_ns 675
                                    # unix_nanos in ntz_ns,ltz_ns dec 675
    # 8. no fused claim of another type moved
    python3 - <<'EOF'
    a = {l.split("\t")[0]: l.split("\t") for l in open("target/varka-661/census-before.tsv").read().splitlines()[1:]}
    b = {l.split("\t")[0]: l.split("\t") for l in open("sql/varka/plans/m9/census.tsv").read().splitlines()[1:]}
    print(sorted(k for k in a if a[k][3] == "in" and (a[k][7], a[k][12]) != (b[k][7], b[k][12])))
    EOF
                                    # []
    # 9. the working tree: the files below and no other
    git status --short
    # 10. the planted faults of the table above, one at a time, each reverted
    # 11. the JVM
    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaNanosAdmissionSuite *ConformanceSuite'
                                    # Failed 0, Errors 0
    python3 dev/varka_census.py --milestone 8 --check; echo $?          # 0
    # 12. the planted difference that must pass, of the paragraph above, then reverted

Command 9's list, each ` M`: `dev/varka_census.py`, `$M9/census_curated.txt`,
`$M9/census.tsv`, `$M9/nanos_admission.tsv`, `docs/sql-varka.md`, `$M9/PLAN.md` and
`$M9/VARKA-661.md`.

**Expectations.**

- [ ] The interim rule is gone: `grep -c 'if name not in CAST_ENTRY_MODES:'
  dev/varka_census.py` prints `0`, and the reason `NANOS` with it.
- [ ] No line of the curated file holds `ntz_ns` or `ltz_ns` in an entry's types but
  `timestamp_nanos`'s result: `grep -c -E '^[^#|]+\|[^|]*(ntz|ltz)_ns' $M9/census_curated.txt`
  prints `0`.
- [ ] The summary's two lines and the totals follow 4.5's rule from the table's `a` and `b`.
- [ ] A type is admitted where Spark takes it under at least one ANSI setting at every
  precision: `one_setting`, `alone` and `names_decline` exist, the planted difference passes
  with its clause, the plant of the first version's rule fails `--self-test`, and a note
  with the words `declines under ANSI: ` and no suite and test does not pass a fused claim.
- [ ] Every cell of the two types names row 699, 673, 674 or 675; three entries carry
  `Spark rejects` on the prediction, and none carries `Spark takes`.
- [ ] What does not change: the cast tables, the probe, the registry, any fused cell.
- [ ] The description lists each `default:` line, the entries no longer `done`, the day's
  `a`, `b` and totals, and each clause `Spark takes ...` with its entry.

**Done when** every command prints as above and the expectations are ticked. The four clauses
of the row's done-when hold from here. Part D adds the band, which `VARKA-660.md` decided with
the types.

**Not here.** No band: no `PRECISION`, no `reads_precision`. No fused mark on any cell of the
two types. No edit of the table by hand, but for the two plants, which are reverted. No
change of the suite or of its calls.

### 5.4 Part D: the band of the precision

**B0, the premises.**

    grep -c 'if name not in CAST_ENTRY_MODES:' dev/varka_census.py       # 1: 0
    grep -c 't, more, rejected = admit_nanos(name, t, admission, errors)' dev/varka_census.py   # 2: 1
    grep -c '^PRECISION = ' dev/varka_census.py                          # 3: 0
    grep -c '^def pair_counts(scoped):$' dev/varka_census.py             # 4: 1
    tail -1 sql/varka/skills/working-in-this-repo.md | grep -c .         # 5: 1
    python3 dev/varka_census.py --check; echo $?                         # 6: 0
    git branch --list 'varka-661d*'                                      # 7: nothing

Premise 1 or 2 printing otherwise: the part ends, "part C is not merged". Premise 3 printing
`1`: part D is merged. Premises 4 and 6 printing otherwise: the part ends with the line.
Premise 5 printing `0`: the file ends with an empty line; step 3 then adds no empty line of
its own first. Premise 7: the part ends with the branch's name.

**The steps.**

1. `python3 dev/varka_census.py --summary > $T/before.txt`.
2. The script: `python3 $T/edits660.py $M9/VARKA-660.md dev/varka_census.py 30 31 32 33 34
   35 36 37`, which prints `8 edits applied: ...`; `ruff format dev/varka_census.py`;
   `python3 dev/varka_census.py --self-test; echo $?` prints `0`; then `census`.
3. `sql/varka/skills/working-in-this-repo.md`: a new section at the file's end, after an
   empty line, then `dev/varka_toc.py SKILLS.md --from sql/varka/skills`, which rewrites the
   index. The section, wrapped within 100 columns:

        ## Spark's nanosecond surface is asked, not listed

        The census admits `ntz_ns` and `ltz_ns` for an entry from
        `sql/varka/plans/m9/nanos_admission.tsv` alone. `python3 dev/varka_census.py --asked`
        writes its lines, one for each entry that takes a timestamp and each type, and
        `VARKA_NANOS_ADMISSION_REGEN=true build/sbt 'catalyst/testOnly *VarkaNanosAdmissionSuite'`
        writes its answers: Spark's analyzer and `checkAnalysis`, with
        `spark.sql.timestampNanosTypes.enabled` set on, under both ANSI settings. A type is
        admitted where Spark takes it under at least one of the two at every precision. Never
        type a nanosecond type into an entry's types, and never edit the table by hand.

        Where one setting alone takes a type, the census's reason column says so: `Spark takes
        <type> under LEGACY alone: ANSI raises <condition>`. A fused claim over such a cell
        needs a kernel that declines under the setting that rejects, by name, and a test of
        that decline; `dev/varka_census.py --check` fails until the entry's note names the
        test as `declines under ANSI: <suite>, <test>` (or `LEGACY`).

        After a sync with apache/spark the suite fails where Spark moved an answer; run the two
        commands, then `python3 dev/varka_census.py`. A newly accepted cell joins the census
        with no row: add the row that owes it to the entry's `rows`. Then run the three commands
        of `plans/m9/VARKA-660.md` 4.4, "The upkeep of the list": a class or a helper they print
        beyond that plan's lists reads a precision, and if it is a census entry its line joins
        `PRECISION` in `dev/varka_census.py`.

        A fused claim of `timestampadd` over a nanosecond type, and of the six cast pairs that
        read their target's precision, needs a coverage row at each of the precisions 7, 8 and 9
        in the shapes `plans/m9/VARKA-660.md` 4.4 gives; with fewer the claim is `^`, fused in
        part. The census cannot see a row's values: such a row's fixture holds a value off the
        precision's step.

4. Row 681's register. `git grep -l 'SPARK-58482' -- sql/varka docs ':!sql/varka/plans'`
   finds it: SPARK-58482 is its first entry (`m10/SCOPE.md` item 123). Where the command
   prints exactly one file, that file gains two entries in the form of its own entries, each
   with these four facts: the version (4.3.0, the flag's), the issue, the Varka code that
   depends on it, and the test that pins it. SPARK-56822, the preview: `VarkaTypes`' two
   entries and `dev/varka_census.py`'s `NS`; pinned by `VarkaNanosAdmissionSuite`, "the
   preview flag is the one this suite sets, and a malformed call is no answer". SPARK-60335,
   no implicit narrowing: `nanos_admission.tsv`'s rejections; pinned by the same suite's
   "nanos_admission.tsv is Spark's answer for every entry the census asks". Where the command
   prints no file or several, the builder adds nothing, and the outcome says "row 681's
   register does not exist yet; it owes the two entries of `VARKA-661.md` 5.4, step 4".
5. `$M9/PLAN.md`. Row 661's cell, by 5.0's rule: `**Started**` becomes `**Done**`, and the
   clause is `part D, the band, #<number>; <the day's two lines of the summary>; <the count>
   cast pairs`, so the cell names the four pull requests. Item 4
   of 1.2: after the item's last line, which ends ``and 14 of the 20 cast pairs.``, a new
   line, re-wrapped within 100 columns: "*<the date>:* row 661 built it: `ntz_ns` admits
   <a+5> pairs and `ltz_ns` <b+5>, so three quarters are <the two numbers, rounded up>." This
   file's section 14: part D's paragraph and every prediction of section 7 scored.

**The tests.** The doctests that edits 30 to 37 bring are `VARKA-660.md` 6.6's last eight
lines of its doctest table (`reads_precision`, `sql_code`, `sql_calls`, `bare_column`,
`nanos_bands`, `cast_bands`, the band's rule in `row`, the casts' band in `pair_credit`), with
their inputs, expected values and the failure each catches. They run in `--self-test` and in
`--check`. Nothing else is tested by this part: it adds no Java.

**The planted faults**, each run by hand and reverted.

| plant | the check that fails | with |
| :--- | :--- | :--- |
| in `PRECISION`, the keyword `"NANOSECOND"` becomes `"HOUR"` | `python3 dev/varka_census.py --self-test` exits 1: six examples of `nanos_bands` fail | the first: `bands("timestampadd(HOUR, i, n7) = n7 AND concat('NANOSECOND', 'x') IS NOT NULL")`, `Expected: []`, `Got: ['p7']` |
| in the curated file, the fused field of `timestampadd` gains `,ntz_ns` | `python3 dev/varka_census.py --check` exits 1 | `census: timestampadd: no coverage row has a TimestampAdd node over a ntz_ns operand` and `census: timestampadd: its answer over ntz_ns depends on the precision, and no coverage row holds its NANOSECOND call over a column of precision 7, 8, 9: mark it ntz_ns^` |

**The commands, in order, each with what it must print.**

    python3 dev/varka_census.py --self-test; echo $?                    # 0
    python3 dev/varka_census.py --check; echo $?                        # 0
    python3 dev/varka_census.py --summary | diff $T/before.txt -
                                    # four lines added and none removed:
                                    # > nanosecond entries and cast pairs whose answer depends on the precision, with a matching coverage row at each of 7, 8 and 9:
                                    # >   ntz_ns                          0 / 1
                                    # >   ltz_ns                          0 / 1
                                    # >   cast pairs                      0 / 6
    grep -A8 '^PRECISION = ' dev/varka_census.py | grep -c '"timestampadd"'   # 2: the entry's key and its first spelling
    git diff --stat $B -- $M9/census.tsv $M9/casts.tsv $M9/nanos_admission.tsv docs/sql-varka.md   # nothing
    python3 dev/varka_toc.py --check SKILLS.md --from sql/varka/skills; echo $?   # 0 (fact 31: without --from it prints "stale" on a correct tree)
    git status --short
    # the two planted faults, one at a time, each reverted

The working tree, each ` M`: `dev/varka_census.py`,
`sql/varka/skills/working-in-this-repo.md`, `SKILLS.md`, `$M9/PLAN.md`, `$M9/VARKA-661.md`,
and the register's file where step 4 found it.

**Expectations.**

- [ ] The script holds `VARKA-660.md`'s design: all 38 edits of its 6.1 are in, edit 19 as
  part C wrote it, and beside them only this plan's own edits: part B's two and part C's
  other seven.
- [ ] The summary gains the four lines; no committed census or cast file moves.
- [ ] The skills section exists and `SKILLS.md`'s index names it.
- [ ] Row 661 reads **Done** in `$M9/PLAN.md`; section 14 scores every prediction.

**Done when** every command prints as above and the expectations are ticked.

**Not here.** No coverage row for the band: rows 674 and 675 write them. No change of the
admission table or of any cell.

## 6. Tests, proofs, fuzzing and coverage

| suite | what it holds | the failure only it catches |
| :--- | :--- | :--- |
| the doctests of `dev/varka_census.py`, run by `--self-test` and `--check` | `legal`, `ns_cast_kind`, `column_type` (part A); the admission table's reader, text, errors and rule (part B); `one_setting`, `alone` and the admission under either ANSI setting (part C); the band's eight functions (part D) | a wrong transcription of `Cast` before any JVM runs; a stale or shuffled table; the first version's rule, both settings must accept, coming back; a claim from one precision |
| `VarkaCastMatrixSuite` (JUnit 5, `@VarkaSuite`, catalyst) | the legality of all 256 ordered pairs against `Cast`, over every form; the 20 pairs pinned; the probe's 102 new form pairs against the compiler | a wrong `legal()`; the two names dropped on both sides; a compiler that raises over a declined type |
| `VarkaTypesSuite`, `VarkaFuzzTypesSuite` (JUnit 5, catalyst) | the two tables are the registry; each precision has its kind's id; a nanosecond column declines by name; no fuzzer draws the types | a registry edit that is more than two declined entries |
| `VarkaTypeConformance` through every conformance suite (JUnit 5, catalyst) | one cast test for each census type | a census type with no cast test; a nanosecond cast that fuses with no claim |
| `VarkaNanosAdmissionSuite` (JUnit 5, `@VarkaSuite`, catalyst) | the table is Spark's answer today, each rejection held to its control; every call is well formed; the flag is the suite's own setting; a cell names the setting that rejects, and one setting admits; two timestamps of two types reach an entry as one | every move of Spark's surface; a malformed call recorded as a rejection; a run on the tests' default; a node left with operands of two types |
| Spark's three cast suites under Varka (ScalaTest, sql/core) | the record names the six forms by the census's two names | a type under two names |
| `dev/varka_census.py --check` in the hook, the lint job, the push check and the regeneration | every committed file of the census equals a fresh one; the table's lines are the asked entries' | any of the above reaching master |

- **Proof files:** none. The row adds no lowering. The cast table's proof column names row
  684's sweep for the ten pairs that read the zone.
- **Fuzzing:** no fuzzer draws either type: `sql/varka/fuzz/types.tsv` reads `n/a` for both
  until row 699.
- **Coverage rows:** none. No IR record, so no registry suite of an IR node applies.
- **JaCoCo and mutation:** not applied. The row's code is a Python script held by its
  doctests and planted faults, a test suite, a test helper and two declined registry entries.
- **Test time**, counted from the work. `VarkaNanosAdmissionSuite`: 128 lines, each asked at
  three precisions under two settings, is 768 analyses of a one-expression projection; the
  controls of the 15 rejected cells add at most 60 (two settings, two microsecond types); the
  result column adds at most 125; the control test 256 (64 calls, two types, two settings);
  the third test 11; the test of mixed operands 50: about 1,300 analyses in all, and no kernel
  is compiled or run. `VarkaCastMatrixSuite`: 102 more
  compiles that decline at the column. Each conformance suite: two cancelled tests.
  Prediction 7 of section 7 bounds the first.

## 7. Measurement

No benchmark section and no A/B arm: the row emits no kernel, and `emitted_bytes.json` does
not move in any part. Nothing runs on the EPYC runners for it.

**Predictions, registered 27 November 2026, before any run.** Each count is the prototype's;
the rule beside it is what the build scores.

| # | prediction | on the prototype | the rule at the build | scored by |
| ---: | :--- | :--- | :--- | :--- |
| 1 | after part A the two types admit the cast entries alone, and no source of `cast` or `try_cast` is lost | `0 / 5` twice; `k` is 0 | `0 / 5` twice; the fused pairs fall by `$T/lost.txt` | 5.1, command 3 |
| 2 | the cast table gains exactly 20 pairs, each legal in the three modes, and the unchanged legality test agrees with no edit of 4.4 | 129 to 149 legal pairs | plus 20 | 5.1, commands 4 and 11 |
| 3 | the probe gains 102 lines, each `declined`, and raises nothing; every conformance suite cancels its two new tests and fails none | read, not run | the same | 5.1, step 8 and commands 5 and 11 |
| 4 | the Java and the Scala of parts A and B compile with no line of this plan corrected | not compiled | yes or no; each corrected line is named | the descriptions of parts A and B |
| 5 | Spark's answers: every cell `accepted` but 15, the three precisions of five (entry, type) lines, with the two conditions of 5.2's command 3; no cell differs between the ANSI settings, so no clause `Spark takes` is written; no cell has a condition outside those two; nine entries name a nanosecond result | 130 lines in part B, 132 in part C | the table is the answer | 5.2, command 3; 5.3, command 5 |
| 6 | no call of `CALLS` is added or corrected at the build | 66 calls | yes or no | the description of part B |
| 7 | `VarkaNanosAdmissionSuite` takes under 20 seconds of test time in the Build | not timed | the same; read `python3 dev/varka_calibration.py` first, and add the scored row with `--add-row` | sbt's time for the suite, in part B's outcome |
| 8 | after part C, `ntz_ns` admits 69 pairs and `ltz_ns` 68; 200 entries are in scope; three entries carry `Spark rejects` | yes | `a + 5` and `b + 5`; `E + 2` | 5.3, commands 3 to 5 |
| 9 | no entry gets its row by default, and none is absent | `0 by default, 0 absent` | the script's line | 5.3, step 6 |
| 10 | each part is under its estimate by no more than half over: A under 400 counted lines, B under 750, C under 300, D under 450 | 250, 550, 180, 330 estimated | the same | the size check of each part |
| 11 | registered after the first review, before any run: Spark takes each of the five calls of `MIXED_CALLS` over each of the five pairs of 4.3's table under both ANSI settings, and brings both operands to the pair's one type | read from `findWiderDateTimeType`, not run | yes or no for each pair; a pair whose type was changed by 5.2's "The first run", line 13, is a no | the fifth test of `VarkaNanosAdmissionSuite`, in part B's outcome |
| 12 | registered after the first review: no line of 5.2's "The first run" but lines 1 to 5 is met, and part B needs no stop | not run | yes or no; each line met is named | the description of part B |

*27 November 2026, after the first review and the owner's answers:* the estimates of
prediction 10 are corrected to 250, 702, 267 and 330 (section 5): part B gained the fourth
and fifth tests, the control inside the answer and the fourth list of generated files, and
part C gained the admission under either ANSI setting. The bounds stay as registered: B under
750, C under 300.

*27 November 2026, after the second review and the lead's probe* (section 3, "The lead's
probe"): the table above stays as registered. The probe and the lead's decision on
`unix_date` (4.3) change this much of it.

- **Prediction 5** is measured in part before the build, at precisions 7 and 9: its five
  rejected lines and their two conditions hold, no cell has another condition, and no call
  differs between the ANSI settings. Two of its clauses are corrected. Its counts of lines,
  130 in part B and 132 in part C, held `unix_date`'s two: they are 128 and 130. And "nine
  entries name a nanosecond result" is measured wrong: 20 do, the nine and eleven entries
  whose curated result is `same`, which moves no cell (5.2, command 3). With `unix_date`
  asked, as registered, the prediction would also have been wrong in its 15 cells: Spark
  rejects that call over both types. The build scores the prediction on the suite's table,
  precision 8 included.
- **Prediction 6** counted 66 calls: `CALLS` has 65.
- **Prediction 8**'s numbers are corrected to 68 pairs for `ntz_ns` and 67 for `ltz_ns`. The
  registered 69 and 68 counted `unix_date`. Its 200 entries and its three entries with
  `Spark rejects` stand, and the probe measured the three. The rule at the build is
  unchanged: `a + 5`, `b + 5` and `E + 2`.
- **Prediction 10**'s estimates are corrected to 250, 701, 282 and 330: part B lost one call,
  and part C gained `names_decline` and its doctest. The bounds stay as registered: C under
  300.
- **Prediction 11** is measured in part: the widened result type for three of its five pairs
  (4.3's table), and not the operand types that the fifth test reads. It is scored by that
  test's first run, pair by pair.

`VARKA-660.md` section 8 registered seven predictions that this row's outcome scores: its 1
by 5.3's command 3, its 2 by 5.1's command 4, its 3 by 5.2's command 3, its 4 by 5.4's
commands, its 7 by part A's time of `VarkaCastMatrixSuite`; its 5 and 6 are scored later, by
row 699 and at the close.

## 8. Who builds it

`m9/PLAN.md` 1.4 has this row in phase 2's foundation, and G5's cheaper models build the
fan-out, "After the foundation". The lead's word for this plan is that a cheaper agent may
build it, so each sheet is written for one: no step leaves a choice, and each stop is a
report. The owner decided who builds each part on 27 November 2026 (section 13, question 4).

- **Parts A, C and D** go to a cheaper model. Their Python is applied by a tool or copied
  from text that ran on the prototype, and each has an oracle the prototype printed. Part A's
  Java is twelve small edits that a compile checks.
- **Part B** is built at full strength. It holds about 460 lines of Java and Scala that no
  compiler has seen. The judgement the first version left to its builder, whether a call
  that Spark refuses is malformed, is now the suite's own rule (4.3), and every first-run
  difference the planner could foresee has its one repair or its stop (5.2, "The first
  run"); what is left for full strength is a difference that table does not name.

None of the four is a fan-out pull request, so none has a line in row 658's ledger
(section 11).

## 9. Risks

1. **The Java and the Scala were never compiled.** Held by 5.0's rule for a compile error, by
   the compile as the first JVM step of parts A and B, and by prediction 4, which counts the
   corrections.
2. **Spark's analyzer answers otherwise than `VARKA-660.md` read.** Held by the design: the
   table is the answer, the prediction is scored, and part C's counts follow the table.
3. **An answer differs between the two ANSI settings.** The cell names the setting that
   rejects, the type is admitted, the reason column says under which setting alone, and a
   fused claim over the cell fails `--check` until its note names the test of the decline
   (4.3). The owner's answer to question 2 of section 13.
4. **A malformed call is recorded as a rejection.** Held by the control: a rejection is
   written only where Spark takes the same call over a microsecond timestamp under the same
   setting, and a second test asks every call over the microsecond types by itself.
5. **The probe raises over a nanosecond column** in place of declining. Then part A ends at
   step 8 with that test's output: it is a compiler fault of its own.
6. **The conformance harness parses `TIMESTAMP_NTZ(9)` under the tests' default** of the flag
   (`DataType.fromDDL` in `castTo`, and its `resolve`). If a sync turns that default off under
   tests, the suites of `date`, `ntz`, `time` and `ltz` fail their two new tests with
   `FEATURE_NOT_ENABLED`; the fix is to set the flag in the harness as `castExpression` does.
7. **Between parts A and C the census under-counts the types:** 5 pairs each. Held by the
   row's status, **Started**, and by part C. Question 3 of section 13.
8. **`cast` and `try_cast` lose a fused source** for each of `date`, `ntz`, `time` and `ltz`
   they count (4.5). Held by part A's step 1, which prints the number. Question 1.
9. **Row 654 lands differently from its planner's prototype.** Every old text is found
   exactly once or the part ends, and every count has its rule.
10. **Row 656 merged first** and changed the switches' `TIMESTAMP` arms, `CAST_TESTS` and the
    fuzz descriptor. Held by part A's premises 13 to 18 and by placing each case at the
    switch's end.
11. **Row 653's part D merged first,** so the census run no longer writes the docs page.
    Held by 5.0's `census` function, which runs `--docs` where the script has it.
12. **Row 696 merged first.** Part A's premise 8 stops, and this plan is re-cut on its
    layout.
13. **The regeneration driver's three edits were checked by `bash -n` and by a dry run on a
    copy that is no git repository.** Held by part B's commands 5 and 6, which run the driver.
14. **The registry says `plain`, 64 bits, for a type the cache stores as a struct.** It is
    the layout of Spark's standard mapping, which the registry's own test checks, and the
    comment says whose the other is. Row 699 registers the struct with the kernels that read
    it.
15. **The census moves between this plan and the build:** a new asked entry has no call.
    Held by part B's step 7, its first rule.
16. **A sixth generated file** that a merge driver merges. It is keyed by its first two
    columns, changes only at a sync or when an asked entry comes or goes, and its lines'
    order is checked.
17. **Part C edits 129 lines of hand-curated data by a script.** A reviewer cannot read each
    by eye. Held by 5.3's commands 3 to 8, which count what it did.
18. **A condition that no list names is recorded as Spark rejecting the type.** It is
    recorded only on the control's word, never for `INTERNAL_ERROR` or `FEATURE_NOT_ENABLED`,
    and 5.2's command 3 prints each such cell for the description. Question 5 of section 13.
19. **The census's code for a cell of one setting has no committed cell to run on,** since
    none is predicted. Held by the doctests of `one_setting`, `alone` and `admit_nanos`, by
    the suite's fourth test, and by part C's planted difference, which was run on the
    prototype.
20. **Spark widens two timestamp types otherwise than `findWiderDateTimeType` reads,** or
    under one setting only. Then the fifth test fails with the lines, and 5.2's "The first
    run", line 13, either records the other type or ends the part.
21. **Part C's eight edits of the script are copied by hand,** not applied by a tool. Each old
    text is in the file once, the doctests they bring fail on a wrong line, and `ruff format`
    settles the layout.
22. **An asked entry's call is taken by no microsecond timestamp,** so a rejection of it says
    nothing of the type. The lead's probe found one such call among the first version's 66,
    `unix_date(c)`, and the entry left the asked set (4.3). Held for `unix_date` by part B's
    premise 21, and for any later entry by the test of the calls and by line 9 of 5.2's "The
    first run", which end the part with the call's name.
23. **The census reads the form of a decline's note, not the test it names.** `names_decline`
    accepts any class name that ends in `Suite` and any test name. Held by the review of the
    pull request that writes the note (section 11, seam 2).

## 10. The house rules that bind this row

From `sql/varka/AGENTS.md`:

- "The generated files every type row rewrites (`coverage.json`, `emitted_bytes.json`,
  `width_audit.json`, `types.tsv`, the census and cast tables, the docs blocks) are never merged
  by hand." The admission table is one more: never edit a line of it.
- "**A pull request stays under 1000 changed lines** of hand-written files - source, plans and
  notes, and hand-curated data such as `census_curated.txt`". Part C's curated lines count
  twice, added and removed.
- "**A comment describes the code as it is.**" Part A's interim comment says what the rule
  does, not that it is temporary; part C replaces it.
- "**A new catalyst suite whose helpers are Java is a JUnit 5 class under `@VarkaSuite`**
  (VARKA-312), each `@Test` carrying its sentence as a `@DisplayName`". The new suite and the
  three new tests in existing suites follow it.
- "Predictions written before a measurement stay in the file after it, scored honestly - the
  project keeps count. Do not retro-edit a task file to look prescient; add the correction and
  say what it corrects." Section 7's table stays as registered.
- "**Check before merging that a pull request cannot turn master red.**" Each part commits
  generated records: if master moved since its last green CI, merge master, regenerate and
  rerun the sheet's commands.

From the repository's `CLAUDE.md`: source lines within 100 characters and ASCII only; the
Apache license header on the new Java file; no push and no pull request without the owner's
approval.

What not to do: do not type `ntz_ns` or `ltz_ns` into an entry's types; do not write or edit
a line of `nanos_admission.tsv` by hand; do not mark any cell of the two types fused; do not
turn the two types' admission, lanes or conformance; do not add a `Storage` kind; do not catch
an exception in a suite to make the probe pass; do not weaken or skip a test that fails; do
not build part D before part C.

## 11. Seams with the plan written beside this one

**Row 658** (the fan-out's template, generator, oracle and ledger), which has no plan this
plan could read. Assumed, each to be checked by row 658's plan:

1. **What a fan-out task edits for a nanosecond cell.** In the curated file, the entry's
   fused field, and its need, rows and note where the claim changes them. Never the entry's
   types, which the admission table gives, and never `nanos_admission.tsv`. Row 658's
   generator emits no edit of either.
2. **Where the generator reads a cell, and the cell's three states.** For one entry and one
   nanosecond type it reads `census.tsv`'s columns `types`, `fused_today`, `fused_in_part`,
   `rows` and `reason`, under the type names `ntz_ns` and `ltz_ns`. A cell is in one of three
   states, and the generator reads the reason column to tell the third:
   - *Rejected.* The type is not in `types`, and `reason` holds a clause that begins `Spark
     rejects <type>`. No task.
   - *Admitted.* The type is in `types`, and `reason` holds no clause that begins `Spark
     takes <type> under`. An ordinary task.
   - *Admitted under one ANSI setting alone* (the owner's answer of 27 November 2026). The
     type is in `types`, and `reason` holds one or two clauses of the form `Spark takes <type>
     under <LEGACY or ANSI> alone: <ANSI or LEGACY> raises <condition>`, with ` at precision
     <p, ...>` before the colon where fewer than three precisions differ. A task whose card
     adds, for each clause: the kernel declines under the setting that raises, with a named
     reason; a test shows that decline; and the entry's note in the curated file gains
     `declines under <that setting>: <suite>, <test>`, without which `python3
     dev/varka_census.py --check` fails, and with it the oracle. The check reads the clause's
     form: a class name that ends in `Suite`, a comma, and a test name that is not empty
     (`names_decline`). It cannot see whether that test exists or asserts the decline; the
     task's review does.
   So the seam "a type Spark rejects is absent from the entry's types; the tool never reads
   the reason" holds for the first two states only. Row 658's plan must say: the tool reads
   `reason` for the clause `Spark takes <type> under`, and a cell with it gets the third
   state's card.
3. **The oracle and the band.** The oracle "fails on an unfused entry". For `timestampadd`
   and the six cast pairs of the band, a claim with fewer than three precisions is `^`, which
   the census does not count: the oracle reads it as unfused. Row 658's template gives such a
   task three coverage rows, in the shapes `VARKA-660.md` 4.4 names, and reads which tasks
   need them from `PRECISION` and `reads_precision` in `dev/varka_census.py` (part D's names).
4. **The oracle's check.** It runs `python3 dev/varka_census.py --check`, which from part C
   fails on a missing line of the table, and on a fused claim over a cell of the third state
   whose note does not name the decline's test. A fan-out task adds no timestamp to an
   entry's types, so it never changes the asked set and never needs the suite's regeneration.
5. **The ledger.** This row's four pull requests are foundation, not fan-out: they get no
   ledger line, whoever builds them, and are not in G5's share.
6. **The guide.** Row 658 rewrites `sql/varka/ADDING_AN_EXPRESSION.md`. This row does not
   touch it or `sql/varka/ADDING_A_TYPE.md`; part D appends one section to
   `sql/varka/skills/working-in-this-repo.md`, at the file's end.

**The merged plans this row meets.**

- **`VARKA-660.md`.** This plan builds its section 6 with five deviations, each recorded in
  that file by edit 2 of section 12: four parts for one order, so its one-off script has
  three steps and its step numbers do not apply; a difference between the ANSI settings is a
  cell and not a failure of the suite, and by the owner's answer the type is admitted where
  one setting takes it; `timestampadd` is asked with the unit `HOUR`; the first run of
  the cast suite fails three tests, not two; and `unix_date` is not asked, so its date
  functions are six and its counts of pairs are four smaller (4.3, 4.5). The storage, which
  its 6.6 left to this plan, is `plain(64)`. Its 38 edits of the script are applied as its
  text has them, but edit 19, whose old text part A changes; this plan adds nine edits of its
  own beside them (5.2, step 4; 5.3, step 2). Its rule that every argument of a call has the
  line's own type stands (4.3). Its 6.5 prints two `sort | uniq -c` outputs of `casts.tsv`
  with a bare `sort`, so their order there is the `en_US.UTF-8` one; this plan's sheets run
  the same commands under `LC_ALL=C` and give that order (5.1, command 4). The lines are the
  same.
- **`VARKA-654.md`.** This row extends what it makes. Its rule 4 and its script count
  `unix_date` for `ntz` and `ltz`, which Spark rejects: edit 4 of section 12 takes the entry
  out of both, at the source, and part B's premise 21 holds the result. One more sentence of
  it is wrong and binds its builder: its 5.2, step 7, says the first run of
  `VarkaCastMatrixSuite` fails two tests; it fails three, since "a pair whose legality is
  flipped in one mode fails the comparison, naming the pair" reads the same file (edit 3 of
  section 12).
- **`VARKA-656.md`.** Nothing of it must change. Its `isLtz` stays false for the nanosecond
  types, which have ids of their own. If it merges first, part A's premises say what moves.
  If part A merges first, row 656's rewording of `README.md`'s list of types does not find
  its two old lines, which part A's step 11 rewrote; its own rule for an old line that is not
  found applies, and the sentence to reach is part A's second form.
- **`VARKA-653.md`.** Its parts A1 to F leave the census's five files as they are, so this
  row's edits of the curated file, of `census.tsv` and of the cast tables apply with or
  without them. Three things differ once they merge, each held by 5.0: after its part B the
  three generated JSON files are views that git ignores, and this row names none of them;
  after its part D `python3 dev/varka_census.py` no longer writes the docs page, and the
  `census` function runs `--docs`, which that plan allows a type row; and its part D rewrites
  three lines of `VARKA-660.md`'s section 6, none of them in 6.1, which the tool of 5.0 reads.
  Nothing of `VARKA-653.md` must change.
- **Row 696** (the census's files, one file an entry; filed by `VARKA-653.md`, no plan). If
  it merges before part A, part A stops at premise 8 and this plan is re-cut. If it is
  planned after this row, its plan takes `nanos_admission.tsv` as a sixth file of the census
  and `--asked` as the writer of its lines.
- **Row 699** has no plan. This row hands it: the two ids, `Declined`, with `plain(64)` and
  the comment that names the struct as its own; the two cast tests of `VarkaTypeConformance`;
  the coverage column types `timestamp_ntz(7)` to `timestamp_ltz(9)`, which `column_type`
  reads; and `VARKA-660.md` 4.7's rule that a fused cell holds over the type's whole range.
  *28 November 2026:* this plan named row 662 where it now names row 699. Row 662 became
  the measurement of the representations alone (`VARKA-662.md`) and touches nothing of
  this row; row 699 takes the ids, the struct in and out and every cell.
- **Rows 699, 673, 674 and 675** have no plans. This row hands each two rules of 4.3. A cell
  is the entry over operands of one type: a call over two timestamps of two types reaches the
  entry behind a cast, so it fuses when the entry's cell and the cast's pair in `casts.tsv`
  both do, and the suite's fifth test pins which type Spark brings the operands to. And a
  cell whose reason holds `Spark takes <type> under <setting> alone` owes, when it is fused, a
  kernel that declines under the other setting by name, a test of that decline, and the note
  `declines under <setting>: <suite>, <test>`.
- **Row 681** has no file yet. Part D's step 4 adds this row's two entries where the register
  exists, and says so in its outcome where it does not.
- **Rows 655, 678 and 679:** no seam.

**Deviations from the shared names of the zone family:** none. The one name of theirs this
row writes is `VarkaZoneSweepSuite`, in the cast table's proof column, through `ZONE_PROOF`.

## 12. Edits to other plans, made by this plan's own pull request

The pull request that adds this file to `sql/varka/plans/m9/` also makes these edits. Each is
an exact old text and its new text; a block is indented by four spaces, and what follows them
is the file's own text. Each old text was matched exactly once, as a whole line where it is
one, in its file on master `e0aee0a14b1`.

1. `m9/PLAN.md`, row 661's line. Old, part of the line:

    The census and the cast matrix with the nanosecond types. **Filed** (25 November 2026; the admission check:

   New, in its place:

    The census and the cast matrix with the nanosecond types. **Planned** (`VARKA-661.md`, 27 November 2026: four parts, the two types in the registry and the cast matrix, then Spark's answers as a table that a suite writes from the analyzer and its check with the flag set on, then the census admitting the types from that table, then the band of the precision; filed 25 November 2026; the admission check:

2. `VARKA-660.md`, section 6.0. After the heading line, which is exactly

    ### 6.0 The order, and where each file comes from

   and the empty line under it, a new paragraph and an empty line:

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

3. `VARKA-654.md`, section 5.2, step 7, two lines. Old, a whole line:

       script can write `casts.tsv`, so the first run of the suite fails two tests by design.

   New:

       script can write `casts.tsv`, so the first run of the suite fails three tests by design.

   Old, two whole lines:

       The second command writes `$M9/casts_fused.tsv` and fails exactly two tests, `legality` and
       `timestampPairs`: `casts.tsv` has no `ltz` pair yet. With any other failure the part ends:

   New, three lines:

       The second command writes `$M9/casts_fused.tsv` and fails exactly three tests, `legality`,
       `plantedLegality` and `timestampPairs`: `casts.tsv` has no `ltz` pair yet, and the planted
       test reads the same file (`VARKA-661.md` 11). With any other failure the part ends:

   The same plan's prediction P11 and its risk 5 say "two"; they stay as registered, and its
   builder scores them against three.

4. `VARKA-654.md`: `unix_date` leaves the date functions that count for `ntz` and `ltz` (the
   lead's decision of 27 November 2026; 4.3). The reason is a measurement: the lead's run of
   Spark's analyzer on master `1924c7a5afb` (section 3) rejected `unix_date(c)` over
   `TIMESTAMP_NTZ` and over `TIMESTAMP`, under both ANSI settings, with
   `DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE`. Four edits, each old text in the file once.

   Section 4.7, rule 4. Old, five whole lines:

    4. **A date function that Spark takes over a timestamp through a cast to `DATE`** (`add_months`,
       `datediff`, `date_diff`, `date_add`, `dateadd`, `date_sub`, `unix_date`) gets `ntz,ltz` at the
       end of its types (the owner, 27 November 2026; `VARKA-660.md` 16). Milestone 8's census
       lists no timestamp for these seven, and stays so (4.4). Row 672 owes `ntz`, and row 668
       owes `ltz`.

   New, nine lines:

    4. **A date function that Spark takes over a timestamp through a cast to `DATE`** (`add_months`,
       `datediff`, `date_diff`, `date_add`, `dateadd`, `date_sub`) gets `ntz,ltz` at the end of its
       types (the owner, 27 November 2026; `VARKA-660.md` 16). Milestone 8's census lists no
       timestamp for these six, and stays so (4.4). Row 672 owes `ntz`, and row 668 owes `ltz`.
       `unix_date` is not among them (the lead, 27 November 2026; `VARKA-661.md` 4.3): it takes a
       `DATE`, and Spark casts no timestamp to it. A run of Spark's analyzer on master
       `1924c7a5afb` rejected `unix_date(c)` over `TIMESTAMP_NTZ` and over `TIMESTAMP`, under both
       ANSI settings, with `DATATYPE_MISMATCH.UNEXPECTED_INPUT_TYPE`. Its line keeps `date` alone,
       as in milestone 8's census.

   Section 4.7, the table of the rows that owe `ltz`, its last line. Old, part of the line:

    | 668, and 672 for `ntz` | `add_months datediff date_diff date_add dateadd date_sub unix_date` (7) | rule 4.

   New, in its place:

    | 668, and 672 for `ntz` | `add_months datediff date_diff date_add dateadd date_sub` (6) | rule 4.

   Section 5.2, the script of step 4. Old, a whole line of the script:

        DATE_FUNCTIONS = "add_months datediff date_diff date_add dateadd date_sub unix_date".split()

   New:

        DATE_FUNCTIONS = "add_months datediff date_diff date_add dateadd date_sub".split()

   The script then leaves the line of `unix_date` as milestone 8's census has it,
   `unix_date|date|int|date|done||`: the entry is on no list of the script and its types hold
   no timestamp. Section 4.10, a new item after the item of the first correction, whose last
   line is exactly

      210 of 304 for 210 of 297 in item 3. The pairs fused stay 585.

   The new item, nine lines, after that line:

    - ***27 November 2026*, a second correction: `unix_date` does not count for `ntz` and `ltz`**
      (4.7, rule 4; `VARKA-661.md` 4.3): Spark rejects it over both. Where this file says the
      seven date functions, read six. Part b's numbers, here and wherever this file repeats them,
      read: 76 `ltz` pairs for 77; 1024 pairs for 1026; 52 pairs gained by the other types for
      53, `ntz` 11 of them for 12; 68 entries edited for 69, 6 of them by rule 4 for 7; 26 no
      longer `done` for 27, the three more than 23 being `add_months`, `datediff` and
      `date_diff`; 59 entries `done` for 58; and 210 of 303 for 210 of 304 in item 3. The script
      of 5.2 prints `68 entries edited, 26 of them no longer done, 16 added; 0 by default, 0
      skipped, 0 absent`, and the summary reads `  ltz     new         0 / 76`.

   The numbers of the new item are measured: the script of `VARKA-654.md` 5.2 with the edit
   above was run on milestone 8's curated file and printed that line, and the census run on
   its result printed 1024 pairs, 59 entries `done`, `49 / 65` for `ntz` and `0 / 76` for
   `ltz` (5.0, the third run). The registered predictions of that plan stay as they are, and
   its builder scores them against these numbers.

No other approved plan must change. `m9/PLAN.md` 1.2, item 4, quotes `VARKA-660.md`'s 69 and
68 pairs; part D's step 5 writes the day's two numbers under it, so it has no edit here.
`VARKA-653.md`'s own edits of `VARKA-660.md` (its part D) do not meet edit 2: they are lines
of the table under the new paragraph.

## 13. Questions for the owner

*27 November 2026:* the owner answered questions 1 to 4: 1 with (a), 2 with (c), 3 with (a)
and 4 with (a). The plan is written on these answers. Question 2's answer is not the one the
plan's first version recommended, and the plan was rewritten on it (4.3, 4.5, parts B and C,
section 12's edit 2). Question 5 is new and open: the plan is written on its first answer,
and the build applies it if the owner does not answer.

*27 November 2026, later:* one decision of this plan is the lead's and not the owner's, made
on the probe of section 3: `unix_date` is asked over no timestamp type (4.3). It narrows no
answer of the owner's: his decision that the date functions count for the timestamp types
rests on Spark's implicit cast to `DATE`, and `unix_date` has none.

1. **`cast` and `try_cast` over `date`, `ntz`, `time` and `ltz` wait for row 674.** From part
   A each of these four sources has two more legal pairs, to the nanosecond types. `cast` and
   `try_cast` count a source fused only when every legal pair from it fuses
   (`m8/PLAN.md` 2.1), so these eight cells, which items 1 and 3 of `m9/PLAN.md` 1.2 count,
   can read fused only after row 674 has fused the source's two nanosecond pairs: a phase 1
   count comes to depend on a phase 2 row. Today none of the eight is fused, so nothing is
   lost on the day.
   - (a) Accept it. It is the rule row 654's question 3 was answered with, and it keeps one
     counting rule. **Recommended, and the default.**
   - (b) Count a source for `cast` and `try_cast` over the microsecond types alone until phase
     2 closes: an edit of `matrix_fused`, and a second rule to explain.
   - (c) Build part A only when phase 1's items are read.

   *27 November 2026:* the owner answered (a): accept it. The casts from `date`, `ntz`,
   `time` and `ltz` wait for row 674 under the one counting rule.
2. **Under which ANSI settings Spark must take a type for the census to admit it.**
   `VARKA-660.md` had the suite fail on a difference; the plan's first version recorded it
   and did not admit the type.
   - (a) Both settings must accept; a cell names the setting that rejects. Recommended by the
     plan's first version: a fused cell then holds in every session.
   - (b) ANSI on alone decides, since it is the session's default.
   - (c) Either setting admits, and the kernel declines under the other.
   No entry is expected to differ (prediction 5).

   *27 November 2026:* the owner answered (c), not the recommendation: a nanosecond type is
   admitted for an entry when Spark accepts it under either ANSI setting, and the kernel
   declines under the setting that rejects. So a cell whose two settings differ is admitted
   and names the setting that rejects and its condition; a cell that both settings reject
   stays out with `Spark rejects ...`; and the row that fuses an admitted cell of one setting
   owes the decline under the other, by name, and a test of it, which `--check` holds through
   the entry's note (4.3).
3. **Parts A and B merge before part C,** so for two pull requests the census reads `0 / 5`
   for each new type and the docs' support table lists the two preview types with nothing
   fused.
   - (a) Merge each part when it is green. **Recommended, and the default:** it is the
     house's one branch at a time, and each state is true.
   - (b) Hold A and B and merge the three as one batch.

   *27 November 2026:* the owner answered (a): each part merges when it is green.
4. **Who builds it.** `m9/PLAN.md` 1.4 has the row in the foundation, at full strength.
   - (a) Parts A, C and D by a cheaper model, part B at full strength (section 8).
     **Recommended, and the default.**
   - (b) All four at full strength.
   - (c) All four by a cheaper model, the lead reading part B's first run.

   *27 November 2026:* the owner answered (a).
5. **Open. A condition that no list names is recorded as Spark rejecting the type.** Spark
   has two conditions for such a rejection today. If a sync brings a third, the suite does
   not judge its name: it records the condition where Spark takes the same call over a
   microsecond timestamp under the same setting, and never an `INTERNAL_ERROR` or a
   `FEATURE_NOT_ENABLED` (4.3, lines 3 to 5).
   - (a) Record it on the control's word, and name each such cell in the pull request's
     description (5.2, command 3). **Recommended, and the default:** a sync is not stopped by
     a new name, and the census's reason quotes what Spark says.
   - (b) Fail the suite on a condition outside a committed list, until someone adds it.
   Whether an analysis that succeeds through an inserted cast counts as Spark taking the
   type is not a question: `VARKA-660.md` 4.5 decided it, yes (4.3, line 1).

## 14. Outcome

To be written when the row closes. Each part adds a dated paragraph here (5.0).
