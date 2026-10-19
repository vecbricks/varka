# VARKA-395: The template warm-up holds no lock while it waits, and states what it costs

*Row 395 of `m8/PLAN.md`, beside wave 0. Planned on 19 October 2026 with the code read at
`e11d025e91e` (the warm-up unchanged since `25d8b251098`, where the row read it).*

## 1. Where this came from

The post-merge reviews of #921 (VARKA-389) and #924 (VARKA-391) found three medium costs in
`VarkaVectorTemplateWarmup`. Milestone 7's close review (`m7/PLAN.md` 12.1) records why the
reviews came after the merge: the three code pull requests of the close opened without a
self-review. Section 4.1's note of 19 October puts this row before F1, because every new lane's
kernels pass through the warm-up. So the fix keeps the warm-up's guarantee: the shared Vector API
templates are warmed before any of Varka's vector code runs in the JVM, and so before a kernel can
take them to C2 (`VARKA-389.md` 8.4a, 9.1). It removes or states the costs.

The three findings, as the row states them:

1. **The pacing waits while holding the lock.** Since #921 the warm-up runs on the first thread
   that needs vector code, inside `static synchronized ensure()`. #924's pacing may wait there up
   to `PACE_BUDGET_MILLIS` (20 s) for C2's queue, and every other thread that reaches the hook
   waits on the monitor meanwhile. The class doc and `VARKA-389.md` 9.1 say 200 to 600 ms.
2. **Every queue read is a global safepoint.** The pacing reads C2's queue with the
   `Compiler.queue` diagnostic command. HotSpot runs that command as `VM_PrintCompileQueue` on the
   VM thread (`diagnosticCommand.cpp` 840 to 842), and `VM_Operation::evaluate_at_safepoint()` is
   true unless overridden (`vmOperation.hpp` 169). `VM_PrintCompileQueue` does not override it
   (`vmOperations.hpp` 267 to 274). The warm-up reads the queue twice before the rounds (once
   for its log line, once to wait), once after every one of the 300 rounds, once more at the end,
   and every 5 ms while it waits: 303 readings and one per 5 ms waited.
3. **C2's thread count is wrong on small hosts.** `c2Threads()` computes `count - count / 3`.
   HotSpot (`compilationPolicy.cpp` 612 and 613) gives C1 `max(count / 3, 1)` threads and C2 the
   rest, at least one. At `CICompilerCount` 2, which the ergonomics choose at 1 to 3 processors
   (565: `max(log2(n) * log2(max(log2(n), 1)) * 3 / 2, 2)`), HotSpot runs one C2 thread and the
   warm-up assumes two. Its limit and its target are then twice HotSpot's, on exactly the small
   executors the pacing exists for.

The lower findings: an interrupt during the pacing leaves the rest of the rounds unpaced; the
javadoc of `beforeVectorCode` promises to catch an initialization failure that the JVM throws at
the call site; the queue is not found under JVMCI's name; `DoubleVector.lanewise(Binary, double)`
and `reinterpretAsLongs` are emitted but called fewer than 2,000 times; and two sentences of the
class doc on where the warm-up runs contradict its own section "When it runs".

## 2. The admission check, done

What reading settled:

1. **The wait is the guarantee, the length is the cost.** Vector code of the engine must not run
   before the rounds finish: a kernel's calls before the warm-up are what take a template to a
   standalone compile, and the warm-up's rounds then deoptimise it (`VARKA-389.md` 8.4a). So a
   thread that reaches the hook while the warm-up runs has to wait for it, whichever thread runs
   it. What can change is which lock it waits on, whether a waiter can disturb the run, and how
   long the run may take. The pacing's budget bounds the last.
2. **No cheaper reading of the queue exists.** HotSpot's perf counters under `sun.ci`
   (`compileBroker.cpp` 696 to 783, 1017) count compiles and times, and `sun.ci.threads` is
   C1 and C2 together; none holds a queue length. `CompilationMXBean` has only the total
   compilation time. The diagnostic command is the only reading, so the fix is its rate and a
   measurement of what it costs.
3. **The redundant read.** With `PACE_ROUNDS` at 1 the loop already reads the queue after the
   300th round, and `warmUp` then reads it again at once. The second read returns at once
   unless the queue grew in between, and is one safepoint for nothing.
4. **Where the doubles' scalar forms come from.** The emitter calls `DoubleVector.add`, `sub`,
   `mul` and `div` with a `double` (`VarkaDivisionLowering` 429 to 446, descriptor
   `LANEWISE_VD`). Each is one call to the final `lanewise(Binary, double)`, whose body calls
   the abstract `broadcast` and `lanewise(Binary, Vector)` (`DoubleVector.java` 908 to 913).
   The warm-up calls only the `Vector` forms, and its masked `lanewise(ADD, 2.0, m)` is another
   method. So `lanewise(Binary, double)` gets no call from the rounds, and its first compile
   falls in the kernels' traffic: the coin of `VARKA-389.md` 9.5. `reinterpretAsLongs` (and
   `reinterpretAsDoubles`, emitted beside it at 431) is called once per round, 300 times.
5. **The initialization failure.** `beforeVectorCode`'s `try` can only catch what happens inside
   it. If `VarkaVectorTemplateWarmup`'s own initializer fails, the JVM throws at the caller's
   `invokestatic`, before the `try`. The initializer builds the operator lists from
   `VectorOperators`, so a missing or broken incubator module fails it there. `IntRangeOps` and
   `SelectionVectorOps` call the hook from their own initializers, unguarded, so such a failure
   would make both classes unusable for the JVM's life.
6. **JVMCI.** `compileBroker.cpp` 963 names the top tier's queue "JVMCI compile queue" when
   `UseJVMCICompiler` is on, so the parser counts nothing and the pacing never waits. With the
   native library the split is `JVMCINativeLibraryThreadFraction` of the count (604 to 608).
7. **Without tiered compilation** (`-XX:-TieredCompilation`) there is no tier-2 or tier-3
   choice for the pacing to steer, and every compile goes to C2's queue, so the pacing would wait
   for nothing.

## 3. The design

### 3.1 The changes

1. **The run holds no lock that other threads take.** `ensure()` claims the run in a short
   `synchronized` block and runs the rounds outside it. The thread that claims it runs the
   warm-up on its own thread, as since #921. Any other thread waits for a latch the run opens
   when it ends, success or failure, so the guarantee of 2.1 holds. Neither the run's pacing nor
   a waiter can be cut short by an interrupt. The pacing's sleep keeps waiting when interrupted,
   and the waiter's `await` does too, so a cancelled task cannot leave the rounds unpaced or run
   vector code early. Each puts the interrupt back on its thread when it returns.
2. **A stated budget.** `PACE_BUDGET_MILLIS` drops from 20 s to the bound section 6 measures, and
   the class doc states the warm-up's cost as the rounds plus at most that budget, for every
   thread that reaches the hook meanwhile. Admission rule for the number: the longest total wait
   measured at two and at four cores, after change 4, times two, rounded up to a whole second,
   and not over 5 s. If two cores need more than 5 s, that is recorded and the budget stays at
   5 s: the pacing then runs out on such a host, and the log line says so.
3. **Fewer safepoints.** The redundant read of 2.3 goes. The sleep while waiting goes from 5 ms
   to 10 ms: a C2 compile takes milliseconds to hundreds of milliseconds, so the queue does not
   move much faster than that. Each wait then overshoots by at most 10 ms. The read after every
   round stays: #924 measured "every round" on four cores and nothing else, and one round's
   burst is what puts the queue over the limit on a small host.
4. **C2's threads as HotSpot counts them.** `c2Threads(count)` becomes a pure function:
   `count - max(count / 3, 1)`, at least 1. With `-XX:-TieredCompilation` the pacing is off.
   The parser accepts "JVMCI compile queue" as the top tier's queue. The JVMCI native library's
   thread split is recorded, not mirrored (7).
5. **The hook's class cannot fail to initialize on the Vector API.** The rounds, their operator
   lists and every method that names a vector type move to a nested holder class, `Rounds`,
   which loads inside `ensure()`'s `try`. What is left in the outer class's initializer reads
   JVM options and an `ObjectName`, each behind its own catch. The javadoc of
   `beforeVectorCode` then says what is true: a failure of the warm-up, its class loading
   included, is caught, and the hook's own class has nothing in its initializer that can fail
   on a missing module.
6. **The doubles' scalar forms and the reinterpretations in the wrapper block.** `add`, `sub`,
   `mul` and `div` with a `double` join the doubles' block of `THIN_CALLS_PER_ROUND` calls. The
   two reinterpretations of the conversions move to their own block of the same count. Each then
   gets 2,400 calls in the rounds.
7. **The class doc** says where the warm-up runs once, in "When it runs", and its cost there.

### 3.2 What is deliberately unchanged

* The rounds, their count, the operators and species they feed, and the read after every round.
* The four call sites of the hook, and the property that switches the warm-up off.
* Row 383's boxing at `IntVector.lanewiseTemplate` on a cold template is a separate row.
* A dedicated warm-up thread. A background thread started at session start could finish before
  the first query and take the wait off every thread. It needs a start point that every JVM
  passes before any vector code, without a race with the helpers' initializers, and the engine
  has none today. The caller still waits for it whichever thread runs it (2.1).

### 3.3 Registered op counts

None: no kernel changes, and the bytes oracle does not move.

## 4. Files

* `sql/catalyst/.../codegen/varka/VarkaVectorTemplateWarmup.java`: the claim and the latch, the
  uninterruptible waits, the budget, the sleep, the removed read, `c2Threads(int)`, the
  tiered-compilation switch, the JVMCI queue name, the `Rounds` holder, the added calls, the
  class doc.
* `sql/catalyst/.../codegen/varka/VarkaVectorTemplateWarmupSuite.scala`: tests 1 to 5 of
  section 5, and the broken sentence of its scaladoc.
* `sql/varka/plans/m8/PLAN.md` row 395, `sql/varka/plans/m7/VARKA-389.md` 9.1 (a dated note on
  the cost), `sql/varka/skills/the-jit.md` (the safepoint and the thread split).

## 5. Tests, and what each is for

1. **HotSpot's split at two and three processors.** `c2Threads(2) == 1` (the count HotSpot's
   ergonomics choose at 1, 2 and 3 processors), `c2Threads(3) == 2` (4 to 7 processors),
   `c2Threads(4) == 3`, and the counts the ergonomics choose at `-XX:ActiveProcessorCount=2`
   and `3` are confirmed once from `-XX:+PrintFlagsFinal` and recorded here.
2. **A waiter waits for the run, and a second run never starts.** Two threads: one claims the
   run, the other enters `ensure()` meanwhile and returns only after the run completes;
   `completedRuns()` is 1. The existing test of two threads keeps its assertion.
3. **An interrupted pacing still waits.** `awaitC2Queue` with an injected queue reader that
   always reports a long queue, on a thread whose interrupt flag is set: it waits its budget,
   not zero, and the thread's interrupt flag is set when it returns.
4. **An interrupted waiter still waits, and keeps its interrupt.**
5. **The parser reads JVMCI's queue name.**
6. **The four-core end-to-end suite**, `VarkaWarmupEndToEndSuite` pinned to four cores with
   `-XX:MaxVectorSize=32`, as #924 left it: five of five in each of three runs.

## 6. The measurement

What the warm-up costs, from the JVM's own account: `-Xlog:safepoint` for every
`PrintCompileQueue` safepoint (time to reach it and total), and the warm-up's log line for its
time, C2's queue and its waits. Arms: master and the fix, in the four-core end-to-end suite
(`taskset -c 0-3`, `-XX:MaxVectorSize=32`) and pinned to two cores (`taskset -c 0-1`), where
HotSpot runs one C2 thread.

### 6.1 Predictions, registered before the run

1. **Safepoints on master, four cores:** 302 to 420 `PrintCompileQueue` safepoints per warm-up,
   302 fixed reads plus one per 5 ms waited (#924 measured holds of 203 to 288 ms).
2. **Their cost:** 20 to 500 us each in total, 10 to 100 ms summed over the warm-up, all
   threads stopped.
3. **The warm-up's time on master, four cores:** 200 to 600 ms, waits included, as #924 read.
4. **The fix at four cores:** the same 200 to 600 ms. Safepoints 301 plus one per 10 ms waited,
   so 301 to 360.
5. **The fix at two cores:** the waits grow, because the target halves to 2 (`Tier3DelayOff`
   times one thread): 0.2 to 3 s in total.
6. **HotSpot's ergonomics:** `CICompilerCount` 2 at 1, 2 and 3 processors, 3 at 4.
7. **The four-core suite on the fix:** five of five in three runs of three.

## 7. Risks

1. **A budget too short for a small host.** The pacing runs out and the wrappers' tier is the
   coin again on that host. The log line reports the waits against the budget, and section 6's
   two-core arm sets the number.
2. **A slower sleep misses a short window.** A queue that drops under the target for under
   10 ms between two reads is missed, and the wait continues to the next read. The
   four-core suite is the check.
3. **A deadlock through a class initializer.** A waiter inside `IntRangeOps.<clinit>` holds that
   class's initialization lock while it waits. The run must never need it, and it does not: the
   rounds call only the JDK.

## 8. Sequencing

1. This plan; the ergonomics check (6.1 #6); master's arms of section 6.
2. The code and the unit tests; the fix's arms; the budget from the rule of 3.1 item 2.
3. The four-core suite three times; the record; the review.

Explicitly out of this row: row 383's boxing, a background warm-up thread (3.2), the JVMCI native
library's thread split, and an audit of every Vector API method the emitter can call against the
warm-up's counts (section 9 says whether the review proposes one).

## 9. Outcome

19 October 2026. Built as section 3 planned, with one addition the tests needed: a test-only gate
(`holdRunsForTest`) that holds a run before its rounds, so that a second thread can be seen
waiting. The budget is **2 s** by the rule of 3.1 item 2: the longest wait measured was 537 ms.
Eighteen JVMs of the end-to-end suite are in `results/VARKA-395-warmup-runs.txt`, each with the
warm-up's own log line and its `PrintCompileQueue` safepoints from `-Xlog:safepoint`: master and
the fix at four and two cores, and six more four-core JVMs on the shipped code (9.2).

| arm | runs passed | warm-up | waited | queue safepoints | their pause, summed | largest one |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| master, four cores | 3 of 3 | 167 to 232 ms | 41 to 110 ms | 309 to 322 | 6 to 8 ms | 379 us |
| master, two cores | 3 of 3 | 274 to 361 ms | 95 to 188 ms | 311 to 331 | 14 to 24 ms | 3.4 ms |
| fix, four cores | 3 of 3 | 203 to 335 ms | 54 to 181 ms | 307 to 318 | 7 to 9 ms | 457 us |
| fix, two cores | 3 of 3 | 424 to 828 ms | 263 to 537 ms | 325 to 338 | 18 to 36 ms | 6.4 ms |
| shipped, four cores, loaded | 6 of 6 | 285 to 514 ms | 94 to 275 ms | 306 to 322 | 10 to 34 ms | 5.1 ms |

The first four arms ran at a load average of 0.5 to 7, except the fix's last two two-core runs (7
to 20); the shipped arm at 5 to 17, beside other agents' test JVMs.

What the runs say:

1. **The finding of item 3 is live on master.** Master's two-core JVMs log "the policy's limit of
   10 and the pacing's target of 4", where HotSpot ran one C2 thread (checked with `jcmd
   Thread.print` at `-XX:ActiveProcessorCount=2` and `3` under
   `-XX:-UseDynamicNumberOfCompilerThreads`: one C2 and one C1 thread each; two C2 at 4). The fix
   logs 5 and 2. At two cores it waits longer for it, 263 to 537 ms against master's 95 to 188
   ms, the cost of holding the queue where the policy decides.
2. **A reading of the queue is cheap on an idle JVM and not on a busy machine.** The median
   safepoint is 8 to 22 us in every arm, but single ones reached 2 to 6 ms on two cores and on a
   loaded four-core machine, and those carry the sums of 10 to 36 ms there; on an idle four-core
   machine the sum is 6 to 9 ms. All of it falls inside the warm-up, once per JVM. Raising the
   sleep to 10 ms and dropping the redundant read kept the count at 306 to 338 although the fix
   waited longer. The reading after every round stays; the cost is stated here and in the class
   doc.
3. **The hold is now bounded by a stated number.** The longest warm-up measured is 828 ms, at two
   loaded cores. A thread that reaches a hook meanwhile waits on a latch for at most the rounds
   plus about 2 s, where it could wait 20 s before, on the class's monitor.
4. **The four-core suite passed every test in every run**, master and the fix, and so did the
   two-core runs: 18 JVMs, 90 tests.

The lower findings: the interrupt is fixed (test 3, and test 4 for a waiter), the JVMCI queue name
is read (test 5) and its native-library thread split is recorded only (2.6), the doubles' scalar
forms and the reinterpretations get 2,400 calls each, the hook's class names no Vector API type
(the compiled class has no `jdk/incubator` reference, the review checked), and the class doc says
where the warm-up runs in one place. Planted faults were caught: a waiter that does not wait failed
the three tests that hold a run, and a pacing that stops at an interrupt failed test 3.

### 9.1 Predictions scored

1. **Safepoints on master, four cores, 302 to 420: held.** 309, 321 and 322. The fixed count is
   303, not the 302 the prediction counted (section 1 item 2); no score changes.
2. **Their cost, 20 to 500 us each and 10 to 100 ms summed: missed, too high on an idle
   machine.** Medians of 10 to 11 us and sums of 6 to 8 ms on master at four cores. The two-core
   runs and the loaded four-core runs fell inside the band's sum (10 to 36 ms) through a few slow
   ones, 2 to 6 ms each; the prediction priced every safepoint like those.
3. **The warm-up on master, four cores, 200 to 600 ms: held in two runs of three**; the third took
   167 ms.
4. **The fix at four cores, 200 to 600 ms and 301 to 360 safepoints: held.** 203 to 514 ms over
   nine runs, 306 to 322 readings; the fixed count is 302.
5. **The fix at two cores, waits of 0.2 to 3 s: held**, at the low end: 263 to 537 ms.
6. **The ergonomics, `CICompilerCount` 2 at 1 to 3 processors and 3 at 4: held**, from
   `-XX:+PrintFlagsFinal` (and 4 at 8, 12 at 24).
7. **The four-core suite on the fix, five of five in three runs of three: held**, and in six more
   on the shipped code (9.2).

### 9.2 The shipped code, and the review

The fix's first six JVMs ran with a budget of 5 s, which never bound. Six more four-core JVMs ran
on the shipped code, three at the 2 s budget and three after the review's changes below: five of
five each, waits of 94 to 275 ms.

The independent review of the diff found no correctness or concurrency bug, and checked the
claim and latch, the class initializers and the HotSpot facts against the JDK source. It found,
and this row fixed: this section missing; the first calibration entry's ratio inverted; a test
helper that could pass a waiter that does not wait when the machine is slow (it now waits until
the waiter is parked on the latch, and rethrows what the waiter threw); the forked JVMs of the
split test inheriting `JAVA_TOOL_OPTIONS` and waiting without a timeout; no test of a waiter
woken by a failed run (added, with the test gate now held before the injected failure); the
plan's read counts one short; the budget stated as exact where each wait can end one sleep past
it (the doc now says "about"), and the log line's peak covering only the paced part (it now says
"while paced"); the latch assigned after the claim flag; the state a test leaves for the next
run's log line; and a broken paragraph wrap.

Left as they are, each recorded here:

* `queueBefore`'s reading and the first wait's reading are back to back, one safepoint of about
  10 us that seeding the log line from the wait would save.
* With the property off, `ensure()` settles without claiming, so a direct `ensure()` after the
  property is cleared would run the warm-up while the hook stays off. Production calls only the
  hook.
* `IntRangeOps` and `SelectionVectorOps` initialise `IntVector.SPECIES_PREFERRED` before their
  static block calls the hook, so the Vector API's classes initialise before the warm-up there;
  no `lanewise` template runs before it, which is what the warm-up guards.

**Proposed, not done here:** a check that every Vector API method the emitter can call is called
at least 2,000 times by the warm-up's rounds. The scalar `DoubleVector` forms of section 2.4 had
no call at all, and nothing would catch the next wrapper the emitter adds. It needs the emitter's
call set from its code, which the descriptors in `VarkaDescriptors` and `Lane` only partly name,
so it is a row of its own.
