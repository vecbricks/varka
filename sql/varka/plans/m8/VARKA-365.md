# VARKA-365: one decimal rule for every Arrow read path below precision 19

*Row 365 of `m8/PLAN.md`, wave 0. Planned on 16 October 2026 at `8a50f61a368`. **The choice of
design is pending**: it is open question 3 of `m8/PLAN.md` section 7, the owner's to answer.
Section 3 lays out what each of its three options would build, so that the answer can be given
against the work, and nothing past section 2 starts before it.*

***Design decided, 16 October 2026: option (iii), no reader change.** The owner answered question 3
the same day (section 9). Sections 2 and 3 stand as written; section 8 now follows (iii)'s branch,
and the parts of sections 4 to 7 marked (i) or (ii) are not built.*

***Refreshed 1 November 2026 at `9c9f6ef5895` (section 10).** The admission run was made, and it
does not find what section 2 predicted: vanilla Spark answers the three casts over the Arrow cache
by the exact rule on every path the run tried, and only Varka, reading the batches through
`ArrowColumnVector`, answers by the double rule. Option (iii)'s premise is reversed by it, so what
the row builds past its suite is put back to the lead (section 10.4).*

***Decided and built, 1 November 2026 (section 11).** The owner: "Varka should follow vanilla
Spark". Question 3's answer, option (iii), stands, no change to Spark's readers, and now means the
exact rule; the row fixes Varka's own row reads of a small decimal, and the upstream JIRA stays
held.*

***Reviewed, 1 November 2026 (section 12).** The view now reads every slot as vanilla's row path
does, malformed ones included, every row read of an input batch goes through it, and the suite
reaches the columnar-out paths.*

***Rescoped, 1 November 2026 (section 13).** The view applies only over the Arrow cache, takes the
low word only from a 128-bit field at the type's scale, and no longer emulates vanilla's
projection reader: on corrupt slots Varka need not reproduce vanilla, by the owner's word.*

## 1. Where this came from

Owner's decision A1 (14 October 2026, `m8/PLAN.md` 2.8): the fork's Arrow readers build a
long-backed `Decimal` up to precision 18, so the kernel, the ghost fallback and the Varka-off path
all follow Spark's exact rule for the ANSI and TRY casts of a decimal to `INT`, `SMALLINT` and
`TINYINT`, which is what Parquet gives. Risk 10 of the milestone is the reason it is wave 0: the
fork's two row paths disagree on decimals today, so an ANSI `CAST(dec AS INT)` over a cached table
can answer differently by batch with Varka on, before any kernel of this milestone exists. The
review of 16 October 2026 (10.1 item 3) then found that the accessor A1 changes is upstream's own
recent choice - SPARK-59443, merged on 21 September 2026 (fork commit `f2dcec5bdd6`) - and that A1
makes every Arrow consumer of the fork, Varka off included, answer the three casts differently from
vanilla Spark's column path. That is a departure outside Varka, so the review put it back to the
owner as question 3, with three options. This plan records the code as it stands, the run that
would confirm the disagreement, and what each option builds.

## 2. The admission check, done

What reading settled, at `8a50f61a368`:

1. **The column path builds a `BigDecimal`-backed value at every precision.**
   `ArrowColumnVector` (`sql/catalyst/src/main/java/org/apache/spark/sql/vectorized/`) picks
   `SmallDecimalAccessor` for precision 1 to 18 (207 to 213). Its `getDecimal` (469 to 483) reads
   the low and the high word, and when the high word is the low word's sign extension it returns
   `Decimal.apply(BigDecimal.valueOf(unscaled, scale), precision, scale)`, under the comment "Keep
   BigDecimal-backed Decimal semantics, including checked integral conversions"; otherwise it
   builds one from `getObject`. `DecimalAccessor` (496 to 499) builds one from `getObject` too.
2. **The cache's row reader builds a long-backed value, with no high-word check.**
   `ArrowColumnReader.scala` (`sql/core/.../execution/columnar/`), for `DecimalType` up to
   `MAX_LONG_DIGITS` (124 to 148), writes the low word straight into the `UnsafeRow` (143 to 147),
   which `getDecimal` reads back long-backed. A slot whose high word is not the sign extension -
   a value outside its declared precision - is silently truncated to its low word here, where the
   column path keeps it whole. Precision above 18 takes the slow path (149 to 160).
3. **Which path a cached batch takes depends on the table's other columns.**
   `ArrowCachedBatchSerializer.convertCachedBatchToInternalRow` (181 to 255) uses the row reader
   only when no column needs its fallback; a table with an array, struct, map, calendar-interval,
   variant, null-typed, UDT, geospatial or nanosecond-timestamp column (`needsFallback`, 205 to
   218; the ANSI interval types are not among them) goes through
   `convertCachedBatchToColumnarBatch` and an `UnsafeProjection` (220 to 239), that is through
   `ArrowColumnVector`. So with Varka off, the same decimal column answers by the exact rule in one
   cached table and by the double rule in another that adds, say, an array column. The columnar
   path, which Varka and its fallback read, always goes through `ArrowColumnVector`
   (`ArrowCachedBatchToColumnarBatchIterator.scala` 134 and 137).
4. **The two rules.** `Decimal.roundToNumeric` (`sql/api/.../types/Decimal.scala` 294 to 320),
   behind `roundToByte`, `roundToShort` and `roundToInt`: a long-backed value truncates and checks
   exactly; a `BigDecimal`-backed one converts to a double and range-checks `floor` and `ceil` of
   it. `roundToLong` (327 to 345) uses `longValueExact` on the `BigDecimal` branch, so `BIGINT` is
   exact on both, and only the three pairs differ.
5. **Every Arrow consumer in the fork reads through `ArrowColumnVector`**: the cached-batch
   columnar iterator, `ArrowRRunner`, `ExecuteExternalUDFExec`, `ArrowConverters`,
   `PythonArrowOutput`, `PythonStreamingSourceRunner` and
   `TransformWithStateInPySparkDeserializer` (all under `sql/core/.../execution/`).
6. **Upstream's regression test pins the double rule.** `ArrowColumnVectorSuite` (sql/core) has
   eight decimal tests from SPARK-59443; "decimal checked integral conversions" (397 to 418)
   asserts that `roundToByte`, `roundToShort`, `roundToInt` and `roundToLong` match a
   `BigDecimal`-backed `Decimal`, for values including `2147483647.99999999`. Under option (i)
   that test's expectation changes; no Varka test exercises the three casts over a decimal today,
   and `0.999999999999999999` appears in no test.
7. **Upstream's record** (`m8/PLAN.md` question 3, read 16 October 2026): SPARK-59443 is a
   performance change that kept the old behaviour on purpose without ruling on it; its one
   reviewer compared old and new accessors and approved; nobody discussed a long-backed value. And
   vanilla Spark's own `ArrowCachedBatchSerializer` already gives the long-backed value on its row
   path. So "vanilla Spark's rule for an Arrow source" is two rules, chosen by whether a batch is
   read as rows or as columns, and by item 3 above, by the table's other columns.

**What still needs a run, and what admits the row.** The disagreement of items 2 to 4 has been read,
not observed. One sql/core test, run on cores 3 to 5, observes it on all three paths:

    taskset -c 3-5 build/sbt 'sql/testOnly *VarkaDecimalReadPathSuite'

over a table of `DECIMAL(18, 18)` holding `0.999999999999999999` and `DECIMAL(18, 8)` holding
`2147483647.99999999`, cached under `ArrowCachedBatchSerializer`, read three ways: the row path
(decimal columns only), the row path's columnar fallback (the same table plus an array column),
and the columnar path with Varka on and every batch on the fallback. Each read runs
`CAST(x AS INT)` under ANSI and under `try_cast`. **The row is admitted if** the row path answers
0 and 2147483647 and the other two answer 1 and raise `CAST_OVERFLOW` (null under TRY): the
disagreement is real and the plan's premise holds. If all three agree, risk 10 is not real as
stated, and the row shrinks to the upstream record of section 3.4. The suite is the first commit
whatever the option, since every option needs it as its regression test.

## 3. The design

### 3.1 The three options, as question 3 states them, and what each builds

**(i) Owner's decision A1 as it stands.** `SmallDecimalAccessor.getDecimal` returns
`Decimal(unscaled, precision, scale)` - long-backed - when the high word is the sign extension and
`|unscaled| < 10^precision`, keeping its `BigDecimal` path for a value outside its declared
precision; `ArrowColumnReader`'s fast path gains the same high-word and precision check and takes
the `BigDecimal` path when it fails. Every path then gives the exact rule below precision 19, as
Parquet does. It builds:
* the two reader changes, and `ArrowColumnVectorSuite`'s "decimal checked integral conversions"
  rewritten to expect the exact rule, with a comment naming this departure;
* an entry 3.8 in `SCOPE_STANDARD_MODE.md`: the fork's Arrow consumers, Varka off as well, answer
  the three casts by the exact rule where vanilla Spark's column path uses the double rule;
* for the kernels: row 350 builds the exact integer rule below precision 19 (SMT at full width),
  and 364 the double rule above, through 366's exact decimal-to-double, as `m8/PLAN.md` 2.8 has
  it. Nothing in 350 depends on a switch.

**(ii) A1 behind a configuration, off by default.** The same reader changes, taken only when a
new SQL configuration is true; off, the fork answers as vanilla Spark's column path until upstream
agrees. It builds:
* the reader changes under the flag, read once per accessor construction, never per value;
* a SQL configuration, proposed as `spark.sql.execution.arrow.longBackedSmallDecimal.enabled`
  (not under `spark.sql.codegen.varka`, since it governs every Arrow consumer, Varka off
  included), with the configuration audit suite run after it is added. Its binding policy cannot
  be the `NOT_APPLICABLE` every existing Varka entry carries (each says it never changes what a
  view resolves to, and `VARKA_WARMUP_ENABLED` chooses between equal answers): this one changes
  answers, so the policy and the reason are decided with the audit;
* both arms of `ArrowColumnVectorSuite`'s test, the existing expectation under the default;
* for the kernels, **350's ANSI and TRY decimal-to-integral design flips with the switch**: off,
  the fallback gives the double rule, so the kernel must too, through a correctly rounded
  `u / 10^s` (366's exact arm wherever 350's guard fails); on, the exact integer rule. Both are
  built and the flag is in the shape key. The row engine's own cached row path keeps its
  long-backed value either way, so with the flag off the disagreement of section 2 item 3 stays.

**(iii) No reader change.** The kernel reproduces vanilla Spark's rule for an Arrow source. Since
Varka reads columns and its fallback reads `ArrowColumnVector`, that is the double rule at every
precision. It builds:
* nothing in the readers; the fork keeps both upstream behaviours, including the row reader's
  missing high-word check (section 2 item 2), which is then reported as its own finding;
* for the kernels, 350 builds the double rule below precision 19 as 364 does above it: the three
  pairs depend on a correctly rounded `u / 10^s`, so on 366's exact arm outside 350's guard, and
  their proof is the sampled arm's, not SMT's (`m8/casts.tsv`'s semantics column and `m8/PLAN.md`
  2.2 and 5 change with it);
* the by-batch disagreement of risk 10 stays, now between Varka-on reads (kernel and fallback
  both double rule) and Varka-off reads of a cached table on the row path (exact rule). The plan
  can make it visible - a coverage note and a line in `docs/sql-varka.md` - but not remove it.

What all three share: section 2's three-path suite, and the upstream JIRA. Under (i) and (ii) the
JIRA records that Spark's ANSI and TRY decimal-to-integral cast depends on the data source and on
the read path, naming both of upstream's paths; under (iii) it records the same as a finding.
Filing it is an external operation and waits for the owner's yes; its text goes in section 9 first.

### 3.2 What is deliberately unchanged

* Precision above 18: `DecimalAccessor` stays `BigDecimal`-backed, and the double rule there is
  Spark's on every path (364 builds it).
* `BIGINT` targets: exact on both backings (`roundToLong`), untouched.
* No Varka compiler, emitter or kernel change: 350, 364 and 366 build the kernels, under whichever
  option is chosen.
* The `UnsafeRow` decimal layout and the cache's file format; the cache arm of 347 is that row's.

### 3.3 Registered op counts

None: this row emits no kernel.

## 4. Files

* `sql/catalyst/src/main/java/org/apache/spark/sql/vectorized/ArrowColumnVector.java` - (i):
  long-backed small decimal; (ii): the same, under the flag
* `sql/core/src/main/scala/.../execution/columnar/ArrowColumnReader.scala` - (i): high-word and
  precision check; (ii): the same, under the flag
* `sql/catalyst/src/main/scala/org/apache/spark/sql/internal/SQLConf.scala` - (ii): the
  configuration
* `sql/core/src/test/scala/org/apache/spark/sql/vectorized/ArrowColumnVectorSuite.scala` - (i):
  expectation rewritten; (ii): both arms
* `sql/core/src/test/scala/.../execution/VarkaDecimalReadPathSuite.scala` - (i): new: the three-path
  suite; (ii): new; (iii): new
* `sql/varka/plans/SCOPE_STANDARD_MODE.md` - (i): entry 3.8; (ii): entry 3.8, conditional
* `sql/varka/plans/m8/casts.tsv` via `census_curated.txt`, `m8/PLAN.md` 2.2 - (ii): the mode note;
  (iii): the three pairs' proof
* `docs/sql-varka.md` - (i): a note on the three casts; (ii): the configuration; (iii): the
  disagreement

## 5. Tests, and what each is for

1. **`VarkaDecimalReadPathSuite`**: the two values of section 2 on the three paths, ANSI and TRY;
   it catches any path that answers by the other rule. Under (ii), both settings.
2. **A wrong high word**: a slot whose high word is not the sign extension still declines on the
   kernel's load and still reads whole on the column path; under (i) and (ii) the row reader now
   agrees with it instead of truncating.
3. **A slot holding `10^p`** (outside precision 18, high word zero): takes the `BigDecimal` path
   on every reader under (i) and (ii).
4. **Spark's own cast suites on every consumer**: `CastWithAnsiOnSuite` and `TryCastSuite`
   unchanged, and `ArrowColumnVectorSuite` per section 4.
5. **The Python and R runners**: one test each through `ArrowConverters` that the three casts give
   the chosen rule, since those consumers change too under (i) and (ii).

## 6. The measurement

The row changes semantics, not speed; but under (i) and (ii) the small-decimal read stops building
a `BigDecimal` per value, which is what SPARK-59443 set out to reduce.

### 6.1 Predictions, registered before the run

1. **Under (i), `ArrowDecimalReadBenchmark`** (upstream's, sql/core, no committed results file in
   this tree) shows the small-precision `getDecimal` faster than today's, by allocating one object
   per value instead of two; direction only, since the baseline has never been run here. Its
   results are generated on the same machine before and after and committed with the change.
2. **The three-path suite adds under 30 s** to the sql/core Varka tests: one cached table, three
   reads, four casts each, stated at the calibration table's ratio for a new sql/core suite
   (VARKA-243's, about 2, on an estimate of 15 s for its sessions and the cache).
3. **Section 2's run finds the disagreement** exactly as section 2 predicts (row path 0 and
   2147483647; the other two 1 and an error).

## 7. Risks

1. **Under (i), the fork's Arrow consumers answer differently from vanilla Spark with Varka off.**
   That is the departure question 3 exists for; entry 3.8 and the documentation say it.
2. **Under (ii), the flag is read where it is cheap.** Read per accessor, not per value; test 1
   runs both settings in one JVM.
3. **Under (iii), 350's three pairs cost more**: every batch whose guard fails takes 366's exact
   arm, and the proof weakens from SMT to sampling.
4. **The row reader's truncation (section 2 item 2) is a wrong answer today** for a value outside
   its declared precision. Under (i) and (ii) it is fixed; under (iii) it needs its own decision.
5. **Upstream may answer the JIRA the other way.** Under (i) the fork then carries the departure
   until it is reverted; under (ii) it is a default left off.

## 8. Sequencing

1. This plan. **Then the owner's answer to question 3, recorded here and in `m8/PLAN.md` section
   7 with its date.** Nothing below starts before it, except step 2.
2. `VarkaDecimalReadPathSuite` as section 2's admission run, its result recorded here.
3. Under (i) or (ii): the reader changes (with the flag under (ii)), the tests of section 5, the
   register entry and the documentation; then the JIRA text for the owner. Under (iii): the
   documentation and the JIRA text only, and 350's design note.

*Updated 16 October 2026, after the answer (iii):* step 1 is done. What remains, in order:

1. `VarkaDecimalReadPathSuite`, first, as the admission run and then the regression test: it pins
   the row path on the exact rule and the row path's columnar fallback and Varka's columnar path
   on the double rule, so 350's kernel has the answer it must match, and it fails if any path
   moves. Section 5's tests 2, 3 and 5 are (i) and (ii)'s and are not built; test 4 needs nothing,
   since no reader changes.
2. The coverage note and the line in `docs/sql-varka.md`: a Varka-off read of a cached table on
   the row path answers the ANSI and TRY casts of a decimal of precision up to 18 to `INT`,
   `SMALLINT` and `TINYINT` by the exact rule, where Varka's kernel and fallback answer by the
   double rule. Written when the row is built.
3. The upstream JIRA's text in section 9: that Spark's ANSI and TRY decimal-to-integral cast
   depends on the data source and the read path, naming both of upstream's paths, with the row
   reader's missing high-word check (section 2 item 2) as its own finding. Filed only with the
   owner's yes.
4. 350's design note in `m8/PLAN.md` (written with this update): the double rule below precision
   19, on 366's exact arm outside 350's guard, proven by the sampled arm.

Explicitly out of this row: any kernel (350, 364, 366), precision above 18, `BIGINT` targets, and
filing the upstream JIRA without the owner's yes.

## 9. Outcome

*Written 1 November 2026, at `9c9f6ef5895`.* Built: `VarkaDecimalReadPathSuite` (sql/core), the
admission run of section 2 and now the regression test, over nine paths rather than three
(section 10.2 says why); a line in `docs/sql-varka.md`'s limitations stating what the suite found;
and the upstream JIRA's text, 9.2 below, rewritten for what the run observed. Not built: the
coverage note, since no coverage row exists for a cast that declines, so the limitations line
carries it; and nothing in any reader or in Varka's exec nodes, whose fix is the lead's to choose
(10.4). Prediction 6.1 #2 (the suite under 30 s): its nine tests ran in 5 s and the suite in 8.4 s
in a warm sql JVM, about 0.3 of the bound, inside the table's band for short sql/core suites over
one cached view (VARKA-318's 0.37). Prediction 6.1 #3 (the disagreement exactly as section 2 says):
wrong in its attribution, section 10. Prediction 6.1 #1 belonged to option (i) and was not run.

### 9.1 The owner's answer to question 3, 16 October 2026

**Option (iii), no reader change.** The reason: the house rule that row answers follow vanilla
Spark always. Option (i) would make every Arrow consumer of the fork - the cached-batch iterator,
the Python, R and external UDF runners - answer the three decimal-to-integral casts differently
from vanilla Spark with Varka off as well, and (ii) only moves that departure behind a switch.
Under (iii) the kernel reproduces vanilla Spark's column-path rule, the double rule over a
`BigDecimal`-backed value, for the ANSI and TRY casts of a decimal to `INT`, `SMALLINT` and
`TINYINT`, which is what Varka's fallback reads through `ArrowColumnVector`.

It supersedes owner's decision A1 of 14 October 2026, which section 1 and 3.1 (i) describe and
which stay as the record. What changes elsewhere, as 3.1 (iii) laid out:

* `m8/PLAN.md` 2.2, 2.8, 2.10, 5, rows 350, 364 and 365, the wave table, risk 10, question 3 and
  10.1 item 3 carry the answer with its date; `m8/casts.tsv`'s semantics and proof columns for the
  three pairs say the double rule at every precision, with the ANSI and TRY range check proven by
  the sampled arm and only LEGACY's low bits by SMT.
* 350 builds the double rule below precision 19 as 364 does above it, on 366's exact arm outside
  350's guard, so that part of 350 lands with or after 366.
* Risk 10 is accepted, not removed: the disagreement is now between Varka-on reads and Varka-off
  reads of a cached table on the row path, and the row makes it visible rather than fixing it.
* Risk 4 of this plan's section 7 (the row reader's truncation of a value outside its declared
  precision) gets the decision it asked for: it is reported upstream as its own finding, in the
  JIRA whose text this section will record, and the fork does not patch it.
* The row shrinks from medium to small: no reader, configuration, register entry or consumer test.

### 9.2 The upstream JIRA's text, filed only with the owner's yes

Recorded 1 November 2026; not filed.

> **Title:** The ANSI and TRY cast of a small-precision decimal to INT, SMALLINT or TINYINT depends
> on how the value was read
>
> `Decimal.roundToNumeric`, behind `roundToInt`, `roundToShort` and `roundToByte`, has two rules: a
> long-backed `Decimal` truncates its unscaled long and checks it exactly, a `BigDecimal`-backed one
> converts to a double and range-checks its floor and ceiling. They disagree near the range's ends
> and near whole numbers: `CAST(0.999999999999999999BD AS DECIMAL(18, 18))` cast to `INT` under ANSI
> is 0 on the first rule and 1 on the second, and `2147483647.99999999` as `DECIMAL(18, 8)` is
> `2147483647` on the first and raises `CAST_OVERFLOW` (null under `try_cast`) on the second.
> `roundToLong` uses `longValueExact` and is exact on both.
>
> Which backing a value has depends on the reader, not on the type or the data. Below precision 19,
> `UnsafeRow.getDecimal` and the Parquet readers give a long-backed value, so does
> `ArrowCachedBatchSerializer`'s row reader (it writes the unscaled low word into the `UnsafeRow`),
> while `ArrowColumnVector.SmallDecimalAccessor` builds a `BigDecimal`-backed one on purpose
> (SPARK-59443, "Keep BigDecimal-backed Decimal semantics"). An expression that reads a
> `ColumnarBatchRow` over Arrow vectors directly - observed for a columnar consumer of the Arrow
> cache; by reading of the code, the Python and R Arrow runners' output too - meets the double
> rule; the same column copied into an `UnsafeRow` first meets the exact one. So the answer of
> one cast can change with a plan's shape.
> Proposal: one rule below precision 19, most naturally the exact one, the one SQL's semantics and
> every row reader give.
>
> A second, separate finding in the same reader: `ArrowCachedBatchSerializer`'s fast path for
> `DecimalType` up to precision 18 reads only the low word of the sixteen-byte slot, with no check
> that the high word is its sign extension or that the value fits the declared precision, where
> `ArrowColumnVector` checks both and raises. By reading of the code (not yet run), a slot holding
> a value outside its precision is silently truncated to its low word on the row path.

## 10. Refresh, 1 November 2026: what the admission run found

### 10.1 What moved on master since 16 October

Re-read at `9c9f6ef5895`. `ArrowColumnVector`, `ArrowColumnReader`, `ArrowCachedBatchSerializer`
and `Decimal.roundToNumeric` are as section 2 reads them, and upstream's master
(`upstream/master`, 31 October 2026) carries the same row reader and the same accessor, so both
behaviours, and the missing high-word check, are vanilla Spark's and not the fork's. Row 347 merged
Varka's 64-bit decimal load (`DecimalColumn`, with the high-word and digit check
`ArrowColumnVector` makes) and the comparisons over it; no cast of a decimal fuses yet
(`m8/casts_fused.tsv` has every `dec` pair declined), so a decimal-to-integral cast with Varka on
is a residual output or the fallback's. Row 157's decimal outputs are unmerged and change nothing
here.

### 10.2 The run, and why it has nine paths

Section 2 item 3 and the row's done-when assumed that, with Varka off, the cache's columnar path
reads through `ArrowColumnVector` and its row path through `ArrowColumnReader`. Two facts the
reading missed decide it otherwise:

1. `InMemoryTableScanExec.supportsRowBased` is true (SPARK-37369), so a row consumer gets rows
   from the scan even with the vectorized reader on and no `ColumnarToRowExec` is inserted: with
   Varka off, every SQL read of the Arrow cache goes through `convertCachedBatchToInternalRow`,
   and only a columnar consumer - a Varka node - reads `ArrowColumnVector`.
2. `UnsafeRowWriter.write(ordinal, Decimal, p, s)` writes `toUnscaledLong` for `p <= 18`, and
   `UnsafeRow.getDecimal` reads it back long-backed. So every copy into an `UnsafeRow` before the
   cast gives the exact rule: the row reader's columnar fallback (its `UnsafeProjection` over the
   batch, for a table with an array) and Varka's fallback when `VarkaInputRows` copies the row
   because a column is read twice, both observed; by reading, `ColumnarToRowExec` without
   whole-stage code too, though the run's whole-stage-off cell met the row reader, not it.

`build/sbt 'sql/testOnly *VarkaDecimalReadPathSuite'` runs nine tests, all passing as pinned (`a` is
`0.999999999999999999`, `b` is `2147483647.99999999`):

| Path | Rule | `CAST(a)`, `CAST(b)`, `try_cast(a)`, `try_cast(b)` |
|---|---|---|
| Varka off, vectorized reader on (the default) | exact | 0, 2147483647, 0, 2147483647 |
| Varka off, vectorized reader off | exact | the same |
| Varka off, a table with an array (the row reader's fallback) | exact | the same |
| Varka off, whole-stage code off | exact | the same |
| Varka on, a cast nothing fuses beside (no Varka node) | exact | the same |
| Varka on, a residual beside a fused output, served | double | 1, `CAST_OVERFLOW`, 1, null |
| Varka on, the same with a column read twice | double | the same |
| Varka on, the fallback over the batch's own rows (all declined) | double | the same |
| Varka on, fallback over a copied row (column read twice) | exact | 0, 2147483647, 0, 2147483647 |

*Note, 1 November 2026 (12.3):* the third row did not take the fallback it names. Its query
selected only `a`, and vanilla picks its reader by the columns the scan selects, so the row reader
read it. The cell now selects the array beside the cast and checks that the scan does; it still
answers by the exact rule, since the projection over the batch writes the unscaled long back.

LEGACY truncates on every path (0 and 2147483647). A first draft forced the decline with section
2's `date_add` past the guard; the kernel served that batch on current master, so the suite uses
`VarkaColumnarToRowExec.setDeclineKernelForTesting` instead, and each cell checks its plan: no Varka
node and no columnar-to-row transition for the row reads, `numVarkaBatches` above or at zero for
the served and declined ones.

### 10.3 What it means

The disagreement of risk 10 is real, but it is not between two upstream paths: vanilla Spark over
the Arrow cache follows the exact rule wherever a SQL query reads it, and **Varka on answers the
three casts differently from Varka off** whenever the cast is a residual output beside a fused one
or is answered by the fallback without a copy. That is a wrong answer by the house rule (row
answers follow vanilla Spark always), present on master today, before any decimal-cast kernel.
Within Varka the answer also depends on whether some column is read twice (`VarkaInputRows`). Option
(iii)'s reason, as 9.1 records it, was that the double rule is "what Varka's fallback reads through
`ArrowColumnVector`" and so vanilla's column-path rule; the run shows vanilla Spark's SQL path never
reads the cache that way, so (iii) as written would have 350 build the rule that departs from
vanilla Spark.

### 10.4 Open points for the lead

1. **Which rule 350's kernel builds for the ANSI and TRY casts to `INT`, `SMALLINT` and `TINYINT`
   below precision 19.** By the house rule and this run, the exact rule (integer arithmetic, SMT at
   full width, as A1 had it) - but without A1's reader change, since vanilla already gives it.
   `m8/PLAN.md` 2.2, 2.8, 5, rows 350 and 364, risk 10 and `m8/casts.tsv`'s semantics and proof
   columns for the three pairs (via `m8/census_curated.txt`) all say the double rule since
   16 October; this refresh leaves them as they are, for the lead's answer.
2. **Varka's own reads of a decimal.** For Varka on to agree with Varka off today, the residual
   outputs (`mergeProjection` over `ColumnarBatchRow`) and the fallback (`VarkaInputRows`) would
   read a decimal of precision 18 or less long-backed, as the row reader hands it: for example by
   always copying a referenced small decimal into the `UnsafeRow` `VarkaInputRows` builds, or a
   Varka-side wrapper over the batch's rows. That is a change in Varka's exec nodes, not in an
   upstream reader, so it does not reopen question 3; it is not built here, and whether it is this
   row's, a new row, or the debt register's is the lead's call. The suite's three double-rule cells
   are the test it would flip.
3. **Whether a Varka filter's residual condition shows the same split** (`WHERE CAST(a AS INT) =
   1` beside a fused conjunct). Not run; the same `ColumnarBatchRow` read suggests it does.
4. **The JIRA's text** (9.2) now proposes the exact rule and names the columnar consumers as the
   ones that differ; filed only with the owner's yes, as before.


## 11. Decided and built, 1 November 2026

### 11.1 The owner's decisions

Given section 10, the owner decided on 1 November 2026:

1. **"Varka should follow vanilla Spark."** Question 3 of `m8/PLAN.md` section 7 is confirmed:
   option (iii) stands, no change to Spark's readers. Its reason - row answers follow vanilla Spark
   - now means the exact rule below precision 19, since section 10 found vanilla Spark on it on
   every SQL read of the Arrow cache. 9.1's "the kernel reproduces vanilla Spark's column-path rule,
   the double rule" is superseded; `m8/PLAN.md` carries the correction wherever it cites the
   answer, each place dated.
2. **Fix it inside this row**: 10.4 items 1 to 3 are answered here.
3. **The upstream JIRA is held**: its text stays in 9.2 and is not filed.

### 11.2 The fix: Varka's row views read a small decimal long-backed

`VarkaRowView` (sql/core, `org.apache.spark.sql.execution`, Java) is the input batch as Varka's
row-by-row evaluations read it: the same column vectors, except that an `ArrowColumnVector` over a
`DecimalVector` of declared precision up to 18 is wrapped in a column whose `getDecimal` reads the
slot's low word and returns `Decimal.createUnsafe(unscaled, precision, scale)` - the long-backed
value `UnsafeRow.getDecimal` returns to vanilla Spark's operators. Every other read goes to the
wrapped vector. A slot whose high word is not its low word's sign extension (a value outside its
precision), and a read at a scale other than the vector's, go to `ArrowColumnVector` as before:
Varka's decimal load declines the first (row 347), and the view does not copy the row reader's
truncation of it (9.2's second finding). A batch with no such column is returned unchanged.

*Note, 1 November 2026:* superseded by 12.1. The review found that the delegation above left
Varka on and off apart on such a slot, and the view now reads it as vanilla's row path does.

The view is used wherever a Varka node evaluates expressions over the rows of an input batch:

* `VarkaColumnarToRowExec`: the merge projection beside the kernel's output (residual entries), and
  the fallback;
* `VarkaKernelEvaluator.projectResiduals`: the residual entries of the columnar-out projection;
* `VarkaVectorProjection.project`: the columnar-out projection's fallback;
* `VarkaFilterExec`'s two evaluators: the fallback predicate, columnar-out and row-out.

The kernels keep reading the input batch itself, whose Arrow checks the view would fail. The
row-out filter's selected-row copy, the columnar-out filter's generic compaction and
`VarkaInputRows`' copy evaluate no expression over a decimal - they copy it, and the copy writes
`toUnscaledLong` either way - so they are left as they are. *(Note, 1 November 2026: superseded
by 12.2; the two filter copies now read through the view too.)* Nothing in `ArrowColumnVector`,
`ArrowColumnReader` or any other Spark reader changes.

**Allocation (reading, not measured).** The view is built on a node's first batch that has such a
column and rebound for each later one: one `ColumnarBatch`, one array and one wrapper per decimal
column per evaluator, nothing per batch after that. Per value, `ArrowColumnVector`'s accessor
builds a `java.math.BigDecimal`, wraps it in a Scala `BigDecimal` and a `Decimal`, and `Decimal.set`
rescales it and counts its digits; the view builds one `Decimal` from the unscaled long, as
`UnsafeRow.getDecimal` does, and the casts then take `roundToNumeric`'s long branch instead of the
double conversion. So a changed path does strictly less per decimal read than before, and a
schema with no small Arrow decimal pays one type check per column per batch. No benchmark was run,
since nothing here can be slower per row than what it replaces; one more virtual call per decimal
read (the wrapper) is the only added step.

### 11.3 The suite, before and after

`VarkaDecimalReadPathSuite` keeps its nine paths and adds three for a cast left in a filter beside
a fused conjunct (`WHERE date_add(d, 30) > DATE'2020-07-01' AND <cast> IN (0, 2147483647)`, which
returns the row under the exact rule and nothing, or `CAST_OVERFLOW`, under the double rule):
Varka off, Varka on with the kernel serving the batch, and Varka on with every batch declined.

| Path | Before | After |
|---|---|---|
| Varka off, four paths (10.2's table) | exact | exact |
| Varka on, a cast nothing fuses beside | exact | exact |
| Varka on, a residual beside a fused output, served | double | exact |
| Varka on, the same with a column read twice | double | exact |
| Varka on, the fallback over the batch's own rows | double | exact |
| Varka on, the fallback over a copied row | exact | exact |
| Varka off, the cast in a filter | exact | exact |
| Varka on, the cast left beside a fused conjunct, served | exact | exact |
| Varka on, the same, the fallback | double | exact |

The "before" column for the first nine paths is 10.2's run; for the filter's three, a run with the
projection's fix in and the filter's not. The leftover filter splits as 10.4 item 3 suspected, but
only on the fallback: served, the residual `FilterExec` above the Varka filter reads the rows the
filter copied into `UnsafeRow`s, long-backed, and answers by the exact rule; declined, the Varka
filter's fallback evaluates the whole condition over the batch's own rows and met the double rule,
which `VarkaFilterExec`'s two fallbacks now read through the view. (A first draft of the shape, with
`date_add(d, i) > DATE'2000-01-01'` after the cast, fused nothing: the rule left the whole filter
to Spark, so the conjunct was changed to the one `VarkaFilterExecSuite` fuses; each filter cell
checks that its plan holds a `FilterExec` over a Varka filter.)

### 11.4 What changed in the plans

`m8/PLAN.md`: the owner's-answer note under the decisions, 1.1's proof item, 2.2, 2.8, 2.10 (d),
5, rows 350, 364 and 365, the two wave tables, risk 10 (removed), question 3 and 10.1 item 3, each
with a dated note that leaves the earlier text as the record. `m8/casts.tsv`'s semantics and proof
columns for the three pairs, regenerated by `dev/varka_census.py` from its rule text (the columns
are the script's, not `census_curated.txt`'s): the exact rule up to precision 18, SMT for it, the
double rule above, the sampled arm for it. `VARKA-350.md` carries a dated note at its head: it
builds the exact rule. `docs/sql-varka.md`'s limitations bullet is removed, since Varka on now
answers as Varka off. Section 9's "not built: nothing in any reader or in Varka's exec nodes" and
its 9.1 are superseded by this section; section 8's coverage note is not needed.

### 11.5 Open points

1. The held JIRA (9.2) still describes the double rule as what a columnar consumer meets; that
   stays true of upstream's Python and R Arrow runners, which this row does not touch.
2. **An unexplained filter decline.** The leftover-filter shape's first draft, `WHERE <cast> IN
   (0, 2147483647) AND date_add(d, i) > DATE'2000-01-01'`, fused no conjunct: the rule left the
   whole filter to Spark, although the projection fuses `date_add(d, i)` over the same table. The
   draft that fuses changed two things at once - the conjunct's position and a literal offset for
   the column `i` - so which one decides is not known. It may be a gap in the filter compiler
   rather than a property of the test, and is left for the lead.
   *Note, 1 November 2026:* the lead made it milestone row 542 of `m8/PLAN.md` (on branch
   `varka-m8-plans-2`), which owns it from here.

## 12. Review fixes, 1 November 2026

A review of section 11's change found eight points; each was checked against the code, and all
eight held. What changed, and what the suite shows for it:

### 12.1 The view reads a slot as vanilla's row path does

Vanilla's row path over the Arrow cache is one of two readers, chosen by the columns the scan
selects (`ArrowCachedBatchSerializer.convertCachedBatchToInternalRows`, its `needsFallback`):

* **The row reader** (`ArrowColumnReader`) when every selected column is a type it reads. It
  writes the slot's low word into the `UnsafeRow` with no check at all, so a high word that is not
  the low word's sign extension is dropped, and a low word with more digits than the precision is
  kept as it is.
* **An `UnsafeProjection` over the batch** when one is an array, struct, map, variant, UDT,
  interval, null, geometry, geography or nanosecond timestamp. It reads through
  `ArrowColumnVector`'s accessor, which raises `NUMERIC_VALUE_OUT_OF_RANGE` on either slot.

Section 11's view did neither on such a slot: it delegated a non-sign-extended high word to the
accessor, which raises where the row reader answers, and read a low word past the precision
unchecked, which answers where the projection raises. `VarkaRowView` now follows the reader
vanilla would take for the batch's columns: with every column one the row reader reads, it builds
`Decimal.createUnsafe` from the low word at the row reader's own offset
(`ArrowCachedBatchSerializer.compactDecimalUnscaledOffset`, reused rather than re-derived), with no
check, as `UnsafeRow.getDecimal` hands it back; otherwise it reads through the accessor and returns
the long-backed value of what the accessor gave, as `UnsafeRowWriter.write` and the row do. The
read at another scale, which no cached vector has, goes the same way as any other. The list of
types is the serializer's, repeated in the view with a pointer to it; nothing in Spark's readers
changes.

The kernel's `DECIMAL_SLOT` guard (`Analysis.java`, `VarkaVectorIR.DecimalColumn`) is kept, and
its comments now say why: on such a slot vanilla may answer (the row reader) or raise (the
projection), so the kernel declines the batch and the fallback, reading through the view, answers
or raises as vanilla does on that batch. Without the guard the kernel would compute a comparison
over a slot that the projection-read case raises on.

*Note, 1 November 2026:* superseded by 13. The view no longer follows the projection reader, and
the low-word path is taken only for a 128-bit field at the type's scale.

### 12.2 One way to read an input batch's rows

`VarkaEvaluatorBase.inputRows(batch)` is now the one place a Varka node reads an input batch's
rows, and every site calls it: `VarkaColumnarToRowExec`'s merge and fallback,
`VarkaKernelEvaluator.projectResiduals`, `VarkaVectorProjection.project` (which now takes the rows
rather than the batch), both `VarkaFilterExec` fallbacks, the row-out filter's selected-row copy
and the columnar-out filter's generic compaction. The last two were left raw in section 11 as
exact; they are exact for a valid slot, but they copy every column through the accessor, so on a
malformed slot the copy raised where vanilla answers - the suite's served leftover-filter cell
below caught the selected-row copy that way. A raw `rowIterator()` on an input batch is left only
in `VarkaRowView` itself; the kernel's own output batch is still read directly, since it is not
the scan's.

The view finds its columns in one pass, its wrapper's constructor binds through `bind`, and the
iterator it returns drops the view's references to the batch's vectors when it reports the end,
so a read after that fails rather than reaching a vector its owner may have freed. The merge
iterator asks both of its iterators for `hasNext` so that this fires there too. A batch whose rows
are abandoned part way keeps the references until the next batch. `VarkaColumnarToRowExec`'s
`rowView` field, which sat in the block of lazy projections, is gone with the per-site views.

### 12.3 The suite: the paths section 11 did not reach, and malformed slots

`VarkaDecimalReadPathSuite` grows from twelve cells to thirty-three, every one passing. Each cell
runs the two LEGACY casts and the four ANSI and TRY casts and names the reading the six followed.

* **The columnar-out paths.** A Varka projection keeps columnar output only under a columnar
  consumer, and the only one in Spark is the noop write, which shows no values. So three cells
  rebuild the plan's top Varka node with columnar output and read the returned column from its
  batches: the projection served (`projectResiduals`), the projection declined
  (`VarkaVectorProjection.project`), and the leftover filter declined (`VarkaFilterExec`'s
  columnar fallback over the whole condition). One more cell is a real plan, a cast beside a fused
  output over a filter on `b > 0` that fuses whole, which puts a Varka filter under the
  projection.
* **Malformed slots.** `rp_bad` is `rp_fused`'s row with two slots rewritten in the cached Arrow
  bytes (the cache keeps the block deserialized and the message uncompressed, and the rewrite is
  checked after it is made): `a`'s low word is 10^18, nineteen digits at precision 18, and `b`'s
  high word is 1. Vanilla's row reader answers 1 and 2147483647 on all six casts. Four Varka-off
  cells and ten Varka-on cells - row out and columnar out, served and declined, both filters'
  fallbacks and copies, and the filter on `b > 0`, whose kernel declines the batch by its guard
  (the cell checks that it served none and that the filter counted a `decimal-slot` decline) -
  all answer the same. `rp_bad_array` is the same beside an array the query selects: vanilla's
  projection raises `NUMERIC_VALUE_OUT_OF_RANGE` on all six, and Varka on raises the same, with
  the kernel left to serve and with every batch declined. Those two cells raise before the metrics
  can say which path answered, so they check only that the plan holds a Varka node and that its
  scan selects the array; what tells them apart is the decline hook.
* **The array cell of 10.2.** Its query selected only `a`, so the row reader, not the projection
  over the batch, read it (a check on the scan's columns failed on the old shape). It now selects
  the array beside the cast and checks that the scan does; it answers by the exact rule.

Each new cell was checked to fail without its fix, by changing the code locally, running the
suite and restoring it:

* `projectResiduals`, `VarkaVectorProjection.project`, the columnar filter fallback and the
  selected-row copy reading `input.rowIterator()`: eight cells failed - the three columnar-out
  cells on `rp_fused` (the double rule) and on `rp_bad` (a raise), the served leftover filter on
  `rp_bad` (the copy raised and sent the batch to the fallback, so the kernel served none), and
  the filter on `b > 0` on `rp_bad`.
* The view delegating a non-sign-extended high word to the accessor, as section 11 built it:
  nine failed, every Varka-on `rp_bad` cell that reads `b` through the view (`b` raised; `a`
  answered 1 either way, so the digit case alone did not part the two).
* The view always taking the row reader's word, whatever the batch's columns: two failed, both
  Varka-on `rp_bad_array` cells, which answered where vanilla raises.

### 12.4 Open points

1. **The type list is repeated.** The view copies the serializer's `needsFallback` list rather
   than calling it, since it is inline in Spark's reader and option (iii) leaves the reader as it
   is. A type added to that list and not to the view would part Varka on from off on a malformed
   slot only; the `rp_bad_array` cells cover the array arm.
2. **Arrow batches from elsewhere.** The view applies to any `ArrowColumnVector` decimal a Varka
   node reads, and models vanilla's reads of the Arrow cache. A Varka node over another Arrow
   source would read its small decimals long-backed where vanilla's `ColumnarToRowExec` over that
   source reads them through the accessor. The other Spark producers of `ArrowColumnVector` this
   row found (the Python and R Arrow runners, the external UDF node) build their batches inside
   row operators, so none reaches a Varka node as its input; a columnar DSv2 source declaring
   Arrow vectors would, and should get a read-path cell of its own then.
   *Note, 1 November 2026:* superseded by 13; the view applies only over the Arrow cache.
3. **Row 542** owns 11.5 item 2.

## 13. Rescoped, 1 November 2026

The two review rounds of section 12 widened the row's goal from "ANSI and TRY casts of well-formed
small decimals read from the Arrow cache answer as vanilla" to bit-exact parity with vanilla on
corrupt bytes, from every Arrow producer and for every shape of column selection. A fallback over
a batch cannot know enough to do that, and the lead pulled the scope back.

### 13.1 The owner's decision

The owner, 1 November 2026: "We shouldn't precisely follow vanilla Spark on corrupt inputs." The
vanilla-parity rule of 11.1 covers well-formed data; on corrupt inputs that Spark's own writers
cannot produce, Varka need not reproduce vanilla's behaviour. The limit in 13.3 rests on it.

### 13.2 What 365 guarantees

* **Over the Arrow cache, well-formed data: the exact rule on every Varka path.** A Varka node
  whose child is `InMemoryTableScanExec` under `ArrowCachedBatchSerializer` (in its query stage
  under adaptive execution), or a columnar Varka node over one, reads every row of its input batch
  through `VarkaRowView`, by way of `VarkaEvaluatorBase.inputRows`, still the one place an input
  batch's rows are read. `VarkaColumnarRule.readsArrowCache` decides it from the child when the
  node builds its evaluator, and the evaluator takes it as a constructor flag, default off.
* **Over any other columnar input: the reads are unchanged.** With the flag off the evaluator
  reads the batch's own rows, as before this row. Over a non-cache Arrow child vanilla's
  whole-stage `ColumnarToRowExec` reads through the accessor and answers by the double rule, and
  Varka on answers the same, served and declined (a new cell over a columnar leaf built as
  `TestColumnarBatchPlan` is). The same cell records a difference this row leaves as it was:
  without whole-stage code vanilla's transition copies each row into an `UnsafeRow` and answers by
  the exact rule, while Varka reads the batch's rows and answers by the double rule.
* **The low word only from a field the cache writes.** The view reads the row reader's low word
  only when the Arrow field is a 128-bit decimal at the scale of the column's Spark type
  (`VarkaLaneArrow.decimalFieldMatches`, the rule the kernels' own check uses); any other field is
  read through `ArrowColumnVector`, which rescales, and the value is made long-backed in place.
  Section 12's view read the low word whatever the field said, so a field at another scale read
  wrong: 12.5 at field scale 1, read as `DECIMAL(18, 2)`, came back 1.25, and now comes back 12.50
  (a new cell; it fails with the guard removed).

### 13.3 The known limit

On a corrupt slot (a high word that is not the low word's sign extension, or a low word with more
digits than the precision), which Spark's own cache writer cannot produce, in a batch whose scan
also selects a nested type or another type the cache's row reader leaves to its
`UnsafeProjection`, vanilla raises `NUMERIC_VALUE_OUT_OF_RANGE` and Varka answers the low word.
Section 12's emulation of the projection reader - the copy of the serializer's `needsFallback`
list and the reading that followed it - is deleted. The kernel's `DECIMAL_SLOT` guard still
declines a batch with such a slot, and the fallback reads the low word as the row reader does, the
common path of vanilla's cache scan. The `VarkaRowView` javadoc carries the same limit.

### 13.4 Smaller changes

* The view drops its references to a batch's vectors on the next `rows()` and at the evaluator's
  task completion, no longer when its iterator reports the end, so the merge iterator of
  `VarkaColumnarToRowExec` asks only the fused rows for `hasNext` again.
* The small-decimal columns are found once from the schema; a batch binds only those, and the
  accessor path makes its value long-backed in place rather than allocating another `Decimal`.

### 13.5 The suite

`VarkaDecimalReadPathSuite` goes from thirty-three cells to thirty-four, every one passing. Removed:
the two Varka-on `rp_bad_array` cells that expected Varka to raise beside an array. Added: one
cell that pins the limit (Varka on, beside the array, served, answering the low words), the
non-cache child cell and the field-scale cell. The Varka-off `rp_bad_array` cell stays as the
vanilla half of the limit; every other cell, the columnar-out ones and the malformed-slot cells
with no nested column, is unchanged and answers as before. Each new cell was checked to fail
without its fix: the evaluator forced to the view whatever its child, the non-cache cell answered
by the exact rule; the field check removed, the scale cell read 1.25.

