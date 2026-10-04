# VARKA-164: a `TIME` chains benchmark, for the full-width number

*Scoped 21 September 2026 (milestone 5 section 2.100, row 164) from
`VARKA-118.md` section 3 item 5; planned 22 September 2026.*

## 1. The question

The closing task wants a number measured on a machine with a full-width
512-bit datapath, and the `TIME` surface cannot supply one. The reason is the
one `Chains` already wrote down for dates: a surface entry times a single
expression, and its lightest entries are bound by memory bandwidth rather than
by arithmetic, so a wider datapath cannot move them however genuine it is.
`DateSurfaceBenchmark` met this first and `DateChainBenchmark` is the answer
that was built for it.

The long lane makes the same problem worse in one direction and better in
another, and neither is guesswork. Worse: a `TIME` row is eight bytes rather
than four, so a given entry moves twice the bytes per row and reaches the
memory floor at half the arithmetic. Better: the `TIME` lowerings are
divisions, and a division is the most expensive thing Varka emits - the
conversion form is three lane operations and the magic form fourteen, against
one for an add - so a `TIME` expression carries far more arithmetic per link
than a date one does. *Correction, 23 September 2026: this read "seven", which
is the int lane's conversion form. A 64-bit lane and a double lane are the same
width, so the long lane's is `L2D`, divide, `D2L` (`VARKA-88.md` 9.2). The
section's conclusion is unchanged and the gap it describes is wider.*

Which of those wins is not known, and it decides the whole shape of the entry
list. That is what this task settles before it quotes anything.

There is a second reason the chains are the right vehicle rather than a wider
surface. The surface's ratios are Varka against stock Spark on one expression,
and the committed files put the fifteen projection entries between 13.4x and 38.2x
(`TimeSurface-varka-jdk25-results.txt` against
`TimeSurface-spark-4.2.0-jdk25-results.txt`). Those are the floor of what the
engine is worth: stock Spark pays its per-row costs once for a single
operation and at every link of a chain, while the kernel fuses the chain into
one loop. A chain file is where that shows.

## 2. The change

`TimeChainBenchmark` beside `DateChainBenchmark`, over the `varka_times`
table, with `TimeChains` as its entry list - the exact mirror of
`Chains`/`DateChainBenchmark`, which is four lines of benchmark class and one
list. The harness, the guards, the job-size rule, the provenance block and the
committed-file discipline are `DateSurfaceBenchmark.run`'s and are reused
unchanged; `dev/varka_bench_surface.sh` gains a `timechains` arm beside its
`surface`, `chains` and `time` ones.

Three things are genuinely new, and they are the task.

### 2.1 The op floor has to be re-derived, not copied

`Chains.MIN_OPS` is 280, and its comment derives that from a model of the date
lane: roughly 0.02 ns per emitter op over a memory floor near 0.8 ns, against
a 3.6 ns/row threshold, which puts break-even near 140 and the floor at twice
it on purpose. Every input to that model changes at the long lane. The memory
floor doubles with the row width. The per-op cost is not the date lane's,
because a long-lane op at 512 bits processes eight lanes where an int op
processes sixteen. And the threshold is the same fixed-share rule but over a
table twice the size, so a given row count buys a different amount of
executor time.

**So `TimeChains.MIN_OPS` is derived from a measurement, not inherited.** The
derivation is one cheap run: the existing `TimeSurface` files already give the
per-row cost of entries whose op counts `dev/varka_emit.sh --table` reports,
and two points on that line fix the slope and the floor. The comment records
the arithmetic, as `Chains`'s does, and the number is whatever it says.

### 2.2 The entries mix the long lane's three types

`Chains` mixes DATE, INT and the year-month interval in every entry, because
Varka covers three int32 types and a benchmark of one understates that. The
`varka_times` table offers the long lane's three - `t`, `t2` as `TIME`, `dt`,
`dt2` as day-time intervals, `l`, `l2` as `bigint` - and the same rule
applies: every entry carries more than one of them where the type system
allows it, and at least one produces an interval or a `TIME` rather than only
consuming one.

What limits composition here is that the `TIME` extracts narrow to an int at
the store and the emitter narrows only at an output root, so `hour(t) + 1`
does not fuse (`VARKA-102.md` 8.3, the restriction VARKA-28 lifts). A chain
therefore composes in the long lane and narrows once at the end, or does not
narrow at all: `time_trunc` over `time_add`-shaped arithmetic, `time_diff`
over two composed times, `greatest`/`least` and the conditionals over
composed values. The list is built from what fuses, and every entry is checked
with `--expect-fused` before it is timed, which is the harness's existing
guard.

### 2.3 The job size is a runner constraint, not a laptop one

The date surface needs 5e8 rows and no runner in the pool can hold that table;
the date chains clear the fixed-share rule at 1e8 because they are
compute-bound. A `TIME` row is eight bytes to a date's four, and the row's own
acceptance line names 2e8 rows in 12g on a runner. Whether that holds is the
first thing a dispatch finds out, and it is cheaper to find out with one
`build-only` warm-up and one ungated row-ladder dispatch than with a gated one
that waits for the Zen 5 - the lesson `VARKA-62.md` 11.8 records about
pricing a run on the machine that is scarce.

## 3. Predictions, registered before the run

1. **`TimeChains.MIN_OPS` lands above `Chains`'s 280.** A long-lane op covers
   half the lanes of an int one at the same width and the row is twice as
   wide, so more ops are needed to leave the memory floor. If it lands below,
   the division's cost per op is doing more work than the model expects and
   the comment says so.
2. **The chain ratio against stock exceeds the surface's.** The surface's
   `TIME` projection rows read 13.4x to 38.2x against stock 4.2.0 in the
   committed files, and the spread is the row engine's cost for the expression
   rather than the kernel's saving (VARKA-155's caution). The chains, which
   amortise stock's per-row costs over more links, read above the top of that
   range on the entries that compose deepest.
3. **Every entry fuses with no fallback batch**, which the harness checks
   rather than the plan asserting it.
4. **The entries clear `--max-fixed-share` at 2e8 rows in 12g on a runner.**
   This is the one the dispatch can refute cheaply, and the row's acceptance
   line names it.
5. **The band is tier 0 or 1 for every projection entry.** The date chains are
   compute-bound and quiet; a `TIME` chain that lands in tier 2 is a signal
   that it is still memory-bound, which would send its op count back to 2.1.

## 4. Verification

- The band measured before any figure is quoted, with
  `dev/varka_bench_band.py --write`, on an idle pinned machine - the standing
  rule, and the one VARKA-90 exists to enforce.
- The laptop's four arms first, as the `TIME` surface has them: stock 4.2.0 on
  JDK 17 and 25, the fork with the engine off, the fork with it on.
- Then the runner, through `.github/workflows/varka-surface-benchmark.yml`,
  with `build-only` to warm the SHA cache, an ungated row ladder to price the
  job, and only then the gated dispatch.
- A unit test that every entry's op count clears `MIN_OPS`, mirroring the one
  `Chains` has, so an entry that stops earning its place fails a suite rather
  than a runner dispatch.
- `dev/varka_quote_check.py`, since every figure quoted anywhere must trace to
  one of the committed files this task writes.

## 5. Outcome

*Written 22 September 2026 as the benchmark was built; the measurements follow
in their own pull requests and are scored here when they land.*

**Building the list found the tool short in three places, fixed here.**
`dev/varka_emit.sh --table` failed with `literal slot 0 outside [0, 0)` on
every long-lane expression that carries a literal slot, because the table path
handed the emitter the int literal count, which is zero for a long-lane shape
whose literals live in `longArgs`; the non-table path had the right count, and
the surface's rows had only ever been checked through it. The tool's resolver
looked functions up before the analyzer ran, so `hour(t + dt)` reached the
registry's builder with its `Add` still uncoerced and tripped the builder's
"function arguments must be resolved" assertion; the lookup is now the
analyzer's own. And a projection whose every entry declined printed no reason,
since `compilePartial` answers `None` without its declines; the compiler now
exposes `declines` for the tools, and the table prints the reason beside
`declined`.

**The composition ceiling.** The deepest chain the lane fuses today is 48 lane
ops, against the date list's 293 to 483. Three rows cap it. An extract narrows
only at an output root (VARKA-28), so nothing may stand above `hour`. `bigint`
and interval arithmetic are not built (VARKA-104 and VARKA-103), so
`time_diff(..) + time_diff(..)` declines and a `bigint` column enters only
through `greatest`, `least`, a comparison or a conditional. And `t - dt`
declines, because Spark resolves it to `t + (-dt)` and the interval negation
is not lowered, which is VARKA-103's to build. That last one corrects
`VARKA-118.md` section 2, which listed `t - interval` among the fused
forms: the coverage table has `t + INTERVAL` and never had the subtraction,
and the correction is written there.

**The op floor, derived (section 2.1).** `TimeSurface-varka-jdk25-results.txt`
against the tool's counts: the one-column entries read 0.8 ns/row at 4 ops
(`least(l, 5000000000)`), 0.9 at 6 (`hour(t)`), 1.0 at 7
(`time_trunc('MINUTE', t)`) and 1.2 at 12 (`minute(t)`), which is 0.05 ns per
op over a floor near 0.6 ns; the two-column entries, `greatest(t, t2)` at 4
ops and 1.1 and `t + dt` at 10 and 1.4, give the same slope over a floor near
0.9 ns. Arithmetic equals the two-column memory floor at 18 ops, and
`TimeChains.MIN_OPS` is 36, twice that - the date list's margin over its own
break-even. The twelve entries run from 36 to 48 ops; every one reads a `TIME`
and an interval column, five read a `bigint`, and the outputs are a `TIME`, an
interval, `time_diff` counts and the three extracts. Each is safe against
midnight by construction: `dt` is added only to `t` or a truncation of `t`,
which the table's sign rule keeps inside the day, and `dt2`, under a second,
only to a time already truncated to the second or coarser.

**Prediction 1 failed.** It put `MIN_OPS` above 280 because a long-lane op
covers half the lanes and the row is twice as wide, and both are so: the
per-op cost is 0.05 ns against the date model's rounded 0.02. But the date
list's floor was set by the fixed-share rule at a chain depth the date lane
reaches, and this lane's reachable depth is an order of magnitude shallower, so
the floor here is set by break-even against memory - and a shallower chain
needs fewer ops to clear a lower bar, not more.

**Prediction 4 is in doubt before any dispatch, from arithmetic alone.** At
0.05 ns per op the heaviest entry is near 3.3 ns/row. The rule's 5% at the
runner's constant of about 36 ms (`VARKA-118.md` section 3, item 5) needs
720 ms of executor time an iteration: 3.6 ns/row at 2e8 rows, 7.2 at 1e8. And
2e8 rows of this table is 9.1 GiB cached (the surface file reads 22.8 GiB at
5e8), which a 12g driver on a 15 GiB runner does not hold, where 1e8 rows at
4.6 GiB does. So the runner asks about 7 ns/row of entries that give 3, a fixed
share near 11%, unless the bound is lifted for the chains - as VARKA-105 lifted
it to 6% for one row, and as item 11 of `VARKA-118.md` section 3 requires
be stated wherever it is. The laptop at 2e8 rows needs 1.8 ns/row against its
18 ms constant, and every entry clears that. The dispatch decides the runner's
bound, and the decision is recorded here when it is taken.

**The runs, 22 and 23 September 2026, both on GitHub Actions.** The row ladder
of section 2.3 was dispatched ungated, because how many rows fit a runner and
what the job constant costs are properties of its memory and cores rather than
of its vector width, and gating a sizing question on the one-in-eighteen
machine is the trap `VARKA-62.md` 11.8 records. It answered in three
dispatches: 2e8 rows is refused outright (the table needs 9.1 GiB against a
12g driver's 7.0 GiB store), 1.5e8 fails on the last arm, and **1e8 is
resident at 4.6 GiB with a worst fixed share of 1.0%**. The first rung landed
on an AMD EPYC 7763 - Zen 3, `avx avx2` only - which is the AVX2 arm in its
natural habitat and the 256-bit end of the width subtraction, for free. The
gated dispatches then took **twenty-four tickets to find an EPYC 9V45**, close
to the census's one in eighteen, at a minute a miss.

**Prediction 4 failed, and the way it failed is the finding.** The entries do
not clear `--max-fixed-share` at 5% on the full-width runner: they read 7.2%
to 9.1%. Nothing is broken - the table was resident, every entry fused with
zero fallback batches - and the cause is the one 11.17 recorded for the date
chains. The fast machine cut Varka's work to 0.42 s an iteration while the
job's constant stayed near 36 ms. The date chains answered that by going from
1e8 rows to 2e8; these cannot, because a `TIME` row is 46 bytes where a date
row is 4, so **no row count both fits a 15 GiB runner and satisfies 5%**. The
benchmark has been outrun by the engine it exists to measure. On the owner's
decision the bound is lifted to 10% for this benchmark on runners, stated in
the README's methodology as `VARKA-118.md` section 3 item 11 requires, and
the workflow gained a `max-fixed-share` input so a run records the bound it was
held to. Two things make that honest rather than convenient: the constant
inflates every arm alike, so it *compresses* the ratio rather than flattering
it, and the driver measures executor time independently, which is the figure
the post leads with.

**Prediction 2 held, and by a margin the plan did not expect.** On the EPYC
9V45 the twelve chains run at 3.7 to 5.5 ns/row against stock 4.2.0's 82 to
198, which is **19.6x to 46.9x, median 31.8x** by wall time and 21.0x to
53.2x, median 34.9x by executor time; against the fork with the engine off,
25.9x. The surface's projection rows read 13.4x to 38.2x, so the chains do
exceed them - but on the Zen 3 runner the same twelve read only 2.6x to 5.9x,
median 3.9x, which is *below* the surface. The prediction is right about the
full-width machine and wrong about the lane in general, and section 2.1's
model is what missed it: it priced an op and forgot that the machine chooses
which ops there are.

**Prediction 3 held.** Every entry fused on both machines, `fallback 0` on all
twenty-four measured rows, checksums recorded.

**Prediction 1's post-mortem, now that both machines have run.** `MIN_OPS` was
derived from the laptop's surface at 0.05 ns per op, and the full-width runner
does the same entries at about a tenth of that per row. The floor is not wrong
- it still separates a memory-bound entry from a compute-bound one on the
machine it was derived from - but it is a laptop constant, and a reader should
not take 36 as a property of the lane.

**What the two machines say about the datapath, which is the question row 164
exists to answer.** Between the Zen 3 and the Zen 5, at the same row count and
the same commit, the three scalar arms gain 1.64x, 1.76x and 1.80x - that is
the machine generation, and it agrees with the 1.78x to 1.84x milestone 4
measured on the date chains. Varka gains **14.6x**. Dividing the machine out
leaves **8.3x for the kernel**, and it decomposes: two of it is the lane count,
four 64-bit lanes against eight, and the rest is the lowering, because a
machine without AVX-512 falls back to the fourteen-operation magic form where
one with it emits the three-operation conversion form. Two times four-point-
seven is 9.4 against 8.3 measured, which is as close as this kind of
arithmetic gets.

That is a different answer from the date chains', where the width was worth
1.14x once the machine was divided out, and the two are consistent: a date
chain is a dependency chain of cheap operations and is latency-bound, while a
`TIME` chain is four to six constant divisions and its cost is the division's
instruction count. **On this workload the lowering matters more than the
width**, and neither number generalises to the other's shape.

Prediction 5, the band, waits: a band for a runner-measured file needs repeated
runs on the same pool and is a question for VARKA-118 rather than this one.

## 6. Explicitly out of this task

- **Lifting the narrowing restriction** so `hour(t) + 1` fuses. That is VARKA-28 (`m8/SCOPE.md` item 39), and this list is built from what fuses
  today rather than waiting for it.
- **A `TIME` equivalent of the surface's filter entries.** The chains answer
  the datapath question, which is a projection question; the filter shapes are
  the surface's and stay there.
- **The full-width number itself.** This task delivers the benchmark, its band
  and the laptop arms; quoting a 512-bit figure is the closing task's (118),
  which is also where the runner-pool hit rate is already priced.
- **Re-deriving `Chains.MIN_OPS`.** The date model stands for the date lane;
  this task derives a second number for a second lane and leaves the first
  alone.
