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
