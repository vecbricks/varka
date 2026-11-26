# VARKA-272: The platform under the kernels

## 1. Where this came from

Row 272 of `m7/PLAN.md` section 3, from the survey of other engines (`m7/READING.md` 8) and
`m10/SCOPE.md` items 56 and 58: item 58 called HotSpot the trusted base that no proof reaches, and
item 56 named a C2 miscompilation of masked stores that JDK 25 carries (JDK-8388492). The row runs
the fuzzers' kernels under the interpreter, C1, C2 and the Vector API's intrinsics off, each in a
forked JVM, compares the answers, runs C2's seeded stress flags, has each arm prove its tier
through JFR, adds an `-Xcomp` arm and keeps crash artifacts. `m7/PLAN.md` section 2 says what it
is for: the Vector API's plain-Java fallback, which `-XX:-EnableVectorSupport` forces everywhere,
is a second implementation of every intrinsic a kernel uses, and comparing configurations checks
the base where it is cheap to (`vector-api-and-width.md`, "The Vector API's plain-Java fallback is
a second implementation"). VARKA-274a (13 October 2026) built what this stands on: the compiled
histories (`VarkaCompiledCheck`) and the tier witness (`VarkaTierWitness`), which say which tier
served each compared call in one JVM. This row forks the JVMs.

## 2. The admission check, done

Done on 14 October 2026 between 21:00 and 22:30 CEST on the laptop, load average 2 to 9 (four
other agents building and testing), every arm JVM held to three cores
(`-XX:ActiveProcessorCount=3`), so every time below is an upper bound on a quiet machine. The
checks, and what each would have rejected:

**2.1 What the nightly runs today, and where.** `dev/varka_nightly.sh` at master 26585876306 runs
canary, fuzz (`VarkaIrFuzzSuite` under sbt, 10,000 iterations, seed the date), forced, sparkfuzz,
golden, sweep, prove, series, variants, deopt, cliff and optionally the gate, each under
`dev/varka_deadline.sh` (10,800 s), logging to `target/varka-nightly/<date>/<step>.log`; a step
with files of its own puts them under the same directory (`golden --out "$logdir/golden"`). The
summary loop lists the steps by name. The open pull request #812 (row 327) adds a `sparkwarm` step
after `sparkfuzz`, edits the usage block's first paragraph and the summary loop's `for` line; this
task's hook stays off all three (3.5).

**2.2 How the IR fuzzer is driven.** `VarkaIrFuzzSuite` (`VarkaOwnJvm`: in a shared JVM it forks a
child with the parent's arguments; `-Dvarka.ownJvm=true` runs it in place, which a forked arm
wants) draws case `k` from `VarkaIrGrammar.shapeRandom(seed, k)`: `-Dvarka.fuzz.seed` (one seed,
both lanes), `-Dvarka.fuzz.iterations` (300 by default; the plain tests, "random IR trees match
the reference evaluator" and the long-lane one), `-Dvarka.fuzz.only`, `-Dvarka.fuzz.variantsEvery`
(the eight variants of each passing draw, VARKA-278), the shrinker on unless `-Dvarka.fuzz.shrink=
false` (VARKA-277), and the known list `sql/varka/fuzz/known_failures.tsv`, empty today. The
compiled test (VARKA-274a, "random IR trees compiled by C2 match the reference evaluator over eight
batch histories") takes `-Dvarka.fuzz.compiled` draws a lane (20), `-Dvarka.fuzz.history=<label>`,
`-Dvarka.fuzz.settleMillis` (10,000), `-Dvarka.fuzz.releasedBatches` (200) and `releasedRows`
(10,000), `-Dvarka.fuzz.witness` and `-Dvarka.fuzz.requireTiers`. Same seed, same corpus in every
JVM: the plain tests are the volume and the cross-arm answers, the compiled test the tier proof.

**2.3 What each JVM configuration does to the compiled test, read off the code.**
`VarkaKernelCompileDirective.detect()` reads `UseCompiler`, `TieredCompilation`,
`TieredStopAtLevel` and `CompilationMode`: under `-Xint` (`UseCompiler=false`) and under
`-XX:TieredStopAtLevel=1` it answers "no C2", so the five warmed histories and `recompiled` are
skipped as "this JVM cannot warm a kernel to C2" and `cold` and `released` run; under `-Xcomp`,
`-XX:-EnableVectorSupport` and the stress flags it answers "tiered" and every history runs.
`VarkaCompiledCheck.notReached` encodes the default JVM: `cold` interpreted at both edges,
`warm-same` and `warm-other` all C2 in some settled case, `released` entered by an OSR at level 3
or 4. Under C1 the released OSR is level 1 and under `-Xcomp` nothing is interpreted, so with
`-Dvarka.fuzz.requireTiers=true` both arms would fail for the wrong reason. This is the check that
rejected the first design, forking the suite as it is: each arm needs its own expectation (3.3),
or its proof is the default JVM's.

**2.4 The tier proof.** Per compared call, `VarkaTierWitness` (a JFR `RecordingStream` on
`jdk.Compilation`, `jdk.Deoptimization`, `jdk.CompilerInlining` and the call event, plus the
code list at the call's edges) gives `C2`, `Trapped`, `Osr(level)` or `Mixed(before, after)`
with `Mixed.interpreted()`; that is the JFR reader this row reuses, not a second one.
`VarkaJitEvidence` (VARKA-295) is the warm-up diagnostic's reader, per kernel class after a
failure; the arms need the per-call reader. The JVM's own facts are readable from inside through
`HotSpotDiagnosticMXBean.getVMOption`: `UseCompiler`, `UseInterpreter`, `TieredStopAtLevel`,
`BackgroundCompilation`, every `Stress*` flag and `StressSeed` read back with origin
`VM_CREATION`; `EnableVectorSupport` only under `-XX:+UnlockExperimentalVMOptions` (locked, it
"does not exist"), so every arm passes the unlock. `-Xlog:compilation=info` prints
`EnableVectorSupport=true` or `false` at startup, from `modules.cpp` when the vector module is
defined; `-XX:+PrintFlagsFinal` shows the flag `false {default}` on both JDKs, with and without
`--add-modules`, because it is set after the flags print - a trap for anyone reading the flag
table.

**2.5 The flags on the two JDKs.** The product JDK 25 (Ubuntu 25.0.4.1) has eight C2 diagnostic
stress flags: `StressLCM`, `StressGCM`, `StressIGVN`, `StressCCP`, `StressIncrementalInlining`,
`StressMacroExpansion`, `StressUnstableIfTraps` and `StressSeed`, and `RepeatCompilation`
(diagnostic). So the stress arm runs on the shipped JDK, nightly, as the row says. The fastdebug
build (`openjdk-build/jdk25`, 25-internal of 16 September 2025) adds the develop flags
`StressLoopPeeling`, `StressDuplicateBackedge`, `StressRecompilation`, `StressBailout`,
`StressLongCountedLoop`, `StressMethodHandleLinkerInlining`, `StressReflectiveCode`,
`StressArrayCopyMacroNode`, C1's `StressLinearScan`, `StressLoopInvariantCodeMotion` and
`StressRangeCheckElimination`, the verifiers `VerifyIterativeGVN`, `VerifyLoopOptimizations` and
`VerifyGraphEdges`, `DeoptimizeALot` and `CICrashAt`. `DeoptimizeALot` deoptimizes the calling
thread's frames at VM transitions, which a kernel's loop never makes, so it exercises the harness
and not the kernel; it stays out, which corrects `m7/PLAN.md` section 2's "only `DeoptimizeALot`
needing the fastdebug build" - what the fastdebug build adds is its assertions and the develop
stress and verify flags. `CICrashAt=<n>` crashes the n-th compile and is the end-to-end test of
the crash artifacts. Crash flags: `DumpReplayDataOnError` is a product flag, on by default;
`ErrorFile` and `ReplayDataFile` take `%p`.

**2.6 Crash artifacts, probed.** A fastdebug JVM crashed on purpose (`-XX:ErrorHandlerTest=14`)
with `-XX:ErrorFile=<dir>/hs_err_%p.log`, `-XX:ReplayDataFile=<dir>/replay_%p.log` and a file
recording (`-XX:StartFlightRecording:settings=none,filename=<dir>/arm.jfr,dumponexit=true`) left
`hs_err_<pid>.log` where asked and JFR's emergency dump `hs_err_pid<pid>.jfr` (119 KB) in the
working directory; `arm.jfr` stayed empty, since `dumponexit` does not run on a crash, and no
replay file appeared, since replay data is written only when a compiler thread crashes. So an arm
runs with its own directory as the working directory, and a crash is read as "an `hs_err` file
exists".

**2.7 Which arms share one build.** All of them: one `catalyst/Test/compile` with the classpath
and JVM options exported as `dev/varka_matrix.sh` exports them (107 s here), the fastdebug JDK
running the same class files (both are Java 25). The sanitizer: the matrix adds
`-Dvarka.sanitizeMemory=true` outside the exported options; its cost under `-Xint` is measured
below.

**2.8 Measured, three cores an arm, before the design was fixed.** The compiled test with 5
draws a lane, every history, seed 20261014:

| probe | wall | per case, int lane | per case, long lane | what the verdicts said |
| --- | ---: | ---: | ---: | --- |
| `c2` default | 78 s | 6,480 ms | 5,659 ms | warm-same all C2 4 of 5 and 5 of 5; released osr4 5 of 5 and 5 of 5; 0 not reached |
| `int` `-Xint`, `cold` only | 15 s | 27 ms | 19 ms | all `Mixed` interpreted; 0 not reached |
| `xcomp` `-Xcomp`, 1 draw | 114 s | 39,850 ms | 21,652 ms | `cold` **trapped** (`unloaded`, reinterpret) on both lanes, `released` trapped, no OSR: 6 "not reached" under the default rules; the warmed histories all C2 |

Startup and suite initialisation, from the difference: about 15 s on the product JDK in the
interpreter and with C2, about 53 s under `-Xcomp`. The `-Xcomp` cost is in `released` (17,659
and 14,618 ms of the case), where every method of every class is compiled before its first call.

**2.9 Measured after 6.1's predictions were registered** (21:55 to 22:15 CEST, the same three
cores an arm, load average 2 to 9; the compiled test at 5 draws a lane unless said; "ms a case"
is the median over the histories that ran):

| probe | wall | int lane | long lane | what it showed |
| --- | ---: | ---: | ---: | --- |
| `int`, `cold`, 20 draws | 23 s | 30 ms | 13 ms | every verdict interpreted |
| `int`, `cold`, sanitizer on | 20 s | 46 ms | 27 ms | 5 s more than the 15 s without it |
| `c1` | 154 s | 15,632 ms | 6,526 ms | `cold` interpreted; the warm-ups skipped themselves; `released` entered by OSR at level 1, which the default rules count as nothing: 2 "not reached" for the wrong reason |
| `xcomp` | 166 s | 13,573 ms | 8,627 ms | `cold` trapped (`unloaded`, reinterpret) on every case, `released` never by OSR: 21 "not reached" under the default rules, the warmed histories all C2 |
| `stress`, with `StressUnstableIfTraps` | 279 s | 43,160 ms | 8,785 ms | int-lane warm-ups settled 3 of 5, the other two thrown away by `unstable_if` traps until the 10 s deadline; the proof held |
| `stress`, without it | 118 s | 10,640 ms | 5,736 ms | settled 5 of 5 on both lanes, all C2 in 4 of 5 and 5 of 5, released osr4 5 of 5; 0 not reached |
| `novec` | 430 s | 56,034 ms | 11,284 ms | int-lane warm-ups settled 0 of 5 (every method at level 0 at the 10 s deadline), `released` osr3 5 of 5; long lane settled 4 of 5, all C2 4, osr4 4 of 5; "0 not reached", vacuously |
| `novec`, `warm-same` alone, 1 draw, 90 s deadline | 148 s | unsettled | settled | JFR: C2 took 23,900 ms on `loopDense0`, 22,000 on `loopDense2`, 24,600 on `loopMasked0`, 29,000 on `epilogueDense`, and skipped `loopMasked1` after 12,400 ("concurrent class loading"); the long lane's compiles took 55 to 490 ms |
| fastdebug, no stress flags, 1 draw | 105 s | 46,451 ms | 29,308 ms | no crash; the one int case trapped on its drawn batch |
| fastdebug, stress without `StressIncrementalInlining`, 1 draw | 103 s | 46,021 ms | 23,392 ms | no crash |
| fastdebug, stress with it, 1 draw | 33 s | crash | | `fatal error: c2 (6666) ...wfuzz2::loopDense0: Hit MemLimit - limit: 1073741824`; `hs_err_<pid>.log`, `replay_<pid>.log` and `hs_err_pid<pid>.jfr` in the arm's directory |
| product, `-XX:CompileCommand=MemStat,*.*,print`, defaults | | | | the first case's `epilogueMasked` compile used 337 MB of C2 arena, `epilogueDense` 331 MB |
| the same under the stress flags with `StressIncrementalInlining` | | | | 1,160 MB and 1,032 MB |

The plain tests, both lanes, variants off, wall time with the 15 s startup: `c2` 2,000 draws a
lane in 25 s (75 s with the variants); `c1` 500 in 15 s; `novec` 500 in 17 s; `stress` 500 in
17 s; `fastdebug` 200 in 43 s; `int` 200 in 54 s (53 s with the sanitizer on, so the sanitizer
costs nothing visible there); `xcomp` 200 in 183 s, about a second a draw pair, since every
drawn kernel's methods are compiled before their first call.

What this establishes. The arms share one build (every product arm, and the fastdebug JDK on
the same class files). An interpreter arm needs neither a smaller batch nor the sanitizer off for
cost; the arms run with it off for the reason on the books, that its branches change what the
compilers compile. Per 100 draws a lane, three cores, under load: the plain tests cost `c2`,
`c1`, `novec` and `stress` a second or two, `fastdebug` about 15 s, `int` about 20 s, `xcomp`
about 90 s; the compiled test, per 100 draws a lane over every history, about 20 minutes on `c2`
and `stress`, 40 on `c1`, 35 on `xcomp` (all but `cold` and `released` on the int lane are the
10 s deadlines on `novec`, which is why it runs those two alone, 3.1). The tier proof comes from
the witness's verdicts under the rules of 3.3, and the fastdebug JDK's per-compilation memory
limit found the first finding before any answer was compared (9).

## 3. The design

### 3.1 The arms, and what each proves

One forked JVM per arm, from `dev/varka_platform.sh`, running `VarkaIrFuzzSuite`'s compiled test
(the tier proof, `--draws` a lane) and its two plain tests (the volume, `--iterations` a lane,
variants off) in one JVM with the same seed, so every arm draws the same corpus:

| arm | JDK | flags | histories that run | the proof the run must give, or fail |
| --- | --- | --- | --- | --- |
| `int` | product | `-Xint` | `cold` (`-Dvarka.fuzz.history=cold`) | `UseCompiler=false`; every verdict `Mixed` interpreted; no compile of any kernel class |
| `c1` | product | `-XX:TieredStopAtLevel=1` | `cold`, `released` (the warm-ups skip themselves) | `TieredStopAtLevel=1`; `cold` interpreted; some `released` call served by C1 code (an OSR at level 1 to 3, or every method at 1 to 3 at both edges); no level-4 code anywhere |
| `c2` | product | none | all eight | as VARKA-274a: `cold` interpreted, `warm-same`/`warm-other` all C2 in a settled case, `released` entered by OSR |
| `novec` | product | `-XX:-EnableVectorSupport` | all eight | `EnableVectorSupport=false` read back; then as `c2` |
| `xcomp` | product | `-Xcomp` | all eight | `UseInterpreter=false`; no verdict `Mixed` interpreted (a `cold` call is compiled code that traps on an unloaded class, 2.8); `warm-same`/`warm-other` all C2; no OSR rule |
| `stress` | product | `-XX:+UnlockDiagnosticVMOptions -XX:+StressLCM -XX:+StressGCM -XX:+StressIGVN -XX:+StressCCP -XX:+StressIncrementalInlining -XX:+StressMacroExpansion -XX:+StressUnstableIfTraps -XX:StressSeed=<seed>` | all eight | the flags read back with the seed; then as `c2` |
| `fastdebug` | fastdebug | the stress set, plus `StressLoopPeeling`, `StressDuplicateBackedge`, `StressRecompilation`, `StressLinearScan`, `StressLoopInvariantCodeMotion`, `StressRangeCheckElimination`, `VerifyLoopOptimizations`, `VerifyGraphEdges` | all eight | `java.home` under the fastdebug build; then as `stress` |

*Corrected after 2.9, before the code was written (22:20 CEST).* `novec` runs `cold` and
`released` only: with the intrinsics off C2 takes 20 to 30 s per int-lane loop method, no
warm-up settles in 10 s nor in 90, and `released` is where C1's OSR code and, where it arrives,
C2's show; its proof is the default rule's `released` clause (an OSR at level 3 or 4) and the
vector flag read back. `stress` leaves `StressUnstableIfTraps` out (a seven-fold cost for traps
the `quiet` histories already take on purpose). `fastdebug` keeps `StressIncrementalInlining`
and raises its per-compilation memory limit to `-XX:CompileCommand=MemLimit,*.*,2G~crash`: the
build's default gigabyte, a crash when hit, fired on the first warmed kernel of every run, since
that flag takes an epilogue compile from about 340 MB to about 1,160 MB (2.9); past two
gigabytes a crash is a finding again. And the default rule gains a clause: a lane whose
warm-ups never settled has not reached the C2 history either, where before it passed vacuously
(the `novec` int lane's "0 not reached").

Every arm also gets `-XX:+UnlockExperimentalVMOptions`, so the vector flag is readable, and
`-Dvarka.fuzz.vectorSupport=true` (`false` for `novec`), which the suite holds the JVM to.
`StressSeed` is the run's seed, never left at 0, where C2 draws a fresh seed per compile and a
crash would not replay. `RepeatCompilation` stays out of the default arms: it recompiles without
installing, which multiplies C2's time and changes no installed code; `--jvm-arg` adds it to a
longer run. The fastdebug arm runs when `VARKA_FASTDEBUG_JDK` names the build or the sibling
checkout `../openjdk-build/jdk25/build/linux-x86_64-server-fastdebug/images/jdk` exists, and is
skipped with a line otherwise, so the nightly needs no argument.

### 3.2 The script

`dev/varka_platform.sh [--arms a,b,...] [--iterations N] [--draws M] [--seed S] [-j K]
[--cpus C] [--out DIR] [--build-dir DIR] [--skip-build] [--jvm-arg X] [--arm-deadline SECS]`.
It builds catalyst's test classes once and exports the classpath and JVM options as the matrix
does, or reuses a matrix build (`--build-dir target/varka-scoped --skip-build` in CI), then runs
each arm as `VarkaSuiteRunner` in its own directory under `<out>/<arm>/` - `run.log`, `junit/`,
`answers.tsv`, `jvm.log` (`-Xlog:compilation=info`), `command.txt`, `version.txt`, `exit`,
`seconds`, `arm.jfr` and, after a crash, `hs_err_<pid>.log`, `replay_<pid>.log` and the emergency
`hs_err_pid<pid>.jfr` - with the arm's directory as the working directory and `-XX:ErrorFile`,
`-XX:ReplayDataFile` and `-XX:StartFlightRecording:settings=none,...,dumponexit=true` pointed
there. `-j` arms run at a time, each under `dev/varka_deadline.sh`, the suite caps
(`spark.test.timeout`, `varka.test.watchdog.minutes`) set past the arm's deadline. `--out`
defaults to `target/varka-platform/<date-time>/`; the nightly passes its own log directory, which
it keeps.

### 3.3 What the suite learns

* **The arm's expectation, `-Dvarka.fuzz.tier=interpreter|c1|c2|xcomp`** (`c2` by default), read
  by `VarkaCompiledCheck.Tier`, with `notReached(tally, tier)` giving each arm the rules in the
  table; the `interpreter` rule also asks the witness for the compiles of every run's class, which
  must be none. The compiled test first holds the JVM's flags to the tier (`VarkaPlatformFacts`:
  the interpreter arm has `UseCompiler=false`, C1 `TieredStopAtLevel=1`, `-Xcomp`
  `UseInterpreter=false`, C2 `TieredStopAtLevel=4` with the interpreter on) and prints them as one
  `platform:` line, which is the fact the report reads. The forked arms run with
  `-Dvarka.fuzz.requireTiers=true`, so a run that cannot prove its tier fails, like the warm-up's
  "no code in use".
* **The answers, `-Dvarka.fuzz.answers=<file>`** (`VarkaAnswerLog`): every compared batch,
  through the one harness every check goes through (`VarkaKernelChecks.runLoaded`), appends a
  64-bit digest of the kernel's outputs - per fill, per output, per row the validity bit and the
  value where valid - or the status of a decline, keyed by the check's context (the case's label,
  the history and the batch, the body) and a repeat count. The report compares arms on the keys
  they share: `cold` for every arm, the warmed histories among the C2 arms, the plain tests
  everywhere. A mismatch names the key, which replays with the seed, `-Dvarka.fuzz.only` and
  `-Dvarka.fuzz.history` under each arm's flags.

### 3.4 The report

`dev/varka_platform_report.py <out>`: one line per arm (JDK, flags, exit, seconds, the
`platform:` facts, the "history(ies) not reached" count, the answers' count, crash files), then
the cross-arm comparison against `c2` (or the first arm that ran), then the verdict. Exit status 1
on a failed arm, a crash, a missing proof or a disagreement. `--self-test` runs it over fixture
directories: agreement, a disagreement, a key one arm lacks, a crashed arm.

### 3.5 The nightly hook

One step before the gate line, `run_step platform dev/varka_platform.sh --seed "$seed" --out
"$logdir/platform"`, behind `--skip-platform`, its summary row printed after the loop (not in the
`for` line #812 edits), one sentence in the paragraph that describes the deopt, cliff and golden
steps. The nightly's defaults are set from 9's measurements.

### 3.6 What is deliberately unchanged

`VarkaSparkFuzzSuite` and the nightly's `sparkfuzz` step (#812's); the compiled histories and the
witness's verdicts (274a; 274b owns the nightly step over 2,000 draws and the coverage rows'
histories); `VarkaJitEvidence` (295); the GitHub fuzz workflow (`varka-fuzz.yml`), which has no
fastdebug build and runs the compiled test on every runner core already - giving it the product
arms is a follow-up once the nightly's numbers are in; the matrix and the gate.

### 3.7 Registered op counts

None: no emitter change.

## 4. Files

| file | what |
|---|---|
| `dev/varka_platform.sh` | the arms, forked |
| `dev/varka_platform_report.py` | the per-arm table, the cross-arm answers, the verdict, `--self-test` |
| `dev/varka_nightly.sh` | the `platform` step, `--skip-platform` |
| `sql/catalyst/src/test/java/.../varka/VarkaCompiledCheck.java` | `Tier`, `notReached(tally, tier)` |
| `sql/catalyst/src/test/java/.../varka/VarkaPlatformFacts.java` | the JVM's flags read back, held to the tier and the vector expectation |
| `sql/catalyst/src/test/java/.../varka/VarkaAnswerLog.java` | the digests file |
| `sql/catalyst/src/test/java/.../varka/VarkaKernelChecks.java` | the digest after each compare |
| `sql/catalyst/src/test/scala/.../varka/VarkaIrFuzzSuite.scala` | the tier and vector properties, the facts line |
| `sql/catalyst/src/test/java/.../varka/VarkaPlatformSuite.java` | the tests of 5 |
| `sql/varka/skills/testing-and-debugging.md`, `SKILLS.md` | the lesson |
| `sql/varka/plans/m7/PLAN.md` | row 272 |

## 5. Tests, and what each is for

1. `VarkaPlatformSuite` (JUnit, `@VarkaSuite`): the digest is a function of the outputs - two
   equal batches digest alike, one bit or one value apart digest differently, a decline digests
   its status; the answers file holds one line per compare with the repeat count; a tier's
   contradictions with the facts are named (`interpreter` against `UseCompiler=true`, `c1` against
   level 4, the vector expectation against the flag) and none for a JVM that matches; and the
   facts line names every flag the report reads.
2. `VarkaIrFuzzSuite`'s compiled test under each tier, which 9 records: the JVM at hand is the
   `c2` tier, so the suite's own run covers that rule; the others are the arms' runs.
3. `dev/varka_platform_report.py --self-test`: the four fixture outcomes of 3.4.
4. `shellcheck dev/varka_platform.sh`; the script's `--help`; a smoke in `dev/varka_scoped_suites.sh
   catalyst` over the product arms at one draw and twenty iterations if its fixed cost fits (9
   decides; `-Xcomp`'s 53 s startup is the likeliest not to).

No pinned fixture moves: no emitter change.

## 6. The measurement

Not a benchmark: the times are what sizes the nightly step and the smoke, taken on the laptop
under load with three cores an arm, and so are integers in seconds with the load noted, never
committed as performance numbers. The arms' tier proofs and the cross-arm comparison over a
few hundred plain draws and 20 compiled draws a lane are the outcome.

### 6.1 Predictions, registered before the run

Registered at 21:55 CEST on 14 October 2026, after 2.8's three probes and before any other arm ran.

1. **Fixed cost an arm** (JVM start and the suite's initialisation): about 15 s on the product JDK
   with C2, C1 and the interpreter; about 55 s under `-Xcomp`; about 45 s on the fastdebug build.
2. **The compiled test, per draw pair** (one int-lane and one long-lane case, every history):
   `c2` about 12 s; `stress` within one and a half times `c2`; `novec` within one and a half
   times `c2`; `xcomp` about 60 s, five times `c2`, almost all in `released`; `c1` under 3 s
   (`cold` and `released` only); `fastdebug` three to four times `c2`.
3. **The plain tests, draws a second a lane, variants off**: `c2` above 100; `stress` above 50;
   `novec` above 30; `c1` above 20; `xcomp` above 5; `fastdebug` above 10; `int` above 2.
4. **The sanitizer under `-Xint`** costs under 30% on the plain tests, since its checks are a few
   comparisons per mapping; the arms run with it off all the same, for the reason on the books -
   its branches change what the compilers compile, and the compilers are the subject.
5. **The tier proofs hold on every arm at 5 draws under load**: `c1`'s `released` shows an OSR at
   level 1 in at least 4 of 5 cases a lane and no level-4 code anywhere; `xcomp` shows no
   interpreted verdict and all-C2 warm-ups in at least 4 of 5; `novec`, `stress` and `fastdebug`
   match `c2`'s 274a rates (all C2 in at least 80% of settled warm-ups).
6. **No disagreement** between any two arms over the shared keys, and none between a kernel and
   the reference evaluator on any arm, over a few hundred plain draws and 20 compiled draws a
   lane; `StressUnstableIfTraps` raises the `trapped` count on the stress arms without a wrong
   answer.
7. **The crash test**: `-XX:CICrashAt=<n>` on the fastdebug arm leaves `hs_err_<pid>.log`, the
   replay file (the crash is a compiler thread's) and the emergency `.jfr` in the arm's directory,
   and the report names the arm as crashed with the three files.
8. **Zero "history not reached" on the product arms at 20 draws** with three cores each on this
   loaded machine, as the `c2` probe gave at 5.

## 7. Risks

1. **An arm proves nothing under load.** A settled warm-up is what the C2 proof reads, and JFR's
   delivery is about a second; 274a measured 85% all-C2. The forked arms run with
   `requireTiers`, so a loaded machine fails the arm as "not reached" rather than passing silently;
   the report shows the counts, and 9 records the rate.
2. **`-Xcomp` is too slow for the nightly.** 2.8 measured a draw pair at 60 s; 20 draws is 20
   minutes. The nightly takes it; a smoke takes one draw or leaves the arm out.
3. **A disagreement is C2's, the reference's or the harness's.** The answers file names the key;
   the case replays under both arms' flags with `-Dvarka.fuzz.only` and the history; a kernel
   wrong against the reference fails inside the arm first, and the shrinker (277) holds its
   smaller case to that history.
4. **The fastdebug build drifts from the product JDK** (25-internal against 25.0.4.1). It is a
   second JVM, not a second JDK release's semantics; a finding on it alone is replayed on the
   product JDK before it is called Varka's.
5. **The repeat-count keys mis-pair across arms** when one arm skips a compare the other makes
   (a history skipped, a shape past the cap on one JVM only). Keys carry the history and the
   batch, so a skipped history drops its keys rather than shifting them; the report compares on
   the intersection and prints how many keys each side lacked.

## 8. Sequencing

1. This plan, and row 272 marked Planned.
2. The suite's additions: `Tier`, `VarkaPlatformFacts`, `VarkaAnswerLog`, the hook in
   `VarkaKernelChecks`, `VarkaIrFuzzSuite`'s properties; `VarkaPlatformSuite`.
3. `dev/varka_platform.sh` and `dev/varka_platform_report.py` with its self-test.
4. The nightly hook, the smoke if it fits, the lesson, section 9 and the row.

## 9. Outcome

Done on 15 October 2026 as 3 describes, with the corrections below. Times are the laptop's under
other agents' load, three cores an arm, two arms at a time: they size the nightly and the smoke,
and are not performance numbers.

### 9.1 What ran

`dev/varka_platform.sh --iterations 300 --draws 5 -j 2 --cpus 3 --seed 20261014`, its own build
included, on the merge of master (which brought 327's `sparkwarm` step into the nightly; both
hooks stay). Every arm passed its tier proof with 0 histories not reached, and every arm's
digests equal the default JVM's on every key they share:

| arm | wall | answers | shared with `c2`, differing | tier proof read |
| --- | ---: | ---: | --- | --- |
| `int` | 65 s | 1,220 | 1,220, 0 | `UseCompiler=false`; every call interpreted, no kernel method compiled |
| `c1` | 139 s | 3,220 | 3,220, 0 | `TieredStopAtLevel=1`; `cold` interpreted, `released` served by C1's code, no level 4 |
| `c2` | 101 s | 3,368 | the reference | as VARKA-274a |
| `novec` | 68 s | 3,220 | 3,220, 0 | `EnableVectorSupport=false` read back; `cold` and `released` as `c2` |
| `xcomp` | 206 s | 2,268 | 2,268, 0 | `UseInterpreter=false`; nothing interpreted, warm-ups all C2; 30 plain draws a lane, a tenth |
| `stress` | 129 s | 3,368 | 3,368, 0 | the six flags and `StressSeed=20261014` read back; as `c2` |
| `fastdebug` | 159 s | 3,220 | 3,220, 0 | the fastdebug build's `java.home`, stress and verify flags; `cold` and `released` as `c2` |

Disagreements between arms: none, and none between a kernel and the reference evaluator on any
arm. The crash test (prediction 7) held: `-XX:CICrashAt=3000` on the fastdebug arm left
`hs_err_<pid>.log`, `replay_<pid>.log` and `hs_err_pid<pid>.jfr` in the arm's directory, and the
report named the arm crashed with the three files. The CI smoke (`int` and `c1`, 1 compiled draw,
20 plain) took 16 s and 55 s, so it is in `dev/varka_scoped_suites.sh catalyst`; the other arms
need several draws for a settled warm-up and run nightly. The nightly's defaults are 2,000 plain
draws and 20 compiled draws a lane; from the 5-draw run that is an estimated 40 to 60 minutes
with two arms at a time, not measured.

### 9.2 Where the design changed, and why

* `fastdebug` runs `cold` and `released` only, like `novec` (3.1's table said all eight): with
  `VerifyLoopOptimizations` and `VerifyGraphEdges` on, no int-lane warm-up settled in 10 s (0 of
  5, in the first run of this arm, which also read the rule's new "no case settled" clause). Its
  answers agreed then too (3,358 keys, 0 differ). Its 686 s became 159 s.
* The tier rules are per tier and the default rule gained the "no case settled" clause, as the
  correction in 3.1 says; `-Dvarka.fuzz.history` takes several histories, comma separated.

### 9.3 The predictions

| | prediction | measured | |
| --- | --- | --- | --- |
| 1 | fixed cost about 15 s product, 55 s `-Xcomp`, 45 s fastdebug | 15 s and 53 s; fastdebug not measured on its own | held for two of three |
| 2 | a draw pair: `c2` 12 s, `stress` within 1.5x, `novec` within 1.5x, `xcomp` 60 s, `c1` under 3 s, fastdebug 3 to 4x | `c2` 12 s; `stress` 16 s, 1.35x; `novec` 67 s, no warm-up settles; `xcomp` 61 s for one draw, 22 s a pair at five; `c1` 22 s; fastdebug 75 s for one draw | `c2`, `stress` held; `novec`, `c1`, fastdebug missed; `xcomp` held at one draw |
| 3 | plain draws a second a lane: `c2` over 100, `stress` 50, `novec` 30, `c1` 20, `xcomp` 5, fastdebug 10, `int` 2 | `c2` about 200, `stress` and `novec` about 250, `c1` over 100, `int` about 10, fastdebug about 5 to 10 at 200 draws; `xcomp` about 3 | `xcomp` missed, fastdebug not cleanly read, the rest held |
| 4 | the sanitizer under `-Xint` costs under 30% | 53 s against 54 s | held |
| 5 | tier proofs at 5 draws: C1 OSR at level 1 in 4 of 5, `xcomp` all-C2 in 4 of 5, `novec`, `stress`, fastdebug at `c2`'s 80% | C1 and `xcomp` pass their rules; `stress` 5 of 5; `novec` 0 of 5 and fastdebug 0 of 5 at the int lane | `novec` and fastdebug missed (they run two histories) |
| 6 | no disagreement | none in 3,368 keys over seven arms | held |
| 7 | the crash leaves three files | it did | held |
| 8 | zero not reached at 20 draws | tried at 5 only | not tested |

### 9.4 Candidates, found and not fixed

* **A JVM with `EnableVectorSupport=false` runs Varka for minutes before C2 code arrives**, and
  its int lane's loop methods took 20 to 30 s each to compile (`the-jit.md`, last section);
  whether Varka should decline to warm, or to fuse, when the flag reads false is a decision.
* **One epilogue's C2 compile used 337 MB of arena**, 1,160 MB with `StressIncrementalInlining`;
  nothing in the byte budget sees it.
* The arms' volume is small here; the first nightly's 2,000 and 20 draws are where a
  disagreement, if there is one, will show, and its log directory keeps the crash files.

### 9.5 The review's fixes

`/code-review high` on the branch found nine things; fixed: arm directories left in an `--out` by
an earlier run were read as part of this one (the script now removes the arms it does not run);
a mistyped arm name was refused only inside a backgrounded subshell and the verdict stayed clean
(validated in the foreground); seven arms two at a time at 3,600 s each could outlast the
nightly's step deadline (the nightly passes `--arm-deadline 1800`); an arm that wrote no answers
dropped out of the comparison and passed (it fails now, with a self-test case); `--seed 0` gave
`StressSeed=0`, which draws a fresh seed per compile (refused); a blank history label threw
(skipped); and two unit tests assumed the JVM at hand was the default tier (they read the
claim). Not changed: `Observed` computes a run's compiles eagerly, a few milliseconds a run; and
`VarkaAnswerLog` keeps its repeat counts per key for the JVM's life, which at the nightly's
volume is hundreds of thousands of strings in a JVM whose heap the arms do not measure.

### 9.6 The CI smoke failed on a runner, and why

The first pull request run of the smoke (`int` and `c1`, seed 20261015, 20 plain draws, 1 compiled
draw a lane, a runner on Zulu 25.0.4) passed `int` in 9 s and failed `c1` in 15 s with 2
histories not reached, its 484 answers equal to the interpreter's on the 84 keys they share. The
log kept no `run.log`, so the cause was reproduced: `c1` pinned to two cores that two busy loops
shared (`taskset -c`), three seeds, failed in two runs of three, in 21 s and 27 s. The messages
name the lane whose `released` history had no call served by C1's code in 200 batches; the first
case's verdicts were `Mixed[before={}, after={}]` in one run (a body with no loop or epilogue
method has no code to show) and every method at level 0 at both edges in the other (a starved C1
queue installed nothing in time). The rule asks that some call of the lane be served by C1 code,
and at one draw a lane that is a property of the draw and of the machine's load. It held on the
laptop's first try only by luck of both.

Decided: the CI smoke is the interpreter arm alone, whose proof (`UseCompiler=false`, every call
interpreted, no kernel method compiled) holds for any draw and any machine. `c1` keeps its rule
unchanged in the nightly, where 20 draws a lane are asked for one that shows C1's code; no rule
was loosened. The same reasoning is why the nightly's other arms ask for several draws, and the
smoke must not grow another arm without a proof of that kind.
