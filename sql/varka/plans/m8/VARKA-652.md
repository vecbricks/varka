# VARKA-652: rerun `VarkaCountBenchmark` after the fused count, for the post's figure

*Planned on 28 November 2026; sections 2 to 5 changed in fix round 2 the same day, after
review 1 and the owner's decision that the fused count's default flips in this milestone. This
plan stands alone. Not started, and it cannot start before row 650's part 650a and row 651's
parts 1, 1b and 2 have merged. The facts were checked on master `6dafb8bd2f6`. Every section is
new on this day: the row had no plan file before.*

## 1. What this row is

**The row's text** (`sql/varka/plans/m8/PLAN.md`, section 3, row 652): rerun
`VarkaCountBenchmark` on an AMD EPYC 7763 after row 651 and commit the results, the before and
after figure for the milestone's post. **Done when** the results file holds the run after row
651 beside row 650's, from the same runner model, and the post's figure reads from it.

It is a dispatch and a results pull request. It changes no code. It gates the close under
items 7 and 8 of the done-when list (`m8/PLAN.md` 1.1 and 12.5), after rows 650 and 651 and
before the post (row 355).

## 2. What an agent must know

- `VarkaCountBenchmark` (row 650) has three sections: `pi: 4 * count(*) / n where x * x + y * y
  < 1`, `COUNT(*) over the filter, 15% selected` and `COUNT(*) over the filter, 85% selected`.
  Row 650 committed its baseline with two arms a section, `baseline (Janino)` and `varka
  (SIMD)`, as four runner files under `sql/core/benchmarks/`:
  `VarkaCountBenchmark-jdk25-runner-epyc7763-results.txt` and `-provenance.txt`, and the same
  two with `-128bit` (the run under `-XX:MaxVectorSize=16`).
- Row 651 added a switch, `spark.sql.codegen.varka.fusedCount.enabled`, under which an
  ungrouped `count(*)` over a fused filter takes its count from the filter's selection bitmap;
  and a third arm to each section, `varka (SIMD), fused count`. The switch is off by default
  until row 651's part 3a flips it. Before the flip the third arm sets the switch; after it the
  second arm pins the switch off and the third sets nothing. Either way one run holds before
  (`varka (SIMD)`, the plan row 650 measured) and after (the third arm) from one machine, under
  the same arm names, so this row may run before or after part 3a.
- The benchmark's tables and the surface's table have one partition. There Spark merges the
  count's two aggregates into one, and the fused count runs in its `Complete` mode, which row
  651's part 1b built. No setting besides the switch is needed for it: Spark's
  `spark.sql.execution.combineAdjacentAggregation` stays at its default.
- At 128 bits on x86 the pi filter is left to Spark (the two-lane refusal), so that section
  has no third arm there and its second arm reads `varka (filter left to Spark)`.
- If row 650's part 650b was built, the surface driver has a `count` benchmark that times the
  pi predicate's counted filter on stock Spark 4.3.0 and on this fork, and four files
  `sql/varka/bench/benchmarks/CountSurface-<label>-zen3-results.txt` hold its baseline.
- A benchmark is never timed on the laptop for a committed file. The runs are dispatches of
  `benchmark.yml` (and of `varka-surface-benchmark.yml`); a run that lands on another CPU fails
  its CPU check and is retried hourly, up to six attempts
  (`sql/varka/skills/benchmarking.md`, "Start here", point 2).

## 3. The admission check, at the time the row starts

    grep -c 'varka (SIMD), fused count' sql/core/src/test/java/org/apache/spark/sql/execution/benchmark/VarkaCountBenchmark.java   # 1
    git ls-files 'sql/core/benchmarks/VarkaCountBenchmark-jdk25-runner-epyc7763*' | wc -l                                         # 4
    grep -c 'cpu:.*AMD EPYC 7763' sql/core/benchmarks/VarkaCountBenchmark-jdk25-runner-epyc7763-provenance.txt                    # 1
    grep -c 'VARKA_FUSED_COUNT_ENABLED' sql/catalyst/src/main/scala/org/apache/spark/sql/internal/SQLConf.scala                   # 2
    git grep -c 'private def isCompleteCountStar' -- sql/core/src/main                                                            # 1 file, count 1
    grep -c 'RESULT_PROJECT' sql/varka/bench/src/main/java/org/apache/spark/sql/varka/bench/DateSurfaceBenchmark.java             # 2 or more
    git ls-files 'sql/varka/bench/benchmarks/CountSurface-*' | wc -l                                                               # 4 if 650b was built, else 0

If any of the first six prints another number, row 650's part 650a and row 651's parts 1, 1b
and 2 are not all merged: stop.
On 28 November 2026 none of this exists (`git grep -l VarkaCountBenchmark -- sql/core` prints
nothing), which is why the row is not started.

## 4. The steps

One pull request: `[VARKA-652] Record the fused count's runs on an EPYC 7763`. Branch
`varka-652a-count-results`, from `origin/master`. Builder: Sonnet-class.

1. **Dispatch the two runs of the class**, on master, which holds the third arm:

        gh workflow run benchmark.yml -R vecbricks/varka --ref master \
          -f class=VarkaCountBenchmark -f expected-cpu="AMD EPYC 7763"
        gh workflow run benchmark.yml -R vecbricks/varka --ref master \
          -f class=VarkaCountBenchmark -f expected-cpu="AMD EPYC 7763" \
          -f extra-java-options="-XX:MaxVectorSize=16"

   Note each run's id (`gh run list -R vecbricks/varka --workflow benchmark.yml --limit 4`).
   No `sections` input: the whole class is rerun. After "gave up: attempt 6 of 6 is the cap",
   dispatch again.
2. **If the `CountSurface` files exist, dispatch the surface once more**, with the switch on
   every arm (a stock release ignores a key it does not know) and a suffix of its own, so the
   baseline files stay:

        gh workflow run varka-surface-benchmark.yml -R vecbricks/varka --ref master \
          -f stop-after=run -f require-datapath=any -f expected-cpu="AMD EPYC 7763" \
          -f benchmark=count -f rows=100000000 -f label-suffix=-zen3-fused-count \
          -f extra-confs=spark.sql.codegen.varka.fusedCount.enabled=true

   The switch is the only conf passed. The surface's table is one partition: the driver's
   `--partitions` defaults to 1 (`DateSurfaceBenchmark`, the field `partitions` of its
   arguments; every table is built over `range(0, rows, 1, partitions)`), and
   `dev/varka_bench_surface.sh` submits it on `local[1]` with
   `spark.sql.shuffle.partitions=1` and passes no other partition count. So the count there is
   the merged aggregate's, which row 651's part 1b serves, and the driver's plan check reads
   it as fused since the same part. Leave the `extra-confs` line out if the default has
   flipped by then, that is if this prints 1:

        grep -A14 'val VARKA_FUSED_COUNT_ENABLED' sql/catalyst/src/main/scala/org/apache/spark/sql/internal/SQLConf.scala | grep -c 'createWithDefault(true)'

   After the run, the Varka arm's file must say the entry ran fused for the shape "filter,
   counted"; if it says partly fused, stop and report the plan the log prints.

3. **Download and place the files.** `gh run download <run-id> -R vecbricks/varka`, untar.
   - Row 650's four runner files stay as they are. The new run is added beside them under the
     suffix `-fused-count`, so that both runs are readable without git (the owner's answer of
     28 November 2026, section 5): the 256-bit run as
     `sql/core/benchmarks/VarkaCountBenchmark-jdk25-runner-epyc7763-fused-count-results.txt` and
     `...-fused-count-provenance.txt`, the 128-bit run as `...-fused-count-128bit-results.txt` and
     `...-fused-count-128bit-provenance.txt` (same directory, same prefix). The artifact writes the
     plain names (`VarkaCountBenchmark-jdk25-results.txt`); copy each to the new name of its
     width. Do not add the plain-named files.
   - The surface run adds four files under `sql/varka/bench/benchmarks/`:
     `CountSurface-spark-4.3.0-jdk17-zen3-fused-count-results.txt`,
     `CountSurface-spark-4.3.0-jdk25-zen3-fused-count-results.txt`,
     `CountSurface-varka-off-jdk25-zen3-fused-count-results.txt` and
     `CountSurface-varka-jdk25-zen3-fused-count-results.txt`.
4. **Check what the files are.**

        grep -c 'cpu:.*AMD EPYC 7763' sql/core/benchmarks/VarkaCountBenchmark-jdk25-runner-epyc7763-fused-count-provenance.txt          # 1
        grep -c 'cpu:.*AMD EPYC 7763' sql/core/benchmarks/VarkaCountBenchmark-jdk25-runner-epyc7763-fused-count-128bit-provenance.txt   # 1
        grep -c 'varka (SIMD), fused count' sql/core/benchmarks/VarkaCountBenchmark-jdk25-runner-epyc7763-fused-count-results.txt       # 3
        grep -c 'varka (SIMD), fused count' sql/core/benchmarks/VarkaCountBenchmark-jdk25-runner-epyc7763-fused-count-128bit-results.txt   # 2
        grep -c 'varka (filter left to Spark)' sql/core/benchmarks/VarkaCountBenchmark-jdk25-runner-epyc7763-fused-count-128bit-results.txt   # 1

5. **Read the controls before the result.** The two arms row 650 measured are in the new file
   too. Against row 650's run:

        python3 dev/varka_bench_diff.py \
          sql/core/benchmarks/VarkaCountBenchmark-jdk25-runner-epyc7763-results.txt \
          sql/core/benchmarks/VarkaCountBenchmark-jdk25-runner-epyc7763-fused-count-results.txt

   The `baseline (Janino)` and `varka (SIMD)` rows are the controls: the code under them did
   not change. Two runs on this CPU differ by their own noise ("A benchmark number is
   reproducible within a run and not between runs", `sql/varka/skills/benchmarking.md`). If a
   control moved by more than 10%, say so in the pull request and dispatch step 1's first run
   once more before reading the third arm; commit the run whose controls sit nearer row 650's.
   Then the before and after inside the one file:

        python3 dev/varka_bench_diff.py \
          --within sql/core/benchmarks/VarkaCountBenchmark-jdk25-runner-epyc7763-fused-count-results.txt \
          --ab "varka (SIMD)" "varka (SIMD), fused count"

6. **Score row 651's four predictions** (`VARKA-651.md` 7) from the 256-bit file, each yes or
   no with the two numbers it rests on, in that file's section 11. A ratio under 1.3X that the
   post is to quote needs a second run compared by `Best Time` (`sql/varka/AGENTS.md`,
   "Measurements, not adjectives"): dispatch step 1's first run again and quote the smaller.
7. **The plan.** In `m8/PLAN.md`, row 652: `**Done** (<date>: runs <id> at 256 bits and <id> at
   128 bits on an AMD EPYC 7763, master <sha>; ...)`, with, for each of the three sections,
   the `Relative` of `varka (SIMD)` and of `varka (SIMD), fused count` from the 256-bit file,
   and the sentence that names the figure's source for row 355: the three sections of
   `VarkaCountBenchmark-jdk25-runner-epyc7763-fused-count-results.txt`, three arms each, and,
   where the surface was run, the "filter, counted" row of the four `-zen3-fused-count` files
   for the bar against stock Spark 4.3.0. Fill this file's section 6.
8. **The checks, then the closing flow** (`sql/varka/ADDING_AN_EXPRESSION.md`, "Closing a row").

        python3 dev/varka_quote_check.py sql/varka/plans/m8/PLAN.md sql/varka/plans/m8/VARKA-651.md   # 0 orphans
        python3 dev/varka_plan_lint.py --offline                                                     # 0 finding(s)

   No requote step: the run adds files and replaces none, so no number a document quotes moves.

**Done when** four new runner files with the suffix `-fused-count` hold the runs after row
651 beside row 650's four, each from an EPYC 7763, the
256-bit file has three arms in each section, and row 652's note names the figure's source.
**Not here:** no code; no change of the switch's default; no run on the laptop; no edit of row
650's note beyond what the requote asks.

## 5. Questions for the owner

**Answered by the owner on 28 November 2026.**

1. **Where the new run goes.** The answer: "should be readable w/o git". So row 650's four
   runner files stay, and the new run is added beside them under the suffix `-fused-count`
   (step 3); the before and after of the post's figure are read from two files of one machine
   model, and the third arm's before and after from the new file alone. The options were (a)
   replace row 650's files, the baseline then only in git, or (b) new files beside them; (b) is
   what is built.
Decided in the text, no longer asked: the figure's source is the files step 7 names; the
figure itself is row 355's work.

## 6. Outcome

To be written when the row closes: the runs, the files, the controls' moves, row 651's
predictions scored.
