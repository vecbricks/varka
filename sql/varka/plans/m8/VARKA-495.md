# VARKA-495: One home for the coverage table's columns, and an unreadable row fails

## 0. What exists now (admission check)

From master at `5c660201b35`, read on 1 November 2026 with `git`. Nothing was run.

1. **The one home exists already.** `coverage.json` has carried a `columns` map, name to SQL type,
   since `32b25d4a9d2` (15 September 2026). `VarkaCoverageSuite` writes it from its own
   `columns` list (`VarkaCoverageSuite.scala:1723-1725`, "Each column's type as SQL, which the
   readers parse back"). It holds the 28 columns from `d` to `d18b`.
2. **A reader exists already.** `VarkaCoverageRows.read` parses the map back into
   `AttributeReference`s through `CatalystSqlParser.parseDataType`. Three suites use it:
   `VarkaCoverageCompositionFuzzSuite`, `VarkaGroupingBoundSuite` and `VarkaDivisorProofSuite`.
   `VarkaSparkFuzz` in sql/core uses its row filters.
3. **The two copies the row names.** `VarkaEmittedBytesSuite.scala:75-116` and
   `VarkaWidthAuditShapes.scala:71-109` each declare the same 28 attributes with the comment
   "as `VarkaCoverageSuite` declares them", in the same order.
4. **What a skipped row is today.** `VarkaEmittedBytesSuite.coverage` (`:228-264`) parses and
   resolves each row's executable SQL against its own columns. A row that fails to resolve, which
   is what a column missing from the copy causes, goes into `coverage_rows_skipped` with no
   failure. The committed `emitted_bytes.json` has `"coverage_rows_skipped" : [ ]`.
   `ADDING_A_TYPE.md` step 11 (`:136-142`) tells a new type's author to add the column in five
   places, the two copies among them.
5. **Order matters, and the reader may lose it.** The bytes oracle compiles each row against
   the column list, so a column's position is an input ordinal and part of the emitted bytes.
   `VarkaCoverageRows.read` takes the map through json4s's `extract[Map[String, String]].toSeq`.
   A Scala immutable `Map` of more than four entries is a hash map, whose order is not the
   file's (**[I]**: json4s's `Extraction` for `Map` was not read; the plan does not rely on it
   either way, see 2.1). If it is lost, the three readers of item 2 have been compiling against
   another order than the coverage suite's. Their outputs are not pinned to the coverage suite's
   bytes, so nothing would have shown it.

### What differs from the row text

- "`VarkaCoverageSuite` now writes from its own list" and the reader are both there (items 1 and
  2). The row's work is the two copies, the failure, and the reader's order.
- The differential and Spark fuzz fixtures (sql/core) build data tables, not attribute lists,
  and derive some columns from others (`ADDING_A_TYPE.md` step 11). They are not copies of the
  list, and the done-when does not name them (section 5).

## 1. Goal and non-goal

**Goal.** `VarkaEmittedBytesSuite` and `VarkaWidthAuditShapes` read their columns from
`coverage.json` through `VarkaCoverageRows`, in the file's order. A coverage row the bytes oracle
cannot resolve fails the suite, naming the row and the identifier it could not resolve.
`emitted_bytes.json` and `width_audit.json` do not change.

**Non-goal.** No change to the coverage table's rows or to the sql/core fixtures.

## 2. Design

### 2.1 The reader keeps the file's order

`VarkaCoverageRows.read` builds the columns by walking the `columns` value as a `JObject`, field
by field, so the order is the file's whatever json4s does with a `Map`. A new method,
`VarkaCoverageRows.columns(path)`, reads only the columns, for the two suites that need nothing
else. Its Scaladoc says the order is the input ordinals'.

### 2.2 The copies go

Both suites take `VarkaCoverageRows.columns(getWorkspaceFilePath("sql", "varka",
"coverage.json"))`. Neither suite reads an attribute outside its `columns` list (read with
`grep`), so the 28 `val`s go with the list.

### 2.3 A row that does not resolve fails

In `VarkaEmittedBytesSuite.coverage`, a row whose parse or resolve throws fails the suite. The
message gives the row's SQL and, for an unresolved attribute, its name: "coverage row `<sql>`
reads `<name>`, which is not a column of coverage.json". It no longer goes to the skipped list.
`coverage_rows_skipped` stays in the rendered file as an empty list, so `emitted_bytes.json` is
byte-identical. The regeneration's comparison of the committed and rendered skip lists stays,
and can no longer move.

### 2.4 The guide

`ADDING_A_TYPE.md` step 11 drops the two copies from its five places: the coverage suite's
`columns` and the two fixtures remain.

## 3. Tests and proofs

1. `VarkaEmittedBytesSuite` and `VarkaWidthAuditSuite` pass with the committed
   `emitted_bytes.json` and `width_audit.json` unchanged. They compare the rendered files with
   the committed ones, so this is the order check of 2.1 too.
2. A test of `VarkaCoverageRows.columns` over a small JSON fixture of more than four columns,
   asserting the file's order.
3. A planted case: a fixture table with a row that reads a column the fixture's map lacks must
   fail the bytes suite's reader with the row and the name (row 504's convention, plan on branch
   `varka-m8-triage`).
4. The composition fuzzer, the grouping bound and the divisor proof suite at their committed
   seeds, which check what 2.1 changes for them (P2).

## 4. Predictions, registered before the build

- P1: `emitted_bytes.json` and `width_audit.json` are unchanged.
- P2: if item 5's loss of order is real, the three suites of item 2 see a new column order. Their
  drawn shapes per seed change, but their verdicts do not: they compare kernels with a
  reference over the same columns. A seed replayed from an older log may no longer reproduce,
  which the pull request says.

## 5. Order, files and size

| File | Change |
|---|---|
| `VarkaCoverageRows.scala` | the ordered reader, `columns` |
| `VarkaEmittedBytesSuite.scala` | the columns from the file; a row that does not resolve fails |
| `VarkaWidthAuditShapes.scala` | the columns from the file |
| `sql/varka/ADDING_A_TYPE.md` | step 11 |

Order: the reader and its test; the two suites; the guide.

Size: small, as the row says.

Out of this row: the sql/core fixtures of `VarkaCoverageDifferentialSuite` and `VarkaSparkFuzz`.
A check that their tables hold every column of the map would be a cheap follow-up (question 1).

## 6. Questions for the owner

1. **The fixtures.** Should the two sql/core fixtures also assert that their tables have a column
   of each name and type in `coverage.json`'s map, so that a new type that skips them fails there
   rather than at a fuzz draw? Recommended: yes, in this row. It is one assertion each and closes
   the last two of step 11's five places.

## 7. Done when

- neither copy exists, and both suites read the columns from `coverage.json` in its order;
- a coverage row that does not resolve fails `VarkaEmittedBytesSuite`, naming the column;
- `emitted_bytes.json` is unchanged.

## 8. Outcome

## Build sheet (11 November 2026)

Follow `BUILD_SHEETS.md` (same folder). Everything below was read at origin/master `c62f492fc89`.
`<scala>` is `sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka`,
`<core>` is `sql/core/src/test/scala/org/apache/spark/sql/execution`.

**What is stale above.** The table now has **35** columns, not 28 (`d3810` is the last), in the
same order in `coverage.json` and in both copies. Line numbers have moved; use the symbols below.
`VarkaWarmupReach.coverageShapes` (Java) reads the columns from `coverage.json` already, through
Jackson, in order: it is not a copy. And `dev/varka_new_lane.py` reads the width audit's copy as
one of a type's sites, so removing that copy edits the script (B1 item 6).

**Question 1 is answered yes** - "the plan's recommendation, taken by the lead on 11 November
2026": the two sql/core fixtures assert their columns, in this row. The assertion differs per
fixture (B1 item 5), because `VarkaSparkFuzz` builds only the columns its draws read, by design.

### B0. Premise re-check

| Command | Expected |
|---|---|
| `grep -c 'AttributeReference("' <scala>/VarkaEmittedBytesSuite.scala <scala>/VarkaWidthAuditShapes.scala` | `35` for each |
| `grep -n 'extract\[Map\[String, String\]\]' <scala>/VarkaCoverageRows.scala` | one line, in `read` |
| `grep -n 'private lazy val coverage: (Seq\[CoverageShape\], Seq\[String\])' <scala>/VarkaEmittedBytesSuite.scala` | one line |
| `grep -n 'val (rows, _) = coverage\|val (rows, skipped) = coverage' <scala>/VarkaEmittedBytesSuite.scala` | seven lines, one `(rows, skipped)` |
| `grep -n '"coverage_rows_skipped" : \[ \]' sql/varka/emitted_bytes.json` | one line |
| `grep -n 'WIDTH_AUDIT' dev/varka_new_lane.py` | three lines |
| `grep -n 'needs one in five places' sql/varka/ADDING_A_TYPE.md` | one line, in step 11 |
| `python3 -c "import json;print(len(json.load(open('sql/varka/coverage.json'))['columns']))"` | `35` |
| `grep -c inSetAsClaimed <scala>/VarkaEmittedBytesSuite.scala` | `0`; `2` means row 567 merged, and B8's last point applies |

### B1. Scope

1. **`<scala>/VarkaCoverageRows.scala`**: a new method directly above `read`, which calls it:

   ```scala
   /**
    * The table's columns, in the file's order, which is the order `VarkaCoverageSuite` declares
    * them in and so the kernels' input ordinals: the object's fields are walked as written,
    * whatever a `Map` would do with them.
    */
   def columns(path: Path): Seq[Attribute] =
     parse(new String(Files.readAllBytes(path), "UTF-8")) \ "columns" match {
       case JObject(fields) => fields.map {
         case (name, JString(t)) => AttributeReference(name, CatalystSqlParser.parseDataType(t))()
         case (name, other) => throw new IllegalArgumentException(
           s"column $name of $path has no SQL type: $other")
       }
       case other => throw new IllegalArgumentException(s"$path has no columns object: $other")
     }
   ```
   Import `JObject` beside `JString`. In `read`, `val cols = columns(path)` replaces the
   `extract[Map[String, String]]` lines, and the result is `Table(cols, projections, predicates)`.
2. **`<scala>/VarkaEmittedBytesSuite.scala`**:
   - Delete the 35 `AttributeReference` vals, their comments and the `columns` list. In their
     place: `private lazy val columns: Seq[Attribute] =
     VarkaCoverageRows.columns(getWorkspaceFilePath("sql", "varka", "coverage.json"))` with the
     comment "The coverage table's columns, from the file `VarkaCoverageSuite` writes them to."
     Remove the imports the deletion leaves unused (the compiler's unused-import warnings name
     them; `DecimalType` and the others the DECIMAL arithmetic test still uses stay).
   - `coverage` becomes `private lazy val coverage: Seq[CoverageShape] =
     coverageOf(getWorkspaceFilePath("sql", "varka", "coverage.json").toFile)`, and the body moves
     to `private def coverageOf(file: File): Seq[CoverageShape]`, which reads its own columns,
     `val cols = VarkaCoverageRows.columns(file.toPath)`, and uses `cols` for the resolve and the
     compile. The
     parse guard becomes:
     `val resolved = try VarkaSqlResolve.resolve(CatalystSqlParser.parseExpression(executable),
     cols) catch { case e: Exception => fail(s"coverage row `$sql` does not resolve against the
     columns of ${file.getName}: ${e.getMessage}", e) }` (the resolver's message names the column:
     "unknown column <name>"). Then `val claimed = asCatalystClaims(resolved, catalyst)` (no `Option` is left), and
     `val compiled = if (form == "predicate") VarkaExpressionCompiler.compilePredicate(claimed,
     cols, VarkaEmitOptions.DEFAULTS, VarkaAdmission.NO_HOST_REFUSAL).toScala.map(_.fused) else
     VarkaExpressionCompiler.compile(Seq(Alias(claimed, "c")()), cols).toScala`; its match keeps
     `case Some(c)` and the decline `fail` as `case None`. `skipped` and its builder go. Update the
     scaladoc: a row that does not parse or resolve now fails, naming the row and the column.
   - All seven `val (rows, _) = coverage` and `val (rows, skipped) = coverage` become
     `val rows = coverage`;
     `"coverage_rows_skipped" -> skipped.asJava` becomes
     `"coverage_rows_skipped" -> java.util.List.of[String]()`, so the file keeps its empty list and
     is byte-identical. The regeneration's skip comparison stays.
   - A new test after "sql/varka/emitted_bytes.json pins ...": "a coverage row over a column the
     table lacks fails the oracle, naming the row and the column". In `withTempDir`, write
     `cov.json` holding `{"columns": {"i": "int", "l": "bigint"}, "expressions": [{"sql": "i + 1",
     "executable": "i + 1", "form": "projection", "catalyst": ["Add"]}, {"sql": "q + 1",
     "executable": "q + 1", "form": "projection", "catalyst": ["Add"]}]}`; then
     `val e = intercept[TestFailedException](coverageOf(file))` and assert `e.getMessage` contains
     "`q + 1`" and "unknown column q".
3. **`<scala>/VarkaWidthAuditShapes.scala`**: delete the 35 vals, their comments and the
   `columns` list. `coverageRows(coverageJson)` starts with
   `val columns = VarkaCoverageRows.columns(coverageJson.toPath)`, and `predicateRow` takes it as a
   third parameter `columns: Seq[Attribute]` (its one caller passes it). Remove the imports left
   unused.
4. **`<scala>/VarkaCoverageSuite.scala`**: a new test, "coverage.json's columns read back in the
   order and at the types this suite declares them": `assert(VarkaCoverageRows.columns(
   getWorkspaceFilePath("sql", "varka", "coverage.json")).map(a => a.name -> a.dataType) ===
   columns.map(a => a.name -> a.dataType))`, `columns` being the suite's own list.
5. **The two sql/core fixtures** (question 1):
   - `<core>/VarkaCoverageDifferentialSuite.scala`, a test after "the coverage table has rows to
     run": "every fixture holds every column of coverage.json, at its type". For each name of
     `fixtures.map(_._1) :+ extremes._1`, take `spark.table(name).schema` and assert, for every
     attribute `a` of `VarkaCoverageRows.columns(getWorkspaceFilePath("sql", "varka",
     "coverage.json"))`, `schema.find(_.name == a.name).map(_.dataType) === Some(a.dataType)`,
     with the clue `s"$name.${a.name}"`.
   - `<core>/VarkaSparkFuzzSuite.scala`, a test after "the fixture builds for every drawn case,
     ...": "the fixture holds every column of coverage.json its draws read, at its type". The
     wanted columns are those of `VarkaCoverageRows.columns(...)` whose name, passed as the
     argument, neither `VarkaCoverageRows.readsBoolean`, `readsNarrow` nor `readsDecimal` matches (the rows the
     fuzzer draws, `VarkaSparkFuzz.coverageRows`, are filtered by the same three); the schema is
     `spark.sql(VarkaSparkFuzz.fixtureSql(VarkaSparkFuzz.drawData(new Random(495), 1, safe =
     true))).schema`; the assertion is the one above. Eighteen columns are wanted
     (`d d2 i ymm ymy ym l l2 t t2 dt dt2 ntz ntz2 x x2 f f2`); assert that count too.
6. **`dev/varka_new_lane.py`**: delete the `WIDTH_AUDIT` constant, `audit = column_types(...)`
   and the `"width audit"` entry of `type_sites`; in the module docstring, "a coverage column, a
   width-audit column and a census entry" becomes "a coverage column and a census entry". The width audit reads its columns from
   `coverage.json`, which the "coverage table" site already checks.
7. **`sql/varka/ADDING_A_TYPE.md` step 11**: replace from "A type with no column in the table
   yet needs one in five places:" up to "...as the `TIMESTAMP_NTZ` columns are derived from a date
   and a time." with: "A type with no column in the table yet needs one in three places:
   `VarkaCoverageSuite`'s `columns`, which `coverage.json`'s column map is written from and every
   catalyst suite reads its columns from (`VarkaCoverageRows.columns`); and the fixtures of
   `VarkaCoverageDifferentialSuite` and `VarkaSparkFuzz`, which may derive the column from
   existing ones, as the `TIMESTAMP_NTZ` columns are derived from a date and a time. Each fixture's
   suite fails on a column of the map it lacks, the fuzz fixture's only for a type its draws read."
8. **`PLAN.md` row 495**: replace the row's opening `**Planned**` with `**Done**`. Fill section 8,
   "Outcome", of this file.

**Out of scope:** the coverage rows; `VarkaWarmupReach` (it already reads the file); the
`asCatalystClaims` copies (row 567, and the width audit's, which 567 leaves to a new row); any
generated file; `dev/varka_precommit.sh`'s `lane_inputs`, which may keep naming
`VarkaWidthAuditShapes.scala`.

### B2. Tests and their oracles

| Test | Asserts | Oracle |
|---|---|---|
| `VarkaEmittedBytesSuite` (all) | the bytes are unchanged | the committed `emitted_bytes.json`; its ordinals follow the column order, so this is also the order check |
| `VarkaWidthAuditSuite` (all) | the audit is unchanged | the committed `width_audit.json` (the laptop is its host) |
| the new bytes-suite test | an unresolvable row fails, naming row and column | the fixture's map, which lacks `q` |
| the new coverage-suite test | the file's columns read back in order and type | `VarkaCoverageSuite`'s own `columns` list, which wrote them |
| the two fixture tests | each fixture has each wanted column at its type | `coverage.json`'s map |
| `VarkaCoverageCompositionFuzzSuite`, `VarkaGroupingBoundSuite`, `VarkaDivisorProofSuite` | pass at their committed seeds | their own oracles (prediction P2 above) |

### B3. Planted faults

1. In `VarkaCoverageRows.columns`, return `fields.sortBy(_._1).map(...)`: the new coverage-suite
   test fails, and `VarkaEmittedBytesSuite` fails on moved bytes.
2. In `coverageOf`'s resolve guard, replace the `fail(...)` message by `"x"`: the new bytes-suite
   test fails on its message assertions.
3. In the differential fixture's `fixtureView`, rename `AS d8` to `AS d8x`: the new differential
   test fails naming `d8`. In the fuzz fixture, rename `AS ntz2` to `AS ntz3`: the new fuzz test
   fails naming `ntz2`.
Revert each before the next.

### B4. Commands, in order

```
flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaCoverageSuite -- -z "read back in the order"'
flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaEmittedBytesSuite *VarkaWidthAuditSuite'
flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaCoverageCompositionFuzzSuite *VarkaGroupingBoundSuite *VarkaDivisorProofSuite'
flock "$VARKA_HEAVY_LOCK" build/sbt 'sql/testOnly *VarkaCoverageDifferentialSuite *VarkaSparkFuzzSuite'
git diff --exit-code -- sql/varka/emitted_bytes.json sql/varka/width_audit.json sql/varka/coverage.json
./dev/varka_new_lane.py --check     # every line "no site is missing", a type's "(2 checked)"
```

All pass; the `git diff` exits 0. Then the planted faults, then `BUILD_SHEETS.md` section 6.

### B5. Done when

`grep -c 'AttributeReference("' <scala>/VarkaEmittedBytesSuite.scala
<scala>/VarkaWidthAuditShapes.scala` prints `0` for each; `grep -c skipped
<scala>/VarkaEmittedBytesSuite.scala` counts only the render and the regeneration's comparison;
B4 passes; `python3 dev/varka_push_check.py` exits 0.

### B6. Size

About 250 counted lines, most of them the removed copies (about 95 lines each): the reader 15,
the bytes suite 30 added, the audit 10, four tests 50, the script 8, the guide 10, the plan and
outcome 25.

### B7. Stop rules

- `emitted_bytes.json` or `width_audit.json` differs after the change: the order or the columns
  moved; stop, do not regenerate.
- A coverage row fails the new resolve guard on the committed table.
- A fixture test fails on the unchanged fixtures: report the fixture, the column and the two
  types; do not change a fixture.
- One of the three reader suites fails at its committed seed (P2 said the verdicts hold).
- `VarkaWidthAuditSuite` reports another host: the run is not on the laptop; stop.

### B8. Decisions already taken

- Question 1, yes: "the plan's recommendation, taken by the lead on 11 November 2026".
- The fuzz fixture's assertion covers only the columns its draws read, as B1 item 5 says.
- Row 567 lands first and also edits `VarkaEmittedBytesSuite.coverage`. If it has merged when
  you start, master's claim line reads `parsed.map(VarkaSqlResolve.inSetAsClaimed(_,
  catalyst.contains("InSet")))` instead of `asCatalystClaims`: apply B1 item 2 to that line the
  same way (`VarkaSqlResolve.inSetAsClaimed(resolved, catalyst.contains("InSet"))`).

### B9. Afterwards (the lead)

For the agent: the commit subject is `[VARKA-495] Read the coverage table's columns from coverage.json`.
Score section 4's P1 and P2 in section 8.

Review and push. Open pull requests on the same files: #1428 (`VarkaEmittedBytesSuite.scala`,
the option arms), #1478 and #1484 (`VarkaWidthAuditShapes.scala`: `coverageRows` and
`predicateRow`'s compile calls; on a conflict keep their compile calls with this row's local
`columns`), and #1428, #1478, #1484 (`PLAN.md`): rebase if one merged first.
