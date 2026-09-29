# The Vector API and vector width

What the Vector API costs on real hardware, and the ways vector width changes the answer rather than just the speed.

One of Varka's lesson files; the index over all of them is
[`SKILLS.md`](../../../SKILLS.md) at the repository root, which is generated from
these files by `dev/varka_toc.py`.

## Vector API on HotSpot, Measured (JDK 25, x86-64)

- **A day-indexed lookup table beats the civil-from-days arithmetic, and a plain
  scalar loop beats the vector gather** (`VarkaVectorApiProbeBenchmark`). Impala
  reads `year` out of a table covering 1950-2049 and computes it only outside
  that window; priced on lanes here, over 20M dates at AVX-512. The table below
  is one run of that benchmark and was never committed; the committed file has
  since been regenerated twice and its rows sit within 3% of these, which moves
  no conclusion drawn here. The fused table further down is the probe's first
  committed run:

  | | whole 100-year table (143 KB) | seven-year span (~10 KB, TPC-H shaped) |
  |---|---|---|
  | `IntVector` gather | 3573.9 M rows/s | 3728.2 |
  | `IntVector` arithmetic | 2379.8 | 2368.3 |
  | scalar `int[]` loop | 3766.3 | **4630.0** |

  Two expectations died here. A gather is **not** automatically slower than
  forty lane ops on this hardware, even when the table overflows L1. And the
  **scalar** loop is fastest of the three - 1.95x the vector arithmetic on the
  realistic span - because the Vector API's gather takes its index map as an
  `int[]`, so the index vector has to be stored and read back, and that spill
  is an artifact of the API rather than of the machine.

- **`IntVector`'s index-map overload exists only on `fromArray`, never on
  `fromMemorySegment`.** Enumerated, not assumed - the whole `from*`/`into*`
  surface is `fromArray(species, int[], int, int[], int)` and
  `fromMemorySegment(species, MemorySegment, long, ByteOrder)`, with no third
  form.

  **This bullet first drew the wrong conclusion from that fact, and the
  correction is the useful part.** It said a gather is therefore unreachable for
  a Varka kernel, because Varka's inputs are off-heap. The API limit is real but
  it is about gathering *from* an off-heap table - item 9's dictionary, which
  genuinely is off-heap. It says nothing about gathering an **on-heap constant
  table** with indices derived from off-heap data, which is what a calendar
  table would be: the column loads with `fromMemorySegment`, the index vector
  spills with `intoArray`, and the gather reads a table Varka owns. Measured in
  exactly that shape (`year(d) = 1998`, counted, column in a `MemorySegment`):
  **2070.8 M rows/s against the arithmetic's 1329.3, a 1.6x**. One API fact,
  two different situations, and reading them as one cost a real option.

- **Size a lookup table to the calendar's period and it needs no fallback.**
  ClickHouse's `DATE_LUT_SIZE` is `0x23AB1` - 146097, exactly one Gregorian era,
  anchored at 1900. Timestamps outside the window fall back to cctz, but day
  numbers do not: `shiftIntoLUTRange` moves them by whole 400-year cycles and
  adds `400 * cycles` to the year, because 400 years is the calendar's period. A
  table indexed by **day of era** makes that the only path - it covers every
  `int32` date with no fallback at all, and the reported year is
  `400 * (era - bias) + table[dayOfEra]`. Varka's prefix already computes that
  index - `emitEra` is the first thing it emits - so the table replaces
  everything after it. 571 KB as an `int[]`; a seven-year query touches 10 KB of
  it. This is the shape behind the 1.6x above.

  ClickHouse's 16-byte entry is six bytes of calendar - year, month, day, day of
  week, days in month - and ten of time zone. The calendar part packs into 26
  bits, so a four-field table is the same `int[]` as the year-only one and each
  further field is a shift and a mask after the one gather: the problem task 32
  solves with a shared prefix, solved with memory instead. ClickHouse also keeps
  the inverse, a 4800-entry first-day-of-month table that makes days-from-civil
  one lookup plus `day - 1`. Neither variant is measured here; both are listed
  under milestone 6's item 10, which also records what the rest of ClickHouse's
  date code was checked for and why none of it transfers.

- **Fusion reverses all of it, and that is the result that matters.** The table
  above times one field into an `int[]` - the shape where Varka's advantage is
  zero. Measured again as `year(d) = 1998`, counted, where the vector paths
  never leave a register:

  | | M rows/s | |
  |---|---|---|
  | gathered from the table, compared in lanes | **3999.8** | 2.8x |
  | arithmetic in lanes, compared in lanes | 1453.0 | 1.0x |
  | scalar lookup inside a vector kernel (spill, scalar, reload) | 1446.9 | 1.0x |
  | scalar loop end to end, no lanes anywhere | 848.1 | 0.58x |

  **The scalar loop that won the unfused test by 1.95x loses the fused one by
  1.7x**, 4630.0 down to 848.1: once the result has to be compared and counted,
  a per-row loop cannot keep up with lanes doing the same work sixteen at a
  time. And the hybrid an emitter would actually have to produce - spill the
  lane group, scalar-lookup, reload, carry on in lanes - is a **wash** with the
  arithmetic (1446.9 against 1453.0): the spill costs exactly what the lookup
  saves. So *emitting scalar ops with a lookup table buys nothing inside a fused
  kernel*, which is the question this was run to answer.

  What does win is the gather, by 2.8x, because it replaces forty lane ops while
  the compare and the count stay in registers. That is the lowering blocked by
  the missing `fromMemorySegment` index-map overload and nothing else - the one
  place where an API gap, not the hardware and not the arithmetic, is costing a
  measured 2.8x on the corpus shape.

  One caveat on reading the vector rows: all three vector paths end in
  `VectorMask.trueCount()`, and the arithmetic drops from 2368.3 unfused to
  1453.0 fused, which is more than a compare should cost. Whatever that is, it
  is paid equally by the gather and the arithmetic, so the 2.8x between them
  stands; the scalar comparison is unaffected by it and loses anyway. Also still
  unmeasured: writing to a `MemorySegment` rather than an `int[]`, null
  handling, and the batch-level fallback a 1950-2049 table needs for the
  `0001..9999` range SQL allows.

- An *exact* magic multiply on int lanes exists only for dividends under roughly
  **46341**, and the bound falls straight out of the two conditions rather than
  needing a search: worst-case `e ~ d` forces `2^k > d * v`, hence `M ~ v`, hence
  `v * M < 2^31` gives `v < 2^15.5`. Past that, use a **round-down** magic
  (`M = floor(2^k/d)`), which never overestimates the quotient, and pay a fixed
  number of carry steps - one compare and two masked adjustments each, on a
  remainder the algorithm usually wants anyway. Task 26 needed two such divisions
  (146097 and 36524) and found no exact form at any useful range for either; what
  made the rest exact was *restructuring* - splitting an era into centuries first
  drops the `/365` dividend from 146096 to 36524, under the bound. Reach for a
  different decomposition before reaching for more carries.
- **The `v * M < 2^31` bound is easy to check for the wrong variable and still
  ship.** Both `PLAN_TASK_34.md`'s leap-flag derivation and, independently,
  task 36's own local copy of it picked `M = 167773` for `/100` and `/400` by
  reasoning about the divisor rather than checking the bound against the actual
  dividend range: the biased year climbs to 46334 over the covered range, and
  `46334 * 167773` is over three and a half times past `2^31`, so a lane's
  signed 32-bit multiply wraps and the shifted quotient is silently wrong past
  roughly year 12400. No boundary list either task's differential used - 1900,
  2000, 2100, 2400, the usual century years - reaches that far, so the bug
  shipped past every targeted test both times; only an **exhaustive sweep of
  the real emitted kernel over the whole covered range** (16,777,216 days,
  seconds of wall time at these widths) found it, on the first and only day it
  could show up. The fix both times was the same round-down-plus-one-carry
  shape the paragraph above already prescribes for a large divisor
  (`M = 41943` at `k = 22`/`24`, the largest product `46334 * 41943` safely
  under `2^31`). The generalizable lesson: when a magic pair is *derived* by
  hand rather than found by the kind of exhaustive search
  `verify_long_lane_magic.py` runs, checking `M * dividend_max < 2^31` is a
  five-second arithmetic check worth doing explicitly before trusting the
  derivation's own prose - and an opt-in exhaustive sweep against the emitted
  kernel, not a curated boundary list, is what actually catches it if that
  check is skipped or miscounted.
- Those carries are not free, and the reason is the masked ops in them. Task 26
  predicted that its full-range variant would cost 5-12% over its narrowed one on
  op count alone (five ops on forty) and measured 14-24%: the five extra ops are
  masked adds and subtracts, which this project has separately measured at 2.3-2.9x
  an unmasked one. Count masked ops at their own weight when predicting.
- **`java.time` itself got 2.0x faster between JDK 17 and JDK 25**
  (`LocalDate.ofEpochDay(d).getYear()`: 236 against 479 M rows/s, same machine,
  `sql/varka/baselines/`). Any speedup quoted against a scalar `java.time` baseline
  has to say which JDK the baseline ran on, and a figure inherited from an older
  task may have a denominator two-fold different from today's. The same trap in the
  other direction: escape analysis scalarizes the `LocalDate` allocation in a tight
  loop, so a scalar calendar loop measured in a microbenchmark is far faster than
  the same code inside a query - task 26 predicted 15-30x over it and measured 3.7x.

- `VectorOperators` has no multiply-high on any lane type, and there is no sign that it
  will get one soon, so do not design around its arrival. Checked against JDK 25 (`javap`
  on `jdk.incubator.vector.VectorOperators`: `MUL` is the only multiply) and against
  openjdk/jdk master (code search for `MUL_HIGH` and `VECTOR_OP_MULHI`: no hits). C2 does
  have the operation internally - `MulHiLNode`/`UMulHiLNode` in `opto/mulnode.hpp`, used by
  `divnode.cpp` to lower scalar division by a constant and by the `Math.multiplyHigh`
  intrinsics, plus `MulHiLoLNode` for the fused 64x64-to-128 form - it is simply not exposed
  lanewise. The nearest request is JDK-8219881, "[vector] Optimized 32-to-64 bit vectorized
  multiply": an Enhancement, still Open, P4, filed February 2019, last touched October 2024,
  with `fixVersion` `repo-panama` rather than any release. The API does accept new integer
  ops when someone drives them - JDK-8338352 delivered `SADD`/`SSUB`/`SUADD`/`SUSUB`,
  `UMIN`/`UMAX` and the unsigned comparisons, all present in JDK 25 - so an RFE backed by a
  concrete workload is a real option, but it is a contribution to make, not a dependency to
  plan against.
- Because of that, full-range
  Granlund-Montgomery magic division is not expressible on int lanes - but a
  *range-narrowed* magic is: shrink the value first until the correctness condition
  (`v * e < 2^k`) and the no-overflow condition (`v * M < 2^31`) both fit in the low
  32 bits that `mul` does return. Mod-7 (task 14 follow-up, after a reviewer asked
  the right question): two 15-bit folds (`2^15 = 1 mod 7`) leave `v <= 32774` with
  the sign fixup, where `q = (v * 37450) >>> 18` is exactly `v / 7` with no final
  fixup - measured 1.6-1.8x the six-fold digit sum it replaced (which stays as a
  reference variant), ~9x lanewise `DIV` (no SIMD divide exists on x86; it
  effectively scalarizes), and ~57x a per-row `LocalDate` loop. The ~10-op-smaller
  method also cuts the per-task JIT warm-up above by ~28 ms per task.
- **The vector path is worth 6.5x over good scalar code, and the project had never checked.**
  `ChronoScalarOps` is `year(date)` as an ordinary Java loop over the same Arrow buffer, in the
  same 4096-row chunks, writing the same outputs: 284.2 M rows/s against the emitted kernel's
  1816.6 at AVX-512, both from the committed parity file. Until it was written, the only
  scalar anchor in that file was a per-row `LocalDate` loop, and the headline ratio was quoted
  against it. Keep the real
  baseline: "faster than `java.time`" and "faster than scalar arithmetic" are different claims
  and the second is the one that justifies the emitter.
- **`LocalDate.ofEpochDay(d).getYear()` in a tight loop does not allocate, and is a legitimate
  scalar baseline.** It measures 480.8 M rows/s, and C2 scalar-replaces the `LocalDate` -
  escape analysis sees it created and consumed in the same loop. It is worth stating because the
  opposite is the natural assumption and it was asserted out loud in this project before being
  checked. It also beats a hand-written 64-bit civil-from-days by 1.7x (480.8 against 284.2):
  `java.time` does the same Hinnant decomposition in 32-bit ints, and forcing everything through
  64-bit longs to buy exactness over the whole int32 range costs more than the exactness is
  worth in scalar code.
- **Spelling a constant division as `(x * M) >>> k` instead of `x / d` is worth 1.38x in scalar
  code** - 284.2 against 205.4 M rows/s for the same algorithm. C2 lowers `long / constant` to
  `MulHiL`, one instruction but a costlier one: it keeps the high half, and on x86-64 it
  clobbers RDX:RAX. An ordinary `imulq` plus a shift is cheaper wherever the product fits 64
  bits, which for a 32-bit dividend and a ~30-bit magic it does.
- **SuperWord will not auto-vectorize the calendar arithmetic, for two independent reasons,
  and neither is the `MemorySegment`.** The `(x * M) >>> k` form uses only `MulL` and
  `URShiftL`, both of which have vector counterparts (`MulVL`, `URShiftVL`), and the loop body
  was written branchless for exactly this reason. C2 still does not vectorize it:
  `-XX:-UseSuperWord` moves the number by 0.1% (284.1 against 284.4 in the A/B run). A
  four-case bisection -
  {`int[]`, `MemorySegment`} x {trivial body, full decomposition} - locates it: the trivial
  `int[]` loop gains 4.84x from SuperWord, the trivial `MemorySegment` loop only 1.09x, and the
  full body gains nothing on *either* (0.99x on `int[]`, 1.00x on the segment). The absolute
  figures in the committed parity file say the same thing: 26996.5 M rows/s for the trivial
  `int[]` loop against 6265.2 for the same body over a `MemorySegment`, and 287.1 for the full
  body on `int[]` against 284.2 over the segment - identical once the arithmetic is the real
  work. So the
  arithmetic is the binding constraint; segment addressing hurts as well but is not what stops
  it.
  `-XX:+TraceSuperWord` on a fastdebug JVM gives the mechanism exactly:
    - At the default `LoopUnrollLimit=60`, `SuperWord::transform_loop` is entered **zero
      times**. The body is too large to unroll, and with no pre/main/post structure there is no
      main loop for SuperWord to work on. It never gets asked.
    - At `-XX:LoopUnrollLimit=1000` it is entered four times and succeeds none. It builds packs
      - 8-wide `LoadI`, 8-wide `MulL` - and then
      `SuperWord::filter_packs_for_profitable` discards all of them
      (`WARNING: Removed pack: not profitable`), ending at `0 packs` and
      `SLP_extract did not vectorize`. The width mismatch is visible in the pack contents:
      eight 32-bit loads feeding 64-bit multiplies do not occupy one vector.
  The consequence for this project: the explicit Vector API is not a convenience over
  auto-vectorization here, it is the only route, and that is now measured rather than assumed.
  A lowering with no int-to-long mixing would clear the second gate, but the first is a tunable
  no shipped Spark can depend on.
- **The A/B that needs no tools**: run the case twice, once with `-XX:-UseSuperWord`. A loop
  SuperWord vectorized slows down; one it never touched does not move. That answers "did it
  vectorize" in a product JVM. Answering *why not* needs a fastdebug build - see the
  fastdebug section below.
- **Op counts bound a speed-up; they do not estimate one.** Three predictions in one milestone,
  all made from op ratios, all optimistic: sharing one decomposition across four calendar fields
  was predicted at 2.0x-3.2x and measured 1.5x; a scalar `year` was predicted within 2.5x of the
  vector kernel and came in at 6.5x; keeping fewer values live was predicted to help at narrow
  widths and lost at both. What the op-count model leaves out - stores, validity bookkeeping,
  loop control, dependency-chain latency - is routinely half the time or more. Predict a bound,
  say it is a bound, and measure.
- Masked lanewise ops and masked stores cost 2.3x-2.9x even when the mask is all-true:
  a runtime mask is opaque to C2 and a masked store never becomes a plain store. If
  masks carry no correctness (in-bounds accesses, invalid destination lanes declared
  undefined), run unmasked and keep validity in long words on the side.
- A vector held in a Java local across a loop pins one register for the whole body and
  blocks C2's rematerialization; ~32 such broadcasts collapsed throughput 7x. Emitted
  at each use, a loop-invariant broadcast gets hoisted when registers allow and
  rematerialized (one instruction) when they do not. Hoist only in measured-small
  regimes.
- Corollary for manual unrolling and software pipelining - the standard prescription
  for feeding a superscalar core, and a real gap, since C2 does not unroll Vector API
  pipelines: three of the measurements above price the experiment before it is run, and
  two of them cut against the prescription. (1) Unrolling by K multiplies the body's
  live temporaries by K, against a register file where ~32 pinned broadcasts already
  cost 7x - so unrolling and pre-broadcasting the loop's constants *compete* rather
  than compose, and K has to be varied together with the broadcast strategy, never
  alone. (2) It multiplies the loop method's op count by K against `GROUP_BUDGET = 16`,
  which exists because C2 compile latency is ~1 ms per vector op - affordable only
  since task 18 pays that once per shape rather than once per task. (3) It cannot
  rescue a chain built on lanewise `DIV`, which scalarizes: interleaving two scalarized
  chains is still scalar, so any such constant needs its range-narrowed magic first.
  None of this predicts that unrolling loses - it says the experiment has three known
  confounders. Registered as `PLAN_MILESTONE_4.md` task 25 (catalogue item 13);
  unmeasured as of this entry, and this bullet gets rewritten with the numbers when it
  is.
- Apply constant offsets *after* a mod, not before: `floorMod(days + 4, 7)` overflows
  int for days near `Int.MaxValue`, while `(floorMod(days, 7) + 4) mod 7` cannot.
  Negative inputs are where every strength-reduced mod goes wrong silently - a test
  range that never crosses zero proves nothing. The rule is about avoiding overflow,
  though, not an end in itself - it inverts when the oracle's own arithmetic already
  overflows on purpose. `next_day` (`PLAN_TASK_33.md`) computes `k - d` *before* the
  mod because Spark's `getNextDateForDayOfWeek` computes it in plain wrapping `int`
  arithmetic; reducing first disagreed with the row engine on 28 boundary cases in the
  planning pass's own check. Whose arithmetic the oracle is - exact (`LocalDate`,
  never wraps) or wrapping (plain Spark `int` math) - decides which way this rule
  points, and the reflex answer for one is the wrong answer for the other.
- A fixed-width species literal (`IntVector.SPECIES_256`) is not a safe way to get "the
  int species with half `LongVector.SPECIES_PREFERRED`'s lane count": under
  `-XX:MaxVectorSize=16` (this project's narrow-vector CI shape, 128-bit), no 256-bit
  registers exist, and the mismatch surfaces as a `VectorIntrinsics` bounds exception
  at a `fromMemorySegment` call site far from the real cause. Derive the matching
  species instead: `VectorSpecies.of(int.class,
  VectorShape.forBitSize(longSpecies.vectorBitSize() / 2))` tracks whatever
  "preferred" resolves to at the JVM's actual configured width, including the narrow
  shape. Any code pairing two lane types by a literal `SPECIES_*` constant needs the
  same check.
- Buffer alignment is not a null hypothesis once the buffer fits in cache: a 64-byte
  (AVX-512 register width) misaligned start costs 1.6-1.7x throughput at a 4096-row
  (one Spark batch, L1/L2-resident) working set, and 1.2x at 128-bit - reproducible
  across repeated runs at both widths (`VarkaMilestone4MeasurementsBenchmark`,
  `PLAN_MILESTONE_4.md` section 8). The same misalignment costs under 2% on a
  multi-megabyte streaming buffer, where DRAM bandwidth dominates and hides it -
  measure at the working-set size the real kernel runs at, not whichever size is
  convenient to allocate once. A 2-way unrolled kernel loses the same 50-60% as the
  non-unrolled one: unrolling does not hide a cache-line-split load.
- A materialization strategy's ranking can flip across the two vector widths this
  project already tests at. Packing a comparison mask straight to its output bitmap
  (skipping an intermediate int 0/1 column) wins by 1.16-1.18x at AVX-512 but *loses*
  by 1.40-1.51x at 128-bit (`VarkaMilestone4MeasurementsBenchmark`). A single
  same-JVM run at the development machine's native width is not enough evidence for
  a strategy that has to also hold at the narrow-vector CI shape.
- When a benchmark's K=1 case is fully unrolled straight-line source (the shape a real
  emitted kernel carries), the K>1 cases must be too - a small constant-bound runtime
  `for` loop over the op index is not a safe stand-in for hand-unrolled code, even
  though C2 usually fully unrolls tiny fixed-trip-count loops itself. Measuring
  `VarkaUnrollFactorBenchmark`'s K=2/K=4 cases through such a loop first showed K=4
  losing 30-60% on some shapes; rewriting them as straight-line interleaved code (same
  shape as K=1, just K independent lane groups instead of one) turned that into a
  reproducible +4-6% win on the shape where unrolling should help at all. The first
  number was an artifact of comparing a loop-shaped baseline against a straight-line
  one, not a real unrolling cost - a benchmark comparing "K=1" against "K>1" has to
  keep every other structural choice, including loop-vs-straight-line shape, identical
  between the arms.
- A hand-written kernel standing in for emitted code must not introduce a method
  boundary the emitted code does not have, and "it is a small private helper, it will
  inline" is not something to assume. Task 32 built a kernel to price sharing one
  civil-from-days decomposition across `year`/`month`/`dayofmonth`/`quarter`, wrote the
  decomposition as a `computeFields` helper returning a record of four `IntVector`s, and
  measured it 1.9x *slower* than the four independently emitted nodes - and task 32 was
  declined on that number. `computeFields` compiles to 376 bytecode bytes; C2's
  `FreqInlineSize` is 325, so it never inlined, so escape analysis never saw the record's
  allocation and its consumers in one compilation unit, so the record and its four vectors
  were really heap-allocated once per lane group. `VarkaLoopEmitter.emitChrono` emits zero
  call boundaries in its lane path. The kernel was measuring a Java abstraction the thing
  it modelled does not have. **Two cheap checks that would have caught it before the
  number was believed**: `javap -c -p` for any method holding lane arithmetic that exceeds
  325 bytes, and `-XX:+PrintInlining` (narrowed with
  `-XX:CompileCommand=option,Class::method,PrintInlining`) for a `failed to inline` inside
  the loop. Rebuilt hand-inlined, the same kernel runs 1.5x *faster* than the four nodes.
- An op-count ratio bounds a sharing win; it does not estimate one. The same task 32
  kernel shares ~45 of each field's ~50 vector ops, so four fields cost ~200 ops separate
  against ~65 shared - a 3x op-count ratio, which was registered as a prediction of
  2.0x-3.2x throughput. Measured: **1.51x-1.54x** at AVX-512 across three runs, the
  committed parity file's being 679.0 against 445.7 M rows/s. The half that went missing is
  everything the model ignored - four stores, four validity-bitmap read-modify-writes, the
  chunk prologue, loop control - none of which sharing touches. Predict a bound, not a number.
- Once a lane path has no calls left in it, `-XX:CompileCommand=inline` buys nothing, and
  it was never a fix anyway - Spark cannot require a `CompileCommand` on a user's JVM, so a
  flag that helped would only be a diagnostic pointing at a code change. Task 32 tested it
  properly before concluding that: `-XX:+PrintInlining` showed the two `VarkaVectorSupport`
  validity helpers genuinely failing to inline inside the shared loop
  (`NodeCountInliningCutoff` on one compilation, `callee is too large` on another - 212
  bytes of a four-arm switch on the lane width that a constant lane count would fold away),
  and forcing them in changed nothing at either vector width. Nor did forcing every Varka
  class (`inline,*varka*::*`). This is the third time an inlining flag has moved under 1% in
  the catalyst parity harness while the engine's JMH harness moves 50-190% on the same flag;
  a flag worth that much in only one harness is measuring the harness.
- A kernel can have two stable machine-code outcomes and no reachable reason. Task 32's
  shared kernel is bimodal at 128-bit: 121 ms or 85 ms, stdev 0 ms inside a run and 42%
  between runs, 3 fast outcomes in 14 runs. Neither forcing inlining, nor disabling
  on-stack replacement, nor rescheduling the body to keep fewer values live made either mode
  deterministic or shifted the distribution. **Report both modes; never average them** - the
  mean describes a state no run is ever in - and treat "which compilation the JVM landed on"
  as a first-class outcome rather than as noise to be smoothed away.
- Keeping fewer values live is not automatically faster, and the intuition is worth
  distrusting. Task 32 built a variant of the same kernel that hoisted the year assembly so
  three intermediates died early and stored each of four outputs the moment it existed
  instead of all four at the end. It lost at both widths (633.0 against 679.0 M rows/s in
  the committed parity file, 156.5 against 165.6 at 128-bit). C2's scheduler did better with
  the wider window than with the shorter live ranges - and the losing shape is the one the
  emitter naturally produces, which is worth knowing before assuming emitted code will match
  a hand-written ceiling.
- The same sharing win is width-dependent, and that is where task 17's register-pressure
  finding actually lives. At 128-bit the identical kernel is a wash: 1.06x in four runs of
  five, 1.50x in the fifth, stdev 0 ms inside each run and 42% between them - a
  compilation the JVM either finds or does not, so the two modes must be reported rather
  than averaged. Five live intermediates plus four outputs fit comfortably in 32 zmm plus
  8 dedicated mask registers and marginally in 16 xmm that must hold masks too; C1 refuses
  the 936-byte body outright at both widths ("out of virtual registers in linear scan").
  Task 17's `GROUP_BUDGET` result (raising it to keep two outputs' cross-output CSE in one
  method lost 4119.9 against 2928.2 M rows/s in the parity file of the day) looked like
  the same effect - until the rows reversed when task 46 moved the validity OR ahead of
  the vector work (the merged method now leads, 5482.1 against 4385.5 at AVX-512 and
  2566.5 against 1645.6 at 128-bit), which says that loss was a refused call in the wider
  method, not registers. Register pressure sets a ceiling on how much sharing can win; it
  does not decide the sign, a narrow-vector measurement is not optional for anything that
  shares live values, and a "known loss" is only known until the emitter around it moves.

## Every operator the plans rely on is one instruction; two species in one JVM is a box per iteration

Established in September 2026 from the product JDK 25's own C2 output on this machine (AMD Ryzen AI
9 HX PRO 370, `UseAVX=3`, preferred int species 512 bits), with hsdis borrowed from the fastdebug
build's `support/hsdis` directory via `LD_LIBRARY_PATH` - the product JVM loads it from there, so
the assembly is production C2's. Probe, logs and scripts: a `Probe.java` with one loop per
operator, `-XX:CompileCommand=print,Probe::op_*`, and a script that splits the log per nmethod and
counts mnemonics and Java call sites.

**The operator table.** Every operator the six codebase reviews put into plans is intrinsic at 256
bits and compiles to the instruction one would hope for, with no call back into Java:

| operator | instruction | for |
|---|---|---|
| `SUSUB` on bytes (256 and 128) | `vpsubusb`; `vpcmpneqb` to a k-mask and `kortestd` for `anyTrue` | item 8's shape mask |
| unsigned compare, ints and longs | `vpcmpltud` / `vpcmpltuq` to a k-mask, `kmovq` for `toLong` | leap hash, shape mask |
| `selectFrom` on 8 ints | `vpermd`, alone | eight-entry tables |
| `rearrange(ix.toShuffle())` on 8 ints | `vpermd` plus four wrap ops (`vpcmpeqd`, `vpsubd`, `vpblendmd`, `vpand`) | use `selectFrom` instead |
| index-map gather, 8 ints | `vpgatherdd` plus a five-op Java-side index check and `kxnorw` | item 10; the check is not removable through the API |
| `LongVector.mul` by a constant, then shift | `vpmullq`, `vpsrlq` | task 49; native here because of AVX-512DQ+VL, a three-multiply emulation on plain AVX2 |
| byte `rearrange` with a constant shuffle | `vpermb` | item 8's three-rows-to-long-lanes compaction; needs VBMI, present |
| `IntVector.mul` then shift | `vpmulld`, `vpsrld` | the baseline everything else is measured against |

The x86 match rules in `src/hotspot/cpu/x86/x86.ad` (`match_rule_supported_vector`) agree with all
of it, but they say what *can* match; only the disassembly says what a given loop got.

**The finding that outranks the table.** A first probe used `SPECIES_256` and `SPECIES_128` of the
same lane types in one process, and in that JVM the byte, permute and gather loops each carried a
heap allocation per iteration - a TLAB bump, a mark-word store, an `int[8]` payload, and the loaded
vector written into it - while the multiply and compare loops did not. The identical methods copied
into a class touching one species were clean, and the first probe became clean when its 128-bit
methods were never called. `-XX:CompileCommand=PrintInlining` names the mechanism: in the polluted
JVM the shared `ByteVector` templates inline bimorphically, "callee changed to
`Byte128Vector::lanewise`" beside the `Byte256Vector` path at the same call sites, so the receiver
must exist as an object for the other branch and the box survives. Same loops, same flags,
polluted against clean: saturating subtract 2.06 against 0.32 ns per vector (6.4x), `selectFrom`
3.06 against 0.24 (12.8x), gather 6.47 against 2.39 (2.7x), long multiply unchanged.

**Assert it as a rate, not as sites** (task 55, `PLAN_TASK_55.md`). A count of allocation sites
in the disassembly cannot separate a per-call setup object from a per-iteration box:
`ChronoVectorOps.vectorFourFields` carries four `NativeMemorySegmentImpl` views C2 never
scalar-replaces, one allocation per call, and an allocation's slow path jumps backwards to its
retry point, so a backward-branch range is not a loop. The assembly suite therefore measures
`ThreadMXBean.getThreadAllocatedBytes` around a thousand calls at steady state and allows one byte
per row; a box is at least 5 per row, a per-call view under 0.25. And whether a bimorphic template
boxes depends on the shape *and the order the profiles filled in*: a `selectFrom` lookup boxes
only when the second species ran hot first, an index-map gather boxes under either order at both
widths. The suite's positive self-test is the gather for that reason.

What this means here. The emitter and every kernel use `SPECIES_PREFERRED` only, and the 128-bit
gate is a separate JVM under `MaxVectorSize=16`, so production is safe by construction - keep it
so: never introduce a second species of a lane type, not for a half-width load, not for a
benchmark that shares a JVM with anything else. The catalyst test harness is not safe, whatever
this note once said: the suites that check their answers at a lanes override - the budget,
validity, composition-fuzz and division suites - run a second int or long species in the JVM every
Varka suite shares, and any kernel compiled there afterwards can box every operation. Answers stay
right, so only a test whose verdict is a JIT outcome notices, and such a test runs in a JVM of its
own: the warm-up suite's compile tests failed one full run in three by the suites' order until
they forked `VarkaKernelWarmupProbe`, as the assembly and cliff suites fork theirs
(`PLAN_TASK_209.md` 13.2). Taking the second species out of the shared JVM is
`SCOPE_MILESTONE_7.md` item 69.
`VarkaMilestone4MeasurementsBenchmark` did exactly that with its half-width int species in a
`forks = 0` JVM, which is one named cause of the engine harness's degraded state (the debt register
in `PLAN_MILESTONE_4.md`). Two tells, either sufficient: an allocation inside a kernel loop body in
the disassembly (task 55 makes it an assertion), and a "callee changed to" line naming a second
species class in `-XX:+PrintInlining` output.

### The runtime half: the evaluator samples allocation, because nothing else can see a box

A boxing kernel is correct, so the ghost fallback, the differential suites and the fuzzer are all
blind to it; the assembly suite (task 55) catches it in the test JVM, and the test JVM is clean by
construction. `VarkaKernelEvaluator` therefore samples the same signal at run time: bytes the thread
allocated across `run`, from `ThreadMXBean`, on a schedule that skips the JIT warm-up (an
interpreted or C1 Vector API loop allocates every vector, which C2's escape analysis then removes -
sampling batch 1 would report boxing that is about to stop; the evaluator's wiring test watches
exactly that, every early sample suspect and the tail clean at a constant 520 bytes per call). How
long the warm-up lasts is the machine's business: this laptop had C2's loop inside 300 batches of
1024 rows, GitHub's runner took 1112 - so the wiring test runs rounds of batches until a whole round
samples clean rather than asserting a fixed tail, and a host that slow sees the default schedule's
first two samples (512 and 1024) both inside the warm-up, which is one spurious warning. Batch 512
first, then powers of two and every 4096th - two million rows at the default batch size; suspect
above 4 KB plus one byte per row; one warning per task on two consecutive suspect samples, a
`numSuspectAllocationSamples` metric, and a `KernelAllocation` JFR event on every sample. The decisions live in `VarkaAllocationSampler` and are unit-tested against a loop that
allocates on purpose - the positive case is not a polluted Vector API loop, because making the shared
test JVM box would degrade every vector suite after it.

## An overflow check is nearly free in wide lanes and expensive in narrow ones

Task 63's ANSI check is a sign test: four lanewise ops and a compare for `+`
and `-`, one compare for unary minus. `VarkaArithmeticBenchmark` prices it as
an A/B on one node - `checkIntOverflow` on against off, the same IR either way
- and the two vector widths disagree about what it costs.

At AVX-512 the checked add runs at 19180.2 M rows/s against 19449.2 unchecked,
which is under 2%. At 128 bits the same pair is 13780.6 against 18776.2, about
27%. Unary minus, which is one compare rather than five ops, still costs 32% at
128 bits (12759.0 against 18768.8) and about 1% at AVX-512.

The masked body at 128 bits is where it stops being a surcharge. A checked add
over a column with nulls runs at 6522.9 M rows/s against 19073.8 with the check
off - the arithmetic and its check are unchanged, so what the masked arm adds is
the disposal: `emitGuardCollect` turns the overflow mask into a `long`, ANDs it
with the node's validity word and ORs it into the batch accumulator, and those
mask-to-long conversions do not vectorize the way the lane ops do. `try_add`,
which disposes of the same mask by narrowing the word instead, is slower again
(3780.6 at 128 bits).

Two things follow. First, the width matters more than the op count when you
predict what a mask-producing addition to a kernel will cost, so predict for
both widths or say which one you predicted for. Second, a check that costs 2% in
the wide lanes can cost two thirds of the throughput in the narrow ones, and the
narrow number is the one that tells you whether the mask disposal - not the test
itself - is what you built.

## A local nothing reads is not free, and its cost is width-dependent

The measurement above says 3.9% for the AVX-512 masked check. The first run of
the same benchmark said 19.0%, and this file said so, and the difference is one
local variable that no instruction ever loaded.

Task 63's checked arithmetic was allocated a `guardTmp` slot by a predicate
written for task 52's range guard, whose emitter genuinely parks a value there.
The arithmetic emitter does not: it parks its operands and result in
`intArithTmp`, and negation reads its operand back with `dup`. So every checked
node reserved a slot, shifted every local after it, and used none of it. A
review found it by reading, not by measuring, and called it dead code.

Removing it moved the AVX-512 masked row 26.1% - from 14706.5 to 18542.2 M
rows/s - and the 128-bit row not at all (6459.9 to 6522.9, inside the run-to-run
band). A dead local costing fifteen points of throughput at one width and
nothing at the other is a register-pressure signature: the wide body holds more
live vector values, so it is the one with no headroom, and one more local is
what tips it into spilling.

Three things to take from it. Dead locals are worth removing on principle and
not only for tidiness, because the JIT does not always eliminate them.
Width-dependent effects can invert which body looks expensive, so a slot change
wants a regeneration at both widths rather than an argument that it cannot
matter. And when a review calls something dead code, the honest response is to
regenerate the numbers that describe the bytecode it was in - `sql/varka/AGENTS.md`
already says so, and here it was the difference between a real finding and an
attribution that was mostly an artifact.

## This machine's AVX-512 is 256 bits wide, and every "512-bit" number in this repo is really a 256-bit one

**A probe that claimed to measure this was reading the frequency control, from task 62 until
11 September 2026.** `dev/varka_bench_surface.sh` built the `datapath` line in every committed
surface file from `Canary.compute` at `-XX:MaxVectorSize=32` and `=64`. That loop is a scalar
xorshift over one `long`, and `Canary`'s own javadoc says so - "Frequency-bound and touches no
memory, so it moves only if the clock does. This is the control." A flag about vector width
cannot move it, so the ratio read 1.00 on every machine ever measured: an AMD EPYC 7763 with no
AVX-512 at all, an EPYC 9V74, an Intel Xeon 6973P-C with `UseAVX=3` and `MaxVectorSize=64`, and
this laptop, all identical. The conclusion in this section's heading is **not** affected,
because it rests on the ladder below rather than on that probe - but a gate built on the probe
would have rejected every machine forever, which is how it was found.

The replacement is `dev/varka_canary/Datapath.java`, and the lesson in its shape is worth more
than the bug: **measure lanes per nanosecond, not operations per second.** A full-width unit
retires a 512-bit operation in the time a 256-bit one takes, so doubling the species doubles the
lanes; a double-pumped unit takes twice as long for twice the lanes and the rate is flat. Rates
in operations per second cannot tell those apart, and neither can anything scalar.

**And carry a positive control in the probe itself.** The new one takes a third reading at 128
bits, because 128 to 256 must show a real doubling on any machine with a 256-bit datapath. On
this laptop it reads 2.02x against 1.14x for 256 to 512 - the first says the probe can see a
doubling, the second says this machine does not have one, and only the pair is evidence. A probe
that has never been observed reading anything but "no" has not been shown to be able to say
"yes", which is exactly the state the old one was in for the whole of its life.

**The pool then said "yes", and said it on an AMD.** Eighteen dispatches of
`varka-surface-benchmark.yml` reached the probe on 11 September 2026. The control read 2.00 to
2.04 on every one of them - five CPU models from four families, so the probe is validated well
away from the laptop - and the 512-bit reading split three ways:

| CPU | runs | `avx512` flags | 256:128 control | 512:256 |
|---|---|---|---|---|
| AMD EPYC 7763 (Zen 3) | 8 | none | 2.00 - 2.01 | **1.00** |
| AMD EPYC 9V74 (Zen 4) | 3 | none | 2.00 | **0.91 - 1.00** |
| Intel Xeon Platinum 8573C | 4 | full set | 2.00 - 2.03 | **1.34 - 1.36** |
| Intel Xeon 6973P-C | 2 | full set | 2.00 - 2.01 | **1.33 - 1.35** |
| AMD EPYC 9V45 (Zen 5) | 1 | full set | 2.04 | **1.99** |

**"Full width" is not a yes-or-no property, and that is the part worth carrying forward.** Both
Xeons have the entire AVX-512 flag set, `UseAVX=3` and `MaxVectorSize=64`, and still read about
1.34. The limit is issue ports, not datapath: Intel's server cores retire 256-bit integer vector
ops on three ports, and a 512-bit op takes a fused pair plus the third, so the issue rate falls
from three per cycle to two while the lanes double - 2 x 2/3 = 1.33 predicted, against 1.33 and
1.35 measured on two different Intel generations. Zen 5 has four 512-bit-native vector pipes and
loses nothing, hence 1.99. So the useful question about a machine is never "does it have
AVX-512" and not even "is the datapath 512 bits", but **how many 512-bit operations it issues
per cycle compared with 256-bit ones** - which is what a lanes-per-nanosecond ratio measures
directly and what no flag reports.

The 6973P-C row is worth one more sentence, because it is the same machine the broken probe had
read 1.00 on. Two probes, one machine, 1.00 and 1.33: whatever the first was measuring, it was
not this.

**A gate in one GitHub job does not gate another job, and the workflow shipped for three days
believing it did.** Every GitHub-hosted job runs on its own fresh ephemeral VM, so a probe in a
`gate` job describes the `gate` VM and nothing else. `varka-surface-benchmark.yml` was split into
`gate` / `build` / `measure` to keep a cold build off the scarce full-width runner, which worked -
and in the same change the datapath gate stopped constraining the measurement. The chains run of
12 September 2026 passed the gate on an EPYC 9V45 at ratio 2.00 and then measured on an EPYC 9V74
at ratio 1.00, `MaxVectorSize: 32`. Because the measure job's `name:` interpolated
`needs.gate.outputs.cpu`, GitHub's own UI attributed the run to the 9V45 throughout.

Three lessons, in descending order of how far they travel:

- **A results file that re-measures its own claims is worth the duplication.**
  `dev/varka_bench_surface.sh` probes on the machine it runs on and writes `datapath:`, `cpu:`
  and `MaxVectorSize:` into every file. That is the only reason this was caught rather than
  published; every other surface, including the job name, said 9V45.
- **Anything a job inherits from another job is a claim about a different machine.** `needs.<job>.outputs`
  carries facts about hardware across a boundary that does not preserve them. Environment,
  caches and artifacts cross; the machine does not.
- **Duplicating a gate is worse than moving it.** Keeping `gate` upstream and adding a second
  probe inside `measure` would demand that *both* VMs be full-width, turning a one-in-eighteen
  chance into about one in three hundred. The probe is now one script, `dev/varka_datapath.sh`,
  taking `--require 512|any`, run by the job that measures; the survey job keeps the same script
  with `--require any`, because a census that aborts on the machines it is counting cannot count
  them.

Measured, not read off a spec sheet. Task 43's committed op-count ladder - a single-output loop
from 20 to 248 `IntVector` ops - was run at three widths on the development machine (AMD Ryzen
AI 9 HX PRO 370, Zen 5 mobile, JDK 25.0.4), by setting `Test / javaOptions +=
"-XX:MaxVectorSize=N"` to 16, 32 and 64:

| ops | 128-bit | 256-bit | 512-bit |
|---|---|---|---|
| ns/row/op | 0.0163-0.0212 | 0.0070-0.0074 | 0.0072-0.0078 |

| step | lanes | speedup |
|---|---|---|
| 128 -> 256 | doubled | **2.48x** |
| 256 -> 512 | doubled | **0.95x** |

**Doubling the vector width from 256 to 512 bits buys nothing here - it is very slightly
negative.** The chip has the whole AVX-512 instruction set (`lscpu` lists `avx512f` through
`avx512_vp2intersect`) and HotSpot picks `MaxVectorSize=64`, so everything *looks* 512-bit; the
execution units behind it are 256 bits wide and 512-bit operations are issued as two halves.
128 -> 256 is even superlinear at 2.48x, which is the per-lane-group fixed costs amortising over
twice the rows on top of the real datapath widening.

**What this means for numbers already committed.** Every result in milestone 4 labelled AVX-512
is a 256-bit-datapath result. None of the *decisions* move, because each is a comparison between
two lowerings at one fixed width - task 45's validity fill, task 53's month numerator, task 48's
elision, task 43's own flatness - and both arms of every comparison ran on the same hardware.
What is overstated is the label: "at both widths" has meant "at 4 lanes and at 16 lanes issued
through a 256-bit datapath", not "at two datapath widths".

**And it means there is headroom on other hardware - measured now, on the runner pool.** The
same jar on an AMD EPYC 9V45 (Zen 5 server) turns that 0.95x into 1.99x on the probe, because
its four vector pipes are 512 bits wide natively. An Intel Sapphire or Emerald Rapids part gives
1.35x rather than 2x for the port reason above, so it is real headroom but a third less than the
lane count suggests. That is still the cheapest performance work available to this project and
it requires no port: the same jar, a different instance type - and the instance type to ask for
is Zen 5 or newer AMD, not Intel.

**The method generalises: to find out whether a machine's widest vector is real, measure three
widths, not two.** Two points cannot distinguish "the wide path is not helping" from "the wide
path does not exist", and `lscpu` and `MaxVectorSize` both report the instruction set rather than
the datapath. A committed op-count ladder is the right instrument because its x-axis is asserted
off the class file, so the same shapes are being compared at each width.

**`-XX:MaxVectorSize=32` is arguably the honest "wide" setting on this laptop**: identical
throughput, smaller emitted bodies, shorter compiles.

## A default that is wrong at one width may be wrong for a reason the width creates

Task 46 gave the per-group validity write a width-specialised helper and made it
the default, on the grounds that the general form is 212 bytes and C2 refuses to
inline it inside a fused loop. Task 70's review then found the default losing
8-9% on a single-field kernel, and milestone 4 opened a row to key the choice on
the number of validity writes a loop body makes. Measured, that rule is right at
one vector width and wrong at the other two, and the justification the option was
added on does not apply to the shape at all.

**Three things were excluded from the JVM's own output, and the third is the
design's own premise.** The compiled masked loop inlines the writer in *both*
arms - neither disassembly contains a call to it, and `PrintInlining` is
symmetric. The arm that loses is the *smaller* one, 642 instructions against 654.
And it is the arm doing the *cheaper* address arithmetic, shift-and-mask against
signed divide and modulo. What differs is register-file placement: ten extra
general-to-vector moves in one arm against ten more general-register ALU
operations in the other.

**The width is not a modifier on the effect; it creates it.** Run at three
widths, the loss exists at 4 lanes and nowhere else - at 8 and 16 the specialised
helper wins at every write count by 8 to 36%. Four lanes is the only width in
that set where a validity group does not own whole bytes: four bits, so two
consecutive groups read-modify-write the same byte and serialise on it. That
serialised chain is the regime in which the register-placement difference decides
the outcome; without it, the cheaper arithmetic wins as designed.

Three habits:

- **Before keying a rule on a quantity, check whether the answer has the same
  sign everywhere the rule will run.** Two shapes at one width proposed "key on
  the write count". A third width showed the question does not have one answer,
  so the rule would have been correct on the shapes it was fitted to and wrong on
  most of the engine.
- **A width is a cheap third sample and often a qualitative one.** Two widths
  disagreeing looks like noise or a modifier; a third turns it into a mechanism,
  because widths differ in structure - here, whether a group's bits fit whole
  bytes - and not only in throughput.
- **When the cause is a regime rather than a constant, look for the task that
  removes the regime.** The lever here was not the helper choice but "one
  validity write per word", already scoped and already gated on this very
  decision. Tuning a constant inside a loop shape that is scheduled for
  replacement spends effort twice and leaves a fitted threshold behind.

The postscript is a tooling one. `VarkaEmitDump`'s option parser read any string
that was not `"true"` as `false`, so `--options validityByWidth=on` silently
selected the arm it was meant to exclude, and two runs measured the same arm
before identical byte counts gave it away. An option parser that coerces rather
than refuses turns a typo into a measurement of the wrong thing.

## A reciprocal multiply is not a division, even when the error bound says it is

Task 88 asked whether `v / d` can be lowered through double lanes, which the
Vector API can do where it has no integer divide at all. The error bound is
encouraging and it is not the whole answer.

For a dividend that is **not** a multiple of the divisor the bound settles it:
the true quotient lies at least `1/d` from every integer, the computed value is
within `|v| * 2^-52` of it for `trunc(v * fl(1/d))` and `2^-53` for
`trunc(v / d)`, so truncation cannot cross an integer while `|v| < 2^52` or
`2^53` respectively. Note which quantity is bounded: the **dividend**, not the
divisor. An earlier draft of this project's own plan wrote the rule as "any
divisor below about 2^21", which is not what the arithmetic says.

For a dividend that **is** an exact multiple the bound says nothing, and the two
forms part company. The divide returns `k` exactly, because the quotient is
representable and IEEE division is correctly rounded. The reciprocal multiply
computes `fl(k * fl(1/d))`, and if `fl(1/d)` rounded *down* the product can land
on the double just below `k`, so truncation gives `k - 1`. The textbook case is
`49 * fl(1/49) = 0.9999999999999999`. Varka's own `/146097` is another, and it
fails at the very first multiple.

The rule is closed-form, so nothing here needs sampling:

    fl(1/d) >= 1/d  ->  exact at every multiple below 2^53
    fl(1/d) <  1/d  ->  exact at every multiple iff trunc(d * fl(1/d)) == 1

**And it is keyed on the dividend range, not on the divisor.** The same `/146097`
is inexact over the era step's `w`, where `146097` is a reachable dividend, and
exact over the Julian century's `quadDays = 4*doe + 3`, whose dividends are only
the values congruent to 3 mod 4 - and `146097` is congruent to 1. A table of
"divisors the reciprocal form may serve" cannot express that; a table of
(divisor, range) pairs can. `sql/varka/plans/verify_double_division.py` is the
check, with every row's verdict pinned so a change fails the run rather than
drifting.

The practical rule when reaching for this lowering: use the divide unless the
closed form admits the reciprocal over the exact range the lowering sees, and
write the range into the table beside the divisor.

## Converting to a wider element type splits a vector into disjoint halves that rejoin with an OR

Lowering an int division through double lanes needs the int vector in double
lanes and the quotient back in int lanes, and `convertShape` is the call for
both. Three things about it are worth knowing before emitting any of it, because
each one is cheap to check and expensive to get wrong in bytecode.

**The parts.** An expanding conversion - one whose element type gets wider, so
the lane count halves - takes parts `0 .. M-1`; a contracting one takes
`-M+1 .. 0`. For the int-to-double round trip `M` is 2, so the halves go out as
parts `0` and `1` and come back as parts `0` and `-1`. Pairing `0` with `0` and
`1` with `-1` is what makes the two results cover different lanes.

**The join.** Each contracted half holds its own lanes and **zero** in the
others, so the two halves rejoin with a plain `or`. No `rearrange`, no blend, no
mask - which is the difference between a seven-op lowering and one that is not
worth emitting. `dev/varka_canary/DoubleDivProbe.java` prints all of this in
about twenty lines and is the thing to run before writing the emitter, not after.

**The species names.** `SPECIES_256` names the vector's **total width**, not its
lane count, so `IntVector.SPECIES_256` is eight int lanes and
`DoubleVector.SPECIES_256` is the four double lanes occupying the same register.
A conversion between them therefore uses the *same* constant name on both sides,
and `SPECIES_PREFERRED` pairs with `SPECIES_PREFERRED` because a JVM's preferred
shape is one shape for every element type.

## An exact quotient makes the round-down carry dead code, and the op counter has to see it

A Granlund-Montgomery magic rounds down, so every site that uses one may need a
correction: compare the remainder against the divisor, add one to the quotient
and subtract the divisor from the remainder under that mask. Three lane ops. A
division that is exact over the site's range leaves a remainder strictly below
the divisor, so the comparison is never true and those three ops are dead rather
than merely redundant. An exact lowering that keeps them is not wrong, but it
prices itself with work it does not need - and an A/B run that way measures the
wrong thing.

The related trap is in the measurement rather than the emission.
`dev/varka_emit.sh --table` reported each body's `IntVector` invocation count,
which was the whole answer while every lowering stayed on the int lane. A
lowering that moves work onto double lanes shrinks exactly that number while
adding conversions and divides on two other types, so the table read the move as
a **saving** of twelve ops when it was a cost of six. The tool now sums every
vector type. The general form of the lesson: an op counter scoped to one type
stops being a cost model the moment a second type appears, and it fails
silently and in the flattering direction.

## The Vector API's math lanes match no scalar library's bits on any host, and a probe that lets C2 fall back measures `Math` against itself

`DoubleVector.lanewise(VectorOperators.SIN)` and its siblings are not Java: C2
lowers them to a vector math library - Intel's SVML on x86 (`libjsvml.so`,
`__jsvml_sin8_ha_z0` at AVX-512, `__jsvml_sin4_ha_l9` at AVX2), SLEEF on
aarch64 (`sind2_u10advsimd`), which is why the JDK ships
`legal/jdk.incubator.vector/sleef.md`. Whether those lanes agree with the
scalar call Spark's row engine makes is a property of the host's libraries, and
`dev/varka_canary/MathLaneProbe.java` measured it on 19 September 2026 on the
three machine classes Varka runs on, with the outputs committed beside it.

**The answer: on no host and for no operator.** Against the library Spark calls
- `java.lang.Math` for the trigonometric family, `cbrt`, `atan2`, `hypot`;
`StrictMath` for `exp expm1 log log10 log1p pow` - every bound operator differs
on up to thirteen percent of ordinary inputs, by one ULP, two for `log10` and
for `tanh` on x86; the three library builds differ from one another as well;
and on aarch64 `Math` itself is fdlibm for everything but `sin` and `cos`,
where x86 has Intel's scalar intrinsics for nine functions. The only exact
lanes are the operators the JDK has no symbol for (`pow` at AVX2, `tanh` on
NEON), which run the scalar call per lane at scalar speed. `SCOPE_FUNCTIONS.md`
section 3 tabulates it and `SCOPE_MILESTONE_7.md` item 36 holds the decision it
forces: a ULP contract, an emitted fdlibm, or a decline, for the family as a
whole.

**The first reading of this probe said the opposite**, and the way it was wrong
is worth more than the table. It reported the lanes equal to `java.lang.Math`
bit for bit, with the JDK's log showing every SVML symbol resolved. Both facts
were true and the conclusion was not, for two reasons that each substitute the
scalar fallback silently, producing correct answers at scalar speed:

* **The operator must be a compile-time constant at the call site.** The probe
  passed `VectorOperators.Unary op` as a method parameter; C2's log said
  `** missing constant: opr=LoadL` for the library call and compiled
  `defaultImpl` instead - a per-lane `Math.sin` loop, which agrees with `Math`
  by construction. Varka's emitted kernels name each operator as a `getstatic`
  of a `static final` field, which is a constant; a shared helper that took the
  operator as an argument would not be.
* **The library binding is lazy, and C2 folds it only if it exists when the
  method compiles.** The JDK resolves an operator's symbol on its first use and
  keeps it in a `@Stable` table. A method compiled while an operator it names
  is still unbound keeps a memory load for that entry, and with it the
  fallback, until something else forces a recompile. Warming fourteen operators
  one after another through one method reproduced this on two of three hosts,
  for different operators on each; touching every operator once before any is
  hot removed it.

Neither failure shows in the results, since the fallback's answers are right.
What shows it is the control the probe now carries: the same run with
`-Djdk.incubator.vector.VectorMathLibrary=java`, which must come out several
times slower per element and must agree with `Math` on every lane. To see the
binding rather than infer it, `-Djdk.incubator.vector.DEBUG=true` prints the
library and the symbol per operator, and
`-XX:+UnlockDiagnosticVMOptions -XX:+PrintIntrinsics` prints `late inline
succeeded` for `libraryUnaryOp` or the reason it refused. A lane result quoted
without that control is a number about whichever code C2 happened to compile.

## SLEEF and OpenVML, read for Varka: no integer division anywhere, and what does transfer

Read 19 September 2026, before anyone proposes them again. **SLEEF** (v3.9,
Boost 1.0) is a vectorised libm - forty-odd transcendentals in 1-ULP and 3.5-ULP
tiers, a DFT, quad precision - over per-ISA helper headers for SSE2, AVX2,
AVX-512F, AdvSIMD, SVE, RVV, POWER and s390x. **OpenVML** (2014-2015, BSD-3) is
an OpenBLAS-style clone of Intel's VML: thirty whole-array element-wise
functions over Cephes-era Horner polynomials, per-CPU kernels chosen at build
time, unmaintained since Haswell.

**Neither has integer division, a multiply-high, or a remainder.** SLEEF's
integer vocabulary is add, sub, neg, the bitwise ops, shifts, eq/gt and select;
its `xfmod` is floating-point and iterates up to 21 rounds in double-double.
OpenVML's only integer operations are the `epi32` add, sub, compare and shift
inside its sine's range reduction. Varka's magic multiply and double-lane route
stand on compiler literature (Granlund and Montgomery), not on the vector-math
libraries. **SLEEF never converts a 64-bit integer either**: it pairs each
double lane with a 32-bit int and so never meets the long lane's problem.

What SLEEF does contribute, by task:

* **A third lowering for the 64-bit divide on AVX2.** `commonfuncs.h`'s
  `vtruncate2_vd_vd` and `vfloor2_vd_vd` build a 52-bit truncation from 32-bit
  converts, because SSE2 has no `roundpd`: scale by 2^-31, `cvttpd2dq`, scale
  back with an FMA, truncate the remainder, subtract. The same idea splits a
  64-bit value into 32-bit halves and converts each with `cvtdq2pd`, which AVX2
  has - exact to 2^53, wider than the `0x4330` identity's 2^52, and no exponent
  bit to reason about. `SCOPE_MILESTONE_7.md` item 37. `vrint2_vd_vd` is the
  2^52 add-and-subtract round-to-nearest the magic form already uses, so that
  part is confirmed prior art.
* **Task 28's widen and narrow sequences, per ISA** (`helperavx2.h`,
  `helperadvsimd.h`). SLEEF keeps one int species per FP species and converts
  at the boundary, which is the shape task 28 settled on. On AVX2 widening is
  `cvtepi32_epi64`; **narrowing int64 to int32 has no instruction** and is a
  `shuffle_ps 0x08 / 0x80` pair and an `or`; mask narrowing is
  `permutevar8x32` over the even lanes. On AdvSIMD: `vmovl_s32` / `vmovn_s64`,
  and `vuzpq` / `vzipq` for masks. Recorded in `PLAN_TASK_28.md` 3.1.
* **Expectations keyed per ISA.** `autovec.c` asserts the compiler took the
  vector path with FileCheck lines per ISA - `// CHECK-AVX2: _ZGVdN4v_...` - not
  one universal assertion. That is the shape the assembly gate wants on a
  runner pool spanning five CPU families, and task 124's baseline per AVX level
  already says so. Recorded in `PLAN_TASK_124.md`.
* **Named accuracy tiers.** `_u05`, `_u10`, `_u15`, `_u35`: every function
  ships under an explicit ULP bound and the caller picks by name. The
  precedent for a standard mode that is a named tier with a stated contract,
  and for a kernel's exactness range being part of its identity.
* **An output digest per function.** `hash_finz.txt` commits a SHA-256 of each
  function's outputs over a fixed input set, one line per (function, tier).
  Varka's fuzz digests are per grammar and lane; per expression, a drift would
  name its expression.
* **Edge sampling.** `tester2` spends a fixed share of draws on
  `nexttoward0(+-0, k)` and `nexttoward0(+-inf, k)` for small random `k` -
  dense at the boundaries of the representable range, uniform elsewhere.
  `VarkaIrGrammar` does this by hand; the long lane's 2^52 and 2^53 edges are
  where the systematic form would earn its keep.
* **Two rules SLEEF's code branches on and Varka's proofs assume away.**
  `ENABLE_FMA_DP`: an exactness argument written for one rounding per operation
  is wrong once the multiply and add fuse, and the split-32 conversion above
  uses an FMA. And the dispatcher substitutes kernels on `cpuSupportsAVX2() &&
  cpuSupportsFMA()` - feature predicates, never a level - which is the review's
  objection to `UseAVX` made structural.

Not worth borrowing: `xfmod`, Payne-Hanek reduction, the DFT, the coefficient
generator (until Varka writes an approximation of its own), and OpenVML entire.
For Spark's FP functions the Vector API's operators are SLEEF or SVML already;
see the section above.

## A vector divide is a divider, whatever the lane width; a multiply-high is not

Task 149 (20 September 2026). The int-lane constant division converted each
half of its lanes to doubles and divided there: seven operations, and the
committed rows said it beat a scalar loop by 1.3x at 512 bits and lost to it at
128. Two things about that were worth learning once.

- **The cost was the two `vdivpd`, not the seven operations, and it does not
  shrink with the width.** A vector double divide's throughput is set by the
  divider unit; at four lanes it costs nearly what it costs at sixteen, so a
  form built on it is four times more expensive per row at 128 bits than at
  512, while a scalar loop that C2 has strength-reduced to a multiply-high is
  flat. Read a rate that falls with the width as a divider, and a rate that
  stays flat as a multiplier.
- **The fix is the one the scalar compiler already made.** Hacker's Delight's
  signed magic through 64-bit lanes - `I2L`, multiply by the multiplier taken
  unsigned, arithmetic shift by `32 + s`, `L2I`, add the sign bit - is eleven
  operations and no divide, and it measured 2.3x the divide at 512 bits and
  1.6x at 128 (`PLAN_TASK_149.md` 9). Taking the multiplier unsigned folds the
  book's "add the dividend when the multiplier is negative" into the product,
  which a 64-bit lane can hold; the proof is an exhaustive sweep over all 2^32
  dividends per divisor, opt-in in `VarkaEmitterDivisionSuite`, not the book.
- **Measure a one-line fix with a filtered run before regenerating an hour of
  benchmarks.** The task's first lead - a `missing constant` line on the
  divisor's broadcast - was built and looked right; a two-minute
  `-Dvarka.bench.only` run of the one affected arm showed it changed nothing,
  and the line turned out to be C2's retried kind. The regeneration that would
  have blessed it was stopped before it started.

## A refusal at a forced width is the back end's, not the lane count's

Task 153's census, taken on an x86 laptop at `-XX:MaxVectorSize=16`, found every
masked 64-bit operation refused at two lanes, and section 3 of its plan read that
as what "a NEON-only aarch64 host" would do. The arm runner's own census
(`varka-width-audit.yml`, 20 September 2026: Neoverse N2, ASIMD with SVE2, a
128-bit species) refuses nothing in the same hundred shapes. The x86 matcher has
no lowering for masked ops at a species below its own width; the aarch64 matcher,
whose native species is 128 bits, has. So a forced-width census on one
architecture says what that back end does below its width and nothing about the
architecture whose width it is imitating; the question has to be asked on the
host class it is about, which the workflow now does in about forty minutes per
architecture. The one qualification still open is SVE: the runner's JDK enables
it, and a NEON-only JVM has not been asked.

## An intrinsic refusal line is not a verdict about this compile; the inlining decisions are

Task 165 spent a day on the wrong instrument. The assembly gate's probe gathers
at a forced 128-bit species, and on part of the runner pool it came out scalar -
248 instructions, deterministically, with `-Xbatch` so it was not a missing
compile. `-XX:+PrintIntrinsics` was the obvious tool and it pointed nowhere: the
refusing hosts print three `missing constant` lines for unrelated shapes and no
`not supported` line, and **so does the laptop while it packs**. A `**` line
says that C2 declined one intrinsic somewhere in the run; it does not say that
it declined the one being looked at, and a census that reads those lines as a
verdict about a particular compile will find the same lines on both sides of the
question.

What separated the hosts was `-XX:+PrintInlining`, filtered to the intrinsic's
own symbol. Where the gather packs, every `VectorSupport::loadWithMap` decision
reads `(intrinsic) late inline succeeded`. Where it does not, the decisions
carry `failed to inline (intrinsic)` with `static call node changed: trying
again` between the attempts, and the first of them reads `callee is too large`.

The reading that follows is worth carrying, because it is counter-intuitive and
the body's own shape argues for it: the refusing body has **zero calls**. A
failed intrinsic does not have to leave a call behind. C2 falls back to inlining
the intrinsic's *Java* implementation as ordinary bytecode, and for a Vector API
gather that implementation is a scalar loop over the index map - a short scalar
body with nothing to grep for. So "no call in the body" is not evidence that the
intrinsic was applied, and the absence of a matcher refusal is not evidence that
it was attempted. Only the inlining decisions distinguish an intrinsic that ran
from one that was given up on.

Two consequences for how a gate is written. A precondition keyed on the symptom
("it came out scalar") absorbs every future cause into a green job, so key it on
the signature of the cause and make anything else loud - `PLAN_TASK_165.md`
section 6 is the worked example. And the reason itself, an inlining budget
running out, is a compiler-heuristic outcome rather than a capability: it varies
with the host's processor count and compiler queue, which is why no CPU feature
and no JDK build explained it.

## A masked store through two lanes costs the loop, not the mask; a half species keeps the loop

Task 102's narrowed store writes an int column from long lanes: the kernel computes at the
long species and stores each group's results as ints through a masked store, because the int
species is twice as long as the long one and the group fills half of it. At 512 and 256 bits
that costs 4% or less against the wide store. At two long lanes it cost 12% on `second` and
18% on the three-field shape, and the direction was explained but not the size.

The 128-bit assembly (`dev/varka_emit.sh --asm --width=16`, task 156) shows the difference
is the loop, not the store instruction: the masked-store loop is not unrolled where the
wide-store loop is, it carries the masked store's own bounds branch inside the loop, and it
shifted the byte offset every iteration. The offset is a small part and its fix moved
little. The rest is loop shape, and it is recovered by storing through the *half species* of
the int lane - `IntVector.SPECIES_64` at two long lanes, `SPECIES_128` at four, `SPECIES_256`
at eight - which is exactly one group wide, so the store is a plain store and the loop is the
wide store's loop again. Measured (`PLAN_TASK_156.md` section 6): equal to the wide store
within 3% at 512 and 256 bits, within 3% at 128 on `hour`, `minute` and `second`, 10% under
on the three-field shape, and past L3 at the wide widths the fastest of the three stores,
since it writes half the bytes with the wide store's loop. The residue at 128 bits on the
three-field shape - three half-vector stores per two-row group - is what a per-store cost
looks like when the lanes are too few to amortise it.

The general lesson: when a narrower vector is needed for one instruction, prefer a species
that is exactly as wide as the data over a wider species with a mask. The mask is not the
expensive part; what it does to the loop around it is.

