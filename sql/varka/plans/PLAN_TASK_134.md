# Task 134: the partitions ladder

*Milestone 5, section 2.69. Opened 16 September 2026 from the September surveys;
run 16 to 17 September 2026 in an idle machine window the owner offered.*

## 1. Where this came from

Every number this engine has published is one partition on one core. A Spark
executor runs one task per core, so the question the closing write-up cannot
dodge is whether a win measured alone survives the memory system being shared.
Two papers in the record say it may not: Schmidt (`SCOPE_MILESTONE_8.md` item
25) has a 48-core socket saturating DRAM at about twelve scalar threads, with
SIMD only lowering that count, and Kersten's branch-free selection loses a fifth
of its throughput at twenty threads from bandwidth alone.

## 2. The admission check, done

**The script could not do it.** `dev/varka_bench_surface.sh` hard-coded
`--master local[1]`, so `--partitions P` only ever made P tasks queue on one
core. The ladder needs a second knob, and this task adds it: `--cores C`, the
driver's `local[C]`, recorded in the provenance beside `partitions`. The two
stay separate because they are separate - P partitions on C cores is a queue C
deep - and the ladder sets them equal.

**Two things change meaning above one core**, and the plan says so rather than
letting a reader assume otherwise. The fixed-share rule, `(wall - executor) /
wall`, goes negative once executor time is a sum over parallel tasks, so it
stops being a guard; the comparable column becomes executor time, and the
wall-time ratio between the two arms at one rung stays meaningful because both
arms pay the same scheduling cost. And the cache build is parallel too, so a
rung's absolute numbers are comparable to another rung's only through the
arms' ratio.

**The machine.** `aqua`, AMD Ryzen AI 9 HX PRO 370: 12 physical cores, 24
hardware threads, the full AVX-512 flag set, and a datapath probe that reads
about 1.14 at 512 against 256 bits - so this is a scaling measurement, not a
width one. It is both machines section 2.69 asks for at once: a Zen 5, and an
SMT machine where 12 and 24 are the physical-core and hardware-thread rungs
(Gottschlag's sibling channel).

## 3. The design

### 3.1 The mechanism

Four rungs - 1, 12, 24 and 6 cores, in that order so that the three most
informative are done first if the window ends early - each with two arms, the
engine on and the engine off, at the committed 1e9 rows and a 56g driver, which
are `PLAN_TASK_99.md`'s parameters so the one-core rung is comparable with the
committed files.

    dev/varka_bench_surface.sh --rows 1000000000 --partitions P --cores P \
      --driver-memory 56g \
      varka-pP=$PWD:$J25:varka \
      varka-off-pP=$PWD:$J25

Eight files, `DateSurface-varka-p{1,6,12,24}-results.txt` and their
`varka-off-` twins. The existing committed one-core files are left alone: this
ladder's rungs are one session and carry their own labels.

### 3.2 What is deliberately unchanged

The driver, the inventory, the fixture, the row count and the heap. The
committed `varka-jdk25` and `varka-off-jdk25` files. `--master local[1]` stays
the default, so every existing command line means what it meant.

## 4. Files

* `dev/varka_bench_surface.sh` - the `--cores` knob and what it changes.
* `sql/varka/bench/benchmarks/DateSurface-varka-p*-results.txt` - eight new.
* `sql/varka/plans/PLAN_MILESTONE_5.md` - row 134.

## 5. Tests, and what each is for

None: this is a measurement. The script's own guards run per arm - the canary,
the load check, the datapath probe, and `--expect-fused` on every Varka row, so
a rung that quietly stopped fusing fails rather than publishing a scalar number.

## 6. The measurement

The eight files.

### 6.1 Predictions, registered before the run

1. **The engine's advantage narrows as cores rise.** The lightest surface rows
   are already memory-bound at one core (`PLAN_TASK_62.md` 11.13 puts a third of
   the surface there), so they have nothing to gain and everything to lose from
   sharing; the heavy calendar rows should hold most of their ratio.
2. **12 to 24 buys little and may cost.** The second thread of a core shares its
   vector units, so on rows bound by arithmetic the sibling adds queueing, not
   throughput; on memory-bound rows it may help by hiding latency.
3. **Scaling is sublinear from 6 cores up on the light rows** and closer to
   linear on the calendar rows, which is the same split as prediction 1 seen per
   rung rather than per arm.
4. Every rung fuses every row: occupancy changes no plan.

## 7. Risks

* **The window ends before the fourth rung.** The order is 1, 12, 24, 6 for that
  reason: the first three answer the question and the fourth refines it.
* **Thermal drift over four rungs.** The canary runs per arm and its numbers are
  in each file; a rung whose canary says the machine moved is reported, not
  quietly averaged in.
* **The heap.** 1e9 rows cached is about 23 GiB; 56g leaves room at every rung,
  and the machine has 83 GiB with 77 free at the start.

## 8. Sequencing

1. The `--cores` knob. 2. This plan. 3. The build, before the window goes quiet.
4. The four rungs, serially, under `systemd-inhibit` so the laptop cannot sleep
through them. 5. Section 9, the row, and the files committed.

## 9. Outcome

Done, 17 September 2026, on `aqua`, between 23:58 and 03:26. Four rungs, two
arms each, eight files, every rung green and every row fused.

**The ladder.** The engine's speedup over the row engine, per rung, median over
the 52 shapes both arms carry:

| cores | median | min | max |
| ---: | ---: | ---: | ---: |
| 1 | 19.0x | 1.12 | 35.3 |
| 6 | 18.1x | 1.10 | 33.1 |
| 12 | 16.2x | 1.14 | 32.2 |
| 24 (SMT) | 17.4x | 1.22 | 35.7 |

**The question section 2.69 asked is answered: the advantage survives full
occupancy.** It dips 15 per cent when twelve cores compete for the memory
system, then recovers about half of that when the sibling threads fill latency
gaps. The worst shape in the table improves with occupancy rather than degrading
- the minimum ratio rises from 1.12 to 1.22 - so there is no shape whose win
disappears under load.

**The median hides the finding, which is a split by shape.** Two-column shapes
with almost no arithmetic lose most and never recover: `greatest(d, d2)` runs
20.7x, 18.1x, 13.9x, 14.2x across the rungs, `least(d, d2)` 20.2x to 14.2x,
`CASE WHEN d < d2 THEN d ELSE d2 END` 19.2x to 13.6x. Arithmetic-heavy calendar
shapes hold or gain: `weekofyear(d)` climbs 24.8x, 27.1x, 30.1x, 35.7x,
`extract(YEAROFWEEK FROM d)` 18.1x to 24.5x, `last_day(d)` 14.4x to 18.7x. One
shape falls monotonically and is named rather than smoothed away:
`extract(DAYOFWEEK_ISO FROM d)`, 34.2x, 31.5x, 26.8x, 24.2x.

**The mechanism, from the two arms' own scaling.** Median speedup over one core
is 3.54x, 5.35x, 5.78x for the engine and 3.76x, 5.62x, 5.86x for the row engine
- the row engine scales slightly better overall, which is why the median ratio
dips. Per shape the two reverse: at 24 threads `weekofyear(d)` scales 7.96x for
the engine against 5.54x for the row engine, while `greatest(d, d2)` scales
3.70x against 5.40x. The reading that fits both is that a fast implementation
reaches the shared bottleneck sooner. On a light shape the kernel is already
near memory bandwidth on one core, so more cores buy little, while the row
engine starts far enough from that ceiling to scale almost linearly into it. On
a heavy shape the kernel has arithmetic to hide latency behind and the row
engine has none to gain from, so the gap widens. **A one-core ratio therefore
overstates what a loaded machine sees on bandwidth-bound shapes and understates
it on compute-bound ones**, which is the sentence task 118 should carry.

**Where the ceiling is.** Per-physical-core efficiency is 0.59 at six cores,
0.45 at twelve and 0.48 at twenty-four threads, so the ceiling is gradual and
already present at six - a bandwidth limit, not a scheduling cliff at some
thread count. Schmidt's twelve-thread saturation on a 48-core socket has its
analogue here well below twelve cores, which is what a mobile part's narrower
memory system predicts.

### 9.1 The predictions, scored

**1. Held, and understated the engine.** The advantage did narrow with cores,
and the split by shape was exactly as registered - the memory-bound rows lost
and the calendar rows held. What the prediction did not allow for is that the
curve is not monotonic: 24 threads beat 12, so 'narrows as cores rise' is true
of 1 to 12 and false of 12 to 24.

**2. Wrong, and the reasoning behind it was wrong too.** The prediction was that
the second thread of a core would buy little and might cost, because siblings
share their vector units. It buys about 8 per cent at the median, and only four
shapes of 52 are slower with it - by 1 per cent, which is noise. Worse for the
prediction, the gain is concentrated exactly where it said the loss would be: `d
+ CAST(month(d) AS INTERVAL YEAR)` gains 1.29x, `add_months(d, 3)` 1.26x,
`last_day(d)` 1.26x, `weekofyear(d)` 1.25x, all arithmetic-bound. The
explanation is one the project already had and I failed to apply:
`PLAN_TASK_62.md` 11.13 concluded from the 1.14x datapath step that these
kernels are latency-bound rather than issue-bound. A latency-bound kernel leaves
its vector units idle much of the time, so a sibling thread fills gaps instead
of competing for slots. The four shapes that do lose are the cheap two-column
ones, which are bandwidth-bound and have nothing for a second thread to do.

**3. Half right.** Scaling is sublinear on the light rows, as predicted, but it
is sublinear everywhere and it is already sublinear at six cores (0.59 per
core), rather than starting linear and bending later. The per-shape split the
prediction described is real.

**4. Held.** Every rung fused every row: 63 fused queries in each of the four
engine arms, 252 in all, no decline, and `--expect-fused` would have failed the
run otherwise.
Occupancy changes no plan, which is what makes the ratios above comparisons of
the same work.

### 9.2 One thing to follow up, not a finding yet

The one-core rung is within noise of the committed 13 September file on 44 of 45
shared shapes - median ratio 1.005 - and 1.27x faster on one: `date_add(d, 3)`,
563 ms against 714 ms. That is the shape a literal day shift takes, and task
84's value-range lattice landed between the two runs, so the plausible reading
is that the analysis now proves the shift in range and drops a runtime guard.
One number is not evidence: this needs task 101's band tooling to say whether
1.27x is outside the noise for that row, and until it does the claim stays here
rather than in the write-up.
