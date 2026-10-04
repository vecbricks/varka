# VARKA-81: Spark's own date tests as a differential corpus

*Milestone 5, section 2.11. Opened 7 September 2026 from the owner's question
about how test coverage could be estimated; planned 16 September 2026, while
VARKA-106, VARKA-84 and VARKA-120 waited on CI.*

## 1. Where this came from

Every oracle this project checks itself against, it also wrote: the reference
evaluator behind the fuzzer, the emitter suite's matrices, the differential
suite's fixtures, and now (VARKA-120) the coverage table run through both
engines. They share one weakness: an assumption held by the author of a
lowering is held by the author of its oracle. Spark's own date tests are the one
corpus this project did not write. They encode what the engine is supposed to
do, and they are the only instrument here that can find a misreading of Spark's
semantics rather than a slip in implementing them.

Section 2.11 already settled the shape, and its two negative findings stand:
`DateExpressionsSuite` never reaches a physical plan and is excluded on the
record; subclassing `DateFunctionsSuite` with the Arrow serializer set would run
everything through the row engine and measure nothing, because its 77 frames are
built inline and cached nowhere. What is left is a harvest of the golden-file
inputs, a rewrite that gives their literal expressions data to run over, and a
differential over the result whose verdicts are committed.

## 2. The admission check, done

**The corpus, counted on today's tree.** The date-family inputs under
`sql/core/src/test/resources/sql-tests/inputs/` and their `select` statements:

| file | selects | with `FROM` | what the `FROM` reads |
|---|---:|---:|---|
| `date.sql` | 101 | 7 | `date_view`: one row, two *string* columns |
| `interval.sql` | 285 | 9 | `interval_view`: one row, one string column |
| `extract.sql` | 128 | 117 | `t`: one row of a string, a timestamp, intervals |
| `timestamp.sql` | 138 | 7 | timestamp views |
| `datetime-formatting.sql` | 35 | 31 | a view of timestamps |
| `datetime-parsing.sql` | 32 | 0 | - |
| `datetime-special.sql` | 4 | 0 | - |

The `ansi/` variants of these files no longer exist in this tree; ANSI is the
session default in tests and the suite inherits it. Section 2.11's "254
statements" was an earlier tree's count; the numbers above are this one's, and
the harvest reports its own count each run.

**Why the files carry no data, once more with the numbers.** 94 of `date.sql`'s
101 statements are literal expressions and are constant-folded before a
physical operator exists; the seven that read `date_view` read two string
columns. Varka reads date and int Arrow columns, so under Varka as written the
corpus exercises zero kernels and passes. The rewrite - typed literals into
columns of an Arrow-cached fixture - is not a convenience, it is what makes the
corpus reach a kernel at all.

**What the rewrite has to work on, and how it renders.** A statement parses to
an expression tree in which `date'2019-01-01'` is already a typed
`Literal(DateType)`, `1` a `Literal(IntegerType)`, `INTERVAL '1' YEAR` a
year-month interval literal, and `'2011-11-11'` a `Literal(StringType)`. The
rewrite replaces each literal of a lane type Varka reads today - `DateType`,
`IntegerType`, `YearMonthIntervalType` - by an attribute `c<k>`, and renders the
tree back to SQL with `Expression.sql`, which prints an unresolved function call
as `name(args)`. Renders that fail to parse again are recorded as such, not
hidden: the corpus lists what it could not rewrite. String literals stay
literals on purpose - `date_sub('2011-11-11', 1)` coerces a string, and a string
column is a shape Varka declines, which is a fact the verdict should show rather
than a fixture should paper over. The `IN` lists, `next_day`'s weekday and
`trunc`'s level are strings or dates: the day name and the level stay literal
(the arms require it); a date `IN` list becomes columns, which the compiler
declines, and that decline is a true statement about the rewritten shape and is
committed as one.

**The fixture per statement.** From the literals a statement gave up, one row
holding their values, one row per literal position with that position null, and
one row with every column null - so a null lane reaches every operand and the
all-null branch is taken - as a `VALUES` view cached in both sessions with the
Arrow serializer, the way VARKA-120's fixtures are built. A handful of rows per
entry, which is why section 2.11 says plainly that this corpus exercises the
epilogue and never a full lane group.

**The verdict, inherited from VARKA-62.** `DateSurfaceBenchmark.classifyPlan`
reads `EXPLAIN` text: `PLAIN` when no line names a Varka node, `PARTIAL` when a
row-engine `Filter` or a non-empty `Project` sits above the first Varka line,
`FUSED` otherwise. The bench module is not on `sql/core`'s test classpath, so
the rule is ported - it is a regex and a loop - and a test pins the port against
the three plan texts the original was written for.

**Errors are entries too.** `date.sql` deliberately carries invalid dates,
`curdate(1)`, a month of 13. On the baseline these raise; under the rewrite,
`make_date(c0, c1, c2)` over the row `(2000, 13, 1)` raises on the row engine
and must raise the same condition through Varka, whose `make_date` declines the
batch to the row engine for exactly that. So an entry's outcome is one of:
*agree* (both engines answer the same rows), *error* (both raise, the same error
condition), or a failure. An entry that raises on one side and answers on the
other is the finding this corpus exists to make.

**The splitter is upstream's.** `SQLQueryTestHelper.splitCommentsAndCodes` and
`getQueries` already separate comments, `--SET` lines and multi-line statements
the way the golden harness does; the suite mixes the trait in rather than
re-deriving the format.

**What is committed.** `sql/varka/golden_corpus.json`, generated by the suite and
byte-compared on `coverage.json`'s precedent (`VARKA_GOLDEN_REGEN=true`): per
entry the source file, the statement as written, the rewritten statement, the
columns it gave up, the verdict and the outcome; per file the counts of fused,
partial, plain, error and skipped. That table of counts, naming which
expressions are out, is the coverage number section 2.11 asked for. A new
upstream statement changes the file on the next regeneration, which is how the
harvest stays re-runnable rather than hand-copied.

**Out of this task, on the record.** The Scala suites section 2.11 named after
the golden files - `DateFunctionsSuite` and the date parts of
`ColumnExpressionSuite` - cannot be harvested re-runnably: their expressions are
Scala code, not text, and a hand-copied list is what row 81 says not to build.
They are left for a decision by the owner: a curated list with its provenance
stated, as its own small task, or nothing. `timestamp.sql` and
`datetime-formatting.sql` are harvested and will report almost entirely `PLAIN`,
since timestamps and formatting are outside Varka today; they stay in the corpus
because the count of what is out is the point, and because milestone 5's TIME
work will move some of them.

## 3. The design

### 3.1 The mechanism

One new suite, `VarkaGoldenCorpusSuite` in `sql/core`, on `VarkaSharedSessions`
and `SQLQueryTestHelper`. In order:

1. **Harvest.** For each file in the table above, read it, split it with the
   upstream helper, keep the `select` statements, and record everything else
   (`create temporary view`, `--SET`) as skipped with its kind. A `select` with
   a `FROM` is recorded as `reads a view of strings` and not rewritten.
2. **Rewrite.** Parse the statement's select items with `CatalystSqlParser`,
   replace each `DateType`, `IntegerType` and `YearMonthIntervalType` literal by
   `c<k>` (the weekday name of `next_day` and the level of `trunc` are strings
   and are untouched), render back with `.sql`, and build the fixture rows from
   the collected literals. A statement with no such literal is recorded as
   `nothing to rewrite` and still run as written, where it will fold and read
   `PLAIN`; the record says why.
3. **Run.** Cache the fixture in both sessions; run the rewritten statement on
   both; classify the Varka plan with the ported rule; compare answers with
   `checkAnswer`, or error conditions when the baseline raises; on a `FUSED` or
   `PARTIAL` plan assert the kernels ran (`numVarkaBatches > 0`), with VARKA-120's exemption when the answer is empty.
4. **Commit.** Render the corpus file and compare it to the committed one; on
   mismatch, fail naming the entries whose verdict or outcome moved and the
   regeneration command.

One test per source file, so a failure names the file, and one test that pins
the classifier's port.

### 3.2 What is deliberately unchanged

The golden files and their `.sql.out`: nothing here edits or re-baselines them,
and their expected values are not this suite's oracle. `VarkaDifferentialSuite`
and `VarkaCoverageDifferentialSuite`: this is a third corpus beside them, not a
replacement. The compiler and emitter: a `PLAIN` verdict is recorded, never
"fixed" here; each is a candidate row for the milestone, filed separately.

### 3.3 Registered op counts

Not applicable.

## 4. Files

* `sql/core/src/test/scala/.../execution/VarkaGoldenCorpusSuite.scala` - new.
* `sql/varka/golden_corpus.json` - new, generated and byte-compared.
* `docs/sql-varka.md` - the suite named in the testing section, and the coverage
  counts referenced from the coverage table's introduction.
* `sql/varka/plans/m5/PLAN.md` - row 81; new rows for whatever the
  corpus finds, filed on the "a finding becomes a row" rule.

## 5. Tests, and what each is for

* **The classifier's port** against three plan texts: no Varka line, a Varka
  line under a row `Filter`, a Varka line with only an empty `Project` above it
  (the aggregate's, not residual).
* **The rewrite on known statements**: `make_date(2019, 1, 1)` becomes
  `make_date(c0, c1, c2)` with three int columns and five rows;
  `date_sub('2011-11-11', 1)` becomes `date_sub('2011-11-11', c0)` with the
  string untouched; `next_day(date'2011-11-11', 'MONDAY')` keeps its day name.
* **One test per file**, each running every statement and comparing the
  rendered corpus section for that file against the committed one.
* **The committed file** as a whole, byte-compared, with the regeneration
  command in the failure message.

    VARKA_GOLDEN_REGEN=true build/sbt -batch "sql/testOnly *VarkaGoldenCorpusSuite"
    build/sbt -batch "sql/testOnly *VarkaGoldenCorpusSuite"

## 6. The measurement

None in the timing sense. The corpus file's count table is the deliverable.

### 6.1 Predictions, registered before the run

1. **`date.sql`: at least 40 of its 101 statements read `FUSED` after the
   rewrite**, and none disagrees with the row engine. The census of its
   functions - `date_sub`, `date_add`, `next_day`, `year`, `month`, `weekday`,
   `make_date`, `dayofyear`, `dayofweek`, `unix_date`, `date_from_unix_date`,
   `datediff` - is the coverage table's own list; what keeps the number under
   101 is the string-typed spellings (`date_sub('2011-11-11', 1)`), the
   timestamp operands, `to_date`, and the invalid-date entries.
2. **`interval.sql` and `extract.sql` read mostly `PLAIN`**: under a fifth of
   `interval.sql` (day-time intervals, decimals and `make_interval` dominate),
   and `extract.sql` almost entirely, since 117 of its 128 statements read a
   view of a string, a timestamp and intervals.
3. **No entry disagrees with the row engine in the *agree* class, and every
   error entry raises the same condition on both sides.** If either fails, that
   is the task's real result and gets its own commit and its own milestone row
   before the corpus is committed.
4. **The whole suite runs in under five minutes**: about 720 statements, each a
   small cached fixture and two queries.

## 7. Risks

* **`Expression.sql` not round-tripping some statement.** Recorded as
  `rewrite failed to render`, with the statement, so the count says how many
  and which; if they are many, a rendering by hand for those shapes is a second
  step, not a blocker.
* **A literal that must stay a literal for an arm to fuse.** The rewrite policy
  is by type, not by position, so an int that an arm needs foldable becomes a
  column and the arm declines. That is a true verdict about the rewritten shape,
  but it can make the fused count read lower than the coverage table implies.
  Section 9 lists such entries by name so the number is read correctly.
* **Fixture rows that trip a guard.** An int literal used as a day offset can
  be large (`date_add(d, 2147483647)` style entries exist); a rewritten column
  offset that leaves the narrowed range declines the batch, which reads as a
  correct decline, not a failure, and the record says so.
* **Runtime.** Seven hundred entries at two sessions each; if the five-minute
  budget breaks, the timestamp and formatting files move behind a flag, since
  they contribute counts and no kernels.

## 8. Sequencing

1. This plan. 2. The classifier port and its test. 3. The rewrite and its
tests on known statements. 4. The harvest over `date.sql` alone, the corpus file
regenerated, prediction 1 scored. 5. The remaining files; predictions 2 to 4
scored. 6. Section 9, row 81, the docs, and a row per finding.

## 9. Outcome

*To be written from the corpus file.*
