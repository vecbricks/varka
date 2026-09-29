# Task 194: is the ladder fair to stock Spark's input path?

## 1. The question

The size ladder (task 171) publishes vanilla Spark against Varka over the fork's
Arrow cache, through the fork's codegen. A stock user runs neither. Stock Spark
4.2.0 crosses HotSpot's 8000-byte limit between 48 and 52 entries where the fork
crosses between 52 and 54 (`PLAN_TASK_192.md` 9.2), so the fork's vanilla arm is
kinder to vanilla than the download is; and Spark's default cache is its own
columnar format, not Arrow, while a query over a file reads Parquet. The cliff
is in the projection and should not move with the input; the absolute numbers
and the ratio may. Milestone 6 row 194, research, optional. The runner is the
instrument, as for every ladder figure.

## 2. The change

The ladder through the surface's stock-distribution driver
(`sql/varka/bench`, task 62), which already runs one benchmark on stock 4.2.0
under two JDKs, on the fork with Varka off and on the fork with Varka on, over
each distribution's own default cache - Arrow only on the Varka arm - and
writes one results file per arm with the CPU and the JDK in its provenance.

* `LadderBenchmark`: the ladder's rungs as entries. A rung is one entry whose
  projection lists the rung's `greatest(add_months(d, k), date_add(d, k),
  last_day(d))` entries, the last unaliased so the driver's `AS a` and its
  checksum fall on it; `varka_dates`'s `d` is generated as the ladder's date
  column is. Nine rungs, 16 to 100. The rungs are written out in the module,
  which runs on distributions that do not carry the fork's test classes.
* `--input parquet` in the driver: the table written once to a Parquet file
  and read back uncached, the path a query over a file takes, in place of the
  cache; the file name gains `-parquet`. `--benchmark ladder` in the shell
  driver and the workflow, and an `input` choice in the workflow.
* The rows are the ladder's two million, not the surface's hundred million:
  vanilla's interpreted arm takes eighteen seconds an iteration at a hundred
  entries over two million rows on the 9V45. The fixed-share rule, made for
  rows that run in milliseconds at a hundred million rows, is lifted for the
  dispatch (`max-fixed-share: 100`), and the provenance's `methodology` line
  says what was run.

Two dispatches of `varka-surface-benchmark.yml`, `benchmark: ladder`, `rows:
2000000`, `require-datapath: any` - the question is the input path, answered by
arms of one file against each other, not the datapath - one with `input: cache`
and one with `input: parquet`. The files: `sql/varka/bench/benchmarks/VarkaLadder-
{spark-4.2.0-jdk17,spark-4.2.0-jdk25,varka-off-jdk25,varka-jdk25}[-parquet]-results.txt`.

## 3. Predictions, registered before the run

1. **Stock crosses earlier.** Stock 4.2.0's 52-entry rung is several times its
   48-entry rung, on both JDKs, where the fork's vanilla arm (`varka-off`) at 52
   is within 20% of its 48: stock's consume method is 8677 bytes at 52, the
   fork's 7868.
2. **Below the cliff the vanilla arms agree.** At 16 to 48 entries stock 4.2.0 on
   JDK 25 and the fork with Varka off, both over the default cache, are within
   20% of each other per rung; the same codegen family reads the same cache.
3. **Above the cliff every interpreted arm agrees.** From 54 entries on, stock on
   either JDK and the fork's vanilla are within 20% of each other: an
   interpreter is an interpreter, and the JDK changes little of it.
4. **The default cache costs vanilla little against Arrow.** The fork's vanilla
   over the default cache is within 20% per rung of the fork's ladder over Arrow
   (`VarkaSizeLadderBenchmark`'s file on the same class of machine): the vanilla
   arm turns columnar batches into rows either way.
5. **Parquet adds a scan, not a cliff.** Under `--input parquet` every vanilla
   arm is slower than over its cache by the scan's cost, under 20% at 16 entries
   and under 5% from 48 on, and the cliff stays between 48 and 52 for stock and
   between 52 and 54 for the fork.
6. **Varka over Parquet is not Varka.** The columnar rule plans its projection
   over any columnar child and decides per batch; a Parquet scan's on-heap
   vectors fail that check on every batch, so the `varka-jdk25` arm under
   `--input parquet` plans a Varka node, serves no batch from the kernel, runs
   the per-row fallback and the driver reports the violation. Its rows read as
   the fallback's cost: slower than the fork's vanilla below the cliff, level
   with it above. That is the columnar datasource's item, not this task's.
7. **The ratio a stock user sees is the ladder's.** At a hundred entries over the
   cache, stock 4.2.0 against Varka on Arrow is within 20% of the fork's own
   ladder ratio on the same class of machine, since stock's interpreted arm
   costs what the fork's does.

**What the answer decides.** The post's ratio is quoted against stock 4.2.0 over
its default cache, the path a reader runs, and names stock's crossing; if
prediction 2 or 4 fails by more than the 20%, the ladder's vanilla arm is not a
fair stand-in for stock and the post says which arm it quotes.

## 4. Verification

* `LadderTest`: every rung runs on stock Spark with its column count and the
  driver's `a` last, plans without a Varka node, and the rungs are the ladder's.
* The bench module's tests pass; `bash -n` on the script; a smoke of the driver
  with `--input parquet` at a thousand rows on the laptop.
* Each committed file's provenance carries `benchmark: ladder`, `input`, the
  rows and the CPU.

## 5. Files

| file | what |
|---|---|
| `sql/varka/bench/.../LadderBenchmark.java` (new), `LadderTest.java` (new) | the benchmark and its test |
| `DateSurfaceBenchmark.java` | `--input parquet`: the table as a Parquet file, the provenance line |
| `dev/varka_bench_surface.sh`, `.github/workflows/varka-surface-benchmark.yml` | `--benchmark ladder`, `--input` |
| `sql/varka/bench/benchmarks/VarkaLadder-*-results.txt` | the two dispatches' files |
| `PLAN_MILESTONE_6.md` | row 194 |

## 6. Explicitly out of this task

A stock arm inside the fork's own ladder class, which cannot run another
distribution's jars; a Parquet arm for Varka, which is the columnar datasource
of the roadmap; ORC; a laptop run, since the runner is the ladder's instrument.

## 7. Outcome, 29 September 2026

Two dispatches on `vecbricks/varka` after the merge, runs 36523439147 (`input: cache`) and
36523441165 (`input: parquet`), both on AMD EPYC 7763 runners with four vCPUs on `local[1]`,
the same class of machine as task 197's dispatch of the same morning
(`VarkaSizeLadderBenchmark-jdk25-runner-7763-results.txt`, the fork's vanilla over Arrow, read
beside them). The eight files are `sql/varka/bench/benchmarks/VarkaLadder-*-results.txt`.
Per row, nanoseconds, best iteration, over each distribution's default cache:

| entries | stock 4.2.0, JDK 17 | stock 4.2.0, JDK 25 | fork, Varka off | fork over Arrow (task 197's file) | Varka |
|---:|---:|---:|---:|---:|---:|
| 16 | 549 | 557 | 520 | 552 | 84 |
| 32 | 1043 | 1083 | 1001 | 1076 | 124 |
| 48 | 1553 | 1585 | 1465 | 1609 | 169 |
| 52 | 8558 | 8722 | 1577 | 1749 | 183 |
| 54 | 8982 | 9240 | 8976 | 9096 | 174 |
| 56 | 9468 | 9576 | 9234 | 9709 | 177 |
| 64 | 11003 | 11198 | 10864 | 11263 | 218 |
| 80 | 14442 | 13972 | 13679 | 14110 | 278 |
| 100 | 18278 | 17851 | 17418 | 17714 | 324 |

And over a Parquet file, read uncached:

| entries | stock 4.2.0, JDK 25 | fork, Varka off | fork, Varka on: the row fallback |
|---:|---:|---:|---:|
| 16 | 565 | 568 | 601 |
| 48 | 1579 | 1632 | 1662 |
| 52 | 8767 | 1740 | 1779 |
| 54 | 9195 | 9333 | 1861 |
| 100 | 17962 | 17850 | 3595 |

The predictions of section 3, scored:

1. **Held.** Stock's 52-entry rung is 5.5 times its 48 on both JDKs; the fork's is 8% over its
   48. Stock crosses between 48 and 52, the fork between 52 and 54.
2. **Held.** Below the cliff stock on JDK 25 and the fork with Varka off, both over the default
   cache, are 7% to 8% apart per rung, the fork the faster.
3. **Held.** From 54 entries on the three interpreted arms are within 5% of each other at every
   rung; the JDK moves stock by under 3%.
4. **Held.** The fork's vanilla over the default cache is 6% to 10% faster than over Arrow at
   every rung, from a run of the same morning on the same class of machine, inside the 20%.
5. **Held for stock, missed for the fork.** Stock over Parquet is within 2% of stock over its
   cache at every rung: at two million rows of one column the vectorized read costs nothing
   the table can see. The fork's vanilla over Parquet is 9% to 11% over its default cache from
   16 to 52 entries, past the 5% allowed from 48 on, and level with stock over Parquet; the
   fork's default cache is the faster path, by the same 7% to 10% it beats stock by. The cliffs
   stay where they were, on both inputs.
6. **Held in mechanism, failed in the size.** The Varka arm over Parquet plans a Varka node,
   the kernel serves no batch (every batch falls back, 2934 to 3423 a rung), the driver reports
   the violation and the run fails on it, as predicted. Below the cliff the fallback is 2% to
   6% slower than the fork's vanilla, as predicted. Past the cliff it is not level with vanilla
   but five times faster: 1861 against 9333 ns at 54 entries, 3595 against 17850 at a hundred.
   The fallback evaluates the projection through `UnsafeProjection`, whose generated code
   splits each expression into its own method, so no method of it crosses 8000 bytes; whole-
   stage codegen puts the projection into one consume method and does. So the fork's row
   fallback, on an input Varka does not read, already stands clear of the cliff the post is
   about, by the mechanism SPARK-33301 brings to whole-stage codegen for one expression.
7. **Held.** Stock 4.2.0 on JDK 25 against Varka at a hundred entries is 55 times; the fork's
   own ladder on the same class of machine that morning is 59 (17714 against 301), 7% apart.

**What the answer decides.** The post's ratio can be quoted against stock 4.2.0 over its default
cache, the path a reader runs, at fifty-five times on a 7763 and naming stock's crossing
between 48 and 52 entries; the fork's vanilla arm is a fair stand-in, 7% to 10% kinder to
vanilla than stock is. The Parquet input changes nothing a reader would notice for vanilla.
The finding of prediction 6 is a sentence for the post and a note for the columnar
datasource item: on an input the kernels do not read, the operator's row fallback stays off
the cliff.
