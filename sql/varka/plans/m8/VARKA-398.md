# VARKA-398: the kernel warm-up probe's verdict on the runners

*Row 398 of `m8/PLAN.md`, filed by row 394 on 19 October 2026 and planned the same day at master
`d49b71a9daf`, after the diagnosis in section 2. Nothing in section 3 was built before this plan.*

## 1. Where this came from

`VarkaKernelWarmupSuite`'s test "a warm-up claimed by a batch with only nulls compiles both of the
kernel's drivers" warms the probe's shape (`add_months(d, k)`, `last_day(d)`, `date_add(d, k)`,
two groups) in a JVM of its own, reads the warm-up's verdict, and then measures sixteen calls of
1,024 rows on a batch with no nulls and on one with some. It has failed on GitHub runners three
times, always the same way: the verdict `COMPILED`, the null-free batches clean, and the batches
with some nulls far over the 20,480-byte limit.

| run | shard, configuration | bytes over the 16 calls | verdict |
| :-- | :-- | --: | :-- |
| 39168155152 | `heavyGroupOutputs=0` | 301,312 | `COMPILED` |
| 39272577973 (master `05242b26d2e`) | 7, `elideChronoMonth=false` | 397,696 | `COMPILED`, 15,980 calls, last probe 480 bytes |
| 39272577973 | 11, the defaults | 397,696 | `COMPILED`, 16,100 calls, last probe 480 bytes |

Shard 11 ran the suite under five configurations on the same runner, and only one failed. The
probe's fork is started with no `varka.matrix.config` and its shapes are built from
`VarkaEmitOptions.DEFAULTS`, so in the option matrix it tests the defaults whatever the shard's
configuration: the three configurations in the table are labels, not causes, which is why row 394
described the first as a failure under the defaults.

## 2. The cause, read

**Which code allocated.** Per call, the counts match one loop's boxing: 397,696 is 16 x 24,856 and
301,312 is 16 x 18,832, and the light group's masked loop, `loopMasked1` (`date_add`, 138
bytes of bytecode, read with `javap` from the emitted class), allocates three vectors per lane
group when it runs in the interpreter - the load, the broadcast of `k` and the sum. At 256 bits
that is 128 lane groups of three 64-byte `Int256Vector`s, 24,576 bytes a call, and at 512 bits 64
lane groups of three 96-byte `Int512Vector`s, 18,432 bytes. The remainder, 280 bytes a call at
256 bits and 400 at 512, is not explained: the laptop's run below leaves 200 at both widths. So,
by inference, the 397,696-byte failures ran on 256-bit runners and the 301,312-byte one on a
512-bit runner, and in each that one loop ran interpreted for all sixteen calls. Checked on the
laptop by
running the probe with that loop and the kernel's own `run` excluded from compilation
(`-XX:CompileCommand=exclude`): 396,416 bytes with `-XX:MaxVectorSize=32` and 298,112 with the
host's 64, within 80 and 200 bytes a call of the runners'. (That run's verdict is `RELEASED`,
because an excluded method is not inlined either; it identifies the allocating code, it does not
reproduce the runner's sequence.)

**Why the verdict did not see it.** The verdict's last probe allocated 480 bytes, so the same loop
was compiled on the path the warm-up calls. The two paths differ in their entry point:

* The warm-up's calls (`VarkaKernelWarmup.Job.call` and `probe`), like the evaluator's
  (`VarkaKernelRunner`), take the form of `VarkaFusedKernel.run` with a scratch address, which for
  a kernel without scratch is the interface's default method dropping the address and calling the
  kernel's seven-argument `run`. In the probe's JVM there is one kernel class, so that default
  method's C2 compile inlines the whole chain: `-XX:+PrintInlining` on the laptop shows
  `VarkaFusedKernel::run` at tier 4 with the kernel's `run`, `runMasked` and `loopMasked1` all
  `inline (hot)`, and `loopMasked0`, too big to inline, called out of line. Neither warm-up call
  site inlines the default method itself (C1's `Job.probe`: "callee uses too much stack"; C2's
  `Job.call`: "already compiled into a big method"), so the verdict's probes run that method's own
  code.
* The test's measured calls (`VarkaKernelWarmupProbe.allocationOfCalls`) call the seven-argument
  `run` on the kernel directly, from interpreted code (the method is never compiled in any of the
  logs). They reach the kernel's own `run` and, through it, each loop method's standalone code.
  Those standalone compiles are separate tasks, requested by the interpreted invocations that
  happen before the default method's compile replaces them, and whether they have landed when the
  verdict comes is a race. On two cores beside three busy loops, `-XX:+PrintTieredEvents` showed
  the kernel's `run` still `in-queue`, at level 0, after 16,133 invocations, while the test's calls
  ran; `loopMasked1`'s standalone compile had landed 20 ms before the verdict, so that run passed.

A claim by a batch with only nulls is the case that loses the race, because half of the warm-up's
masked calls pass the all-null count and return at the driver's shortcut before any loop runs:
`loopMasked1` gets half the invocations it gets under the other claims, its standalone compile is
requested later, and the default method's compile, which stops its interpreted invocations, can
come first. HotSpot can also drop a queued task whose method has had no invocations for 50 ms
(`TieredCompileTaskTimeout`, `compilationPolicy.cpp` `select_task`, jdk-25-ga), which a method now
reached only through an inlined copy satisfies; the runner's logs cannot tell a late compile from a
dropped one, and the fix below does not depend on which.

*Added after section 3's change 2 was built, as the first use of its line:* the probe's new
jitState line, printed after the measured calls, read in six runs with the claim of only nulls on
two cores beside three busy loops at `-XX:MaxVectorSize=32`. In all six the kernel's own `run`,
`runDense` and `runMasked` had no code in use, and in five of them C2 was still compiling the
kernel's `run` at that moment; the four loop methods had standalone code. So in every one of those
runs the old measurement ran both drivers' dispatch interpreted and passed only because the loops'
standalone compiles had landed, which on the runners `loopMasked1`'s had not.

So the runners' failures are consistent with a verdict that was right about the entry the warm-up
and the evaluator call, and a test that measured a different entry, one the contract in
`VarkaFusedKernel` reserves for callers outside a task. No runner log rules out the other reading,
a deoptimisation of `loopMasked1`'s code on the values of the batches with some nulls; the runners
logged no compilation events, and nothing in the laptop's logs showed one.

**Not reproduced on the laptop.** About twenty probe runs with the claim of only nulls, on two and
four pinned cores, beside a second probe JVM or two to four busy loops, at
`-XX:MaxVectorSize=32`, `-XX:UseAVX=2` and the host's AVX-512, all passed (1,920 to 5,120 bytes).
Delaying the standalone compiles with a per-method `CompileThresholdScaling` does not mimic the
runner either: it leaves those methods without a profile, C2 then declines to inline them into the
default method, and the verdict is `RELEASED`. The cause above is read from the runners' byte
counts, which name the method, and from the laptop's compilation logs, which show the two paths.

## 3. The change

1. **The test measures the entry the verdict read.** `allocationOfCalls` calls the scratch form of
   `run`, as `VarkaKernelWarmup` and `VarkaKernelRunner` do, with a scratch region when the kernel
   asks for one and `0L` when it does not. The calls then reach the default method's own code, as
   the verdict's probes do, whichever tier it is at when they run, on the real batches, so a
   deoptimisation on real values - the thing the claim tests exist to catch - still fails the
   test. By inference it covers the runners' failure: their verdict was clean while, by the byte
   counts, `loopMasked1` had no standalone code, so the clean code was an inlined copy in a C2
   compile the verdict's probes reached; on the laptop neither warm-up call site inlines the
   default method (`PrintInlining`), so that compile would be the default method's own, which is
   what the new calls dispatch to. The runners' inlining decisions are not logged.

   The same calls through the kernel's own seven-argument `run` are measured too, after the
   asserted ones, and reported - in the test's output and in its failure message, with the
   jitState line - but not asserted on: that chain is the one a second kernel class leaves a
   caller on (section 7, row 405), and a reader should see when it has no code after a `COMPILED`
   verdict. The test is renamed for what it asserts, "after a warm-up claimed by a batch with
   <nulls>, both drivers run without boxing through the entry the evaluator calls"; it was
   "... compiles both of the kernel's drivers".
2. **The probe reports its kernel's compiled methods.** After the measured calls the probe prints
   `VarkaKernelWarmup.jitState(kernel class)` (the class's methods with C2 code, and those with
   none), followed by the same list for `VarkaFusedKernel`, whose default `run` the measured calls
   now enter, on a line of its own, and the suite puts it in the allocation assertion's message,
   so a later failure on a runner names the methods with and without code instead of leaving them
   to be worked out from a byte count.
3. **The fork carries the matrix configuration.** The probe builds both shapes from
   `VarkaMatrixRuns.BASE`, and the suite starts the fork with `-Dvarka.matrix.config=<this JVM's
   configuration>` and with this JVM's `-XX:MaxVectorSize`, `-XX:UseAVX` and (added after the review) `-XX:UseSVE` if it was given them,
   so the matrix tests each configuration and a 256-bit run on the laptop reaches the probe. The
   memory sanitizer stays off in the fork: the suite measures what C2 made of a kernel, and a
   sanitized call deoptimises warm code that was compiled outside the sanitizer's window
   (`VARKA-400.md` 2; row 401 is that question for the warm-up).

*Added after the first run under every configuration (section 8), which the change failed in 47 of
51 configurations:*

4. **The class-name test builds its plain key from the probe shape's own options.** "only a warmed
   kernel's class name is one the directive matches" compared the warmed shape's name with a plain
   key built from the defaults, which held only while the shape was built from the defaults too.
5. **The forked compile tests cancel under a lane count that is not the JVM's preferred.** Under
   `lanesOverride=4` every warm-up was `RELEASED` at its deadline with every probe boxing: the
   template warm-up compiles the Vector API's shared templates for the preferred species, and a
   kernel of a second species beside them boxes every operation (`vector-api-and-width.md`, "two
   species in one JVM is a box per iteration"). The same holds for `lanesOverride=16` on a 256-bit
   runner. A cancel by the JVM's species rather than a `skips.tsv` line, because which override is
   the second species depends on the machine and a fixed line would be stale on the other class.
6. **One `skips.tsv` line**: `methodByteBudget=0` makes the wide shape's emission throw, since with
   the byte budget off the emitter caps a kernel at 64 distinct ops; the reason is the one the
   other 30 lines of that configuration give.

## 4. Predictions, registered before the runs

1. The suite under the defaults on the laptop, through `dev/varka_matrix.sh`, in 26 to 40 s (32 s
   before), the jitState line adding under 100 ms to each probe.
2. Under every one of the matrix's configurations the suite exits 0 or needs at most four
   `skips.tsv` lines, each for a configuration whose kernel the probe's claims cannot hold for by
   design; no configuration shows a `COMPILED` verdict followed by an over-limit batch.
3. At 256 bits (`--jvm-arg -XX:MaxVectorSize=32`) the suite passes under the defaults and under
   `lanesOverride=16`, which it cancels or skips there rather than failing.
4. Deferred to the next scheduled option matrix: the test passes in every shard; if it fails, the
   jitState line names a kernel method with no code in use.

*Corrected after the review of 19 October 2026:* prediction 4's second half cannot fail, since
passing runs already show the kernel's own `run`, `runDense` and `runMasked` with no code. What the
jitState line can show: which of the kernel's and the interface's methods have standalone code, at
which tier (`Compiler.codelist`). What it cannot: inlining, so a method listed without code may be
running compiled inside another method's compile. Of the line, only the tier of the default
`VarkaFusedKernel.run` says what the asserted calls ran. Prediction 4 is read instead as: the test
passes in every shard of the next scheduled option matrix, and in any shard that fails, the
informational own-`run` bytes and the default method's tier are recorded with the failure.

## 5. Verification

* The suite under the defaults and under every configuration, `dev/varka_matrix.sh --all --module
  catalyst --suites VarkaKernelWarmupSuite`, under the shared slots, read from its report.
* The suite at 256 bits under the defaults and `lanesOverride=16`.
* The probe with the claim of only nulls, run directly as the fork runs it, on two cores beside
  busy loops, six times, to show the new measurement stays under the limit there too.

## 6. Explicitly out of this row

* Whether the verdict should also wait for the kernel's own `run` chain; see section 7.
* The warm-up's calls inside the sanitizer's window: row 401.
* The runner's CPU in the matrix's logs: row 399.

## 7. Findings outside the row

* **The verdict rests on inlining through a default method that only one kernel class has
  reached.** In this JVM the warm-up's verdict reads the kernel through
  `VarkaFusedKernel.run`'s default method compiled with the kernel inlined. In an executor that
  later meets a second kernel class through the same method, that compile's class check fails and
  is thrown away, and calls fall back to the first kernel's own `run` chain, whose standalone C2
  code may never have been compiled, as the runners showed it need not be. A shape reported
  `COMPILED` could then run part of its driver interpreted until those compiles land. Not measured
  here. The test now measures the one-class JVM's path on purpose, since it is the path the verdict
  reads; whether the verdict should also wait for the kernel's own `run` chain, which the old test
  happened to measure, is this finding's question, proposed as a row of its own in the report of
  this row rather than folded in. Filed as row 405 of `m8/PLAN.md`.
* **The species cancel hides a product behaviour.** Under a `lanesOverride` other than the JVM's
  preferred int species, every warm-up is `RELEASED` at its deadline while every call boxes: the
  template warm-up compiles the shared templates for the preferred species only. The test cancels
  there because no verdict can come; a session that sets such an override gets the same cliff.
  Recorded in row 405's text.
* **The fork's processors.** The probe's JVM does not get `-XX:ActiveProcessorCount`, so under a
  parent given one its compiler threads follow the machine's processors, not the parent's.

## 8. Outcome

Built and run on the laptop on 19 October 2026 (Zen 5, `MaxVectorSize=64`, JDK 25.0.4.1) through
`dev/varka_matrix.sh`, under the shared slots.

**The first run under every configuration** (`--all --module catalyst --suites
VarkaKernelWarmupSuite -j 4`), on section 3's changes 1 to 3: the defaults and the three batch-size
configurations passed; the other 47 failed "only a warmed kernel's class name is one the directive
matches" (change 4's cause); `lanesOverride=4` also failed all five forked compile tests,
`RELEASED` at 120 s with the last probe at 426,144 to 851,968 bytes (change 5's cause), and
`methodByteBudget=0` failed the wide shape's test on the 64-op cap (change 6). No configuration
showed a `COMPILED` verdict followed by an over-limit batch.

**On the fix.** The same run: all 51 configurations exit 0, 13 tests passed in each, except
`lanesOverride=4` (8 passed, the 5 forked compile tests cancelled) and `methodByteBudget=0` (12
passed, the wide test cancelled as listed). At 256 bits (`--jvm-arg -XX:MaxVectorSize=32`) the
defaults and `lanesOverride=8` passed 13 of 13 and `lanesOverride=16` passed 8 and cancelled the
5; `lanesOverride=8` passing there also shows the flag reached the probe's JVM, since an eight-lane
kernel in a 512-bit JVM would be a second species. The probe run directly with the claim of only
nulls on two cores beside three busy loops at 256 bits, six times: the batches with some nulls
allocated 1,920 to 3,840 bytes, every run's jitState line showing the kernel's own `run` with no
code (section 2).

**The predictions of section 4, scored.**

1. The suite under the defaults in 26 to 40 s: **held**, 31 s alone and 37 to 39 s beside three
   other configurations' JVMs.
2. Every configuration exits 0 with at most four `skips.tsv` lines, and no `COMPILED` verdict
   followed by an over-limit batch: **held in what it was for, missed in how**. One line was
   needed and no configuration showed the runner's failure, but the change itself broke a test in
   47 configurations and one more needed a cancel, neither of which the prediction counted on: the
   class-name test's dependence on the shape's options was not read before the change.
3. At 256 bits the defaults pass and `lanesOverride=16` does not fail: **held**, cancelled by the
   species check of change 5, which the prediction had expected to need.
4. The next scheduled option matrix: **not yet scored**; it is the first run of this test on
   runners with the new measurement.

**After the review.** The narrow gate step runs this suite at `-XX:MaxVectorSize=16`
(`dev/varka_gate.sh`), which the probe now receives: the suite under the defaults at 128 bits
passed 13 of 13, 35 s. The jitState line's entry part, read once at 256 bits after the fix, showed
`VarkaFusedKernel.run` with tier-3 code only and the kernel's own `run` compiled at tier 4: the
default method's C2 compile had not landed when the verdict came, and the verdict and the measured
calls both ran the kernel's own methods, which is the same path for both.

**What the committed bytes were run under.** The 51-configuration run and the 256-bit runs above
were on the commit before the review. The review's changes - the jitState line's entry part, the
class-name test reading the shape's counts (the same values), the javadoc - were then run under
the defaults (13 of 13, 31 s), the defaults at 128 bits (above), and one probe at 256 bits. The
51-configuration run at `-j 4` is also the done-when's "beside a second JVM": every configuration
ran beside three others' JVMs, the defaults in 37 to 39 s; the six starved probe runs used busy
loops instead.

**The done-when's first clause** asked for the cause read from the probe's compilation log on a
runner. It is met by inference, not by a runner's log: the runners' byte counts name the method,
and the laptop's logs show the two paths; no runner log carries compilation output, and none can
until the next scheduled option matrix runs this probe with its jitState line (prediction 4).

**The self-review**, an independent read of the diff, found no correctness bug and ten points,
eight taken: the jitState line also lists `VarkaFusedKernel`'s code, the entry the test now takes;
the 128-bit run above; the javadoc and this plan say the measured calls and the verdict's probes
share the default method's own code at whatever tier, rather than claiming its C2 compile is
always in; section 7 says the test measures the one-class path on purpose; the done-when clause
above; the `Forked` doc names the new field; "four tests" corrected to five; the class-name test
takes the shape's own counts. Not taken: `runUnwarmed`'s seven-argument calls, whose subject is
the shared templates any entry reaches; and prediction 3's wording, which stays as registered.

**After the coordinator's review** (the informational own-`run` measurement, the rename,
`-XX:UseSVE`, row 405, the softened record): the suite passed under the defaults (13 of 13, 34 s),
`elideChronoMonth=false`, `heavyGroupOutputs=0` and `useAVX=0` (13 of 13 each), `lanesOverride=4`
(8 passed, 5 cancelled) and `methodByteBudget=0` (12 passed, the listed test cancelled), and at 128
bits under the defaults (13 of 13, 36 s). The own-`run` readings, not asserted, were 0 to 10,880
bytes over the sixteen calls in those runs, all under the limit; in three of the six the
interface's default `run` was still at tier 3 when the line was printed. `dev/lint-scala`,
`dev/lint-java` and `dev/varka_errorprone.sh` exit 0.

**Checks run:** scalastyle on catalyst's tests (0 errors), checkstyle on catalyst's tests (one
error, in upstream's `StUtilsSuite.java`, not touched here), `dev/varka_errorprone.sh` (exit 0),
the 100-column and non-ASCII checks, `dev/varka_calibration.py --check`. The lesson is in
`skills/the-jit.md`, "A warmed kernel measured through another entry can run code the verdict
never read".
