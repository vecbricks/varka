# VARKA-391: The one-line wrappers compile at tier 3, inside the warm-up's rounds

## 1. Where this came from

`VARKA-389.md` 9.5: the end-to-end warm-up suite released two of its five warm-ups on four-core
256-bit hosts, on master as on the fix, from one JVM to the next. The tiered policy's own trace
(`-XX:+PrintTieredEvents`) of two runs on the laptop's four cores named the coin. The one-line
wrappers the kernels call, `IntVector.max(Vector)` and `min(Vector)` among them, are called once
a round by the template warm-up, 300 calls in all; C1's queue scales the policy's first
threshold (`k` up to 6.6 in the trace, against 200 calls), so their first compile fired 200 to
300 ms after the rounds, in the kernel warm-up's traffic, and C2's queue at that instant chose
the tier: 7 entries and the wrapper went to tier 3, was profiled to 23,552 calls and every
kernel compiled clean; 17 and it went to tier 2, its profile froze at 641 calls, and the kernel
that inlined it kept a virtual call and boxed. Row 390 made CI retry the suite in a fresh JVM
meanwhile; the owner asked for the fix.

## 2. The admission check, done

**Why tier 2 is final for these wrappers.** The policy means a tier-2 method to return to tier 3
once C2's queue is short, unless C1 reported nothing to profile in it, in which case it goes
straight to tier 4 (`transition_from_limited_profile`, `would_profile`). C1 at tier 2 marks a
call as a profiling point only when its target is final (`GraphBuilder::invoke`,
`need_null_check`: call profiling belongs to tier 3). `max(Vector)`, `min(Vector)`,
`add(Vector)` and their kind each hold one call, to `lanewise(Binary, Vector)`, which is
abstract in `IntVector` and `LongVector`; so their tier-2 compile carries no profiling point,
and the trace shows them going from tier 2 to tier 4 at their first tier-2 report. `min(int)`
calls the final `lanewise(Binary, int)`, got its mark, and went to tier 3 in the same run. A
wrapper that lands at tier 2 therefore never gets a profile, and C2 trusts a profile only past
`ProfileMaturityPercentage` (20%) of the tier-4 count, 1,000 calls.

**What decides the tier.** The queue at the crossing. The first threshold is 200 calls scaled
by C1's load; the wrapper crosses it when its calls pass that number, and `transition_from_none`
reads C2's queue at that instant: over `Tier3DelayOn` (5) times C2's threads, ten on four cores,
and it is tier 2. The warm-up can hold C2's queue, through the `Compiler.queue` diagnostic
command, but only during its own rounds; one call a round left the wrappers at 300 calls when
the rounds ended, under a threshold the load had scaled to 400 to 1,320.

**What pacing alone did (VARKA-389.md 9.5).** Paced every round to the policy's limit of ten,
the suite passed nine tests of ten in two runs, the coin; paced to the policy's resume line of
four (`Tier3DelayOff` times the threads), with the queue at most 7 through every round, it
released two of five in three runs of three, because the crossing came after the rounds.

**The policy's old mark.** Past `TieredOldPercentage` (1000%) of the first threshold, 2,000
calls, `is_old` holds: `call_event` passes `disable_feedback`, so the queue no longer decides
the tier, and `select_task` no longer drops the method's queued task as stale.

## 3. The design

Two changes to `VarkaVectorTemplateWarmup`, both in the rounds it already runs before any vector
code of the engine (VARKA-389):

1. **Eight calls a round for every one-line wrapper** (`THIN_CALLS_PER_ROUND`): the block of
   `add`, `sub`, `mul`, `min`, `max`, `and`, `or`, `div`, `blend`, `broadcast` and `zero` calls
   in each of the int, long and double rounds runs eight times, 2,400 calls over the 300 rounds.
   Each wrapper then crosses its first threshold inside the rounds for any load factor up to
   twelve, and passes the old mark before the rounds end.
2. **Pacing on C2's queue to the resume line**: before the rounds, after every round and once at
   the end, the warm-up waits until C2's queue is within `Tier3DelayOff` times C2's threads,
   both read from the JVM's options, 20 seconds in all at most; where the diagnostic command is
   unavailable it does not wait. The hold is what makes the crossing inside the rounds land at
   tier 3; the first change is what puts the crossing there.

The warm-up logs one line per JVM with what the pacing saw: C2's queue before the rounds, its
peak during them, the policy's limit, the target and the milliseconds waited.

## 4. Files

- `VarkaVectorTemplateWarmup.java`: `THIN_CALLS_PER_ROUND`, the repeat around the wrapper
  blocks, the pacing (`PACE_QUEUE_TARGET`, `PROFILING_TIER_QUEUE_LIMIT`, `awaitC2Queue`,
  `c2QueueLength`), the log line, the class doc's paragraph.
- `VarkaVectorTemplateWarmupSuite.scala`: the pacing's parser and bounds, and the invariant that
  the wrappers pass 2,000 calls inside the rounds.
- `sql/varka/plans/m7/PLAN.md` row 391, `sql/varka/skills/the-jit.md`.

## 5. Tests, and what each is for

- The unit suite's pacing test: `c2QueueLength` reads the JVM's `Compiler.queue` text (drained,
  C1 busy only, C2 busy, unknown format), the target is at least one and at most the policy's
  limit, a wait returns within its budget, and `THIN_CALLS_PER_ROUND * ROUNDS >= 2000`.
- The end-to-end warm-up suite on four cores at 256-bit lanes, the failing host, with the
  policy's trace: the wrappers' first compile must be tier 3 and inside the rounds, and every
  test must pass, run after run.
- The suite at sixteen cores and the forked wide-shape check of VARKA-389, so the heavier
  rounds change nothing where the warm-up already worked.

## 6. The measurement

The rounds grow by eight wrapper blocks a round; on the laptop's four cores the warm-up took
about the same 200 to 450 ms as before, the pacing's waits included. The suite's pass rate on
four cores is the measurement that matters, and the trace's tier for each wrapper is its
explanation.

## 7. Risks

- **A host where C2's queue never drains to the target.** The hold has a budget of 20 seconds in
  all; past it the rounds run unpaced, and the log line says so.
- **A new wrapper the kernels call that the warm-up does not.** It would be back on the coin.
  The warm-up's block mirrors the emitter's calls; a wrapper added to the emitter is added here.

## 8. Sequencing

One pull request on top of #921, since the warm-up's placement is that row's. Row 390's retry
list keeps the suite until this row has run on the pool's 4-core AVX2 runners for a while
without a retry annotation; then the entry goes.

## 9. Outcome

18 October 2026. On the laptop pinned to four cores with `-XX:MaxVectorSize=32`, the end-to-end
warm-up suite passed all five tests in three runs of three with the policy's trace on (G15a to
c), where the same suite had released two of five in three runs of three an hour earlier with
the pacing alone and in two of two runs unpaced. In every run the wrappers' first compile was
tier 3, inside the rounds, with C2's queue at 0 to 2 at the instant:

| run | `IntVector.max(Vector)` | `IntVector.min(Vector)` | `IntVector.add(Vector)` | `LongVector.max(Vector)` |
| :--- | :--- | :--- | :--- | :--- |
| G15a | tier 3, C2 queue 0, `k` 3.4 | tier 3, 0, 3.2 | tier 3, 0, 1.2 | tier 3, 0, 5.0 |
| G15b | tier 3, 0, 2.6 | tier 3, 0, 2.4 | tier 3, 0, 1.0 | tier 3, 0, 5.0 |
| G15c | tier 3, 0, 1.8 | tier 3, 0, 1.6 | tier 3, 2, 1.0 | tier 3, 0, 4.0 |

The `k` column is C1's load factor at the crossing, read from the trace: the threshold stood at
200 to 1,000 calls in these runs, and the wrappers crossed it inside the rounds in all of them.
The unit suite passes, seven tests. Three more four-core runs, the suite at sixteen cores and
the forked wide-shape check are in section 9.1 as they land.

### 9.1 Confirmation runs

Three more four-core runs without the trace (G16d to f): five of five tests each, six runs of
six on the day. The suite at sixteen cores (G17): five of five. The forked wide-shape check of
VARKA-389, the order the benchmark runs in, on the same build: passed, so the heavier rounds
deoptimise nothing where the warm-up already worked. The pull request's own "Varka suites: sql"
job is the first run on the pool's 4-core AVX2 runners.
