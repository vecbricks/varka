# What C2 does with these loops

What HotSpot's C2 does with these loops, each lesson proven from the JVM's own output rather than inferred from a timing.

One of Varka's lesson files; the index over all of them is
[`SKILLS.md`](../../../SKILLS.md) at the repository root, which is generated from
these files by `dev/varka_toc.py`.

## C2 Compile Latency Is the Wide-Vector-Loop Cliff (root cause, proven)

- A 64-op emitted vector loop ran 1.0 G rows/s in one JVM and 9-13 M rows/s in
  another. First hypothesis - "history-dependent inlining", suspecting
  `InlineSmallCode` - was *refuted* by experiment: raising it changed nothing. The
  proven mechanism (`-XX:+PrintCompilation`): the method's tier-4 OSR compile takes
  ~10 seconds for a 1457-byte method whose 64 Vector API call sites each expand into
  large intrinsic graphs, and until it lands the loop runs the C1 version with boxed
  vectors. A 30-second window showed the rate jump 9 -> ~1000 M rows/s at t=12s.
  "JVM history" only shifted when the compile started relative to the measurement
  window - fresh JVMs got it in during warmup, busy ones did not.

  **Update, VARKA-43: this no longer reproduces, on the same path, at four times the width.**
  A ladder of single-output loops from 20 to 248 `IntVector` ops - a `greatest`/`least` tree
  over independent `dayofweek(d + k)` subtrees, which is linear at 19 ops per step - was
  measured with `-XX:+PrintCompilation` at both widths on JDK 25. Tier-4 **OSR** compile of
  `loopDense0`, which is the path the paragraph above describes: 15, 52, 100, 163, 165 and
  186 ms across the ladder at AVX-512, and 12 to 140 ms at 128-bit. The standard, non-OSR
  compile is slower and still small: 30 to 271 ms at AVX-512, 58 to 501 ms at 128-bit, linear
  at roughly 1.1 and 2.0 ms per op. So a 248-op loop compiles in 186 ms where a 64-op loop
  once took ~10 s. The old observation is not being called a mismeasurement - a rate jumping
  9 to ~1000 M rows/s at t=12s is not subtle - but the number describes a JDK that is no
  longer the one in use, and anything resting on it (`GROUP_BUDGET`'s javadoc, among others)
  needs re-deriving rather than re-citing. *`GROUP_BUDGET`'s javadoc was re-derived on 10
  September 2026: the compile-time argument is gone from it, replaced by VARKA-71's survey -
  raising the budget past 24 regroups one shape of nine for one lane op, while growing every
  method toward C1's refusal threshold. The constant is unchanged at 16 and its justification
  is now a measurement of what the budget does rather than of what compiling costs.*

  **And it disagreed with another number this repository already carried.** `m4/PLAN.md`
  section 2.3 and its debt register both price a wide loop's compile at "~1 ms per vector op",
  which at 64 ops is 64 ms rather than 10 s - a 150x disagreement that sat unremarked. The
  ladder agrees with the per-op figure. So the honest reading of the ~10 s is that it was
  probably never ten seconds of compiler *work*: the bullet above says fresh JVMs got the
  compile in during warmup and busy ones did not, which describes a compile task **queueing**
  behind others under load. That keeps the observation - a loop running C1-boxed at ~1% until
  its compile lands - and drops the inference that op count caused it. A queued compile can
  bite at any width, which is a scheduling property and not something a per-method op budget
  can bound.

  **Stamp measured numbers with the host and JDK that produced them.** The ~10 s entry did not,
  which is why nobody could tell staleness from disagreement for as long as both numbers sat
  in the tree. Everything above was measured on an AMD Ryzen AI 9 HX PRO 370 under OpenJDK
  25.0.4+7 on Linux, via `-XX:+PrintCompilation` over the committed
  `VarkaEmitterParityBenchmark` ladder.

  **Throughput does not fall off either, at either width.** Nanoseconds per row per op over
  the same ladder: 0.0078, 0.0073, 0.0072, 0.0077, 0.0075 from 58 ops up at AVX-512, and
  0.0212, 0.0179, 0.0163, 0.0163, 0.0168 at 128-bit - flat, and at 128-bit *improving* with
  width, since the narrowest body spreads the loop's fixed costs over the fewest ops. There
  is no register-pressure cliff for an emitted single-output loop up to 248 ops.

  **And C1's `out of virtual registers in linear scan` is not about the machine register
  file.** The bimodality section below attributes that refusal to 128-bit and its sixteen
  `xmm` registers. Measured, it happens identically at 512-bit with thirty-two `zmm`
  available, on exactly the same two methods - `ChronoVectorOps::vectorFourFields` (936
  bytes) and the widest ladder point's `epilogueDense` (1954 bytes), never any
  `loopDense0`. It is C1's own linear-scan allocator running out of *virtual* registers on a
  large body, and it ends in "retry at different tier", so the method goes to C2 rather than
  failing to compile - which is why it was never visibly slow.
- The structural fix stands regardless: keep every hot loop method small by
  construction (the emitter splits outputs across sibling loop methods of at most
  `GROUP_BUDGET = 16` ops, called from a driver). Small methods compile in
  moments; the 64-op kernel as four 16-op methods hits ~1 G rows/s in the same
  polluted JVM that showed the cliff.
- Corollary for benchmarks of generated code: a case that never speeds up may be
  waiting on a compile, not hitting a wall. Distinguish with a long window and
  periodic rate reporting before concluding anything; then read
  `-XX:+PrintCompilation` (a repeated OSR task line marked `blocked` was the tell).
- Related cost numbers: emitting + defining + loading + instantiating a fused kernel
  class is 130-450 us even for the widest shape - class *generation* is never the
  cold-start cost; C2 compile latency is.
- Same family, earlier finding (VARKA-10): two vector loops emitted into one method
  also degrade each other (3x-4x on the second loop). One C2 compilation per hot
  loop, always - sibling methods, not longer methods.
- Same family, VARKA-14's post-commit diagnosis: **a class defined per task re-pays
  the whole tier ladder per task.** The per-task loader defines a fresh kernel class
  each task; HotSpot treats it as new, so every task runs interpreter, then C1 with
  boxed vectors, then the C2 OSR compile - a *fixed per-task* cost that grows with
  the loop method's vector-op count (~13 ms for an 8-op chain, ~50 ms for the 20-op
  dayofweek fold) and dwarfs the ~80 us emission it sits next to. Two diagnostics
  that pin it: (1) scale the table 4x - a per-task-fixed cost leaves the absolute
  delta unchanged where a per-row cost quadruples it; (2) `-XX:+PrintCompilation`
  shows one tier-4 OSR of the same-named method *per task*, each followed by
  "made not entrant: OSR invalidation" as the task's class dies. The decomposition
  (VARKA-14.md 7.5): the C2 compile itself is ~1 ms per vector op (2/10/20-25 ms
  for 3/10/20-op loops), and the interpreted and C1 profiling phases before it
  scale the same way, because tier counters advance per backedge at boxed speed -
  which is also why a scratch-batch warm spin saves nothing. Corollary for any
  cross-task cache: caching `byte[]` does not help - a re-defined class is a new
  class and re-pays the ladder; only reusing the *loaded class* preserves the C2
  code. And benchmark tasks must be long enough to amortise the ladder, or the
  committed number prices JIT warm-up, not the kernel. VARKA-18 acted on the
  corollary - `VarkaShapeCache` shares the loaded class across tasks, keyed on
  the IR shape - and the committed depth curve flattened from 2.2x-eroding-to-1.3x
  into 6.5-7.2x flat, confirming the ladder was the whole erosion.
- Second corollary, caught by VARKA-18's PR review after the results file was
  committed: **a cache keyed on structure silently defeats a harness that
  manufactures freshness through values.** `VarkaColdStartBenchmark` made each
  iteration "fresh" via distinct columns and literals - exactly what the shape key
  ignores by design - so after VARKA-18 the guard query warmed the process-wide
  cache and every timed "cold" iteration measured a hit while the harness's own
  comments still promised a fresh emission. When a cache key changes, re-derive
  every benchmark's freshness argument from the new key rather than trusting the
  harness; the fix here invalidates the shape cache inside the timer loop.
- **The 64-op cliff does not generalize to every wide method - it is specific to
  what was compiling it.** VARKA-32 step B2 built the real thing the cliff worried
  about, a 200-vector-op single loop method (four calendar fields fused by a
  widened `GROUP_BUDGET`), and measured its compile time directly with
  `-XX:+PrintCompilation` filtered to the generated class's own name (every Varka
  kernel gets a distinct class, so this is exact). It reached tier 4 in **272 ms**,
  and the widest kernel in the same suite (twenty separate loop methods) in 2.4 s -
  neither approaches the ~10-second cliff, and neither shows the cliff's own tell
  (a repeated tier-4 task line marked `blocked`, zero occurrences anywhere in the
  log). The original 64-op finding was on a *different* kernel - `javac`-compiled
  Java source with a heavier call-site mix - and the risk register correctly
  treated it as a hazard to re-check rather than an assumed fact, which is exactly
  why this measurement was taken before recommending the design rather than after.
  The general lesson: a historic compile-time cliff belongs to the method that hit
  it until proven otherwise, not to "loop methods of that op count" as a category -
  measure the actual generated bytecode's compile time before assuming a past
  finding transfers to a new emitter, a new op mix, or a new class shape.

- **Op count is not the only compile gate: bytes are a second, harder one.**
  `GROUP_BUDGET` bounds a loop method's *vector ops* because compile time grows with
  them. `HugeMethodLimit` (8000 bytes, a product default) bounds a method's *bytecode*,
  and past it HotSpot does not compile the method slowly - it does not compile it at
  all, at any tier, so the method runs interpreted with boxed vectors forever. The two
  gates catch different shapes, and the emitter's epilogue is where the second one
  bites: VARKA-24 made it one method over *every* output, so its size grows with the
  whole projection rather than with a group, and four calendar fields over five date
  columns crossed 8000 bytes. Measure it rather than estimating - the `Code`
  attribute's length, which is exactly what HotSpot measures, is two lines through
  `java.lang.classfile` (`VarkaEmitterTestSupport.codeSize`) - and assert the crossing
  in a test, so the next wide node moves a number instead of quietly falling off.
  VARKA-32 step B1's ladder is in `VARKA-32.md` section 7.1.

## A forked probe's warm-up count buys a compile request, not a compile

The assembly suite's child (`VarkaAssemblyProbe`) calls a method 200000 times so that C2
compiles it, then measures and prints. The count is far past `Tier4InvocationThreshold`, and
it was still not enough: on GitHub's four-core runners the gate failed one run in three with
a C1 body for the hand-written kernel (`saw c1`) and scalar C2 bodies for the 128-bit cases,
byte-identical from run to run, while the same commit passed on a re-run. The laptop
reproduces the four failures when sbt and its children are pinned to two cores that busy
loops already occupy (VARKA-150).

Background compilation is the reason. Crossing the threshold queues a compile; the calls keep
running in the interpreter or in C1 while the compiler thread waits for a core, and on a
starved machine the calls run out first. A C2 body that does arrive early, before the vector
classes the intrinsics need were loaded, is the Java fallback: scalar, and boxing when
inlined, which is what the identical instruction counts were.

`-Xbatch` on the child makes the compile happen on the calling thread, so the crossing call
returns with the nmethod installed and a count-based warm-up means what it says. Under the
same starvation the suite goes from four failures to none. The flag is already what
`VARKA-153.md`'s width probe runs under, for the related reason that a synchronous
compile is one whose diagnostics attribute to the method that asked for it.

The general rule for any harness that forks a JVM and reads what C2 did: either compile
synchronously, or wait for evidence of the compile (a `PrintCompilation` line for the
method, a WhiteBox query), and never infer it from an iteration count, however generous.

## A forked JVM keeps printing after its last marker line

A harness that runs several forks in one log and splits them at a marker the child prints last
(`VARKA_DEOPT_DONE=` in `VarkaDeoptCycleProbe`) gives each fork's shutdown to the next one. A
JVM exiting with compiles still queued prints their `PrintCompilation` lines - tier-4 tasks
marked `blocked` - after its last line of its own, and every fork of a case emits a class of the
same name, so nothing in those lines says which JVM printed them. In the deoptimization-cycle
census at sixty outputs it moved a fourth tier-4 compile of a loop method from the fork that made
it to the fork after (`VARKA-189.md` 10).

Split at the marker the child prints first instead: a fork runs from its own start line to the
next fork's, and the last marker only says whether it finished. `dev/varka_deopt_cycle.py` does
this, and `VarkaDeoptCycleSuite` pins it with two recorded consecutive forks.

## Watching what C2 compiled, at runtime, with no flags

JFR's `jdk.Compilation` event carries `method`, `compileLevel`, `isOsr` and `codeSize`, and
`jdk.jfr.consumer.RecordingStream` consumes it in-process with no agent and no diagnostic flags.
That makes compiled size observable in any JVM, which the bimodality section above needed a
fastdebug build and `PrintAssembly` to see. VARKA-50 builds this; four things it cost to learn.

* **The success field is spelled `succeded`** in the JDK's own event metadata. Asking for the
  correctly spelled name throws, and if the handler catches broadly the stream goes quietly dead
  rather than failing loudly. Dump the schema (`FlightRecorder.getFlightRecorder().getEventTypes()`)
  rather than trusting the field names you would expect.
* **Key any size comparison on (class, method, compile level), never on the class alone.** A
  Varka kernel is many methods by design, and the tiers differ enormously: measured on one `year`
  kernel, `epilogueDense` is 165728 bytes at tier 3 and 1888 at tier 4 - profiled C1 against
  optimised C2, a factor of 88. Compare across tiers and every method reports a 98% "divergence"
  in every JVM. Drop OSR compilations too: same method, same tier, 744 bytes as OSR against 576
  not.
* **Compiled size for a given key is byte-identical run to run.** Measured across three JVMs and
  across two emissions inside one JVM, every key came out the same to the byte. So any threshold
  between zero and the ~2x that a bad allocation costs will do, and the choice is not delicate.
* **A per-JVM baseline cannot see the bimodality that motivated it.** Every key is normally
  compiled exactly once per JVM, and VARKA-32's spread was *between* runs - "stdev 0 inside a run,
  42% between runs". What gives a second compilation of one key is **re-emission**: the same
  shape emitted into a fresh class of the same name under a different loader, which is what
  `maxEntries = 0` and cache eviction already do, and which is also the parked "resample" idea.
  Emitting one shape twice in a JVM produced 16 compilations across 8 keys, each compiled twice.
  Design the diagnostic around re-emission, not around recompilation.

**You do not need to run the fastdebug JVM to read product assembly.** HotSpot's fourth
fallback for locating the disassembler is `hsdis-<arch>.so` on `LD_LIBRARY_PATH`
(`disassembler.cpp`), so a `libhsdis.so` built once against a fastdebug tree can be copied to
`hsdis-amd64.so` and used with the *product* JVM (and you do not need the fastdebug tree
either: `dev/varka_hsdis_build.sh` compiles `src/utils/hsdis/capstone/hsdis-capstone.c`
against the distribution's `libcapstone-dev` with `make/Hsdis.gmk`'s flags - a 16 KB shared
object in under a second, the JDK headers only for `jni.h`):

```
LD_LIBRARY_PATH=<dir with hsdis-amd64.so> java -XX:+UnlockDiagnosticVMOptions \
    -XX:CompileCommand=print,<class>::<method> ...
```

That matters because some behaviour only appears in the product build - this bimodality among
it - so being able to disassemble there rather than only in fastdebug is what made the
comparison above possible at all.

**Four details that decide whether a disassembly-reading test works or quietly passes.** These
came out of VARKA-31's feasibility check, run against the system JDK 25.0.4 product build with
the fastdebug tree's `hsdis-amd64.so` on `LD_LIBRARY_PATH`.

* **Prefer `-XX:CompileCommand=print,<class>::<method>` to `-XX:+PrintAssembly`.** `print` emits
  one method's disassembly; `PrintAssembly` emits the whole compilation log, and the difference
  is hundreds of lines against tens of megabytes. With `print` there is nothing left for a
  `compileonly` filter to do.
* **Both a C1 and a C2 nmethod are printed for the same method**, headed `C1-compiled nmethod`
  and `C2-compiled nmethod`. C1's body is scalar by construction, so anything reading the output
  must split on those headers and keep the C2 one. Scanning the concatenated text finds scalar
  instructions in a method that vectorized perfectly.
* **The mnemonic and its operands are separated by tabs, not spaces** - `vpaddd\t\t0x10(%rsi,
  %rax, 4), %zmm0, %zmm0`, confirmed with `cat -A`. A pattern written for whitespace-as-spaces
  matches nothing, and *matching nothing looks exactly like a body with no vector instructions*.
  Any such test needs a self-test - a deliberately scalar method asserted to contain none of the
  family and a vector one asserted to contain one - or every case can pass vacuously.
* **"hsdis is present" is not the same as "hsdis loaded".** Without a working disassembler
  HotSpot prints `Loading hsdis library failed` and degrades to bytecode-level output rather than
  erroring, so detection must look for a real `[Disassembly]` section with hex-addressed
  instruction lines, and should distinguish "no library found" from "found and refused to load"
  when it reports a skip.

**Three ways this went wrong in practice, all caught by the self-test before any real kernel was
looked at.** Each produced a *plausible* result rather than an error, which is why the
scalar/vector pair is built and run before anything else.

* **`-XX:CompileCommand`'s method pattern cannot mix `/` with `::`.**
  `print,org/apache/spark/.../Probe::method` fails VM startup outright -
  `Method pattern uses '/' together with '::'`. Use `package.Class::method` or
  `package/Class.method`, not a mix. The child then never starts, and a suite that only checks
  for disassembly reports "no disassembler" for what is really a malformed flag - so check the
  child's exit code first, and fail rather than skip on it.
* **Gate instruction parsing on the `[Disassembly]` marker, not on the shape of a line.** With no
  usable disassembler HotSpot prints the nmethod under `[MachCode]` as raw hex words -
  `0x...: ff1f 0045 | 85c9 0f84 | ...` - and those lines have exactly the `0x<addr>:` shape an
  instruction line has. Requiring the mnemonic to start with a letter does not separate them
  either, since hex words routinely do (`ff1f`, `e929`, `c349`). Measured: 68 such lines parsed
  as instructions, and the suite reported "the intrinsic did not fire" about a body it had never
  read.
* **`Loading hsdis library failed` does not mean a library was found.** HotSpot prints it both
  when it looked and found nothing and when it found something it could not load, so a skip
  message keyed off that line says "a disassembler was found but HotSpot refused to load it"
  when none exists. Discriminate on your own search result instead.

**Assert families, never mnemonics or counts.** The register class is a property of the host -
`zmm` under AVX-512, `ymm` under AVX2, `xmm` at `-XX:MaxVectorSize=16` - so derive it from
`IntVector.SPECIES_PREFERRED.vectorBitSize()` at runtime. And do not assert instruction *counts*:
the bimodality section above found identical vector-op counts with a 2x difference in total
instructions, so a count assertion goes red on a register-allocation roll with nothing wrong.

**And derive the family from output you actually read, not from a mnemonic list written from
memory.** The obvious list for an integer comparison - `vpcmpd`, `vpcmpeqd`, `vpcmpgtd` - matches
nothing on AVX-512, because the predicate is folded into the mnemonic: `a > b` on int lanes comes
out as `vpcmpnled`, not-less-or-equal. The full set runs to a dozen suffixes and varies with how
C2 chose to spell the comparison, so the durable rule is the shape `[v]pcmp<predicate>d` rather
than an enumeration that goes stale on the next lowering change.

**Forcing C2 to inline Varka's own packages is a decline, and not because it does nothing.**
`-XX:CompileCommand=inline,org.apache.spark.sql.varka.*::*` (plus the catalyst varka package)
leaves every loop body byte-identical - the emitted `year` body stays at 327 instructions with
10 `vpaddd`, 8 `vpmulld`, 4 `vpsrld`, over three runs each with zero variance, and
`ChronoVectorOps.vectorFourFields` stays at 1174. What it changes is the method boundary: the
emitted `run` grows from 271 instructions with no vector ops to 471 carrying the whole year
lowering, `runDense` stops being compiled standalone, and the vectorized body then exists in two
places. That is exactly the sibling-method structure VARKA-24 built and `GROUP_BUDGET` exists to
control, so the flag works against the emitter's design - and it would have to be set on every
executor to do so. If method fusion is ever worth measuring, produce it from the emitter with
`withGroupBudget`, which is per-shape and needs no flag.

`-XX:+PrintInlining` explains why the bodies survive: the directive overrides the size heuristic
and lands on a harder limit. `VarkaVectorSupport::orValidityBitsAt` (212 bytes) goes from
`failed to inline: callee is too large` to `failed to inline: NodeCountInliningCutoff`. The
reason changes, the outcome does not - which is also evidence that VARKA-46's inlining problem
cannot be solved with a flag.

**Measure the caller, not only the method you are interested in.** The finding above was nearly
missed: the A/B started on `loopDense0`, found it identical under both configurations, and would
have concluded "the flag changes nothing". It changes the *caller*. When a flag affects inlining,
the method whose body moves is the one doing the calling.

**What the kernels actually compile to**, read this way rather than inferred from a ratio, on a
Zen 5 host at AVX-512 (JDK 25 product build). `DateVectorOps.vectorAddDays`: 5 `vpaddd`, 23 `%zmm`
operands. `ChronoVectorOps.vectorFourFields`: 15 `vpaddd`, 13 `vpmulld`, 7 `vpsrld`. The emitted
`year` loop body: 10 `vpaddd`, 8 `vpmulld`, 4 `vpsrld`, 63 `%zmm` operands. The emitted
`dayofweek` body: 65 `vpaddd`, 26 `vpmulld`, 39 `vpsrld` - VARKA-14's range-narrowed magic is
packed, which is the whole reason that lowering exists. An emitted comparison: 15 `vpcmpnled` and
15 `vpblendmd`, no branch. Everything Varka emits or hand-writes for date work vectorizes; that
was an assumption until VARKA-31.

## A bimodal kernel is usually the register allocator, and here is how to prove it

VARKA-32's shared four-field calendar kernel ran at either 165 or 236 M rows/s under
`-XX:MaxVectorSize=16` - stdev 0 ms *inside* a run, 42% *between* runs, 4 fast outcomes in 21.
Six hypotheses were tested and all failed: shorter live ranges, forcing the validity helpers to
inline, forcing every Varka class to inline, disabling on-stack replacement, raising
`LoopUnrollLimit`, and buffer alignment (which had an OpenJDK bug report behind it,
JDK-8380195, describing the same shape and blaming alignment - it is not that here).

**What it actually is.** Capture `PrintAssembly` for the method in both modes and compare the
*standard* (non-OSR) nmethod:

| | instructions | stack traffic | xmm spill moves | vpmulld | vpsrld | vpsubd |
|---|---|---|---|---|---|---|
| fast (240.6) | 1581 | 721 | **4** | 26 | 14 | 20 |
| slow (164.7) | 3000 | 1567 | **74** | 26 | 14 | 20 |

The vector op counts are **identical**, so it is not unrolling, not a different lowering and
not a missing intrinsic. The entire 1581-to-3000 difference is spill and reload traffic, 18x
more of it. C2's register allocator sometimes finds a clean allocation for this body and
sometimes does not, from the same IR. The OSR compilations, by contrast, are the same in both
runs to within one instruction (7303 against 7304).

It is width-specific for the obvious reason: 128-bit has 16 xmm registers and this body sits at
the edge of them - C1 refuses the same method outright with
`COMPILE SKIPPED: out of virtual registers in linear scan` - while AVX-512's 32 zmm registers
leave slack and show no bimodality at all.

**It is a property of the measurement environment, not of the kernel.** It does not reproduce
standalone (six runs, all 210.5 M rows/s, no spread) and does not reproduce under a fastdebug
JVM even running the whole benchmark (six runs, all ~161). It needs the product C2 *and* the
benchmark's accumulated JVM state. So production is not exposed to it, but a long benchmark's
later cases are, and a ratio is only trustworthy when both arms sit in the same run - which for
the shared-versus-four-node comparison they do, since they are adjacent cases.

**The general rule.** When a kernel is bimodal across JVMs with no spread inside a run, diff
the standard nmethod between the two modes and count spill moves before theorising. Equal op
counts with unequal instruction counts means allocation, not transformation, and no amount of
inlining, unrolling or alignment flags will touch it. The design answer is to reduce what must
be live at once - and note that *scheduling* to shorten live ranges did not help here
(`vectorFourFieldsShortLive` is bimodal too); what helps is not putting four outputs in one
method at a narrow width, which is why the four-node baseline is stable in all 21 runs.

**The effect is observable at runtime, with public API.** JFR's `jdk.Compilation` event
carries `method`, `compileLevel`, `isOsr` and - the useful one - `codeSize`, so a
`jdk.jfr.consumer.RecordingStream` can watch the compiled size of Varka's own generated kernel
methods as they are compiled, with no agent and no diagnostic flags. The fast and slow
allocations differ by roughly 2x in code size here, so the anomaly is visible in that number.
And because every kernel is emitted into a fresh class, "recompile it" is available too: emit
the same shape again under a new class name and the allocator gets a fresh roll.
That makes a detect-and-resample loop *possible*; it does not make it wise. Each resample costs
another class, another compile and another warm-up, against a shape whose kernel a short query
may only run a handful of times, and the detection needs a per-shape expected size that will
drift. Prefer the structural fix - do not put four outputs in one method at a width whose
register file cannot hold them - and use the JFR signal as a *diagnostic*, so that a
badly-allocated kernel is reportable rather than invisible, instead of as a control loop.

**Update, VARKA-32 step B2: the "structural fix" above turns out to be a property of this
one hand-written kernel's bytecode, not of "four outputs in one method at 128-bit."** Once
the emitter's own fragment mechanism made the *real* shared-loop-method shape buildable
(`VarkaEmitOptions.withGroupBudget(200)`, four calendar outputs genuinely fused into one
generated method - the same four-output-one-method shape this section just said to avoid),
it was measured at 128-bit across three separate runs and showed **no bimodality at
all** - stable to a few milliseconds every time, at every field count from two to four. The
two hand-written kernels this task also built (`ChronoVectorOps.vectorFourFields`, the
"ceiling", and `vectorFourFieldsNoValidity`) remain bimodal on the same three runs, one of
them in a new flavor - flipping between fast and slow *within* a single run's iterations
rather than settling into one mode for the run's duration (`VARKA-32.md` section 7.4).
So: a register allocation this fragile is a property of one specific compiled method's
bytecode (here, `javac`'s output for a hand-written 936-byte body), not an inherent cost of
the technique it demonstrates. Do not generalize "four outputs in one method is unsafe at
128-bit" from a single hand-written kernel to the emitter's own generated bytecode for the
same shape - measure the actual generated path before declining a design on this basis.

## Two things share the name `uncommon_trap`, and only one of them happened

`m4/PLAN.md` 2.39 read `-XX:+LogCompilation` and reported that a kernel
was taking nine `profile_predicate` traps per method with action
`maybe_recompile`, thirty-six for the kernel, 494 across the file, and concluded
that a polluted profile was making C2 speculate, fail and rebuild. A whole task
was planned on that. None of it was there.

A self-closing `<uncommon_trap .../>` inside a `<parse>` tree is a guard the
compiler **inserted while building the method**. C2 emits a fixed block of them
per counted-loop compile - `predicate`, `profile_predicate`, `loop_limit_check`,
`auto_vectorization_check` - which is why their counts move together and tie. A
loop that never deoptimises emits four. A runtime deoptimisation is a different
element: an open `<uncommon_trap thread=... compile_id=... level=...>` carrying a
nested `<jvms method='...'/>` frame with a literal method name, usually followed
by `<make_not_entrant>`. Counting the first and reporting it as the second counts
compiles.

**The tell was inside the section's own numbers.** It reported six tier-4
compiles plus two on-stack replacements two paragraphs above, and nine traps per
method two paragraphs below. Eight compiles plus the tier-3 compile is nine. A
per-method count that equals the compile count is a per-compile event, and the
document had both numbers on one page.

Counted properly the same run gives 1764 insertions against 394 runtime
`profile_predicate` deoptimisations, and the storm is real but elsewhere: the
worst method takes 100 deoptimisations across 194 compiles and is *hand-written*,
while the shape the section was about no longer appears at all.

Three habits, and the first two are cheap:

- **Reproduce a diagnostic's element on something with a known answer before
  counting it in anger.** A twenty-line program with a summing loop settles what
  `profile_predicate` means in about a minute, and it settles it against a
  program that provably has no deoptimisation to find.
- **Two counts that must differ and do not are a keying bug, not a coincidence.**
  `predicate` and `profile_predicate` are independent runtime failures and tied
  exactly. Independent things do not tie; a fixed block emitted once per compile
  does.
- **Attribute through the field that names the thing.** A runtime deoptimisation
  carries its own `<jvms method='...'/>` with a literal name. The numeric
  `<klass>`/`<method>` ids that need a per-compilation-unit lookup live only in
  the `<parse>` tree - the half that should not be attributed at all - so a
  procedure that resolves ids is a procedure counting the wrong half.

`dev/varka_trap_census.py` does the split and exists so this cannot be repeated
by hand.

## Calling into an uncompilable method costs something even when it does nothing

A method past `HugeMethodLimit` is never compiled at any tier, so every call into it runs
interpreted - including a call that hits an early return and does no real work at all.
Measured on a 20-calendar-output epilogue (9436 bytes unshared, past the limit; 4048 bytes
shared, under it) at an *aligned* chunk size, where the generated early-return check
(`if (loopBound < length) ...; return 0`) fires on every single call and the epilogue never
executes a real row: the unshared (uncompilable) version still cost 1.36x the shared
(compilable) one, invisible at large chunks (4096 calls per pass, real vector work
dominates) and clearly visible at small ones (15625 calls per pass, the per-call cost
dominates). Do not describe a `HugeMethodLimit` crossing as "costs nothing on aligned
batches" - it costs nothing *extra in arithmetic* on aligned batches, but the call itself,
paid on every batch whether aligned or not, is measurably more expensive when the callee can
never leave the interpreter. The unaligned case remains far larger (7.3x at a chunk where
most of the batch is remainder) because there real interpreted arithmetic dominates too.

The refusal is silent: `-XX:+PrintCompilation` prints no line for a method
`DontCompileHugeMethods` rejects, at any tier, so the evidence that a method is over the
limit is the *absence* of its line while its siblings reach tier 4. `VarkaHugeMethodSuite`
pins that from a forked JVM under `-Xbatch`, both ways: the legacy single epilogue absent,
and under `methodByteBudget` every loop and epilogue method at tier 4.

## A hand-written comparison kernel needs every fast path the real one has

`ChronoVectorOps.vectorFourFields` was built as VARKA-32's throughput ceiling: same
arithmetic, same guard, same op count as the emitted kernel it stands in for. It has no
dense/masked split, though - it always builds a `VectorMask` and uses masked load/store
overloads, even when the caller reports every row non-null. On null-free data the emitted
kernel dispatches to a genuinely unmasked dense body (VARKA-10's split), so the "ceiling" was
silently measuring the masked-body cost the whole time. Once step B2 made the true emitted
dense-shared kernel buildable, it beat the "ceiling" by 1.15-1.20x, reproducibly across three
runs. The lesson generalizes past this one kernel: a hand-written stand-in for an emitted
path is only a fair bound if it takes every fast path the emitted dispatcher can reach for
the data it is fed - a masked-only comparison kernel run on null-free data is not measuring
the same thing the emitted kernel is, and the gap does not announce itself; nothing crashes
or looks wrong, the "ceiling" just quietly is not one.

## A kernel clean at 512 bits can be a per-lane loop at 128, and only the narrow companion shows it

VARKA-152's `VarkaTimeBenchmark` (20 September 2026): the 64-bit magic divide -
fourteen operations, reviewed, tested against the reference at both long
widths, green - measured 0.84x of the conversion form at the wide width and
0.02x at 128 bits, 48.8 against 2377.2 M rows/s. `-XX:+PrintIntrinsics` under
`-XX:MaxVectorSize=16` said why in five lines: `** not supported: ... vlen=2
etype=long is_masked_op=1`, the same for a masked double op, and
`op=comp ... vlen=2 ... ismask=usestore` for the compares. At two 64-bit lanes
this JVM has no lowering for a masked long or double lanewise operation or for
a compare that produces a mask, and the Vector API runs each as a Java loop
over the lanes. Nothing throws, nothing is wrong, and the suites pass.

Three things to carry.

- **Correctness tests at both widths say nothing about vectorisation at
  either.** The differential ran the magic form at 2 and 8 lanes and was green
  both times, because a per-lane Java loop computes the right answer. Only a
  rate, or the JIT's own log, tells a vector op from its fallback - which is
  VARKA-124's whole argument for the assembly gate, now with a second example.
- **Read the narrow companion for collapses, not for ratios.** The wide file
  cannot show this class of failure at all; the 128-bit file is where a masked
  or width-specific lowering falls off a cliff, and a row in it at one fiftieth
  of its neighbour is a diagnosis to run, not a slow machine.
- **Masks are the width-fragile part of a 64-bit kernel.** The unmasked steps
  of the same sequence vectorised at two lanes; every masked one did not. A
  long-lane construction that needs a mask - a guard, an overflow test, a
  blend - should be assumed scalar at 128 bits until `PrintIntrinsics` at
  `MaxVectorSize=16` says otherwise (`m5/PLAN.md` 2.89 is that audit).
## Ask C2 which vector calls it refused, per shape, and know which of its three answers is a verdict

VARKA-153 (20 September 2026) turned one measured collapse into a table by
forking a probe per vector width under `-Xbatch` and a `PrintIntrinsics`
directive scoped to the emitted classes (`-XX:CompileCommand=PrintIntrinsics,
<class prefix>*::*`), with a marker printed before and after each shape.
`-Xbatch` is what makes the markers attribute: compilation then happens on the
calling thread, between the markers, instead of on a background thread whose
lines land under whichever shape is running. `VarkaWidthAuditSuite` is the
machinery and `sql/varka/width_audit.json` the census; three things to carry.

- **C2 prints three kinds of line, and only one is a verdict.** `** not
  supported: ...` is architectural - no lowering for that operation at that
  lane count and element type on this machine, and no retry changes it; it is
  the kind behind the measured 128-bit collapse and the only kind to assert on.
  `** missing constant: ...` is a first late-inline attempt that C2 retries after
  more optimisation: `i + 1` prints two at 128 bits and the committed 128-bit
  numbers show it fully vectorised, so reading it as a fallback would have
  called forty vectorised int rows scalar. `** unbox failed: ...` is a vector
  reaching the call as a heap object, and in a JVM that has compiled many
  kernels it appears on shapes that print nothing when run alone - the
  second-species pollution the assembly gate's self-test demonstrates.
- **Run the shape alone before believing what the sequence said about it.** A
  one-JVM sweep over a hundred kernels is the realistic condition and the right
  census, but a line under one shape can be the JVM's history rather than the
  shape's code. The probe takes a name filter for exactly this, and
  `-XX:CompileCommand=PrintInlining,<pattern>` beside the intrinsics directive
  shows which inlined method a line came from.
- **The first CI run of the audit answered a question two tasks had carried.**
  On the runner pool's EPYC 7763 at 256 bits C2 refuses `L2D` and `D2L` at
  four 64-bit lanes (`op=cast#510/512 vlen2=4`), in every shape with a 64-bit
  constant division and nowhere else - VARKA-88's "the converts do not
  intrinsify under AVX2" confirmed from the log on real hardware, where the
  laptop, whose AVX-512VL converts work at every width, could never show it.
  An invariant that runs on every runner class is a census of the fleet for
  free; encode the refusals a host class is known to have (`knownBelowAvx512`)
  rather than failing the class, and let each new one be a finding.
- **At two 64-bit lanes this JVM lowers no mask at all.** Compare to mask,
  blend, mask cast, broadcast, logic and test are all `not supported` at
  `vlen=2 etype=long`, and all fine at four lanes of either element type. Every
  long-lane guard, overflow test, selection and `CASE` is built from them, so
  on this JVM a two-lane species - NEON's - runs them per lane. The unmasked
  arithmetic and the conversion-form division vectorise. Design a 64-bit
  construction for the narrow species without a mask, or decline it there.

## A new kernel class runs interpreted until enough batches have gone through it

Varka's loop and epilogue methods are called once per batch, and each call loops only a few
hundred times, so a freshly emitted class reaches neither the first compiler tier's invocation
threshold (200 calls, or 100 calls with 2,000 calls and iterations together) nor its back-edge
threshold (60,000 iterations in one call) until many batches have passed. Over a hundred
thousand rows - ten batches of the cache's default 10,000 rows, about 625 iterations each at
sixteen lanes - the cold-start benchmark's kernels were never compiled at any tier:
`-XX:+PrintCompilation` shows no `loopDense` or `epilogueDense` event in the whole run, while
vanilla's generated `processNext` compiles in the same log, because its one loop over every row
runs in a single invocation and the JIT compiles it partway through (`VARKA-195.md` 5.2).
Interpreted Vector API code is a library call per operation, so such a query is slower on Varka
than on vanilla. Two things follow for anyone measuring: a "second run" of a newly emitted class
is not steady state, and a benchmark that wants steady state has to push enough batches through
the class first, as the size ladder's two-second warmup does. With
`spark.sql.codegen.varka.warmup.enabled`, the default since VARKA-212, a new shape's
batches take the row path while a background thread compiles the kernel instead (the
two sections below).

The same investigation turned up a benchmarking trap worth its own line: fresh literals chosen
to defeat a cache can push a value past a kernel's covered range - `add_months`' month bound
here - and the kernel's guard then hands every batch to Spark's row path, so the Varka arm times
vanilla. `VarkaSizeLadder.varkaFused` now fails a run that has any fallback batch.

A test whose verdict is "the allocation samples read clean within N rounds" measures the same
thing before it measures its subject. `VarkaErrorContextSuite`'s steady-state check - that a
task whose batches all failed leaves nothing the kernel's path then pays per batch - read every
batch suspect for twenty rounds in the option matrix's run after a merge on 14 October 2026, on
a kernel that reads clean within five rounds on the laptop and whose bytes do not depend on the
configuration drawn; `-XX:CompileCommand=exclude` over the kernel classes reproduces the read
exactly, and the interpreted loop allocates about 120 KB a 10,000-row batch where a compiled one
allocates a few. The test now takes a control under the same cap before its treatment and cancels,
with `VarkaJitEvidence`'s account of the class, when the control never reads clean
(`VARKA-314.md` 9.2); the warm-up suite, whose subject is the JIT, keeps failing instead.

## A method C1 compiles at tier 2 but not at tier 3 is stranded on C1 code

A kernel's loop and epilogue methods are too large for C1's fully profiled tier: every tier-3
compile of them ends "COMPILE SKIPPED: out of virtual registers in LIR generator (retry at
different tier)", which marks the method not C1-compilable, and the interpreter then profiles
it until C2 takes it. That is the normal path, and it works. But when a method crosses its
first threshold while C2's queue is longer than `Tier3DelayOn` times the C2 thread count,
HotSpot asks C1 for tier 2, limited profiling, instead - and tier 2 fits where tier 3 does
not. The method now runs C1 code, boxing every vector operation. Its next request is tier 3,
which fails; from tier 2 the policy climbs only to tier 3, and tier-2 code does not update the
method's profile, so the direct climb to C2 that the interpreter path takes never comes. The
method stays on C1 code for the life of its class (`VARKA-212.md` 10.2).

It is intermittent because it depends on what else C2 is doing at one moment - a new query's
own compiles are enough - which is why a 54-entry kernel compiled in one JVM and was stranded
in the next three. The proof is in the JVM's own output. `-XX:+PrintTieredEvents` prints a
`compile level=2` event for the stranded methods with the queue sizes beside it, a later
`compile level=3` that fails, and then only `level=2` call events for as long as the run
lasts. The DiagnosticCommand MBean's `compilerQueue`, invoked in-process, shows both queues
empty with nothing compiling. No compile is pending, so none is ever going to land. (`jcmd`
could not attach to the forked benchmark JVM from this sandbox; the MBean is the same command,
from inside.)

The fix keeps C1 off the warmed kernel classes: one compiler directive,
`c1: { Exclude: true }`, added through the DiagnosticCommand MBean's `compilerDirectivesAdd`
the first time a session decides to warm its kernels (`VarkaKernelCompileDirective`). The
first C1 request is refused and marks the method not C1-compilable, which is where the tier-3
failure leaves it anyway, so every warmed kernel method goes from the interpreter to C2.
`VarkaWarmupDirectiveBenchmark` is the A/B, in fresh JVMs.

It has a price. The failed tier-3 request is also what creates a method's profile; with C1
excluded the interpreter creates it only at twice the tier-3 threshold, so a kernel fed by
625-iteration batches reaches C2 about a hundred batches later (`VARKA-212.md` 10.6). A
warm-up does not pay it, because its thousands of short calls create the profile at once; a
kernel fed by its own batches does. And a directive matches by class name, so it reaches
every class of that name from the moment it is added, whoever emitted it: installed at the
first warm-up, it went on to reach the kernels of sessions with the warm-up off, in the same
JVM. So the decision to warm is made when the kernel is emitted, and a warmed kernel gets a
name of its own (`VarkaFusedProjection_w...`, a letter no hex hash contains) that the
directive matches and no other kernel has (`VARKA-212.md` 10.7). A benchmark's best time
hides a price like this and its average does not.

The general rules: for generated code too large for C1's tier 3, exclude C1 outright rather
than rely on tier 2 never happening; scope the exclusion to exactly the classes that get the
treatment it assumes; and read the compilers the JVM runs first. Where C1 is the top tier -
`TieredStopAtLevel` below 4, `-XX:CompilationMode=quick-only`,
`-XX:+NeverActAsServerClassMachine` - excluding it leaves the methods interpreted for good;
where C2 is the only compiler, as with `-XX:-TieredCompilation`, there is nothing to exclude.
One practical detail: the MBean joins its string arguments into a command line and splits it
at spaces and `=`, so the directive file's path must be passed in double quotes, or a
temporary directory with a space in its name makes the command fail.

## Warming a kernel off the critical path

`VarkaKernelWarmup` runs a new kernel on a copy of its shape's first batch until it is
compiled, while the shape's batches take the row path. Five things it took to make that
reliable (`VARKA-212.md` 10).

* **Short calls, not long ones.** HotSpot's thresholds count invocations and back edges.
  Calls of about 48 rows advance the invocation counters hundreds of times faster per row
  than calls of 10,000, and the tier-4 predicate needs a minimum of invocations whatever the
  back edges. Each call keeps the batch's own remainder past its last whole lane group
  (32 rows plus the length modulo 32), so the epilogues see what real batches give them - an
  empty call on a 10,000-row batch - instead of a tail the warm-up made up that C2 would then
  compile at full size.
* **The verdict is allocation, and a compiled wide kernel still allocates.** An uncompiled
  kernel boxes a vector per operation; a compiled one allocates the memory segments its driver
  makes per column, which escape where C2 leaves a call out of line. On a 54-entry kernel that
  is about 7 KB a call, over the species-pollution allowance on its own. The verdict therefore
  allows 256 bytes per column per call and requires the rate to fall to a quarter of the first
  probe's.
* **A count is not a verdict.** Crossing a threshold queues a compile; it lands when a compiler
  thread reaches it, and under the tier-2 trap above it never does. Measure the kernel, then
  switch.
* **Warm both drivers.** A kernel has one driver for batches whose inputs have no nulls and one
  for batches with them, and the batch that claims the warm-up says nothing about which the
  later batches need. Warmed on one alone, the verdict sent every batch of the other kind to
  methods that had never run, boxing at about three times the row path's cost, and - under the
  C1 exclusion - profiled late. The calls now alternate between the drivers, and the masked
  driver's calls pass each input with and without nulls so that every per-input null test is
  profiled both ways; the dense driver's calls read the values under a batch's nulls, so those
  are replaced with a valid value of the same input first.
* **The shape cache keys on the context class loader, so the warm state does too.** A query
  run as an SQL execution runs its tasks under the session's artifact loader; one run through
  `queryExecution.toRdd` outside an SQL execution uses the default loader and meets a
  different entry, cold. A check that a shape's compiled kernel served a query has to run the
  query the way the query it checks ran.

## JEP 515's profiles cannot reach a kernel, and on the incubator module they reach nothing

JEP 515 (JDK 25) stores method profiles in the AOT cache of JEP 483 so that a production run
compiles its hot methods at once. Two things keep it from Varka, both checked on JDK 25.0.4 with
a probe trained in one step (`-XX:AOTCacheOutput`, JEP 514) on 5 October 2026:

* **The module that Varka needs switches it off.** With `--add-modules jdk.incubator.vector`,
  identical in training and production as JEP 483 asks, the dump disables the full module graph,
  links no class ahead of time (0 of 901) and stores no profile (`MethodTrainingData = 0`).
  Without it, 889 of 890 classes are linked and 230 methods profiled. Spark's `--add-opens`,
  which JEP 483's text forbids, is accepted on JDK 25: 899 linked, 236 profiled.
* **A kernel is outside the cache anyway.** Only classes the JDK's built-in loaders take from
  the class path, the module path or the JDK are cached (JEP 483). A class a user-defined loader
  defines - as `VarkaGeneratedClassLoader` defines every kernel - is archived as "unregistered",
  its bytes only: not linked, not profiled.

The cache also refuses a directory on the class path ("Cannot have non-empty directory in
paths"), so it needs the jars of an installed distribution, not a development build.

The JVM lever that does reach the kernels alone is
`-XX:CompileCommand=CompileThresholdScaling,<kernel class pattern>,<factor>`, a startup flag; a
compiler directive - what `VarkaKernelCompileDirective` adds at runtime - rejects the key. Whether
it pays on top of the warm-up is `m9/SCOPE.md` item 84; scaling the whole JVM does not
(`VARKA-212.md` 9.1).

## A kernel's methods compile one at a time, so a verdict on the whole kernel must see every loop

C2 compiles a kernel's loop methods one after another - on a starved machine about a hundred
milliseconds each, queued - so for a while after the heavy group's loop is compiled a light
group's loop can still run interpreted. A verdict that reads the whole kernel's allocation sees
the heavy group's drop, and if its probe calls are short, the light loop's boxing - a few hundred
bytes a call - hides under an allowance that exists to excuse per-call memory segments. The
warm-up called such a kernel compiled, and the first real 1024-row batches boxed about twenty
bytes a row in the light loop (`VARKA-221.md` 2, found by JFR allocation samples on the test
thread; `PrintInlining` showed every compile of that loop fully intrinsified, so it was late, not
bad). The rule: measure what grows with rows over calls long enough that it dominates what grows
with calls - the warm-up's probe now runs whole-snapshot calls - and make each probe reach every
driver's loops, which for the masked driver means a null count that does not trigger its
all-null shortcut.

## A row projection over a batch's rows can cost escape analysis its allocations

Spark's `UnsafeProjection` over a `ColumnarBatchRow` reads each column reference through the
batch's column vector and, for Arrow, its accessor, and subexpression elimination leaves plain
column references alone: the size ladder's 16-entry projection reads `d` about 33 times a row.
The reads do not show in a profile - they are inlined, and cheap - but they change what C2
makes of the generated methods. Over that projection the callers C2 compiles inlined 9260 bytes
against 8811 over an `UnsafeRow`, and escape analysis eliminated six allocations in each where it
eliminates twelve on vanilla's path; each `add_months` then allocated the closure that Spark's
`MathUtils.toIntExact` hands `withOverflow`, 33 MB a query of a hundred thousand rows, and the
row path ran at about 1.6 times vanilla's row-at-a-time path.

* **Read each column once.** Copy the referenced columns into an `UnsafeRow` per row and project
  over that (`VarkaInputRows`): C2 then makes of the projection what it makes of vanilla's, to
  the eliminated allocation. Only where some column is read more than once - a column read once
  is read once either way.
* **Look at allocation by class before timing.** JFR's `jdk.ObjectAllocationSample` put a
  closure class at the top of Varka's row path and nowhere on vanilla's, where the execution
  samples only said the date arithmetic was slow.
* **Compare what C2 eliminated, not only what it inlined.** The inlining at `withOverflow` was
  the same on both paths; `dev/varka_c2_report.py` over a `-XX:+LogCompilation` log counts the
  eliminated allocations per compile, and that is where the paths differed. See
  `VARKA-228.md` 7.

## A wide loop method's cliff is the time before C2, and a six-second verdict cannot see past it

VARKA-209 forked the cheap-tail kernel (`year(d) + k` in one loop method) twenty times per output
count under `-XX:+LogCompilation`, and the same class landed fast or slow from one JVM to the
next. Reading each fork's compile log beside its rate settled what the timing could not:

* **C1 refuses the loop past about a hundred vector call sites**, "out of virtual registers in
  LIR generator", at under 1500 bytes for this shape - below the ~1900 the `make_date` shape
  measured, because the limit is C1's registers after inlining the Vector API's bodies, not the
  method's bytes. From there the loop runs interpreted, boxing every vector, until C2's compile
  lands, and that lands a second later for every eight outputs: second 1 at 16 outputs, 4 at
  40, 7 at 48.
* **Read when a fork settled, not only how it ended.** A verdict taken from the last seconds of
  a six-second fork called every wide shape slow; an eight-second fork of the same 48-output
  kernel was fast from its seventh second. The reader now prints the first second from which
  every later rate is fast, and a census of a shape whose compile comes late needs a window
  past it, or it measures the window.
* **The deoptimization cycle is not confined to the legacy form.** A quarter to a third of the
  forks at 24 to 40 outputs recompiled the loop three to nine times with `profile_predicate`
  traps between, the default options and the default epilogue form, where row 218 had it
  never cycling. The compiled code is identical in the forks that cycle and the forks that do
  not, to the intrinsic; only the count of compiles and traps differs. Under `-Xbatch` every
  fork cycles, at 175 to 191 microseconds a row, because each recompile of the method stops the
  calling thread for the second it takes: the flag that fixes compile order is also the flag
  that makes a cycle unmissable.
* **C2's refusal reasons do not discriminate.** `size > DesiredMethodLimit`, `callee is too
  large` and the rest appear in every fork alike; with `-XX:-ClipInlining` they become
  `NodeCountInliningCutoff` and no verdict moves. What C2 does refuse past 56 outputs in one
  method, or 112 sites in each of two, is the whole compile: "out of nodes during split", and
  with `MaxNodeLimit` raised ten-fold a compile that does not finish. `NodeCountInliningCutoff`
  and `DesiredMethodLimit` are develop flags, unsettable on a product JDK; `ClipInlining`,
  `LiveNodeCountInliningCutoff`, `MaxNodeLimit` and `NodeLimitFudgeFactor` are the levers, and
  the last must stay within 2% to 40% of the limit or the JVM refuses to start.
* **Six groups of the same outputs are fast in every fork**, C1 compiling every method, at 4.1
  to 4.4 ns a row, where the one group that lands well runs at 1.2 to 1.4 and the one that
  cycles at 115. See `VARKA-209.md` 9.


## The deoptimization cycle is profiled loop predication's, and the wait before C2 is nobody's

The night after VARKA-209's admission check ran its arms at 24, 40 and 48 cheap-tail outputs,
ten forks of 24 seconds each (`VARKA-209.md` 10):

* **`-XX:-UseProfiledLoopPredicate` ends the cycle**: no fork of 30 cycled under it against 10
  of 30 with the default, with the same compiled code to the intrinsic and the same settle
  seconds. The trap reason `profile_predicate` was the mechanism's name: C2 hoists a predicate
  out of the loop on the profile's word, it fails, the code is thrown away, the next compile
  hoists it again. The flag is a product flag; its cost to everything else is unmeasured.
* **Nothing shortens the interpreter's seconds before C2** - 2 at 24 outputs, 4 at 40, 6 at 48
  in every arm: C1 excluded, helpers forced inline by directive with `MaxNodeLimit` raised, the
  flag. Only a loop method C1 compiles is fast from the first second, and C1 compiles 93
  vector call sites and refuses 99 on JDK 25.0.4.1: 20 cheap tails on their prefix, or one
  `make_date` output, at 55 sites each on a prefix of 38.
* **A compiler directive that forces helpers inline changes nothing when C2 inlines them
  already**: look for "force inline by CompileCommand" in the log to see that it matched, and
  compare the vector operations and calls left to see whether it mattered.
* **A `<failure>` inside a compile task is not a failed compile until `<task_done>` says so**:
  "retry without subsuming loads" is a bailout C2 retries within the task, and the task's
  `success` attribute is the outcome. `dev/varka_c2_report.py` reads it so, and every loop
  method of the `make_date` shape compiles that way.
* **Traps are not the cycle.** The `make_date` shape's 313-site methods trapped and recompiled
  four times a fork in most forks at 10 and 14 outputs, and every fork settled fast by second
  4; the cheap shape's 99- to 177-site methods cycled for good in a tenth to a half. Which
  loop keeps re-hoisting a failing predicate is not read from its size.
* **Every `MemorySegment` a loop keeps live is a set of checks C2 hoists before the loop**, and
  their count is one thing that decides the cycle (`VARKA-198.md` 12). The Vector API's
  segment loads and stores carry the segment's class, read-only flag, length, session state and
  the session's confined-owner test; C2 hoists them per segment as profiled predicates once it
  no longer scalar-replaces the segments. VARKA-198's producer loop went from six live segments
  to twelve - one per prefix vector stored - and cycled at 8 to 60 outputs at both widths on
  the batches path; one segment over the whole scratch, addressed by offset, brought it back
  to seven and to compiling once. So a kernel body should hold as few segment objects as its
  work allows: one per region of memory, offsets for the rest. To read such a fork:
  `VARKA_DEOPT_DUMP=<dir>` makes `VarkaDeoptCycleProbe` write the class, and
  `-XX:CompileCommand=print,<class>::loopDense0` with `dev/varka_emit.sh`'s `hsdis` prints
  every C2 version; the deoptimization log's `pc=` is the return address after the
  `UncommonTrapBlob` call, and the check is the compare that branches to that stub.

## A call-site budget keeps wide groups under C1, and a narrow heavy group runs under C2 alone

VARKA-209's remedy for the cliff above (`VARKA-209.md` 11 and 13): the emitter reads each
group method's Vector API call sites off the built class, beside the bytes it already measures,
and splits a group whose loop or epilogue is over 93 - C1's last compiled count on JDK 25 -
through the same regroup the byte budget uses. What the build and its review taught, beyond the
census:

* **Count the unit C1 counts, from the class, not from the weights.** The grouping weights
  over-count a cheap tail by two (a field's seven plus one, against three sites emitted), and
  the boundary is six sites wide, so a weight-based budget splits at fourteen tails what C1
  compiles at twenty. `VarkaEmittedClass.vectorCallSites` is the count; the masks' calls are
  left out because the census's boundary had none, and the `make_date` shape's ten masks an
  output move no grouping.
* **A budget in C1's unit is a rule for wide groups only.** Applied to every group over it, the
  budget split the `make_date` ladder to one output a method - 2.3 times slower at steady state
  for sixty outputs, the shared-prefix file's sixty-group arm - and declined VARKA-190's
  hundred-entry kernel, whose single-entry methods were still past C1 at 150 sites each. A
  group of few heavy outputs gains no C1 from a split and pays a call, a loop and the prefix's
  loads per method per batch for good; so the budget splits a group only while it holds more
  than `HEAVY_GROUP_OUTPUTS` (six) outputs. The census's numbers back the exemption: five
  `make_date` outputs a method, 313 sites, settled by second 4 and cycled in no fork of 116,
  where the cheap tails past C1, with over twenty output segments live, cycled in a sixth to a
  half - the segment count VARKA-198 tied the cycle to.
* **Under materialization a split's producer carries six stores more than its prefix**, so the
  first half of a split group can sit over the budget by exactly those stores (a `make_date`
  pair split by force: 99 and 68), and a later group loads the prefix in six sites where the
  decomposition cost thirty-three (twenty-two tails: 71 and 42). Read a split's counts from the
  class rather than adding them up.
* **The oracle does not see wide groups.** No coverage row is a wide cheap group - no single
  expression is one - and the fuzzer's shapes are single roots of many nodes, heavy groups all;
  `emitted_bytes.json` moved nothing under the budget, and the guard on the budget's behaviour
  is `VarkaEmitterBudgetSuite`'s pinned counts and `VarkaInliningCliffSuite`'s forks, not the
  oracle.
* **Bound the epilogue too.** An epilogue C1 refuses has no back edge to reach C2 by: it runs
  interpreted on every batch that leaves a remainder until invocation counts bring C2, which
  is thousands of batches, where a loop method's back edges bring C2 in seconds. The committed
  deopt logs show C1 refusing per-group epilogues. A group's epilogue carries its loop's count
  on every shape measured, so reading both costs no split.
* **A budget that bounds a delay must never cost a kernel.** Each split gives the driver a call
  more in each form, so splits can push the driver past the byte budget where the unsplit
  grouping fits; the emitter builds such a class again without the call-site budget rather
  than decline, since a method C2 compiles in seconds still beats the row fallback.
* **The dump says which methods are past C1.** `dev/varka_emit.sh` prints `PAST C1:` for a loop
  method over the budget; under the defaults that is a heavy group left on purpose, and the
  line is information, not a finding to fix.


## Split methods compile, but C2 stops inlining their calls at the caller's budget

Splitting generated code into methods keeps every method under the 8000 bytes HotSpot
compiles, but it does not keep the calls cheap. C2 inlines callees into a caller only until
the caller's inlined bytecode reaches `DesiredMethodLimit` (8000 bytes in HotSpot's source, a
develop flag no product JDK can change) and refuses the rest with `size > DesiredMethodLimit`.
The refused methods are then compiled by C2 on their own, so nothing runs interpreted, and
each row still pays a real call per refused method, with no optimisation across the calls.

* **Read it per caller, with the compile's tier.** `-XX:CompileCommand=PrintInlining,<class>::*`
  prints call-site lines with no header naming the compiled method; add
  `-XX:+PrintCompilation` and take the lines after a tier-4 header, skipping the "made not
  entrant" lines that share the header's shape. Without the tier, C1's refusals ("callee uses
  too much stack") read like C2's.
* **On vanilla Spark's projection outside a stage**, a `CASE WHEN` of 16 branches splits into
  6 methods and C2 inlines 5; at 300 branches it splits into 101, grouped into one caller, and
  C2 inlines 11 and refuses 90 for the budget, identically in every fork under `-Xbatch`.
  `VarkaSplitInliningSuite` pins both ends; see `VARKA-181.md` 10.

## A method filled to the byte budget by prediction cost thirteen times its neighbours on a four-core runner, and a margin is what bought it back

- VARKA-236's item 71 section ran sixty-four cheap tails under three groupings: the weights
  (four loop methods), the cost model's prediction without a margin (three, each filled to
  near the 8000-byte budget) and the prediction with the fit's margins (four again). On the
  laptop the three read 6, 8 and 7 ms, a millisecond apart. On the GitHub runner, an Intel Xeon
  Platinum 8370C with four cores, they read 17, 217 and 17 ms: the filled methods' arm was
  thirteen times slower than either neighbour for the whole measurement, two seconds of warm-up
  and five iterations of two seconds (`VarkaWideKernelBenchmark-jdk25-runner-8370c-results.txt`,
  3 October 2026). Every other family of the section read the same across the three arms on
  both machines.
- The record's explanation, not verified with flags on the runner because a runner's JVM
  cannot be probed after the fact: a method near the budget takes C2 seconds to compile, and
  on four cores shared with the benchmark the compile did not finish inside the window, so the
  arm was timed on C1 code; the laptop's eight pinned cores finished it during warm-up. It is
  the cliff "A wide loop method's cliff is the time before C2" above, met on a slower machine
  than the one that found it. The fit's margins (21.1% of the byte budget) close the group one
  output earlier and the runner reads it as the weights' arm.
- The lesson for a default: a grouping that is right by the budget on the development machine
  can be wrong by the compile window on the machine the number is published from, so a
  grouping default is decided on the runner's reading, and with the margin the fit derives,
  never at the budget's edge. `predictGrouping` ships with `planSize` for this reason and
  neither alone (`VARKA-236.md` 9.3).

## C2 vectorizes unit-stride columns in the heap and off it, and never rows 32 bytes apart

A per-row hash of four int fields over 65,536 rows, on JDK 25, with the auto-vectorizer on and
then off (`-XX:-UseSuperWord`): heap columns 0.150 and 0.799 ns a row, off-heap columns (one
`MemorySegment` a field) 0.141 and 1.018, rows 32 bytes apart in one segment 0.837 and 0.837. The
segment loops are vectorized as well as the array ones, so the old rule that arrays vectorize and
segments do not no longer holds for unit-stride access; what decides it is rows against columns.
The Vector API adds nothing for heap columns and about 12% for off-heap ones. Most passes over an
IR are dependency chains no vectorizer touches; the candidates are hashing every row and the
rebuild's remap (`VARKA-291.md` 9.7; the script, `vector-loops.sh`, is in
commit `f77e5fb9ea6`, removed since: `VARKA-291.md` 9.9).

## Value records crash C2's escape analysis on the early-access Valhalla build, and the crash is in a method handle

On `27-jep401ea3+1-1`, the real IR compiled with `value` on its 37 records crashes the JVM with a
`SIGSEGV` in `ConnectionGraph::optimize_ideal_graph` once the run is long enough for C2 (the
full corpus; the 321 small committed graphs did not, until the largest was added). The method
being compiled is always a `LambdaForm$MH::invoke`; `-XX:-DoEscapeAnalysis` and C1 only avoid it,
and the plain records on the same JDK do not crash. Value types lean on escape analysis, so
measuring them with it off understates them: keep reflective code out of the measured JVM
(`VARKA-291.md` 9.5).

## With the intrinsics off C2 takes twenty seconds a loop method, and an epilogue's compile 337 MB

From VARKA-272's forked arms (14 October 2026, `VARKA-272.md` 2.9).

- Under `-XX:-EnableVectorSupport` every Vector API call is the plain-Java fallback, a per-lane
  loop over a lambda, and C2 inlines all of it: JFR's `jdk.Compilation` events put the int lane's
  `loopDense0` at 23,900 ms of compile time, `loopMasked0` at 24,600 and `epilogueDense` at
  29,000 for one drawn kernel, one compile skipped on "concurrent class loading", and the compiled
  code trapping on `profile_predicate` in `IntVector.ldLongOp` and `bTest` once it ran; the long
  lane's methods compiled in 55 to 490 ms. No warm-up settles in 10 s, nor in 90. The fallback is
  a correctness reference and a trap: a JVM that reaches the module without the intrinsics
  (`EnableVectorSupport=false` at startup) runs Varka's kernels under C1 and the interpreter for
  minutes before C2 code arrives, if it arrives. Declining at startup on that flag is a candidate
  row.
- `-XX:CompileCommand=MemStat,*.*,print` prints each compilation's arena usage at exit. On the
  first drawn kernel of seed 20261014 C2 used 337 MB on `epilogueMasked` and 331 MB on
  `epilogueDense` under the default flags, and 1,160 MB and 1,032 MB under the seeded stress flags
  with `StressIncrementalInlining`. A fastdebug build has a per-compilation limit of a gigabyte
  that crashes when hit (`Hit MemLimit`), which is how this was found: the fastdebug arm crashed
  on its first warmed kernel until its limit went to `-XX:CompileCommand=MemLimit,*.*,2G~crash`.
  The product build has no limit. A third of a gigabyte for one epilogue's compile is a cost the
  byte budget does not see; bounding it is a candidate row.
- `StressUnstableIfTraps` throws away a warm-up's code on random traps: two int-lane cases in five
  never settled, each at the 10 s deadline per warmed history, seven times the arm's cost. The
  `quiet` histories take their traps on purpose, so the flag stays out of the nightly's stress set.

## With the trap limits raised, a masked loop traps thousands of times and still reads as settled

From VARKA-378 (16 October 2026, `VARKA-378.md` 9.2), which tried to plant the deoptimization
cycle by raising the JVM's trap limits under the nightly's NULL-mode guard: the IR fuzzer's
compiled test, draws 0 to 99 a lane at seed 20260903, with `-XX:PerMethodTrapLimit=100000
-XX:PerBytecodeTrapLimit=100000` (the census's arm L, `VARKA-326.md` 3.1).

* **The traps did not stop, and the cycle did not appear.** The masked loops trapped at
  `profile_predicate` 320,187 times at the preferred width and 555,548 times at 128 bits; int-lane
  draw 67 alone took 3,898 traps on `loopMasked0` in 0.55 s, every one `maybe_recompile`, with
  three `made not entrant` lines over the whole class. Yet every kernel was reported settled,
  draw 67 at 897 ms, and VARKA-189's rule (a loop method made not entrant three or more times with
  more than four traps) counted no kernel cycling: the guard counted 0 and 1 against its ratchets.
* **Why both checks miss it.** The warm-up's verdict reads the code list, which shows the loop at
  tier 4 between traps; the cycle rule counts recompiles, and a `maybe_recompile` trap under raised
  limits throws the frame to the interpreter without throwing the code away. So a loop that
  leaves its compiled code at every such trap is invisible to the warm-up's check, to
  the nightly's `deopt` step and to the `deoptnull` count alike.
* **Under the defaults this form ends**: `PerBytecodeTrapLimit` 4 and `PerMethodTrapLimit` 100 make
  C2 recompile without the failing predicate, which is the cycle those checks do see. So it is not
  what runs today; it is what an operator's raised limits would turn the cycle into
  (`VARKA-295.md` 9.6 raised `PerMethodTrapLimit` to end a different trap sum). A check that
  wants to see it counts traps per second of C2 code, not recompiles.

## A kernel warmed after other kernels can stay allocating, and the template prewarm guards only the int lane

From VARKA-257 (16 October 2026, `VarkaKernelHistory-jdk25-probe.txt`), which warms N kernels of
the IR fuzz grammar through production's warm-up in one JVM and reads each one's verdict.

- **History is a variable a fresh-JVM probe cannot see.** At the 512-bit preferred species, with
  the prewarm off, no kernel at position 0 of a history was released (0 of 12), and past position
  16 28 to 32 per cent were, nearly all with every loop method at tier 4 and still allocating
  (VARKA-295's cause 4). The draws released after a history compiled when run alone (6 of 6).
  A guard that forks one class per JVM reads 0 here by design.
- **The Vector API template prewarm (`VarkaVectorTemplateWarmup`) clears the int lane early and
  leaves the long lane**: int releases before position 16 went from 19 of 84 to 0, and past 16
  from 27.8 to 10.4 per cent; long releases past 16 stayed at about 30 per cent (43 of 144 with it,
  46 without). Do not read VARKA-295 9.8's "0 of 2,000" as covering long-lane kernels: those were
  the Spark fuzzer's compositions.
- **At 128 bits a long-lane kernel releases alone** (6 of 6 seeds, every loop C2): that is
  VARKA-374's shape-level case, so a history reading at 128 bits must be split by lane before it
  is read as history.
- **Replay a release exactly** with the seed and `-Dvarka.history.from=<draw>`
  (`dev/varka_kernel_history.sh --after 1 --forks 1 --jvm -Dvarka.history.from=<k>`): a release
  that disappears alone is the history's.

## Which tier served a call: the code list at the call's edges, JFR's events inside it

From VARKA-274 (13 to 16 October 2026, `VARKA-274.md` 2 and 9), which runs drawn kernels over
batch histories and proves per compared call which tier ran it, with no diagnostic flags.

* **The code list is the in-process `jcmd Compiler.codelist`.** The DiagnosticCommand MBean's
  `compilerCodelist` returns the whole JVM's installed code, one line per version: compile id,
  level, state (`0` in use, `1` not entrant) and method. A read takes about 3.5 ms at a few
  thousand lines, which is the tier witness's main cost; read it once per call and reuse the
  previous call's closing read as the next one's opening read.
* **Read the tier at both edges, and what happened inside from the events.** Code can leave the
  cache with no `jdk.Deoptimization` event (an invalidated dependency is not one), so a call is C2
  code only when the same compiled version of each loop and epilogue method was in use at both
  edges and no trap fell in its window. JFR delivers events about a second late: settle on a
  marker event and one further flush, never on a sleep, and do not let a warm-up decide on a code
  list the stream has not caught up with - it can take an OSR version for a standard one.
* **A small kernel's loop is C2 code inside its caller.** C2 inlines a one-root kernel's loop and
  epilogue into `runDense` or `run`, after which their own invocation counters stop and the code
  list shows them at level 0 forever. Count a method inlined into a caller's level-4 version in
  use (`jdk.CompilerInlining`) as level 4, or every such kernel reads unsettled.
* **A trap is attributed by the compile it threw away.** `jdk.Deoptimization`'s `method` is
  where the trap was taken, often a Vector API method or a runtime helper inlined into a kernel
  method; its `compileId` names the kernel's compiled code. Matching on `method` dropped every
  trap taken in inlined code.
* **An OSR version serves the call after the one it was compiled in.** It is entered at the next
  back edge, and the compile usually finishes while the harness compares the previous batch.
* **Where OSR happens.** With C1 on, as for a kernel run with the warm-up off or released at its
  deadline, cold batches of 10,000 rows enter each loop by a tier-3 OSR around the 100th
  kernel call and a tier-4 OSR around the 165th: the back-edge counter crosses 60,000 a few calls
  before the invocation counter reaches its minimum. With C1 excluded, the warmed path, nothing
  is OSR-compiled even in one call of 2,000,000 rows.
* **The warm-up's allocation verdict fires before the epilogues are compiled.** It reads what a
  1,024-row call allocates; an epilogue running only the remainder allocates too little to show,
  so in 32 of 39 drawn kernels an epilogue was still interpreted when the verdict fired.

## Why a warmed kernel still allocates: C2 sums class-check traps over the whole compile

From VARKA-295 (15 and 16 October 2026, `VARKA-295.md` 9.5 to 9.9), the cause of the warm-up
flake, which released a few kernels in a hundred warm-ups as compiled and still allocating.

* **The mechanism.** The Vector API's templates (`VectorOperators.opKind`, `ImplCache::find`,
  the `lanewise` forms) are shared by every kernel in the JVM and carry one profile each.
  Standalone C2 compiles of them speculated on the operator class they had seen and trapped on
  the next, and C2 charges a shared method's trap and decompile counts to every compile that
  inlines it, once per inlined copy, against two whole-compile limits: class-check traps against
  `PerMethodTrapLimit` (100) and decompiles against `PerMethodRecompilationCutoff`. A clean loop
  method totals 4 traps; a released one passed 100 at parse 427 of 3,309, with `opKind` inlined
  170 times at 4 to 5 traps each. Past the limit the miss path is a real virtual call, the vector
  joining from it is a non-exact `IntVector`, the `binaryOp` intrinsic refuses it, and the box
  that stays is a `new int[16]` an iteration that escape analysis cannot remove: 80 bytes, 20,480
  a call of four methods at 64 iterations. Counts, not receiver-type profiles: raising both limits
  removed every release in 1,000 warm-ups, and `TypeProfileLevel` moved nothing.
* **The fix.** `VarkaVectorTemplateWarmup` runs once per JVM before the warm-up worker's first
  job: every Vector API method the emitter's kernels call, on `IntVector`, `LongVector` and
  `DoubleVector` at the preferred species, with the operator classes interleaved in each round so
  every template site sees several while it is profiled. At the preferred species the warm arm's
  deadline releases went from 442 to 0 in 2,000 compositions on each of five seeds. It does
  nothing at a 128-bit preferred species (VARKA-374) or under a 4-lane override (VARKA-373), and
  the section above says what it leaves on the long lane after a history.
* **Reading the allocation probe.** A last-probe value is calls x iterations x array size, so
  20,480 bytes is exactly one surviving vector array an iteration and the larger values are its
  multiples. A magnitude alone cannot tell compiled-with-residue from a loop still interpreted,
  whose one long call boxes tens of kilobytes, which is why the rule that allowed 32 times the
  settled bytes was withdrawn.
## A template warm-up run after the templates' first compile deoptimises them, and a wide kernel sums one decompile past C2's cutoff

Found by milestone 7's close, which regenerated `VarkaColdStartBenchmark` and read RELEASED on
every projection shape of 16 to 100 entries, on the laptop and on two GitHub runners alike, where
the file of 28 September had every shape COMPILED (`m7/VARKA-389.md`). The kernel's bytecode was
identical before and after; the compiled loops allocated; the change was VARKA-295's template
warm-up (#827), and the JIT's log said why in two runs, warm-up on and off:

* **C2 sums an inlined method's `MethodData` counts once per copy.** `Parse::Parse` adds each
  parsed method's trap counts and decompile count to the compile's totals, and past
  `PerMethodTrapLimit` (100) traps or 201 decompiles (`PerMethodRecompilationCutoff`/2 + 1) the
  compile keeps a real virtual call on every class-check miss instead of an uncommon trap. A
  four-output group method inlines `IntVector.lanewise` about six hundred times and the operator
  cache three hundred and fifty, so a count of one on a shared template is 600 in the kernel's
  compile; a single-output group inlines a hundred copies and stays under. That is the whole
  difference between the wide shapes that released and the range filters that compiled, and it
  is machine-independent, which is how it reads the same on three CPUs.
* **The template warm-up deoptimises templates that are already compiled.** In a JVM whose first
  vector code was not a warm-up job (the benchmark's unwarmed arms, a session without the
  warm-up, the query-path helpers), the templates reach their standalone C2 compiles from one
  kernel's profile first; the warm-up's rounds then take branches those compiles never saw, eight
  `unstable_if` traps reinterpret them in one second, and each template keeps `decompiles='1'`.
  With the warm-up off there was not one trap in a template and not one virtual call in the
  kernel. Run before any vector code, as VARKA-389 moved it, the warm-up has nothing to
  deoptimise and keeps what VARKA-295 measured. The round count does not matter (100 rounds
  released the same way); the order does.
* **How to read it.** `-XX:+LogCompilation`, then the kernel's `<task>` for a loop method: count
  `<virtual_call>` (367 against 0), find the first `<observe trap=... mdecompiles='201'>` and the
  `<parse method=...>` it sits in, and tally the `<parse>` elements per method for the copies.
  The deopt that put the count there is an `<uncommon_trap>` whose first `<jvms>` frame is the
  template, with the compile id of the standalone nmethod. JFR's allocation samples name the
  boxed fallbacks (`IntVector.lanewiseTemplate`, `uOpTemplate`, `blendTemplate`, `bTest`) under
  the kernel's compiled frames; PrintInlining shows the symptom as "no static binding" on
  `IntVector::lanewise`, not as a size cutoff.
* **The two JVM limits are the other lever.** With `PerMethodRecompilationCutoff` and
  `PerMethodTrapLimit` at 100,000 the same JVM compiled the wide shape in 1.4 s with the warm-up
  on and its steady state read 16 ms, the best of every arm; a global flag, so a deployment
  decision rather than a library fix, and VARKA-295 measured both at 0 releases in 1,000.
* **On four cores the same symptom has a second, older cause: the template's first compile
  lands at tier 2, and its profile never matures.** The tiered policy
  (`CompilationPolicy::transition_from_none`, JDK 25) sends a method due for tier 3 to tier 2,
  C1 code with counters and no profiling, while C2's queue is longer than `Tier3DelayOn` (5)
  times C2's threads, ten entries on a four-core host (two C2 threads). The template's first
  compile is late there: C1's queue scales the tier-3 thresholds (`threshold_scale`, `k` up to
  6.6 in the trace with one C1 thread), so `IntVector.max` crossed `200 x k` at 640 to 770 calls,
  200 to 300 ms after the template warm-up's rounds (which call it once a round), in the kernel
  warm-up, with C2's queue at 11 to 26. Passing run: queue 7 at that instant, tier 3, profile
  fed to 23,552 calls, kernel clean. Failing run: queue 17, tier 2, profile frozen at 641 calls,
  then straight to tier 4 at the first tier-2 report; C2 trusts a profile only past 1,000 calls
  (`ProfileMaturityPercentage` 20% of the tier-4 count), so the kernel's compile read `<call ...
  count='-1'>` at the template's inner `lanewise` call and kept a virtual call, which boxes the
  vector (`m7/VARKA-389.md` 9.5). Read it with `-XX:+PrintTieredEvents`: every decision prints
  `queues=c1,c2`, `k=` and `mdo=`; the `[compile level=2 ...]` line of a template, and the
  `mdo=` count under 1,000 at its `[compile level=4 ...]`, are the failure in two lines. Tell it
  from the sums above by the tiers in the log (`<nmethod ... level='2'>`) and by the absence of
  any `observe trap` sum in the kernel's compile. Pacing the warm-up's rounds on C2's queue
  cannot reach it, since the compile fires after the rounds: paced every round to the policy's
  limit the suite passed twice and released once; to the resume limit, with the queue at most 7
  through the rounds, it released three times of three. A 4-core AVX2 runner is where CI meets
  it, so a pass of the end-to-end warm-up suite on the pool was a draw. Why tier 2 is final for
  these wrappers: C1 at tier 2 marks a call as a profiling point only when its target is final
  (`GraphBuilder::invoke`, `need_null_check`), and `max(Vector)`, `min(Vector)`, `add(Vector)`
  hold one call each, to the abstract `lanewise(Binary, Vector)`, so their tier-2 compile says
  "nothing to profile" and `transition_from_limited_profile` sends them straight to tier 4;
  `min(int)` calls the final `lanewise(Binary, int)` and went to tier 3 in the same run. The fix
  (`m7/VARKA-391.md`) is in the warm-up: eight calls a round for every one-line wrapper, so each
  crosses its first threshold inside the rounds and passes the policy's old mark of 2,000 calls
  before they end, and the rounds paced on C2's queue to the policy's resume line
  (`Tier3DelayOff` times the threads); the wrappers then compiled at tier 3 with the queue at 0
  to 2 in every traced run, and the suite passed every test in every run on four cores.

## Reading C2's queue from Java stops the JVM, and below four processors HotSpot runs one C2 thread

Found by the post-merge reviews of the template warm-up's pacing and measured in
`m8/VARKA-395.md`:

* **`Compiler.queue` is a safepoint.** The diagnostic command (reached from Java through the
  `com.sun.management:type=DiagnosticCommand` MBean's `compilerQueue`) runs `VM_PrintCompileQueue`
  on the VM thread (`diagnosticCommand.cpp`), and a VM operation is evaluated at a global safepoint
  unless it says otherwise (`vmOperation.hpp`), which this one does not. Each reading stops every
  Java thread. In the end-to-end warm-up suite the median reading cost 8 to 22 us, but on two cores
  or a loaded machine single ones took 2 to 6 ms, and about 320 readings summed 10 to 36 ms
  there against 6 to 9 ms on four idle cores. Read `-Xlog:safepoint` for the
  `Safepoint "PrintCompileQueue"` lines. No cheaper reading exists: the `sun.ci` perf counters
  count compiles and times, `sun.ci.threads` is C1 and C2 together, and `CompilationMXBean` has
  only the total time. So a loop that polls the queue pays a safepoint a poll; poll at the speed
  the queue moves (a C2 compile is milliseconds to hundreds of them), not faster.
* **HotSpot's compiler-thread split has a floor.** `CompilationPolicy::initialize` gives C1
  `max(count / 3, 1)` threads and C2 the rest, at least one, and the ergonomics choose
  `CICompilerCount` = `max(log2(n) * log2(max(log2(n), 1)) * 3 / 2, 2)` for `n` processors: 2 at
  1 to 3 processors, so one C2 thread, 3 at 4 to 7, 4 at 8, 12 at 24. `count - count / 3` without
  the floor reads two C2 threads at a count of 2, and so doubles every queue limit the policy
  computes from C2's threads (`Tier3DelayOn` and `Tier3DelayOff` times C2's threads) on exactly
  the small hosts where the queue decides tiers. Check it with `jcmd <pid> Thread.print` under
  `-XX:-UseDynamicNumberOfCompilerThreads`, which starts every compiler thread at once. With a
  JVMCI compiler the top tier's queue is named "JVMCI compile queue" (`compileBroker.cpp`), and
  with its native library the split is `JVMCINativeLibraryThreadFraction` of the count.

## A warm-up outside the sanitizer's window makes C2 trap at its first checked call

Found by `VarkaTierWitnessSuite` failing under the memory sanitizer and read in `m8/VARKA-400.md`:
a warmed history reported as `Trapped[reason=speculate_null_assert, action=make_not_entrant,
at=VarkaMemorySanitizer.check]` was not a memory violation but a deoptimization. The check tests
its thread's window against null, and the warm-up ran on a thread with no window, so the profile
said the value was always null. C2's parser turns an `ifnull` whose operand's speculative type is
always null into a `null_assert` guarded by an uncommon trap of that reason (`opto/parse2.cpp`,
`handle_if_null`), and the first call that opened a window threw both drivers' code away. The
rule: test-only instrumentation inlined into a kernel must see the same state while the kernel
warms as when it is measured. A warm-up and its measured calls both run in the sanitizer's window
(`VarkaCompiledCheck`'s `Args.call`), or both outside it. A trap's `at=` naming a test helper,
with a `speculate_*` reason, points here before it points at the kernel.

## A warmed kernel measured through another entry can run code the verdict never read

Found by `VarkaKernelWarmupSuite` failing on runners and read in `m8/VARKA-398.md`: a warm-up
reported `COMPILED`, and then the batches with some nulls allocated sixteen interpreted calls of
one light loop. The warm-up's calls enter a kernel through `VarkaFusedKernel.run`'s scratch form,
an interface default method; in a JVM with one kernel class C2 compiles it with the whole kernel
inlined, so the verdict can be earned by that inlined copy while the kernel's own `run` and a
light loop called only through it still have no standalone code (a separate compile, queued late
or never requested once the inlined copy took its calls). The test called the kernel's own `run`
and met those methods interpreted. The rule: a measurement that checks a JIT verdict enters the
kernel through the method the verdict's calls enter (`PrintInlining` shows which call sites inline
it; neither of the warm-up's does), since an inlined copy and a standalone compile are different
code and only the allocation tells them apart.

The bytes name the method and the runner's width. An interpreted lane-group loop boxes each
vector it makes: `date_add(d, k)`'s load, broadcast and sum are three per lane group, 64 bytes
each at 256 bits over 128 groups of a 1,024-row call and 96 bytes each at 512 bits over 64, so
24,856 and 18,832 bytes a call with the driver's segments. A failure's byte count divided by the
calls, set against that arithmetic, says which loop ran interpreted and on which class of runner
before any log does.

## A second kernel class through the default `run` did not leave the first kernel on code that boxes

Measured in `m8/VARKA-405.md` to test row 398's worry that a warmed kernel's verdict, earned by
`VarkaFusedKernel.run`'s default method with the kernel inlined, would not survive a second kernel
class reaching that method (the compile's class check fails, the compile is thrown away, calls fall
back on the kernel's own `run` chain). It did not reproduce: in 12 forked JVMs (10 read the state afterwards), on one to
twenty-four cores beside up to six busy loops, a first kernel warmed to `COMPILED` and then three
more shapes warmed through the same method had C2 code for its `run` and its four loop methods
afterwards, and sixteen calls of 1,024 rows allocated 0 to 9,600 bytes through the scratch form and
through its own `run`, against 397,696 for one interpreted loop. Right after the verdict the
loops had C2 code and `run`, `runDense` and `runMasked` had none (two runs on one core): the
chain's compiles are requested by the same calls that request the default method's, and the
verdict waits for the loops, so the part still missing is the driver, whose interpreted code
allocates memory segments and not vectors. So a verdict does not need the "wait for the kernel's
own chain" step the worry proposed; what stays open is a queued loop compile that never lands
before the verdict, seen once on runners and not on the laptop.

`VarkaKernelWarmupProbe <claim> others` runs that sequence, and `-Dvarka.probe.busyThreads=<n>`
with `taskset` and `-XX:ActiveProcessorCount` starves its compiler threads. Reading a kernel's
state with `VarkaKernelWarmup.jitState(Class)` needs the kernel's class, not the interface's:
`jitState(VarkaFusedKernel.class)` lists the default method's tiers, a different question.

**A lane count other than the JVM's preferred one never earns a verdict.** The template warm-up
compiles the shared Vector API templates for the preferred species, so a kernel emitted at another
count (`lanesOverride`, set by the suites and the option matrix, never by a session) boxes every
call and its warm-up is `RELEASED` at the deadline: at 4 lanes under `-XX:MaxVectorSize=32`
(preferred 8) 253,952 bytes a call, and the shape's batches on Spark's row path until then. The
option is documented and the warm-up suite cancels its compile tests there; refusing it would end
the option matrix's lane-count configurations, which are its point.

## HotSpot counts calls per dispatched method, and stops counting a callee C2 has inlined

Read in `m8/VARKA-397.md`, building the check that the template warm-up calls every Vector API
method the kernels call past the tiered policy's "old" mark: 2,000 calls,
`Tier3InvocationThreshold` times `TieredOldPercentage` / 100 in `CompilationPolicy::is_old`. Two
facts decide what a count of calls means to the policy:

- **The counter belongs to the method a call dispatches to, not to the call's symbolic reference.**
  `Vector.convertShape` is implemented by each vector class, so a kernel's call is
  `Int512Vector.convertShape` or `Long512Vector.convertShape` by its receiver, one counter each;
  every `reinterpretAs*` call, whether written against `Vector` or `LongVector`, is the one
  `AbstractVector` method; a call written against `VectorMask` counts on its lane's mask class
  (`Int512Mask.and`). So calls are compared by their dispatch target, found by walking the
  receiver class up to the first declaration with a body, and a warm-up's calls are counted with
  their receivers, since a call site written against `VectorMask` serves every lane.
- **A callee's count stops once a compiled caller has inlined it, unless the caller runs at tier
  3.** Only C1's full-profiling tier counts inlined calls: `profile_inlined_calls()` is
  `profile_calls() && C1ProfileInlinedCalls`, and `profile_calls()` requires
  `CompLevel_full_profile` (`c1_Compilation.hpp`); tier 1, tier 2 and C2's code do not count
  them. A count taken at the call sites equals HotSpot's while the callers run interpreted or at
  tier 3. The policy sends a caller to tier 2 instead of tier 3 when C2's queue is over
  `Tier3DelayOn` times C2's threads at its first threshold (`compilationPolicy.cpp`, `common()`),
  so a warm-up that counts on its callees' counts depends on its pacing keeping that queue short;
  and a caller past C2's threshold (`Tier4InvocationThreshold`, 5,000) stops counting what C2
  inlines.

`TieredOldPercentage` is a diagnostic flag: `HotSpotDiagnosticMXBean.getVMOption` reports it as
absent unless the JVM runs with `-XX:+UnlockDiagnosticVMOptions`.
