# VARKA-553: The date and TIME surfaces regenerated on runners against stock 4.3.0

## 0. Where this came from

Filed on 2 November 2026 by the lead's decision on row 552's plan (`VARKA-552.md` 7, decision 1).
Row 552 moves Varka's comparisons with stock Spark from 4.2.0 to 4.3.0, the release of 30 October
2026, but leaves out the README's two surface tables, the date surface and the `TIME` surface:
their files are laptop runs at a billion and half a billion rows, and the README says why a runner
cannot take those row counts. How they are regenerated was settled before this row: scope item 110
(`m9/SCOPE.md`, the owner's word of 18 October 2026) makes surface regeneration a row of its own,
run on the runner surface workflow at the row counts a runner's driver holds, with the laptop's
billion-row files kept as the appendix that proves the fixed-share bound. This is that row. It
also takes the README's `-ymm` and `abs(ymm)` recheck, which reads against the date surface:
`DateSurface-spark-4.2.0-jdk25-ymm-recheck-results.txt`.

## 1. Goal and non-goal

**Goal.** The README's two surface tables quote runner files whose stock arms are Spark 4.3.0,
with their date beside each span, and cite the laptop files as the appendix.

**Non-goal.** Re-running the laptop's billion-row files, or retiring them: they stay as committed.
The chains are row 552's.

## 2. The steps

1. Waits for row 552's step 1, which sets `STOCK_VERSION: 4.3.0` in
   `varka-surface-benchmark.yml`.
2. `benchmark=surface` and `benchmark=time`, every arm in one run, at the row count the workflow
   measures resident, sharded with the workflow's `shard` input so each job fits, and merged with
   `dev/varka_bench_merge.py`; results committed by hand after their provenance is read, as new
   `DateSurface-spark-4.3.0-*` and `TimeSurface-spark-4.3.0-*` files with the Varka and engine-off
   files of the same runs. The 4.2.0 files stay as the record, as row 552 keeps its own.
   *CI:* one `stop-after=build` dispatch, then one `run` dispatch per shard and surface, each the
   owner's, waiting for any running option matrix.
3. The README's two tables, the recheck paragraph and the paragraphs read from the surfaces
   requoted from the new files, the laptop files cited as the appendix;
   `dev/varka_bench_diff.py --requote` lists the moved lines and `dev/varka_quote_check.py` holds
   every figure to a committed file.
   *CI:* the push starts the Build, whose docs job runs the quote check.

## 3. Done when

- both surfaces' 4.3.0 stock files are committed with the Varka and engine-off files of the same
  runs;
- the README's surface tables quote them with their date and cite the laptop files as the
  appendix, and `git grep -n '4\.2' README.md` shows no surface line;
- `dev/varka_quote_check.py` passes.

## 4. Outcome

Not started.
