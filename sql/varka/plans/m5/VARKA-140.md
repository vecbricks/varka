# VARKA-140: the chains at occupancy

*Milestone 5, section 2.75. Opened and measured 17 September 2026, in an idle
machine window, from VARKA-134's own finding.*

## 1. Where this came from

VARKA-134 put the date *surface* on an occupancy ladder and found the engine's
advantage narrowing from 19.0x at one core to 16.2x at twelve, with the erosion
concentrated on shapes that read two columns and compute almost nothing. Its
explanation was that a fast implementation reaches the shared bottleneck sooner:
on a light shape the kernel is already near memory bandwidth on one core, while
the row engine starts far enough away to scale almost linearly into it.

That explanation makes a prediction it did not test. `Chains` is the surface's
opposite by construction - the same operations composed three and four deep
until the kernel is bound by what it computes, which is why `VARKA-62.md`
11.13 chose it over the surface as the honest measure of the datapath - so if
the mechanism is right, the chains should erode less, and might not erode at
all. The write-up's headline figure of about 10x comes from the chains, and it
had no occupancy answer.

## 2. The admission check, done

**The committed chains files cannot serve as the one-core rung.** Their
provenance reads `datapath: ... ratio 2.01`, which is the EPYC 9V45 runner of
VARKA-62 (B), not this laptop's 1.14. Both rungs are measured here, in one
session, as VARKA-134's were.

**Why two rungs and not four.** The question is whether a compute-bound ratio
survives full occupancy, which one core and twelve physical cores answer; the
SMT and half-occupancy rungs refine a curve that VARKA-134 already drew.

## 3. The design

Two rungs, two arms each, at the committed 2e8 rows and a 56g driver:

    dev/varka_bench_surface.sh --benchmark chains --rows 200000000 \
      --partitions P --cores P --driver-memory 56g \
      varka-pP=$PWD:$J25:varka \
      varka-off-pP=$PWD:$J25

Twelve cores first, so an overrun costs the cheaper rung.

## 4. Files

* `sql/varka/bench/benchmarks/DateChain-varka-p{1,12}-results.txt` and their
  `varka-off-` twins - four new.
* `sql/varka/plans/m5/PLAN.md` - row 140.

## 5. Tests, and what each is for

None: a measurement, with the script's own guards per arm and `--expect-fused`
on every Varka row.

## 6. The measurement

### 6.1 Predictions, registered before the run

1. The chains erode less than the surface's 15 per cent.
2. The engine's own scaling is better on the chains than on the surface's 5.35x,
  because there is arithmetic to hide latency behind.
3. Every chain stays above the write-up's 10x at twelve cores, or the write-up
  needs a caveat.

## 7. Risks

* **One hour.** The row-engine arm at one core is the long pole at about half an
  hour; the twelve-core rung is measured first for that reason.

## 8. Sequencing

1. This plan. 2. The two rungs. 3. Section 9 and row 140.

## 9. Outcome

Done, 17 September 2026, on `aqua`, 09:50 to 10:20. Four files, both rungs
green, every Varka row fused.

**The advantage grows under occupancy rather than eroding.**

| cores | median | min | max |
| ---: | ---: | ---: | ---: |
| 1 | 10.29x | 8.93 | 12.76 |
| 12 | 11.42x | 10.19 | 13.82 |

Not one of the twelve chains falls, and the worst improves from 8.93x to 10.19x,
so every chain is above 10x at full occupancy. The largest movers go the same
way: `dayofweek(add_months(last_day(date_add(d, i)), i) + ...)` from 10.59x to
13.29x, `quarter(...)` from 9.40x to 11.19x.

**The mechanism is confirmed from the opposite side.** On the surface the row
engine scaled better than the engine - 5.62x against 5.35x at twelve cores -
which is why that ratio dipped. On the chains it reverses: the engine scales
**7.09x** and the row engine **6.18x**. Same machine, same session, same twelve
cores; the only difference is how much arithmetic each shape does per byte it
reads. VARKA-134's reading was that a fast implementation reaches the shared
bottleneck sooner, and this is its other half: where there is no memory
bottleneck to reach, the kernel keeps pulling away.

The engine's scaling is also *uniform* here - 6.63x to 7.31x across all twelve -
where the surface's ranged 3.59x to 6.98x. Compute-bound work scales more and
scales evenly; memory-bound work scales less and scales unevenly, by how much
each shape reads.

### 9.1 The predictions, scored

**1. Held, and understated.** The prediction was that the chains would erode
less than 15 per cent. They do not erode: they gain 11 per cent.

**2. Held.** 7.09x against the surface's 5.35x, per physical core 0.59 against
0.45.

**3. Held.** Every chain is above 10x at twelve cores, where at one core three
were below it.

### 9.2 What this changes in the write-up

VARKA-118 can stop hedging about occupancy in one direction and start stating it
in two. The honest sentence is that the engine's advantage narrows on
bandwidth-bound expressions and widens on compute-bound ones, both measured on
the same machine in the same week - and that the headline, which comes from the
chains, is if anything conservative for a loaded executor rather than
flattering.
