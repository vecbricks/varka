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
   4.2.0 files stay as the record, as row 552 keeps its own. The surfaces run at both of the
   README's widths, as the chains do (row 552's step 2), in two shard sets, each pinned to one
   CPU model so that no table mixes models (the lead's decision of 2 November 2026):
   - (a) **256 bits, on the AMD EPYC 7763**: `require-datapath=any`,
     `expected-cpu="AMD EPYC 7763"` on every shard, a miss retried hourly and never relaxed (the
     owner's word of 27 October 2026, which keeps every committed baseline on the 7763). It gives
     the README's 256-bit surface columns and the `-ymm` and `abs(ymm)` recheck, committed with
     the `-zen3` suffix as row 552's 7763 chains are. The 7763 has no AVX-512, so the `TIME`
     surface's division rows run on the slow lowering there (README lines 280-282: 0.09 to 0.16
     of their full-width rate under `-XX:UseAVX=2`).
   - (b) **512 bits, on the AMD EPYC 9V45**: `require-datapath=512`,
     `expected-cpu="AMD EPYC 9V45"` on every shard. `sql/varka/HARDWARE.md` (lines 39-42) names
     the Zen 5 EPYC as the one runner class with a full-width datapath and says the full-width
     surface numbers wait for it (`m4/VARKA-62.md` section 11). It gives the README's 512-bit
     surface figures and the `TIME` division row behind the "up to 39x" headline. At about one
     draw in eighteen, each shard takes 18 dispatches on average (a geometric draw; a batch of
     fifteen lands at least one 9V45 with a chance of about 58%), so a set of N shards per surface
     expects about 18N dispatches, sent in batches of ten to fifteen as the README's recipe says.
   The inputs both sets share:
   - `rows=100000000`, the only count the workflow's `rows` note calls resident on a runner, and
     `driver-memory=6g`, the workflow's default (scope item 110's "7 GB driver" is the runner's
     limit, not an input);
   - `max-fixed-share=45`. The README's methodology (lines 245-258) says the surface's lightest
     entries, about 0.5 ns/row, cannot clear the standing 5% bound on a runner below about 1.4e9
     rows: at 1e8 rows a 36 ms job constant against about 50 ms of work is a fixed share of about
     42% (`(wall - executor) / wall`, `DateSurfaceBenchmark`), and the laptop's worst row, 5.6% of
     a 28 ms constant at 1e9, backs out to the same rate. The one runner measurement at 1e8 reads
     lower: `DateSurfaceBenchmark.java:600` records `date_add(d, 3)` at 854 M rows/s with a fixed
     share of 15.4%, a third of the arithmetic, so the first shard is likely to land under the
     bound. The value is **[I]** until the first shard's `# fixed share` lines of each set (the
     9V45's faster rows carry a larger share); a row over it fails the shard, and the bound is
     then set from those lines and stated. `varka-surface-benchmark.yml`'s `max-fixed-share`
     description requires that wherever a lifted bound produced a committed number, the README
     methodology says so, which step 3 does.
   *CI:* one `stop-after=build` dispatch, then for set (a) one `run` dispatch per shard and
   surface, and for set (b) batches of ten to fifteen per shard and surface until each shard
   lands, each the owner's, waiting for any running option matrix.
3. The README's two tables, the recheck paragraph and the paragraphs read from the surfaces
   requoted from the new files, the machine, width and date beside each table, the laptop files
   cited as the appendix that clears the 5% bound. The README quotes whichever committed runner
   file each section is built from, which is what scope item 110's "whatever machine took them"
   asks. The "up to 39x" headline (lines 58-62) and the `dayofweek(d)` and
   `time_trunc('MILLISECOND', t2)` figures of lines 229-231 follow that rule: the headline takes
   the 9V45 `TIME` surface's number once set (b)'s file lands, or, if the README is rewritten
   first, the 7763 number marked as AVX2, replaced when the 9V45 file lands. With them: the
   `trunc(d, 'QUARTER')` paragraph (151-156), rewritten or removed by what the run shows, scoring
   row 552's P3 (`VARKA-552.md` 4: the two baselines now agree within 20%, since 4.3.0 has the
   fork's `truncDate`); and the methodology at 245-258, which today says the surface cannot be
   measured on a runner at all, rewritten to state the runner files' row count, their lifted
   bound and the worst fixed share each table shows; `dev/varka_bench_diff.py --requote` lists
   the moved lines and `dev/varka_quote_check.py` holds every figure to a committed file.
   *CI:* the push starts the Build, whose docs job runs the quote check.

## 3. Done when

- both surfaces' 4.3.0 stock files are committed from both shard sets, the 7763's (`-zen3`) and
  the 9V45's, each with the Varka and engine-off files of the same runs;
- the README's surface tables quote them with their machine and date, the "up to 39x" headline
  is the 9V45 `TIME` surface's number, and the tables cite the laptop files as the
  appendix, and `git grep -n -E '4\.2\.0|stock 4\.2' README.md` shows no surface line;
- the README's methodology states the runner files' lifted fixed-share bound;
- row 552's P3 is scored in section 4;
- `dev/varka_quote_check.py` passes.

## 4. Outcome

Not started.
