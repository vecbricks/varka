# VARKA-571: a Varka benchmark dispatch neither waits for nor fails on TPC-DS data

## 1. Where this came from

Row 571 of `PLAN.md`, found by the dispatches of the night of 4 November 2026. Every
`benchmark.yml` dispatch of a Varka class failed in "Generate an TPC-DS dataset" at "Build
tpcds-kit". `generate-tpcds` defaults to true, and the kit did not build with the runner image's
compiler. SPARK-60478 fixes that failure, and it was due with the next apache sync. The
workaround was `-f generate-tpcds=false`.

## 2. The admission check, done

**The failure no longer holds.** SPARK-60478 (`668fdddb148`) builds tpcds-kit with
`-std=gnu89`. It reached Varka's master with the apache sync merged as `b90e6799220` (#1427) on 9
November 2026, and `benchmark.yml` on master carries it ("Build tpcds-kit"). Three dispatches of a
Varka class since then left `generate-tpcds` at true: runs 42794013318 and 42794019027 on 10
November and 43183588553 on 12 November. In each, the data job and the benchmark job succeeded
(`results/VARKA-571-dispatches.txt`). So a dispatch with no flags runs, which is the done-when's
third condition, and it holds now without a change.

**The cost still holds.** `generate-tpcds` still defaults to true. The benchmark job `needs` the
data job, so a Varka dispatch with the default waits for the data before its first benchmark: 8.6,
10.6 and 13.1 minutes in those three runs, and one more runner for that time. No Varka benchmark
reads the data. No `Varka*Benchmark` class reads `SPARK_TPCDS_DATA`. The two that name TPC-DS
(`VarkaRangeFilterBenchmark`, `VarkaColdStartBenchmark`) build `modified-q3`'s filter over a
synthetic column. `sql/varka/skills/benchmarking.md` mentions `generate-tpcds` only for Arm. The
done-when's first two conditions, skipping by default and the skill saying so, do not hold.

What the check would have rejected: a plan to work around a build failure that no longer
happens. What is left is a wait of about ten minutes per dispatch, not a failure. Question 1
asks whether that is worth a change.

## 3. The design

### 3.1 The data job runs only for a class that can read it

`matrix-gen` already parses the class list. It gains an output, `tpcds`, which is `false` when
every class given names a Varka class: after a glob's `{a,b}` is expanded into its
alternatives, each bare name or last segment starts with `Varka`, as in
`VarkaDecimalBaselineBenchmark`, `*.VarkaDecimalBaselineBenchmark` or
`{VarkaA,VarkaB}Benchmark`. It is `true`
otherwise, so `*` and every Spark class keep upstream's behaviour. The data job's condition
becomes `inputs.generate-tpcds && !inputs.pair && needs.matrix-gen.outputs.tpcds == 'true'`
(the job gains `needs: matrix-gen`), and the benchmark job's cache step reads the same output.
`generate-tpcds=false` still turns the data off for any class. The input's description says that
a Varka-only list skips the data.

Alternatives considered: flip the input's default to false (rejected: a Spark class that needs
the data would then fail unless the person dispatching it knew the flag, and upstream's file
keeps the default); and leave the file alone and document the flag (rejected: the flag is the
step everyone forgets, which is how the night of 4 November went).

### 3.2 What is deliberately unchanged

The data job itself and its cache key. Pair mode, which already skips the data. The Arm note in
`benchmarking.md`, which stays true.

## 4. Files

| file | what |
|---|---|
| `.github/workflows/benchmark.yml` | the `tpcds` output, two conditions, the description |
| `sql/varka/skills/benchmarking.md` | one paragraph: a Varka-only dispatch skips the data |

## 5. Tests, and what each is for

* `actionlint .github/workflows/benchmark.yml`.
* The matrix step's Python, run locally over the class lists `VarkaDecimalBaselineBenchmark`,
  `*.VarkaDecimalBaselineBenchmark`, `{VarkaA,VarkaB}Benchmark`, `*`, `TPCDSQueryBenchmark` and
  a mix of a Varka and a Spark class: `false`, `false`, `false`, `true`, `true`, `true`. This
  catches a matcher that sends a Spark class to a job with no data.

## 6. The measurement

### 6.1 Predictions, registered before the run

1. A dispatch of one Varka class with no flags shows "Generate an TPC-DS dataset with SF=1" as
   skipped, and its benchmark job starts within 2 minutes of `matrix-gen`. The three runs of
   section 2 started it 8.6 to 13.1 minutes later.

## 7. Risks

1. A Varka class added later that does read TPC-DS data would run without it and fail at its
   first read. The `benchmarking.md` paragraph names the rule, and such a class can be dispatched
   with a Spark class beside it, or the matcher can learn its name.

## 8. Sequencing

One pull request of about 30 counted lines.

1. The output, the conditions and the description. Done when `actionlint` passes and the local
   run of section 5 prints the six expected answers.
2. The skill paragraph. Done when `python3 dev/varka_toc.py SKILLS.md --from sql/varka/skills
   --check` passes.
3. On the owner's word, one dispatch of `VarkaDecimalBaselineBenchmark` with no flags. Done when
   prediction 1 is scored.

## 9. Questions for the owner

1. **Narrow the row or close it?** The failure the row was written for is gone (section 2), and
   so is the done-when's "a dispatch with no flags runs". What is left is about ten minutes and
   one runner per Varka dispatch. Recommended: narrow the row to the skip of 3.1 and keep it
   small, because benchmarks are dispatched several times a week, and a wait that has to be
   remembered away is the trap that caught the night of 4 November. Closing it is equally
   defensible, since nothing fails now. This is the owner's call because it is about what the row
   is for, not about the facts. The plan is built on narrowing.

## 10. Done when

A dispatch of a Varka class skips the TPC-DS generation by default, `benchmarking.md` says so,
and a dispatch with no flags runs, with the third condition already met on master since
`b90e6799220`.

## 11. What was built (20 November 2026)

Steps 1 and 2 of section 8, in one branch, `varka-571-bench-no-tpcds`.

* **Step 1.** `matrix-gen` writes a fourth output, `tpcds`. Its matcher expands each `{a,b}`
  (innermost first, so nested braces expand too) and takes the last `.`-segment of every
  alternative; the output is `false` only when each of them starts with `Varka`. The data job
  gains `needs: matrix-gen` and the condition of 3.1, the benchmark job's cache step reads the same
  output, and the `generate-tpcds` description says a Varka-only list skips the data. `actionlint`
  reports no error of its own; its 20 shellcheck notes are the same 20 that master's file gets,
  all in the unchanged `run` blocks (legacy backticks, unquoted `$(...)`). The step's Python, taken
  from the workflow file and run locally, gives the six answers of section 5 and four more:
  `{VarkaA,TPCDSQuery}Benchmark` and `*Varka*` give `true`, a package glob ending in `.Varka*` and
  the space-separated `VarkaA VarkaB` give `false`.
* **Step 2.** `benchmarking.md` gains a section, "A dispatch of Varka classes only skips the
  TPC-DS data job", and `SKILLS.md` its entry; `dev/varka_toc.py --check` passes. The Arm section
  is unchanged and still true: setting `generate-tpcds` to false there is now redundant for a
  Varka class and still needed for a Spark one.
* **Step 3 is owed**: the dispatch waits for the owner's word, so prediction 1 is unscored.

**Where the plan was silent.** The data's cache key hashes `benchmark.yml` itself, so this change,
like any edit of the file, misses the cache once: the first dispatch after the merge that keeps the
data (a Spark class, or `*`) builds tpcds-kit and generates the data again. That costs one run's
wait and nothing else, and a Varka-only dispatch never looks the key up.

## 12. Decisions taken while the owner was away (20 November 2026)

1. **Question 1, narrow or close: narrowed**, as section 9 recommends and the plan is built on.
   The reason is the plan's: benchmarks are dispatched several times a week, and a wait that has
   to be remembered away is the trap of 4 November. The change is about 25 counted lines, so if
   the owner would rather close the row, the branch is dropped without cost to anything else.
2. **The skill paragraph is a section of its own**, not a sentence in the dispatch bullet of
   "Regenerate the section you changed": a reader asking why a dispatch had no TPC-DS data finds it in the
   `SKILLS.md` index by its title, which is what the plan's done-when for step 2 checks.
