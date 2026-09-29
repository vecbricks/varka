# Task 202: Varka against an interpreted vector engine - vecruntime on the size ladder

*Planned 29 September 2026 (milestone 6 row 202), with its admission check run the same day on
the laptop. The owner made the row owed before the closing post (`PLAN_TASK_181.md` 10).*

## 1. What is known

**The row.** A vector-at-a-time engine evaluates each expression node with its own kernel into a
materialized column, so it has no 8000-byte cliff, the same as Comet and Velox. The post's claim
is only fair if it says what fusion adds beyond having no cliff. The row asks for an arm of such
an engine on task 171's size ladder and on the date chains, with every rung checked from the
engine's recorded fallback reasons before its number is timed.

**The engine.** The row names spark-vector; the project has since been renamed vecruntime
(`github.com/vecruntime/vecruntime`) and released 0.0.3 on 27 September 2026. What bears on the
arm, from its README and `docs/`:

* A plugin jar for stock Spark, turned on with `spark.plugins=io.vecruntime.spark.VectorPlugin`
  and the Vector API's module in the driver's and executors' Java options. It converts `Filter`,
  `Project`, aggregates, sorts and joins into its own operators over Arrow-layout batches, one
  kernel per expression node, and falls back to Spark for anything else with a reason it logs
  under `spark.vecruntime.explainFallback.enabled`.
* **Spark 4.1.x only.** The arm runs on Spark 4.1.3, not the 4.2.0 the other stock arms use, so
  it needs a stock 4.1.3 arm of its own as its control. Spark 4.1 on JDK 25 also needs Hadoop
  3.4.3's client jars in place of the bundled 3.4.2.
* **A cached table is left to Spark** (`InMemoryTableScanExec`, its issue 55), so the arm reads
  Parquet through Spark's vectorized reader - the input task 194 added to the ladder's driver.

## 2. The admission check, 29 September 2026

On the laptop, JDK 25.0.4.1, Spark 4.1.3 with the Hadoop client swapped, vecruntime 0.0.3 from its
release, checksums verified for all three. A functional check, not a measurement: 200 000 rows,
`local[1]`, the machine not held quiet, and nothing committed from it.

**The plugin takes the ladder.** `EXPLAIN` of the sixteen-entry rung over a Parquet table:
`VectorProject [greatest(add_months(d, 1), date_add(d, 1), last_day(d)) AS c1, ...]` directly
over the `FileScan parquet`, with Spark's `ColumnarToRow` above it; a filtered query plans
`VectorFilter` under `VectorProject`. Run through the driver with the new arm (section 3), every
rung plans `VectorProject` and none has a row-engine node above it.

**It takes none of the date chains.** All twelve fall back, each for one logged reason,
"unsupported expression DateAddYMInterval": every chain adds a year-month interval column to a
date, which the plugin does not convert. The driver's check fails the run on the first of them,
as it should, so the chains have no vecruntime arm to time. The plugin's own expression table
lists `add_months` with an integer month count, and no date-plus-interval arithmetic.

**The ladder's first reading**, nanoseconds a row by executor time, over Parquet:

| entries | stock 4.1.3 | vecruntime 0.0.3 |
|---:|---:|---:|
| 16 | 250 | 805 |
| 32 | 495 | 1595 |
| 48 | 730 | 2385 |
| 52 | 4025 | 2600 |
| 54 | 4125 | 2715 |
| 56 | 4360 | 3005 |
| 64 | 5000 | 3240 |
| 80 | 6275 | 4115 |
| 100 | 8060 | 5255 |

vecruntime has no cliff and grows linearly, about 50 ns a row an entry, about 12 ns for each of
an entry's four nodes, `add_months`, `date_add`, `last_day` and `greatest`, each written out as a
column. That puts it about three times slower than stock Spark below the cliff, where whole-stage
code generation keeps an entry's values in registers, and about one and a half times faster past
it. The number the row asks for is the first of these: what a materialized intermediate costs.

## 3. The design

The arm is built so a runner can run it and so no rung the plugin leaves to Spark is ever timed
under its name.

* **The driver** (`sql/varka/bench`) takes `--expect-operator REGEX`: every case must plan with a
  node whose line matches it, with no row-engine `Filter` or `Project` above, or the run fails
  after writing its file, and the `# plan:` line names the node found. It is the check
  `--expect-fused` makes for Varka, with the engine's node in place of Varka's.
  `PlanCheckTest` pins it on vecruntime's plan as `EXPLAIN` prints it.
* **The surface script** takes a `vecruntime` token in an arm's conf field: the plugin on, its
  fallback reasons logged, the Vector API's module in the driver's options, and
  `--expect-operator 'Vector(Project|Filter)'`. It refuses the token on a cached input.
* **The surface workflow** takes a `vecruntime` input. On it, the measure job assembles two homes
  from Spark 4.1.3 - stock with the Hadoop client swapped, and the same with the plugin jar -
  each file checked against its publisher's checksum and the pair cached by version, and adds
  two arms, `spark-4.1.3-jdk25` and `vecruntime-0.0.3-jdk25`, beside the stock 4.2.0 and Varka
  arms. It refuses the input on a cached dispatch. The assembly step was run locally as written.

## 4. The measurement

The ladder over Parquet on a runner, with the vecruntime arms: first one dispatch on any runner,
to see the arm work there, then the 9V45 through the datapath gate, since the post's ladder
figure is that machine's. Two million rows, the count task 194's runner ladder used; one
partition; the job-size rule as the driver enforces it. Varka's own number is its committed
runner ladder over its cache (`VarkaSizeLadderBenchmark-jdk25-runner-results.txt`); over Parquet
the fork runs the row path, which task 194 measured, so the dispatch's Varka arms are the controls
they were there.

What the post's section 3.7 may then quote: vecruntime's cost an entry, where it crosses stock
Spark 4.1.3, and Varka's ratio to it at 16, 54 and 100 entries, each with the input it read.

### 4.1 Predictions, registered before the runner run

1. Every rung plans `VectorProject` on the runner, as on the laptop, and the run passes the
   driver's check.
2. vecruntime's time is linear in entries, with no step: on the runner its per-row time at 100
   entries is within 20% of the sixteen-entry time scaled by 100/16.
3. Below the cliff vecruntime is slower than stock 4.1.3, by two to four times at 16 to 48
   entries; past it, faster, by less than two times at 100 entries.
4. Varka over its cache is faster than vecruntime over Parquet at every rung by more than ten
   times.

## 5. Risks

1. **The inputs differ.** vecruntime reads Parquet and Varka its cache, so the ratio carries the
   decode. Task 194 measured stock Spark over both and found Parquet cost it nothing the table
   shows; the file and the post name the input on every number regardless.
2. **A preview release.** vecruntime 0.0.3 is weeks old and moves daily. The arm pins the release
   and its checksum, and the provenance names both; a later release is a later dispatch.
3. **The date chains cannot run.** The finding stands as the result for the chains: the plugin
   converts none of them, and the reason is recorded rather than worked around with a different
   chain.

## 6. Sequencing

The plan and the tooling first, this pull request, since the workflow dispatches only from the
default branch. Then the dispatches, and the results with the predictions of 4.1 scored, the
post's 3.7 text and row 202 as their own pull request.

## 7. Results, 29 September 2026

**Two runs, both on a 9V45.** Both dispatches ran the ladder at this plan's commit over Parquet,
two million rows and one partition, with six arms: stock 4.2.0 on JDK 17 and 25, stock 4.1.3,
vecruntime 0.0.3, and the fork with Varka off and on. The ungated dispatch section 4 asked for
(run 36616758753) drew an AMD EPYC 9V45 whose probe read 2.00, so it is the 9V45 measurement as
well; the gated one (run 36618382924) drew a second 9V45, ran at the same time, and is its repeat.
Both concluded "failure" for the one reason task 194 recorded for this input: the fork with Varka
on plans a Varka node whose kernel serves no Parquet batch, and the driver fails the run on it
after writing the file (`PLAN_TASK_194.md` 7). vecruntime's arm passed its check in both.

**The files.** Every file the two runs wrote is committed. The first run's are the reading: the
two new arms under the driver's names, and the four whose names hold task 194's 7763 readings
with a `-9v45` suffix, as task 164 suffixed its second machine's files. The second run's carry
the same names with `-repeat` added.

**The ladder**, nanoseconds a row by executor time, from the first run. Every column reads Parquet
except the last, Varka over its Arrow cache from the committed 9V45 ladder
(`VarkaSizeLadderBenchmark-jdk25-runner`). Stock 4.2.0 is its JDK 25 arm, and "fallback" is the
fork with Varka on, whose every batch falls back to the row path.

| entries | vecruntime | stock 4.1.3 | stock 4.2.0 | Varka off | fallback | Varka |
|---:|---:|---:|---:|---:|---:|---:|
| 16 | 857.0 | 293.5 | 306.0 | 300.5 | 332.5 | 28.3 |
| 32 | 1667.0 | 601.0 | 622.0 | 620.0 | 714.0 | 43.7 |
| 48 | 2510.5 | 866.5 | 920.5 | 924.5 | 1111.0 | 57.7 |
| 52 | 2736.0 | 4707.0 | 4929.0 | 1000.5 | 1066.0 | 55.9 |
| 54 | 2864.5 | 4879.0 | 5110.0 | 5059.0 | 1200.0 | 59.1 |
| 56 | 3007.5 | 5091.5 | 5347.5 | 5295.5 | 1620.0 | 59.8 |
| 64 | 3516.5 | 5865.5 | 5966.5 | 6134.5 | 1828.5 | 64.8 |
| 80 | 4383.0 | 7617.0 | 7558.0 | 7722.0 | 2215.0 | 72.1 |
| 100 | 5748.0 | 9509.0 | 9547.0 | 9425.0 | 2434.5 | 97.1 |

**The predictions of 4.1, scored.**

1. **Held.** Every rung of both runs planned `VectorProject`, with no row-engine node above it.
2. **Held.** At 100 entries vecruntime reads 5748.0 against 5356 for the sixteen-entry time
   scaled, 7% over where 20% was allowed, and 5% over in the repeat. Its slope rises rather than
   steps, about 52 ns an entry from 16 to 48 entries and 62 from 48 to 100, where both stock arms
   and the fork with Varka off grow more than five times between two adjacent rungs.
3. **Held.** Below the cliff vecruntime is 2.9, 2.8 and 2.9 times slower than stock 4.1.3 at 16,
   32 and 48 entries; at 100 it is 1.65 times faster, 1.71 in the repeat. The two cross only at
   stock's step, between 48 and 52 entries: below it stock grows 18 ns an entry against
   vecruntime's 52, so without the step they would not meet.
4. **Held, three times over.** Varka over its cache is 30 times faster than vecruntime at 16
   entries, 48 times at 54 and 59 at 100, where the prediction's floor was ten.

**The repeat** reads within 3.3% of the first run at every rung, on both the vecruntime and the
4.1.3 arm. Section 3.7's four numbers come to 56, 2.9, 1.7 and 57 from it, against 58, 2.9, 1.7
and 59 from the first run, which is the post's source.

**The input.** The fork with Varka off reads Parquet within 15% of its committed 9V45 ladder over
the cache at every rung of both runs, and steps where that ladder does, between 52 and 54 entries.
Both stock releases step one rung earlier, between 48 and 52, as on both of task 194's inputs, so
the rung is the engine's and not the input's. A difference that size cannot move a ratio of
fifty-nine.

**What Spark's own routes around the cliff add.** The fallback evaluates each row through
`UnsafeProjection`, whose generated code gives each expression its own method, so it has no step
either (`PLAN_TASK_194.md` 7). It runs the ladder 1.7 to 2.6 times faster than vecruntime at every
rung of both runs. Below the step it costs 11% to 20% over the fork with Varka off, the price of
per-expression methods on this machine: task 192's `wholeStage=false` arm costs 12% to 17% over
the defaults at the same rungs (`VarkaSizeLadderTuningBenchmark-jdk25-runner`). The same file's
`hugeMethodLimit=8000` arm, the setting that removes whole-stage code's step, is 2.5 to 3.0 times
faster than vecruntime at every rung, over its cache. So vecruntime beats Spark only under Spark's
defaults, and 3.7 says so in a clause that names the setting (`PLAN_TASK_181.md` 13).

**The chains** were not dispatched. The admission check found the plugin converting none of the
twelve (section 2), and the driver would fail the arm on the first; that finding is the result for
the chains, as risk 3 says.
