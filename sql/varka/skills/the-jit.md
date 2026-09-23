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

  **Update, task 43: this no longer reproduces, on the same path, at four times the width.**
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
  September 2026: the compile-time argument is gone from it, replaced by task 71's survey -
  raising the budget past 24 regroups one shape of nine for one lane op, while growing every
  method toward C1's refusal threshold. The constant is unchanged at 16 and its justification
  is now a measurement of what the budget does rather than of what compiling costs.*

  **And it disagreed with another number this repository already carried.** `PLAN_MILESTONE_4.md`
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
- Same family, earlier finding (task 10): two vector loops emitted into one method
  also degrade each other (3x-4x on the second loop). One C2 compilation per hot
  loop, always - sibling methods, not longer methods.
- Same family, task 14's post-commit diagnosis: **a class defined per task re-pays
  the whole tier ladder per task.** The per-task loader defines a fresh kernel class
  each task; HotSpot treats it as new, so every task runs interpreter, then C1 with
  boxed vectors, then the C2 OSR compile - a *fixed per-task* cost that grows with
  the loop method's vector-op count (~13 ms for an 8-op chain, ~50 ms for the 20-op
  dayofweek fold) and dwarfs the ~80 us emission it sits next to. Two diagnostics
  that pin it: (1) scale the table 4x - a per-task-fixed cost leaves the absolute
  delta unchanged where a per-row cost quadruples it; (2) `-XX:+PrintCompilation`
  shows one tier-4 OSR of the same-named method *per task*, each followed by
  "made not entrant: OSR invalidation" as the task's class dies. The decomposition
  (PLAN_TASK_14.md 7.5): the C2 compile itself is ~1 ms per vector op (2/10/20-25 ms
  for 3/10/20-op loops), and the interpreted and C1 profiling phases before it
  scale the same way, because tier counters advance per backedge at boxed speed -
  which is also why a scratch-batch warm spin saves nothing. Corollary for any
  cross-task cache: caching `byte[]` does not help - a re-defined class is a new
  class and re-pays the ladder; only reusing the *loaded class* preserves the C2
  code. And benchmark tasks must be long enough to amortise the ladder, or the
  committed number prices JIT warm-up, not the kernel. Task 18 acted on the
  corollary - `VarkaShapeCache` shares the loaded class across tasks, keyed on
  the IR shape - and the committed depth curve flattened from 2.2x-eroding-to-1.3x
  into 6.5-7.2x flat, confirming the ladder was the whole erosion.
- Second corollary, caught by task 18's PR review after the results file was
  committed: **a cache keyed on structure silently defeats a harness that
  manufactures freshness through values.** `VarkaColdStartBenchmark` made each
  iteration "fresh" via distinct columns and literals - exactly what the shape key
  ignores by design - so after task 18 the guard query warmed the process-wide
  cache and every timed "cold" iteration measured a hit while the harness's own
  comments still promised a fresh emission. When a cache key changes, re-derive
  every benchmark's freshness argument from the new key rather than trusting the
  harness; the fix here invalidates the shape cache inside the timer loop.
- **The 64-op cliff does not generalize to every wide method - it is specific to
  what was compiling it.** Task 32 step B2 built the real thing the cliff worried
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
  bites: task 24 made it one method over *every* output, so its size grows with the
  whole projection rather than with a group, and four calendar fields over five date
  columns crossed 8000 bytes. Measure it rather than estimating - the `Code`
  attribute's length, which is exactly what HotSpot measures, is two lines through
  `java.lang.classfile` (`VarkaEmitterTestSupport.codeSize`) - and assert the crossing
  in a test, so the next wide node moves a number instead of quietly falling off.
  Task 32 step B1's ladder is in `PLAN_TASK_32.md` section 7.1.

## A forked probe's warm-up count buys a compile request, not a compile

The assembly suite's child (`VarkaAssemblyProbe`) calls a method 200000 times so that C2
compiles it, then measures and prints. The count is far past `Tier4InvocationThreshold`, and
it was still not enough: on GitHub's four-core runners the gate failed one run in three with
a C1 body for the hand-written kernel (`saw c1`) and scalar C2 bodies for the 128-bit cases,
byte-identical from run to run, while the same commit passed on a re-run. The laptop
reproduces the four failures when sbt and its children are pinned to two cores that busy
loops already occupy (task 150).

Background compilation is the reason. Crossing the threshold queues a compile; the calls keep
running in the interpreter or in C1 while the compiler thread waits for a core, and on a
starved machine the calls run out first. A C2 body that does arrive early, before the vector
classes the intrinsics need were loaded, is the Java fallback: scalar, and boxing when
inlined, which is what the identical instruction counts were.

`-Xbatch` on the child makes the compile happen on the calling thread, so the crossing call
returns with the nmethod installed and a count-based warm-up means what it says. Under the
same starvation the suite goes from four failures to none. The flag is already what
`PLAN_TASK_153.md`'s width probe runs under, for the related reason that a synchronous
compile is one whose diagnostics attribute to the method that asked for it.

The general rule for any harness that forks a JVM and reads what C2 did: either compile
synchronously, or wait for evidence of the compile (a `PrintCompilation` line for the
method, a WhiteBox query), and never infer it from an iteration count, however generous.

## Watching what C2 compiled, at runtime, with no flags

JFR's `jdk.Compilation` event carries `method`, `compileLevel`, `isOsr` and `codeSize`, and
`jdk.jfr.consumer.RecordingStream` consumes it in-process with no agent and no diagnostic flags.
That makes compiled size observable in any JVM, which the bimodality section above needed a
fastdebug build and `PrintAssembly` to see. Task 50 builds this; four things it cost to learn.

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
  compiled exactly once per JVM, and task 32's spread was *between* runs - "stdev 0 inside a run,
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
came out of task 31's feasibility check, run against the system JDK 25.0.4 product build with
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
places. That is exactly the sibling-method structure task 24 built and `GROUP_BUDGET` exists to
control, so the flag works against the emitter's design - and it would have to be set on every
executor to do so. If method fusion is ever worth measuring, produce it from the emitter with
`withGroupBudget`, which is per-shape and needs no flag.

`-XX:+PrintInlining` explains why the bodies survive: the directive overrides the size heuristic
and lands on a harder limit. `VarkaVectorSupport::orValidityBitsAt` (212 bytes) goes from
`failed to inline: callee is too large` to `failed to inline: NodeCountInliningCutoff`. The
reason changes, the outcome does not - which is also evidence that task 46's inlining problem
cannot be solved with a flag.

**Measure the caller, not only the method you are interested in.** The finding above was nearly
missed: the A/B started on `loopDense0`, found it identical under both configurations, and would
have concluded "the flag changes nothing". It changes the *caller*. When a flag affects inlining,
the method whose body moves is the one doing the calling.

**What the kernels actually compile to**, read this way rather than inferred from a ratio, on a
Zen 5 host at AVX-512 (JDK 25 product build). `DateVectorOps.vectorAddDays`: 5 `vpaddd`, 23 `%zmm`
operands. `ChronoVectorOps.vectorFourFields`: 15 `vpaddd`, 13 `vpmulld`, 7 `vpsrld`. The emitted
`year` loop body: 10 `vpaddd`, 8 `vpmulld`, 4 `vpsrld`, 63 `%zmm` operands. The emitted
`dayofweek` body: 65 `vpaddd`, 26 `vpmulld`, 39 `vpsrld` - task 14's range-narrowed magic is
packed, which is the whole reason that lowering exists. An emitted comparison: 15 `vpcmpnled` and
15 `vpblendmd`, no branch. Everything Varka emits or hand-writes for date work vectorizes; that
was an assumption until task 31.

## A bimodal kernel is usually the register allocator, and here is how to prove it

Task 32's shared four-field calendar kernel ran at either 165 or 236 M rows/s under
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

**Update, task 32 step B2: the "structural fix" above turns out to be a property of this
one hand-written kernel's bytecode, not of "four outputs in one method at 128-bit."** Once
the emitter's own fragment mechanism made the *real* shared-loop-method shape buildable
(`VarkaEmitOptions.withGroupBudget(200)`, four calendar outputs genuinely fused into one
generated method - the same four-output-one-method shape this section just said to avoid),
it was measured at 128-bit across three separate runs and showed **no bimodality at
all** - stable to a few milliseconds every time, at every field count from two to four. The
two hand-written kernels this task also built (`ChronoVectorOps.vectorFourFields`, the
"ceiling", and `vectorFourFieldsNoValidity`) remain bimodal on the same three runs, one of
them in a new flavor - flipping between fast and slow *within* a single run's iterations
rather than settling into one mode for the run's duration (`PLAN_TASK_32.md` section 7.4).
So: a register allocation this fragile is a property of one specific compiled method's
bytecode (here, `javac`'s output for a hand-written 936-byte body), not an inherent cost of
the technique it demonstrates. Do not generalize "four outputs in one method is unsafe at
128-bit" from a single hand-written kernel to the emitter's own generated bytecode for the
same shape - measure the actual generated path before declining a design on this basis.

## Two things share the name `uncommon_trap`, and only one of them happened

`PLAN_MILESTONE_4.md` 2.39 read `-XX:+LogCompilation` and reported that a kernel
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

`ChronoVectorOps.vectorFourFields` was built as task 32's throughput ceiling: same
arithmetic, same guard, same op count as the emitted kernel it stands in for. It has no
dense/masked split, though - it always builds a `VectorMask` and uses masked load/store
overloads, even when the caller reports every row non-null. On null-free data the emitted
kernel dispatches to a genuinely unmasked dense body (task 10's split), so the "ceiling" was
silently measuring the masked-body cost the whole time. Once step B2 made the true emitted
dense-shared kernel buildable, it beat the "ceiling" by 1.15-1.20x, reproducibly across three
runs. The lesson generalizes past this one kernel: a hand-written stand-in for an emitted
path is only a fair bound if it takes every fast path the emitted dispatcher can reach for
the data it is fed - a masked-only comparison kernel run on null-free data is not measuring
the same thing the emitted kernel is, and the gap does not announce itself; nothing crashes
or looks wrong, the "ceiling" just quietly is not one.

## A kernel clean at 512 bits can be a per-lane loop at 128, and only the narrow companion shows it

Task 152's `VarkaTimeBenchmark` (20 September 2026): the 64-bit magic divide -
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
  task 124's whole argument for the assembly gate, now with a second example.
- **Read the narrow companion for collapses, not for ratios.** The wide file
  cannot show this class of failure at all; the 128-bit file is where a masked
  or width-specific lowering falls off a cliff, and a row in it at one fiftieth
  of its neighbour is a diagnosis to run, not a slow machine.
- **Masks are the width-fragile part of a 64-bit kernel.** The unmasked steps
  of the same sequence vectorised at two lanes; every masked one did not. A
  long-lane construction that needs a mask - a guard, an overflow test, a
  blend - should be assumed scalar at 128 bits until `PrintIntrinsics` at
  `MaxVectorSize=16` says otherwise (`PLAN_MILESTONE_5.md` 2.89 is that audit).
## Ask C2 which vector calls it refused, per shape, and know which of its three answers is a verdict

Task 153 (20 September 2026) turned one measured collapse into a table by
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
  constant division and nowhere else - task 88's "the converts do not
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
