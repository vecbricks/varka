# VARKA-602: The nightly composition fuzzer accounts for every coverage row

Row 602 of `PLAN.md`, added on 11 November 2026 on the owner's word from a reading of
`varka-fuzz.yml` against `coverage.json`. The row's text is the plan; this file holds its build
sheet.

## Build sheet (11 November 2026)

For a coding agent building the row in one pull request, stopping at a local commit. Follow
`sql/varka/plans/m8/BUILD_SHEETS.md` for what every sheet shares: the worktree, the heavy lock,
the environment, the commit format, the linters and the rule that the agent never pushes. Where
this sheet and that file disagree, stop and report. Everything below was read at `c62f492fc89`
(origin/master on 11 November 2026).

Branch and worktree: `varka-602` in `../varka-602`, made as BUILD_SHEETS.md section 1 item 2
says. Commit subject: `[VARKA-602] Count the coverage rows the composition fuzzer leaves out`.

**Stale fact in the row, corrected here.** The row counts 205 of 686 rows left out; on
`c62f492fc89` the table has 700 rows and 205 are left out (39 over a `BOOLEAN` column, 106 over
a `TINYINT` or `SMALLINT` one, 60 over a `DECIMAL` one; no row matches two filters). Nothing in
this sheet hard-codes a count: the test asserts an identity and prints the counts.

Paths: `S` below is
`sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaCoverageCompositionFuzzSuite.scala`
and `R` is `VarkaCoverageRows.scala` in the same directory.

### B0. Premise re-check

Run each from the worktree root; any other output: stop and report (B7).

1. Open work on the same files: `gh pr view 1425 --repo vecbricks/varka --json state --jq .state`
   prints `OPEN` on 11 November. #1425 edits `S` (it adds `compiledRowKernels`, `runCompiled` and
   a decimal-slot test around the compiled test, lines 860 to 1060) and `varka-fuzz.yml` (a
   `proofs` job and the `report-failure` job's `needs`, both below the composition step). The
   lead's local branch `varka-warmup-review` edits `S` too. Neither touches the lines this sheet
   edits. Rebase if #1425 or `varka-warmup-review` merged first (BUILD_SHEETS.md says how); if
   the rebase conflicts in the lines B1 names, stop and report.
2. `grep -n -E 'def readsBoolean|def readsNarrow|def readsDecimal|case class Table' "$R"` (with
   `R=sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaCoverageRows.scala`)
   prints four lines, in that order; `grep -c 'VARKA-352' "$R"` prints `2` (the narrow and
   decimal docs name it; the boolean doc does not).
3. `grep -n -A2 'private def readsOtherColumns(executable: String): Boolean =' "$S"` prints:

   ```
   private def readsOtherColumns(executable: String): Boolean =
     VarkaCoverageRows.readsBoolean(executable) || VarkaCoverageRows.readsNarrow(executable) ||
       VarkaCoverageRows.readsDecimal(executable)
   ```

   and `grep -c 'private lazy val unbakedAlone: Set\[String\] =' "$S"` prints `1`.
4. `grep -n 'test("random projections of coverage rows are fused or declined in bytes, never thrown")' "$S"`
   prints one line (the anchor for the new test), and
   `grep -n '^import org.apache.spark.sql.types.{DataType, DayTimeIntervalType, DoubleType, FloatType, TimeType}$' "$S"`
   prints one line.
5. The callers of the three predicates:
   `git grep -l -E 'VarkaCoverageRows\.reads(Boolean|Narrow|Decimal)' -- '*.scala' '*.java'`
   prints three paths: `S`, `.../varka/VarkaGroupingBoundSuite.scala` and
   `sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaSparkFuzz.scala`. The last two
   stay as they are.
6. The summary step: `grep -n -F 'grep -E "Tests: succeeded|FAILED" "target/fuzz/compositions-$seed.log" || true' .github/workflows/varka-fuzz.yml`
   prints one line (step "Run the coverage composition fuzzer"), and
   `git grep -n -F 'compositions-' -- dev .github` prints only the two lines of that step (no
   other reader of the log).
7. The counts, from the table and independent of the suite:

   ```
   python3 -c 'import json,re; j=json.load(open("sql/varka/coverage.json")); e=[r.get("executable") or r["sql"] for r in j["expressions"]]; f=[r"\bb2?\b", r"\b(y1|y2|s1|s2)\b", r"\b(d9|d9b|d18|d18b|d9s4|d16s9|d8|d19|d38|d38b|d3810)\b"]; first=[next((k for k,p in enumerate(f) if re.search(p,x)),None) for x in e]; print(len(e), sum(c is not None for c in first), *[first.count(k) for k in range(3)], sum(sum(bool(re.search(p,x)) for p in f) > 1 for x in e))'
   ```

   prints `700 205 39 106 60 0` at `c62f492fc89` (the last number: rows matching two filters). If master has moved, these numbers may differ;
   write down what it prints: B4 compares the test's output with it.
8. `git branch --list 'varka-602*'` prints nothing, and `test -f sql/varka/plans/m8/VARKA-602.md`
   and `test -f sql/varka/plans/m8/BUILD_SHEETS.md` succeed in the new worktree (this sheet reached master with its plan pull request; cut the
   worktree from an origin/master that holds it).

### B1. Scope

**Decision: the owing row is a field of the filter** (not a map in the suite), so a filter
cannot be added without saying who owes its rows. The three predicates stay, since
`VarkaSparkFuzz` and `VarkaGroupingBoundSuite` call them.

**`R` (`VarkaCoverageRows.scala`)**: directly after `def readsDecimal(...)` and its blank line,
before `case class Table(...)`, add the block below followed by one blank line:

```scala
  /**
   * A filter the composition fuzzer applies to the coverage rows: `name` says what a row it leaves
   * out reads, `owedBy` the plan row whose work draws such rows (`None` when no row owes it, which
   * the fuzzer's accounting test fails on), and `reads` whether an executable row is left out.
   */
  case class Filter(name: String, owedBy: Option[String], reads: String => Boolean)

  /** The composition fuzzer's filters, in the order a row left out is counted against them. */
  val compositionFilters: Seq[Filter] = Seq(
    Filter("BOOLEAN", Some("VARKA-352"), readsBoolean),
    Filter("TINYINT or SMALLINT", Some("VARKA-352"), readsNarrow),
    Filter("DECIMAL", Some("VARKA-352"), readsDecimal))
```

Nothing else in `R` changes.

**`S` (`VarkaCoverageCompositionFuzzSuite.scala`)**:

- The import `org.apache.spark.sql.types.{DataType, DayTimeIntervalType, DoubleType, FloatType, TimeType}`
  becomes
  `org.apache.spark.sql.types.{BooleanType, ByteType, DataType, DayTimeIntervalType, DecimalType, DoubleType, FloatType, ShortType, TimeType}`
  (over 100 columns, which is allowed for an import: `scalastyle-config.xml`'s
  `FileLineLengthChecker` has `ignoreImports` true, and `dev/varka_precommit.sh` exempts import
  lines; the import line it replaces is already over 100). The fully qualified names in `private def columns`
  stay as they are.
- The body of `readsOtherColumns` (the two lines after its `=`) becomes the one line
  `    VarkaCoverageRows.compositionFilters.exists(_.reads(executable))`. The comment above it
  stays. The rows drawn, and so every seed's draws, are unchanged: the same three predicates in
  an `||`.
- Directly after the `unbakedAlone` block (its last line is `      }.toSet`) and its blank line,
  before `private def resolve(sql: String)`, add the block below followed by one blank line:

  ```scala
    /**
     * The rows `filters` leave out by a filter that names no row owing them, counted by the
     * filter's name; each row is counted against the first filter that reads it.
     */
    private def unowed(rows: Seq[VarkaCoverageRows.Row],
        filters: Seq[VarkaCoverageRows.Filter]): Map[String, Int] =
      rows.flatMap(r => filters.find(_.reads(r.executable))).filter(_.owedBy.isEmpty)
        .groupBy(_.name).map { case (name, hits) => name -> hits.size }
  ```

- Directly before
  `test("random projections of coverage rows are fused or declined in bytes, never thrown") {`,
  add this test and a blank line after it:

  ```scala
    test("every coverage row is drawn or left out by a filter that names the row owing it") {
      val rows = table.projections ++ table.predicates
      val filters = VarkaCoverageRows.compositionFilters
      // The oracle is the types of the columns a row's resolved expression reads, not the filters'
      // regular expressions over its text.
      val types = Map[String, DataType => Boolean](
        "BOOLEAN" -> ((t: DataType) => t == BooleanType),
        "TINYINT or SMALLINT" -> ((t: DataType) => t == ByteType || t == ShortType),
        "DECIMAL" -> ((t: DataType) => t.isInstanceOf[DecimalType]))
      assert(filters.map(_.name).toSet == types.keySet)
      var untyped = 0
      for (row <- rows) {
        val read = VarkaCoverageRows.resolve(row.executable, table.columns).references.toSeq
          .map(_.dataType)
        val byType = types.collect { case (name, is) if read.exists(is) => name }.toSet
        val byFilter = filters.filter(_.reads(row.executable)).map(_.name).toSet
        assert(byFilter == byType, s"${row.executable}: filters $byFilter, column types $byType")
        if (byType.isEmpty) untyped += 1
      }
      val leftOut = rows.groupBy(r => filters.find(_.reads(r.executable)).map(_.name))
      val filtered = rows.size - leftOut.getOrElse(None, Nil).size
      val drawn = projections.size + predicates.size
      val alone = rows.count(r => !readsOtherColumns(r.executable) && unbakedAlone(r.executable))
      // The pools the tests draw from against the oracle: every row of no filtered type, less
      // those this configuration declines alone.
      assert(drawn + alone == untyped, s"$drawn drawn + $alone alone, $untyped of no filtered type")
      info(s"coverage accounting: ${rows.size} rows, $drawn drawn, $filtered left out by a filter, " +
        s"$alone declined alone by this configuration")
      for (f <- filters) {
        val n = leftOut.getOrElse(Some(f.name), Nil).size
        info(s"coverage accounting: left out, ${f.name}: $n rows, owed by " +
          f.owedBy.getOrElse("no row"))
      }
      assert(unowed(rows, filters).isEmpty,
        s"rows left out by a filter that names no owing row: ${unowed(rows, filters)}")
      // The check above bites: a filter that owes no row and reads every row is reported for all.
      assert(unowed(rows, Seq(VarkaCoverageRows.Filter("planted", None, _ => true))) ==
        Map("planted" -> rows.size))
    }
  ```

  (`table.columns` here, not the suite's `columns`, which drops the boolean and narrow columns
  and so cannot resolve the rows that read them.)

**`.github/workflows/varka-fuzz.yml`**, step "Run the coverage composition fuzzer": the line
`grep -E "Tests: succeeded|FAILED" "target/fuzz/compositions-$seed.log" || true` becomes the two
lines

```bash
          grep -E "Tests: succeeded|FAILED|coverage accounting:" \
            "target/fuzz/compositions-$seed.log" || true
```

so the four accounting lines reach the nightly's step summary.

**The plan's record** (in this commit): in `PLAN.md` row 602 replace the opening `**Planned** (`
with `**Done** (built on varka-602: the composition fuzzer's filters name
the row owing their rows, a test counts drawn, filtered and declined-alone rows against the
column types and fails on an unowed filter, and the nightly summary prints the counts; ` and
leave the rest of the row as it is.

**OUT OF SCOPE**:

- Dropping any filter or drawing those rows: row 352's.
- The same filters in `VarkaSparkFuzz.scala` (sql/core) and `VarkaGroupingBoundSuite.scala`.
- `sql/varka/fuzz/types.tsv` and `VarkaFuzzTypes.java`: their `DECIMAL` Spark-level site says
  `owed:347`; that is the Spark-level fuzzer's site, not this one. Leave it.
- The compiled test's `oneTable` filter (rows whose kernel reads both literal tables): those
  rows are still drawn by the composition tests, and that test already prints
  `<n> of <m> coverage rows' kernels`.
- The suite's `columns` filter (a column list, not rows).
- Any other step or job of `varka-fuzz.yml`, and #1425's `proofs` job.

### B2. Tests

The new test asserts, in order: (1) the filters' names are the oracle's three type groups; (2)
for every row the filters that read it are exactly the type groups of the columns its resolved
expression references (the oracle: Catalyst's analysis against `table.columns`, which shares
nothing with the regexes); (3) drawn plus declined alone is the oracle's count of rows reading no filtered type; (4) no row
is left out by a filter with `owedBy == None`; (5) `unowed` reports every row for a planted
unowed filter, so (4) is not vacuous. Its four `coverage accounting:` lines are the summary.

### B3. Planted fault

1. In `R`, change the `BOOLEAN` filter's `Some("VARKA-352")` to `None`. Run B4 step 2. Expected:
   the new test fails with
   `rows left out by a filter that names no owing row: Map(BOOLEAN -> 39)` (39 at
   `c62f492fc89`; the number is B0 check 7's third). Revert.
2. In `R`, change `booleanColumn`'s regex `"\\bb2?\\b"` to `"\\bb\\b"` (it then misses `b2`).
   Run B4 step 2. Expected: the new test fails with
   `NOT b2: filters Set(), column types Set(BOOLEAN)` or the same for
   `CAST(x AS BOOLEAN) = b2` (the two rows that read `b2` alone). Other tests of the suite may
   fail too, since those rows are then drawn; only the new test's message is the check. Revert.

### B4. Commands, in order

1. `flock "$VARKA_HEAVY_LOCK" build/sbt catalyst/Test/compile` -> `[success]`.
2. `mkdir -p target && flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaCoverageCompositionFuzzSuite -- -z "every coverage row is drawn or left out"' 2>&1 | tee target/varka-602.log`
   -> `All tests passed.` The suite is `VarkaOwnJvm`: its child JVM runs the whole suite whatever
   `-z` names (about two and a half minutes on CI, `sql/varka/ci/suite-times.tsv`), and copies
   its output to sbt's.
3. `grep -E "Tests: succeeded|FAILED|coverage accounting:" target/varka-602.log` (the workflow's
   new pattern) -> the four lines, at `c62f492fc89`:

   ```
   coverage accounting: 700 rows, 495 drawn, 205 left out by a filter, 0 declined alone by this configuration
   coverage accounting: left out, BOOLEAN: 39 rows, owed by VARKA-352
   coverage accounting: left out, TINYINT or SMALLINT: 106 rows, owed by VARKA-352
   coverage accounting: left out, DECIMAL: 60 rows, owed by VARKA-352
   ```

   (each after sbt's `[info]` prefix and ScalaTest's `+ `), plus a `Tests: succeeded` line. The
   numbers must equal B0 check 7's: rows, left out, and the three per-filter counts; drawn is
   rows minus left out at the defaults, where nothing is declined alone.
4. The whole suite at the defaults:
   `flock "$VARKA_HEAVY_LOCK" dev/varka_matrix.sh --defaults --out target/varka-602 -j 2 --module catalyst --suites VarkaCoverageCompositionFuzzSuite,VarkaGroupingBoundSuite`
   -> exit status `0`.
5. `actionlint -oneline .github/workflows/varka-fuzz.yml | wc -l` -> `1` (the pre-existing
   SC2086 note at `c62f492fc89`; the edit adds none).
6. B3, both steps. Revert each by undoing its one edit by hand (never `git checkout` the file,
   which would drop B1's work), then re-run B4 step 2: passes.
7. The plan record of B1 and the Outcome section BUILD_SHEETS.md section 7 asks for.
8. BUILD_SHEETS.md section 6, items 1 to 6 in its order, on that final tree (`dev/lint-scala`
   under `flock` for the Scala; no Java or Python changes). Its last item,
   `python3 dev/varka_push_check.py`, must exit `0`. Then the commit.

### B5. Done when

B4 step 2 passes, B4 step 3 prints four `coverage accounting:` lines whose numbers equal B0
check 7's, B4 steps 4, 5 and the push check pass, and `git diff origin/master --stat` lists
exactly `R`, `S`, `.github/workflows/varka-fuzz.yml`, `sql/varka/plans/m8/PLAN.md` and
`sql/varka/plans/m8/VARKA-602.md` (its Outcome section).

### B6. Size

About 75 counted lines: `R` about 15, `S` about 45, the workflow 2, the plan record and the
Outcome section about 10. The
new test adds under a second to the suite (it resolves each row once; `unbakedAlone` is already
computed by the suite's other tests). Far under the 1000-line cap.

### B7. Stop rules

Stop and report, without guessing, when:

- any B0 check prints something other than its expected output;
- B2 assertion 2 fails on master's own rows (the filters and the column types disagree before
  any change of yours): that is a finding for the lead, not something to fix here;
- a row matches two filters (B0 check 7's last number is not `0`);
- `VarkaCoverageRows.resolve` throws for a row;
- the rebase onto #1425 or `varka-warmup-review` conflicts in the lines B1 edits;
- the accounting lines do not appear in B4 step 3's output although the test passed;
- anything asks you to touch an OUT OF SCOPE item.

### B8. Decisions already taken

Taken in this sheet on 11 November 2026, where the row left them open:
- **The owing row is a field**, `Filter.owedBy: Option[String]` (the row offered a field or a map).
- **Three filters, not four**: `TINYINT` and `SMALLINT` share `readsNarrow`, so one filter.
- **All three owed by VARKA-352**: `R` names it for the narrow and decimal predicates,
  `VARKA-352.md` 3 item 6 draws `BOOLEAN`, `TINYINT` and `SMALLINT` here, and the row (the
  owner's word) says so for all four types. `VARKA-352.md` 5 and `types.tsv` owe `DECIMAL`'s
  Spark-level site to 347 instead; that is another fuzzer's site, and the lead may re-point the
  `DECIMAL` filter's `owedBy` to `VARKA-347` in review. Build it as `VARKA-352`.
- **A row matching two filters counts against the first**; none does today.
- **`unbakedAlone` rows are counted apart and owe no row** (a configuration declines them).
- **The summary is the four `info` lines** through the workflow's grep; no new step or file.

### B9. What the owner must do afterwards

Nothing to dispatch. After the merge, the next scheduled nightly's "Coverage compositions" step
summary on vecbricks/varka should show the four `coverage accounting:` lines; if it does not, the
lead reads that step's log.
