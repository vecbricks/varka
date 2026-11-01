# VARKA-430: The memory sanitizer's check keeps a warmed kernel and stays cheap (rows 430 and 437)

*Rows 430 and 437 of `m8/PLAN.md` share this file. Both come from the 21 October 2026 review of
row 401, both are in `VarkaMemorySanitizer`, and both matter only under
`-Dvarka.sanitizeMemory=true`. Row 430 is about the check's branch profile. Row 437 is about the
cost of the window's range scan. Section A is row 430 and section B is row 437. Each has its own
admission check, design, predictions and done-when, and each can merge alone.*

## 0. What exists now (both rows)

Read on 1 November 2026 from the worktree at `130280562f3` (code as of master `5c660201b35`); the
line numbers are that tree's. Master has since moved to `1bb9c97b5d0`, whose diff to the files these
rows touch (`Analysis.java`, `Slots.java`, `VarkaIrGrammar.java`, row 157's decimal store) shifts
some lines and changes no finding. Nothing was run and nothing was dispatched.

1. **The sanitizer has not changed since row 401.** `VarkaMemorySanitizer.java` was last touched
   by #953 (`70dab76c410`, row 401). `check(address, bytes)` reads `ACTIVE.get()`, the thread's
   window, tests it against null, counts the check in `CHECKED` and calls `Window.check`.
   `Window.check` scans its `count` ranges in registration order. It returns at the first range
   that holds the mapping and, if none does, throws, naming the nearest range. Every method
   returns at once on the `static final ENABLED` when the sanitizer is off.
2. **Who opens a window.** Production code opens one in four places: the evaluator's batch
   (`VarkaEvaluatorBase.java:581`), the filter's batch and its compaction
   (`VarkaFilterEvaluator.java:205`, `:407`), the warm-up gate (`VarkaWarmupGate.java:86`) and
   the kernel warm-up (`VarkaKernelWarmup.Job.run`, `:736`). In test code the windows are opened
   by `VarkaKernelChecks.sanitized` (`:173`), which the harness's answer checks and
   `VarkaCompiledCheck`'s calls use, and by `VarkaReplay` (`:227`). So no production kernel call
   is made outside a window.

## A. Row 430: a windowless call after a windowed warm-up

### A.1 The admission check

1. **The trap is a speculative null assert, read from HotSpot's source.** When C2 parses the
   `ifnull` on the window, it asks the type profile whether the value was always null
   (`parse2.cpp:2658-2662`). If it was, C2 emits `null_assert`, an uncommon trap on a non-null
   value. The trap's reason is `Reason_speculate_null_assert`, and the trap history records it
   as `Reason_null_assert` (`deoptimization.hpp:412-413`). That is the log's
   `null_assert_or_unreached0` that `VARKA-401.md` 2 quotes. The branch's own profile then
   decides the other direction: a branch never taken gets an `unstable_if` trap
   (`parse2.cpp:1653-1658`, `seems_never_taken` at `:1335`). Both profiles belong to the
   inlined method, `check`, not to the kernel that inlines it: `dynamic_branch_prediction` reads
   `method()->method_data()` (`:1213`), and while `check` is parsed inline `method()` is `check`.
   So the trap needs **a JVM whose calls of `check` all went one way when C2 compiled the
   kernel**, followed by a call that goes the other way. This was read from the source and not
   run.
2. **Where that can happen.** The flag reaches these JVMs:
   - **The shared test JVM** under `dev/varka_matrix.sh`, which turns the sanitizer on in every
     test JVM (`:257`), on CI as on the laptop. Here `check` sees both directions as soon as one
     harness call (windowed) and one direct `kernel.run` (windowless) have run, so the trap can
     only hit kernels compiled before that. Direct windowless calls are in the emitter suites
     that call a kernel themselves: `VarkaEmitterArithmeticSuite` (8 call sites),
     `VarkaEmitterFloatingArithSuite` (6), `VarkaDecimalLoadSuite`, `VarkaInternalErrorSuite`,
     `VarkaEmitterNarrowSuite`, `VarkaCompilationWatchSuite` and
     `VarkaEmitterFixtures.runKernel2`. Each asserts answers and none asserts a tier.
   - **Own-JVM children.** `VarkaChildJvm.run` copies every argument of the parent's command line
     (`:167-172`), so `VarkaOwnJvm` suites run sanitized under the matrix. The JIT-asserting one,
     `VarkaTierWitnessSuite`, calls kernels only through `VarkaCompiledCheck`, inside a window,
     as its class comment says.
   - **Forked probes do not get the flag.** `VarkaKernelWarmupSuite` (`:100`) builds its
     child's command line itself and copies only the vector-width flags.
     `VarkaInliningCliffSuite` passes a fixed command line. `VarkaDeoptCycleSuite` forks no JVM:
     it parses recorded logs with `dev/varka_deopt_cycle.py`. `VarkaSanitizedWarmupSuite` adds
     the sanitizer on purpose (`:53-62`). In the other probes `check` is dead code.
   - **Benchmarks never run sanitized** (the class comment: "the only mode of a benchmark").
3. **The positive control is the only place the trap is asserted.** `VarkaSanitizedWarmupProbe`
   warms in a window, makes `CALLS` = 200 calls in a window, then `WINDOWLESS_CALLS` = 5000
   calls with none, and the suite requires a `make_not_entrant` at `check` in the second range.

### A.2 What differs from the row text

- The row says the suite "makes 200 windowless calls". The probe makes 200 calls in a window and
  5000 without one (item 3).
- The row lists "a unit test calling a kernel directly" and "a probe's measured calls" as the
  windowless callers. Item 2 narrows that. The probes' JVMs do not have the flag. In the shared
  test JVM the profile turns two-sided early, so a trap costs at most a recompile of a kernel
  compiled before that. No production path calls a kernel without a window.
- **A profile-neutral form needs the call to stay out of line.** Any test on the window, whether
  a null test, a flag or a sentinel window's field, is a branch with a profile of its own, and a
  sentinel `Window` subclass is a type check with a class-check trap. Only a `check` that C2
  does not inline leaves the kernel's code independent of the window. The trap can then fall
  only on `check`'s own small nmethod.
- **How "not inlined" could be asked for.**
  `-XX:CompileCommand=dontinline,...VarkaMemorySanitizer::check`
  still applies to a method that a compiler directive matches, as long as that directive has no
  inline rules of its own (`compilerDirectives.cpp:550-561`). `VarkaKernelCompileDirective`'s
  directive sets only `c1: { Exclude: true }`. A second directive added at run time with a
  `match: "*.*"` would not work, because the first matching directive wins
  (`compilerDirectives.cpp:764-787`): it would shadow the warmed kernels' C1 exclusion, or the
  kernel directive would shadow it.

### A.3 Goal and non-goal

Goal: meet row 430's done-when by one of its two arms (question A1).

Non-goal: the warm-up's own window (row 401, done) and the cost of the scan (section B).

### A.4 The design, two arms

**Arm 1, the listing (recommended).** The sanitizer's class comment gets one paragraph listing
the windowless callers of A.1 item 2 and why a trap does not matter in each. In the shared test
JVM it is a recompile of an early kernel in a suite that asserts answers. The forked probes and
benchmarks never have the flag. The JIT-asserting own-JVM suite calls only in windows.
`VarkaSanitizedWarmupSuite` keeps its positive control. No production code changes. A test
keeps the listing honest: `VarkaSanitizedWarmupSuite` gains a case that scans the test sources
for `VarkaMemorySanitizer.PROPERTY` and `sanitizeMemory` and asserts that the only fork that
passes the property is its own. If the owner prefers no source-scanning test, it is dropped and
the paragraph alone carries the listing.

**Arm 2, an out-of-line check.** Every launcher that turns the sanitizer on also passes
`-XX:CompileCommand=dontinline,<package>/VarkaMemorySanitizer::check`, with `<package>` the class's
package in slash form:
`dev/varka_matrix.sh`, `dev/varka_emit.sh`, `dev/varka_pit.sh`, `dev/varka_new_tests.py` and
the probe fork in `VarkaSanitizedWarmupSuite`. `VarkaChildJvm` copies it on. The probe's
positive control would then find no trap at `check` in the kernel's nmethods. It would be
rewritten to assert the opposite: no kernel method is made not entrant across the windowless
calls, and a trap, if any, falls only on `check` itself. A plain `build/sbt testOnly` with the
property set by hand would still inline. A guard in `VarkaMemorySanitizer`'s static initializer
could warn, but reading the JVM's compile commands needs a diagnostic command. The plan does
not build that.

### A.5 Tests

- Arm 1: the paragraph, and the source-scanning case above. `VarkaSanitizedWarmupSuite`'s
  existing case is unchanged.
- Arm 2: `VarkaSanitizedWarmupSuite`'s positive control inverted as above, and one run of
  `dev/varka_matrix.sh --defaults` on CI (the PR's option-matrix dispatch) to show the flag
  breaks no suite.

### A.6 Predictions and costs, registered before the build

- PA1 (arm 1): no test time changes beyond the source-scanning case, which reads the Varka test
  sources and runs in under 2 s, against the calibration table's compile-only suite of
  VARKA-318 (1.9 to 2.3 s for a whole suite that resolves the coverage rows).
- PA2 (arm 2): the option matrix's wall time moves by less than its run-to-run spread. A
  mapping is checked once per column per call, outside the row loop, and an out-of-line call
  costs a few nanoseconds.
- PA3 (arm 2): `emitted_bytes.json` and every pinned output are unchanged. The flag changes C2's
  code, not the bytes.

### A.7 Order, files and size

| File | Arm | Change |
|---|---|---|
| `VarkaMemorySanitizer.java` | 1 | the class comment's paragraph |
| `VarkaSanitizedWarmupSuite.scala` | 1, 2 | the source scan (1), or the inverted control (2) |
| `dev/varka_matrix.sh`, `varka_emit.sh`, `varka_pit.sh`, `varka_new_tests.py` | 2 | the flag |
| `sql/varka/plans/m8/VARKA-430.md` | both | the outcome |

One pull request, verified by its PR Build and, for arm 2, an option-matrix dispatch. Size:
small (arm 1 about half a day, arm 2 about a day).

### A.8 Done when

Row 430's done-when, through the arm the owner picks: the windowless callers listed in the
sanitizer's class comment with the reason for each (arm 1), or `check` out of line in every
sanitized launcher with the probe's control showing no kernel deoptimized (arm 2).

## B. Row 437: the window's range scan

### B.1 The admission check

1. **How many ranges a warm-up registers.** `Job.run` registers every segment in `owned` once,
   before the calls start (`:736-744`): one per allocation made through `alloc`, which covers
   the copied batch's data and validity, the destinations and the scratch, once for the slice
   calls and once for the probe calls. The row's "about 67" for a wide kernel is the review's
   count. It is not committed anywhere else and this plan does not recount it.
2. **How many checks a warm-up makes.** The warm-up runs up to `SPIN_CALLS` = 16000 calls in
   blocks of `BLOCK_CALLS` = 64, each block followed by `PROBE_CALLS` = 4 probe calls, then
   paced probes every `PACE_MILLIS` = 5 ms until the verdict (`VarkaKernelWarmup.java:149-181`).
   Each call maps each column it reads or writes and its validity, and each mapping is one
   `check`. Before an over-read is found, each check scans on average about half of the
   registered ranges.
3. **The bound that follows.** With a wide kernel's few dozen mappings a call and about 67
   ranges, the spin phase does on the order of 10^7 range compares. At a few nanoseconds per
   compare that is tens of milliseconds per warm-up. This is reasoned, not measured. The only
   committed sanitized timing of the warm-up suites is VARKA-401's table (`VARKA-401.md` 4): the
   four warm-up suites took 62 s with the sanitizer on and 61 s off. The scan is not visible at
   that grain.
4. **The evaluator's window is small.** A batch registers its inputs, outputs, scratch and
   derived columns, one range each. Only the warm-up's window holds both the slice calls' and
   the probe's copies.

### B.2 What differs from the row text

- The row asks for a sorted index "and a sanitized warm-up's time to its verdict measured before
  and after". Item 3 predicts that the scan is a small share of a verdict that takes seconds, so
  the measurement may show no difference. The plan therefore measures the scan's share before
  building the index (B.4 step 1) and asks the owner whether a share below the measurement's
  spread closes the row with its figure (question B1).
- The warm-up's verdict time is already logged ("is compiled after a warm-up of N ms",
  `VarkaKernelWarmup.java:845-850`). `VarkaSanitizedWarmupProbe` prints the state but no time.

### B.3 Goal and non-goal

Goal: a sanitized warm-up's time to its verdict measured with the flat scan and with the
replacement, and the replacement built if the first measurement shows the scan is worth it.

Non-goal: the violation message. The nearest-range search runs only on the failure path and
keeps its linear scan.

### B.4 The design

1. **Measure first.** A probe, `VarkaSanitizedWarmupTimingProbe` (test code), warms the
   widest kernel of `VarkaKernelWarmupProbe`'s shapes three times in a sanitized JVM. For each
   warm-up it prints the verdict time from the warm-up's log line, `checked()`'s difference and
   the window's range count. A loop in the same probe times `Window.check` over a window of the
   same count of ranges, a million checks of a mapping in the middle range. The window is
   package-private and works without the property. The scan's share is checks times the cost of
   one check, divided by the verdict time.
2. **If the share is worth it, the replacement.** There are two forms (question B2):
   - **(a) one range per column**, as the row's alternative says: `Job` allocates each column's
     slice and probe copies as one segment and registers that segment once. That halves the
     ranges, and the scan stays flat.
   - **(b) a sorted index**: `Window` sorts its ranges by start on the first `check` after an
     `add` (a dirty flag), keeps a running maximum of ends, and binary-searches the last start at
     or below the address. If that range holds the mapping, `check` returns. Otherwise it walks
     back while the running maximum still reaches the mapping's end, since ranges may overlap: a
     window can hold an input both as its own buffer and inside a derived one.
3. Whichever form is built, the probe is rerun on the same host and both timings are committed to
   `m8/results/VARKA-437-warmup-scan.txt`.

### B.5 Tests

- `VarkaMemorySanitizerSuite` gains `Window` cases with no property needed: overlapping ranges,
  a mapping at a range's exact end, a mapping spanning two adjacent ranges (a violation, as
  today), an `add` after a `check` (the dirty flag), and the nearest-range message unchanged.
  For form (b) they run against the flat scan as the oracle over random windows of 1 to 100
  ranges.
- `VarkaSanitizedWarmupSuite` and `VarkaMemorySanitizerEndToEndSuite` unchanged and passing.

### B.6 Predictions, registered before the run

- PB1: the scan's share of a sanitized warm-up's verdict time on the widest probe shape is under
  5%.
- PB2: if a replacement is built, the verdict time moves by less than its spread over the three
  warm-ups.
- PB3: the new `Window` cases add under 2 s to `VarkaMemorySanitizerSuite`, at about the
  calibration table's ratio for a JUnit class with no session (VARKA-328, about 0.03 of a
  padded estimate).

### B.7 Order, files and size

| File | Change |
|---|---|
| `VarkaSanitizedWarmupTimingProbe.java` (test, new) | step 1's measurement |
| `m8/results/VARKA-437-warmup-scan.txt` | the measurement, before and after |
| `VarkaMemorySanitizer.java` or `VarkaKernelWarmup.java` | form (b), or form (a) |
| `VarkaMemorySanitizerSuite.scala` | the `Window` cases |

Order: the probe and its first measurement, then, if question B1 says build, the replacement and
the second measurement, in one pull request. The probe runs on the laptop as a diagnosis run
under the shared JVM slots with `SPARK_LOCAL_IP=127.0.0.1`. It needs no CI runner, since it
compares two forms on one host. Size: small.

### B.8 Done when

Row 437's done-when: the sorted index or one range per column built, and the verdict time
measured before and after and committed, or, by question B1, the scan's share measured and
committed and the row closed with that figure.

## 1. Out of scope (both rows)

- The sanitizer in benchmarks: off by design.
- Reading the JVM's compile commands from the sanitizer (A.4, arm 2's caveat).

## 2. Questions for the owner

- **A1. Which arm closes row 430.** (1) the listing, kept honest by a test that only one suite
  forks a sanitized child; (2) `check` out of line through a compile command in every sanitized
  launcher. *Recommended: (1).* No production path calls a kernel without a window. The trap
  costs a recompile in suites that assert answers and not tiers. Arm 2 spreads a JVM flag over
  five launchers and still misses a hand-typed `testOnly`.
- **B1. Does a small share close row 437 without the index.** If PB1 holds (under 5%), close
  the row with the committed figure rather than build the index. *Recommended: yes.* The row's
  harm is time to a verdict in sanitized runs, and a share below the spread is no harm.

  *1 November 2026, by the owner's word:* yes: a share under 5% closes row 437 with the committed
  figure.
- **B2. Which replacement, if one is built.** (a) one range per column; (b) a sorted index.
  *Recommended: (a).* It is the smaller change, it is in the warm-up where the many ranges come
  from, and the evaluator's windows are small already.

## 3. Open points for the lead

1. The row's "about 67" is the review's count and is in no committed file. Step 1's probe prints
   the range count of the shape it measures, and that count is what the outcome quotes.
2. If arm 2 is chosen, `skills/testing-and-debugging.md`'s sanitizer lesson should name the
   compile command beside the property.

## 4. Outcome

(Filled in when the rows are built.)
