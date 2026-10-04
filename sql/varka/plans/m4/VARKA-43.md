# VARKA-43: what bounds a loop method inside one output

## 1. Where this came from

`m4/PLAN.md` 2.16 / task row 43. `groupOutputs` partitions *between*
outputs and never inside one, so `GROUP_BUDGET` binds only when a projection's
ops are spread across several roots. `CHRONO_WEIGHT` separates calendar nodes
that are separate output roots and does nothing for calendar nodes under one
root. The budget's own javadoc calls single-output loops healthy "at every width
tried", and the width tried was **59 ops**.

This task finds out where that stops being true. Per 2.16 it is a design task
rather than a recipe: the measurement decides between three mechanisms, and the
choice is the task. This file covers the measurement, which is all that can be
built while the emitter is held by other work (section 5).

### 1.1 Two corrections to 2.16, both measured

**The example no longer demonstrates the problem.** 2.16 says
`least(greatest(year, month), greatest(dayofmonth, quarter))` is one root
emitting one method of 1672 bytes, "roughly 190 vector ops". Measured on
today's emitter it is **61** `IntVector` ops and 7533 class bytes. The
difference is VARKA-32 step B1: fragment sharing collapses the four calendar
prefixes into one, so the shape that used to be four decompositions is now one
decomposition and four tails. The number was true when written and is not now.
`year` alone measures 39 ops, which agrees with `VARKA-48.md`'s own figure -
so the emitter has not drifted; the shape has.

**`HugeMethodLimit` is not the constraint here.** VARKA-44's concern is that the
epilogue crosses HotSpot's 8000-byte refusal threshold at seventeen calendar
outputs. A single-output *loop* method does not come close: measured across the
ladder below, `loopDense0` runs from 287 bytes at 20 ops to **1989 bytes at 248
ops**, about 7.4 bytes per op, so it would take roughly 1050 ops to reach 8000.
Whatever bounds a loop method inside one output, it is not a hard refusal to
compile - which means the answer has to come from timing and from register
residency, not from a size threshold.

## 2. The instrument

The ladder has to vary op count and nothing else. Three things would confound it
if the shape were built the obvious ways:

* **A chain of `AddDays`/`SubDays`** varies dependency depth along with op count,
  which is VARKA-25's axis, not this one.
* **Repeated calendar nodes** get their prefixes shared (step B1 above), so op
  count stops tracking node count - the correction in 1.1 is exactly this trap.
* **Repeated identical subtrees** are CSE'd by `emitValue`, so they cost once.

The shape that avoids all three is a `greatest`/`least` tree over independent
`dayofweek(d + k)` subtrees, each with a distinct literal slot: no two subtrees
are equal, so nothing is CSE'd; `DayOfWeek` shares no fragment; and the subtrees
are independent of each other, so the body is wide rather than deep. Measured,
it is exactly linear at **19 ops per step**:

| subtrees | `loopDense0` ops | `loopDense0` bytes | `epilogueMasked` bytes |
|---|---|---|---|
| 1 | 20 | 287 | 385 |
| 2 | 39 | 431 | 572 |
| 3 | 58 | 572 | 756 |
| 4 | 77 | 713 | 940 |
| 5 | 96 | 854 | 1124 |
| 6 | 115 | 995 | 1308 |
| 7 | 134 | 1137 | 1493 |
| 9 | 172 | 1421 | 1863 |
| 11 | 210 | 1705 | 2233 |
| 13 | 248 | 1989 | 2603 |

That covers 2.16's requested ladder - 60, 100, 150, 190, 250 ops - at 3, 5, 8,
10 and 13 subtrees, and the op counts are asserted off the class file rather
than assumed, so a lowering change that moves them fails the benchmark's own
check rather than silently re-labelling the x-axis.

## 3. What is measured

1. **Steady-state throughput per row** at each ladder point, at both vector
   widths. Rows per second is the wrong unit here because every point does a
   different amount of work; the number that matters is **nanoseconds per row
   per op**, which is flat if the loop scales and rises where it does not.
2. **Time to reach steady state.** This is the axis VARKA-11 measured when it set
   `GROUP_BUDGET`, and the one the budget exists to protect: a body that
   eventually runs fast but takes seconds of C2 time is a bad trade for a short
   query. Read from `-XX:+PrintCompilation` as the wall time from JVM start to
   the tier-4 compilation of `loopDense0`, the same way `VARKA-32.md` 7.5
   read it.
3. **Whether C1 refuses.** `SKILLS.md` records C1 declining a wide body at
   128-bit with `COMPILE SKIPPED: out of virtual registers in linear scan`. If
   that appears anywhere on the ladder it is a stronger signal than any timing,
   because it is the register file saying no rather than a benchmark saying
   slower.

## 4. Predictions, registered before the run

1. Nanoseconds per row per op is flat to within noise across the whole ladder at
   AVX-512. The 32 zmm registers hold this body comfortably at every width
   tried, and nothing in the lowering changes shape with size.
2. At 128-bit it is **not** flat, and degrades somewhere between 100 and 200
   ops, because 16 xmm registers is where `SKILLS.md`'s spill investigation put
   the edge for a body of about forty live values.
3. Tier-4 compile time grows roughly linearly and stays under one second at 248
   ops. `VARKA-32.md` 7.5 measured a 200-op method reaching tier 4 in 272 ms
   and the whole twenty-method kernel in 2.4 s, so VARKA-26's "~10 second cliff"
   is expected to be a number about a machine and a JDK that no longer applies.
4. C1 refuses somewhere on the 128-bit ladder and nowhere on the AVX-512 one.

Prediction 3 is the one that matters for the milestone: if it holds, the
compile-cliff argument behind `GROUP_BUDGET = 16` rests on a stale measurement,
and that is worth knowing before the next task weighs a node against it.

## 5. What this task does not do

**It does not choose the mechanism.** 2.16 offers three - split inside an
output, decline the shape through `fitsBudgets`, or accept it and record where
the cliff sits. All three edit `VarkaLoopEmitter` or the compiler, which PR #64
and PR #78 hold. The measurement is what those PRs do not block, and it is what
the decision has to rest on anyway, so it goes first and the decision follows
once the emitter is free. 2.16 asks for exactly this order.

**It does not touch the epilogue.** That is VARKA-44, and the epilogue column in
section 2's table is recorded only because it comes free from the same emission.

## 6. Files

| file | what |
|---|---|
| `VarkaEmitterParityBenchmark.scala` | the ladder section: the shape builder, the op-count assertions, the per-op timing at both widths |
| `VarkaEmitterParityBenchmark-jdk25-results.txt` | regenerated in one run |
| `sql/varka/plans/m4/VARKA-43.md` | this file, with section 8 filled in |
| `m4/PLAN.md` | 2.16's two corrections from 1.1, and row 43 |
| `SKILLS.md` | whatever the ladder says about where a single-output loop stops scaling |

Nothing under `src/main`. This task measures the emitter; it does not change it.

## 7. Risks

1. **Measuring the wrong axis.** Section 2's three confounders are the whole
   design of the instrument, and 1.1 is evidence the trap is real rather than
   theoretical - the milestone's own example fell into it.
2. **A ladder whose x-axis drifts.** The op counts are asserted off the class
   file, so a lowering change breaks the assertion instead of quietly moving the
   axis.
3. **Reading compile time from a benchmark JVM.** `PrintCompilation` output has
   to come from a run whose only load is the shape under test, or the timings
   measure queueing behind other compilations. Each ladder point gets its own
   JVM for that reading.
4. **Concluding from one host.** Everything here is one Zen 5 machine on one
   JDK. The conclusion that can be drawn is about this machine; the conclusion
   that cannot is "no cliff exists". Section 8 states which is which.

## 8. Outcome

The ladder ran at both widths, with a `-XX:+PrintCompilation` pass at each. Four
predictions, two held, two missed - and the two that missed are the informative
ones.

### 8.1 Throughput: flat at both widths

> **Correction, measured after this task landed.** The column below labelled
> AVX-512 is a 256-bit-datapath result. Running this same ladder at
> `-XX:MaxVectorSize=32` gives 0.0070-0.0074 ns/row/op - within noise of the
> 512-bit column, and a 0.95x "speedup" for doubling the lanes, while 128 -> 256
> is 2.48x. This machine has the whole AVX-512 instruction set and HotSpot picks
> `MaxVectorSize=64`, but the execution units behind it are 256 bits wide.
> Nothing in this section's *conclusions* moves - both arms of every comparison
> here ran on the same hardware, and flatness is a property of the ladder rather
> than of the width - but "at both widths" below means 4 lanes against 16 lanes
> issued through a 256-bit datapath, not two datapath widths. `SKILLS.md` carries
> the three-width table and the method.

Per-op cost, derived from the committed results (AVX-512) and the 128-bit run
recorded here. Rows per second is the wrong unit when every point does a
different amount of work; nanoseconds per row per op is flat if the loop scales.

| ops | 20 | 58 | 96 | 153 | 191 | 248 |
|---|---|---|---|---|---|---|
| AVX-512 ns/row | 0.125 | 0.453 | 0.698 | 1.107 | 1.468 | 1.858 |
| AVX-512 ns/row/op | 0.0063 | 0.0078 | 0.0073 | 0.0072 | 0.0077 | 0.0075 |
| 128-bit ns/row | 0.274 | 1.231 | 1.721 | 2.492 | 3.113 | 4.156 |
| 128-bit ns/row/op | 0.0137 | 0.0212 | 0.0179 | 0.0163 | 0.0163 | 0.0168 |

**There is no cliff at either width up to 248 ops in one loop method.** At
AVX-512 the per-op cost sits in 0.0072-0.0078 from 58 ops onward, about +-4%. At
128-bit it *improves* from 58 to 153 and then goes flat. At both widths the
worst point on the ladder is the narrowest one, where the loop's fixed costs are
spread over the fewest ops.

### 8.2 Compile time: linear, and two orders of magnitude below the folklore

Wall time from the tier-3 compilation of `loopDense0` to its tier-4
compilation, non-OSR, read from `-XX:+PrintCompilation`:

| ops | 20 | 58 | 96 | 153 | 191 | 248 |
|---|---|---|---|---|---|---|
| AVX-512 (ms) | 30 | 82 | 131 | 206 | 228 | 271 |
| 128-bit (ms) | 58 | 110 | 240 | 321 | 403 | 501 |

Linear, at roughly 1.1 ms per op at AVX-512 and 2.0 at 128-bit. The AVX-512
figure lands within 1 ms of `VARKA-32.md` 7.5's independent reading (272 ms
for a 200-op method), from a different shape and a different run, which is about
as much corroboration as two measurements can give each other.

**The comparison against the folklore needs the OSR path, not this one.** The
"~10 seconds" in `VarkaLoopEmitter`'s `GROUP_BUDGET` javadoc, and in
`SKILLS.md`, describes a tier-4 **OSR** compile of a 64-op loop - and the table
above deliberately excludes OSR compilations, so it does not measure the same
thing. Measured on the path the folklore actually describes:

| ops | 20 | 58 | 96 | 153 | 191 | 248 |
|---|---|---|---|---|---|---|
| AVX-512 OSR t3->t4 (ms) | 15 | 52 | 100 | 163 | 165 | 186 |
| 128-bit OSR t3->t4 (ms) | 12 | 33 | 81 | 88 | 121 | 140 |

The OSR compile is *faster* than the standard one at every ladder point: **186
ms at 248 ops** against "~10 seconds at 64 ops". Four times the op count, and
roughly fifty times less compile time.

**The repository already contained the contradiction.** Section 2.3 of
`m4/PLAN.md` and its debt register price the same thing at "~1 ms per
vector op", which at 64 ops is 64 ms - so two numbers 150x apart had coexisted
unremarked, and the ladder agrees with the per-op one. That makes the likeliest
reading of the ~10 s that it was never ten seconds of compiler *work*:
`SKILLS.md` says fresh JVMs got the compile in during warmup and busy ones did
not, which describes a compile task **queueing** behind others under load. That
keeps what was observed and drops the inference that op count caused it - and a
queued compile can bite at any width, which no per-method op budget can bound.

Stated carefully, because this is a different machine and a different JDK from
the one that produced the original number: **the ~10-second OSR compile does not
reproduce here, on the same path, at four times the width.** That is not a claim
that the observation was wrong when it was made - `SKILLS.md` records the rate
jumping 9 to ~1000 M rows/s at t=12s, which is not the kind of thing one
mismeasures. It is a claim that the number no longer describes this JDK, and
that anything resting on it needs re-deriving rather than re-citing.

### 8.3 C1 refuses, and it is not about the register file

`COMPILE SKIPPED: out of virtual registers in linear scan (retry at different
tier)` appears in both runs, on exactly the same two methods:

* `VarkaFusedLadder13::epilogueDense` (1954 bytes) - the *epilogue* at the
  widest ladder point, not the loop. `loopDense0` is never refused, at any
  ladder point, at either width.
* `ChronoVectorOps::vectorFourFields` (936 bytes), the hand-written kernel.

Both are refused **identically at 512-bit and at 128-bit**, which is the finding.
`SKILLS.md` attributes that refusal to 128-bit and its sixteen `xmm` registers;
it happens just as readily with thirty-two `zmm` available, so it is C1's own
linear-scan allocator running out of *virtual* registers on a large body, not
the machine register file. And "retry at different tier" means the method goes
to C2 instead - it is a tier decision, not a failure to compile, which is why
nothing was ever visibly slow.

### 8.4 Predictions, scored

1. **Held.** Flat at AVX-512, +-4% from 58 ops up.
2. **Missed.** 128-bit was predicted to degrade between 100 and 200 ops on
   register-pressure grounds. It does not degrade at all; per-op cost improves
   from 58 to 153 and then flattens. This is the same conclusion VARKA-32 step B2
   reached from a different direction - `SKILLS.md`'s spill investigation traced
   128-bit bimodality to one hand-written 936-byte body, and B2 found the
   emitter's own generated four-output method had none. This ladder extends that
   from four outputs to 248 ops in a single method: **the register-residency
   argument in `GROUP_BUDGET`'s javadoc is about hand-written bytecode, not
   about what the emitter produces.**
3. **Held**, and it is the one the milestone turns on - but it took a second
   measurement to earn. The first pass compared standard compilations against a
   figure describing OSR ones, which would have been a wrong comparison drawn
   from right numbers. On the OSR path the gap is wider still: 186 ms at 248
   ops. See 8.2.
4. **Missed in both halves.** C1 was predicted to refuse somewhere on the
   128-bit ladder and nowhere on the AVX-512 one. It refuses in the same one
   place at both widths, and that place is the epilogue rather than the loop.

### 8.5 What this does and does not license

It licenses retiring the compile-time argument for `GROUP_BUDGET = 16` **as it
applies to ops inside one output**: at 248 ops a loop method costs 271 ms of C2
time on the standard path and 186 ms on the OSR one, and scales linearly in
throughput, so a shape that fuses into one wide method is not paying the price
the budget was written to avoid.

`GROUP_BUDGET`'s own javadoc still states the ~10-second figure as its "measured
reason". Correcting that sentence is deliberately left to VARKA-43's second half
rather than done here, and not to avoid a merge conflict: the javadoc explains
why the constant has the value it has, and the second half is what decides
whether the value changes. Editing the explanation while leaving the number, and
the decision, outstanding would leave the file less coherent than it is now.
This file and `SKILLS.md` carry the measurement in the meantime.

It does not license "no cliff exists". This is one Zen 5 host, one JDK 25 build,
one shape family, and a ladder that stops at 248 ops because that is where the
milestone's question stopped. A different lowering with more live values per op
could still spill; what has been shown is that op *count* alone does not cause
it over this range.

It also does not license anything about a **full-width** 512-bit machine, which
this one is not (see 8.1's correction). The compile-time findings should carry
over unchanged, since C2's work is a function of the IR rather than of the
execution units, and so should the flatness, which is about op count. What would
change on Intel Sapphire Rapids or AMD Turin is the absolute throughput of the
512-bit arm - and with it, whether 248 ops in one method still costs what it
costs here. Re-running this ladder is the first thing worth doing on such a host,
because it is cheap and it is already committed.
