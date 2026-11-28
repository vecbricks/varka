# VARKA-650: the pi query as a demonstration, and `VarkaCountBenchmark` with its baseline

*Planned on 28 November 2026. This plan stands alone. Not started: no branch holds any of it
(section 3, F1). The code facts were checked on master `6dafb8bd2f6`. Every section is new on
this day: the row had no plan file before.*

*Fix round 2, the same day, after review 1: F4 (the script's command), 4.1 (what is copied),
the oracle of part 650a, section 10 (risk 3) and section 11 changed.*

## 1. What this row is

**The row's text** (`sql/varka/plans/m8/PLAN.md`, section 3, row 650): add `VarkaCountBenchmark`,
a new feature family in its own file. It times `SELECT 4 * count(*) / n FROM t WHERE x * x + y *
y < 1` over two stored `DOUBLE` columns of uniform draws in [0, 1) (stored, since an inline
`rand()` is nondeterministic and declines), and the two `COUNT(*)` sections of
`VarkaFilterBenchmark`, moved or repeated there. It runs on an AMD EPYC 7763, Varka on and off
and stock Spark 4.3.0. **Done when** the results file holds the three sections from one EPYC
7763 run, before row 651 changes any code.

**Why.** The milestone's post (row 355) wants a demonstration anyone reads: the Monte Carlo
estimate of pi. A point (x, y) drawn in the unit square falls inside the quarter circle with
probability pi / 4, so four times the share of rows with `x * x + y * y < 1` estimates pi. The
query is one fused filter under a `count(*)`, the shape row 651 changes. This row measures it
before that change.

**What it gives the rows after it.** Row 651's part 2 adds a third arm to the class. Row 652
reruns it. Row 355 quotes its files.

**What must be merged first.** Nothing.

**Which item of the done-when list it moves** (`m8/PLAN.md` 1.1): items 7 and 8, the post, by
section 12.5 of that plan ("Items 7 and 8: 355, then 650, 651 and 652, and 354"). It moves no
census cell.

## 2. What an agent must know

- A Varka SQL benchmark is a Java class on `VarkaSqlBenchmarkBase`
  (`sql/core/src/test/java/org/apache/spark/sql/execution/benchmark/`). It builds two sessions
  over an Arrow-cached table with `VarkaArrowSessions.createSession(name, varkaEnabled,
  warmupEnabled, master)`, checks that the Varka session's kernel answered every batch
  (`requireKernel`), then times each query on both. `VarkaFilterBenchmark` is the model.
- A *results file* is what the class prints, committed under `sql/core/benchmarks/`. A file
  measured on a GitHub runner whose CPU was checked is a *runner results file*, named
  `<Class>-jdk25-runner-epyc7763-results.txt`, with a `-provenance.txt` twin, and the same pair
  with `-128bit` for the run under `-XX:MaxVectorSize=16`
  (`sql/varka/skills/benchmarking.md`, "Start here", point 1).
- `benchmark.yml` builds this fork and runs a benchmark class in it. It cannot run a stock
  Spark release. The only tool that does is the *surface driver*: the module `sql/varka/bench`,
  a plain application submitted to a downloaded Spark by `dev/varka_bench_surface.sh`, run on
  runners by `varka-surface-benchmark.yml`. In one run it times four arms: stock Spark 4.3.0 on
  JDK 17 and on JDK 25, this fork with Varka off, and this fork with Varka on. For every filter
  entry it times two shapes, one of them "filter, counted": `SELECT count(*) FROM <table> WHERE
  <predicate>`. Row 633 added the math functions to it (#1635, results #1640); that pull
  request is the model of part 650b.
- *The two-lane refusal.* On an x86 host whose 64-bit vectors have two lanes (128 bits), the
  compiler leaves every filter condition over a 64-bit lane, a `DOUBLE` among them, to Spark,
  unless `spark.sql.codegen.varka.declineTwoLaneConditions` is set false. So in the 128-bit run
  the pi filter is Spark's own and no Varka node is planned for it.

**The names this row adds.**

| name | part | what it is |
| :--- | :--- | :--- |
| `VarkaCountBenchmark` | 650a | the class, `sql/core/src/test/java/org/apache/spark/sql/execution/benchmark/VarkaCountBenchmark.java` |
| views `varka_unit(x, y)` and `varka_dates(d, i)` | 650a | its two cached tables |
| `VarkaCountBenchmark-jdk25-runner-epyc7763[-128bit]-{results,provenance}.txt` | 650a | its four baseline files, under `sql/core/benchmarks/` |
| `Counts`, `CountSurfaceBenchmark`, `TableShape.UNIT`, `--benchmark count` | 650b | the stock arm, in `sql/varka/bench` |
| `CountSurface-<label>-zen3-results.txt`, four files | 650b | its results, under `sql/varka/bench/benchmarks/` |

## 3. The admission check

Read on master `6dafb8bd2f6`; no command starts a JVM.

**F1. Nothing exists.**

    git grep -l 'VarkaCountBenchmark' -- . ':!sql/varka/plans'      # prints nothing
    git branch -a --list '*varka-650*'                              # prints nothing

`VarkaCountBenchmark` is named only in `m8/PLAN.md`. The branch `varka-pi-rows` filed the three
rows and is merged (#1702). The only pi query in the tree is Spark's own example
`examples/src/main/java/org/apache/spark/examples/JavaSparkPi.java`, an RDD job.

**F2. Today's numbers, on the laptop only.**
`sql/core/benchmarks/VarkaFilterBenchmark-jdk25-results.txt`, sections "COUNT(*) over the
filter, 15% selected" and "COUNT(*) over the filter, 85% selected": the Varka arm reads 1.8X and
0.8X. The file's CPU line is the laptop's (`AMD Ryzen AI 9 HX PRO 370`). No runner file of that
class exists: `ls sql/core/benchmarks | grep VarkaFilterBenchmark` prints one file.

**F3. The two `COUNT(*)` sections' definition**, to repeat: `VarkaFilterBenchmark.cacheDates`
(2,000,000 rows, `d` null on every 31st row and cycling over 1460 days from 2020-01-01),
`cutoff(days)`, `countQuery(days)` with 219 and 1241 days, timed by `runRowPair`, whose body is
`session.sql(query).queryExecution().toRdd().count()`.

**F4. The pi table, modelled.** With `x = (hash(id, 1) + 2147483648.0D) / 4294967296.0D` and
`y` the same over `hash(id, 2)`, both lie in [0, 1) and never reach 1: `hash` is an `INT`, so
the numerator is at most 2^32 - 1. A port of Spark's Murmur3 to Python
(`scratch-650/pi_table.py` in the ops home's `m8-row-plans`, held to two published vectors of
the algorithm) counts, for 20,000,000 rows, 15,706,357 inside the circle: an estimate that is
off from pi by less than 0.001. Its last line reads "pi_table: rows 20000000, inside 15706357,
estimate 3.141271, off by 0.000321, least 0.000000011, greatest 0.999999981". The command,
from the ops home's `m8-row-plans`, is `python3 scratch-650/pi_table.py 20000000`; the row
count is its one argument and has no default. The benchmark
does not hard-code that count; it checks the estimate against pi within 0.01 (4.1). At
2,000,000 rows the same script counts 1,570,850 inside.

**F5. The surface driver times a counted filter already.**
`DateSurfaceBenchmark.filterQuery` builds `SELECT count(*) FROM <table> WHERE <filter>` and
`runEntry` times it as "filter, counted" on every arm.

    grep -n '"filter, counted"' sql/varka/bench/src/main/java/org/apache/spark/sql/varka/bench/DateSurfaceBenchmark.java

**What I could not check** (no JVM): that `x * x + y * y < 1` fuses whole at 256 bits. The
coverage table has the double product, the double sum and the double comparisons as separate
rows (`grep -n '"sql" : "x \* x2"\|"sql" : "x + x2"\|"sql" : "x < 1.5D"' sql/varka/coverage.json`
prints three lines), not their composition under a comparison. The command for the lead:
`dev/varka_emit.sh "x * x + y * y < 1" --columns x:double,y:double`, done when the entry's line
ends `FusedOutput(0)`. Part 650a's dry check fails loudly if it does not fuse (5, step 4).

## 4. The design

### 4.1 `VarkaCountBenchmark` (part 650a)

Java, `extends VarkaSqlBenchmarkBase`, modelled on `VarkaFilterBenchmark`. Its class Javadoc
says what section 1 says, names the three sections, and ends with the "To run this benchmark"
block naming `benchmark.yml` (5, step 5).

**The tables.**

    private static final int UNIT_ROWS = 20_000_000;
    private static final int DATE_ROWS = 2_000_000;

    /** Two DOUBLE columns of uniform values in [0, 1), hashes of the row id; no nulls. */
    private static void cacheUnit(SparkSession session) {
      session.sql("""
          select (hash(id, 1) + 2147483648.0D) / 4294967296.0D as x,
                 (hash(id, 2) + 2147483648.0D) / 4294967296.0D as y
          from range(0, %d)""".formatted(UNIT_ROWS))
          .createOrReplaceTempView("varka_unit");
      VarkaArrowSessions.cache(session, "varka_unit");
    }

`cacheDates(session)` is `VarkaFilterBenchmark.cacheDates` copied, with its view name and
`DATE_ROWS`, and `cutoff(days)`, `predicate(days)` and `countQuery(days)` copied with it
(`countQuery` calls `predicate`, and `cutoff` needs the import of `java.time.LocalDate`).
**Decided: they are repeated, not moved.** `VarkaFilterBenchmark` and its committed file stay
as they are, so that file's two sections stay readable beside the new class's. The pi table has ten
times the rows of the date table because its kernel does so little per row that at two million
rows the job's fixed cost would be most of the time; the date sections keep the row count of
the sections they repeat, so the two files can be read side by side.

**The queries and the section titles.** The titles are fixed: row 651, row 652 and the post
name them.

| section title | query |
| :--- | :--- |
| `pi: 4 * count(*) / n where x * x + y * y < 1` | `SELECT 4 * count(*) / 20000000 AS pi FROM varka_unit WHERE x * x + y * y < 1` |
| `COUNT(*) over the filter, 15% selected` | `countQuery(219)` |
| `COUNT(*) over the filter, 85% selected` | `countQuery(1241)` |

**One section.**

    private static long run(SparkSession session, String query) {
      return session.sql(query).queryExecution().toRdd().count();
    }

    private void runSection(
        SparkSession baseline, SparkSession varka, String name, String query, int rows) {
      // Both engines must give one answer before either is timed.
      require(baseline.sql(query).collectAsList().equals(varka.sql(query).collectAsList()),
          "section '" + name + "': Varka's answer differs from the row engine's");
      // A filter over 64-bit lanes is left to Spark at two lanes (the two-lane refusal), and
      // then no kernel runs: the arm says so instead of carrying the kernel's name.
      boolean fused = holdsVarkaNode(varka.sql(query).queryExecution().executedPlan());
      if (fused) {
        requireKernel(varka, name, query);
      }
      section(name, () -> {
        VarkaBenchmark benchmark = benchmark(
            name + " over " + rows + " Arrow-cached rows", rows).timed().build();
        benchmark.addCase("baseline (Janino)", i -> run(baseline, query));
        benchmark.addCase(fused ? "varka (SIMD)" : "varka (filter left to Spark)",
            i -> run(varka, query));
        runCases(benchmark);
      });
    }

`runBenchmarkSuite` follows `VarkaFilterBenchmark`'s: `stopLeftoverSessions()`, the two sessions
(`"VarkaCount-baseline"` with Varka off, `"VarkaCount-varka"` with it on, both `local[1]`,
`clearSessions()` after each), the `require` that they differ, both tables cached on both
sessions, then before the first section

    double pi = ((Number) varka.sql(PI_QUERY).collectAsList().get(0).get(0)).doubleValue();
    require(Math.abs(pi - Math.PI) < 0.01, "the pi table estimates " + pi);

(Spark's `/` over integral operands is a `DOUBLE` division; reading the cell as a `Number`
holds whatever numeric type the session's settings give it), the three `runSection` calls, and
`baseline.stop()` and `varka.stop()` in a `finally`. The date sections
must fuse on every host: after `cacheDates`, `require(holdsVarkaNode(...))` for `countQuery(219)`.

### 4.2 The stock arm (part 650b)

A new entry list in the surface driver, as row 633's `Maths`:

- `Counts.java`: `ENTRIES = List.of(Surface.Entry.filter("x * x + y * y < 1"))`, with a Javadoc
  that says why it is a filter entry (the driver's "filter, counted" shape is the pi query less
  its final arithmetic) and names the table.
- `CountSurfaceBenchmark.java`: `main` calls `DateSurfaceBenchmark.run(argv, Counts.ENTRIES,
  "count", "VarkaCountSurface", DateSurfaceBenchmark.TableShape.UNIT)`, as
  `MathSurfaceBenchmark` does with its own four arguments.
- `DateSurfaceBenchmark.TableShape`: a fifth value `UNIT`, with `"unit"` in `of`, columns
  `List.of("x", "y")`, table name `varka_unit`, filter column `x`, provenance `"unit (2)"`, a
  `buildUnitTable` beside `buildDoublesTable` holding the two expressions of 4.1 over
  `range(0, %d, 1, %d)`, its arm in `buildTable`, and `UNIT` beside `DOUBLES` in
  `buildParquetTable`'s refusal.
- `dev/varka_bench_surface.sh`: the `count)` line of the `case "$benchmark"` switch
  (`main_class=...CountSurfaceBenchmark; stem=CountSurface`), `count` in the usage comment and
  in the error message, a comment paragraph for it, and `count` beside `math` in the refusal of
  `--input parquet`.
- `.github/workflows/varka-surface-benchmark.yml`: `- 'count'` in the `benchmark` input's
  options, and one sentence in its description.
- `CountsTest.java`, `MathsTest`'s twin: the entry runs as a counted filter on the stock
  release the module compiles against, over a thousand rows; the table has two `DOUBLE` columns
  with no null, every value in [0, 1); the label is shared with no other surface; the entry is
  satisfied by `UNIT` and refused by `ALL`; the shape builds the columns it claims; Parquet
  input is refused. And `SurfaceTest`'s cleanup list gains `"varka_unit"` where it names
  `"varka_doubles"`.

The surface's count is over 100,000,000 rows on the runner, its usual row count; the pi table
there is 1.5 GiB cached, as the math table is (the provenance block of
`sql/varka/bench/benchmarks/MathSurface-varka-jdk25-zen3-results.txt`).

### 4.3 The 128-bit run

At `-XX:MaxVectorSize=16` on an EPYC 7763 the pi filter is left to Spark (section 2, the
two-lane refusal), `fused` is false for the pi section, and its second arm is named `varka
(filter left to Spark)`. That is the default a user gets at that width, and the file records it.
The date sections are `INT`-lane kernels and fuse at every width.

## 5. The parts

### Part 650a: `[VARKA-650] Time the pi query and two counted filters before the fused count`

Branch `varka-650a-count-benchmark`, from `origin/master`. Builder: Sonnet-class.

**B0, the premises.**

    git grep -l 'VarkaCountBenchmark' -- . ':!sql/varka/plans' | wc -l                 # 0
    grep -c 'private static String countQuery(int days)' sql/core/src/test/java/org/apache/spark/sql/execution/benchmark/VarkaFilterBenchmark.java   # 1
    grep -c 'public static void requireKernel' sql/core/src/test/java/org/apache/spark/sql/execution/benchmark/VarkaSqlBenchmarkBase.java            # 1
    grep -c 'def cache(session: SparkSession, view: String)' sql/core/src/test/scala/org/apache/spark/sql/execution/benchmark/VarkaArrowSessions.scala  # 1

**The steps.**

1. Write `VarkaCountBenchmark.java` as 4.1 says. License header from `VarkaFilterBenchmark.java`.
2. Compile: `build/sbt sql/Test/compile` ends in `[success]`.
3. `dev/lint-java` passes.
4. The dry check, which builds both tables, runs every guard and times nothing:

        build/sbt -batch "project sql" 'set Test/javaOptions += "-Dvarka.bench.dryRun=true"' \
          "Test/runMain org.apache.spark.sql.execution.benchmark.VarkaCountBenchmark"

   Done at `[success]`. A failure names its guard: "did not fuse" or a `requireKernel` message
   means the pi filter does not fuse whole on this host; stop and report the message, and the
   output of `dev/varka_emit.sh "x * x + y * y < 1" --columns x:double,y:double`. Do not time
   the class on the laptop.
5. Open the pull request (the closing flow of `sql/varka/ADDING_AN_EXPRESSION.md`, "Closing a
   row"), then dispatch the two baseline runs on it. `<n>` is the pull request's number:

        gh workflow run benchmark.yml -R vecbricks/varka -f pr=<n> \
          -f class=VarkaCountBenchmark -f expected-cpu="AMD EPYC 7763"
        gh workflow run benchmark.yml -R vecbricks/varka -f pr=<n> \
          -f class=VarkaCountBenchmark -f expected-cpu="AMD EPYC 7763" \
          -f extra-java-options="-XX:MaxVectorSize=16"

   A run that lands on another CPU fails at the step "Probe the machine's CPU and vector
   datapath" and is retried hourly by `varka-bench-cpu-retry.yml`, up to six attempts; after
   "gave up", dispatch again (`sql/varka/skills/benchmarking.md`, "Start here", point 2).
6. Download each run's artifact (`gh run download <run-id> -R vecbricks/varka`), untar it, and
   commit its two files under the runner spelling, since no tool names a class's first runner
   files: from the first run `sql/core/benchmarks/VarkaCountBenchmark-jdk25-results.txt` becomes
   `VarkaCountBenchmark-jdk25-runner-epyc7763-results.txt` and the provenance file
   `...-runner-epyc7763-provenance.txt`; from the second, `...-runner-epyc7763-128bit-results.txt`
   and `...-runner-epyc7763-128bit-provenance.txt`. `VarkaMathBaselineBenchmark`'s four files
   are the model. Check each provenance file's `cpu:` line reads `AMD EPYC 7763`.
7. The row's state in `m8/PLAN.md`: `**Started** (650a merged as #<n>, runs <id> and <id>; left:
   650b, the stock arm)`, with one dated sentence quoting, from the 256-bit file, the
   `Relative` of the Varka arm in each of the three sections. Fill section 9 of this file.

**The oracle.** The 256-bit results file holds three sections with the titles of 4.1, each
with a `baseline (Janino)` row and a `varka (SIMD)` row; the 128-bit file's pi section reads
`varka (filter left to Spark)`.

    grep -c '^pi: \|^COUNT(\*) over the filter' sql/core/benchmarks/VarkaCountBenchmark-jdk25-runner-epyc7763-results.txt   # 6: each title twice
    grep -c 'varka (SIMD)' sql/core/benchmarks/VarkaCountBenchmark-jdk25-runner-epyc7763-results.txt                        # 3
    python3 dev/varka_quote_check.py sql/varka/plans/m8/PLAN.md                                                             # 0 orphans

The first count is 6 because Spark's harness prints a section's title twice, as a banner and
as the table's header, and each of the three titles starts a line both times. Any other count
means a section is missing or renamed: stop and report the file.

**Done when** the four files are committed from two runs on an EPYC 7763 and row 651 has
merged no code. **Not here:** no edit of `VarkaFilterBenchmark` or its file; no laptop results
file; no third arm (row 651's part 2 adds it).

### Part 650b: `[VARKA-650] Time the pi query against stock Spark 4.3.0`

Branch `varka-650b-count-surface`, from `origin/master`. Independent of 650a. Builder:
Sonnet-class, with `git show d127a7f62d8` (#1635) open: it is the same change for another
table, file by file.

**B0, the premises.**

    grep -c 'ALL, DATES, TIMES, DOUBLES;' sql/varka/bench/src/main/java/org/apache/spark/sql/varka/bench/DateSurfaceBenchmark.java   # 1
    grep -c "math)    main_class" dev/varka_bench_surface.sh                                                                         # 1
    grep -c "          - 'math'" .github/workflows/varka-surface-benchmark.yml                                                       # 1
    grep -c 'STOCK_VERSION: 4.3.0' .github/workflows/varka-surface-benchmark.yml                                                     # 1

**The steps:** the six bullets of 4.2, in that order. Then:

    ./build/mvn -q -f sql/varka/bench/pom.xml test          # exits 0
    dev/varka_bench_surface.sh --help | grep -c count        # 1 or more

and, once the pull request is open, one run at 256 bits (as #1640's run was dispatched; the
128-bit run is not asked for, since the pi filter does not fuse there):

    gh workflow run varka-surface-benchmark.yml -R vecbricks/varka --ref <branch> \
      -f stop-after=run -f require-datapath=any -f expected-cpu="AMD EPYC 7763" \
      -f benchmark=count -f rows=100000000 -f label-suffix=-zen3

The workflow's `--ref` must hold the new choice; if it refuses a fork's branch, merge the code
first and dispatch on `master`, and commit the results in a second pull request, as row 633
did (#1635, then #1640). Commit the four files the run's artifact holds, under
`sql/varka/bench/benchmarks/`: `CountSurface-spark-4.3.0-jdk17-zen3-results.txt`,
`CountSurface-spark-4.3.0-jdk25-zen3-results.txt`, `CountSurface-varka-off-jdk25-zen3-results.txt`
and `CountSurface-varka-jdk25-zen3-results.txt`. Each file's `cpu:` line must read `AMD EPYC 7763`
and all four must name one `run:`.

**Done when** the four files are committed from one run, the Varka arm's file says the entry
ran fused, and row 650 is marked `**Done**` with the run's link and, for the "filter, counted"
shape, the stock JDK 25 time over the Varka time. **Not here:** no change to the other
surfaces' entries or committed files; no 128-bit run.

## 6. Tests

`CountsTest` (650b) is the only suite. `VarkaCountBenchmark` is held by its own guards (equal
answers, the estimate, `requireKernel`), run by step 4's dry check and again by each runner
run. No proof, no fuzzer production, no coverage row: the row adds no kernel.

## 7. Measurement

This row is the measurement. **Predictions, registered before the runs.**

1. On the EPYC 7763 at 256 bits, the 85% section's Varka arm reads below 1.0X, as the laptop's
   0.8X does. Yes or no.
2. The 15% section's Varka arm reads above 1.0X. Yes or no.
3. The pi section's Varka arm at 256 bits reads below 1.5X: 78.5% of its rows pass (F4), so
   most rows still cross to Spark's aggregate. Yes or no.
4. In the surface run, stock 4.3.0 on JDK 25 and the fork with Varka off differ by less than
   10% on the counted shape. Yes or no.

Row 650's closing note scores them from the committed files.

## 8. Who builds it

| part | counted lines (estimate) | builder | starts from | waits on |
| :--- | ---: | :--- | :--- | :--- |
| 650a | about 190: the class 170, plan text 20; results files do not count | Sonnet-class | master | nothing; must merge before row 651's part 2 |
| 650b | about 300: entries and main 70, driver 60, script and workflow 30, tests 130, plan 10 | Sonnet-class | master | nothing |

Neither part needs full strength: 650a copies `VarkaFilterBenchmark`'s structure with the code
of 4.1, and 650b repeats #1635.

## 9. Outcome

To be written when the row closes: the runs, the files, the four predictions scored.

## 10. Risks and house rules

1. **The pi filter does not fuse whole.** Held by step 4's dry check; the builder stops.
2. **A benchmark number from a busy or wrong machine.** Held by `expected-cpu` and by never
   timing on the laptop (`sql/varka/skills/benchmarking.md`, "Start here").
3. **The baseline is taken after row 651 changed the path.** Row 651's parts 1 and 1b change
   no plan while its switch is off, and the default is off until that row's part 3a, which
   comes after its part 2; the order that matters is that 650a's files are committed before
   651's part 2 edits the class.
4. **Sections renamed later.** The titles of 4.1 are quoted by rows 651 and 652; do not change
   them.

House rules that bind: a benchmark is Java on `VarkaSqlBenchmarkBase`; a new family gets its
own class; a performance claim traces to a committed file; do not rename a committed results
file; a pull request stays under 1000 counted lines (`sql/varka/AGENTS.md`, "Measurements, not
adjectives" and "Pull requests").

## 11. Questions for the owner

**Answered by the owner on 28 November 2026**: question 1 (a), "ok"; question 2 (a),
"keep". Both are built as written.

1. **The stock Spark 4.3.0 arm.** The row asks for it "through `benchmark.yml`", which builds
   the fork and cannot run a stock release. Options: (a) part 650b, the surface driver's
   `count` benchmark, as row 633 did for the math functions; (b) no stock arm: the fork with
   Varka off stands for stock, as row 552's admission check argues the two agree on these
   paths. Recommendation: (a), since the post compares with the newest release and can quote
   only committed files. **Default applied: (a).**
2. **The pi table's size**, 20,000,000 rows against the date table's 2,000,000 (4.1). Options:
   (a) as planned, since the pi kernel does so little a row that two million rows would time
   the job's fixed cost; (b) one row count for both tables. Recommendation: (a). **Default
   applied: (a).**

Decided in the text, no longer asked: the two `COUNT(*)` sections are repeated in the new
class, not moved out of `VarkaFilterBenchmark` (4.1).
