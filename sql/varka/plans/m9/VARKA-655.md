# VARKA-655: baselines for `TIMESTAMP_LTZ` and the nanosecond types, and the timestamp surface

*Planned on 27 November 2026. This file stands alone: a builder needs it and the repository's
`AGENTS.md` files. The row is not started, since milestone 9 waits for the owner's word
(`m9/PLAN.md` decision 18). Its code facts were checked on master `73fad30ead4`. One run of
`dev/varka_emit.sh` was made on an older worktree, at `93625e9c721`; section 3 says which fact
it gave. No other JVM was run and nothing was timed. Revised the same day after a builder's
review; the facts of section 3 and every old text of section 13 were checked again on master
`84d6b2aff10`.*

## 1. What this row is

**The row's text** (`m9/PLAN.md` section 3): "Baselines for `TIMESTAMP_LTZ` and the nanosecond
types before their kernels, and the timestamp surface. [...] One class a family, as row 377
built them: the zone-free sections, the fields, the casts, the arithmetic and the zone
functions, each under UTC, a fixed offset and a daylight-saving zone, over instants in time
order and in no order across one and ten years; and timestamp cases in the surface that runs
against the newest stock Spark release, with a stock arm over the Arrow cache that release ships
(the catalogue's item 132), so that the kernels' share is read apart from the cache format's, or
the row records why the release's serializer cannot be compared with the fork's. **Done when**
each class and both widths' results from the EPYC 7763 are committed before the type's first
kernel, each later row's measurement diffs against them, and the `TIMESTAMP_NTZ` files are run
again so that milestone 8's kernels have their numbers".

A baseline is a benchmark class committed with its results while the engine does not yet fuse
what it times. Both of its arms then run Spark's row path, and the committed file is what a
later kernel's measurement is compared with. The row writes no kernel and changes no
production code.

**What it gives the rows after it.**

| row | what it reads of this row |
| :--- | :--- |
| 656, `TIMESTAMP_LTZ` on the long lane | `VarkaTimestampBaselineBenchmark`, the class of the zone-free sections, shaped as `VARKA-656.md` 5.2 greps it (4.5), its queries suite, and its four results and provenance files; part D of that row runs the class again and diffs |
| 657, the session zone in a kernel | `VarkaTimestampFieldsBaselineBenchmark` (the one new class that holds `hour(`) and `VarkaTimestampCastsBaselineBenchmark` (the one new class that holds `AS DATE`), each with a line a form that row 657 turns to fused (4.6), and their results |
| 685 to 688 | the queries suites named `*BaselineQueriesSuite`; the ten-year sections that row 657 lists as `ALL_FALL_BACK`; the classes that hold the text `America/Los_Angeles` (the four zoned ones) |
| 690, 670, 671 | the lead forms `CAST(ntz AS TIMESTAMP)`, `CAST(d AS TIMESTAMP)` and `ts + INTERVAL '1' DAY` over the full grid; the sections of the zone functions and of the arithmetic |
| 691, the zone kernels measured | every zoned class at 256 and 128 bits, and the `TIMESTAMP_NTZ` twin beside each form |
| 667 to 669, the fan-out | one section a form at G7's cell, committed before the form's kernel |
| 699, 673 to 675, the nanosecond rows | the four nanosecond classes and their results (parts F and G) |
| 691, and 676, the post | the timestamp surface against stock Spark 4.3.0 with its two Arrow-cache arms, and its first, unfused run; row 691 runs it first with the zone kernels on, and row 676 once more at the end (4.10) |
| milestone 8's rows 338 to 340 | the three `TIMESTAMP_NTZ` files run again with the kernels on (part D) |

**What must be merged before it starts.** The owner's word that milestone 9 starts. Row 663,
the upstream sync, which is merged (#1773). This plan, merged by the lead before part A.
Nothing else: parts A to E are wave 0 of `m9/PLAN.md` section 4 and wait for no row. Parts F
and G are wave 4; they need part A alone.

**The goals it serves** (`m9/PLAN.md` 1.1), each quoted, with the item of 1.2 that it moves and
how a reader checks the move.

| goal, as `m9/PLAN.md` has it | the item of 1.2, quoted | what the row moves, and the check |
| :--- | :--- | :--- |
| "**G7. Speed where a time zone is read.** A fused `TIMESTAMP_LTZ` expression runs significantly faster than vanilla Spark: than the row path of the same build, section by section, and than the newest stock Spark release on a timestamp surface. No fused shape is slower than the row path on a measured host; a shape that would be declines there." | "**G7:** on the AMD EPYC 7763 at 256 bits, in the baseline classes of row 655 under a daylight-saving zone with a year of instants in no order, every `TIMESTAMP_LTZ` section whose kernel reads the zone runs at least five times the row path's rate and every other section at least twice; no section is slower than the row path at 256 or at 128 bits; and the timestamp surface against the newest stock Spark release is committed for the post. The nanosecond `TIMESTAMP_LTZ` sections meet the same bars in phase 2. The bars are the owner's (decision 9)." | the row builds what the item reads: the classes, the cell "`America/Los_Angeles`, a year in no order" in every form (4.3), and the surface. The row moves no ratio: every `TIMESTAMP` section's two arms tie. Check: the files of 4.9 are in `sql/core/benchmarks`, and each holds the section titles of 4.4 |
| "**G1. `TIMESTAMP_LTZ` at microseconds (phase 1).**" | "**G1:** at least three quarters of `TIMESTAMP_LTZ`'s (entry, type) pairs fused (about 66 entries are expected to admit it)." | only through later rows: a row that fuses an entry reports its speed against this row's file. This row fuses nothing |
| "**G4. Nanosecond `TIMESTAMP_NTZ` and `TIMESTAMP_LTZ`, precision 7 to 9 (phase 2).**" | "**G4:** at least three quarters of each nanosecond type's pairs fused under the flag" | only through rows 699 and 673 to 675, which measure on the classes of parts F and G. Check for this row: those classes' files are committed before row 699's first kernel |
| "**G6. The post.**" | "**G6:** the post is published." | only through rows 691 and 676, which run the surface with the kernels on; the post quotes those files |
| G2, G3, G5 | items 2, 3 and 8 | not moved |

## 2. What an agent must know

### 2.1 The idea

Spark's `TIMESTAMP` (its SQL alias is `TIMESTAMP_LTZ`, its class `TimestampType`) is a long of
microseconds since the epoch in UTC. An expression that shows it as a local time reads the
session time zone, `spark.sql.session.timeZone`. Varka declines the type today, so every
expression over it runs on Spark's row path. Milestone 9 fuses the type row by row. The house
rule (`m9/PLAN.md` 1.5) is: "a baseline on the unchanged engine before a kernel, in its own
pull request; every number from the AMD EPYC 7763 at 256 and 128 bits; a ratio under 1.3 run
again and compared by minimums". This row is that baseline, for the microsecond type in five
families and for the nanosecond types in four.

Milestone 8 built the model for `TIMESTAMP_NTZ` (its row 377): one Java class a family on
`VarkaSqlBenchmarkBase`, two sessions over an Arrow-cached table of 2,000,000 rows, two cases a
section named `spark row path (varka off)` and `varka on`, a fuse class stated for every
section and checked before any timing, a ScalaTest suite that runs every section at a small
scale in every Build, and results from GitHub's AMD EPYC 7763 runners committed with a
provenance file at 256 and at 128 bits. This row follows that model and adds three things a
zone needs: the session zone as a dimension of a section, data sets that differ in how many
changes of offset a batch spans, and a `TIMESTAMP_NTZ` twin beside a form, which is the
control that the zone's price is read against.

### 2.2 Terms

- **Row path:** Spark's own evaluation, whole-stage generated Java over rows. **Kernel:** the
  class Varka emits for a projection or a filter. **Fuses:** the Varka session plans the
  expression as a kernel.
- **Arm, case:** one timed line of a section. Every section here has two: `spark row path
  (varka off)`, a session without the Varka rule, and `varka on`, a session with it.
- **Fuse class:** a value of `VarkaSqlBenchmarkBase.Fusion` that says how much of a section's
  plan is Varka's: `NONE`, `NULL_TESTS`, `SPLIT`, `PARTIAL` or `WHOLE`. A baseline section is
  `NONE`. `VarkaSqlBenchmarkBase.fusion(SparkPlan)` reads the class of a plan;
  `requireBaseline` (in the benchmark) and `requireSection` (in the queries suite) fail when
  the class read is not the class stated.
- **Batch check:** a value of `VarkaSqlBenchmarkBase.BatchCheck` that says what a `WHOLE`
  section's batches do: `KERNEL` (the kernel computes every batch), `MAY_FALL_BACK` (not
  checked) or `ALL_FALL_BACK` (a guard declines every batch). It is read only where the class
  is `WHOLE`.
- **The two-lane refusal:** on an x86 host whose 64-bit vectors have two lanes (the 128-bit
  run), the compiler leaves a filter's condition over a long to the row path (VARKA-503).
  `VarkaSessionEmit.of(conf).declineTwoLaneConditions()` says whether a session does. A
  projection is never refused.
- **Zone:** a value of `spark.sql.session.timeZone`. The three of this row are `UTC`, the
  fixed offset `+01:00`, and `America/Los_Angeles`, which changes its offset twice a year.
- **Change:** an instant at which a zone's offset changes. **In time order:** a column whose
  instants never decrease from row to row. **In no order:** instants drawn by a hash of the
  row id, so that every batch spans the whole data set.
- **Data set:** one of four ranges of instants (4.2): a week, a year in time order, a year in
  no order, ten years in no order.
- **Cell:** a zone and a data set. **G7's cell:** `America/Los_Angeles` with a year in no
  order, the cell that `m9/PLAN.md` 1.2 item 10 reads.
- **Form:** one SQL expression of a class, written over column names with two marks (4.2).
  **Lead form:** a form timed in six cells. Every other form is timed in G7's cell alone.
- **Twin:** the same form over a `TIMESTAMP_NTZ` column that holds the same instants read in
  UTC. A twin reads no zone and fuses today.
- **Queries suite:** the ScalaTest suite of a class, named `<Class minus Benchmark>QueriesSuite`.
- **The lead:** the agent that coordinates the row. It pushes, opens pull requests,
  dispatches workflows and merges. **The builder:** the agent that has this plan. It stops at
  a local commit.
- **Surface:** the Maven project `sql/varka/bench`, a plain Spark application that times one
  entry an expression on several Spark distributions, among them a downloaded stock release
  (`dev/varka_bench_surface.sh`, `.github/workflows/varka-surface-benchmark.yml`).
- **Nanosecond types:** `TIMESTAMP_LTZ(p)` and `TIMESTAMP_NTZ(p)` with `p` from 7 to 9, Spark's
  `TimestampLTZNanosType` and `TimestampNTZNanosType`, behind the internal flag
  `spark.sql.timestampNanosTypes.enabled`.

### 2.3 The names this row reads, and the names it adds

Paths: `<b>` is `sql/core/src/test/java/org/apache/spark/sql/execution/benchmark`, `<s>` is
`sql/core/src/test/scala/org/apache/spark/sql/execution/benchmark`, `<r>` is
`sql/core/benchmarks`, `<sb>` is
`sql/varka/bench/src/main/java/org/apache/spark/sql/varka/bench` and `<st>` is the same path
under `src/test`.

| name | where | read or added | meaning |
| :--- | :--- | :--- | :--- |
| `VarkaSqlBenchmarkBase` | `<b>` | read | the base of a SQL benchmark: `requireBaseline`, `requireSection`, `Fusion`, `BatchCheck`, `noop`, `note`, `stopLeftoverSessions`, `clearSessions`, `FUSE_CHECK_PROPERTY` |
| `VarkaArrowSessions.createSession`, `.cache` | `<s>/VarkaArrowSessions.scala` | read | a local session whose cache is Arrow's, with the Varka rule or without; a view cached and checked to be Arrow's |
| `VarkaSharedSessions` | `sql/core/src/test/scala/org/apache/spark/sql/execution` | read | the suites' two sessions, `spark` and `varkaSpark`; `withBothConf`; `forEachTwoLaneRefusal` |
| `VarkaTimestampNtzBaselineBenchmark`, `...NtzFieldsBaselineBenchmark`, `...NtzArithmeticBaselineBenchmark` | `<b>` | read; part D runs them | milestone 8's three `TIMESTAMP_NTZ` baselines |
| `VarkaTimestampBaselineBase` | `<b>` | added, part A | the abstract base of this row's nine classes: the zones, the data sets, the table, the forms, the run |
| `VarkaTimestampBaselineQueries` | `<s>` | added, part A | the trait of this row's nine queries suites |
| `VarkaTimestampBaselineBenchmark` | `<b>` | added, part A | the zone-free sections; view `varka_ts` |
| `VarkaTimestampFieldsBaselineBenchmark` | `<b>` | added, part B | the fields; view `varka_ts_fields` |
| `VarkaTimestampCastsBaselineBenchmark` | `<b>` | added, part B | the casts; view `varka_ts_casts` |
| `VarkaTimestampArithmeticBaselineBenchmark` | `<b>` | added, part C | the arithmetic; view `varka_ts_arith` |
| `VarkaTimestampZoneFunctionsBaselineBenchmark` | `<b>` | added, part C | the zone functions; view `varka_ts_zone` |
| `VarkaTimestampNanosBaselineBenchmark`, `...NanosFieldsBaselineBenchmark` | `<b>` | added, part F | the nanosecond zone-free sections and fields |
| `VarkaTimestampNanosCastsBaselineBenchmark`, `...NanosFunctionsBaselineBenchmark` | `<b>` | added, part G | the nanosecond casts and functions |
| `Timestamps`, `TimestampSurfaceBenchmark`, `TableShape.TIMESTAMPS` | `<sb>` | added, part E | the surface's entry list, its main class and its table `varka_timestamps` |
| `TimestampsTest` | `<st>` | added, part E | the entry list's test on the module's stock release |

Each class has a queries suite in `<s>` and four files in `<r>` (4.9).

## 3. The admission check

Each fact was checked on master `73fad30ead4` and again on `84d6b2aff10`, from the root of the
checkout, with the paths
of 2.3 as shell variables `B`, `S`, `R`, `SB`.

| # | fact | command | prints |
| ---: | :--- | :--- | :--- |
| 1 | The three `TIMESTAMP_NTZ` baseline classes exist | `ls $B/VarkaTimestampNtz*BaselineBenchmark.java \| wc -l` | `3` |
| 2 | No baseline class reads a `TIMESTAMP` | `grep -l "TimestampType\|TIMESTAMP_LTZ\|timestamp_micros\|TIMESTAMP '" $B/Varka*BaselineBenchmark.java \| wc -l` | `0` |
| 3 | The three wide `TIMESTAMP_NTZ` files were last written before their kernels merged | `for c in "" Arithmetic Fields; do git log -1 --format=%cs -- $R/VarkaTimestampNtz${c}BaselineBenchmark-jdk25-runner-epyc7763-results.txt; done` | `2026-10-26`, `2026-10-31`, `2026-11-02` |
| 4 | Their sections tie today: the file of the fields has `hour(ntz)` at 64.1 ns a row on the row path and 64.0 with Varka on | `grep -A3 "^TIMESTAMP_NTZ hour(ntz), no nulls over" $R/VarkaTimestampNtzFieldsBaselineBenchmark-jdk25-runner-epyc7763-results.txt` | the two cases, `64.1` and `64.0` |
| 5 | The registry declines `TIMESTAMP`, so nothing over it fuses | `grep -P "^TIMESTAMP\t" sql/varka/types.tsv \| cut -f5` | `declined` |
| 6 | The registry does not know the nanosecond types: `VarkaTypes.idOf` returns null for them | `grep -c "Nanos" sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaTypes.java` | `0` |
| 7 | The fuse classes and the batch checks | `grep -c "public enum Fusion { NONE, NULL_TESTS, SPLIT, PARTIAL, WHOLE }" $B/VarkaSqlBenchmarkBase.java; grep -c "public enum BatchCheck { KERNEL, MAY_FALL_BACK, ALL_FALL_BACK }" $B/VarkaSqlBenchmarkBase.java` | `1`, `1` |
| 8 | `requireBaseline` and `requireSection` take a `BatchCheck` | `grep -c "Fusion expected, BatchCheck check) {" $B/VarkaSqlBenchmarkBase.java` | `2` |
| 9 | A dry run times nothing but runs every check: `runCases` returns at once under `-Dvarka.bench.dryRun=true` | `grep -A3 "protected static void runCases" sql/catalyst/src/test/java/org/apache/spark/sql/VarkaBenchmarkBase.java` | `if (dryRun()) {` and `return;` |
| 10 | The nanosecond flag is internal and off outside tests | `grep -A15 'buildConf("spark.sql.timestampNanosTypes.enabled")' sql/catalyst/src/main/scala/org/apache/spark/sql/internal/SQLConf.scala` | `.internal()`, and `createWithDefaultFunction(() => Utils.isTesting)` |
| 11 | Spark answers the forms of 4.8 over the nanosecond types and rejects `ts - ts`, `months_between`, `timestampdiff` and, over the zoned type, `convert_timezone` | `grep -c "UNSUPPORTED_FEATURE.TIMESTAMP_NANO\|UNEXPECTED_INPUT_TYPE" sql/core/src/test/resources/sql-tests/results/timestamp-ltz-nanos.sql.out` | a count above 0; the file's queries were read one by one for 4.8 |
| 12 | The fused `TIMESTAMP_NTZ` forms that the twins of 4.4 need are coverage rows | the script of 4.7 | `28 twins, 0 missing` |
| 13 | Stock Spark 4.3.0 ships the Arrow cache serializer, and it stores a `TIMESTAMP` | `git ls-tree -r --name-only v4.3.0 \| grep -c "columnar/ArrowCachedBatchSerializer.scala"; git grep -c "TimeStampMicroTZVector" v4.3.0 -- sql/core/src/main/scala/org/apache/spark/sql/execution/columnar/ArrowCachedBatchSerializer.scala` | `1`, and a count above 0 |
| 14 | The surface runs against 4.3.0 and its module compiles against 4.2.0 | `grep -c "STOCK_VERSION: 4.3.0" .github/workflows/varka-surface-benchmark.yml; grep -c "<spark.version>4.2.0</spark.version>" sql/varka/bench/pom.xml` | `1`, `1` |
| 15 | The surface script already has the cache-only arm | `grep -n "arrow-cache)" dev/varka_bench_surface.sh` | one line |
| 16 | An entry of the surface may be left out of the fuse check | `grep -c "public record Entry(String label, String projection, String filter, boolean expectFused)" $SB/Surface.java` | `1` |
| 17 | No branch of this row exists | `git branch -a --list '*varka-655*' \| wc -l` | `0` |

**The run of `dev/varka_emit.sh`** (on `93625e9c721`, older than master): `dev/varka_emit.sh "ts <
ts2" --columns ts:timestamp,ts2:timestamp` answers `unsupported column type timestamp`. The
tool has no `timestamp` column type until row 656's part A. So this plan could not print a
`TIMESTAMP` expression's decline from the tool. Fact 5 stands in for it, and each part's queries
suite proves it on the build's master before any dispatch.

**What I could not check.**

- No section was run. That each form analyzes, answers no null over the null-free columns and
  plans as 4.4 says is checked by the queries suite of its part. Where the suite disagrees, the
  sheet's rule of 5.0 applies.
- The code of this plan (4.2, 4.5, 4.6, 5.x) was not compiled: this plan was written with no
  build. A compile error is the builder's to fix where the fix changes no name, no SQL text and
  no title; anything else is a stop.
- The time a class takes on a runner is a prediction (7).
- Whether a Varka session reads a cached nanosecond column, which the Arrow cache stores as a
  struct of two vectors, without an error. Part F's suite shows it; 5.6 says what to do.
- Whether the stock release's serializer and the fork's give one cache format. 4.10 gives the
  rule that decides it from the first run.

## 4. The design

### 4.1 Nine classes, one base, one trait

One class a family, as the row says. Five are of the microsecond `TIMESTAMP`: the zone-free
sections, the fields, the casts, the arithmetic and the zone functions. Four are of the
nanosecond types: the zone-free sections, the fields, the casts and the functions. Each is a
`public class` in `<b>` that extends `VarkaTimestampBaselineBase` and states only its view, its
columns and its forms. Each has a queries suite in `<s>` of a few lines that extends the trait
`VarkaTimestampBaselineQueries`. The base and the trait are part A's and are given whole below,
so that parts B, C, F and G add lists and no logic.

The three `TIMESTAMP_NTZ` classes of milestone 8 are not touched and not moved onto the base:
part D only runs them.

### 4.2 The base class, whole

`<b>/VarkaTimestampBaselineBase.java`. Its first 16 lines are the license header, copied from
lines 1 to 16 of `<b>/VarkaTimestampNtzBaselineBenchmark.java`. Then:

    package org.apache.spark.sql.execution.benchmark;

    import java.util.ArrayList;
    import java.util.List;
    import java.util.Map;

    import org.apache.spark.sql.SparkSession;
    import org.apache.spark.sql.VarkaBenchmark;
    import org.apache.spark.sql.execution.VarkaSessionEmit;
    import org.apache.spark.sql.internal.SQLConf;

    /**
     * The base of the timestamp baselines (VARKA-655): what the classes of the families share,
     * so that a class states only its view, its columns and its forms.
     *
     * <p>A section is a form in a cell. A cell is a session time zone, one of {@link #ZONES},
     * and a data set, one of {@link Data}. The run goes zone by zone: it sets the zone on both
     * sessions, caches the class's table, runs that zone's sections and drops the cache. It
     * goes through the zones twice, first to check every section's plan against its
     * {@code Fusion} class ({@link VarkaSqlBenchmarkBase#requireBaseline}) and then to time,
     * so that no section is timed before every section is checked.
     *
     * <p>Each section has two arms, named so the labels survive a kernel landing:
     * {@code spark row path (varka off)}, a session without the Varka rule, and
     * {@code varka on}, a session with it.
     */
    public abstract class VarkaTimestampBaselineBase extends VarkaSqlBenchmarkBase {

      static final int NUM_ROWS = 2_000_000;

      /** The session time zones: UTC, a fixed offset, and one with two changes a year. */
      static final String UTC = "UTC";
      static final String FIXED = "+01:00";
      static final String DST = "America/Los_Angeles";

      /** The zones, in the order their sections run. */
      static final List<String> ZONES = List.of(UTC, FIXED, DST);

      /** How a title ends: over the columns with no null, or over those with one in ten. */
      static final String NO_NULLS = "no nulls";
      static final String NULLS = "one row in ten null";

      /**
       * A data set: the instants of a timestamp column. Each starts at {@code startSeconds}
       * since the epoch and spans {@code spanSeconds}. In time order, row {@code id} of
       * {@code rows} holds second {@code id * spanSeconds / rows} of the span; in no order, a
       * hash of the row id picks the second, so every batch spans the whole data set. A second
       * hash gives each value its microseconds of the second.
       */
      enum Data {
        /** 1 to 8 June 2024, with no change of offset in any of the three zones. */
        WEEK("w", "a week", 1_717_200_000L, 604_800L, false),
        /** 365 days from 1 January 2024, rising row by row. */
        YEAR_ORDERED("yo", "a year in time order", 1_704_067_200L, 31_536_000L, true),
        /** The same 365 days in no order: the data set of the milestone's speed bar. */
        YEAR("y", "a year in no order", 1_704_067_200L, 31_536_000L, false),
        /** 1 January 2020 to 1 January 2030, 3653 days, in no order. */
        TEN_YEARS("d", "ten years in no order", 1_577_836_800L, 315_619_200L, false);

        final String suffix;
        final String label;
        final long startSeconds;
        final long spanSeconds;
        final boolean ordered;

        Data(String suffix, String label, long startSeconds, long spanSeconds, boolean ordered) {
          this.suffix = suffix;
          this.label = label;
          this.startSeconds = startSeconds;
          this.spanSeconds = spanSeconds;
          this.ordered = ordered;
        }

        /**
         * SQL of a long: the microseconds since the epoch of row {@code id} of {@code rows}, in
         * the column whose hashes take {@code tag} and {@code tag + 100}. Two columns in time
         * order hold the same second in a row and differ in their microseconds.
         */
        String microsSql(int tag, int rows) {
          String second = ordered
              ? "(id * " + spanSeconds + "L) div " + rows + "L"
              : "pmod(xxhash64(id, " + tag + "), " + spanSeconds + "L)";
          return "((" + startSeconds + "L + " + second + ") * 1000000L + pmod(xxhash64(id, "
              + (tag + 100) + "), 1000000L))";
        }
      }

      /** What a column holds, as SQL over the microseconds {@code %s}; none reads a zone. */
      enum Kind {
        TS("timestamp_micros(%s)"),
        NTZ("timestampadd(MICROSECOND, %s, TIMESTAMP_NTZ '1970-01-01 00:00:00')"),
        DATE("date_from_unix_date(cast(%s div 86400000000L as int))"),
        MICROS("%s"),
        MILLIS("%s div 1000L"),
        SECONDS("%s div 1000000L"),
        DOUBLE_SECONDS("cast(%s as double) / 1000000.0D");

        final String sql;

        Kind(String sql) {
          this.sql = sql;
        }

        String of(String micros) {
          return sql.replace("%s", micros);
        }
      }

      /** A column built once a data set, named {@code name_<the data set's suffix>}. */
      record Column(String name, Kind kind, int tag) {}

      /** A column that belongs to no data set, such as an interval or a quantity. */
      record Free(String name, String sql) {}

      /** A zone and a data set. */
      record Cell(String zone, Data data) {}

      /** The cell the milestone's speed bar reads. */
      static final Cell G7 = new Cell(DST, Data.YEAR);

      /**
       * The cells of a lead form. A fixed zone folds to a sum, which no order of the data can
       * change, so the week, the year in time order and the ten years run under {@link #DST}
       * alone.
       */
      static final List<Cell> LEAD = List.of(new Cell(UTC, Data.YEAR),
          new Cell(FIXED, Data.YEAR), new Cell(DST, Data.WEEK),
          new Cell(DST, Data.YEAR_ORDERED), G7, new Cell(DST, Data.TEN_YEARS));

      /** A section as the run and the queries suite read it. */
      record Timed(String zone, String title, String query, Fusion fusion, BatchCheck check) {}

      /**
       * A form: one expression of a class in every cell it is timed in. {@code select} and
       * {@code where} are SQL over column names with two marks: {@code @} after the name of a
       * column of the data set, {@code #} after the name of a {@link Free} column that the
       * sections with nulls read in its null variant. A form with a {@code where} is a filter
       * that selects {@code select}; any other is a projection of {@code select} as
       * {@code v}. {@code zoned} is false for a form that reads no zone by its type: it runs
       * once, under {@link #DST}, and its title names no zone. {@code tenYears} is the batch
       * check of the form's cell of {@link Data#TEN_YEARS} under {@link #DST}, where a kernel
       * may decline every batch; every other cell is {@code KERNEL}.
       */
      record Form(String type, boolean zoned, String select, String where, List<Cell> cells,
          Fusion fusion, BatchCheck tenYears) {

        /** A {@code TIMESTAMP} form in the six cells of {@link #LEAD}. */
        static Form lead(String select, Fusion fusion, BatchCheck tenYears) {
          return new Form("TIMESTAMP", true, select, "", LEAD, fusion, tenYears);
        }

        /** A {@code TIMESTAMP} form in {@link #G7} alone. */
        static Form one(String select, Fusion fusion) {
          return new Form("TIMESTAMP", true, select, "", List.of(G7), fusion, BatchCheck.KERNEL);
        }

        /** A form's {@code TIMESTAMP_NTZ} twin, over a year in no order. */
        static Form twin(String select, Fusion fusion) {
          return new Form("TIMESTAMP_NTZ twin", false, select, "", List.of(G7), fusion,
              BatchCheck.KERNEL);
        }

        Timed at(String view, Cell cell, boolean nulls) {
          String shown = where.isEmpty() ? select : where;
          String title = type + " " + shown.replace("@", "").replace("#", "") + ", "
              + (zoned ? cell.zone() + ", " : "") + cell.data().label + ", "
              + (nulls ? NULLS : NO_NULLS);
          String query = "SELECT " + expand(select, cell.data(), nulls)
              + (where.isEmpty() ? " AS v" : "") + " FROM " + view
              + (where.isEmpty() ? "" : " WHERE " + expand(where, cell.data(), nulls));
          boolean wide = zoned && cell.zone().equals(DST) && cell.data() == Data.TEN_YEARS;
          return new Timed(cell.zone(), title, query, fusion, wide ? tenYears : BatchCheck.KERNEL);
        }
      }

      /** {@code template} with its marks replaced by the suffixes of {@code data}'s columns. */
      static String expand(String template, Data data, boolean nulls) {
        String n = nulls ? "n" : "";
        return template.replace("@", "_" + data.suffix + n).replace("#", n);
      }

      /** Every section of {@code forms}: each zone, then each null density, then each form. */
      static List<Timed> grid(String view, List<Form> forms) {
        var out = new ArrayList<Timed>();
        for (String zone : ZONES) {
          for (boolean nulls : new boolean[] {false, true}) {
            for (Form form : forms) {
              for (Cell cell : form.cells()) {
                if (cell.zone().equals(zone)) {
                  out.add(form.at(view, cell, nulls));
                }
              }
            }
          }
        }
        return out;
      }

      /**
       * The table: {@code id}; then each of {@code columns} once a data set; then {@code free};
       * then each of those columns again under its name plus {@code n}, with one row in ten
       * null. Column {@code i} of the list is null where {@code id % 10} is {@code 2 * i + 1}
       * modulo ten, so neighbours in the list are null on different rows.
       */
      static String tableSql(int rows, List<Data> dataSets, List<Column> columns,
          List<Free> free) {
        var inner = new ArrayList<String>();
        var names = new ArrayList<String>();
        for (Data data : dataSets) {
          for (Column c : columns) {
            String name = c.name() + "_" + data.suffix;
            inner.add(c.kind().of(data.microsSql(c.tag(), rows)) + " as " + name);
            names.add(name);
          }
        }
        for (Free f : free) {
          inner.add(f.sql() + " as " + f.name());
          names.add(f.name());
        }
        var outer = new ArrayList<String>();
        outer.add("id");
        outer.addAll(names);
        for (int i = 0; i < names.size(); i++) {
          outer.add("case when id % 10 = " + (2 * i + 1) % 10 + " then null else "
              + names.get(i) + " end as " + names.get(i) + "n");
        }
        return "select " + String.join(", ", outer) + " from (select id, "
            + String.join(", ", inner) + " from range(0, " + rows + "))";
      }

      /** The view's name. */
      abstract String view();

      /** The data sets the view holds. */
      abstract List<Data> dataSets();

      /** The view's columns of each data set. */
      abstract List<Column> columns();

      /** The view's columns of no data set. */
      List<Free> free() {
        return List.of();
      }

      /** Settings both sessions need before the view parses, such as a type's flag. */
      Map<String, String> settings() {
        return Map.of();
      }

      /**
       * Every section, in the order they run, with the classes a session plans them at when it
       * does or does not refuse a filter's condition in a kernel driven at 64 bits.
       */
      abstract List<Timed> timed(boolean declinesLongConditions);

      /** The view's query at {@code rows} rows. */
      final String table(int rows) {
        return tableSql(rows, dataSets(), columns(), free());
      }

      @Override
      public final void runBenchmarkSuite(String[] mainArgs) {
        stopLeftoverSessions();
        String name = view().replace('_', '-');
        SparkSession baseline = VarkaArrowSessions.createSession(
            name + "-baseline-off", false, false, "local[1]");
        clearSessions();
        SparkSession varka = VarkaArrowSessions.createSession(
            name + "-baseline-on", true, false, "local[1]");
        clearSessions();
        try {
          List<SparkSession> sessions = List.of(baseline, varka);
          for (SparkSession session : sessions) {
            settings().forEach((key, value) -> session.conf().set(key, value));
          }
          // The refusal as the Varka session resolves it, as VarkaBooleanOutputBenchmark reads it.
          List<Timed> all = timed(
              VarkaSessionEmit.of(varka.sessionState().conf()).declineTwoLaneConditions());
          for (String zone : ZONES) {
            underZone(zone, sessions, () -> {
              for (Timed t : all) {
                if (t.zone().equals(zone)) {
                  requireBaseline(varka, t.title(), t.query(), t.fusion(), t.check());
                }
              }
            });
          }
          for (String zone : ZONES) {
            underZone(zone, sessions, () -> {
              for (Timed t : all) {
                if (t.zone().equals(zone)) {
                  time(baseline, varka, t);
                }
              }
            });
          }
        } finally {
          baseline.stop();
          varka.stop();
        }
      }

      /** Runs {@code body} with {@code zone} set and the table cached on every session. */
      private void underZone(String zone, List<SparkSession> sessions, Runnable body) {
        for (SparkSession session : sessions) {
          session.conf().set(SQLConf.SESSION_LOCAL_TIMEZONE().key(), zone);
          session.sql(table(NUM_ROWS)).createOrReplaceTempView(view());
          VarkaArrowSessions.cache(session, view());
        }
        try {
          body.run();
        } finally {
          for (SparkSession session : sessions) {
            session.catalog().uncacheTable(view());
          }
        }
      }

      private void time(SparkSession baseline, SparkSession varka, Timed t) {
        section(t.title(), () -> {
          VarkaBenchmark benchmark = benchmark(
              t.title() + " over " + NUM_ROWS + " Arrow-cached rows", NUM_ROWS).timed().build();
          benchmark.addCase("spark row path (varka off)", i -> noop(baseline.sql(t.query())));
          benchmark.addCase("varka on", i -> noop(varka.sql(t.query())));
          runCases(benchmark);
        });
      }
    }

**Visibility.** Every member above with no modifier is package-private. The classes and the
suites of this row are in the package `org.apache.spark.sql.execution.benchmark`, so each reads
them; Scala reads a Java package-private member from the same package, as
`VarkaTimestampNtzFieldsBaselineQueriesSuite` reads `MAX_DAYS`. `Fusion` and `BatchCheck` are
the public nested enums of `VarkaSqlBenchmarkBase`, in scope here by inheritance.

**What the methods call.** `stopLeftoverSessions`, `clearSessions`, `noop` and
`requireBaseline(SparkSession, String, String, Fusion, BatchCheck)` are
`VarkaSqlBenchmarkBase`'s. `section`, `benchmark` and `runCases` are `VarkaBenchmarkBase`'s.
`createSession(appName, varkaEnabled, warmupEnabled, master)` and `cache(session, view)` are
`VarkaArrowSessions`'s; Java passes all four arguments. The session zone is a runtime
setting, `SQLConf.SESSION_LOCAL_TIMEZONE()`, whose key is `spark.sql.session.timeZone`.

### 4.3 The zones, the data sets and the cells

| zone | what it is | why |
| :--- | :--- | :--- |
| `UTC` | no offset | the compiler folds it: a zoned kernel is its `TIMESTAMP_NTZ` twin |
| `+01:00` | a fixed offset | the fold with a sum |
| `America/Los_Angeles` | two changes a year | the zone of G7's bar and of the zone family's probes |

| data set | suffix | instants | order | changes of `America/Los_Angeles` in it |
| :--- | :--- | :--- | :--- | ---: |
| a week | `w` | 1717200000 s (1 June 2024 00:00 UTC), 604800 s | none | 0 |
| a year in time order | `yo` | 1704067200 s (1 January 2024 00:00 UTC), 31536000 s (365 days) | rising | 2 |
| a year in no order | `y` | the same range | none | 2 |
| ten years in no order | `d` | 1577836800 s (1 January 2020 00:00 UTC), 315619200 s (3653 days) | none | 20 |

A value is `(start + second) * 1000000 + micro`, a long of microseconds. `second` is
`pmod(xxhash64(id, tag), span)` in no order and `(id * span) div rows` in time order. `micro`
is `pmod(xxhash64(id, tag + 100), 1000000)`. At 2,000,000 rows a step in time order is about 16
seconds, so a column in time order never decreases. The largest value is under 1.9e15, far
from the ends of a long, and `id * span` is under 6.4e13.

The cache holds 10,000 rows a batch, so 2,000,000 rows are 200 batches. A batch of the year in
time order spans 1.8 days and at most one change. A batch in no order spans its whole data
set: 0, 2 or 20 changes. The row's "ten years in time order" is not built: a batch of it would
span 18 days and at most one change, the case that the year in time order already is.

**The cells.** A lead form runs in six cells: `UTC` and `+01:00` over a year in no order, and
`America/Los_Angeles` over each of the four data sets. A fixed zone is not run over the other
data sets: under it the row path adds one offset and a kernel folds, so the order of the data
changes neither. Every other form runs in G7's cell alone. Every form runs with no nulls and
with one row in ten null. So a lead form is 12 sections and any other form is 2.

### 4.4 The forms of the five microsecond classes

Columns: `ts` and `ts2` are `TIMESTAMP`, `ntz` and `ntz2` are `TIMESTAMP_NTZ` and hold the same
microseconds as `ts` and `ts2` read in UTC, `d` is the UTC date of `ts`, `us`, `ms` and `sec`
are `BIGINT` counts since the epoch, `dbl` is `sec` with its fraction as a `DOUBLE`. In a form,
`@` marks a column of the data set and `#` a free column (4.2).

| class and view | lead forms (12 sections each) | forms in G7's cell (2 sections each) | twins, all `Fusion.WHOLE` but the first of the zone-free class (2 sections each) | sections |
| :--- | :--- | :--- | :--- | ---: |
| `VarkaTimestampBaselineBenchmark`, `varka_ts` | none; see 4.5: `ts@ < ts2@` as a filter, `greatest(ts@, ts2@)` and `CASE WHEN ts@ < ts2@ THEN ts@ ELSE ts2@ END` run in four cells | `unix_seconds(ts@)`, `unix_millis(ts@)`, `unix_micros(ts@)`, `timestamp_seconds(sec@)`, `timestamp_millis(ms@)`, `timestamp_micros(us@)`, `hash(ts@)`, `xxhash64(ts@)` | `ntz@ < ntz2@` as a filter (`WHOLE`, and `NONE` under the two-lane refusal), `greatest(ntz@, ntz2@)`, `CASE WHEN ntz@ < ntz2@ THEN ntz@ ELSE ntz2@ END`, `hash(ntz@)`, `xxhash64(ntz@)` | 50 |
| `VarkaTimestampFieldsBaselineBenchmark`, `varka_ts_fields` | `hour(ts@)`, `minute(ts@)`, `second(ts@)` | `year(ts@)`, `quarter(ts@)`, `month(ts@)`, `day(ts@)`, `dayofyear(ts@)`, `dayofweek(ts@)`, `weekday(ts@)`, `weekofyear(ts@)`, `last_day(ts@)`, `next_day(ts@, 'MONDAY')`, `trunc(ts@, 'MONTH')`, `time_bucket(INTERVAL '15' MINUTE, ts@)` | each of the 15 forms but `time_bucket`, over `ntz@`: 14 | 88 |
| `VarkaTimestampCastsBaselineBenchmark`, `varka_ts_casts` | `CAST(ts@ AS DATE)`, `CAST(ntz@ AS TIMESTAMP)`, `CAST(d@ AS TIMESTAMP)` | `CAST(ts@ AS TIMESTAMP_NTZ)`, `CAST(ts@ AS TIME)`, `CAST(ts@ AS BIGINT)`, `CAST(ts@ AS DOUBLE)`, `CAST(ts@ AS DECIMAL(20, 6))`, `CAST(sec@ AS TIMESTAMP)`, `CAST(dbl@ AS TIMESTAMP)` | `CAST(ntz@ AS DATE)` | 52 |
| `VarkaTimestampArithmeticBaselineBenchmark`, `varka_ts_arith` | `ts@ + INTERVAL '1' DAY` | `ts@ - ts2@`, `ts@ + dt#`, `ts@ + INTERVAL '1' MONTH`, `ts@ + INTERVAL '1' HOUR`, `timestampadd(HOUR, q#, ts@)`, `timestampadd(DAY, q#, ts@)`, `timestampadd(MONTH, q#, ts@)` | `ntz@ + INTERVAL '1' DAY`, `ntz@ - ntz2@`, `ntz@ + dt#`, `ntz@ + INTERVAL '1' MONTH`, `timestampadd(HOUR, q#, ntz@)`, `timestampadd(DAY, q#, ntz@)`, `timestampadd(MONTH, q#, ntz@)` | 40 |
| `VarkaTimestampZoneFunctionsBaselineBenchmark`, `varka_ts_zone` | `date_trunc('DAY', ts@)` | `date_trunc('HOUR', ts@)`, `date_trunc('MONTH', ts@)`, `from_utc_timestamp(ts@, 'Europe/Amsterdam')`, `to_utc_timestamp(ts@, 'Europe/Amsterdam')`, `convert_timezone('Europe/Amsterdam', ntz@)`, `months_between(ts@, ts2@)`, `make_timestamp(y#, mo, dd, hh, mi, s)`, `try_make_timestamp(y#, mo, dd, hh, mi, s)` | none | 28 |

The five classes hold 258 sections at a width. `python3` on the model in this plan's scratch
gave the counts; the queries suite of each class asserts its count.

**Why these forms.**

- *Zone-free.* The comparison, `greatest` and the choice are what row 656 fuses. The six
  conversions with the epoch and the two hashes are row 667's. `IN`, the null tests and
  `least` have no section: row 656's sheet classes this file's sections by a rule of two
  clauses (a `WHERE` over the two timestamp columns; a select list of `greatest`, `least`,
  `CASE`, `if` or `coalesce`), and a filter over one column and literals fits neither. Row
  667 owns `in_set`'s section. `coalesce(ts, ts2)` over the columns with no null is folded to
  `ts` by the optimizer and would time nothing.
- *Fields.* `hour`, `minute` and `second` are row 657's first kernels and the zone family's
  sections (`VARKA-657.md` 6). The others are row 668's list. `dayofmonth` is `day`, and
  `extract`, `date_part` and `datepart` are rewritten to the field's own expression before a
  plan is made, so they have no section of their own.
- *Casts.* The three lead forms are the three rules of a zone (`VARKA-657.md` 2.4): an instant
  to a local date, a local time to an instant, a local midnight to an instant. The seven other
  forms are one a kernel family of row 669: to and from the other timestamp, to `TIME`, to an
  integer, to a fraction, to a decimal, from an integer, from a fraction. No value raises: each
  target holds every value of 2020 to 2030.
- *Arithmetic.* `ts + INTERVAL '1' DAY` is the kept-offset rule's first kernel (row 690).
  `ts - ts2` reads no zone. The interval column `dt` is within 500 days and the quantity `q`
  within 1000, so no sum leaves the range of a timestamp.
- *Zone functions.* Row 670's list. `Europe/Amsterdam` is a literal zone with changes that is
  not the session's. `convert_timezone` takes and returns `TIMESTAMP_NTZ` (`VARKA-654.md`
  4.6); its two-argument form reads the session zone as the source. `make_timestamp_ltz` is
  `make_timestamp`'s own expression and has no section.

**The free columns.**

| class | name | SQL | values |
| :--- | :--- | :--- | :--- |
| arithmetic | `dt` | `make_dt_interval(cast(pmod(xxhash64(id, 7), 1001) - 500 as int), 0, 0, cast(cast(pmod(xxhash64(id, 107), 86400000000) as decimal(18, 0)) * 0.000001 as decimal(18, 6)))` | a day-time interval of -500 to 500 days and a time of day |
| arithmetic | `q` | `cast(pmod(xxhash64(id, 8), 2001) - 1000 as int)` | an `INT` of -1000 to 1000 |
| zone functions | `y` | `cast(2024 - pmod(id, 2) as int)` | 2023 or 2024 |
| zone functions | `mo` | `cast(1 + pmod(xxhash64(id, 21), 12) as int)` | 1 to 12 |
| zone functions | `dd` | `cast(1 + pmod(xxhash64(id, 22), 28) as int)` | 1 to 28 |
| zone functions | `hh` | `cast(pmod(xxhash64(id, 23), 24) as int)` | 0 to 23 |
| zone functions | `mi` | `cast(pmod(xxhash64(id, 24), 60) as int)` | 0 to 59 |
| zone functions | `s` | `cast(pmod(xxhash64(id, 25), 60000000) * 0.000001 as decimal(16, 6))` | 0 to under 60, with microseconds |

`make_timestamp` over these reaches local times inside the hour that `America/Los_Angeles`
skips on 12 March 2023 and 10 March 2024; Spark moves such a time forward and raises nothing.

### 4.5 The zone-free class, whole, and what row 656 greps

`VARKA-656.md` 5.2 finds this class by a grep (its B0 line 12) and checks its shape (lines 13
to 14b): exactly one file among `<b>/Varka*BaselineBenchmark.java` holds one of
`TimestampType`, `TIMESTAMP_LTZ`, `timestamp_micros`, `TIMESTAMP '` and also `greatest(`; that
file holds `record Section(String title, String query, Fusion fusion)` once, `boolean
declinesLongConditions` at least once, and both `Fusion.NONE` and `Fusion.WHOLE`. So this class
is written in the direct style of `VarkaTimestampNtzBaselineBenchmark`, with its own `Section`
record and a `sections(boolean declinesLongConditions)`. It is the only class of this row
with the text `greatest(`.

`<b>/VarkaTimestampBaselineBenchmark.java`, after the 16 lines of the license header:

    package org.apache.spark.sql.execution.benchmark;

    import java.util.ArrayList;
    import java.util.LinkedHashMap;
    import java.util.List;
    import java.util.Map;

    /**
     * The baseline for the expressions over a {@code TIMESTAMP} that read no time zone, before
     * Varka has a lane for the type (VARKA-655): Spark's row path over an Arrow-cached table,
     * so that the rows that fuse them report what they change against a committed file.
     *
     * <p>The comparison of two timestamps as a filter, the greater of the two and the choice
     * between them run under each zone of {@link VarkaTimestampBaselineBase#ZONES} over a year
     * of instants in no order, and under the zone with changes over a year in time order too.
     * No form here reads the zone, so the zones and the order are controls: they must not
     * move a ratio. The six conversions with the epoch and the two hashes run under the zone
     * with changes over a year in no order, the cell the milestone's speed bar reads. Five
     * forms have a {@code TIMESTAMP_NTZ} twin over the same instants read in UTC; the twins
     * fuse today, and their {@code varka on} arm times a kernel.
     *
     * <p>A baseline is only one while nothing fuses. Every {@code TIMESTAMP} form is listed
     * {@code Fusion.NONE} in {@link #sections(boolean)}; the run and
     * {@code VarkaTimestampBaselineQueriesSuite} fail when a section's plan has another
     * class. A row that fuses a form sets the form's class there: a filter over the long lane
     * takes {@code filter}, which is {@code Fusion.NONE} in a session that refuses a filter's
     * condition in a kernel driven at 64 bits ({@code declinesLongConditions}, VARKA-503), the
     * 128-bit companion's host by default; a projection is never refused and takes
     * {@code Fusion.WHOLE}.
     *
     * <p>To run this benchmark, on a GitHub runner (the results come back as an artifact):
     * <pre>
     *   gh workflow run benchmark.yml -f class=VarkaTimestampBaselineBenchmark -f jdk=25 \
     *     -f expected-cpu="AMD EPYC 7763"
     *   (and again with -f extra-java-options=-XX:MaxVectorSize=16 for the 128-bit companion)
     * </pre>
     */
    public class VarkaTimestampBaselineBenchmark extends VarkaTimestampBaselineBase {

      /** The table's view name. */
      static final String VIEW = "varka_ts";

      /**
       * A section: its title, its query, and how much of it the Varka session fuses today, the
       * {@link VarkaSqlBenchmarkBase.Fusion} class the run checks its plan against before timing.
       */
      record Section(String title, String query, Fusion fusion) {}

      /** The six conversions with the epoch, each over the column of its unit. */
      private static final List<String> EPOCH = List.of(
          "unix_seconds(ts@)", "unix_millis(ts@)", "unix_micros(ts@)",
          "timestamp_seconds(sec@)", "timestamp_millis(ms@)", "timestamp_micros(us@)");

      /**
       * Every section by its zone, in the order they run, with the classes a session plans
       * them at when it does or does not refuse a filter's condition in a kernel driven at 64
       * bits, which a comparison's condition over the long lane is.
       */
      static Map<String, List<Section>> sections(boolean declinesLongConditions) {
        // A fused filter over the long lane: the row path's in a session that refuses it.
        Fusion filter = declinesLongConditions ? Fusion.NONE : Fusion.WHOLE;
        // How much of each TIMESTAMP form fuses. A row that fuses a form sets its class here.
        Fusion comparison = Fusion.NONE;
        Fusion pick = Fusion.NONE;
        Fusion choice = Fusion.NONE;
        Fusion epoch = Fusion.NONE;
        Fusion hashes = Fusion.NONE;
        String ltz = "TIMESTAMP";
        String twin = "TIMESTAMP_NTZ twin";
        var out = new LinkedHashMap<String, List<Section>>();
        for (String zone : ZONES) {
          boolean changes = zone.equals(DST);
          var list = new ArrayList<Section>();
          for (boolean nulls : new boolean[] {false, true}) {
            for (Data data : changes ? List.of(Data.YEAR, Data.YEAR_ORDERED) : List.of(Data.YEAR)) {
              var cell = new Cell(zone, data);
              add(list, ltz, true, cell, nulls, "ts@", "ts@ < ts2@", comparison);
              add(list, ltz, true, cell, nulls, "greatest(ts@, ts2@)", "", pick);
              add(list, ltz, true, cell, nulls,
                  "CASE WHEN ts@ < ts2@ THEN ts@ ELSE ts2@ END", "", choice);
            }
            if (changes) {
              for (String form : EPOCH) {
                add(list, ltz, true, G7, nulls, form, "", epoch);
              }
              add(list, ltz, true, G7, nulls, "hash(ts@)", "", hashes);
              add(list, ltz, true, G7, nulls, "xxhash64(ts@)", "", hashes);
              add(list, twin, false, G7, nulls, "ntz@", "ntz@ < ntz2@", filter);
              add(list, twin, false, G7, nulls, "greatest(ntz@, ntz2@)", "", Fusion.WHOLE);
              add(list, twin, false, G7, nulls,
                  "CASE WHEN ntz@ < ntz2@ THEN ntz@ ELSE ntz2@ END", "", Fusion.WHOLE);
              add(list, twin, false, G7, nulls, "hash(ntz@)", "", Fusion.WHOLE);
              add(list, twin, false, G7, nulls, "xxhash64(ntz@)", "", Fusion.WHOLE);
            }
          }
          out.put(zone, list);
        }
        return out;
      }

      private static void add(List<Section> out, String type, boolean zoned, Cell cell,
          boolean nulls, String select, String where, Fusion fusion) {
        Timed t = new Form(type, zoned, select, where, List.of(cell), fusion, BatchCheck.KERNEL)
            .at(VIEW, cell, nulls);
        out.add(new Section(t.title(), t.query(), t.fusion()));
      }

      @Override
      String view() {
        return VIEW;
      }

      @Override
      List<Data> dataSets() {
        return List.of(Data.YEAR, Data.YEAR_ORDERED);
      }

      @Override
      List<Column> columns() {
        return List.of(new Column("ts", Kind.TS, 0), new Column("ts2", Kind.TS, 1),
            new Column("ntz", Kind.NTZ, 0), new Column("ntz2", Kind.NTZ, 1),
            new Column("us", Kind.MICROS, 0), new Column("ms", Kind.MILLIS, 0),
            new Column("sec", Kind.SECONDS, 0));
      }

      @Override
      List<Timed> timed(boolean declinesLongConditions) {
        var out = new ArrayList<Timed>();
        sections(declinesLongConditions).forEach((zone, list) -> {
          for (Section s : list) {
            out.add(new Timed(zone, s.title(), s.query(), s.fusion(), BatchCheck.KERNEL));
          }
        });
        return out;
      }
    }

Its first section is titled `TIMESTAMP ts < ts2, UTC, a year in no order, no nulls` and runs
`SELECT ts_y FROM varka_ts WHERE ts_y < ts2_y`. Its last is `TIMESTAMP_NTZ twin xxhash64(ntz), a
year in no order, one row in ten null` and runs `SELECT xxhash64(ntz_yn) AS v FROM varka_ts`.
`ts_y` and `ts2_y` are independent hashes, so the comparison keeps about half the rows; in
`ts_yo` and `ts2_yo` the two columns hold the same second and differ in their microseconds, so
it keeps about half there too.

### 4.6 How a class says that nothing fuses, and how a later row turns a section

- **Nothing fuses.** Every `TIMESTAMP` form is `Fusion.NONE`. In the zone-free class that is
  the five local variables `comparison`, `pick`, `choice`, `epoch` and `hashes` of `sections`.
  In the other classes it is the second argument of each `Form.lead(...)` and `Form.one(...)`
  line of `forms()`. The run checks every section before it times any
  (`requireBaseline`), and the queries suite checks every section in every Build
  (`requireSection`), with the two-lane refusal off and on. A plan with a Varka node under a
  section listed `NONE` fails both, with the text `<title> is listed as NONE but plans as
  <class>; a row that changes how it fuses lists its new class: <query>` and the plan's tree.
- **A later row turns a zone-free form** by one edit of its variable. Row 656's part B, step
  9: `Fusion comparison = filter;`, `Fusion pick = Fusion.WHOLE;` and `Fusion choice =
  Fusion.WHOLE;`. Its rule, "every other section stays `Fusion.NONE`", leaves `epoch` and
  `hashes`, which row 667 turns.
- **A later row turns a zoned form** by its line of `forms()`. Row 657's part B, step 6, makes
  `Form.lead("hour(ts@)", Fusion.NONE, BatchCheck.KERNEL)` into `Form.lead("hour(ts@)",
  Fusion.WHOLE, BatchCheck.ALL_FALL_BACK)`. The third argument is the batch check of the
  form's ten-year cell under `America/Los_Angeles`; every other cell is `KERNEL`, which is the
  table of that step. Row 686, whose table form covers the ten years, sets it back to
  `BatchCheck.KERNEL`. A form of `Form.one` has no ten-year cell and takes `Fusion.WHOLE` alone.
- **A later row measures** by dispatching the class again on its pull request and committing
  the four files over this row's (4.9). `python3 dev/varka_bench_diff.py --git HEAD <file>`
  then prints each section against the baseline.
- **A later row adds a form** in a pull request of its own, with nothing fused, before its
  kernel: one `Form.one(...)` line and the suite's section count plus two.
- **The class comment** names every form that fuses once a row turns one: the row adds the
  sentence "`<form>` fuses (VARKA-<n>)." to the paragraph that begins "A baseline is only one".

### 4.7 The twins

A twin is the form over the `TIMESTAMP_NTZ` column of the same data set. `ntz` is built from
the same microseconds as `ts` by a sum that reads no zone, so under `UTC` a twin and its form
answer the same. A twin's title starts `TIMESTAMP_NTZ twin` and names no zone; it runs once, in
the pass of `America/Los_Angeles`, over a year in no order. Its difference from the form's
section in G7's cell is the zone's price, on the row path today and in a kernel later.

Each twin fuses today (`Fusion.WHOLE`; the filter of the zone-free class is `NONE` under the
two-lane refusal), because its expression is a row of `sql/varka/coverage.json`. This script,
saved as `$T/twins.py` and run from the root, checks all 28, the 27 of the microsecond classes
and `least(ntz, ntz2)` of the nanosecond zone-free class:

    import json
    twins = """ntz < ntz2
    greatest(ntz, ntz2)
    least(ntz, ntz2)
    CASE WHEN ntz < ntz2 THEN ntz ELSE ntz2 END
    hash(ntz)
    xxhash64(ntz)
    hour(ntz)
    minute(ntz)
    second(ntz)
    year(ntz)
    quarter(ntz)
    month(ntz)
    day(ntz)
    dayofyear(ntz)
    dayofweek(ntz)
    weekday(ntz)
    weekofyear(ntz)
    last_day(ntz)
    next_day(ntz, 'MONDAY')
    trunc(ntz, 'MONTH')
    CAST(ntz AS DATE)
    ntz + INTERVAL '1' DAY
    ntz - ntz2
    ntz + dt
    ntz + INTERVAL '1' MONTH
    timestampadd(HOUR, i, ntz)
    timestampadd(DAY, i, ntz)
    timestampadd(MONTH, i, ntz)""".splitlines()
    rows = set()
    def walk(o):
        if isinstance(o, dict):
            if "sql" in o:
                rows.add(o["sql"])
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)
    walk(json.load(open("sql/varka/coverage.json")))
    missing = [t.strip() for t in twins if t.strip() not in rows]
    print(len(twins), "twins,", len(missing), "missing", *missing)

It prints `28 twins, 0 missing` on the plan's commit. A form with no fused twin
(`time_bucket`, the conversions with the epoch, the casts to a `TIMESTAMP`, the zone
functions) has none. Two twins of the nanosecond classes are spelled otherwise than their
row: `extract(HOUR FROM ntz)` is the row `hour(ntz)` and `ntz :: date` is the row `CAST(ntz AS
DATE)`; the script checks them under the row's spelling. The script names the columns as the
coverage file does, `i` for the classes' `q`.

### 4.8 The four nanosecond classes

**What Spark accepts today.** The flag `spark.sql.timestampNanosTypes.enabled` is internal.
Its default follows `Utils.isTesting`, asked each time the flag is read, and
`BenchmarkBase.main` turns that on for every benchmark JVM, the runner's included; each
class sets the flag anyway, on both sessions through `settings()`, so that it rests on no
default. The `TIME` type's flag, which the casts class sets, differs in one thing: its
default is `Utils.isTesting` as it was when `SQLConf` first loaded, so it is on only if that
load comes after `BenchmarkBase.main` set the property. That order is likely and unproven,
which is one more reason the class sets it.

The golden files `sql/core/src/test/resources/sql-tests/results/
timestamp-ltz-nanos.sql.out` and `timestamp-ntz-nanos.sql.out` were read query by query. Spark
answers, over both types: the comparisons, `greatest`, `least`, `CASE`, the calendar and clock
fields, `extract(SECOND FROM ...)` as a `DECIMAL(11, 9)`, the casts to and from `DATE`, the
microsecond timestamps and each precision, `+` and `-` of a day-time and of a year-month
interval, `timestampadd`, `date_trunc`, `from_utc_timestamp`, `to_utc_timestamp`, `unix_nanos`,
`unix_micros`, and `timestamp_nanos` of a `BIGINT`. It rejects `ts - ts`
(`UNSUPPORTED_FEATURE.TIMESTAMP_NANO...`), `months_between`, `timestampdiff` and `time_trunc`,
and `convert_timezone` over the zoned type. No class holds a rejected form.

**What they can run before row 699.** Everything: the registry does not know the types
(`VarkaTypes.idOf` returns null), so every nanosecond section is `Fusion.NONE` and both arms
run the row path over the cache's struct of two vectors. The classes need no kernel, no
census type and no decision of row 660.

**The columns.** Part F adds three kinds to `Kind`, each over the microseconds `%s` with
`pmod(xxhash64(id, 200), 1000)` nanoseconds added:

    NANOS("(%s * 1000L + pmod(xxhash64(id, 200), 1000L))"),
    LTZ9("timestamp_nanos(%s * 1000L + pmod(xxhash64(id, 200), 1000L))"),
    NTZ9("cast(timestamp_nanos(%s * 1000L + pmod(xxhash64(id, 200), 1000L))"
        + " as timestamp_ntz(9))");

`NANOS` is a `BIGINT` of nanoseconds, under 1.9e18. `LTZ9` is a `TIMESTAMP_LTZ(9)`. `NTZ9` is
its local time in the session zone, a `TIMESTAMP_NTZ(9)`; the table is cached once a zone, and
a `TIMESTAMP_NTZ(9)` form runs in the pass of `America/Los_Angeles` alone, so its column holds
that zone's local times. `ts` and `ts2` are `LTZ9`, `nt` and `nt2` are `NTZ9`, `ns` is `NANOS`,
`tsm` is a microsecond `TIMESTAMP` (`Kind.TS`), and `ntz` and `ntz2` are the microsecond
`TIMESTAMP_NTZ` of the twins, as in 4.4. Every view keeps `ntz`, which the trait's control
reads.

**The forms.** A form over `TIMESTAMP_LTZ(9)` has the type text `TIMESTAMP_LTZ(9)` and is
zoned. A form over `TIMESTAMP_NTZ(9)` has the type text `TIMESTAMP_NTZ(9)`, is not zoned, and
runs in G7's cell as a twin does. Part F adds three factories for them to `Form` (5.6).
The microsecond twins are `Form.twin(...)` as before. No nanosecond class holds the text
`greatest(`, `hour(`, `AS DATE` or `America/Los_Angeles`: 4.12 says why, and so the forms spell
`least(`, `extract(HOUR FROM ...)` and `:: date`.

| class and view | `TIMESTAMP_LTZ(9)` lead forms (12 sections each) | `TIMESTAMP_LTZ(9)` forms in G7's cell (2 each) | `TIMESTAMP_NTZ(9)` forms (2 each) | microsecond twins, `WHOLE` (2 each) | sections |
| :--- | :--- | :--- | :--- | :--- | ---: |
| `VarkaTimestampNanosBaselineBenchmark`, `varka_ts_nanos` | none | `ts@ < ts2@` as a filter, `least(ts@, ts2@)`, `CASE WHEN ts@ < ts2@ THEN ts@ ELSE ts2@ END`, `unix_nanos(ts@)`, `unix_micros(ts@)`, `timestamp_nanos(ns@)` | `nt@ < nt2@` as a filter, `least(nt@, nt2@)`, `CASE WHEN nt@ < nt2@ THEN nt@ ELSE nt2@ END`, `unix_nanos(nt@)` | `ntz@ < ntz2@` as a filter (`NONE` under the two-lane refusal), `least(ntz@, ntz2@)`, `CASE WHEN ntz@ < ntz2@ THEN ntz@ ELSE ntz2@ END` | 26 |
| `VarkaTimestampNanosFieldsBaselineBenchmark`, `varka_ts_nanos_fields` | `extract(HOUR FROM ts@)` | `extract(MINUTE FROM ts@)`, `extract(SECOND FROM ts@)`, `second(ts@)`, `year(ts@)`, `quarter(ts@)`, `month(ts@)`, `day(ts@)`, `dayofyear(ts@)`, `dayofweek(ts@)`, `weekday(ts@)`, `weekofyear(ts@)`, `last_day(ts@)`, `next_day(ts@, 'MONDAY')` | `extract(HOUR FROM nt@)`, `extract(SECOND FROM nt@)`, `year(nt@)` | `extract(HOUR FROM ntz@)`, `second(ntz@)`, `year(ntz@)` | 50 |
| `VarkaTimestampNanosCastsBaselineBenchmark`, `varka_ts_nanos_casts` | `ts@ :: date` | `CAST(ts@ AS TIMESTAMP_LTZ)`, `CAST(ts@ AS TIMESTAMP_LTZ(7))`, `CAST(tsm@ AS TIMESTAMP_LTZ(9))`, `CAST(ts@ AS TIMESTAMP_NTZ(9))`, `CAST(nt@ AS TIMESTAMP_LTZ(9))`, `CAST(d@ AS TIMESTAMP_LTZ(9))` | `nt@ :: date`, `CAST(nt@ AS TIMESTAMP_NTZ)`, `CAST(nt@ AS TIMESTAMP_NTZ(7))`, `CAST(ntz@ AS TIMESTAMP_NTZ(9))`, `CAST(d@ AS TIMESTAMP_NTZ(9))` | `ntz@ :: date` | 36 |
| `VarkaTimestampNanosFunctionsBaselineBenchmark`, `varka_ts_nanos_fn` | `ts@ + INTERVAL '1' DAY` | `ts@ + INTERVAL '1' MONTH`, `ts@ + dt#`, `timestampadd(NANOSECOND, q#, ts@)`, `timestampadd(DAY, q#, ts@)`, `date_trunc('DAY', ts@)`, `date_trunc('MICROSECOND', ts@)`, `from_utc_timestamp(ts@, 'Europe/Amsterdam')`, `to_utc_timestamp(ts@, 'Europe/Amsterdam')` | `nt@ + INTERVAL '1' DAY`, `nt@ + INTERVAL '1' MONTH`, `timestampadd(NANOSECOND, q#, nt@)`, `date_trunc('DAY', nt@)`, `convert_timezone('Europe/Amsterdam', nt@)` | `ntz@ + INTERVAL '1' DAY`, `ntz@ + INTERVAL '1' MONTH` | 42 |

The four classes hold 154 sections at a width. `second(ntz@)` is the twin of both
`second(ts@)` and of the seconds with a fraction, since the microsecond `second` with its
fraction as a decimal does not fuse yet (milestone 8's row 340 leaves it to row 351).
`extract(HOUR FROM ntz@)` is `hour(ntz)`: Spark replaces an `extract` by the field's own
expression before a plan is made.

The precision is 9 but for the two casts to precision 7: the value's layout is one for the
three precisions (`m9/PLAN.md` 2.5), so a second precision would time the same row path.

**The golden files hold literals, not columns.** They show that Spark's analyzer accepts each
form's expression over the type; they do not run it over a cached column. The queries suite's
first test is the only check of that, so 5.6 says exactly what the builder does with each way
it can fail:

| what fails | where it shows | what the builder does |
| :--- | :--- | :--- |
| the table itself: `spark` throws while `withZone` builds or caches the view, before any section | the first test, with no section's title in the clue | stop. Report the exception's class and message, the zone, and the column kinds of the class. A kind that Spark cannot build or cache is not a form to remove |
| a form on the plain session: `spark.sql(t.query).collect()` throws an `AnalysisException`, or an error whose class starts `UNSUPPORTED_FEATURE` | the first test, with the section's title in the clue | remove the form by the rule below, and go on |
| a form on the Varka session only: `spark` answers and `varkaSpark` throws, or answers differently | the first test's `checkAnswer`, with the section's title in the clue | stop. Report: the session (`varkaSpark`), the exception's class and message or the differing rows, the section's title (it holds the zone), and its query. The engine must decline a column it cannot read; the fix is a pull request of its own, before this part |
| anything else | any test | stop, with the same report |

**Removing a form** that Spark rejects on the build's master changes exactly these, and the
builder reports each number it changed:

1. the form's line in `forms()`, that form alone. Its form over the class's other type goes
   only if Spark rejects that one too, by a failure of its own;
2. the suite's `sectionCount`: less 2 for each form removed (a lead form is never removed:
   see below);
3. the class's `N` wherever a sheet uses it: 4.9's checks then read the new `N` and `2 * N`.
   The notes of fused twins, and so the dry runs' counts, do not change: a twin is a
   microsecond form and is not removed by this rule;
4. the class comment gains the sentence "`<form>` is not here: Spark rejects it (`<error
   class>`).";
5. section 11 gains the line "`<class>`: `<form>` removed, Spark rejects it (`<error class>`);
   N is `<new N>`, not 4.8's `<old N>`." The tables of 4.8 and 4.9 are not rewritten: they
   are the plan, and section 11 is the record.

If the removed form is a lead form, the class has no lead: that is a stop, since the grid of
the class is then not what rows 699 and 673 read.

### 4.9 The results files, and who does what

A class `<C>` has four files in `sql/core/benchmarks`:

    <C>-jdk25-runner-epyc7763-results.txt
    <C>-jdk25-runner-epyc7763-provenance.txt
    <C>-jdk25-runner-epyc7763-128bit-results.txt
    <C>-jdk25-runner-epyc7763-128bit-provenance.txt

They come from two dispatches of `.github/workflows/benchmark.yml` on a GitHub runner whose CPU
is an AMD EPYC 7763: one at the runner's 256 bits and one with `-XX:MaxVectorSize=16`. The
workflow writes a class's results under the plain name `<C>-jdk25-results.txt` with
`<C>-jdk25-provenance.txt` beside it, and returns them in the run's artifact,
`benchmark-results-25-2.13.tar`. The lead renames them. The builder never pushes and never
dispatches.

**The builder hands the lead**, after its first commit of a part: the branch; the commit; the
classes to dispatch, by full name; the part's title.

**The lead does**, for a part and each of its classes:

    gh workflow run benchmark.yml --repo vecbricks/varka --ref master -f pr=<n> \
      -f create-commit=false -f class=org.apache.spark.sql.execution.benchmark.<C> -f jdk=25 \
      -f expected-cpu="AMD EPYC 7763"
    gh workflow run benchmark.yml --repo vecbricks/varka --ref master -f pr=<n> \
      -f create-commit=false -f class=org.apache.spark.sql.execution.benchmark.<C> -f jdk=25 \
      -f expected-cpu="AMD EPYC 7763" -f extra-java-options=-XX:MaxVectorSize=16

after it has pushed the branch and opened the pull request `<n>` as a draft. Without `-f
pr=<n>` the workflow measures the default branch. A run that fails its CPU check is dispatched
again; nothing else is relaxed. From each run's artifact the lead takes the two files of
`sql/core/benchmarks` and renames `<C>-jdk25-` to `<C>-jdk25-runner-epyc7763-` for the first
run and to `<C>-jdk25-runner-epyc7763-128bit-` for the second.

**The builder gets back** one directory, `$IN`, that holds the four files of each class under
the names above; a file `$IN/minutes.txt` with one line a run, `<class> <width> <minutes>`,
the minutes of the run's step "Run benchmarks" from its job log; and the pull request's
number.

The rename is the lead's for every later dispatch of these classes too (rows 656, 657, 687 and
after): the artifact's two names are the same at both widths, so the two runs' files collide
in one directory until they are renamed.

**A run that dies on a twin.** The laptop is AVX-512 and the runner is AVX2, so a twin's
kernel may hand batches to the row path at 128 bits there and nowhere else. The run then
stops before any timing with `<title> is WHOLE but its varka on arm would time the row path`.
The builder's dry runs under `-XX:UseAVX=2` (5.0) are meant to show this first. If a dispatch
still dies so, the lead dispatches that class and width again with `-f
extra-java-options="-XX:MaxVectorSize=16 -Dvarka.baseline.fuseCheck=false"`, which lifts the
check and writes, for that twin, a note that ends `to another path)` in place of the usual
one. The builder then expects the usual notes less that twin's two, counts the lines `grep -c
"but the arm times the row path on" $W` as the rest, records the twin in section 11, and the
lead files a row for the kernel. No `TIMESTAMP` section may have a note in either case.

**The builder checks each file before it commits it** (`W` is a results file, `V` its
provenance file, `N` the class's section count of 4.4 or 4.8):

| check | command | must print |
| :--- | :--- | :--- |
| the CPU | `grep -c "^cpu: *AMD EPYC 7763" $V` | `1` |
| the width | `grep "^inputs:" $V` | ends `extra-java-options=<none>` for the wide file and `extra-java-options=-XX:MaxVectorSize=16` for the 128-bit one |
| the head | `grep "^commit:" $V` | the first commit's hash and `(refs/pull/<n>/head of vecbricks/varka)` |
| every section ran | `grep -c "^=\{20,\}" $W` | `2 * N` |
| only the twins fused | `grep -c ": fuses" $W; grep -c "^TIMESTAMP_NTZ twin .*: fuses whole on the varka on arm, so it times a kernel there, not the row path$" $W` | the same number twice: the class's notes of the table below, for the file's width |
| both arms of every section | `grep -c "^spark row path (varka off)" $W; grep -c "^varka on" $W` | `N`, `N` |

| class | sections `N` | notes of fused twins, wide file | notes, 128-bit file |
| :--- | ---: | ---: | ---: |
| `VarkaTimestampBaselineBenchmark` | 50 | 10 | 8 |
| `VarkaTimestampFieldsBaselineBenchmark` | 88 | 28 | 28 |
| `VarkaTimestampCastsBaselineBenchmark` | 52 | 2 | 2 |
| `VarkaTimestampArithmeticBaselineBenchmark` | 40 | 14 | 14 |
| `VarkaTimestampZoneFunctionsBaselineBenchmark` | 28 | 0 | 0 |
| `VarkaTimestampNanosBaselineBenchmark` | 26 | 6 | 4 |
| `VarkaTimestampNanosFieldsBaselineBenchmark` | 50 | 6 | 6 |
| `VarkaTimestampNanosCastsBaselineBenchmark` | 36 | 2 | 2 |
| `VarkaTimestampNanosFunctionsBaselineBenchmark` | 42 | 4 | 4 |

The 128-bit run refuses a filter's condition over a long, so the twin filter of the two
zone-free classes is the row path there and has no note. A file that fails a check is not
committed: the builder reports the check and its output, and the lead dispatches again.

Then `cp $IN/<file> sql/core/benchmarks/<file>` for each file, the predictions of section 7
scored in section 11 with their numbers, and the second commit. The lead pushes it, marks the
pull request ready and merges.

### 4.10 The timestamp surface against stock Spark

The surface (`sql/varka/bench`) is the one tool that runs an expression on a downloaded Spark
release. Part E gives it a timestamp entry list, as #1635 gave it the math one, and the two
arms that separate the cache format's share from the kernels'.

- **The entries**, `Timestamps.ENTRIES`, 16, over the view `varka_timestamps`: `hour(ts)`,
  `minute(ts)`, `second(ts)`, `year(ts)`, `month(ts)`, `dayofweek(ts)`, `CAST(ts AS DATE)`,
  `date_trunc('DAY', ts)`, `date_trunc('HOUR', ts)`, `ts + INTERVAL '1' DAY`, `ts + INTERVAL
  '1' MONTH`, `from_utc_timestamp(ts, 'Europe/Amsterdam')`, `unix_micros(ts)`, `greatest(ts,
  ts2)` and `CASE WHEN ts < ts2 THEN ts ELSE ts2 END` as projections, and `ts < ts2` as a
  filter. Each is built with `expectFused` false (`Timestamps.notYet`), since none fuses yet:
  the fork's arms then hold it to nothing. A row that fuses an entry makes it
  `Surface.Entry.projection(...)` or `Surface.Entry.filter(...)`, which the Varka arm's
  `--expect-fused` then holds.
- **The table**, `TableShape.TIMESTAMPS`: `ts` and `ts2`, the baseline's year in no order
  (4.3) with the tags 0 and 1, `ts` null where `id % 31 = 0` and `ts2` where `id % 31 = 15`.
  `buildTimestampsTable` sets `spark.sql.session.timeZone` to `America/Los_Angeles` on the
  session before it builds the view, as `buildTimesTable` sets the `TIME` flag, so every arm,
  stock or fork, reads the same zone, and the checksums of the arms agree.
- **The arms**, six for this benchmark, in the order they run: `spark-4.3.0-jdk17` and
  `spark-4.3.0-jdk25` (the release with its default cache), `varka-off-jdk25` (the fork,
  default cache, Varka off), `varka-cache-jdk25` (the fork, Arrow cache, Varka off),
  `varka-jdk25` (the fork with Varka on), and last `spark-4.3.0-arrow-cache-jdk25` (the
  release with its own `ArrowCachedBatchSerializer`, the script's `arrow-cache` token on the
  stock home). The workflow adds the two cache arms only where its `benchmark` input is
  `timestamp`; the other surfaces keep their four arms, as `m10/SCOPE.md` item 132 keeps
  them.
- **Why the stock Arrow arm is last.** `dev/varka_bench_surface.sh` runs under `set -euo
  pipefail` and runs the arms in the order given, so an arm that exits non-zero ends the run
  and no later arm writes a file. The stock Arrow arm has never run on a runner, so last, it
  can cost only itself. The fork's cache arm, `varka-cache-jdk25`, has not run on the other
  surfaces either (only once on the laptop, for `DateSurface-fifth-cache-results.txt`), but
  it reads the cache through the serializer that `varka-jdk25` reads through, on every
  surface: if it could not start, `varka-jdk25` could not, so it stays before that arm. The
  script's closing table then puts
  that arm's rates against the others'; that table is the job's log and is not read here.
- **When the run's step is red.** The workflow tars and uploads `sql/varka/bench/benchmarks`
  whenever the job is not cancelled, so the files of the arms that finished are in the
  artifact. Three cases, and what the lead does:
  - *The stock Arrow arm died:* the artifact holds five files and none named
    `TimestampSurface-spark-4.3.0-arrow-cache-*`, and the last line of the job's log that
    starts `== ` and holds ` -> ` (the script prints one such line, `== <label>: <home> under
    <JDK> -> <file>`, as it starts an arm) names the label `spark-4.3.0-arrow-cache-jdk25`.
    Both must hold: five files with that line naming `varka-jdk25` mean that the Varka arm
    wrote its file and then exited non-zero, so the stock Arrow arm never started, and that
    is the third case. The lead hands over the five, with the arm's error text from the job
    log in `$IN/stock-arrow-error.txt`. The builder commits the five and section 11 records the
    error and the sentence below. No re-dispatch.
  - *All six arms ran and the closing table failed* with "the arms disagree about": the lead
    hands over the six. The builder's step 11 finds which file disagrees.
  - *Any other arm died:* nothing is committed. The lead reports the arm and its error to the
    builder, who stops: an entry or the table is wrong on that arm, and that is a fix of the
    part's code and a new dispatch.
- **A share, exactly.** Every ratio is of wall rates (the column `Rate(M/s)` of a file's first
  table of an entry, not its `executor time` table), for one shape of each entry: `projection,
  columnar consumer` for the 15 projections and `filter, columnar consumer` for `ts < ts2`.
  `python3 dev/varka_bench_diff.py --table A=<file> B=<file>` prints, under `**wall time**`,
  one row an entry and shape with the column `B / A`, which is B's rate over A's: above 1, B
  is faster. With that:
  - the *release's format share* of an entry is `B / A` with A `spark-4.3.0-jdk25` and B
    `spark-4.3.0-arrow-cache-jdk25`: how much faster the release's row path is over its Arrow
    cache than over its default cache;
  - the *fork's format share* is `B / A` with A `varka-off-jdk25` and B `varka-cache-jdk25`;
  - the *kernels' share* is `B / A` with A `varka-cache-jdk25` and B `varka-jdk25`; it is
    about 1 in this row's run, where nothing fuses, and is the number of rows 691 and 676.
- **Whether the release's serializer can be compared with the fork's** is decided by the
  first run, by this rule. The fork's serializer has moved on since `v4.3.0`: `git diff
  --name-only v4.3.0 HEAD -- sql/core/src/main/scala/org/apache/spark/sql/execution/columnar/
  | wc -l` prints 12 on master `84d6b2aff10`. For each of the 16 entries at 256 bits, divide
  the release's format share by the fork's. If every quotient is between 1 / 1.3 and 1.3, the
  two are read as one format and the post may quote the release's arm. If an entry is outside
  it, or the stock Arrow arm died, or its checksums differ from the other arms', section 11
  records which, with the entry and both shares where there are two, and the sentence "the
  release's serializer is not the fork's: the format's share is read from the fork's two
  arms". Either way the row has what its text asks.
- **The first run** is this row's, with every entry unfused: it commits the six arms at 256
  and at 128 bits under the label suffixes `-zen3-unfused` and `-zen3-128bit-unfused`, so
  that no later run writes over them. It stays the unfused baseline.
- **The runs with the kernels on** (the owner, 27 November 2026). Row 691, the zone kernels'
  measurement, makes the first, so that the first reading against the stock release comes
  when the zone kernels exist; row 676, the post, runs the surface once more at the end.
  What row 691's builder does, in a pull request of that row:
  - in `Timestamps.ENTRIES`, each entry that fuses on that row's master becomes
    `Surface.Entry.projection("<expr>")`, or `Surface.Entry.filter("ts < ts2")` for the
    filter; an entry fuses when `dev/varka_emit.sh "<expr>" --columns
    ts:timestamp,ts2:timestamp --zone America/Los_Angeles` prints `FusedOutput(0)` for it.
    Every other entry stays `notYet`;
  - `TimestampsTest.noEntryIsHeldToAKernelYet` is renamed `theEntriesNotFusedYet` and holds
    the list of the labels that are still `notYet`, in place of "all 16";
  - the lead dispatches the same three commands as part E's step 10, with the six arms, and
    the label suffixes `-zen3` and `-zen3-128bit`. The Varka arm's `--expect-fused` then
    fails the run if a turned entry does not fuse or hands a batch to the row path. At 128
    bits an entry whose conversion the record of `VARKA-689.md` 4.9 calls `refused` does
    not fuse on the runner, by design: row 691's plan says how the 128-bit dispatch
    expects those entries (the row path), and it does not turn the refusal off for the
    surface, since a user's session would not;
  - the twelve files `TimestampSurface-<arm>-zen3-results.txt` and
    `...-zen3-128bit-results.txt` are committed beside this row's `unfused` twelve, and the
    kernels' share of an entry is read from that run's `varka-cache-jdk25` and `varka-jdk25`.
  Row 676 does the same again on the milestone's last master: it turns what has fused since,
  dispatches, and commits over row 691's twelve files, whose numbers stay in git's history.
- **No nanosecond entry** (the owner, 27 November 2026). The module compiles and tests
  against Spark 4.2.0, which has no nanosecond type, and the release's own support is a
  preview behind an internal flag.

### 4.11 The second run of the `TIMESTAMP_NTZ` files

The three classes of milestone 8 list their fused sections today, and their committed files
are from before the kernels: both arms tie in them (fact 4 of section 3). Part D dispatches
each class at both widths on master's code and commits the twelve files over the old ones.
The old numbers stay in git's history. For each file, the baseline is the newest commit whose
copy of the file holds no line with `: fuses`, the note a run writes for a section that
fuses. That is not always the file's last commit: the 128-bit file of
`VarkaTimestampNtzBaselineBenchmark` was run again on 23 November (#1665) and holds six such
notes. On master `84d6b2aff10` the rule gives (5.4 has the command):

    23a256d0db5 2026-10-31 VarkaTimestampNtzArithmeticBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt
    23a256d0db5 2026-10-31 VarkaTimestampNtzArithmeticBaselineBenchmark-jdk25-runner-epyc7763-results.txt
    8f171c54722 2026-10-26 VarkaTimestampNtzBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt
    8f171c54722 2026-10-26 VarkaTimestampNtzBaselineBenchmark-jdk25-runner-epyc7763-results.txt
    011841fdc8d 2026-11-02 VarkaTimestampNtzFieldsBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt
    011841fdc8d 2026-11-02 VarkaTimestampNtzFieldsBaselineBenchmark-jdk25-runner-epyc7763-results.txt

`python3 dev/varka_bench_diff.py --git <that commit> <file>` prints the kernels against the
baseline, and part D records the six lines in section 11.

No code changes. If a run fails its fuse check at 128 bits (a section listed `WHOLE` that the
runner's AVX2 host plans otherwise), the lead dispatches that class and width again with `-f
extra-java-options="-XX:MaxVectorSize=16 -Dvarka.baseline.fuseCheck=false"`, which lifts the
check and notes in the file what each section fused; the builder records the section in
section 11, and the lead files a row for it. The builder edits no class.

### 4.12 The words a class file must and must not hold

Four merged plans find this row's classes by a grep over `<b>/Varka*BaselineBenchmark.java`.
The base class's file name does not match that pattern.

| the grep, and who runs it | what it must print | so |
| :--- | :--- | :--- |
| one of `TimestampType`, `TIMESTAMP_LTZ`, `timestamp_micros`, `TIMESTAMP '` and also `greatest(` (`VARKA-656.md` 5.2, B0 line 12) | exactly one file | only `VarkaTimestampBaselineBenchmark` holds `greatest(`; the nanosecond zone-free class spells `least(` |
| `hour(` (`VARKA-657-PARTS.md` 5.2, part B) | the `TIMESTAMP_NTZ` fields class and row 655's fields class | only `VarkaTimestampFieldsBaselineBenchmark` holds `hour(` among this row's files; the zone functions class names its hour column `hh` and writes `'HOUR'`; the nanosecond classes spell `extract(HOUR FROM` |
| `AS DATE` (`VARKA-657-PARTS.md` 5.4, part D) | row 655's casts class | only `VarkaTimestampCastsBaselineBenchmark` holds `AS DATE` among this row's files; the nanosecond casts spell `:: date` |
| `America/Los_Angeles` (`VARKA-687.md`, part b) | row 655's zoned classes | the four zoned microsecond classes name the zone in their class comment; the zone-free class and the nanosecond classes do not |

The rule covers comments too. Each part's commands run the four greps.

### 4.13 Edges

- **Null rows.** Every form runs over the null variants of its columns too; a form over two
  of them is null on two rows in ten. The suite checks that a section with no nulls answers
  no null and that one with nulls answers between one and three rows in ten null.
- **The ends of a long, and of Spark's range.** A baseline holds no value near them on
  purpose: every value is between 2020 and 2030, so no arm raises and, later, no kernel's
  guard declines a batch but the ten-year one by its changes. The types' ends are the
  conformance suites' (`VARKA-656.md`), not a benchmark's.
- **A batch with no live row** does not occur: nulls are one row in ten of every batch.
- **Two lanes of 64 bits.** The 128-bit run. Nothing of a `TIMESTAMP` fuses, so only the twin
  filter changes class there, by `declinesLongConditions`.
- **AVX2 against AVX-512.** The runner is AVX2 at 256 bits. The laptop is not measured here.
- **A gap or an overlap of local time.** `ntz` in the casts class is the UTC reading of the
  instants, so under `America/Los_Angeles` about one row in 8760 of a year is inside the
  skipped hour and as many inside the repeated one; `CAST(ntz AS TIMESTAMP)` answers them as
  Spark's row path does in both arms.
- **Midnight.** No form reads the current date or time.

### 4.14 What is out of the row

| what | who holds it |
| :--- | :--- |
| any kernel, any change of production code, the type in the registry | rows 656, 657 and after |
| a section for `IN`, a null test, `least`, `in_set`, `known_not_null`, `try_eval` | row 667 adds each in a baseline pull request before its kernel (the owner, 27 November 2026) |
| a section for each of the other eleven numeric and boolean cast pairs, and for `TIME` to `TIMESTAMP`, which reads the current date | row 669, each in a baseline pull request before its kernel (the owner, 27 November 2026) |
| the A/B arms of the zone forms, the laptop's 512 bits | row 691 |
| the nanosecond representations measured in hand loops | row 662, in a spike class of its own (`VARKA-662.md` 4.7) |
| the nanosecond kernels measured | row 699, on parts F and G's classes |
| the surface's runs with the kernels on, and their figures | row 691 first, then row 676 for the post (the owner, 27 November 2026) |
| a band file (`<C>-jdk25-band.txt`) | the first row that has to read a move in a class, as `sql/varka/AGENTS.md` has it |
| the stock Arrow-cache arm on the date, `TIME` and math surfaces | `m10/SCOPE.md` item 132 |
| a nanosecond surface against stock Spark | no row: the owner decided on 27 November 2026 that this milestone has none (section 14) |
| the never-slower gate | row 680 |

## 5. The parts

Seven pull requests. Five add classes with their results, one is results alone, one is the
surface.

| part | title | branch | counted lines, estimate |
| :--- | :--- | :--- | ---: |
| A | `[VARKA-655] Benchmark the zone-free TIMESTAMP expressions on the row path first` | `varka-655a-zone-free` | 710 |
| B | `[VARKA-655] Benchmark the TIMESTAMP fields and casts on the row path first` | `varka-655b-fields-casts` | 300 |
| C | `[VARKA-655] Benchmark TIMESTAMP arithmetic and zone functions on the row path` | `varka-655c-arithmetic-zone` | 320 |
| D | `[VARKA-655] Results: the TIMESTAMP_NTZ baselines run again with their kernels on` | `varka-655d-ntz-second-run` | 60, and at most 140 |
| E | `[VARKA-655] Time TIMESTAMP expressions against stock Spark 4.3.0 and its caches` | `varka-655e-surface` | 500 |
| F | `[VARKA-655] Benchmark nanosecond timestamps' zone-free forms and fields first` | `varka-655f-nanos-fields` | 360 |
| G | `[VARKA-655] Benchmark the nanosecond timestamps' casts and functions first` | `varka-655g-nanos-functions` | 300 |

Each title is at most 80 characters with its tag, the limit of `dev/varka_msg_check.py`: 79,
74, 77, 80, 79, 77 and 74.

The estimates, file by file. Part A: the base class 344 with its header and comments, the
zone-free class 152, the trait 172, the suite 29, the two plan files 12. Part B: two classes
of 110 and 105, two suites of 30 and 33, the plan files 20. Part C: two classes of 115 and
125, two suites of 30, the plan files 15. Part D: about 30 lines of this file's section 11,
three lines in milestone 8's plans, and at most 40 requoted lines, each counted twice (5.4
bounds them). Part E: `Timestamps` 75, `TimestampSurfaceBenchmark` 38, `DateSurfaceBenchmark`
65, the script 22, the workflow 14, `TimestampsTest` 165, `SurfaceTest` 3, the catalogue's
note 2, and this file's section 11 with its two tables of 16 rows, about 115. Part F: the
base 30, the trait 20, two classes of 105 and 110, two suites of 33, the plan files 30. Part
G: two classes of 110, two suites of 33, the plan files 15. Results and provenance files
count in none: `dev/varka_msg_check.py` leaves files under a `benchmarks/` directory out.

**The cut line of part A.** A is the only part near 750. If `python3 dev/varka_push_check.py`
counts over 850, the builder moves the trait's third test ("the data of ... is what its class
says", with `changesIn`) out of part A and into part B, which then adds it to the trait as its
first step, and reports the move. Nothing else moves.

| part | needs | gives | wave |
| :--- | :--- | :--- | ---: |
| A | this plan merged | the base, the trait, the zone-free class with its suite and four files. Row 656's part B can start. Master is green and nothing fuses | 0 |
| B | A merged | the fields and the casts classes, suites and eight files. Row 657's parts B and D can list their sections | 0 |
| C | A merged | the arithmetic and the zone functions classes, suites and eight files. Rows 690, 670 and 671 read them | 0 |
| D | nothing of this row | the twelve `TIMESTAMP_NTZ` files run again. Only milestone 8's record and row 691's twins' reader read it | 0 |
| E | nothing of this row | the timestamp surface and its first, unfused run. Rows 691 and 676 read it | 0 |
| F | A merged | the base's nanosecond kinds; the nanosecond zone-free and fields classes, suites and eight files. Row 699 measures its zone-free forms, its field and its seconds on them | 4 |
| G | F merged | the nanosecond casts and functions classes, suites and eight files. Row 699 measures its cast to microseconds on the casts class, so both F and G merge before row 699's first kernel; rows 674 and 675 read them | 4 |

B, C, D and E can be built side by side by different agents once A has merged (D and E need
not wait for A): they share no file but `m9/PLAN.md` and this file's section 11, where each
adds its own lines. F and G follow each other, since G reads F's kinds.

### 5.0 What every sheet shares

- Work in the worktree and on the branch the lead names. Each part starts from master after
  the part it needs has merged.
- Every shell that builds or tests starts with:

      export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
      export SPARK_LOCAL_IP=127.0.0.1
      export PATH=$HOME/.venv-ruff/bin:$PATH
      export VARKA_HEAVY_LOCK=<the lock file the lead names>
      M9=sql/varka/plans/m9; T=target/varka-655; mkdir -p $T
      B=sql/core/src/test/java/org/apache/spark/sql/execution/benchmark
      S=sql/core/src/test/scala/org/apache/spark/sql/execution/benchmark
      X=sql/core/src/test/scala/org/apache/spark/sql/execution
      R=sql/core/benchmarks
      SB=sql/varka/bench/src/main/java/org/apache/spark/sql/varka/bench
      ST=sql/varka/bench/src/test/java/org/apache/spark/sql/varka/bench
      P=org.apache.spark.sql.execution.benchmark

  `$T` is scratch under `target/`, which git ignores.
- Wrap every `build/sbt`, `build/mvn`, `dev/lint-java`, `dev/lint-scala` and
  `dev/varka_errorprone.sh` call in `flock "$VARKA_HEAVY_LOCK"`. Call `dev/varka_push_check.py`
  bare: it takes the lock.
- B0 and the commands of each sheet are code blocks, typed as printed. Inside a quoted grep
  pattern `\|` is grep's "or"; a bare `|` between two commands is the shell's pipe.
- Run B0 before any edit. A line that does not print what the sheet says is a stop: report the
  command and its output, and edit nothing.
- Before B0 of any part, save the script of 4.7 as `$T/twins.py`.
- Where a sheet gives code, type it as given: each line was counted and is within 100 columns.
  A block that is a whole file starts at its least-indented line, which is the file's column
  0. A fragment is indented in its file as the lines around it, not as the sheet's list
  indents it.
- **The license header.** A new file starts with the header of the file its sheet names: that
  file's lines from the first to the line ` */`, which is line 16. A Java file under `$B`
  has `package` on line 17, as its model has. A Scala file under `$S` and a Java file under
  `$SB` or `$ST` have a blank line 17 and `package` on line 18, as their models have. The
  sheets' blocks start at the `package` line.
- No part changes a census cell, a coverage row, an emitted byte or another class's results
  file, and each sheet's `git status --short` shows that: only its listed files.
- A suite command passes when sbt exits 0 and prints the summary line the sheet gives.
- **The two dry runs** of a class `<C>` run its `runBenchmarkSuite` with every check and no
  timing, under AVX2, the runner's instruction set, since the laptop's AVX-512 lowers masks
  that the runner cannot:

      flock "$VARKA_HEAVY_LOCK" build/sbt -batch "project sql" \
        'set Test/javaOptions ++= Seq("-Dvarka.bench.dryRun=true", "-XX:UseAVX=2")' \
        "Test/runMain $P.<C>" > $T/dry-<C>.log 2>&1; echo "exit $?"
      grep -c ": fuses whole on the varka on arm, so it times a kernel there, not the row path" \
        $T/dry-<C>.log

  It must print `exit 0` and the class's count of fused twins (4.9's table, the wide file's
  column). **The narrow dry run** has `"-XX:MaxVectorSize=16"` as a third element of the
  `Seq` and the log `$T/dry16-<C>.log`; it must print `exit 0` and the 128-bit column's count.
  The `echo` and the `grep` run whatever sbt's exit was, so read `exit` first: with another
  exit the count means nothing. A dry run that ends with `is WHOLE but its varka on arm would
  time the row path` is 4.9's "run that dies on a twin", found before a dispatch: stop and
  report the twin's title and the width. If the narrow dry run of a zone-free class prints
  the wide count, the host does not refuse a two-lane condition under these flags: report the
  number and go on. A dry run writes no file: `git status --short` is the same before and
  after it.
- **Test time.** Command 2 of each sheet is run with `2>&1 | tee $T/suites.log`. sbt prints
  each test with its time, `- <test's name> (<n> seconds, <m> milliseconds)`. The time of a
  suite is the sum of its tests' lines, found by `grep " of <the class's simple name> "
  $T/suites.log` (each test's name holds its class). Prediction 18 is scored from them.
- **The four greps of 4.12**, run before each first commit:

      ls $B/Varka*BaselineBenchmark.java | xargs grep -l "TimestampType\|TIMESTAMP_LTZ\|timestamp_micros\|TIMESTAMP '" | xargs grep -l "greatest("
      grep -l "hour(" $B/Varka*BaselineBenchmark.java
      grep -l "AS DATE" $B/Varka*BaselineBenchmark.java
      grep -l "America/Los_Angeles" $B/Varka*BaselineBenchmark.java

  Each sheet says what the four print after its part.
- Before a commit: `dev/varka_precommit.sh`; `dev/varka_errorprone.sh --master "$(git rev-parse
  origin/master)"` and `dev/lint-java` for Java; `dev/lint-scala` for Scala; `python3
  dev/varka_plan_lint.py --check --offline`. After it: `python3 dev/varka_push_check.py`, which
  prints the size and must exit 0.
- No part regenerates anything: no generated file reads a benchmark class. Do not run
  `dev/varka_regen_generated.sh`.
- A commit's subject is the part's title; the second commit of a part adds ` (results)`. Its
  body ends with one attribution line, `Generated-by: <tool> (<model>)`, and no other trailer.
- In `$M9/PLAN.md`, row 655's cell: part A changes `**Planned**` to `**Started**`; the last
  part to merge changes `**Started**` to `**Done**` and adds the seven pull request numbers.
  Each part adds what it built to section 11 of this file, under a line `Part <letter>:`.
- Stop at the local commit. No push, no pull request, no dispatch: the lead does those (4.9).
  Report the branch, the commit, the commands with their results and the size.
- Stop also on: a suite that should pass and fails for a reason the sheet does not name; a
  file in `git status --short` that the sheet's list does not hold, or a listed file that is
  missing; a size over the estimate by half or over 900; anything the sheet does not decide.
- **A `TIMESTAMP` form that does not plan `NONE` on master.** The suite then fails with "is
  listed as NONE but plans as". The form's kernel merged before its baseline. Stop: report
  the form and the class its plan has. The lead decides whether the part is built on the
  commit before that kernel.
- **A twin that does not plan `WHOLE` on master.** The suite then fails with "is listed as
  WHOLE but plans as". Run `python3 $T/twins.py`: if it lists the twin as missing, remove the
  twin's line, lower the suite's count by two, report it, and go on. If the twin is not
  missing, stop.
- **What runs only in CI.** The timed runs on the EPYC 7763 (4.9); the option matrix, where
  the suites' fuse test is cancelled by its `PinsDefaults` tag and their other tests run under
  every configuration; the Maven build. The lead dispatches them. What the builder runs first
  is each sheet's commands.

### 5.1 Part A: the base, the trait and the zone-free class

**B0, the premises.**

    git branch -a --list '*varka-655a*' | wc -l                                  # 1: 0
    ls $B $S | grep -c "^VarkaTimestampBaseline"                                  # 2: 0
    grep -l "TimestampType\|TIMESTAMP_LTZ\|timestamp_micros\|TIMESTAMP '" $B/Varka*BaselineBenchmark.java | wc -l   # 3: 0
    grep -P "^TIMESTAMP\t" sql/varka/types.tsv | cut -f5                          # 4: declined, or owed
    python3 $T/twins.py                                                          # 5: 28 twins, 0 missing
    grep -c "public enum Fusion { NONE, NULL_TESTS, SPLIT, PARTIAL, WHOLE }" $B/VarkaSqlBenchmarkBase.java   # 6: 1
    grep -c "Fusion expected, BatchCheck check) {" $B/VarkaSqlBenchmarkBase.java   # 7: 2
    grep -c "protected def forEachTwoLaneRefusal(body: Boolean => Unit): Unit" $X/VarkaSharedSessions.scala   # 8: 1
    grep -c "protected def withBothConf\[T\](key: String, value: String)(body: => T): T" $X/VarkaSharedSessions.scala   # 9: 1
    grep -c "boolean declineTwoLaneConditions" sql/core/src/main/java/org/apache/spark/sql/execution/VarkaSessionEmit.java   # 10: 1 or more
    grep -c 'and the timestamp surface. \*\*Planned\*\* (`VARKA-655.md`' $M9/PLAN.md   # 11: 1

`$T/twins.py` is the script of 4.7. Line 4 printing `on_lane` is a stop: row 656's part B has
merged before its baseline; report it. Line 4 printing `owed` is row 656's part A, which fuses
nothing: go on.

**The steps.**

1. `$B/VarkaTimestampBaselineBase.java`: new, the file of 4.2.
2. `$B/VarkaTimestampBaselineBenchmark.java`: new, the file of 4.5.
3. `$S/VarkaTimestampBaselineQueries.scala`: new. The license header is lines 1 to 16 of
   `$S/VarkaTimestampNtzBaselineQueriesSuite.scala`. Then:

        package org.apache.spark.sql.execution.benchmark

        import java.time.{Instant, ZoneId}

        import scala.jdk.CollectionConverters._

        import org.apache.spark.sql.QueryTest
        import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrix.PinsDefaults
        import org.apache.spark.sql.execution.VarkaSharedSessions
        import org.apache.spark.sql.execution.benchmark.VarkaTimestampBaselineBase.Data
        import org.apache.spark.sql.internal.SQLConf

        /**
         * The tests of a timestamp baseline's queries suite (VARKA-655): the benchmark's
         * sections run at a small scale on both sessions in every build, under each of its
         * zones, so a section that no longer parses, that raises, that answers a null over the
         * columns with none, that the two sessions answer differently or that fuses unlisted
         * fails a pull request rather than the runner dispatch that times it. The fuse check
         * is the benchmark's own, made with the two-lane condition refusal off and on. The
         * last test holds the table's data sets to what the base class says of them.
         */
        trait VarkaTimestampBaselineQueries extends QueryTest with VarkaSharedSessions {

          /** The benchmark whose sections the suite runs. */
          protected def newBenchmark: VarkaTimestampBaselineBase

          /** How many sections the benchmark times at one width. */
          protected def sectionCount: Int

          /** The keys the benchmark's `settings()` must hold, each set to `true`. */
          protected def settingKeys: Set[String] = Set.empty

          private lazy val bench = newBenchmark
          private lazy val family = bench.getClass.getSimpleName
          private lazy val view = bench.view()
          private val zones = VarkaTimestampBaselineBase.ZONES.asScala.toSeq
          private val rows = 2000

          /** Runs `body` under `zone`, the table at `count` rows cached on both sessions. */
          private def withZone(zone: String, count: Int)(body: => Unit): Unit = {
            def under(left: Seq[(String, String)]): Unit = left match {
              case (key, value) +: rest => withBothConf(key, value)(under(rest))
              case _ =>
                val sessions = Seq(spark, varkaSpark)
                try {
                  for (session <- sessions) {
                    session.sql(bench.table(count)).createOrReplaceTempView(view)
                    session.catalog.cacheTable(view)
                  }
                  body
                } finally {
                  for (session <- sessions if session.catalog.tableExists(view)) {
                    session.catalog.uncacheTable(view)
                    session.catalog.dropTempView(view)
                  }
                }
            }
            under(bench.settings().asScala.toSeq :+ (SQLConf.SESSION_LOCAL_TIMEZONE.key -> zone))
          }

          /** The changes of offset of the zone with changes inside `data`, from `java.time`. */
          private def changesIn(data: Data): Int = {
            val rules = ZoneId.of(VarkaTimestampBaselineBase.DST).getRules
            val end = Instant.ofEpochSecond(data.startSeconds + data.spanSeconds)
            val first = rules.nextTransition(Instant.ofEpochSecond(data.startSeconds))
            Iterator.iterate(first)(t => rules.nextTransition(t.getInstant))
              .takeWhile(t => t != null && t.getInstant.isBefore(end)).size
          }

          test(s"every section of $family runs, and both sessions answer it alike") {
            // A test JVM enables every preview type by default, so a section cannot show that
            // the benchmark sets its type's flag: the settings are held to the suite's list.
            assert(bench.settings().asScala.toMap === settingKeys.map(_ -> "true").toMap)
            val all = bench.timed(false).asScala.toSeq
            assert(all.size === sectionCount)
            assert(all.map(_.title).distinct.size === all.size)
            assert(all.map(_.zone).toSet.subsetOf(zones.toSet))
            for (zone <- zones) {
              withZone(zone, rows) {
                for (t <- all if t.zone == zone) {
                  withClue(t.title) {
                    val answer = spark.sql(t.query).collect().toSeq
                    checkAnswer(varkaSpark.sql(t.query), answer)
                    if (t.query.contains(" WHERE ")) {
                      // A filter over two columns keeps some rows and not all of them.
                      assert(answer.nonEmpty && answer.size < rows)
                    } else if (t.title.endsWith(VarkaTimestampBaselineBase.NO_NULLS)) {
                      assert(answer.count(_.isNullAt(0)) === 0)
                    } else {
                      // One to three of the form's columns are null on one row in ten each.
                      val nulls = answer.count(_.isNullAt(0))
                      assert(nulls >= rows / 10 && nulls <= 3 * rows / 10)
                    }
                  }
                }
              }
            }
          }

          test(s"each section of $family fuses as its class lists it", PinsDefaults) {
            // The benchmark plans under the emitter's default options, so the classes pin the
            // defaults, and an option matrix configuration cancels the test; so does the run
            // that lifts the benchmark's check.
            assume(VarkaSqlBenchmarkBase.fuseCheck(),
              s"-D${VarkaSqlBenchmarkBase.FUSE_CHECK_PROPERTY}=false times the sections fused")
            forEachTwoLaneRefusal { declines =>
              for (zone <- zones) {
                withZone(zone, 100) {
                  // The twin cast to DATE fuses, so a check that found no Varka node anywhere,
                  // over a plan it could not see into, fails here rather than passing all.
                  assert(VarkaSqlBenchmarkBase.fusedPlan(varkaSpark,
                    s"SELECT CAST(ntz_y AS DATE) AS v FROM $view").isPresent)
                  for (t <- bench.timed(declines).asScala if t.zone == zone) {
                    withClue(s"${t.title}, refusal $declines") {
                      VarkaSqlBenchmarkBase.requireSection(
                        varkaSpark, t.title, t.query, t.fusion, t.check)
                    }
                  }
                }
              }
            }
          }

          test(s"the data of $family is what its class says") {
            // What the base class says of each data set: its changes of offset in the zone
            // with changes, and whether its instants are in time order.
            val changes =
              Map(Data.WEEK -> 0, Data.YEAR_ORDERED -> 2, Data.YEAR -> 2, Data.TEN_YEARS -> 20)
            val ordered = Set(Data.YEAR_ORDERED)
            withZone(VarkaTimestampBaselineBase.UTC, rows) {
              for (data <- bench.dataSets().asScala) {
                withClue(data.label) {
                  assert(changesIn(data) === changes(data))
                  val ts = s"ts_${data.suffix}"
                  val r = spark.sql(
                    s"""select min(m) as lo, max(m) as hi, count_if(prev > m) as descents,
                       |       count_if(n is null) as nulls
                       |from (select unix_micros($ts) as m, ${ts}n as n,
                       |             lag(unix_micros($ts)) over (order by id) as prev
                       |      from $view)""".stripMargin).head()
                  val start = data.startSeconds * 1000000L
                  val span = data.spanSeconds * 1000000L
                  assert(r.getAs[Long]("lo") >= start && r.getAs[Long]("hi") < start + span)
                  assert(r.getAs[Long]("hi") - r.getAs[Long]("lo") > span / 2)
                  assert(r.getAs[Long]("nulls") === rows / 10)
                  if (ordered(data)) {
                    assert(r.getAs[Long]("descents") === 0)
                  } else {
                    assert(r.getAs[Long]("descents") > rows / 3)
                  }
                }
              }
            }
          }
        }

   Every view of this row holds `id`, `ts_y`, `ts_yn` and `ntz_y`, which the trait reads by
   name.
4. `$S/VarkaTimestampBaselineQueriesSuite.scala`: new, the same header, then:

        package org.apache.spark.sql.execution.benchmark

        /**
         * The zone-free TIMESTAMP baseline's sections in every build (VARKA-655): see
         * [[VarkaTimestampBaselineQueries]].
         */
        class VarkaTimestampBaselineQueriesSuite extends VarkaTimestampBaselineQueries {

          override protected def newBenchmark: VarkaTimestampBaselineBase =
            new VarkaTimestampBaselineBenchmark

          override protected def sectionCount: Int = 50
        }

5. `$M9/PLAN.md`, row 655's cell: `**Planned**` becomes `**Started**`. This file, section 11:
   the line `Part A: the base, the trait and the zone-free class; results pending.`
6. The commands below, through command 9. Then the first commit, and the hand-over of 4.9
   with the one class `$P.VarkaTimestampBaselineBenchmark`.
7. With `$IN` from the lead: the checks of 4.9 on each of the four files, with `N` 50 and the
   notes 10 and 8. Copy the four files into `sql/core/benchmarks`. Score predictions 1 to 5,
   12, 18 and 19 of section 7 for this class in section 11, each with its numbers, and
   replace "results pending" by the pull request's number. Second commit.

**The tests.**

| suite (language, module) | test | inputs | expected, and its oracle | the failure only it catches |
| :--- | :--- | :--- | :--- | :--- |
| `VarkaTimestampBaselineQueriesSuite` (ScalaTest, sql/core, `$S`) | every section of VarkaTimestampBaselineBenchmark runs, and both sessions answer it alike | the class's table at 2000 rows, cached under each of the three zones; all 50 sections | `settings()` is empty, as the suite's `settingKeys` is; 50 sections with distinct titles; `varkaSpark` answers each as `spark` does (`checkAnswer`; the oracle is Spark's row path); a filter keeps between 1 and 1999 rows; a projection over the columns with no null answers no null, one over the null columns between 200 and 600 | a form that does not analyze or raises; a title used twice; a count that drifts from the plan's |
| the same | each section of VarkaTimestampBaselineBenchmark fuses as its class lists it | the table at 100 rows, under each zone, with the two-lane refusal off and on | each section's plan has its listed class (`requireSection`); a `WHOLE` twin runs every batch as a kernel; the control `CAST(ntz_y AS DATE)` holds a Varka node | a row that fuses a form and does not list it; a twin that stopped fusing; a check that sees no Varka node at all |
| the same | the data of VarkaTimestampBaselineBenchmark is what its class says | the table at 2000 rows under UTC; the data sets `YEAR` and `YEAR_ORDERED` | each `ts` column lies inside its range and spans over half of it; 200 nulls in its null variant; 0 descents in time order and over 666 in no order; 2 changes of `America/Los_Angeles` in each (`java.time`'s `ZoneRules.nextTransition`, a public API) | a data set whose constants are wrong: a range with another count of changes, an "ordered" column that is not |
| `VarkaTimestampNtzBaselineQueriesSuite` (existing) | both | unchanged | passes | a change of `VarkaSqlBenchmarkBase` that this part must not make |

A batch with no live row, the ends of a long and the years before 1582 or after 9999 have no
case: 4.13 says why a baseline holds none.

**Planted faults.** Each is made, its command run, and then undone; `git diff` on the file is
empty after each.

| fault | where | the test that must fail, and its message |
| :--- | :--- | :--- |
| `Fusion pick = Fusion.WHOLE;` | `sections`, the zone-free class | the fuse test, with `TIMESTAMP greatest(ts, ts2), UTC, a year in no order, no nulls is listed as WHOLE but plans as NONE` |
| the twin `greatest(ntz@, ntz2@)` listed `Fusion.NONE` | the same method | the fuse test, with `is listed as NONE but plans as WHOLE` |
| `YEAR_ORDERED(..., true)` made `false` | `Data`, the base class | the data test, at the clue `a year in time order`, on `descents === 0` |
| `sectionCount` 51 | the suite | the first test, on `all.size === sectionCount` |

**The commands.**

| # | command | must print |
| ---: | :--- | :--- |
| 1 | `flock "$VARKA_HEAVY_LOCK" build/sbt -batch sql/Test/compile` | `[success]` |
| 2 | `flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'sql/testOnly *VarkaTimestampBaselineQueriesSuite *VarkaTimestampNtzBaselineQueriesSuite'` | `Passed: Total 5, Failed 0, Errors 0, Passed 5` |
| 3 | the same with `'set sql/Test/javaOptions += "-XX:MaxVectorSize=16"'` before the `testOnly` | the same line |
| 4 | the four planted faults, each with command 2 | each fails as its row says |
| 5 | the dry run and the narrow dry run of `VarkaTimestampBaselineBenchmark` | `exit 0` and `10`; `exit 0` and `8` |
| 6 | the four greps of 5.0 | the zone-free class; the `TIMESTAMP_NTZ` fields class; the `TIMESTAMP_NTZ` baseline; nothing |
| 7 | `grep -c "record Section(String title, String query, Fusion fusion)" $B/VarkaTimestampBaselineBenchmark.java; grep -c "boolean declinesLongConditions" $B/VarkaTimestampBaselineBenchmark.java; grep -c "Fusion.NONE" $B/VarkaTimestampBaselineBenchmark.java; grep -c "Fusion.WHOLE" $B/VarkaTimestampBaselineBenchmark.java` | `1`; `2`; a count above 0; a count above 0 (row 656's B0 lines 13 to 14b) |
| 8 | the checks of 5.0 before a commit | each exits 0 |
| 9 | `git status --short` | the six lines below |

    M  sql/varka/plans/m9/PLAN.md
    M  sql/varka/plans/m9/VARKA-655.md
    A  sql/core/src/test/java/org/apache/spark/sql/execution/benchmark/VarkaTimestampBaselineBase.java
    A  sql/core/src/test/java/org/apache/spark/sql/execution/benchmark/VarkaTimestampBaselineBenchmark.java
    A  sql/core/src/test/scala/org/apache/spark/sql/execution/benchmark/VarkaTimestampBaselineQueries.scala
    A  sql/core/src/test/scala/org/apache/spark/sql/execution/benchmark/VarkaTimestampBaselineQueriesSuite.scala

(after `git add` of the six; the order of the lines is git's). After the second commit's
`git add`, four lines of `sql/core/benchmarks` and this file.

**Expectations, to tick.**

- [ ] `VarkaTimestampBaselineBase` exists with `ZONES`, `Data`, `Kind`, `Column`, `Free`,
  `Cell`, `G7`, `LEAD`, `Timed`, `Form` (`lead`, `one`, `twin`, `at`), `expand`, `grid`,
  `tableSql`, `table`, the five overridable members and a final `runBenchmarkSuite`.
- [ ] `VarkaTimestampBaselineBenchmark` holds 50 sections, each `TIMESTAMP` one `Fusion.NONE`.
- [ ] The suite's three tests pass at both widths; the four planted faults fail as said.
- [ ] Both dry runs exit 0.
- [ ] Four files of the class are in `sql/core/benchmarks`, each passing 4.9's checks.
- [ ] No file outside the list changed: no production code, no generated file, no other
  benchmark's results.
- [ ] Predictions 1 to 5, 12, 18 and 19 are scored in section 11.

**Done when** the class, its suite and its four files are on master and row 656's B0 lines 12
to 14b print what that sheet asks. **Not here:** any other class; an edit of
`VarkaSqlBenchmarkBase` or of a `TIMESTAMP_NTZ` class; a section for `IN` or a null test.

### 5.2 Part B: the fields and the casts

**B0, the premises.**

    git branch -a --list '*varka-655b*' | wc -l                                              # 1: 0
    grep -c "static List<Timed> grid(String view, List<Form> forms)" $B/VarkaTimestampBaselineBase.java   # 2: 1
    grep -c "static Form lead(String select, Fusion fusion, BatchCheck tenYears)" $B/VarkaTimestampBaselineBase.java   # 3: 1
    grep -c "trait VarkaTimestampBaselineQueries" $S/VarkaTimestampBaselineQueries.scala      # 4: 1
    ls $B/VarkaTimestampFieldsBaselineBenchmark.java $B/VarkaTimestampCastsBaselineBenchmark.java 2>/dev/null | wc -l   # 5: 0
    grep -l "hour(" $B/Varka*BaselineBenchmark.java | wc -l                                   # 6: 1
    grep -c "val TIME_TYPE_ENABLED" sql/catalyst/src/main/scala/org/apache/spark/sql/internal/SQLConf.scala   # 7: 1
    python3 $T/twins.py                                                                      # 8: 28 twins, 0 missing

**The steps.**

1. `$B/VarkaTimestampFieldsBaselineBenchmark.java`: new, the header of
   `$B/VarkaTimestampBaselineBase.java`, then:

        package org.apache.spark.sql.execution.benchmark;

        import java.util.List;

        /**
         * The baseline for the fields of a {@code TIMESTAMP} before Varka fuses them (VARKA-655):
         * Spark's row path over an Arrow-cached table, so that the rows that fuse a field report
         * what they change against a committed file.
         *
         * <p>The clock fields are the lead forms: each runs under UTC, under {@code +01:00} and
         * under {@code America/Los_Angeles}, the last over a week, a year in time order, a year
         * in no order and ten years in no order ({@link VarkaTimestampBaselineBase#LEAD}). Every
         * other form runs under {@code America/Los_Angeles} over a year in no order alone, the
         * cell the milestone's speed bar reads. A form whose {@code TIMESTAMP_NTZ} kernel exists
         * has a twin over the same instants read in UTC: the twin fuses today, and its
         * difference from the form is the zone's price.
         *
         * <p>A baseline is only one while nothing fuses. Every {@code TIMESTAMP} form is listed
         * {@code Fusion.NONE} in {@link #forms()}; the run and the class's queries suite fail
         * when a section's plan has another class. A row that fuses a form sets the form's class
         * in its line of {@link #forms()} and, for a lead form, what its kernel does with the
         * batches of the ten years, which span more changes of offset than a kernel's form may
         * cover.
         *
         * <p>To run this benchmark, on a GitHub runner (the results come back as an artifact):
         * <pre>
         *   gh workflow run benchmark.yml -f class=VarkaTimestampFieldsBaselineBenchmark \
         *     -f jdk=25 -f expected-cpu="AMD EPYC 7763"
         *   (and again with -f extra-java-options=-XX:MaxVectorSize=16 for the 128-bit companion)
         * </pre>
         */
        public class VarkaTimestampFieldsBaselineBenchmark extends VarkaTimestampBaselineBase {

          /** The table's view name. */
          static final String VIEW = "varka_ts_fields";

          /**
           * Every form, with how much of it the Varka session fuses and, for a lead form, the
           * batch check of its ten-year cell. A row that fuses a form sets both in its line.
           */
          static List<Form> forms() {
            return List.of(
                Form.lead("hour(ts@)", Fusion.NONE, BatchCheck.KERNEL),
                Form.lead("minute(ts@)", Fusion.NONE, BatchCheck.KERNEL),
                Form.lead("second(ts@)", Fusion.NONE, BatchCheck.KERNEL),
                Form.one("year(ts@)", Fusion.NONE),
                Form.one("quarter(ts@)", Fusion.NONE),
                Form.one("month(ts@)", Fusion.NONE),
                Form.one("day(ts@)", Fusion.NONE),
                Form.one("dayofyear(ts@)", Fusion.NONE),
                Form.one("dayofweek(ts@)", Fusion.NONE),
                Form.one("weekday(ts@)", Fusion.NONE),
                Form.one("weekofyear(ts@)", Fusion.NONE),
                Form.one("last_day(ts@)", Fusion.NONE),
                Form.one("next_day(ts@, 'MONDAY')", Fusion.NONE),
                Form.one("trunc(ts@, 'MONTH')", Fusion.NONE),
                Form.one("time_bucket(INTERVAL '15' MINUTE, ts@)", Fusion.NONE),
                Form.twin("hour(ntz@)", Fusion.WHOLE),
                Form.twin("minute(ntz@)", Fusion.WHOLE),
                Form.twin("second(ntz@)", Fusion.WHOLE),
                Form.twin("year(ntz@)", Fusion.WHOLE),
                Form.twin("quarter(ntz@)", Fusion.WHOLE),
                Form.twin("month(ntz@)", Fusion.WHOLE),
                Form.twin("day(ntz@)", Fusion.WHOLE),
                Form.twin("dayofyear(ntz@)", Fusion.WHOLE),
                Form.twin("dayofweek(ntz@)", Fusion.WHOLE),
                Form.twin("weekday(ntz@)", Fusion.WHOLE),
                Form.twin("weekofyear(ntz@)", Fusion.WHOLE),
                Form.twin("last_day(ntz@)", Fusion.WHOLE),
                Form.twin("next_day(ntz@, 'MONDAY')", Fusion.WHOLE),
                Form.twin("trunc(ntz@, 'MONTH')", Fusion.WHOLE));
          }

          @Override
          String view() {
            return VIEW;
          }

          @Override
          List<Data> dataSets() {
            return List.of(Data.values());
          }

          @Override
          List<Column> columns() {
            return List.of(new Column("ts", Kind.TS, 0), new Column("ntz", Kind.NTZ, 0));
          }

          @Override
          List<Timed> timed(boolean declinesLongConditions) {
            return grid(VIEW, forms());
          }
        }

2. `$B/VarkaTimestampCastsBaselineBenchmark.java`: new. It is the fields class's file with
   these differences and no other:
   - the class's name, and `Fields` in the `gh` command of the comment, read `Casts`;
   - the comment's first paragraph reads: "The baseline for the casts to and from a {@code
     TIMESTAMP} before Varka fuses them (VARKA-655): Spark's row path over an Arrow-cached
     table, so that the rows that fuse a cast report what they change against a committed
     file.";
   - the second paragraph's first sentence, up to its colon, reads: "The cast to a date, the
     cast from a {@code TIMESTAMP_NTZ} and the cast from a date are the lead forms, one for
     each rule by which a zone turns an instant into a local time or back:"; the rest of the
     paragraph stays. A fourth paragraph is added before the `gh` one: "The table holds a
     {@code TIME} result, so both sessions set the {@code TIME} type's flag: its default is
     fixed when {@code SQLConf} loads, from whether the JVM counts as a test's by then.";
   - `VIEW` is `"varka_ts_casts"`;
   - two more imports, `java.util.Map` after `java.util.List`, and, after a blank line,
     `org.apache.spark.sql.internal.SQLConf`;
   - `forms()` returns:

            Form.lead("CAST(ts@ AS DATE)", Fusion.NONE, BatchCheck.KERNEL),
            Form.lead("CAST(ntz@ AS TIMESTAMP)", Fusion.NONE, BatchCheck.KERNEL),
            Form.lead("CAST(d@ AS TIMESTAMP)", Fusion.NONE, BatchCheck.KERNEL),
            Form.one("CAST(ts@ AS TIMESTAMP_NTZ)", Fusion.NONE),
            Form.one("CAST(ts@ AS TIME)", Fusion.NONE),
            Form.one("CAST(ts@ AS BIGINT)", Fusion.NONE),
            Form.one("CAST(ts@ AS DOUBLE)", Fusion.NONE),
            Form.one("CAST(ts@ AS DECIMAL(20, 6))", Fusion.NONE),
            Form.one("CAST(sec@ AS TIMESTAMP)", Fusion.NONE),
            Form.one("CAST(dbl@ AS TIMESTAMP)", Fusion.NONE),
            Form.twin("CAST(ntz@ AS DATE)", Fusion.WHOLE)

   - `columns()` returns `List.of(new Column("ts", Kind.TS, 0), new Column("ntz", Kind.NTZ,
     0), new Column("d", Kind.DATE, 0), new Column("sec", Kind.SECONDS, 0), new Column("dbl",
     Kind.DOUBLE_SECONDS, 0))`;
   - one more override, after `columns()`:

            @Override
            Map<String, String> settings() {
              return Map.of(SQLConf.TIME_TYPE_ENABLED().key(), "true");
            }

   The comment of this class must not hold the text `hour(`.
3. `$S/VarkaTimestampFieldsBaselineQueriesSuite.scala` and
   `$S/VarkaTimestampCastsBaselineQueriesSuite.scala`: new, each the file of part A's step 4
   with the class names changed, the comment's first words "The TIMESTAMP fields baseline's"
   and "The TIMESTAMP casts baseline's", and `sectionCount` 88 and 52. The casts suite has one
   more member and one more import, `org.apache.spark.sql.internal.SQLConf`, after a blank
   line under `package`:

        override protected def settingKeys: Set[String] = Set(SQLConf.TIME_TYPE_ENABLED.key)
4. This file, section 11: `Part B: the fields and the casts classes; results pending.`
5. The commands below, through 7. First commit. Hand-over with the two classes.
6. With `$IN`: 4.9's checks on the eight files (`N` 88 with notes 28 and 28; `N` 52 with notes
   2 and 2). Copy them. Score predictions 1 to 3, 5 to 8, 12, 18 and 19 for the two classes in
   section 11, each with its numbers. Second commit.

**The tests.** The trait's three tests, in each of the two suites.

| suite | what its inputs add to part A's | the failure only it catches |
| :--- | :--- | :--- |
| `VarkaTimestampFieldsBaselineQueriesSuite` | 88 sections; the four data sets, so the data test also holds the week to 0 changes and the ten years to 20; the ten-year cell under `America/Los_Angeles` at 100 and 2000 rows | a field form that Spark rejects over a `TIMESTAMP`; a ten-year range that is not ten years |
| `VarkaTimestampCastsBaselineQueriesSuite` | 52 sections; `settings()` held to the `TIME` flag by `settingKeys`; `ntz` values inside the skipped and the repeated hour of 10 March and 3 November 2024 at 2000 rows only by chance, so no assertion rests on them | a cast that raises under ANSI on the table's values; a class that does not set the `TIME` flag, which no test JVM would otherwise show |

**Planted faults.**

| fault | where | the test that must fail |
| :--- | :--- | :--- |
| `Form.lead("hour(ts@)", Fusion.WHOLE, BatchCheck.KERNEL)` | the fields class | its fuse test, `TIMESTAMP hour(ts), UTC, a year in no order, no nulls is listed as WHOLE but plans as NONE` |
| `TEN_YEARS`'s span `31_536_000L` | `Data`, the base class | the fields suite's data test, at the clue `ten years in no order`, on the count of changes (2 for 20) |
| `settings()` returns `Map.of()` | the casts class | the casts suite's first test, on its first assertion, the one on `bench.settings()`. No section can show it: sbt runs tests with `spark.testing` set, which enables the `TIME` type by default |

**The commands.**

| # | command | must print |
| ---: | :--- | :--- |
| 1 | `flock "$VARKA_HEAVY_LOCK" build/sbt -batch sql/Test/compile` | `[success]` |
| 2 | `flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'sql/testOnly *VarkaTimestampFieldsBaselineQueriesSuite *VarkaTimestampCastsBaselineQueriesSuite *VarkaTimestampBaselineQueriesSuite'` | `Passed: Total 9, Failed 0, Errors 0, Passed 9` |
| 3 | the same with `'set sql/Test/javaOptions += "-XX:MaxVectorSize=16"'` | the same line |
| 4 | the planted faults, each with command 2 | each fails as its row says |
| 5 | both dry runs of each of the two classes | `exit 0` and `28`, `28`; `exit 0` and `2`, `2` |
| 6 | the four greps of 5.0 | the zone-free class; the `TIMESTAMP_NTZ` fields class and `VarkaTimestampFieldsBaselineBenchmark`; the `TIMESTAMP_NTZ` baseline and `VarkaTimestampCastsBaselineBenchmark`; the two new classes |
| 7 | the checks of 5.0 before a commit; `git status --short` | each exits 0; this file and four new files, two under `$B` and two under `$S` |

**Expectations, to tick.**

- [ ] Two classes, 88 and 52 sections, every `TIMESTAMP` form `Fusion.NONE` and `KERNEL`.
- [ ] Six tests pass at both widths; the planted faults fail as said; four dry runs exit 0.
- [ ] Row 657's greps (4.12) print each class once.
- [ ] Eight files are in `sql/core/benchmarks`, each passing 4.9's checks.
- [ ] Predictions 1 to 3, 5 to 8, 12, 18 and 19 are scored for the two classes.

**Done when** both classes, their suites and eight files are on master. **Not here:** an edit
of the base but none; a section for another cast pair (row 669).

### 5.3 Part C: the arithmetic and the zone functions

**B0.** Part B's lines 1 to 4 and 8, with `varka-655c` in line 1, and:

    ls $B/VarkaTimestampArithmeticBaselineBenchmark.java $B/VarkaTimestampZoneFunctionsBaselineBenchmark.java 2>/dev/null | wc -l   # 0

**The steps.**

1. `$B/VarkaTimestampArithmeticBaselineBenchmark.java`: new. It is part B's fields class's
   file with these differences and no other:
   - the class's name, and `Fields` in the `gh` command, read `Arithmetic`;
   - the comment's first paragraph: "The baseline for the arithmetic over a {@code TIMESTAMP}
     before Varka fuses it (VARKA-655): Spark's row path over an Arrow-cached table, so that
     the rows that fuse a sum or a difference report what they change against a committed
     file.";
   - the second paragraph's first sentence, up to its colon: "The sum with an interval of one
     day, which keeps the local time across a change of offset, is the lead form:"; the word
     "each" after the colon reads "it". A fourth paragraph before the `gh` one: "No sum leaves
     a timestamp's range: the interval column is within 500 days and the quantity within
     1000.";
   - `VIEW` is `"varka_ts_arith"`;
   - `forms()` returns:

            Form.lead("ts@ + INTERVAL '1' DAY", Fusion.NONE, BatchCheck.KERNEL),
            Form.one("ts@ - ts2@", Fusion.NONE),
            Form.one("ts@ + dt#", Fusion.NONE),
            Form.one("ts@ + INTERVAL '1' MONTH", Fusion.NONE),
            Form.one("ts@ + INTERVAL '1' HOUR", Fusion.NONE),
            Form.one("timestampadd(HOUR, q#, ts@)", Fusion.NONE),
            Form.one("timestampadd(DAY, q#, ts@)", Fusion.NONE),
            Form.one("timestampadd(MONTH, q#, ts@)", Fusion.NONE),
            Form.twin("ntz@ + INTERVAL '1' DAY", Fusion.WHOLE),
            Form.twin("ntz@ - ntz2@", Fusion.WHOLE),
            Form.twin("ntz@ + dt#", Fusion.WHOLE),
            Form.twin("ntz@ + INTERVAL '1' MONTH", Fusion.WHOLE),
            Form.twin("timestampadd(HOUR, q#, ntz@)", Fusion.WHOLE),
            Form.twin("timestampadd(DAY, q#, ntz@)", Fusion.WHOLE),
            Form.twin("timestampadd(MONTH, q#, ntz@)", Fusion.WHOLE)

   - `columns()` returns `List.of(new Column("ts", Kind.TS, 0), new Column("ts2", Kind.TS,
     1), new Column("ntz", Kind.NTZ, 0), new Column("ntz2", Kind.NTZ, 1))`;
   - one more override, after `columns()`, whose two SQL strings are 4.4's `dt` and `q`:

            @Override
            List<Free> free() {
              return List.of(
                  new Free("dt", "make_dt_interval(cast(pmod(xxhash64(id, 7), 1001) - 500 as int),"
                      + " 0, 0, cast(cast(pmod(xxhash64(id, 107), 86400000000) as decimal(18, 0))"
                      + " * 0.000001 as decimal(18, 6)))"),
                  new Free("q", "cast(pmod(xxhash64(id, 8), 2001) - 1000 as int)"));
            }

2. `$B/VarkaTimestampZoneFunctionsBaselineBenchmark.java`: new, with the same kind of
   differences from the fields class:
   - the name, and `Fields` in the `gh` command, read `ZoneFunctions`;
   - first paragraph: "The baseline for the functions over a {@code TIMESTAMP} that are about
     a zone or a calendar unit, before Varka fuses them (VARKA-655): Spark's row path over an
     Arrow-cached table, so that the rows that fuse a function report what they change
     against a committed file.";
   - second paragraph's first sentence, up to its colon: "The truncation to a day, whose
     answer is a local midnight turned back into an instant, is the lead form:"; "each" after
     the colon reads "it"; the paragraph's last sentence, on twins, is replaced by "No form
     here has a fused {@code TIMESTAMP_NTZ} twin; the column {@code ntz} is the operand of
     {@code convert_timezone} and of the queries suite's control.";
   - `VIEW` is `"varka_ts_zone"`;
   - `forms()` returns:

            Form.lead("date_trunc('DAY', ts@)", Fusion.NONE, BatchCheck.KERNEL),
            Form.one("date_trunc('HOUR', ts@)", Fusion.NONE),
            Form.one("date_trunc('MONTH', ts@)", Fusion.NONE),
            Form.one("from_utc_timestamp(ts@, 'Europe/Amsterdam')", Fusion.NONE),
            Form.one("to_utc_timestamp(ts@, 'Europe/Amsterdam')", Fusion.NONE),
            Form.one("convert_timezone('Europe/Amsterdam', ntz@)", Fusion.NONE),
            Form.one("months_between(ts@, ts2@)", Fusion.NONE),
            Form.one("make_timestamp(y#, mo, dd, hh, mi, s)", Fusion.NONE),
            Form.one("try_make_timestamp(y#, mo, dd, hh, mi, s)", Fusion.NONE)

   - `columns()` returns `List.of(new Column("ts", Kind.TS, 0), new Column("ts2", Kind.TS,
     1), new Column("ntz", Kind.NTZ, 0))`;
   - `free()` returns the six columns of 4.4's table, in its order, each as `new Free("<name>",
     "<SQL>")` with the SQL of that table's row.

   Neither comment holds the text `hour(`, `AS DATE` or `greatest(`.
3. The two suites, as part B's step 3, with "The TIMESTAMP arithmetic baseline's" and "The
   TIMESTAMP zone functions baseline's", and `sectionCount` 40 and 28.
4. Section 11: `Part C: the arithmetic and the zone functions classes; results pending.`
5. The commands. First commit. Hand-over with the two classes.
6. With `$IN`: 4.9's checks (`N` 40 with notes 14 and 14; `N` 28 with notes 0 and 0). Copy.
   Score predictions 1 to 3, 5, 7, 12, 18 and 19 for the two classes. Second commit.

**The tests.** The trait's three tests in each suite. What only these suites catch: a sum that
leaves the range of a timestamp and raises (the arithmetic suite's first test); a
`make_timestamp` argument out of its range, which would answer a null over the columns with no
null or raise under ANSI (the zone functions suite's first test); a `try_make_timestamp` that
the build's Spark does not have.

**Planted faults.** `new Free("q", "cast(pmod(xxhash64(id, 8), 2001) + 300000000 as int)")` in
the arithmetic class: its first test must fail, since `timestampadd(MONTH, q, ts)` then leaves
the range and raises. `"cast(13 + pmod(xxhash64(id, 21), 12) as int)"` for `mo` in the zone
functions class: its first test must fail, on `make_timestamp` raising or on a null over the
columns with no null.

**The commands.** Part B's, with the two suites and the two classes of this part: command 2
prints `Passed: Total 9, Failed 0, Errors 0, Passed 9` with part A's suite beside the two;
the dry runs print `14`, `14` and `0`, `0`; the four greps print what they printed after part
B and, in the last, these two classes too; `git status --short` before the first commit is
this file and four new files, two under `$B` and two under `$S`.

**Done when** both classes, their suites and eight files are on master. **Not here:** a
section for `months_between` over a zone column or any zone read from a column (out of the
milestone, `m9/PLAN.md` 1.3).

### 5.4 Part D: the `TIMESTAMP_NTZ` files, run again

No code. The three classes are `VarkaTimestampNtzBaselineBenchmark`,
`VarkaTimestampNtzArithmeticBaselineBenchmark` and `VarkaTimestampNtzFieldsBaselineBenchmark`.

**B0.**

    git branch -a --list '*varka-655d*' | wc -l                                 # 1: 0
    ls $R/VarkaTimestampNtz*BaselineBenchmark-jdk25-runner-epyc7763*.txt | wc -l   # 2: 12
    for f in $R/VarkaTimestampNtz*BaselineBenchmark-jdk25-runner-epyc7763*-results.txt; do for c in $(git log --format=%h -- "$f"); do if [ "$(git show "${c}:${f}" | grep -c ': fuses')" = 0 ]; then echo "$c $(git log -1 --format=%cs "$c") $(basename "$f")"; break; fi; done; done | tee $T/before.txt   # 3: six lines
    flock "$VARKA_HEAVY_LOCK" build/sbt -batch 'sql/testOnly *VarkaTimestampNtzBaselineQueriesSuite *VarkaTimestampNtzArithmeticBaselineQueriesSuite *VarkaTimestampNtzFieldsBaselineQueriesSuite'   # 4: Failed 0, Errors 0

Line 3 walks each file's commits from the newest and prints the first whose copy of the file
has no `: fuses` line: the baseline before the kernels (4.11). It must print six lines, one a
file; on master `84d6b2aff10` they are the six of 4.11. Fewer than six lines is a stop: a
file has no commit without a fused section, so its baseline is lost; report the file.

**The steps.**

1. Section 11: `Part D: the three TIMESTAMP_NTZ classes dispatched again; results pending.`,
   then the line "The baseline before the kernels, by file:" and the six lines of
   `$T/before.txt`, indented four spaces. First commit. Hand-over with the three classes.
2. The lead dispatches as 4.9 says, six runs. With `$IN`: 4.9's checks on the twelve files,
   with these numbers in place of its table:

    | class | `2 * N` rule lines | notes of fused sections, wide | 128-bit |
    | :--- | ---: | ---: | ---: |
    | `VarkaTimestampNtzBaselineBenchmark` | 16 | 8 | 6 |
    | `VarkaTimestampNtzArithmeticBaselineBenchmark` | 24 | 8 | 8 |
    | `VarkaTimestampNtzFieldsBaselineBenchmark` | 28 | 14 | 14 |

   The note counted is the line that ends `: fuses whole on the varka on arm, so it times a
   kernel there, not the row path`; 4.9's check "only the twins fused" reads here: `grep -c ":
   fuses" $W` prints the table's number for the file's width.
3. Copy the twelve files: `cp $IN/<file> sql/core/benchmarks/<file>`. Then, for each of the
   six results files, with `<c>` its commit of `$T/before.txt`: `python3
   dev/varka_bench_diff.py --git <c> sql/core/benchmarks/<file> --control "spark row path" |
   tee $T/diff-<file>`. Without `--control` the tool's own pattern matches no case of these
   files and prints no controls. With it, the output starts as this, which is the committed
   128-bit file of `VarkaTimestampNtzBaselineBenchmark` against its baseline `8f171c54722`
   (rates in millions of rows a second; the first three of its eight control lines):

        -- controls (spark row path); if these moved, the machine moved --
        case                                                                                                                before      after    change
        [TIMESTAMP_NTZ comparison, no nulls over 2000000 Arrow-cached rows] spark row path (varka off)                        18.8       17.5     -6.5% <--
        [TIMESTAMP_NTZ arithmetic chain, no nulls over 2000000 Arrow-cached rows] spark row path (varka off)                   3.6        3.7     +2.5%
        [TIMESTAMP_NTZ cast to DATE, no nulls over 2000000 Arrow-cached rows] spark row path (varka off)                      14.4       14.2     -1.3%

   then a block `-- moved by at least 3% --` with the `varka on` cases, which are the
   kernels' gains. Read the controls first: the `spark row path (varka off)` case of a
   section runs the same code in both files, so its rate must be within a factor of 1.3 of
   the baseline's, either way: a `change` between -23% and +30%. The tool's `<--` marks 3%
   and is not this rule.
   A file with a control outside that is not committed: `git checkout -- <the two files of
   that class and width>`, report the section and both rates, and the lead dispatches that
   class and width once more. If the second run's control is outside it too, stop: commit
   nothing of that class, and report both runs' rates for the section; the lead decides.
4. `python3 dev/varka_bench_diff.py --git HEAD sql/core/benchmarks/<file> --requote | tee
   $T/requote-<file>` for each of the six. Under each moved case it lists the document lines
   that hold the old number, as `    <path>:<line>: <text>`. Most are chance: `3.6` is in
   hundreds of lines that are not about these files. So a listed line **counts** only if its
   text names the class (`VarkaTimestampNtz`) or the case's section (the words of its title
   before the comma, such as `TIMESTAMP_NTZ comparison`, or its expression, such as
   `year(ntz)`); any other hit is left as it is and is not counted. Count the lines that
   count and are outside the plans:

        cat $T/requote-* | grep "^ *\(docs/\|README\|SKILLS\|sql/varka/AGENTS\)" | grep -c "VarkaTimestampNtz\|TIMESTAMP_NTZ\|(ntz"

   then read the lines that pipeline counted and drop any that the rule above does not hold
   for; the count is what is left.
   - A line that counts under `sql/varka/plans/` is a record of its date: leave it, and name
     its plan file once in section 11 under "Quoted on purpose, as the number before the
     run:".
   - A line that counts in `docs/`, `README.md`, `SKILLS.md` or `sql/varka/AGENTS.md` is
     requoted from the new file, if the count is at most 40. (`SKILLS.md` is generated from
     `sql/varka/skills/`: requote the line in its source file there and run `python3
     dev/varka_toc.py SKILLS.md --from sql/varka/skills`.)
   - If the count is over 40, requote none: list the files and the count in section 11 under
     "Owed: a requote of", and report it. The lead opens a pull request for the requote
     alone. This keeps the part under 140 counted lines.
5. At the end of each of `sql/varka/plans/m8/VARKA-338.md`, `VARKA-339.md` and `VARKA-340.md`,
   after a blank line, one line, with `<date>` the day of this commit as `date -u '+%-d %B
   %Y'` prints it and `<n>` the pull request's number: `*<date>, VARKA-655 part D (#<n>):*
   the row's baseline class was run again with its kernels on, at both widths on the EPYC
   7763; the commit that holds the baseline before the kernels is in \`m9/VARKA-655.md\` 11,
   and \`dev/varka_bench_diff.py --git <that commit> <file>\` prints each section's ratio.`
6. Score predictions 9 to 12 in section 11, each with its numbers from the `$T/diff-*` files
   and from `python3 dev/varka_push_check.py`. Second commit. `git status --short`: twelve
   files of `sql/core/benchmarks`, this file, the three plans of milestone 8, and any file
   step 4 requoted.

**Done when** the twelve files are on master, the six baseline commits are in section 11 and
predictions 9 to 12 are scored. **Not here:** an edit of a class or of a suite; a band file; a
table of every section's ratio, which the command of step 5's line prints from git.

### 5.5 Part E: the timestamp surface

**B0.**

    git branch -a --list '*varka-655e*' | wc -l                                             # 1: 0
    ls $SB/Timestamps.java $SB/TimestampSurfaceBenchmark.java $ST/TimestampsTest.java 2>/dev/null | wc -l   # 2: 0
    grep -c "ALL, DATES, TIMES, DOUBLES;" $SB/DateSurfaceBenchmark.java                      # 3: 1
    grep -c "public record Entry(String label, String projection, String filter, boolean expectFused)" $SB/Surface.java   # 4: 1
    grep -n "math)    main_class=org.apache.spark.sql.varka.bench.MathSurfaceBenchmark; stem=MathSurface ;;" dev/varka_bench_surface.sh   # 5: one line
    grep -c "          - 'math'" .github/workflows/varka-surface-benchmark.yml                # 6: 1
    grep -c -F '"varka-off-jdk25$SUFFIX=$PWD:$J25$OPEN" \' .github/workflows/varka-surface-benchmark.yml   # 7: 1
    grep -c 'for (String view : List.of("varka_times", "varka_doubles")) {' $ST/SurfaceTest.java   # 8: 1
    grep -c "2.11); the other surfaces stay here.\*" sql/varka/plans/m10/SCOPE.md             # 9: 1

**The steps.**

1. `$SB/Timestamps.java`: new, the first 22 lines of `$SB/Maths.java` (its header, the blank
   line 17, `package org.apache.spark.sql.varka.bench;`, a blank line, `import
   java.util.List;` and a blank line), then a `public final class Timestamps` with a private
   constructor, as `Maths` has. Its comment says: the
   timestamp surface (VARKA-655), one entry an expression over a `TIMESTAMP` under
   `America/Los_Angeles`; the table (4.10); that no entry fuses yet, so each is built by
   `notYet` and no arm holds it to a kernel; and that the row that fuses an entry turns it
   into `Surface.Entry.projection` or `Surface.Entry.filter`. Its members:

        public static final List<Surface.Entry> ENTRIES = List.of(
            notYet("hour(ts)"),
            notYet("minute(ts)"),
            notYet("second(ts)"),
            notYet("year(ts)"),
            notYet("month(ts)"),
            notYet("dayofweek(ts)"),
            notYet("CAST(ts AS DATE)"),
            notYet("date_trunc('DAY', ts)"),
            notYet("date_trunc('HOUR', ts)"),
            notYet("ts + INTERVAL '1' DAY"),
            notYet("ts + INTERVAL '1' MONTH"),
            notYet("from_utc_timestamp(ts, 'Europe/Amsterdam')"),
            notYet("unix_micros(ts)"),
            notYet("greatest(ts, ts2)"),
            notYet("CASE WHEN ts < ts2 THEN ts ELSE ts2 END"),
            new Surface.Entry("ts < ts2", null, "ts < ts2", false));

        /** A projection that does not fuse yet: no arm holds it to a kernel. */
        private static Surface.Entry notYet(String expr) {
          return new Surface.Entry(expr, expr, null, false);
        }

2. `$SB/TimestampSurfaceBenchmark.java`: new, a copy of `$SB/MathSurfaceBenchmark.java` with
   the class named `TimestampSurfaceBenchmark`, the call `DateSurfaceBenchmark.run(argv,
   Timestamps.ENTRIES, "timestamp", "VarkaTimestampSurface",
   DateSurfaceBenchmark.TableShape.TIMESTAMPS);`, and the comment: "The timestamp surface:
   {@link Timestamps#ENTRIES} over {@code varka_timestamps}, through the same driver as the
   date surface, so the residency guard, the fixed-share rule, the {@code EXPLAIN} check, the
   checksums and the provenance block apply unchanged. Selected by {@code
   dev/varka_bench_surface.sh --benchmark timestamp}; its files are {@code
   sql/varka/bench/benchmarks/TimestampSurface-<label>-results.txt} (VARKA-655)."
3. `$SB/DateSurfaceBenchmark.java`, in `TableShape` and below it. Each switch there has no
   default, so the compiler names any one missed:
   - `ALL, DATES, TIMES, DOUBLES;` becomes `ALL, DATES, TIMES, DOUBLES, TIMESTAMPS;`
   - `of`: a case `case "timestamps" -> TIMESTAMPS;` after the `doubles` one, and the message
     becomes `"--table-columns takes all, dates, times, doubles or timestamps, not '" + s +
     "'"`;
   - `columns`: `case TIMESTAMPS -> java.util.List.of("ts", "ts2");`
   - `tableName`: `case TIMESTAMPS -> "varka_timestamps";`
   - `filterColumn`: `case TIMESTAMPS -> "ts";`
   - `provenance`: `case TIMESTAMPS -> "timestamps (2)";`
   - `buildTable`, after the `DOUBLES` branch, the same three lines for `TIMESTAMPS` calling
     `buildTimestampsTable(spark, rows, partitions, level)`;
   - `buildParquetTable`: the condition gains `|| shape == TableShape.TIMESTAMPS`, and the
     last line of its comment, `   * surface is measured over the cache alone.`, becomes `   *
     and the timestamp surfaces are measured over the cache alone.` (one line);
   - after `buildDoublesTable`, the new method:

            /**
             * The timestamp surface's table, {@code varka_timestamps} (VARKA-655): {@code ts} and
             * {@code ts2}, two {@code TIMESTAMP} columns over the 365 days from 1 January 2024 UTC,
             * the second and the microsecond of each value picked by hashes of the row id, so every
             * batch spans both of the year's changes of offset in {@code America/Los_Angeles}. It is
             * {@code VarkaTimestampBaselineBase}'s year in no order. Every 31st row is null, at rows
             * 0 and 15 of each 31 so the two never share a null row.
             *
             * <p>The session's time zone is set here, as {@link #buildTimesTable} sets the
             * {@code TIME} flag: every arm, stock or fork, then reads a field in the same zone, and
             * an arm that forgot the setting cannot disagree with the others' checksums.
             */
            static void buildTimestampsTable(SparkSession spark, long rows, int partitions,
                StorageLevel level) {
              spark.conf().set("spark.sql.session.timeZone", "America/Los_Angeles");
              String micros = "timestamp_micros((1704067200L + pmod(xxhash64(id, %d), 31536000L))"
                  + " * 1000000L + pmod(xxhash64(id, %d), 1000000L))";
              String select = "CASE WHEN id %% 31 = 0 THEN NULL ELSE "
                  + String.format(Locale.ROOT, micros, 0, 100) + " END AS ts,"
                  + " CASE WHEN id %% 31 = 15 THEN NULL ELSE "
                  + String.format(Locale.ROOT, micros, 1, 101) + " END AS ts2";
              spark.sql(String.format(Locale.ROOT,
                  "SELECT " + select + " FROM range(0, %d, 1, %d)", rows, partitions))
                  .createOrReplaceTempView("varka_timestamps");
              // MEMORY_ONLY for the reason the date table gives above.
              spark.catalog().cacheTable("varka_timestamps", level);
              spark.sql("SELECT count(*) FROM varka_timestamps").collect();
            }

   - in the class comment, the line ` * surface ({@link TimeSurfaceBenchmark}) and the math
     surface ({@link MathSurfaceBenchmark});` becomes the two lines ` * surface ({@link
     TimeSurfaceBenchmark}), the math surface ({@link MathSurfaceBenchmark}) and the` and ` *
     timestamp surface ({@link TimestampSurfaceBenchmark});`.
4. `dev/varka_bench_surface.sh`:
   - `#       [--benchmark surface|chains|time|timechains|ladder|math] \` gains `|timestamp`
     before `]`, and `#       [--table-columns all|dates|times|doubles] \` gains `|timestamps`;
   - after the comment line `# against Varka's kernel.` and its `#` line, five new lines:

            # --benchmark timestamp runs Timestamps, the timestamp surface (VARKA-655), writing
            # TimestampSurface-<label>-results.txt over its own table, varka_timestamps: two TIMESTAMP
            # columns over the year 2024 in no order. The driver sets spark.sql.session.timeZone to
            # America/Los_Angeles on every arm, so each reads the zone's two changes of offset.
            #

   - the comment line `# DateChain, TimeSurface, TimeChain, VarkaLadder or MathSurface by
     --benchmark. SPARK_HOME is a` becomes the two lines `# DateChain, TimeSurface, TimeChain,
     VarkaLadder, MathSurface or TimestampSurface by` and `# --benchmark. SPARK_HOME is a`;
   - after the `math)` case, the line `  timestamp) main_class=
     org.apache.spark.sql.varka.bench.TimestampSurfaceBenchmark; stem=TimestampSurface ;;`
     (one line, with no break after `main_class=`);
   - the message `"--benchmark wants surface, chains, time, timechains, ladder or math," \`
     becomes `"--benchmark wants surface, chains, time, timechains, ladder, math or
     timestamp," \` (one line);
   - `if [ "$benchmark" = math ] && [ "$input" = parquet ]; then` becomes `if { [ "$benchmark"
     = math ] || [ "$benchmark" = timestamp ]; } && [ "$input" = parquet ]; then` (one line),
     the `echo` under it becomes `echo "--benchmark $benchmark reads its cached table; drop
     --input parquet" >&2`, and the two comment lines above the `if` become `# The math and
     the timestamp tables are measured over the cache only (VARKA-633, VARKA-655),` and `#
     refused here rather than in the first arm's JVM after the machine checks.`
5. `.github/workflows/varka-surface-benchmark.yml`:
   - under the `benchmark` input's `options`, after `          - 'math'`, the line `          -
     'timestamp'`; its `description` gains, before the closing quote, ` "timestamp" is one
     entry per TIMESTAMP expression under America/Los_Angeles, with a stock and a fork arm
     over the Arrow cache beside the others (VARKA-655).`;
   - in the step `Run the surface`, the line `        # Varka last: the comparison table's
     ratios are the last file against the others.` gets these lines before it:

            # The timestamp surface's two cache arms (VARKA-655): the release and the fork with
            # the Arrow cache and no engine, so that the cache format's share is read apart from
            # the kernels'. The other surfaces keep their arms (m10/SCOPE.md item 132).
            STOCK_CACHE=(); FORK_CACHE=()
            if [ "$BENCHMARK" = timestamp ]; then
              STOCK_CACHE=("spark-${STOCK_VERSION}-arrow-cache-jdk25$SUFFIX=$STOCK:$J25:arrow-cache$MORE")
              FORK_CACHE=("varka-cache-jdk25$SUFFIX=$PWD:$J25:arrow-cache$MORE")
            fi

     indented as the lines around them (eight spaces before `#`, `STOCK_CACHE` and `if`). The
     call's last two lines, `"varka-off-jdk25$SUFFIX=$PWD:$J25$OPEN" \` and
     `"varka-jdk25$SUFFIX=$PWD:$J25:varka$MORE"` with nothing after it, become four, each at
     the indentation of those lines (ten spaces):

            "varka-off-jdk25$SUFFIX=$PWD:$J25$OPEN" \
            "${FORK_CACHE[@]}" \
            "varka-jdk25$SUFFIX=$PWD:$J25:varka$MORE" \
            "${STOCK_CACHE[@]}"

     (the `"${OTHERS[@]}" \` line above them stays). The stock Arrow arm is
     the call's last argument, for 4.10's reason. The comment line `# Varka last: the
     comparison table's ratios are the last file against the others.` becomes `# The stock
     Arrow arm last where there is one, so that an arm that has never run cannot stop the
     others; else Varka last: the comparison table's ratios are the last file against the
     others.`, wrapped as a comment of that block within 100 columns.
6. `$ST/TimestampsTest.java`: new, modelled on `$ST/MathsTest.java` line by line: the same
   imports, `start()` building `TableShape.TIMESTAMPS` at 1,000 rows in 2 partitions, and
   `stop()` that uncaches and drops `varka_timestamps`, then calls
   `spark.conf().unset("spark.sql.session.timeZone")`, then `BenchSession.stop(spark)`. Seven
   tests:

    | test method | inputs | expected | the failure only it catches |
    | :--- | :--- | :--- | :--- |
    | `everyEntryRunsOnTheStockRelease` | each entry: its `projectionQuery`, or for the filter its `filterColumnarQuery` and `filterQuery`, over the 1,000 rows | a projection has one column and 1,000 rows; the filter's two queries run; `plansVarka` is `Fusion.PLAIN` for each | an entry the module's stock release cannot parse |
    | `theTableHasTheShapeTheEntriesAssume` | `SELECT count(*), count(ts), count(ts2), min(unix_micros(ts)), max(unix_micros(ts)), count(CASE WHEN ts IS NULL AND ts2 IS NULL THEN 1 END), count(DISTINCT unix_micros(from_utc_timestamp(ts, 'America/Los_Angeles')) - unix_micros(ts)) FROM varka_timestamps` | 1000; 967; 968; at least 1704067200000000; under 1735603200000000; 0; 2. And `spark.conf().get("spark.sql.session.timeZone")` is `America/Los_Angeles` | a table outside 2024, or one that spans one offset only; a builder that does not set the zone |
    | `noEntryIsHeldToAKernelYet` | `Timestamps.ENTRIES` | 16 entries, each `expectFused()` false | an entry held to `--expect-fused` before its kernel, which would fail the Varka arm |
    | `labelsAreUniqueAndNotSharedWithTheOtherSurfaces` | the labels of the four entry lists | 16 distinct, none in `Surface.ENTRIES`, `Times.ENTRIES` or `Maths.ENTRIES` | a label the merge and the diff would confuse |
    | `everyEntryIsSatisfiedByTheTimestampsTableAndRefusedByTheDateOne` | `requireColumns(e, TIMESTAMPS)` and `(e, ALL)` | the first returns, the second throws `IllegalArgumentException` | an entry that reads another table's column |
    | `theTimestampsShapeBuildsTheColumnsItClaims` | the view's field names; the shape's four answers | `ts`, `ts2`; `varka_timestamps`; `ts`; `TableShape.of("timestamps")` | a shape whose names drift from the table |
    | `theTimestampSurfaceIsMeasuredOverTheCacheOnly` | `buildParquetTable(spark, 1_000L, 2, TIMESTAMPS, dir)` | throws `IllegalArgumentException` | a Parquet run of a table that has none |

   No entry's text holds a token that is another shape's column (`d`, `d2`, `i`, `ymm`, `ymy`,
   `ym`, `t`, `t2`, `dt`, `dt2`, `l`, `l2`, `x`, `u`): `requireColumns` would refuse it.
7. `$ST/SurfaceTest.java`, in the `finally` of `eachTableShapeBuildsTheColumnsItClaims`, which
   builds every shape and so now sets the session zone too: `List.of("varka_times",
   "varka_doubles")` becomes `List.of("varka_times", "varka_doubles", "varka_timestamps")`,
   and after that loop's closing brace, before the `DateSurfaceBenchmark.buildTable` call, a
   new line `spark.conf().unset("spark.sql.session.timeZone");`. The comment above the loop
   gains the sentence "The timestamps table goes too, and the session zone it set." Then the
   body of that `finally` (the loop, the `unset` and the `buildTable` call) moves, unchanged,
   into a new `private static void restoreFixture()` of the class, which the `finally`
   calls, and the class gains one test after that one:

            /** No table shape may leave its session setting behind for the suites after it. */
            @Test
            public void theSessionZoneIsNotLeftSet() {
              try {
                DateSurfaceBenchmark.buildTable(spark, 1_000L, 1,
                    org.apache.spark.storage.StorageLevel.MEMORY_ONLY(),
                    DateSurfaceBenchmark.TableShape.TIMESTAMPS);
                assertTrue(spark.conf().getOption("spark.sql.session.timeZone").isDefined());
              } finally {
                restoreFixture();
              }
              assertTrue(spark.conf().getOption("spark.sql.session.timeZone").isEmpty());
            }

   `restoreFixture` drops the three views whether or not each exists, as the loop did:
   `uncacheTable` of a view that was not built throws, so in the helper each view's two
   calls are guarded by `if (spark.catalog().tableExists(view))`. `SurfaceTest` then has one
   test more than on master. `$ST/TimestampsTest.java`'s `stop()` unsets the zone in a
   `finally` around its uncache and drop, so that a failed drop cannot leave the zone set
   for the module's other suites.
8. In the second commit, not the first, since it needs the pull request's number:
   `sql/varka/plans/m10/SCOPE.md`, item 132: after the line that ends `2.11); the other
   surfaces stay here.*`, a new line, with `<date>` as `date -u '+%-d %B %Y'` prints it on
   that day and `<n>` the number the lead gave with `$IN`: `*<date>: built for the timestamp
   surface by VARKA-655's part E (#<n>): the workflow's \`timestamp\` benchmark runs the
   release and the fork over the Arrow cache beside the other arms.*`
9. Section 11: `Part E: the timestamp surface; first run pending.` The commands. First commit.
   Hand-over: the branch, the commit and the benchmark's name, `timestamp`.
10. **The lead dispatches**, on the branch's head, as it did for the math surface:

        gh workflow run varka-surface-benchmark.yml -R vecbricks/varka --ref <branch> \
          -f stop-after=build -f benchmark=timestamp
        gh workflow run varka-surface-benchmark.yml -R vecbricks/varka --ref <branch> \
          -f stop-after=run -f benchmark=timestamp -f require-datapath=any \
          -f expected-cpu="AMD EPYC 7763" -f rows=100000000 -f partitions=1 -f driver-memory=6g \
          -f label-suffix=-zen3-unfused -f create-commit=false
        gh workflow run varka-surface-benchmark.yml -R vecbricks/varka --ref <branch> \
          -f stop-after=run -f benchmark=timestamp -f require-datapath=any \
          -f expected-cpu="AMD EPYC 7763" -f rows=100000000 -f partitions=1 -f driver-memory=6g \
          -f extra-confs=spark.driver.extraJavaOptions=-XX:MaxVectorSize=16 \
          -f label-suffix=-zen3-128bit-unfused -f create-commit=false

    and gives the builder `$IN` with the files of the two artifacts, as the runs wrote them:
    `TimestampSurface-<arm>-zen3-unfused-results.txt` and
    `TimestampSurface-<arm>-zen3-128bit-unfused-results.txt` for the arms of 4.10, twelve
    files, or ten with `$IN/stock-arrow-error.txt` where the stock Arrow arm died (4.10's
    first case, which the lead tells from the third by the job log's last `== <label>:`
    line). A run whose step is red for any other dead arm
    is 4.10's third case: the builder gets the arm and its error, commits nothing, and stops.
11. **The builder checks each file:** `grep -c "^cpu: *AMD EPYC 7763" <file>` is 1; `grep
    "^benchmark:" <file>` ends `timestamp`; `grep "^entries:" <file>` ends `16`; `grep
    "^rows:" <file>` ends `100000000`; `grep "^MaxVectorSize:" <file>` ends `16` in a
    `128bit` file and `32` in the other. A file that fails one is not committed; the builder
    reports it. Then the checksums, for each width, with `W` empty or `-128bit`:

        for f in $IN/TimestampSurface-*-zen3$W-unfused-results.txt; do
          echo "$(grep "^# checksum" $f | md5sum | cut -c1-32) $(basename $f)"; done | sort

    The first column is one value in every line where the arms agree (prediction 13 is then
    yes). Where they do not, the files fall into groups by that value. If the one file that
    stands alone is the stock Arrow arm's, it is 4.10's case of a serializer that does not
    agree: go on, and leave that file out of every `--table` below, since the tool exits on
    a disagreeing checksum and prints no table. Any other grouping is a stop: an arm computed
    another answer; report the groups. Either way, find the entries that differ, with `A` a
    file of the larger group and `B` the file that stands apart:

        diff <(grep '^# checksum' A) <(grep '^# checksum' B)

    The driver writes one `# checksum` line an entry, in the order of `Timestamps.ENTRIES`,
    so the `diff`'s line numbers are the entries' places in that list; name those entries in
    the report or in section 11.
12. Copy the files into `sql/varka/bench/benchmarks/`. Then, for each width, three tables,
    each of two arms that agree, saved under `$T`:

        D=sql/varka/bench/benchmarks; V=-zen3$W-unfused
        python3 dev/varka_bench_diff.py --table \
          rel=$D/TimestampSurface-spark-4.3.0-jdk25$V-results.txt \
          relcache=$D/TimestampSurface-spark-4.3.0-arrow-cache-jdk25$V-results.txt | tee $T/release$W.txt
        python3 dev/varka_bench_diff.py --table \
          off=$D/TimestampSurface-varka-off-jdk25$V-results.txt \
          cache=$D/TimestampSurface-varka-cache-jdk25$V-results.txt | tee $T/fork$W.txt
        python3 dev/varka_bench_diff.py --table \
          rel=$D/TimestampSurface-spark-4.3.0-jdk25$V-results.txt \
          off=$D/TimestampSurface-varka-off-jdk25$V-results.txt | tee $T/same$W.txt

    Under `**wall time**`, the last column of a row is the second arm's rate over the first's
    (4.10). `release` gives the release's format share, `fork` the fork's, `same` the fork
    with Varka off over the release. Where the stock Arrow arm died or disagrees, the first
    command is not run, or is run with `relcache=<file>` alone, which prints that arm's rates
    with no comparison; the release's format share is then not computed.
13. The quotients, by this helper, saved as `$T/q.py` (tested on a real `--table` output of
    the math surface, where it printed nine entries and marked two `OUT`):

        """Usage: python3 -I q.py RELEASE.txt FORK.txt [SAME.txt]

        Reads the `**wall time**` tables that `dev/varka_bench_diff.py --table` printed for two arms
        each, and prints, for every entry, the release's format share, the fork's, and their quotient
        with `ok` where it is between 1 / 1.3 and 1.3 and `OUT` where it is not. With SAME.txt it also
        prints the fork with Varka off over the release, `ok` between 1 / 1.15 and 1.15."""
        import re
        import sys

        SHAPES = ("projection, columnar consumer", "filter, columnar consumer")


        def shares(path):
            out, wall = {}, False
            for line in open(path):
                if line.startswith("**"):
                    wall = line.strip() == "**wall time**"
                    continue
                cells = [c.strip() for c in line.strip().strip("|").split("|")]
                if not wall or len(cells) < 6 or cells[1] not in SHAPES:
                    continue
                m = re.fullmatch(r"\*\*([0-9.]+)x\*\*", cells[-1])
                if m:
                    out[cells[0].strip("`")] = float(m.group(1))
            return out


        def verdict(value, bound):
            return "ok" if 1 / bound <= value <= bound else "OUT"


        release, fork = shares(sys.argv[1]), shares(sys.argv[2])
        same = shares(sys.argv[3]) if len(sys.argv) > 3 else {}
        bad = 0
        for entry in release:
            if entry not in fork:
                print(f"{entry}: in the first file only")
                bad += 1
                continue
            q = release[entry] / fork[entry]
            line = f"{entry} | {release[entry]:.2f} | {fork[entry]:.2f} | {q:.2f} {verdict(q, 1.3)}"
            bad += verdict(q, 1.3) == "OUT"
            if entry in same:
                line += f" | {same[entry]:.2f} {verdict(same[entry], 1.15)}"
            print(line)
        print(f"{len(release)} entries, {bad} outside 1.3")

    For each width: `python3 -I $T/q.py $T/release$W.txt $T/fork$W.txt $T/same$W.txt | tee
    $T/quotients$W.txt`. Each line is `<entry> | <release's format share> | <fork's format
    share> | <quotient> ok or OUT | <fork off over release> ok or OUT`, and the last line
    counts the entries and those outside 1.3. Where the first table was not made (the stock
    Arrow arm died or disagrees), run `python3 -I $T/q.py $T/same$W.txt $T/same$W.txt`
    instead: its second column is then the fork with Varka off over the release, and its
    quotient column, always 1.00, is ignored.
14. Section 11, under `Part E:`: the 16 lines of `$T/quotients.txt` as a table with the heads
    `entry | release's format share | fork's format share | quotient | fork off over
    release`; the same for `$T/quotients-128bit.txt`; 4.10's verdict in its words; and the
    scores of predictions 12 to 15: 13 by step 11's listing, 14 by the 256-bit quotients
    (yes if the helper's last line reads `16 entries, 0 outside 1.3`; "not scored" where the
    stock Arrow arm died or disagrees), 15 by the last column at 256 bits (yes if no line
    holds `OUT` there). Then step 8's note. Second commit.

**The commands.**

| # | command | must print |
| ---: | :--- | :--- |
| 1 | `flock "$VARKA_HEAVY_LOCK" ./build/mvn -f sql/varka/bench/pom.xml test` | `BUILD SUCCESS`, and the line `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0` that ends `in org.apache.spark.sql.varka.bench.TimestampsTest`; `SurfaceTest`'s line reads one test more than on master (step 7) |
| 2 | `dev/varka_bench_surface.sh --benchmark timestamp --input parquet x=/tmp:/tmp; echo "exit $?"` | `--benchmark timestamp reads its cached table; drop --input parquet` and `exit 2` |
| 3 | `dev/varka_bench_surface.sh --benchmark nope x=/tmp:/tmp; echo "exit $?"` | `--benchmark wants surface, chains, time, timechains, ladder, math or timestamp, got 'nope'` and `exit 2` |
| 4 | `bash -n dev/varka_bench_surface.sh; echo "exit $?"; python3 -c "import yaml; yaml.safe_load(open('.github/workflows/varka-surface-benchmark.yml'))"; echo "exit $?"` | `exit 0` twice |
| 5 | `grep -n -F -A1 '"varka-jdk25$SUFFIX=$PWD:$J25:varka$MORE" \' .github/workflows/varka-surface-benchmark.yml` | two lines, the second `"${STOCK_CACHE[@]}"`: the stock Arrow arm is the call's last |
| 6 | the checks of 5.0 before a commit; `git status --short` | each exits 0; three new files and five changed: `DateSurfaceBenchmark.java`, `SurfaceTest.java`, the script, the workflow, this file. `m10/SCOPE.md` is the second commit's |

**Planted faults.** `notYet("hour(ts)")` made `Surface.Entry.projection("hour(ts)")`:
`noEntryIsHeldToAKernelYet` must fail. The `spark.conf().set(...)` line of
`buildTimestampsTable` removed: `theTableHasTheShapeTheEntriesAssume` must fail on the session
zone's value. The `unset` line of `SurfaceTest.restoreFixture` removed: step 7's test
`theSessionZoneIsNotLeftSet` must fail on its last assertion. (`RuntimeConfig.getOption`
returns a Scala `Option`; `isDefined()` and `isEmpty()` are its methods.)

**Done when** the surface's code, its test and the twelve files of the first run are on
master, and section 11 holds 4.10's verdict. **Not here:** an edit of `Surface`, `Times` or
`Maths`; a change of the stock version; a README section, which is the post's (row 676).

### 5.6 Part F: the nanosecond zone-free forms and fields

**B0, the premises.**

    git branch -a --list '*varka-655f*' | wc -l                                            # 1: 0
    grep -c "static List<Timed> grid(String view, List<Form> forms)" $B/VarkaTimestampBaselineBase.java   # 2: 1
    grep -c "protected def settingKeys: Set\[String\] = Set.empty" $S/VarkaTimestampBaselineQueries.scala   # 3: 1
    grep -c "NANOS\|LTZ9\|NTZ9" $B/VarkaTimestampBaselineBase.java                          # 4: 0
    grep -c "val TIMESTAMP_NANOS_TYPES_ENABLED" sql/catalyst/src/main/scala/org/apache/spark/sql/internal/SQLConf.scala   # 5: 1
    grep -c "timestamp_nanos(1230219000123456789)" sql/core/src/test/resources/sql-tests/results/timestamp-ltz-nanos.sql.out   # 6: 1 or more
    python3 $T/twins.py                                                                    # 7: 28 twins, 0 missing
    grep -c "Nanos" sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaTypes.java   # 8: 0: no kernel reads the types

Line 8 printing above 0 means a nanosecond row has started. Go on: the suite decides (5.0's
rule for a form that does not plan `NONE`). Line 7 covers every twin of parts F and G, under
its coverage row's spelling (4.7).

**The steps.**

1. `$B/VarkaTimestampBaselineBase.java`:
   - `Kind`: `DOUBLE_SECONDS("cast(%s as double) / 1000000.0D");` ends with a comma, and the
     three constants of 4.8 follow, the last ending with the semicolon. The enum's comment
     becomes "What a column holds, as SQL over the microseconds {@code %s}. None reads a zone
     but {@code NTZ9}, the local time of an instant in the session zone the table is cached
     under."
   - `Form`, after `twin`, three factories:

            /** A {@code TIMESTAMP_LTZ(9)} form in the six cells of {@link #LEAD}. */
            static Form nanosLead(String select, Fusion fusion, BatchCheck tenYears) {
              return new Form("TIMESTAMP_LTZ(9)", true, select, "", LEAD, fusion, tenYears);
            }

            /**
             * A {@code TIMESTAMP_LTZ(9)} form in {@link #G7} alone; a filter where {@code where}
             * is set.
             */
            static Form nanos(String select, String where, Fusion fusion) {
              return new Form("TIMESTAMP_LTZ(9)", true, select, where, List.of(G7), fusion,
                  BatchCheck.KERNEL);
            }

            /** A {@code TIMESTAMP_NTZ(9)} form, which reads no zone, over a year in no order. */
            static Form nanosNtz(String select, String where, Fusion fusion) {
              return new Form("TIMESTAMP_NTZ(9)", false, select, where, List.of(G7), fusion,
                  BatchCheck.KERNEL);
            }

   - `$S/VarkaTimestampBaselineQueries.scala`, after the line `private val rows = 2000`, a
     member a nanosecond suite overrides, and after the third test, a fourth that only such a
     suite registers:

            /** Whether the view's `ts` columns are nanosecond timestamps. */
            protected def nanos: Boolean = false

            if (nanos) {
              test(s"the nanosecond columns of $family hold nanoseconds") {
                withZone(VarkaTimestampBaselineBase.UTC, rows) {
                  val r = spark.sql("select typeof(ts_y) as type, " +
                    s"count(distinct unix_nanos(ts_y) % 1000) as nanos from $view").head()
                  assert(r.getAs[String]("type") === "timestamp_ltz(9)")
                  assert(r.getAs[Long]("nanos") > 500)
                }
              }
            }

     A microsecond suite does not override `nanos`, so it keeps its three tests.
2. `$B/VarkaTimestampNanosBaselineBenchmark.java`: new, the fields class's file (part B) with
   these differences:
   - the name, and `Fields` in the `gh` command, read `Nanos`;
   - the comment's first two paragraphs are replaced by: "The baseline for the expressions
     over the nanosecond timestamps that read no time zone, before Varka reads the types
     (VARKA-655): Spark's row path over an Arrow-cached table, which stores a value as a
     struct of a long of microseconds and a short of nanoseconds, so that the rows that build
     a layout for the types report what they change against a committed file." and "The forms
     run over a {@code TIMESTAMP_LTZ(9)} under the zone with changes and over a {@code
     TIMESTAMP_NTZ(9)}, each over a year of instants in no order. Three have a microsecond
     {@code TIMESTAMP_NTZ} twin, which fuses today: its difference from the form is what the
     second word costs. Both sessions set {@code spark.sql.timestampNanosTypes.enabled}, the
     internal flag the types are behind.";
   - in the third paragraph, "Every {@code TIMESTAMP} form" reads "Every nanosecond form", and
     its last sentence, from "and, for a lead form," to the end, is dropped;
   - `VIEW` is `"varka_ts_nanos"`; the imports gain `java.util.Map` and
     `org.apache.spark.sql.internal.SQLConf`;
   - `forms()` takes `boolean declinesLongConditions`, starts with `Fusion filter =
     declinesLongConditions ? Fusion.NONE : Fusion.WHOLE;` under the comment `// A fused
     filter over the long lane: the row path's in a session that refuses it.`, and returns:

            Form.nanos("ts@", "ts@ < ts2@", Fusion.NONE),
            Form.nanos("least(ts@, ts2@)", "", Fusion.NONE),
            Form.nanos("CASE WHEN ts@ < ts2@ THEN ts@ ELSE ts2@ END", "", Fusion.NONE),
            Form.nanos("unix_nanos(ts@)", "", Fusion.NONE),
            Form.nanos("unix_micros(ts@)", "", Fusion.NONE),
            Form.nanos("timestamp_nanos(ns@)", "", Fusion.NONE),
            Form.nanosNtz("nt@", "nt@ < nt2@", Fusion.NONE),
            Form.nanosNtz("least(nt@, nt2@)", "", Fusion.NONE),
            Form.nanosNtz("CASE WHEN nt@ < nt2@ THEN nt@ ELSE nt2@ END", "", Fusion.NONE),
            Form.nanosNtz("unix_nanos(nt@)", "", Fusion.NONE),
            new Form("TIMESTAMP_NTZ twin", false, "ntz@", "ntz@ < ntz2@", List.of(G7), filter,
                BatchCheck.KERNEL),
            Form.twin("least(ntz@, ntz2@)", Fusion.WHOLE),
            Form.twin("CASE WHEN ntz@ < ntz2@ THEN ntz@ ELSE ntz2@ END", Fusion.WHOLE)

   - `dataSets()` returns `List.of(Data.YEAR)`; `columns()` returns `List.of(new Column("ts",
     Kind.LTZ9, 0), new Column("ts2", Kind.LTZ9, 1), new Column("nt", Kind.NTZ9, 0), new
     Column("nt2", Kind.NTZ9, 1), new Column("ns", Kind.NANOS, 0), new Column("ntz", Kind.NTZ,
     0), new Column("ntz2", Kind.NTZ, 1))`;
   - `timed` returns `grid(VIEW, forms(declinesLongConditions))`;
   - `settings()` returns `Map.of(SQLConf.TIMESTAMP_NANOS_TYPES_ENABLED().key(), "true")`.
3. `$B/VarkaTimestampNanosFieldsBaselineBenchmark.java`: new, with the same kind of
   differences: the name reads `NanosFields`; the first paragraph: "The baseline for the
   fields of the nanosecond timestamps before Varka reads the types (VARKA-655): Spark's row
   path over an Arrow-cached table, so that the rows that fuse a field over the microsecond
   word, and the seconds with their nanoseconds, report what they change against a committed
   file."; the second: "The hour of a {@code TIMESTAMP_LTZ(9)} is the lead form: it runs under
   UTC, under a fixed offset and under the zone with changes, the last over a week, a year in
   time order, a year in no order and ten years in no order ({@link
   VarkaTimestampBaselineBase#LEAD}). Every other form runs under the zone with changes over a
   year in no order. Three forms run over a {@code TIMESTAMP_NTZ(9)} too, and three have a
   microsecond {@code TIMESTAMP_NTZ} twin. Both sessions set {@code
   spark.sql.timestampNanosTypes.enabled}."; "Every {@code TIMESTAMP} form" reads "Every
   nanosecond form"; `VIEW` is `"varka_ts_nanos_fields"`; `settings()` as in step 2;
   `dataSets()` stays `List.of(Data.values())`; `columns()` returns `List.of(new Column("ts",
   Kind.LTZ9, 0), new Column("nt", Kind.NTZ9, 0), new Column("ntz", Kind.NTZ, 0))`; and
   `forms()` returns:

            Form.nanosLead("extract(HOUR FROM ts@)", Fusion.NONE, BatchCheck.KERNEL),
            Form.nanos("extract(MINUTE FROM ts@)", "", Fusion.NONE),
            Form.nanos("extract(SECOND FROM ts@)", "", Fusion.NONE),
            Form.nanos("second(ts@)", "", Fusion.NONE),
            Form.nanos("year(ts@)", "", Fusion.NONE),
            Form.nanos("quarter(ts@)", "", Fusion.NONE),
            Form.nanos("month(ts@)", "", Fusion.NONE),
            Form.nanos("day(ts@)", "", Fusion.NONE),
            Form.nanos("dayofyear(ts@)", "", Fusion.NONE),
            Form.nanos("dayofweek(ts@)", "", Fusion.NONE),
            Form.nanos("weekday(ts@)", "", Fusion.NONE),
            Form.nanos("weekofyear(ts@)", "", Fusion.NONE),
            Form.nanos("last_day(ts@)", "", Fusion.NONE),
            Form.nanos("next_day(ts@, 'MONDAY')", "", Fusion.NONE),
            Form.nanosNtz("extract(HOUR FROM nt@)", "", Fusion.NONE),
            Form.nanosNtz("extract(SECOND FROM nt@)", "", Fusion.NONE),
            Form.nanosNtz("year(nt@)", "", Fusion.NONE),
            Form.twin("extract(HOUR FROM ntz@)", Fusion.WHOLE),
            Form.twin("second(ntz@)", Fusion.WHOLE),
            Form.twin("year(ntz@)", Fusion.WHOLE)

   Neither class's text holds `greatest(`, `hour(`, `AS DATE` or `America/Los_Angeles`.
4. The two suites, as part B's step 3: "The nanosecond zone-free baseline's" with
   `sectionCount` 26, and "The nanosecond fields baseline's" with 50, each with the import of
   `org.apache.spark.sql.internal.SQLConf` and two more members:

            override protected def nanos: Boolean = true

            override protected def settingKeys: Set[String] =
              Set(SQLConf.TIMESTAMP_NANOS_TYPES_ENABLED.key)
5. Section 11: `Part F: the nanosecond zone-free and fields classes; results pending.` The
   commands. First commit. Hand-over with the two classes.
6. With `$IN`: 4.9's checks (`N` 26 with notes 6 and 4; `N` 50 with notes 6 and 6, or the `N`
   that section 11 records where 4.8's rule removed a form). Copy. Score predictions 12, 16,
   18 and 19 for the two classes, and 17 from the fields file. Second commit.

**The tests.** The trait's three tests in each suite. Their data test reads `ts_y` through
`unix_micros`, which Spark answers over a `TIMESTAMP_LTZ(9)`.

| what only these suites catch | where |
| :--- | :--- |
| a Varka session that fails, instead of declining, over a cached column that is a struct of two vectors | the first test of either suite: `varkaSpark` must answer every section as `spark` does. 4.8's table says what each kind of failure is and what the builder reports |
| a kind of column that Spark cannot build or cache | the first test, before any section: a stop (4.8's table, first row) |
| a form Spark rejects on the build's master | the first test; 4.8's rule removes the form and says every number that changes |
| a class that does not set the flag | the first test's assertion on `settings()`; no section can show it, since sbt's tests enable the types by default |
| a nanosecond form that a later row fused without listing it | the fuse test |
| the precision lost on the way into the table | the trait's fourth test, added by step 1: under UTC at 2000 rows, `typeof(ts_y)` is `timestamp_ltz(9)` and the 2000 values hold over 500 distinct counts of nanoseconds within the microsecond, of the 1000 a hash can give |

**Planted faults.** `Kind.LTZ9`'s SQL without `+ pmod(xxhash64(id, 200), 1000L)`: the test
"the nanosecond columns of ... hold nanoseconds" must fail on `> 500`, with 1 distinct value.
`Form.nanos("ts@", "ts@ < ts2@", Fusion.WHOLE)`: the fuse test must fail with `is listed as
WHOLE but plans as NONE`. `settings()` returning `Map.of()` in either class: its first test
must fail on the assertion on `bench.settings()`.

**The commands.** Part B's seven, with this part's two suites and classes: command 2 prints
`Passed: Total 11, Failed 0, Errors 0, Passed 11` (four tests in each of the two suites and
part A's three); the dry runs print `6`, `4` and `6`, `6`; the four greps print what they
printed before the part. `git status --short` before the first commit: the base class, the trait,
this file, and four new files.

**Done when** the two classes, their suites and eight files are on master before row 699's
first kernel. **Not here:** a census type, a registry entry, any reader of the struct.

### 5.7 Part G: the nanosecond casts and functions

**B0, the premises.**

    git branch -a --list '*varka-655g*' | wc -l                                            # 1: 0
    grep -c "static Form nanosNtz(String select, String where, Fusion fusion)" $B/VarkaTimestampBaselineBase.java   # 2: 1
    grep -c "protected def nanos: Boolean = false" $S/VarkaTimestampBaselineQueries.scala    # 3: 1
    ls $B/VarkaTimestampNanosFieldsBaselineBenchmark.java | wc -l                           # 4: 1
    ls $B/VarkaTimestampNanosCastsBaselineBenchmark.java $B/VarkaTimestampNanosFunctionsBaselineBenchmark.java 2>/dev/null | wc -l   # 5: 0
    python3 $T/twins.py                                                                    # 6: 28 twins, 0 missing

**The steps.**

1. `$B/VarkaTimestampNanosCastsBaselineBenchmark.java`: new, part F's fields class's file
   with: the name `NanosCasts`; the first paragraph: "The baseline for the casts of the
   nanosecond timestamps before Varka reads the types (VARKA-655): Spark's row path over an
   Arrow-cached table, so that the rows that fuse a cast between the nanosecond types, the
   microsecond types and a date report what they change against a committed file."; in the
   second paragraph "The hour of a {@code TIMESTAMP_LTZ(9)}" reads "The cast of a {@code
   TIMESTAMP_LTZ(9)} to a date", "Three forms run" reads "Five forms run" and "three have"
   reads "one has"; `VIEW` `"varka_ts_nanos_casts"`; `columns()` returns `List.of(new
   Column("ts", Kind.LTZ9, 0), new Column("nt", Kind.NTZ9, 0), new Column("tsm", Kind.TS, 0),
   new Column("ntz", Kind.NTZ, 0), new Column("d", Kind.DATE, 0))`; and `forms()` returns:

            Form.nanosLead("ts@ :: date", Fusion.NONE, BatchCheck.KERNEL),
            Form.nanos("CAST(ts@ AS TIMESTAMP_LTZ)", "", Fusion.NONE),
            Form.nanos("CAST(ts@ AS TIMESTAMP_LTZ(7))", "", Fusion.NONE),
            Form.nanos("CAST(tsm@ AS TIMESTAMP_LTZ(9))", "", Fusion.NONE),
            Form.nanos("CAST(ts@ AS TIMESTAMP_NTZ(9))", "", Fusion.NONE),
            Form.nanos("CAST(nt@ AS TIMESTAMP_LTZ(9))", "", Fusion.NONE),
            Form.nanos("CAST(d@ AS TIMESTAMP_LTZ(9))", "", Fusion.NONE),
            Form.nanosNtz("nt@ :: date", "", Fusion.NONE),
            Form.nanosNtz("CAST(nt@ AS TIMESTAMP_NTZ)", "", Fusion.NONE),
            Form.nanosNtz("CAST(nt@ AS TIMESTAMP_NTZ(7))", "", Fusion.NONE),
            Form.nanosNtz("CAST(ntz@ AS TIMESTAMP_NTZ(9))", "", Fusion.NONE),
            Form.nanosNtz("CAST(d@ AS TIMESTAMP_NTZ(9))", "", Fusion.NONE),
            Form.twin("ntz@ :: date", Fusion.WHOLE)

2. `$B/VarkaTimestampNanosFunctionsBaselineBenchmark.java`: new, the same way: the name
   `NanosFunctions`; the first paragraph: "The baseline for the arithmetic and the functions
   over the nanosecond timestamps before Varka reads the types (VARKA-655): Spark's row path
   over an Arrow-cached table, so that the rows that fuse a sum that carries into the
   microsecond word, a truncation or a zone function report what they change against a
   committed file."; "The hour of a {@code TIMESTAMP_LTZ(9)}" reads "The sum of a {@code
   TIMESTAMP_LTZ(9)} and an interval of one day", "Three forms run" reads "Five forms run"
   and "three have" reads "two have"; `VIEW` `"varka_ts_nanos_fn"`; `columns()` returns
   `List.of(new Column("ts", Kind.LTZ9, 0), new Column("nt", Kind.NTZ9, 0), new Column("ntz",
   Kind.NTZ, 0))`; `free()` is part C's arithmetic class's `free()`; and `forms()` returns:

            Form.nanosLead("ts@ + INTERVAL '1' DAY", Fusion.NONE, BatchCheck.KERNEL),
            Form.nanos("ts@ + INTERVAL '1' MONTH", "", Fusion.NONE),
            Form.nanos("ts@ + dt#", "", Fusion.NONE),
            Form.nanos("timestampadd(NANOSECOND, q#, ts@)", "", Fusion.NONE),
            Form.nanos("timestampadd(DAY, q#, ts@)", "", Fusion.NONE),
            Form.nanos("date_trunc('DAY', ts@)", "", Fusion.NONE),
            Form.nanos("date_trunc('MICROSECOND', ts@)", "", Fusion.NONE),
            Form.nanos("from_utc_timestamp(ts@, 'Europe/Amsterdam')", "", Fusion.NONE),
            Form.nanos("to_utc_timestamp(ts@, 'Europe/Amsterdam')", "", Fusion.NONE),
            Form.nanosNtz("nt@ + INTERVAL '1' DAY", "", Fusion.NONE),
            Form.nanosNtz("nt@ + INTERVAL '1' MONTH", "", Fusion.NONE),
            Form.nanosNtz("timestampadd(NANOSECOND, q#, nt@)", "", Fusion.NONE),
            Form.nanosNtz("date_trunc('DAY', nt@)", "", Fusion.NONE),
            Form.nanosNtz("convert_timezone('Europe/Amsterdam', nt@)", "", Fusion.NONE),
            Form.twin("ntz@ + INTERVAL '1' DAY", Fusion.WHOLE),
            Form.twin("ntz@ + INTERVAL '1' MONTH", Fusion.WHOLE)

3. The two suites: "The nanosecond casts baseline's" with `sectionCount` 36, and "The
   nanosecond functions baseline's" with 42, each with the import and the two members of part
   F's step 4, so each has the trait's fourth test.
4. Section 11, the commands, the two commits and the hand-over as in part F. 4.9's checks:
   `N` 36 with notes 2 and 2; `N` 42 with notes 4 and 4, or section 11's `N` after 4.8's
   rule. Score predictions 12, 16, 18 and 19 for the two classes; the part that merges last
   also sums prediction 18 over the nine suites from section 11. If this is the row's last
   part to merge, `$M9/PLAN.md`'s cell reads
   `**Done**` with the seven pull request numbers.

**The tests and the planted faults** are part F's, over these classes. One fault more: `new
Free("q", ...)` with `+ 2000000000` in place of `- 1000` must fail the functions suite's
first test on `timestampadd(DAY, q, ts)` raising.

**The commands.** Part F's: command 2 over this part's two suites and part A's prints
`Passed: Total 11, Failed 0, Errors 0, Passed 11`; the dry runs print `2`, `2` and `4`, `4`;
`git status --short` before the first commit is this file and four new files.

**Done when** the two classes, their suites and eight files are on master before the first
kernel of rows 674 and 675. **Not here:** `ts - ts`, `months_between`, `timestampdiff`, which
Spark rejects over the types (`m9/PLAN.md` 1.3).

## 6. Tests, proofs, fuzzing and coverage

The row adds test-scope code only: benchmark classes, their suites, and the surface module's
driver and test. So:

| suite (language, module) | part | what it holds | the failure only it catches |
| :--- | :--- | :--- | :--- |
| `VarkaTimestampBaselineQueriesSuite` (ScalaTest, sql/core) | A | the 50 zone-free sections under three zones on both sessions; each section's fuse class with the two-lane refusal off and on; the two data sets | a zone-free form that fuses unlisted, which would make row 656's "before" a kernel's time |
| `VarkaTimestampFieldsBaselineQueriesSuite`, `VarkaTimestampCastsBaselineQueriesSuite` | B | 88 and 52 sections; the four data sets, each with its count of changes from `java.time` | a week with a change in it, or ten years that are one: a cell that would not test what rows 657, 685 and 686 read it for |
| `VarkaTimestampArithmeticBaselineQueriesSuite`, `VarkaTimestampZoneFunctionsBaselineQueriesSuite` | C | 40 and 28 sections; the free columns' ranges, through sums that would raise outside them | a sum that raises on the runner, 20 minutes into a dispatch |
| `VarkaTimestampNanosBaselineQueriesSuite`, `...NanosFieldsBaselineQueriesSuite` | F | 26 and 50 sections under the flag; the nanoseconds of the columns | a Varka session that fails over the cache's struct of two vectors; a column whose nanoseconds are all zero |
| `VarkaTimestampNanosCastsBaselineQueriesSuite`, `...NanosFunctionsBaselineQueriesSuite` | G | 36 and 42 sections | a form Spark stopped accepting after a sync |
| `TimestampsTest` (JUnit 5, `sql/varka/bench`) | E | the 16 entries on the module's stock release; the table's range, nulls, zone and two offsets; that no entry is held to a kernel | an entry that fails on the release, found by the runner otherwise |
| the three `VarkaTimestampNtz*BaselineQueriesSuite` (existing) | A, D | unchanged, and green before part D dispatches | a `TIMESTAMP_NTZ` section whose class is stale before 30 minutes of runner are spent on it |

Each suite of this row runs at both vector widths (command 3 of each sheet), under each option
configuration through the matrix, where its fuse test is cancelled by `PinsDefaults` and its
answer tests run, and with the memory sanitizer on.

**The edges, and where each has a case.** Nulls: every form, in the sections with one row in
ten null. A zone with changes, a fixed offset and UTC: every lead form. Rows across both
changes of a year, in time order and in no order: the data sets, held by the data test. A
local time in a gap or an overlap: the casts and `make_timestamp`, answered by both arms
alike. The ends of a long, the years before 1582 and after 9999, a batch with no live row:
none, by 4.13, since a baseline must not raise or decline; the conformance and zone suites of
rows 656 and 657 hold them. Two 64-bit lanes: command 3 and the 128-bit dispatch.

**Proofs.** None: the row adds no lowering. `sql/varka/proofs` is not touched.

**Registry suites of an IR record, coverage rows, census cells.** None: the row adds no IR
record, no emit option, no family, no coverage row and no census cell. `git status --short`
after each part's suites lists no generated file.

**Fuzzing.** None: no fuzzer draws a benchmark.

**JaCoCo and mutation.** They measure production classes, and the row has none. The planted
faults of each sheet are what shows that its tests bite.

**Checks that run only in CI.** The timed dispatches; the option matrix; the Maven build,
which compiles `sql/varka/bench` only through the surface workflow's `stop-after=build`.

## 7. Measurement

The row's measurement is the baseline itself. Every number is from a GitHub runner with an AMD
EPYC 7763, at its 256 bits and at 128 bits (`-XX:MaxVectorSize=16`); the laptop measures
nothing here. A class has no band file yet, so a move is read against the flat rule: a ratio
under 1.3 is run again and compared by minimums. There is no A/B arm.

What the predictions rest on: the committed `TIMESTAMP_NTZ` baselines, where the row path
costs 64.1 ns a row for `hour(ntz)`, 91.8 for `year(ntz)`, 70.5 for the cast to a date, 53.8
for the comparison and 49.1 for the choice
(`VarkaTimestampNtzFieldsBaselineBenchmark-jdk25-runner-epyc7763-results.txt` and
`VarkaTimestampNtzBaselineBenchmark-jdk25-runner-epyc7763-results.txt`); the zone probe, where
`java.time`'s lookup cost about 25 ns a row (`VARKA-657.md` 6.1); and `m9/PLAN.md` 1.5, where
row 340 registered five times the row path for `year(ntz)`.

**Predictions, registered on 27 November 2026, before any run.** `S` is the count of a file's
sections whose title starts `TIMESTAMP ` (40, 60, 50, 26 and 28 for the five microsecond
classes) or, for a nanosecond class, `TIMESTAMP_LTZ(9)` or `TIMESTAMP_NTZ(9)` (20, 44, 34 and
38). "All but a twentieth" allows `S div 20` sections outside, and one where that is 0. A
ratio is of the two cases' `Best Time(ms)`; a time a row is the column `Per Row(ns)`.

| # | prediction | scored in |
| ---: | :--- | :--- |
| 1 | Nothing over a `TIMESTAMP` fuses: every `TIMESTAMP` section of the five classes plans `NONE` with the two-lane refusal off and on. Yes or no for each class | the fuse test of command 2 and the dry runs; parts A, B and C, their last step |
| 2 | The two arms tie: in all but a twentieth of a file's `S` sections, `varka on` is within 10 percent of `spark row path (varka off)`; no section is outside 30 percent | each results file; parts A, B and C, their last step |
| 3 | The width does not move a baseline: in all but a twentieth of a class's `S` sections, the row path's time a row in the 128-bit file is within 10 percent of the wide file's | the two results files of a class; parts A, B and C, their last step |
| 4 | The zone does not move a zone-free form: for the comparison, `greatest` and the choice over a year in no order, the row path's times a row under the three zones are within 10 percent of each other. Yes or no for each form and density | the wide file of the zone-free class; part A, step 7 |
| 5 | Each twin that lists `WHOLE` runs, at 256 bits, at least twice the row path's rate, but for the filter, which runs between 1.3 and 2.5 times it; at 128 bits no projection twin is slower than the row path | the twin sections of each file; parts A, B and C, their last step (the zone functions class has no twin) |
| 6 | The zone's price on the row path: at G7's cell with no nulls, `hour(ts)` costs the row path between 15 and 60 ns a row more than its twin `hour(ntz)` costs it; under UTC it costs within 10 ns a row of the twin | the wide file of the fields class; part B, step 6 |
| 7 | Neither the order nor the span moves the row path: for each lead form with no nulls, its row-path times a row under `America/Los_Angeles` over the four data sets are within 20 percent of each other | the wide files of the fields and casts classes, part B, step 6; of the arithmetic and zone functions classes, part C, step 6 |
| 8 | `CAST(ntz AS TIMESTAMP)` is the dearest lead form of the fields and the casts on the row path at G7's cell with no nulls: it costs more a row than `hour(ts)`, `minute(ts)`, `second(ts)`, `CAST(ts AS DATE)` and `CAST(d AS TIMESTAMP)`. Yes or no | the two wide files; part B, step 6 |
| 9 | `year(ntz)` with no nulls, run again with its kernel: at least five times the row path's rate at 256 bits | the wide file of the `TIMESTAMP_NTZ` fields class; part D, step 6 |
| 10 | Every `WHOLE` section of the three `TIMESTAMP_NTZ` classes at 256 bits runs at least twice the row path's rate, but for the comparison, between 1.3 and 2.5 times | the three wide files; part D, step 6 |
| 11 | At 128 bits no `WHOLE` section of the three classes is slower than the row path, and the comparison, refused there, ties within 10 percent. Yes or no | the three 128-bit files; part D, step 6 |
| 12 | Sizes: each part is within its estimate of section 5 by half, and under 900 counted lines | `python3 dev/varka_push_check.py` after the second commit; each part, its last step |
| 13 | The surface's arms agree: one checksum value for each width. Yes or no | part E, step 11's listing |
| 14 | The release's serializer reads as the fork's format: for each of the 16 entries at 256 bits, the release's format share over the fork's is between 1 / 1.3 and 1.3. Yes or no; not scored where the stock Arrow arm died or disagrees | part E, step 14, from `$T/quotients.txt` |
| 15 | The fork with Varka off is the release: for each of the 16 entries at 256 bits, `varka-off-jdk25`'s rate over `spark-4.3.0-jdk25`'s is between 1 / 1.15 and 1.15 | part E, step 14, from the last column of `$T/quotients.txt` |
| 16 | Nothing over a nanosecond type fuses, and its two arms tie as prediction 2 says, over the nanosecond `S` | parts F and G, their last step |
| 17 | The second word's price on the row path: with no nulls under UTC, `extract(HOUR FROM ts)` over a `TIMESTAMP_LTZ(9)` costs the row path between 1.2 and 4 times what the twin `extract(HOUR FROM ntz)` costs the row path, each read from its `spark row path (varka off)` case | the wide file of the nanosecond fields class; part F, step 6 |
| 18 | Test time on the laptop: the tests of each queries suite of this row take under 60 s together in command 2's log, and the nine suites under 5 minutes together | each part's last step, from `$T/suites.log` (5.0); the part that merges last sums the nine from section 11 |
| 19 | Runner time: a class's timed run takes under 15 s a section, so under `N / 4` minutes: 13, 22, 13, 10 and 7 minutes for the five microsecond classes | each part's last step, from `$IN/minutes.txt` (4.9) |

**How prediction 18 was priced.** A suite's work is its sections, each run three times: once
on both sessions in the answer test, and once with each setting of the refusal in the fuse
test. The nearest suite is `VarkaTimestampNtzFieldsBaselineQueriesSuite`: 14 sections, three
tests, 4 to 5 seconds in `sql/varka/matrix/suite-seconds.tsv` and
`sql/varka/ci/tier-split/suite-times.tsv`, about a tenth of a second for a section's run. At
that price the 412 sections of the nine suites are about 150 seconds, and the fields suite's
88 are about 32. Each suite also caches its table nine times at 100 or 2000 rows. The
calibration table's rows for a new sql/core suite are VARKA-243, at twice its estimate, and
VARKA-379, at half, so the estimate is doubled for the bound of a suite and kept for the sum's.
What it adds to a Build: under 5 minutes to the sql/core Varka shard that runs these suites.
The prediction is read on the laptop, where the builder has the log; a Build's own time for
the suites is in its test report, and the lead adds it to section 11 where it differs by more
than twice.

**How prediction 19 was priced.** A case warms up for 2 seconds and then runs at least five
iterations and at least 2 seconds (`VarkaBenchmark.Builder.timed`). An iteration over 2,000,000
rows at the row path's 50 to 250 ns a row is 0.1 to 0.5 seconds, so a case is 4 to 5 seconds
and a section's two cases about 10. The 258 microsecond sections are then about 45 minutes of
jobs at a width, and the 154 nanosecond ones about 30.

## 8. Who builds it

`m9/PLAN.md` 1.4 names the rows built at full strength: 656, 657 and 682 to 692. Row 655 is
not among them, and it is no fan-out row: the generated task plan of 2.7 (row 658) does not
exist in wave 0. **The owner decided on 27 November 2026:** cheaper models may build parts B
and C from their sheets once part A has merged, and parts A and E stay at full strength.

| part | builder | why |
| :--- | :--- | :--- |
| A | full strength (the owner, 27 November 2026) | the base and the trait are the logic of eight later classes, and the class is the seam with row 656's greps |
| B, C | a cheaper model, from the part's sheet, once A has merged (the owner, 27 November 2026) | each is two lists of forms from a sheet that gives every line, with a suite that checks each form |
| D | any builder; the lead for the dispatches | results and three dated lines |
| E | full strength (the owner, 27 November 2026) | a workflow, a shell script and a driver that run only on a runner; a mistake costs a dispatch of hours |
| F, G | full strength, until the owner says otherwise | as B and C, but Spark's surface moves, and 4.8's table and rule need a careful report. The owner's word of 27 November names B and C only |

**What this means for goal G5.** G5 counts "fan-out pull requests" built by the cheaper
models, and 1.2 item 8 reports their share from row 658's ledger. Parts B and C are not
fan-out pull requests, so they are not in that share's numerator or denominator. They are
the first pull requests a cheaper model builds from a hand-written sheet of this kind, before
the pilot (row 659), so what they teach is the ledger's and the template's:

- If row 658's ledger exists when a part merges, the lead adds the part's line to it, marked
  "not fan-out: VARKA-655 part B" (or C), with the builder's model, the fix rounds and the
  review's finding counts, as a fan-out line has them.
- If the ledger does not exist yet, the lead writes the same facts in section 11 under the
  part's line, and row 658 copies them into the ledger when it creates it.
- The builder's commit ends with `Generated-by: <the model's own tool> (<the model>)`, as
  `m9/PLAN.md` 2.7 item 6 has it.
- The loop is 2.7's: one review (`/code-review high`) and one fix round by the model; then the
  part merges, or the lead takes it over and the ledger line says so.

## 9. Risks

1. **A kernel merges before its baseline.** Then the "before" is lost. What holds it: the
   order of `m9/PLAN.md` section 4 (this row in wave 0); row 656's B0 line 12, which stops
   that row without part A's class; and 5.0's rule, which stops a part of this row whose form
   already fuses.
2. **A customer's grep finds another file, or two.** 4.12 lists the four greps, each sheet
   runs them, and the nanosecond classes avoid the four texts.
3. **The code of this plan was not compiled.** 3 says so. What holds it: command 1 of each
   sheet, and the rule that a compile fix changes no name, no SQL text and no title.
4. **A form does not analyze or raises on the build's master.** The first test of its suite
   fails before any dispatch. For a nanosecond form, 4.8's rule removes it; for any other, the
   builder stops.
5. **A twin stops fusing** through a change of milestone 8's tail. The fuse test fails in the
   Build of that change, not in this row; 5.0 says what a part of this row does if it meets
   one.
6. **The runner pool.** A run on another CPU is dispatched again (`expected-cpu`). A file is
   committed only with `AMD EPYC 7763` in its provenance (4.9).
7. **A run is long or fails late.** The checks run before any timing, in the suite and in the
   dry run; the largest class is 88 sections, under 22 minutes by prediction 19. If a job
   passes its limit, the lead splits nothing: it reports, and the class's leads are cut to
   four cells by a follow-up the owner approves.
8. **The Varka session fails over a nanosecond column.** Part F's first test shows it, and it
   is a stop with a finding, not a form to remove.
9. **The second run overwrites the before-kernel numbers.** Git's history holds them, part D
   records each old file's commit in section 11, and the quote check reads a number from a
   file's history.
10. **The stock arm over the Arrow cache does not start or does not agree.** 4.10's rule
    turns each case into the record the row's text allows.
11. **The time zone data of a later JDK** moves a change of `America/Los_Angeles` inside 2020
    to 2030. The data test then fails with the count it found, and the fix is the table's
    number with a dated note, in a pull request of its own.
12. **An AVX2 runner plans or runs a twin otherwise than the AVX-512 laptop.** The dry runs are
    made under `-XX:UseAVX=2` at both widths (5.0), and 4.9 says what the lead and the
    builder do if a dispatch still dies on a twin.
13. **An arm of the surface dies.** The stock Arrow arm, the only one that has never run, is
    last, and 4.10 gives the three cases of a red run.
14. **Row 653 or row 646 splits `m9/PLAN.md`'s table** before a part runs. B0 line 11 of part
    A then prints 0: stop and report "the task table moved; the status edit of VARKA-655 is
    cut for one table". The lead re-cuts that step.

## 10. The house rules that bind this row

From `sql/varka/AGENTS.md` and `m9/PLAN.md`:

- "Any performance claim in code comments, plans or docs traces to a committed benchmark
  result file." A class comment here states no number.
- "at least five iterations over two-second windows, generated on an otherwise idle machine,
  and any claim resting on a ratio under ~1.3x is re-run and compared by minimums before it is
  written down."
- "a baseline on the unchanged engine before a kernel, in its own pull request; every number
  from the AMD EPYC 7763 at 256 and 128 bits" (`m9/PLAN.md` 1.5).
- "A new benchmark is Java on `VarkaBenchmarkBase`"; "the sql/core suites stay ScalaTest".
- "The Varka code carries no `TODO` or `FIXME` comments"; "A comment describes the code as it
  is."
- "A pull request stays under 1000 changed lines of hand-written files"; "Generated and
  results files [...] do not count."
- "Review your own diff before opening the pull request" (`/code-review high` on the diff;
  the lead runs it before each push).
- "Never kill by name pattern"; "Never `git stash`"; "Write only inside your own worktree and
  scratch area."
- "A red master comes first."

What not to do:

- Do not run a benchmark for its numbers on the laptop, and do not commit a file that 4.9's
  checks do not pass.
- Do not push, open a pull request or dispatch a workflow.
- Do not list a `TIMESTAMP` or nanosecond form as fused, and do not weaken a suite's check to
  make a section pass.
- Do not edit `VarkaSqlBenchmarkBase`, a `TIMESTAMP_NTZ` class, or any production code.
- Do not write `greatest(`, `hour(`, `AS DATE` or `America/Los_Angeles` in a class file that
  4.12 does not give that text to, comments included.
- Do not open OpenJDK's `java.time` sources: what the row rests on is `ZoneRules.nextTransition`
  through the public API, in the data test.
- Do not add a form, a zone or a data set that this plan does not name.

## 11. Outcome

To be written when the row closes. Each part adds its lines here as it merges.

## 12. Seams with the plans written beside this one

The plans of rows 653, 660 and 690 were written beside this one and could not be read.

| row | what this plan assumes | where this plan checks it, or what it gives |
| :--- | :--- | :--- |
| 653, the shared files | nothing of its layout: this row appends to no coverage list, census file, cast table or skip list, and regenerates nothing. It edits one cell of `m9/PLAN.md`'s table | part A, B0 line 11; risk 12 |
| 660, the precisions | nothing: the nanosecond classes use precision 9 and the SQL type names, no census name. If row 660 makes one census type a precision, row 699 adds forms at 7 and 8 by 4.6's rule | 4.8 |
| 690, a local time to an instant | that it measures on this row's lead forms `CAST(ntz AS TIMESTAMP)` and `CAST(d AS TIMESTAMP)` of `VarkaTimestampCastsBaselineBenchmark` and `ts + INTERVAL '1' DAY` of `VarkaTimestampArithmeticBaselineBenchmark`, and turns each by its line of `forms()`; its ten-year cells stay `ALL_FALL_BACK` until row 693 | 4.4, 4.6 |

The plans this row meets that are final, and how it follows each:

| plan | what it asks of this row | how this plan gives it |
| :--- | :--- | :--- |
| `VARKA-656.md` 5.2, B0 lines 12 to 14b; step 9; part D; section 12 | one class with the zone-free sections, a `greatest` among them, the `Section` record, `sections(boolean declinesLongConditions)`, both fuse classes; a queries suite named `...BaselineQueriesSuite`; four files; twins | part A; 4.5; `sections` returns the sections by zone, which that plan's greps do not depend on. Its step 9 spoke of sections and of "every other section"; here the classes are five variables and the twins already list `WHOLE`: section 13, edit 6. Its part D copied the artifacts' files by name; the lead renames them (4.9): section 13, edit 7 |
| the same, section 7, prediction 11 | twins in the `TIMESTAMP_NTZ` baseline's file | the twins are sections of this row's class, over the same instants; section 13 gives the edit |
| `VARKA-657-PARTS.md` 5.2, 5.4 and 5.5 | a fields class found by `hour(`, a casts class found by `AS DATE`, sections listed with a fuse class and a batch check, the cells of its step 6; results committed from the runs' artifacts | part B; 4.6; 4.12. The grid is smaller than its section 7 says: section 13, edits 3 and 4. Its part E and `VARKA-687.md`'s part b commit "the results and provenance files of both widths" and name no file: 4.9's rename by the lead applies to them as written |
| `VARKA-657.md` 6 | the family's seven sections under three zones, four data sets and two null densities, each with a `TIMESTAMP_NTZ` twin as its control | the seven are lead forms: `hour`, `minute`, `second`, `CAST(ts AS DATE)`, `CAST(ntz AS TIMESTAMP)`, `CAST(d AS TIMESTAMP)`, `ts + INTERVAL '1' DAY`. The fixed zones run over a year in no order alone (4.3), and the two casts to a `TIMESTAMP` have no fused twin (4.7): section 13, edit 5 |
| `VARKA-685.md`, `VARKA-688.md` | queries suites matched by `*BaselineQueriesSuite`; ten years in no order still `ALL_FALL_BACK` | the suites' names; `Form`'s third argument |
| `VARKA-687.md`, part b | at least one class with the text `America/Los_Angeles`; each such class dispatched again | the four zoned microsecond classes |
| `m9/PLAN.md` rows 691 and 676 | the surface's figures with the kernels on: G7's "than the newest stock Spark release on a timestamp surface" | part E builds the surface and its first, unfused run, which stays the baseline under the labels `-zen3-unfused` and `-zen3-128bit-unfused`. Row 691 turns the entries its master fuses and makes the first run with the kernels on; row 676 does it once more for the post (4.10; the owner, 27 November 2026). Section 13, edits 9 and 10, give both rows that work |
| `m9/PLAN.md` rows 667 to 669 | "a benchmark section" for each entry | a form in G7's cell for the entries of 4.4; a row adds any other by 4.6's rule, before its kernel |

## 13. Edits to other plans, made by this plan's own pull request

Each old text was checked on master `84d6b2aff10` (`git show origin/master:<path>`): it occurs
there exactly once, as consecutive whole lines but for edits 1, 9 and 10, each part of one line. The
blocks are indented eight spaces by this list: take eight away to have the file's own text,
whose lines start at column 0, or at column 3 or 5 inside a list of that file. No new line is
over 100 columns but those of edits 1, 9 and 10, which are parts of table rows.

1. `sql/varka/plans/m9/PLAN.md`, the line of row 655; the text is inside one long line of the
   table. Old text:

        and the timestamp surface. **Filed** (25 November 2026; the admission check: the `TIMESTAMP_NTZ` baseline classes exist

   New text:

        and the timestamp surface. **Planned** (`VARKA-655.md`, 27 November 2026: seven parts, the zone-free class that row 656 needs first, then the fields and the casts, the arithmetic and the zone functions, the second run of the `TIMESTAMP_NTZ` files, the surface with two arms over the Arrow cache, and two parts of nanosecond classes in wave 4; nine classes on one base, a lead form in six cells and every other form in G7's cell, each with a `TIMESTAMP_NTZ` twin where one fuses; filed 25 November 2026; the admission check: the `TIMESTAMP_NTZ` baseline classes exist

2. the same file, section 4, the paragraph of wave 0: the surface's code and first run are wave 0
   too (part E). Old text:

        655's `TIMESTAMP_LTZ` classes and the second run of the `TIMESTAMP_NTZ` ones; the carried rows'
        built parts; rows 590 and 681, which move no census cell; row 663 once #1708 and #1675 have merged.

   New text:

        655's `TIMESTAMP_LTZ` classes, the second run of the `TIMESTAMP_NTZ` ones and its timestamp
        surface (`VARKA-655.md`, parts A to E); the carried rows' built parts; rows 590 and 681, which
        move no census cell; row 663 once #1708 and #1675 have merged.

3. `sql/varka/plans/m9/VARKA-657-PARTS.md`, 5.2, step 6. Old text:

           column is listed as fused, the way the class's `Section` says it (`Fusion.WHOLE` and a
           `BatchCheck` of `VarkaSqlBenchmarkBase`), by this table:

   New text:

           column is listed as fused in its line of the class's `forms()` (`VARKA-655.md` 4.6:
           `Fusion.WHOLE`, and a `BatchCheck` of `VarkaSqlBenchmarkBase` for the ten-year cell,
           which is the table's last row; every other cell is `KERNEL`), by this table:

4. the same file, section 7. Old text:

        under UTC, `+01:00` and `America/Los_Angeles`; over a week, a year in time order, a year in no
        order and ten years in no order; with no nulls and with one row in ten null. The control of

   New text:

        under UTC and `+01:00` over a year in no order, and under `America/Los_Angeles` over a week,
        a year in time order, a year in no order and ten years in no order (`VARKA-655.md` 4.3);
        with no nulls and with one row in ten null. The control of

5. `sql/varka/plans/m9/VARKA-657.md`, section 6: the grid, and which sections have a twin. Old text:

        `CAST(d AS TIMESTAMP)` and `ts + INTERVAL '1' DAY`; each under UTC, `+01:00` and
        `America/Los_Angeles`; over a week, a year in time order, a year in no order and ten years in no
        order; with no nulls and with one row in ten null. The control of each section is its
        `TIMESTAMP_NTZ` twin: the difference is the zone's price. Numbers are the EPYC 7763's at 256 and

   New text:

        `CAST(d AS TIMESTAMP)` and `ts + INTERVAL '1' DAY`; each under UTC and `+01:00` over a year in
        no order, and under `America/Los_Angeles` over a week, a year in time order, a year in no order
        and ten years in no order (`VARKA-655.md` 4.3); with no nulls and with one row in ten null. The
        control of a section is its `TIMESTAMP_NTZ` twin where that twin fuses (`VARKA-655.md` 4.7; the
        two casts to a `TIMESTAMP` have none): the difference is the zone's price. Numbers are the EPYC
        7763's at 256 and

6. `sql/varka/plans/m9/VARKA-656.md`, 5.2, step 9, its last bullet: the class's variables, and the
   twins that already list `WHOLE`. Old text:

           - `<exect>/benchmark/<Base>.java`: a section whose query has a `WHERE` over the two
             timestamp columns and none of 4.3's functions takes the class `declinesLongConditions ?
             Fusion.NONE : Fusion.WHOLE`, as `VarkaTimestampNtzBaselineBenchmark`'s comparison does;
             a section whose select list is `greatest`, `least`, `CASE`, `if` or `coalesce` over them
             takes `Fusion.WHOLE`. Every other section stays `Fusion.NONE`. The class comment says
             which sections row 656 fused.

   New text:

           - `<exect>/benchmark/<Base>.java`, in `sections` (`VARKA-655.md` 4.5 and 4.6), three
             lines: `Fusion comparison = Fusion.NONE;` becomes `Fusion comparison = filter;`, where
             `filter` is that method's `declinesLongConditions ? Fusion.NONE : Fusion.WHOLE`, as
             `VarkaTimestampNtzBaselineBenchmark`'s comparison has it; `Fusion pick = Fusion.NONE;`
             and `Fusion choice = Fusion.NONE;` become `Fusion pick = Fusion.WHOLE;` and `Fusion
             choice = Fusion.WHOLE;`. `epoch` and `hashes` stay `Fusion.NONE`, and the sections
             titled `TIMESTAMP_NTZ twin` keep the classes they have. The class comment gains the
             sentence "The comparison, `greatest` and the choice fuse (VARKA-656)."

7. the same file, 5.4, step 2: the lead renames the artifacts' files, so that step 3's "no file of
   that name" stop fires only for a file that row 655 did not commit. Old text:

           A run on another CPU is dispatched again. The lead downloads each run's artifact and
           gives the builder one directory, `$R`, that holds the four files as the artifacts name
           them: the results file and the provenance file of each width.

   New text:

           A run on another CPU is dispatched again. The lead downloads each run's artifact, whose
           two files are named `<Base>-jdk25-results.txt` and `<Base>-jdk25-provenance.txt` at both
           widths, and renames `<Base>-jdk25-` to `<Base>-jdk25-runner-epyc7763-` for the first run
           and to `<Base>-jdk25-runner-epyc7763-128bit-` for the second (`VARKA-655.md` 4.9). It
           gives the builder one directory, `$R`, that holds the four files under those names, which
           are the names row 655 committed.

8. the same file, section 7, prediction 11. Old text:

        11. A `TIMESTAMP` section's `varka on` time is within 1.1 of its `TIMESTAMP_NTZ` twin's in the
            `TIMESTAMP_NTZ` baseline, where the two classes hold the same query over the same rows:
            it is one kernel class over another Arrow vector class. Not scored where `<Base>` has no
            such twin; section 11 then says so.

   New text:

        11. A `TIMESTAMP` section's `varka on` time is within 1.1 of its `TIMESTAMP_NTZ` twin's, the
            section of `<Base>` titled `TIMESTAMP_NTZ twin` with the same form over the same
            instants (`VARKA-655.md` 4.7): it is one kernel class over another Arrow vector
            class. Scored for the comparison, `greatest` and the choice over a year in no order.

9. `sql/varka/plans/m9/PLAN.md`, section 3, the line of row 691 (the owner, 27 November 2026:
   row 691 makes the surface's first run with the kernels on); the text is inside one long
   line of the table. Old text:

        the default of each option at each width, G7's first reading, and the family's outcome. **Done when** each form of `VARKA-657.md` 3.5 is measured in a kernel, the default at each width is the fastest, G7's bar holds for `hour` and for the cast to `DATE`, or the miss is recorded with its lever, and the outcome in `VARKA-657.md` is written

   New text:

        the default of each option at each width, G7's first reading, the first run of row 655's timestamp surface with the zone kernels on against the stock release (`VARKA-655.md` 4.10: the entries that fuse turned in `Timestamps`, the six arms at 256 and 128 bits), and the family's outcome. **Done when** each form of `VARKA-657.md` 3.5 is measured in a kernel, the default at each width is the fastest, G7's bar holds for `hour` and for the cast to `DATE`, or the miss is recorded with its lever, the surface's twelve files with the kernels on are committed beside row 655's unfused ones, and the outcome in `VARKA-657.md` is written

10. The same file, section 3, the line of row 676, which keeps the post's run; inside one
   long line of the table. Old text:

        and what the cheaper models built, with its figures from committed results. **Done when** it is published

   New text:

        and what the cheaper models built, with its figures from committed results; it runs row 655's timestamp surface against the stock release once more, on the milestone's last kernels, after row 691's first run with the zone kernels (`VARKA-655.md` 4.10). **Done when** the surface's last run is committed and the post is published

No other plan is edited. `m10/SCOPE.md`'s note under item 132 is part E's step 8, and the
three dated lines in milestone 8's plans are part D's step 5; neither is this pull request's.

## 14. The owner's decisions

**Decided by the owner on 27 November 2026.** The first five are as this plan recommended;
the sixth overrules its recommendation. The plan is written on all six.

- **The second run in place.** Part D commits the twelve `TIMESTAMP_NTZ` files over the old
  ones. The old numbers stay in git's history, and part D records each file's baseline
  commit (4.11).
- **Cheaper models for parts B and C.** They may build B and C from their sheets once part A
  has merged; parts A and E stay at full strength (section 8).
- **No nanosecond surface against the stock release** in this milestone (4.10).
- **The sections left to the fan-out.** `IN`, the null tests, `least`, the eleven other
  numeric and boolean casts and `TIME` to `TIMESTAMP` are left to the fan-out rows, each
  adding its baseline section in a pull request before its kernel (4.14, 4.6).

- **The grid** (27 November 2026, later): as 4.3 has it, a lead form in six cells and every
  other form in G7's cell.
- **Who runs the surface with the kernels on** (27 November 2026, later; this overrules the
  plan's first recommendation, which was row 676 alone): row 691, the zone kernels'
  measurement, makes the first run against the stock release, so that the reading comes when
  the zone kernels exist; row 676, the post, runs it again at the end. This row's first run
  stays the unfused baseline (4.10; section 13, edits 9 and 10).

No question of this plan is open.
