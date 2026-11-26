# VARKA-224: Benchmarks and tools in Java

## 1. Where this came from

Row 224 of `m7/PLAN.md`, from `m10/SCOPE.md` item 81 and moved here from milestone 6 on 2 October
2026 (`m6/PLAN.md` row 224, which carries the full brief). Two Scala layers sit between a Java
author and the measuring tools:

* **Spark's benchmark harness.** The catalyst and core benchmarks are Scala only because they
  extend Spark's `BenchmarkBase` and `Benchmark`, whose `-results.txt` format
  `dev/varka_bench_regen.sh`, `dev/varka_bench_diff.py` and the quote check read. From Java the
  harness is callable but awkward: the `Benchmark` constructor's defaults become seven arguments,
  two of them Scala types (`FiniteDuration`, `Option[OutputStream]`); `addCase(name)(f)` becomes a
  `Function1` that must return `BoxedUnit.UNIT`; `runBenchmark`'s block a `Function0`.
* **`VarkaEmitDump`**, behind `dev/varka_emit.sh`, through which every plan's registered op counts
  and the emission profiles go. Its Scala layer once cost a measurement: it handed the emitter a
  Scala `List` through `asJava`, whose `get(i)` walks from the head, and the admission probe of
  VARKA-191 read emission three times slower than the benchmark did (`VARKA-191.md` 9).

The row is done when an adapter exists with one benchmark on it reproducing its committed results,
`VarkaEmitDump` is Java with `dev/varka_emit.sh`'s output unchanged, and `sql/varka/AGENTS.md`
says that a new benchmark is written in Java on the adapter.

## 2. The admission check, done

**A Java benchmark needs no `main` of its own** (11 October 2026). The brief expected a Java
subclass to write the static `main` a Scala `object` gets for free, and that would not compile:
`BenchmarkBase.main` is an instance method, and Java refuses a static method that hides one. So
either the Java side stops extending `BenchmarkBase`, and restates its twenty lines of `main`
(the results file's name from the class's simple name, the `-jdk<N>` suffix, the
`SPARK_GENERATE_BENCHMARK_FILES` switch), or the class is launched without a static `main`.

JDK 25 launches a class through an inherited instance `main(String[])` when it declares no static
one (JEP 512, final in 25), and `catalyst/Test/runMain` forks a JVM through that launcher
(`fork := true` in `project/SparkBuild.scala`'s test settings). A probe confirmed it: a Java class
extending `BenchmarkBase`, with one `runBenchmark` section and no `main`, ran under
`SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "catalyst/Test/runMain ..."`, and Spark's own `main`
wrote its section to `sql/catalyst/benchmarks/VarkaJep512Probe-jdk25-results.txt`. Spark's code
therefore keeps naming and writing the file, and the adapter restates none of it. The check would
have rejected the design if sbt had looked the method up as static itself, as an unforked `run`
does; then the adapter would have had to own `main`, with the file-name rule stated twice.

**The dump's baseline**: `dev/varka_emit.sh` run on master over a fixed corpus of fourteen cases
(5), each saved with its exit code and with its two timing fields masked, before any code moved.

## 3. The design

### 3.1 The adapter

Two Java classes in the catalyst test tree, package `org.apache.spark.sql` beside the benchmarks:

* **`VarkaBenchmarkBase`**, an abstract subclass of Spark's `BenchmarkBase`. A benchmark extends it
  and implements `runBenchmarkSuite(String[])`. It offers `section(String, Runnable)` over
  `runBenchmark`, and `benchmark(String, long)`, which returns a builder whose output stream is
  already the run's results file. It declares no `main`; the inherited one is the launcher's
  entry, as 2 found.
* **`VarkaBenchmark`**, a wrapper over one Spark `Benchmark`: a builder with Spark's defaults (two
  iterations, two-second warm-up and minimum time, as `java.time.Duration`), then
  `addCase(String, IntConsumer)`, `addCase(String, int, IntConsumer)` for a fixed iteration count,
  and `run()`. Spark's `Benchmark` still times the cases and writes the table, so the results
  format has one source.

Only what the ported benchmark and the obvious next ones need is wrapped; `addTimerCase` and the
per-iteration output wait for a benchmark that uses them.

### 3.2 The benchmark ported onto it

`VarkaCompileBenchmark` (144 lines), which times the compiler families: the smallest catalyst
benchmark, independent of the vector width, and the one `dev/varka_bench_pair.sh` documents. It
keeps its fully qualified name, so `dev/varka_bench_regen.sh` and the pair runner take it
unchanged, and its results file is the same file.

### 3.3 `VarkaEmitDump` in Java

Same class name, now `src/test/java/.../codegen/varka/VarkaEmitDump.java`, with a static `main`
(a tool, not a benchmark). The arguments become a record; the entry dump, the `--table` printer
and the `--rounds` driver stay as methods in one file. The compiler facade already takes and
returns Java types except its two `Seq` arguments, which the dump builds with
`scala.jdk.javaapi.CollectionConverters` as `VarkaVectorProjection` does; `VarkaSqlResolve` stays
Scala, shared with `VarkaWordCensus` and the Scala benchmarks. Its output is unchanged line for
line, including two details a port could drift on: `maxBy` over the parameter slots keeps the first
of equal maxima, and an exit through `System.exit(1)` after the declines.

### 3.4 What is deliberately unchanged

* **The other Scala benchmarks**, ported when a task touches them, as the compiler families were.
  A port proves itself by reproducing its own committed results.
* **`sql/varka/bench/.../Harness.java`**, the surface harness, which writes a different format, and
  the engine's JMH benchmarks.
* **`VarkaSqlResolve`**, as above.

### 3.5 Registered op counts

None move; nothing in the emitter changes.

## 4. Files

| file | what |
|---|---|
| `sql/catalyst/src/test/java/org/apache/spark/sql/VarkaBenchmarkBase.java` | the base class |
| `sql/catalyst/src/test/java/org/apache/spark/sql/VarkaBenchmark.java` | the `Benchmark` wrapper |
| `sql/catalyst/src/test/java/org/apache/spark/sql/VarkaCompileBenchmark.java` | the port; the `.scala` deleted |
| `sql/catalyst/src/test/java/.../codegen/varka/VarkaEmitDump.java` | the port; the `.scala` deleted |
| `sql/varka/AGENTS.md`, `docs/sql-varka.md`, `sql/varka/skills/` | the rule, references to the Scala files, the launcher finding |
| `sql/varka/plans/m7/PLAN.md`, `VARKA-224.md` | the row and this plan |

## 5. Tests, and what each is for

* **The dump's corpus**, before and after, byte-identical after masking the two timing fields:
  a single entry; two entries under `--options`; `--rounds` on the int lane, with `--nulls` (the
  masked path) and with `--rows` off the lane multiple (the epilogue); `--repeat`; the long lane
  (`second(t)`); `--table` with a variant; an all-declining projection (exit 1, the declines
  listed); a mixed one (a decline on its entry line); a short column under `--rounds` (the skip
  line); interval and int arithmetic; an invalid flag value (the refusal); and no expression (the
  usage, exit 2). It catches any change of format, order, rounding or exit code.
* **The benchmark** reproduces its committed results (6).
* **Suites**: the gate's catalyst compile builds both ports, and nothing else depends on them.

## 6. The measurement

`dev/varka_bench_pair.sh catalyst VarkaCompileBenchmark`, on the idle laptop: the Scala class at
the merge base and the Java port on the branch, in alternating rounds, compared by median. The
pair runner restores the committed files after each round, so no results file changes. If the
port reproduces the merge base's numbers, the committed file stands for both.

### 6.1 Predictions, registered before the run

1. **Every row within the pair runner's default threshold (10%)**, and within 3% for most: the
   timed calls are the same compiler calls with the same arguments; only the loop around them
   changes language.
2. **The dump's corpus is byte-identical** after masking.
3. **The Java dump is shorter than the Scala one** (466 lines), as the ported compiler families
   were not; if it is longer, the cause is the boundary with Scala and is said in 9.

## 7. Risks

1. **The launcher dependency.** A benchmark without a static `main` runs only through a launcher
   that knows JEP 512: a forked sbt run, `java`, or an IDE on JDK 25. An unforked sbt `run` would
   not find it. Every test configuration here forks, and the base class's documentation says so.
2. **The sink.** The Scala benchmark keeps its results live through a `@volatile` field; the port
   must keep the same so the JIT cannot drop the calls, or its numbers come out faster than the
   Scala's for the wrong reason. A row faster beyond 6.1's band is read as this first.
3. **NullAway.** VARKA-266, open as this is written, checks Varka's Java under the codegen package
   at error level; the test tree is not in its source list, so the ports are written without
   nullable fields but are not checked by it.

## 8. Sequencing

1. This plan, and the row marked Planned.
2. The adapter, `VarkaCompileBenchmark` on it, `VarkaEmitDump` in Java, the rule and the
   references, in one commit, with the corpus compared.
3. The pair measurement and section 9.

## 9. Outcome

Done on 11 October 2026, in three commits after the plan: the adapter with the two ports, the
benchmark scripts' class lookup, and this section.

**The dump is unchanged.** All fourteen cases of 5 print the same bytes and exit codes on the
port as on master, the two timing fields masked; the corpus was run again after the lint fix to
the argument parser, with the same result.

**The benchmark reproduces the Scala one.** `dev/varka_bench_pair.sh catalyst
VarkaCompileBenchmark`, five interleaved rounds of the Scala class at master `03a3178e1f8` and the
Java class on the adapter (`sql/catalyst/benchmarks/VarkaCompileBenchmark-jdk25-224-pair-results.txt`):
no row slower or faster by 10 per cent or more by the median, every row within 5 per cent, and six
of the eight within 3, the Java side reading slightly slower on all eight. The power profile was
`performance` before and after.

**And its committed results.** The pair's per-call times sit beside
`VarkaCompileBenchmark-jdk25-results.txt` as master commits it: the calendar projection at 4,107
to 4,360 ns over the ten runs against the file's 4,247, the TIME one at 4,541 to 4,689 against
4,597. So the Java benchmark reproduces its committed file, which is what 1 asked of a port, and
the file stands for it unchanged.

**Predictions scored.**

1. **Held.** Every row within the pair runner's 10 per cent, and most within 3 (six of eight); the
   two outside it, the sixty-output projection and the range predicate, are within 5. No row is
   faster, so 7's second risk, a sink the JIT could drop, did not show.
2. **Held.** The corpus is byte-identical.
3. **Wrong.** The Java dump is 573 lines against the Scala's 466. The difference is spelling, not
   the boundary with Scala, which shrank to three `Seq` conversions: Scala's format strings, its
   `zip` and `map` chains and its pattern-matched `foreach` become explicit loops and
   `String.format` calls in Java, and the arguments a record and a parse method.

**Corrections to this plan, which is a record and is not rewritten:**

* 3.2 says the port keeps its name, "so `dev/varka_bench_regen.sh` and the pair runner take it
  unchanged". They did not: both that script and `dev/varka_bench_repeat.sh` resolved a bare class
  name from its `.scala` file, and the first pair run stopped on the Java side. They now look for
  a `.java` file too and read its package line without the semicolon.
* 3.3 says `VarkaSqlResolve` stays Scala. It does, but gains three entries for Java: a
  parse-and-resolve, since `CatalystSqlParser.parseExpression` is an instance method Java cannot
  reach through a static forwarder, and a column and an alias, whose case classes keep their
  expression ids in a second parameter list Java cannot default.
* The gate's lint failed once: Spark's checkstyle allows one statement per line, and the argument
  parser's switch arms had two. Fixed before the pull request opened.
