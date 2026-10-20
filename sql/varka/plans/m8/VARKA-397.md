# VARKA-397: every Vector API method the kernels call warmed past the policy's mark

*Row 397 of `m8/PLAN.md`, beside wave 0. Planned on 19 October 2026 with the code read at
`d49b71a9daf`. Section 2 is a spike run on that commit, before this plan's predictions.*

## 1. Where this came from

Row 395's review (`VARKA-395.md` 9.2) found that the emitter calls `DoubleVector.add`, `sub`,
`mul` and `div` with a scalar, which the template warm-up (`VarkaVectorTemplateWarmup`) never
called, and that the double reinterpretations had 300 calls where the warm-up's own design asks
for 2,000 (`THIN_CALLS_PER_ROUND`'s doc: past that mark the tiered policy treats a method as old,
its later tier decisions ignore C2's queue and its queued compile cannot go stale). #932 added
those calls by hand. Nothing catches the next one: the emitter's call set is named only in part by
the descriptors of `VarkaDescriptors` and `Lane`. Section 4.1's note of 19 October puts this row
before F1, since each lane row adds calls the emitter makes. **Done when** a test fails when any
Vector API method an emitted kernel invokes gets fewer than 2,000 calls from the warm-up's rounds,
every new lane row's kernels included, with one planted omission caught.

## 2. The admission check, done

A spike (`VarkaWarmupReach`'s first form, run with `catalyst/Test/runMain`) settled what the check
needs:

1. **The mark is HotSpot's.** `CompilationPolicy::is_old` (`compilationPolicy.cpp` 1000 to 1006
   in the local JDK 25 source) applies the call predicate at level 0 scaled by
   `TieredOldPercentage / 100`, 1000 / 100: `Tier3InvocationThreshold` 200 times ten, 2,000 calls.
2. **The unit is the method HotSpot counts, not the call's symbolic reference.** Read by its
   symbolic reference the gap looks different from what HotSpot sees. `Vector.convertShape` is
   abstract and each vector class implements it (`Int512Vector.convertShape`), so the emitter's
   call written against `Vector` reaches a different counter per lane; `Vector.reinterpretAsLongs`
   and `LongVector.reinterpretAsLongs` both reach `AbstractVector.reinterpretAsLongs`, one
   counter; `VectorMask.toLong` reaches `Int512Mask.toLong` or `Long512Mask.toLong` by its
   receiver. So the check keys every call by the class whose method body it dispatches to for the
   warm-up's preferred-species receivers, read by reflection over the JDK's own classes.
3. **The warm-up's counts need the receiver, not only the call site.** The warm-up's mask calls
   are written against `VectorMask` for all three lanes. The counting copy of the rounds records
   each instance call's receiver class: it sets the arguments aside in fresh locals, passes the
   receiver to the counter, and puts them back. With the call site alone, the 600 mask calls
   could not be told apart by lane.
4. **A kernel's lane is read from its class.** The bytes give a call's static owner and not its
   receiver, so a kernel's `VectorMask.and` is taken to reach the mask of every lane its class
   uses (the vector classes its calls name and whose species it reads). Conservative: a class with
   a double division asks for the double masks to be warm too.
5. **Counting by running a copy works.** The rounds' class (`VarkaVectorTemplateWarmup$Rounds`)
   is read from the class path, rewritten with `java.lang.classfile`, and defined with
   `Lookup.defineHiddenClass(..., NESTMATE)` in the warm-up's nest, so the copy keeps its access to
   the warm-up's private statics (the pacing's log-line fields). One counted pass took 73 to 194 ms.
6. **The corpus converges fast.** The coverage table's 119 rows under the matrix's base reach 60
   counted methods; the first 200 fuzz shapes of each lane add 3; the coverage rows and 20 fuzz
   shapes of each lane under the 47 other distinct matrix configurations add 12 (`useAVX=0` 9,
   `validityByWidth=false`, `floorMod7=DIV` and `division=DOUBLE_RECIP` one each): 75 in all.
   In an earlier run that keyed calls by their call sites, 20 fuzz draws a lane found all that 500
   did. The whole corpus took about 26 s of emission in the spike's JVM, half of it the first
   pass's class loading and JIT.
7. **38 of the 75 are under the mark on master**, every one a gap of the kind 9.2 found: never
   called (`IntVector.add(int,VectorMask)`, `sub(int)`, `min(int)`, `max(int)`, `or(int)`,
   `LongVector.and(long)`, `mul(long)`, `or(long)`, `AbstractSpecies.loopBound(int)`, the double
   masks' `and`, `or`, `not`, `anyTrue`), called once a round, 300 times (the four memory-segment
   loads and stores of each lane, the int and long masks' `and`, `or`, `not`, `anyTrue`, the
   long and double `convertShape`), or a few times a round (`AbstractSpecies.indexInRange`, 600;
   `AbstractSpecies.length`, 606; `Int512Vector.convertShape` and `VectorMask.fromLong`, 600;
   `Long512Vector.lanewise(Unary, VectorMask)`, 900; the double `compare` and
   `Double512Mask.toLong`, 1,200). 2.1 of `VARKA-395.md`
   priced the warm-up's goal as "every wrapper past 2,000"; these were outside the wrapper block.

What the check would have rejected: a mark that is not the policy's, or a corpus that does not
reach the kernels' calls. Neither held.

## 3. The design

### 3.1 The check, and the calls it asks for

1. **`VarkaWarmupReach`** (catalyst test tree, Java because scalac cannot see
   `java.lang.classfile` types): the kernels' counted methods over the corpus of 2.6, the warm-up's
   counts from one pass of the counting copy (2.3, 2.5), and the list of counted methods under
   2,000 with the call and the shape that reach each.
2. **`VarkaWarmupReachSuite`** (JUnit 5 under `@VarkaSuite`): fails on a non-empty list; a
   planted omission (the counting copy told to skip `DoubleVector.add(double)`, the call #932
   added) is named; the mark is checked against the JVM's `Tier3InvocationThreshold` and
   `TieredOldPercentage`, so a JDK that moves either fails here first.
3. **The rounds call the 38 methods 2,400 times or more.** Each call of 2.7 moves into a block of
   `THIN_CALLS_PER_ROUND` calls a round, the way #932 moved the doubles' scalar forms: the memory
   loads and stores, the mask operations of each lane (the doubles' block gains its masks), the
   scalar forms never called, the species' `length`, `loopBound` and `indexInRange`, the
   conversions and the masked unary. The operator loops and the round count stay.

The corpus is the coverage table (every expression the engine fuses has a row, `VarkaCoverageSuite`
fails otherwise, so each new lane row's kernels join with its rows), the fuzz grammar's first draws
of both lanes, and the matrix's configurations, since the warm-up also runs in the option matrix's
JVMs.

### 3.2 What is deliberately unchanged

* The warm-up's receivers: the preferred species only, as its class doc states. A
  `lanesOverride` shard's kernels reach other classes' counters, which this check does not cover.
* What a method calls inside the Vector API: a wrapper's own call to `lanewise` is not credited
  to `lanewise`. The check counts the warm-up's direct calls only, a lower bound on HotSpot's
  count; a method it finds short is warmed by a direct call.
* The hand-written runtime helpers kernels call (`VarkaVectorSupport`, `VarkaScratch`) name no
  Vector API method.
* The rounds' count, the pacing and its budget, the hooks: row 395's.

### 3.3 Registered op counts

None: no kernel changes, and the bytes oracle does not move.

## 4. Files

* `sql/catalyst/src/test/java/.../codegen/varka/VarkaWarmupReach.java`: new, the check.
* `sql/catalyst/src/test/java/.../codegen/varka/VarkaWarmupReachSuite.java`: new, its suite.
* `sql/catalyst/src/main/java/.../codegen/varka/VarkaVectorTemplateWarmup.java`: the rounds'
  calls of 3.1 item 3 and the class doc's note of what the rounds call.
* `sql/varka/plans/m8/PLAN.md` row 397, this file, the calibration entries,
  `m8/results/VARKA-397-warmup-runs.txt`.

## 5. Tests, and what each is for

1. **Every counted method the corpus reaches has 2,000 calls.** Fails on master with the 38 of
   2.7; catches the next wrapper a lane row adds.
2. **A planted omission is named.** The copy skips one call; the check names its method with 0
   calls. Catches a counter that credits calls it did not see.
3. **The mark is the JVM's.**
4. **`VarkaVectorTemplateWarmupSuite`** unchanged, including its pin that the rounds' result is
   deterministic.
5. **The four-core end-to-end suite**, `VarkaWarmupEndToEndSuite` pinned to four cores with
   `-XX:MaxVectorSize=32`, the check row 395 ran: the rounds' heavier blocks must not cost it its
   pass.

## 6. The measurement

The warm-up's cost, from its own log line, in the four-core end-to-end suite: three JVMs on master
and three on the change, alternated, on the same build of everything else.

### 6.1 Predictions, registered before the run

1. **The suite's time**: 15 to 45 s for the three tests in a fresh JVM, the spike's 26 s of
   emission plus one counted pass; the calibration table's new-suite ratio of about 2 (VARKA-243)
   is not applied, because the spike already measured the emission.
2. **The warm-up's time on the change, four cores**: within 1.5 times master's median in the same
   session. The added calls are microseconds a round; the cost is the methods they take past the
   tier thresholds during the rounds, whose compiles the pacing then waits for.
3. **The pacing's waits on the change**: within the 2 s budget in every run, the log line's wait
   under 2,000 ms.
4. **The four-core suite on the change**: five of five in three runs of three.
5. **At 256 bits** (`-XX:MaxVectorSize=32`, the runners' width) the suite passes on the change
   and the corpus reaches the same 75 counted methods, named `Int256Vector` and the rest.

## 7. Risks

1. **The heavier rounds release the four-core suite.** More methods compile inside the rounds and
   C2's queue is longer when the wrappers cross their thresholds. Test 5 is the check, and its
   log line says what the pacing saw.
2. **A kernel's mask call counted against the wrong lane.** 2.4's rule asks for every lane the
   class uses, so it can only over-ask; the cost is a few warm-up calls on double masks.
3. **A JDK whose vector classes are organised differently.** The dispatch is read from the
   running JDK, so the check follows it; test 3 pins the mark.
4. **The counting copy's run writes the warm-up's log-line fields** (`queueBefore`, `queuePeak`,
   `waitedMillis`), as the nestmate it is. Only the log line reads them, and
   `VarkaVectorTemplateWarmupSuite` resets them before the tests that run the warm-up.

## 8. Sequencing

1. This plan, with section 2's spike.
2. The check and its suite, failing on master with the 38 of 2.7.
3. The rounds' calls; the suite green; section 6's runs; the record.

Explicitly out of this row: the warm-up at other species (3.2), transitive credit inside the
Vector API (3.2), and any change to the pacing or the round count.

## 9. Outcome

19 October 2026. Built as section 3 planned. On master the check fails on the 38 methods of 2.7,
of the 75 the corpus reaches; on the change each of the 38 gets 2,400 calls or more, and the planted
omission of `DoubleVector.add(double)` is named with 0 calls. At 256 bits (`-XX:MaxVectorSize=32`)
the corpus reaches the same 75, named `Int256Vector` and the rest, and the suite passes, before
the review's corpus changes and after them. The lesson on what HotSpot counts is in
`skills/the-jit.md`.
`VarkaVectorTemplateWarmupSuite` passes its 12 tests on the change, its deterministic-result test
included. The four-core end-to-end suite, alternated with master's rounds
(`results/VARKA-397-warmup-runs.txt`):

| arm | runs passed | warm-up | waited | C2's queue at most |
| :--- | ---: | ---: | ---: | ---: |
| master, four cores | 3 of 3 | 301 to 432 ms, median 375 | 78 to 124 ms | 6 |
| change, four cores | 3 of 3 | 326 to 484 ms, median 336 | 133 to 210 ms | 7 to 9 |

The runs were beside another agent's option-matrix run, at a load average of 5 to 9. The change's
pacing waits a little longer and meets a longer queue, from the methods its blocks now take past
the tier thresholds inside the rounds; the warm-up's time did not move beyond the arms' spread, and
stays inside the class doc's 200 to 830 ms.

What the build found that the plan did not list: the mark's second factor, `TieredOldPercentage`,
is a diagnostic flag, which `HotSpotDiagnosticMXBean` reports as absent without
`-XX:+UnlockDiagnosticVMOptions`. The mark test reads it where the JVM shows it and otherwise
takes its default, 1000; `Tier3InvocationThreshold` is a product flag and is always read.

The independent review of the diff found no correctness bug; it checked the rewrite's stack and
locals, the counter's reset, and that every call the rounds lost outside a loop is back inside a
block. It found, and this row changed: the planted test proved only that an omitted call reads 0,
so it now also holds one call to its exact count, 2,400, which a counter crediting a call twice
would miss; under a matrix configuration the plain defaults' kernels were not in the corpus, so the
defaults are emitted beside the matrix's base; the lane-count configurations are left out, since
their kernels name another species and only over-asked of the preferred one's classes; a
configuration whose every shape declined would have added nothing unseen, so it now fails the
check; and the warm-up's doc now says "in these blocks or in the operator loops", since `lanewise`
and `compare` reach the mark through the operator loops. Two corrections to sections 3 and 7:

* **3.2's "a lower bound on HotSpot's count" is not always true.** The counter counts every call a
  call site makes; HotSpot stops counting a callee once a compiled caller has inlined it, unless
  that caller runs at tier 3 (the second review's reading, checked in the JDK 25 source:
  `profile_inlined_calls()` is `profile_calls() && C1ProfileInlinedCalls`, and `profile_calls()`
  requires `CompLevel_full_profile`, `c1_Compilation.hpp` 241 to 247). So the count equals
  HotSpot's while the rounds' methods run interpreted or at tier 3. Two ways it can run ahead:
  the new `masks()` helper, 7,200 calls, passes C2's 5,000, and the species' `length`,
  `loopBound` and `indexInRange` and `VectorMask.fromLong`, which C2 may inline there, have about
  5,000 calls when it does, past the mark; and a round method (`ints`, `longs`, `doubles`,
  `conversions`, 300 calls each) compiled at tier 2 instead of tier 3. The policy chooses tier 2
  when C2's queue is over `Tier3DelayOn` times C2's threads, 10 on four cores, as the method
  crosses its first threshold, about its 200th call (`compilationPolicy.cpp` 1356 and 1357). C1
  at tier 2 inlines the final wrappers and stops counting them, at about 200 rounds of 300: about
  1,600 of the 2,400 the check reports. **The check's margin assumes tier-3 compiles of the
  rounds.** The pacing exists to hold that queue under its target of 4 at each round's start;
  the change's runs met at most 7 to 9 (master's 6) against the line of 10, so the assumption
  held with one to three entries to spare on four cores, read from the queue's peak and not from
  the rounds' own tiers.

  Kept as it is, not made robust here: a margin that holds even if counting stopped at the
  callers' first compile needs at least 10 calls a round (2,000 by round 200) for every counted
  method, which means raising `THIN_CALLS_PER_ROUND` from the 8 that rows 389 to 391 measured.
  That moves where every wrapper crosses its thresholds and so the pacing's behaviour, which only
  a new round of the four-core runs can judge, while the pacing already holds the queue under the
  tier-2 line in every run measured. If a run's log line shows the queue at the line, that is the
  trigger to raise it.
* **7.4's "resets them before the tests that run the warm-up"**: `VarkaVectorTemplateWarmupSuite`
  resets them after each test, and then runs the warm-up again.
* **What 7.4 left out**: the counted copy runs a whole pass of the rounds, the pacing included,
  outside `ensure()`'s guard. In a shared test JVM where it runs before any other Varka test it
  is the JVM's first vector code, and the real warm-up's log line, "before the JVM's first vector
  code", is then not quite true. Harmless for the suites, which do not judge that order; stated.
* **Not in the corpus, and out of scope**: the hand-written Vector API callers on the evaluator's
  batch path, `SelectionVectorOps` (`compactInts`) and `IntRangeOps` (`allWithin`). They are not
  called from a kernel, so their methods are not read; each calls the warm-up's hook from its own
  initializer.
* The catch around an emission took any `IllegalArgumentException` as a decline; it now takes
  only `VarkaEmitDeclined`, the emitter's size decline, so a structural refusal or the class-file
  library's own failure under one configuration fails the check instead of dropping its shapes.

The rest of the review's findings were the house rules (a history comment, `var` where the
right-hand side does not name the type) and parsing a kernel's bytes once; all fixed.

**After master's kernels of both lanes (20 October 2026).** Master at `0eda4cb66ce` added
kernels that hold both lanes, widening and narrowing between them, and 9 coverage rows (128 in
all). Merged, the corpus reaches the same 75 methods at 512 and at 256 bits: the new kernels'
conversions and int-species loads and stores in long-driven kernels are calls the corpus already
held, so the rounds needed no new call, and the check passes. The four-core end-to-end suite
passed five of five once on the merged tree, at a load average of 22 from other agents' JVMs; its
warm-up took 1,167 ms, 765 of them waiting for C2's queue, which peaked at 8 against the tier-2
line of 10 (`results/VARKA-397-warmup-runs.txt`). The time is the machine's load: the arms of the
table above ran at 5 to 9.

### 9.1 Predictions scored

1. **The suite's time, 15 to 45 s: held.** 28 s for the three tests alone in a test JVM, nearly
   all of it the corpus test's emission, and 41 s with sbt's start; 27 s after the review's
   corpus changes, and 15 s when another suite has warmed the compiler and the emitter first.
2. **The warm-up's time within 1.5 times master's median: held.** A median of 336 ms against
   master's 375, 0.9 times; the arms' ranges overlap.
3. **The pacing's waits within the 2 s budget: held.** 133 to 210 ms.
4. **The four-core suite, five of five in three runs of three: held.**
5. **At 256 bits, the suite passes and the corpus reaches 75 methods: held.**

