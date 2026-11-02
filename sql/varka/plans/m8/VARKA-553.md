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
2. `benchmark=surface` and `benchmark=time`, every arm in one run, sharded with the workflow's
   `shard` input so each job fits, and merged with `dev/varka_bench_merge.py`; results committed
   by hand after their provenance is read, as new `DateSurface-spark-4.3.0-*` and
   `TimeSurface-spark-4.3.0-*` files with the Varka and engine-off files of the same runs. The
   4.2.0 files stay as the record, as row 552 keeps its own. The inputs, the same on every shard:
   - `rows=100000000`, the only count the workflow's `rows` note calls resident on a runner, and
     `driver-memory=6g`, the workflow's default (scope item 110's "7 GB driver" is the runner's
     limit, not an input);
   - `max-fixed-share=45`. The README's methodology (lines 245-258) says the surface's lightest
     entries, about 0.5 ns/row, cannot clear the standing 5% bound on a runner below about 1.4e9
     rows: at 1e8 rows a 36 ms job constant against about 50 ms of work is a fixed share of about
     42% (`(wall - executor) / wall`, `DateSurfaceBenchmark`), and the laptop's worst row, 5.6% of
     a 28 ms constant at 1e9, backs out to the same rate. The value is **[I]** until the first
     shard's `# fixed share` lines; a row over it fails the shard, and the bound is then set from
     those lines and stated. `varka-surface-benchmark.yml`'s `max-fixed-share` description
     requires that wherever a lifted bound produced a committed number, the README methodology
     says so, which step 3 does;
   - `require-datapath=any` and `expected-cpu="AMD EPYC 7763"` on every shard, so the shards
     cannot mix CPU models, a miss retried hourly and never relaxed (the benchmark rule that runs
     are on an EPYC 7763). A 512-bit requirement would need a one-in-eighteen hit per shard. The
     7763 is a 256-bit machine without AVX-512: the README (lines 280-282) reads the `TIME`
     surface's division rows under `-XX:UseAVX=2` at 0.09 to 0.16 of their full-width rate, so
     the runner `TIME` surface is a 256-bit claim, and the laptop's 512-bit file stays its
     512-bit record.
   *CI:* one `stop-after=build` dispatch, then one `run` dispatch per shard and surface, each the
   owner's, waiting for any running option matrix.
3. The README's two tables, the recheck paragraph and the paragraphs read from the surfaces
   requoted from the new files, the machine and date beside each table, the laptop files cited
   as the appendix that clears the 5% bound and, for `TIME`, as the 512-bit record. With them:
   the "up to 39x" headline (lines 58-62) and the `dayofweek(d)` and `time_trunc('MILLISECOND',
   t2)` figures of lines 229-231; the `trunc(d, 'QUARTER')` paragraph (151-156), rewritten or
   removed by what the run shows, scoring row 552's P3 (`VARKA-552.md` 4: the two baselines now
   agree within 20%, since 4.3.0 has the fork's `truncDate`); and the methodology at 245-258,
   which today says the surface cannot be measured on a runner at all, rewritten to state the
   runner files' row count, their lifted bound and the worst fixed share each table shows;
   `dev/varka_bench_diff.py --requote` lists the moved lines and `dev/varka_quote_check.py` holds
   every figure to a committed file.
   *CI:* the push starts the Build, whose docs job runs the quote check.

## 3. Done when

- both surfaces' 4.3.0 stock files are committed with the Varka and engine-off files of the same
  runs;
- the README's surface tables quote them with their date and cite the laptop files as the
  appendix, and `git grep -n -E '4\.2\.0|stock 4\.2' README.md` shows no surface line;
- the README's methodology states the runner files' lifted fixed-share bound;
- row 552's P3 is scored in section 4;
- `dev/varka_quote_check.py` passes.

## 4. Outcome

Not started.
