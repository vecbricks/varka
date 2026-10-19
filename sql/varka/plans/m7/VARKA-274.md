# VARKA-274: The fuzzers check the code users run

## 1. Where this came from

Every answer check of a random kernel goes through `VarkaKernelChecks.runOne`
(`VarkaKernelChecks.java:556`). It defines the emitted class in a new loader, runs `run` once
over one batch of at most 1,000 rows (`VarkaIrFuzzSuite.scala:115`), compares, and releases the
loader. A class that runs once is never compiled, so the fuzzers' verdicts come from the
interpreter. The SQL suites that reach kernels through a session run with the warm-up off
(`VarkaSharedSessions.scala:111`), so a new kernel there also serves its few batches
interpreted. Users run something else: with the warm-up on, the default
(`SQLConf.VARKA_WARMUP_ENABLED`), a kernel serves batches only once C2 has compiled it
(`VarkaKernelWarmth`), and with the warm-up off, or after a warm-up's deadline, a kernel moves
through the interpreter, C1 and C2 while its batches run. The C2 vector code is checked against
an answer on three shapes only (`VarkaWarmupEndToEndSuite`).

Li, Jiang, Xu and Su ("Validating JIT Compilers via Compilation Space Exploration", SOSP 2023;
`m7/READING.md` 11) report, for a seven-day run against OpenJ9, that of the 154 seeds whose output
Artemis made differ, 138 (89.6%) "cannot be triggered simply by comparing the default and
fully-compiled JIT-trace" (p. 10, beside Table 4; checked against the PDF). Those needed the
mixed histories in between: a method compiled at one tier, recompiled at another, deoptimized,
entered by OSR. HotSpot's miscompilation in their example needed C1, C2, a deoptimization and an
OSR (p. 3). Artemis gives up on a seed after eight mutants (p. 10), which is where the row's
"about eight batch histories" comes from. The plan's section 2.2 ("Interpreted code")
and risk 8 take the row in; row 272, the interpreter, C1, C2 and intrinsics-off arms in forked
JVMs, follows it and reuses its tier proof (3.2).

## 2. The admission check, done

Probes on 13 October 2026, 15:45 to 16:15 CEST, on the laptop (AMD Ryzen AI 9 HX PRO 370, 24
threads, JDK 25.0.4.1, preferred int species 512 bits over a 256-bit datapath; load average 0.2
when the timed arms started), in a scratch worktree at `eca9171de4b` (master). The probe was a
Java class in the catalyst test package, run as a plain JVM on the catalyst test classpath with
`--add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Dspark.testing=1`. For
ground truth it used the JDK's WhiteBox API (`-XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI`,
the class compiled from the OpenJDK source tree's `test/lib`), which reads a method's compile
level directly. WhiteBox ships in no JDK image, so it checked the facts and is not part of the
design. The probe drew int-lane shapes from `VarkaIrGrammar.drawShape` with the fuzzer's seed
(20260903), emitted them under `VarkaEmitOptions.DEFAULTS`, loaded each class once and ran its
batches through a harness that compares every row and validity bit with `VarkaReference`, as
`runOne` does. The probe and its logs were deleted afterwards.

**(a) Today's verdicts are interpreter-served.** `VarkaIrFuzzSuite`'s main test at its default
300 iterations, run with `-XX:+PrintCompilation` and a JFR recording (`settings=profile`): 7.7 s
wall, and not one compile line for a `VarkaFusedFuzz*` class at any tier. JFR's execution samples
caught 15 kernel frames, all of type `Interpreted`. What the interpreted frames call is not all
interpreted: the same run compiled 600 Vector API methods at tier 4 and 1,280 at tier 3 on their
own (they are shared by every kernel in the JVM), and `-XX:+PrintIntrinsics` showed 659 Vector API
intrinsics taken and 157 refused in those standalone compiles. So a fuzzer verdict today is the
interpreter running the kernel, calling library code that is partly intrinsified with every
vector boxed at the call boundary and partly the plain-Java fallback. Neither is the code C2
makes of a kernel, in which the vectors stay in registers. The composition fuzzer checks through
the same `runOne` and was not run separately.

**(b) Getting a fuzz kernel to C2 takes about a sixth of a second.** 40 drawn kernels (1 to 3
roots over 1 to 3 inputs; 4 to 16 kernel methods each), each warmed the way `VarkaKernelWarmup`
warms: calls of 32 rows plus the draw's length modulo 32, alternating the dense driver (no nulls)
and the masked one (every fifth row null), on a 1,024-row copy, then 16,000 calls and 200 ms
more. Under C1 excluded, as for production's warmed kernels (`VarkaKernelCompileDirective`):

| | min | median | p90 | max |
| --- | ---: | ---: | ---: | ---: |
| last C2 compile of the kernel after its first call (ms, from JFR) | 44 | 164 | 445 | 29,847 |
| the warm-up's allocation verdict (ms) | 29 | 122 | 343 | 29,054 |
| calls to that verdict | 5,874 | 14,916 | 16,368 | 327,888 |

Three of the 40 kernels (k = 8, 18 and 25) never settled: their loop methods entered the
`profile_predicate` deoptimization cycle (`the-jit.md`, "The deoptimization cycle is profiled
loop predication's"), 97 to 110 traps each in 30 s, and k = 8 still had a loop method at level
0 when the probe gave up. With C1 left on, the same 40 gave a median verdict of 122 ms and a
maximum of 497 ms, and k = 8 still cycled.

The warm-up's allocation verdict is not a C2 verdict. It reads what a 1,024-row call allocates,
and an epilogue that runs only the remainder rows allocates too little to show. At the moment
the verdict fired, 32 of the 39 kernels that reached it still had an epilogue method at level 0
and 3 had a loop method at level 0 (WhiteBox levels). The verdict is right for what it is for, a
kernel that has stopped boxing on its bulk rows; it cannot tell the checker that every method a
batch enters is C2 code.

The driver and dispatch methods (`run`, `runDense`, `runMasked`, 44 to 73 bytes) are often
never compiled on their own: JFR's `jdk.CompilerInlining` showed C2 inlining both drivers into
`run` ("inline (hot)") in some kernels, and in others `run` stayed interpreted while every loop
and epilogue was C2. All the per-row work is in the loop and epilogue methods
(`VarkaMethodNames.isGroupMethod`), which are never inlined into a driver ("hot method too big"),
so those are the methods whose tier a verdict has to name.

**(c) JFR proves which tier served a call, and costs nothing measurable.** A `RecordingStream`
in the test's own JVM, with no flags, on `jdk.Compilation` (threshold 0), `jdk.Deoptimization`
and `jdk.CompilerInlining`, reported every level-4 compile WhiteBox saw, and each trap that
`-Xlog:deoptimization=debug` logged (3 of 3 on the run compared). A test-only JFR event
committed around each compared call put every trap in its call: of the 27 traps taken after the
warm-ups, all 27 fell inside a compared call's window. The 40-kernel run took 101,527 ms with
the recording and 102,197 ms without it, and the median warm-up 352 ms against 357 ms. The
DiagnosticCommand MBean's `compilerCodelist`, the in-process `jcmd Compiler.codelist`, read in
about 3 ms the installed code of the whole JVM (3,233 lines): compile id, level and state per
method, `0` for in use and `1` for not entrant. It showed exactly the methods JFR had seen trap
as not entrant. In one run a `run` method read level 4 and later level 0 with no trap event; a
rerun under the deoptimization log did not compile that `run` at all, so the change was not
reproduced. Code can leave the code cache without a trap (an invalidation by a broken dependency
is not a JFR event), so the design reads a call's tier at both edges, from the code list, and
what happened inside, from the events.

**(d) An OSR mid-batch and a trap after a quiet warm-up can both be provoked, and both happen
on the users' paths.**

- **Traps.** After the warm-up above, which only ever ran remainders the slices give and never an
  all-null input, each kernel ran five batches. The batch with a remainder the warm-up never gave
  (1,000 rows) trapped in 9 of 40 kernels, an all-null first column in 28 of 40, the draw's own
  batch with nulls in 2 of 40; every trap was `unstable_if` with action `reinterpret`. The draw's
  own null-free batch trapped in none. Every answer matched the reference.
- **OSR.** On the warmed path, C1 excluded, one cold call of 2,000,000 rows OSR-compiled nothing
  in 12 kernels: with no tier-3 attempt the interpreter starts profiling late (`the-jit.md`, "A
  method C1 compiles at tier 2"). With C1 on, the path of a kernel run with the warm-up off or
  released at its deadline, every one of the 12 kernels OSR-compiled its loop methods at tier 3
  and then tier 4 inside that call. And on batches users have: 200 cold batches of 10,000 rows,
  C1 on, gave in each of the 12 kernels a tier-3 OSR compile of each loop method at batch 96 to
  101 and a tier-4 OSR compile at batch 163 to 186, each event inside one batch's window but
  three. The interpreter's back-edge counter crosses its threshold (60,000) a few calls before the
  invocation counter reaches its minimum (100 calls), so a 625-iteration batch is entered by OSR.
  All 2,400 batches matched the reference.

**What the check rejected.**

1. **The warm-up's verdict as the checker's.** It fires with epilogues interpreted (b).
2. **Per-method WhiteBox levels as the proof.** WhiteBox is not shipped, and a driver at level 0
   is usually inlined into a compiled `run` or harmless; the proof names the loop and epilogue
   methods only.
3. **"OSR mid-batch" on the warmed path.** It does not occur there (d). The OSR history runs the
   path where it does, C1 on.
4. **Out-of-contract values as a trap trigger.** The probe's first trap history put
   `Integer.MIN_VALUE` and `MAX_VALUE` on valid lanes; 11 of 40 kernels then answered differently
   from the reference, and the first of them (k = 2) did the same on a cold, interpreted class.
   Those values are outside the bound the grammar draws within (`VarkaIrGrammar.COLUMN_BOUND`),
   where neither side's answer is specified, so the guard history takes row 279's guard edges
   instead (3.3).
5. **Warming every draw of the nightly.** A kernel's seven compiled histories cost about 2.5 s
   from the numbers above: five warm-ups of about 350 ms on classes of their own, a recompile, and
   200 batches of 10,000 rows for `released`, which took 6.5 s for 12 kernels with every row
   compared. Against the nightly's 10,000 iterations a lane (`dev/varka_nightly.sh:54`) that is
   about 14 hours in one JVM; the compiled histories take a sample of draws instead (3.4).

## 3. The design

### 3.1 One class per kernel, run over a history

A **history** is the sequence of calls a kernel class meets before and during its compared
batches. `VarkaKernelChecks` gains a form of `runOne` that takes an already loaded kernel, so a
history loads its class once and compares as many batches on it as it needs; today's entry points
keep loading a class per batch and their messages do not change. A new Java class beside it,
`VarkaCompiledCheck`, runs a drawn case over each history in turn, each history on a class of its
own, since a history is exactly what ran on the class before:

| history | before the compared batch | compared, and the tier it must show |
| --- | --- | --- |
| `cold` | nothing | the drawn batch, interpreted: today's check, kept as it is |
| `warm-same` | warmed on the drawn batch's own values, both drivers | the drawn batch, both bodies: C2 |
| `warm-other` | warmed on another draw of the same columns, other nulls | the drawn batch, both bodies: C2 |
| `quiet-remainder` | warmed with remainder-free calls only | a batch whose length leaves a remainder: C2 or a trap |
| `quiet-null` | warmed with partial nulls only | a batch with an all-null input: C2 or a trap |
| `quiet-guard` | warmed inside every guard | row 279's edge values, on the decline side and the answer side: C2 or a trap |
| `recompiled` | `quiet-null`'s trap, then calls until the method is C2 code again | the trapping batch again: C2 after a deoptimization |
| `released` | C1 on, no warm-up: 200 batches of 10,000 rows | every batch: the interpreter, tier-3 OSR, tier-3, tier-4 OSR, C2, each compared |

Eight histories, seven of them new. Every compared batch is compared in full with the reference
whatever tier served it; the tier is a second assertion, about whether the history happened.

The warmed histories name their class with the warmed mark (`VarkaShapeCacheImpl.WARMED_MARK`)
under a fuzz prefix and install production's directive through
`VarkaKernelCompileDirective.readyForWarmup`, so C1 is kept off them as it is off a user's warmed
kernel. `released` uses a prefix the directive does not match, so C1 is on, as for a kernel run
with the warm-up off.

### 3.2 The tier witness

`VarkaTierWitness` (Java, catalyst test tree) holds one `RecordingStream` per JVM on
`jdk.Compilation`, `jdk.Deoptimization` and a test-only event, `VarkaCheckedCallEvent`, which
the check commits around each compared call with its history and batch. Before and after each
compared call it reads `compilerCodelist`. For a call it gives a verdict, a sealed type:

- **`C2`**: every loop and epilogue method of the body the batch selects
  (`VarkaMethodNames.loop`, `epilogue`, by the batch's null counts) is at level 4 and in use
  before and after the call, and no trap fell in the call's window;
- **`Trapped(method, reason)`**: a `jdk.Deoptimization` event fell in the window;
- **`Osr(level)`**: an OSR compile of one of those methods finished in the window;
- **`Mixed(levels)`**: some of those methods were below level 4 when the call began, and nothing
  happened inside.

Drivers and `run` are left out of the verdict: they hold no per-row work and are often inlined or
interpreted (2(b)). JFR delivers events about a second late, so the witness settles a history's
verdicts after its calls, on the stream's flush, and the check waits for it. Row 272 runs the same
histories in forked JVMs under `-Xint`, `-XX:TieredStopAtLevel=1` and the intrinsics off, and
asks the same witness which tier served: under `-Xint` it must see no compile at all.

### 3.3 Warming to C2, and what it is waited on

The warm-up is the check's own, not `VarkaKernelWarmup` (whose job and state machine serve the
production cache, and whose allocation verdict is not a C2 verdict, 2(b)). It reuses that class's
call shape: 32 rows plus the batch's remainder per call, alternating the drivers, on a 1,024-row
copy. It stops when the code list shows every loop and epilogue method of both drivers at level
4 and in use. A kernel that has not settled after 10 s (the measured p90 is 445 ms) is
**unsettled**: its batches are still compared and their verdicts recorded, the test reports the
count, and the shape is listed for the nightly's deoptimization-cycle guard rather than failed.
The `quiet-guard` history needs a draw with a guard; its batches come from `VarkaGuardSpecs`, the
guard-edge values row 279 builds, and a draw with no guard skips it.

### 3.4 Where it runs, and how much

- **`VarkaIrFuzzSuite`** gets a test, "random IR trees compiled by C2 match the reference
  evaluator over eight batch histories", at both lanes, over the first `-Dvarka.fuzz.compiled`
  draws (default 20) of the run's own seed, so a failure replays with the fuzzer's
  `-Dvarka.fuzz.only`, and `-Dvarka.fuzz.history=<name>` runs one history alone. Draws whose options set a lanes override run `cold` only and are counted:
  a second species in the JVM makes every later kernel's C2 code box at the merge of two receiver
  classes (`vector-api-and-width.md`), which no user's single-species JVM runs. The suite already
  runs in a JVM of its own (`VarkaOwnJvm`).
- **`VarkaCoverageCompositionFuzzSuite`**: its test of every coverage row's kernel alone runs
  `warm-same`, `quiet-null` and `released` per row, so each lowering the table lists is checked
  as C2 code.
- **The nightly** (`dev/varka_nightly.sh`) runs the compiled test over 2,000 draws a lane, split
  over eight JVMs by draw range, and prints the verdict counts per history and the unsettled
  shapes: at 2.5 s a kernel, 4,000 kernels over eight JVMs is about 21 minutes, if eight JVMs and
  their compiler threads share the 24 cores without slowing each other's compiles (prediction 9).

### 3.5 Deliberately unchanged

- **`VarkaSparkFuzzSuite` and the session suites** keep the warm-up off. Holding a random
  composition to Spark with the warm-up on needs each query run twice with a wait for the verdict
  between, as `VarkaWarmupEndToEndSuite` does; that is a candidate row (3.7), not this one.
- **`VarkaKernelWarmup`'s allocation verdict.** It decides when production stops taking the row
  path, and an epilogue interpreted on a batch's remainder costs little there; whether it should
  wait for the epilogues is a measurement of production's cost (3.7).
- **The JVM flags of the test JVMs.** The witness needs JFR and the DiagnosticCommand MBean, both
  in every JDK 25; forked JVMs with other flags are row 272's.
- **The wide compositions** (`drawWideShape`, 250 outputs and more) are not warmed: a wide kernel's
  C2 compiles take seconds each (`the-jit.md`, "A wide loop method's cliff"), and row 272's forked
  arms are the place to price them.

### 3.6 Registered op counts

None: no emitted code changes.

### 3.7 Candidate rows found by the check

1. **Small fuzz kernels enter the deoptimization cycle.** 3 of 40 drawn kernels, each of 1 to 3
   roots with only a masked driver, cycled on `profile_predicate` for 30 s under the warmed path
   (97 to 110 traps each), one of them with C1 on as well. The nightly's guard forks one
   `make_date` kernel only; the fuzz grammar's shapes reach the cycle more often than that guard
   can see. A row would census the cycle over the grammar's shapes and decide between a guard and
   a change to the emitted loop.
2. **The warm-up switches before the epilogues are compiled.** In 32 of 39 kernels. A row would
   measure what an interpreted epilogue costs per batch on production's paths and, if it shows,
   add the code list to the verdict.
3. **The Spark-level fuzzer with the warm-up on** (3.5).

## 4. Files

| file | what |
| --- | --- |
| `sql/catalyst/src/test/java/.../varka/VarkaKernelChecks.java` | the comparison over an already loaded kernel; today's entry points unchanged |
| `sql/catalyst/src/test/java/.../varka/VarkaCompiledCheck.java` | the histories, the check's warm-up, the unsettled count |
| `sql/catalyst/src/test/java/.../varka/VarkaTierWitness.java` | the recording, the code list, the per-call verdict |
| `sql/catalyst/src/test/java/.../varka/VarkaCheckedCallEvent.java` | the test-only JFR event around a compared call |
| `sql/catalyst/src/test/scala/.../varka/VarkaIrFuzzSuite.scala` | the compiled test at both lanes |
| `sql/catalyst/src/test/scala/.../varka/VarkaCoverageCompositionFuzzSuite.scala` | three histories per coverage row |
| `sql/catalyst/src/test/scala/.../varka/VarkaTierWitnessSuite.scala` | the witness's own tests (5) |
| `dev/varka_nightly.sh` | the compiled step, eight JVMs |
| `sql/varka/skills/the-jit.md`, `SKILLS.md` | what 2 found: the code list, OSR on 10,000-row batches with C1 on and none with it off, the allocation verdict and the epilogues |

## 5. Tests, and what each is for

1. **The witness sees each tier.** A kernel run once is `Mixed` with every method at level 0; the
   same kernel warmed is `C2`; a warmed kernel given an all-null input reports `Trapped` with
   `unstable_if`. Without this, a witness that always said `C2` would pass every history.
2. **The witness sees an OSR.** The `released` history's first 200 batches on one fixed shape
   report a tier-3 and a tier-4 `Osr` verdict on some batch.
3. **A trap is attributed to its call.** Two calls back to back, the second trapping: the trap is
   in the second's window and not the first's.
4. **The code list is read right.** The witness's parser over code-list text captured in 2's
   probe: a method listed at level 4 with state 0 counts as C2 code, the same method with state 1
   (not entrant) or missing does not. This is the read that would catch code leaving the cache
   without a trap event (2(c)); it is tested on captured text because no shipped API invalidates
   a method on demand.
5. **A planted wrong answer fails every history.** A test hook adds one to the reference's answer
   for one output on one row; each of the eight histories fails, its name in the message. Without
   it, a history that compared nothing would pass.
6. **Unsettled is a result, not a failure.** A kernel given a 1 ms settle deadline is reported
   unsettled, its batches compared.
7. **The replay works.** A failure in the compiled test names the seed, the iteration and the
   history, and `-Dvarka.fuzz.only` with `-Dvarka.fuzz.history` reruns just that.

No pinned fixture moves.

## 6. The measurement

The compiled test at its default (20 draws a lane) and the nightly's 2,000 draws a lane: the time
per kernel and per history, the verdict counts per history, the unsettled shapes, and any
disagreement. The PR-CI arm's time on the laptop and on a GitHub runner.

### 6.1 Predictions, registered before the run

1. No compiled history finds a wrong answer at 20 draws a lane; at 2,000 the chance of one is
   small but not nil, and a finding is fixed or becomes a row before the plan closes.
2. A kernel's seven compiled histories cost under 3 s on the laptop (about 2.5 s from 2), so the default arm adds under
   two minutes to `VarkaIrFuzzSuite` (two lanes, 20 draws each).
3. `warm-same` and `warm-other` show `C2` on at least 95% of settled draws.
4. `quiet-null` traps on at least half of the draws whose kernel has a masked driver (28 of 40
   measured), `quiet-remainder` on at least a sixth (9 of 40).
5. `released` shows a tier-4 OSR verdict on at least 90% of draws within its 200 batches (12 of
   12 measured).
6. Between 2% and 15% of draws are unsettled at 10 s (3 of 40 measured), and every unsettled one
   shows `profile_predicate` traps.
7. The long lane's kernels settle in the same time as the int lane's, within a factor of two at the
   median.
8. The JFR recording and code-list reads add under 5% to a history's time.
9. The nightly's 4,000 kernels over eight JVMs take under 30 minutes on the laptop.

## 7. Risks

1. **The JIT is not deterministic.** Compile timing depends on the machine's load, so a history's
   tier is a measured outcome, not a given. That is why the answer is compared whatever the tier,
   the tier verdict says whether the history happened, and an unsettled kernel is counted, not
   failed. A tier assertion that fails on a loaded runner fails as "history not reached", with the
   code list in the message, never as a wrong answer.
2. **One species per JVM.** A lanes-override draw in the same JVM boxes every later kernel's C2
   code; the compiled test runs those draws `cold` only (3.4), and `VarkaSpeciesGuard` already
   fails a suite that adds a species in the shared JVM.
3. **The cycle eats the budget.** An unsettled kernel holds its 10 s; three in forty would add
   about a second a kernel on average. The nightly's per-JVM watchdog is raised to match
   (`testing-and-debugging.md`, "A long opt-in run needs the suite timeout raised").
4. **JFR's late delivery.** A verdict read before the stream flushed would miss the last events;
   the witness settles on the flush callback after the history's last call, never on a sleep.
5. **The directive is JVM-wide.** Installed once by the compiled test, it reaches every warmed-mark
   class in that JVM; the fuzz prefix keeps it off other suites' kernels, and the suite's own JVM
   keeps it off other suites.

## 8. Sequencing

Two pull requests.

1. **274a: the witness and the histories.** `VarkaTierWitness`, the event, `VarkaCompiledCheck`
   with all eight histories, the loaded-kernel form in `VarkaKernelChecks`, the compiled test in
   `VarkaIrFuzzSuite` at the default 20 draws, and `VarkaTierWitnessSuite`. Proved by tests 1 to 7
   and predictions 2 to 5, 7 and 8.
2. **274b: wider and nightly.** The coverage rows' three histories, the nightly step over eight
   JVMs, and the skills section. Proved by predictions 1, 6 and 9 from the first nightly. Then
   section 9.

## 9. Outcome (274a)

Done on 13 October 2026, as 3.1 to 3.4 describe for `VarkaIrFuzzSuite`, with the changes below;
the coverage rows' histories, the nightly step and the skills section are 274b's.

### 9.1 What was built

* **`VarkaKernelChecks`** gained `runLoaded` and `runLoadedLong`: one batch through one body on a
  kernel the caller loaded, with a `CallHook` told just before and after the kernel's call.
  `runOne` became the load and release around the same body, so today's entry points and their
  messages are unchanged. A package-private flag plants a wrong answer for test 5.
* **`VarkaTierWitness`** holds one `RecordingStream` per JVM on `jdk.Compilation` (threshold 0),
  `jdk.Deoptimization`, `jdk.CompilerInlining` and the test event `VarkaCheckedCallEvent`, reads
  `compilerCodelist` at each compared call's edges, and gives the sealed verdict of 3.2. It
  settles on a marker event and one further flush, never on a sleep.
* **`VarkaCompiledCheck`** runs a case over the eight histories, each on a class of its own, with
  its own warm-up (`VarkaKernelWarmup`'s call shape, buffers built once, the code list read every
  256 calls, a 10 s deadline), and a `Tally` that prints the counts per lane and history and the
  unsettled cases with their trap reasons and method levels.
* **`VarkaIrFuzzSuite`** registers the compiled test first, so that no lanes-override draw of the
  later tests has put a second species in the JVM; its own override draws run `cold` only, after
  every other draw (12 of 40 at the default seeds). It fails on a wrong answer, and as "history not
  reached" when a `cold` call was not interpreted, when no settled `warm-same` or `warm-other` case
  of a lane ran every call as C2 code, or when no `released` case of a lane was served by an OSR.
  `-Dvarka.fuzz.compiled`, `-Dvarka.fuzz.history`, `-Dvarka.fuzz.settleMillis`,
  `-Dvarka.fuzz.releasedBatches`/`releasedRows` and `-Dvarka.fuzz.witness` size it.
* **The nightly fuzz job** (`.github/workflows/varka-fuzz.yml`) runs `VarkaIrFuzzSuite` in one
  JVM per runner core with the day's seed, so from the next night it runs the compiled test too:
  20 new draws a lane in each JVM, beside the others on a loaded runner, which is also the first
  place "history not reached" can fire under load. 274b's own step over 2,000 draws is separate.
* **`VarkaTierWitnessSuite`**, seven tests, joins `VarkaAssemblySuite` in the quiet phase of
  `dev/varka_gate.sh` and `dev/varka_scoped_suites.sh` and in `dev/varka_matrix.sh`'s exclusions.

### 9.2 Where the design changed, and why

1. **A loop inlined into its caller is C2 code.** The first 4-draw run left a kernel unsettled in
   every warmed history with no trap: `loopDense1` and `epilogueDense1` stayed at level 0 while
   every masked method was at 4, and a one-root `isNotNull` kernel had all four methods at 0. C2
   had inlined them into `runDense` or `run` and compiled that, after which their own invocation
   counters stopped. 2(b) said the loop and epilogue methods are "never inlined into a driver";
   that holds for the probe's kernels, not for small ones. The witness now reads
   `jdk.CompilerInlining` and counts a method inlined into a caller's level-4 version in use as
   level 4; with it every warm-up of that run settled. The settle times have a floor of about a
   second when a verdict waits for the inlining events, which JFR delivers once a second.
2. **An OSR serves the call after the one it was compiled in.** Read as "an OSR compile finished
   inside the call", `released` showed a tier-3 OSR on no case and tier 4 on 2 of 4: the compiles
   finish while the harness compares the previous batch. An OSR version is entered at the next
   back edge of the next call, so the verdict is now `Osr` where a method has an OSR version in
   use at the call's start (or finishing inside it) at a level above its standard version's.
3. **The code list is read once per call, not twice.** With two reads a call, the witness cost
   `released` 2.9 to 3.7 times its time (the read is about 3.5 ms and `released` makes 200
   calls). The opening read of a call now reuses the previous call's closing read on the same
   class, unless the kernel ran in between (a warm-up); a compile installed in that gap makes a
   call look a level lower than it ran, never higher, and an OSR installed there is found from
   the compile events and the closing read. It still costs what prediction 8 records.
4. **The all-null trap is the driver's, so the witness's trap test uses the remainder.** Running
   candidate shapes through `quiet-null` showed the all-null trap is `unstable_if` in
   `runMasked`, the all-null shortcut, and only where C2 compiled the driver, which it often does
   not: the same `year(c)` kernel trapped in one test and not in the next. Test 1's trap is
   therefore `quiet-remainder`'s, an `unstable_if` in an epilogue, which every settled warm-up has
   compiled. `quiet-null` and `quiet-remainder` each open with a batch shaped like the warm-up's
   calls, so a trap is seen to fall in the next call's window and not that one's (test 3).
5. **`quiet-guard` reaches a guard through one column.** `VarkaGuardSpecs` gives verdicts, not
   batches. The history bisects one column of the drawn first row between its drawn value and the
   column's bound for the last value the guards' verdict answers and the first it declines,
   keeping the answer side inside the drawn bound (outside it neither side's answer is specified,
   rejected item 4) and letting only the decline side step one past it. The decline side is held
   to the verdict's status, the answer side compared. 6 of the 28 cases have such a guard.
6. **A body with no loop or epilogue method is not counted.** A kernel whose outputs read no
   column has no masked group methods; a call through that body read as C2 vacuously, which made a
   `cold` call fail as "not interpreted" on the first 20-draw run. Such a call is now `Mixed` and
   left out of the counts.
7. **The recording stays on with `-Dvarka.fuzz.witness=false`.** The warm-up needs the OSR and
   inlining events to know it is done, so the switch turns off the per-call code-list reads and
   events only, which is what prediction 8 prices.

### 9.3 The tests

All seven of section 5 pass, at the host's width (three runs) and at `-XX:MaxVectorSize=16` (two
runs, after test 3 was relaxed as its item says), each suite run in a JVM of its own:

1. A `cold` call is `Mixed` with every method at level 0; `warm-same` settles and every call is
   `C2`; `quiet-remainder` traps with `unstable_if` in an epilogue (9.2.4 says why not the all-null
   input).
2. `released` on a fixed shape is served by a tier-3 OSR from batch 97 or 98 and a tier-4 OSR from
   batch 164 or 165 at the host's width.
3. `quiet-remainder`'s first call is `C2` and the trap falls in a later call, in the epilogue of
   that call's own body. At 128 bits only the dense body's call trapped, so the test asks for one.
4. The code list parser over captured lines: level 4 in use counts, not entrant does not, an
   OSR-only level 4 does not, a missing method is 0, an inlined callee counts.
5. The planted wrong answer fails each of the eight histories with the history in the message.
6. A 1 ms deadline leaves `warm-same` unsettled with both bodies' batches compared.
7. The failure names `seed=7 iteration=3` and the history with the replay properties, and
   `-Dvarka.fuzz.history=quiet-null` runs that history alone.

The compiled test passes at its default, 20 draws a lane, in its own JVM at both widths (145 s at
the host's, 196 s at 128 bits), and under the memory sanitizer in the catalyst suites at the
defaults (`dev/varka_matrix.sh --defaults --module catalyst --split 3 -j 3`): 617 passed, 0 failed,
26 cancelled, in 370 s. No compiled history found a wrong answer.

### 9.4 The predictions

Measured on the laptop at 18:13 to 18:23 CEST, load average 0.4 to 2.5, each arm a JVM of its own
(`dev/varka_matrix.sh --suites VarkaIrFuzzSuite --no-sanitizer`), 14 cases a lane over the
compiled histories after the 6 lanes-override draws a lane ran `cold` only.

| | prediction | measured | |
| --- | --- | --- | --- |
| 2 | a kernel's seven compiled histories under 3 s; the default arm adds under two minutes | median 3,623 ms a case at the int lane, 1,356 ms at the long; the test takes 145 s | missed |
| 3 | `warm-same`, `warm-other` `C2` on at least 95% of settled draws | 11 of 13 and 11 of 13 at the int lane, 12 of 14 and 12 of 14 at the long (85% and 86%) | missed |
| 4 | `quiet-null` traps on at least half, `quiet-remainder` on at least a sixth | `quiet-null` 9 of 14 and 13 of 14; `quiet-remainder` 13 of 14 and 14 of 14 | held |
| 5 | `released` tier-4 OSR on at least 90% within 200 batches | 13 of 14 and 14 of 14 | held |
| 7 | the long lane settles within a factor of two of the int lane at the median | 132 ms against 412 ms, the long lane three times faster | missed |
| 8 | the recording and code-list reads add under 5% to a history | +19% a case at the int lane (3,623 against 3,055 ms), +25% at the long; `released` 2.3 times; the whole test +6% (145 against 137 s) | missed |

* **2.** `released` is the largest share (median 1,268 ms at the int lane, 561 ms of it without
  the witness), then the warm-ups, whose median of 412 ms is mostly the wait for JFR's next
  delivery (9.2.1). The unsettled kernel holds its 10 s deadline in each of five warmed
  histories and the recompile, 46 s on its own. At these medians, 274b's nightly of 2,000 draws a
  lane over eight JVMs is about (2,000 x 3.6 s + 2,000 x 1.4 s) / 8, 21 minutes, before the
  unsettled cases' deadlines and if the JVMs do not slow each other.
* **3.** The two settled int-lane cases that were not all C2 trapped on their own drawn batch
  after a warm-up on its own values (the third, `Mixed`, is the unsettled kernel); the long
  lane's two did the same. A
  warm-up on 32-row slices of a 1,024-row copy does not give a batch of the drawn length's shape,
  and C2 traps on what it was not shown. The same holds for production's warm-up, which has the
  same call shape (candidate 4).
* **7.** Missed in the direction nobody registered: the long lane's kernels settle faster. Why was
  not investigated. Under load (two A/B runs at load 2 to 7) the ratio was 1.9 and 2.0.
* **8.** The per-call code-list read is about 3.5 ms and `released` makes 200 calls, so the read
  is most of what the witness costs; candidate 1.

Prediction 6 is 274b's; this run's data for it: 1 of 14 int-lane cases (seed 20260903, iteration
8) unsettled in every warmed history, 35 to 70 `profile_predicate` traps each, the cycle row 326
censuses. Predictions 1 and 9 are 274b's.

### 9.5 Candidate rows (listed, not fixed)

1. **A cheaper tier read.** The code list costs about 3.5 ms a read and dominates the witness's
   cost (prediction 8). Reading it only at a history's edges and placing each call from the JFR
   compile, inlining and deoptimization events would make a call's verdict nearly free, at the
   price of a code change between two calls with no event, which the edge reads would still
   catch per history. Wanted before 274b multiplies the draws by a hundred.
2. **Guard edges behind a subtree.** `quiet-guard` reached 6 of 28 cases: a guard whose checked
   value is a column's. A guard over an expression of columns needs the edge solved through the
   subtree, by the reference's own arithmetic or by drawing guard-adjacent shapes.
3. **The all-null shortcut traps in the driver.** `quiet-null`'s traps are `runMasked`'s
   all-null branch where C2 compiled the driver, so the history tests the driver more than the
   loops; a history that nulls one column of a batch where the others have values in every
   lane would reach the loops' null paths.
4. **Production's warm-up and the first real batch.** 2 of 14 kernels a lane trapped on their own
   drawn batch right after a settled warm-up on its own values (9.4, prediction 3). Whether a
   user's warmed kernel takes a `reinterpret` on its first real batch, and what that costs, is a
   measurement of `VarkaKernelWarmup` on production's batch shapes.


### 9.6 The review's fixes

A code review of the pull request found ten things, all fixed in it on 13 October 2026 after each
was checked; none was a false positive. Where this changes 9.1 to 9.4, this section is the
correction. Measured on the laptop at load average 3 to 10 (another agent's runs beside), so the
times here are not comparable to 9.4's quiet ones.

1. **A trap inside inlined code was lost.** `jdk.Deoptimization`'s `method` is the method the trap
   was taken in; its `compileId` names the compiled code thrown away. The witness read the first,
   so a trap inside a Vector API method or a runtime helper C2 had inlined into a kernel method was
   dropped. It now attributes a trap by the compile id of a watched class's compile. Shown from
   recorded events: every 20-draw run since finds 4 such traps, the same 4 at both widths - `run`
   trapping inside `jdk.internal.foreign.SegmentBulkOperations.fill` (twice), `runMasked` inside
   `VarkaVectorSupport.everyOutputReadsAnAllNullColumn`, and `run` inside a helper of
   `org.apache.spark.sql.varka.vector`, each `unstable_if`. The report prints the count and examples.
2. **C2 at both edges did not mean the same code.** The verdict asked for level 4 at both edges;
   it now asks, per method, for one compiled version (its own, or a caller's it was inlined into)
   in use at both, so code thrown away and recompiled inside a call reads `Mixed`. Test 4 checks
   it on captured lines (1751 at one edge, a recompiled 1790 at the other).
3. **`recompiled` looked for the trap in the loops only**, where 9.2.4 had found it in the masked
   driver. It now compares the class's versions in use before and after the all-null batch over
   every method, drivers and `run` included, and waits for every thrown-away method as well as the
   loops. "Trapped and then C2 on the last batch" read 10 and 13 of 14 before and after; why the
   loops' reading had already caught these kernels was not investigated.
4. **The warm-up decided ahead of the recording.** The code list is current, the JFR stream about
   a second behind, so an OSR version could be read as a standard one before its compile event
   arrived. Counting only versions the stream has delivered fixed that, and doubled the cost: the
   median settle went from 412 ms to about 1,000 ms (JFR's delivery interval) and the test from
   145 s to 295 s. So the warm-up now decides quickly, records the code list it decided on, and
   each decision is confirmed after the test's final settle against everything delivered; the
   report gives settled and confirmed counts, and a confirmed warm-up is what the not-reached
   check and prediction 3 count. The finding was real: at the host's width every decision was
   confirmed, but at 128 bits 2 of 14 int-lane `recompiled` decisions and up to 4 of 14 long-lane
   decisions per warmed history were not, and the long lane's `warm-same`, with 4 unconfirmed,
   shows `osr4` on 4 cases.
5. **The compiled test skipped the known failures and the shrinker.** A failure now carries the
   history (`VarkaCompiledCheck.HistoryFailure`) and the check's own failure as its cause, which
   the signature classifies; a known one is logged and left, a new one is shrunk against the
   history that failed, since a failure of compiled code need not show in the interpreter.
6. **"History not reached" failed the suite on a loaded machine.** Of the two choices offered -
   count it in the shared runs, or move the compiled test to the quiet phase - it is counted: the
   test prints how many and why, and fails on them only under `-Dvarka.fuzz.requireTiers=true`.
   The compiled test's job is to find wrong answers on new draws, and the nightly fuzz job, which
   draws them, runs one JVM per core; moving the test to the quiet phase would take it out of the
   nightly. The tier facts are held hard where the machine is quiet, by `VarkaTierWitnessSuite` on
   fixed shapes. A wrong answer fails everywhere.
7. **The recording outlived the test.** `VarkaTierWitness.close()` stops it when the compiled test
   ends. The later tests of the suite took 9.6 s before (load 0.4) and 9.2 s after (load 1 to 2):
   no difference that this machine's noise can show.
8. **Verdicts were recomputed at every count.** Each call's verdict is computed once, after a
   settle that covers the call, and kept; reading it earlier throws.
9. **The warm-up's sizes were copies.** It uses `VarkaKernelWarmup.SNAPSHOT_ROWS` and
   `SLICE_ROWS`.
10. **The compiled test's section header split the variants helpers.** It has no header now and
    sits with the other draw tests, before the first of them, so it still runs first.

**And master's output validity fills.** Merging master brought the check that runs every compared
batch twice, once with the output validity pre-filled with zeros and once with ones. The loaded
form does the same, and the witness's window holds both runs. So each history's kernel runs twice
per compared batch: `released`'s OSRs come at half the batch numbers they did (tier 3 at batch 49
and tier 4 at 84 at the int lane, host width).

After the fixes and the merge: `VarkaTierWitnessSuite` passes at both widths; the compiled test
passes in its own JVM at both widths (142 s and 227 s) with no wrong answer and 0 histories not
reached; the catalyst suites at the defaults (`dev/varka_matrix.sh --defaults --module catalyst
--split 3 -j 3`) pass, 636 passed and 0 failed, in 575 s at load 9. Prediction 3 at the host's
width now reads 10 of 14 and 12 of 14 at the int lane, 12 of 14 and 12 of 14 at the long, still
missed; prediction 8 was not re-measured on a quiet machine, and at load 5 the A/B read +11% at
the int lane and -17% at the long, which is that load's noise.

### 9.7 274b: the coverage rows, the nightly step and the skills section

Built on 16 October 2026, on the laptop at load average 5 to 34 (other agents' runs beside), on
six of its cores (18 to 23), so every time here is a loaded machine's.

**What was built.**

* **The coverage rows' kernels as C2 code.** `VarkaCoverageCompositionFuzzSuite` gets a test,
  registered first, that runs coverage rows' kernels alone over `warm-same`, `quiet-null` and
  `released` (`VarkaCompiledCheck`, unchanged), at the matrix base with any lanes override taken
  off, for the reason 3.4 gives for the IR suite. Each row's batch comes from a stream seeded by
  the run's seed and the row, 64 to 1,000 rows with a third of each column null, its values drawn
  as the suite's other kernel checks draw them; the histories compare with no decline allowed, so
  the batch is the first of three attempts, each nearer zero, that `VarkaGuardSpecs` says every
  guard answers. A failure names the row and replays with `-Dvarka.fuzz.coverageRow=<sql>`.
  The int-lane value draw moved out of `checkKernel` into `intInput` so both use it; the stream
  it draws from is unchanged, and the suite's other 14 tests pass as before, and `VarkaIrFuzzSuite`'s 14 with the sanitizer.
* **The nightly step, `compiled`** (`dev/varka_nightly.sh`, after `deoptnull`, `--skip-compiled`),
  runs `dev/varka_compiled_fuzz.sh`: the IR fuzzer's compiled test over draws 0 to 1,999 a lane
  with the day's seed, split by draw range over eight JVMs of three cores each
  (`-Dvarka.fuzz.compiledFrom`, which row 326's census added), then the coverage test over every
  row in one JVM on all 24 cores. It reuses `deoptnull`'s build when that step passed. Wrong
  answers fail it; history-not-reached is counted, as 9.6.6 settled.
* **The totals.** Under `-Dvarka.fuzz.census=true` the IR suite now also prints, once the witness
  has settled, one `VARKA_COMPILED` line per history run - lane, draw, history, what its calls
  came to (`Tally.kind`, pulled out of the report so both read the same rule), settled,
  confirmed, settle and wall ms, an unsettled warm-up's trap reasons, the label - and
  `dev/varka_compiled_fuzz.py` totals them over the JVMs: per lane and history, the unsettled
  cases with their traps (prediction 6), the not-reached lines and the failed tests (prediction
  1). It checks itself on planted lines before each run (`--self-test`).
* **The skills section**: `the-jit.md`, "Which tier served a call: the code list at the call's
  edges, JFR's events inside it", from 2 and 9.2.

**Where the design changed.**

1. **The coverage rows run as a sample, the whole table at night.** 3.4 had every row's kernel run
   in the suite's test. The first full run took 540 s for the 92 rows at load 30 with the memory
   sanitizer on and had not finished when the suite's 9-minute own-JVM watchdog failed the run,
   taking every later test of the suite with it. So a run takes 4 rows a lane drawn by the seed
   (`-Dvarka.fuzz.coverageCompiled=<n>|all`), and the nightly's step, whose seed is the day's,
   runs all 92 without the sanitizer: over nights the sample rotates through every lowering in
   the gate as well. A separate test rather than three more histories in "every coverage row's
   kernel agrees", since that test runs a lanes-override pass and is registered after one.
2. **A lanes override is taken off, not cancelled.** Under a matrix configuration that sets one,
   the test runs the configuration's other options at the JVM's own width, so the matrix's
   declining check never sees a test that fused nothing.

**The measurements.**

* **The coverage test.** A sample of 8 a lane, with the sanitizer as the gate runs it: the suite
  51 s in its own JVM without the test's rows and 112 s with them (load 5 to 11), and 5 minutes
  with them at load 35, beside the IR suite, which its 9-minute watchdog failed at that load. So
  the default is 4 a lane: the suite 91 s without the rows and 133 s with them at load 26 to
  29, and about half a minute added on a quiet machine by the 8-a-lane run's numbers; every
  history reached, no wrong answer. All 92 rows
  without the sanitizer on six cores at load 7 to 10: 321 s; `warm-same` all C2 on 63 of 63 and
  29 of 29, `quiet-null` trapped on 61 of 63 and 29 of 29, `released` a tier-4 OSR on 63 of 63 and
  29 of 29, first at batch 84 and 42 at the median (two runs a batch, master's validity fills).
  One `quiet-null` warm-up (`date_add(d, 3)`) was unsettled with every method at level 0 and no
  trap, where the run before had settled it; not reproduced, not investigated. Under the sanitizer
  4 traps a run fall inside `VarkaMemorySanitizer.check`, inlined into the loops: the gate's C2
  code is the sanitizer's, which the nightly's arm, without it, is not.
* **The step, a trial.** `--draws 40 --jvms 2 --cpus 3 --cpu-base 18` at seed 20261016, load 10:
  each JVM's 20 draws a lane took 7.1 and 7.4 minutes, the coverage arm 321 s, the step 763 s.
  34 int-lane and 32 long-lane cases ran the compiled histories (6 and 8 lanes-override draws
  cold only); the median case took 11.5 s at the int lane and 8.4 s at the long, about three
  times 9.4's whole-machine medians; why was not split. No wrong answer, no history not reached.
  Unsettled: 1 of 34 int-lane cases (2.9%) and 2 of 32 long-lane (6.2%), all three trapping at
  `profile_predicate`. A second trial at load 35 (seed 7, 8 draws a lane) ran a case in 45 s at
  the int lane's median and left 3 of 12 cases unsettled, one of them with only `unstable_if`
  traps: under load a warm-up can miss its deadline outside the cycle, which prediction 6's
  "every unsettled one shows `profile_predicate`" does not allow for.
* **The nightly's time, extrapolated, not measured.** Eight JVMs of 250 draws a lane each, at the
  trial's 7.2 minutes per 20 draws a lane on three cores, is about 90 minutes, and the coverage arm
  on 24 cores at most the trial's 5 minutes: **about 95 minutes added to the nightly**, inside the
  step deadline of three hours, if eight JVMs on 24 cores run as two did on six. 3.4's 21 minutes
  assumed 9.4's whole-machine case times; a JVM held to three cores runs a case about 2.6 times
  slower. `--draws 1000` would halve it; the default stays 3.4's 2,000 until a night has run.
  *Owner's decision, 16 October 2026:* the nightly runs 1,000 draws a lane, now the default of
  `dev/varka_compiled_fuzz.sh`, so the step adds about 50 minutes (45 for the fuzz JVMs, at most 5
  for the coverage arm), extrapolated as above; 2,000 stays a `--draws 2000` away.

**Predictions 1, 6 and 9**, which 8 gives the first nightly, are owed to it: the trial's 66
compiled cases and 92 coverage rows found no wrong answer (1), its unsettled share sits inside 6's band with every one
trapping at `profile_predicate`, and 9 (under 30 minutes) is extrapolated to miss at about 95,
a ratio near 3.2, on the calibration table as an extrapolation until a night scores it.

**Owed.** The first nightly's totals, which score 1, 6 and 9. 9.5's first candidate, the cheaper
tier read, was not built: the witness's +19 to 25% a case is most of a quarter-hour of this step.

**The review's fixes.** `/code-review high` on the diff found nine things; seven were fixed after
each was checked, two were left with a reason.

1. **Only the histories that run the row's own batch.** `-Dvarka.fuzz.history=warm-other` or
   `quiet-guard` would have drawn values in the IR grammar's bounds into a coverage row's derived
   code, bounded or `TIME` input; the test now runs only `cold`, `warm-same`, `quiet-null`,
   `recompiled` and `released` of what the property names.
2. **The guard check read no nulls.** A guard above a `coalesce` or `greatest` reads the value a
   null input leaves; the check now asks the guards about the batch with no nulls (the other
   body's run reads every value), with its own null flags, and with `quiet-null`'s all-null
   column 0.
3. **The watchdogs outlasted the step.** Each fuzz JVM had 170 minutes and the coverage arm 170
   after them, inside the nightly's three-hour step deadline, which would have killed the reader
   with them. Now 120 and 40, so a long night still ends in totals; at the trial's 90 minutes a
   JVM the margin is a third, and `--draws 1000` is the lever if a night runs out of it.
4. **A failed JVM gave no totals.** The `VARKA_COMPILED` lines were printed after the last draw, so
   a JVM whose test failed dropped every case it had run. They are printed from the test's
   `finally`, after a settle, and a failure there is logged, never put in place of the test's own.
5. **A garbled line crashed the reader.** It is skipped and counted, and the self-test plants one.
6. **The new members split the "Failures, shrunk" section.** They have a section of their own; the
   test itself stays first among the tests, with no header, as 9.6.10 settled for the IR suite.
7. **A local `table` shadowed the suite's.** Renamed.

Left: **the test runs in steps that filter it out** - `VarkaOwnJvm`'s child runs the whole suite
whatever the `-z`, so the nightly's `forced` step and every matrix configuration pay the
sample's minute too; that is the trait's documented cost, and a test that skips itself under a
filter would leave the gate's runs without it. **Listing `compiled` in the summary loop** instead
of its own block after it: the loop's line is shared with row 257's step in flight, which the
brief kept every agent off.

### 9.8 The first nightly, 18 to 19 October 2026

The laptop nightly's `compiled` step (`target/varka-nightly/2026-10-18/compiled.log`, seed
20261018, row 392's branch) ran eight JVMs of 125 draws a lane, the owner's 1,000 a lane of 16
October, and the 92 coverage rows, in 2,449 s. It scores predictions 1, 6 and 9 (`m7/PLAN.md` 12.3):

* **1, held.** No wrong answer in 1,580 compiled cases over the two lanes and the 92 coverage
  rows; 0 histories not reached, 0 failed tests.
* **6, missed low.** Unsettled at 10 s: 13 of 790 int-lane cases (1.6%), 11 of them trapping at
  `profile_predicate`, and 1 of 790 long-lane cases (0.1%), trapping there. Both lanes sit under
  the band's 2%, and two int-lane cases unsettled without that trap. The 3 of 40 the band came
  from was a small sample on a loaded machine.
* **9, missed.** 4,000 kernels under 30 minutes: the night ran half that count, 2,000 kernels, in
  41 minutes, so the registered count scales to about 82 minutes, a ratio of about 2.7, where the
  extrapolation of 16 October said 3.2 (`skills/calibration/VARKA-274-6.1-9.md`).

The same night's `platform` step met this test under `-Xcomp` for the first time and read 797
histories as not reached (`m7/PLAN.md` 12.2): the tier witness takes a kernel with no compiled
code at both edges for an interpreted one, and under `-Xcomp` there is no interpreter to run it.
