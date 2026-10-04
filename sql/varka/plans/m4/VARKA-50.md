# VARKA-50: make a bad register allocation visible

## 1. Where this came from

`m4/PLAN.md` 2.20 / task row 50. VARKA-32 spent six failed hypotheses on
a kernel that ran at either 165 or 236 M rows/s under `-XX:MaxVectorSize=16` -
stdev 0 inside a run, 42% between runs - before the cause turned out to be C2's
register allocator. The two compilations contain *identical* vector op counts;
the whole difference is spill traffic, four stack moves against seventy-four.
`SKILLS.md` carries the evidence.

The structural answer is VARKA-32's, and this task is not it. This is the other
half: **not preventing a bad allocation, but noticing one**. Today a
badly-allocated kernel costs 30-40% and nothing anywhere reports that it
happened.

VARKA-31 is the neighbouring instrument and the contrast is worth stating.
VARKA-31 reads the *instructions* once, on a developer machine, under a
disassembler - it answers "did this vectorize". This task watches *compiled
size* at runtime, in any JVM, with public API and no flags - it answers "did
this compilation come out unusually large for a shape we have seen before".
Neither subsumes the other, and neither is a throughput ratio.

## 2. The mechanism, and the one correction to 2.20

JFR's `jdk.Compilation` event carries `method`, `compileLevel`, `succeeded`,
`isOsr` and - the useful one - `codeSize`. `jdk.jfr.consumer.RecordingStream`
(public since JDK 14) consumes it in-process with no agent and no diagnostic
flags. `jdk.jfr` is a default root module, so nothing changes in the build.

**The expectation is self-calibrating**, which is what makes this worth
building. A committed table of expected sizes would have to come from somewhere
and would drift with every emitter change. Varka already keys every kernel by a
shape hash and the same shape emits byte-identical bytecode, so the comparison
is between *compilations of the same shape* rather than against any constant.
The first compilation establishes the baseline; a later one that differs
materially is the report.

### 2.1 The correction: the key is a method, not a shape

Section 2.20 says "per shape hash, the first non-OSR `codeSize` seen". That is
not enough, and following it literally would make the detector fire constantly
on healthy JVMs. A generated kernel is not one method - VARKA-24 deliberately
split it into siblings, so a single shape emits `run`, `runDense`, `runMasked`,
`loopDense<g>`, `loopMasked<g>`, `epilogueDense` and `epilogueMasked`, whose
compiled sizes differ from each other by an order of magnitude. Keyed on the
shape alone, the second method compiled for a shape would be compared against
the first and reported as a divergence.

The key is therefore **(shape hash, method name)**. Both come out of the event's
`method` field: the class is
`org.apache.spark.sql.varka.execution.VarkaFusedProjection_<hash>`
(`VarkaShapeCacheImpl.classNameFor`) and the method name is beside it. Two
compilations sharing that key are compiling the same bytecode, so any size
difference between them is C2's doing and nobody else's.

### 2.2 What is filtered out, and why

* **Everything not a Varka kernel.** The filter is the generated class-name
  prefix. `jdk.Compilation` fires for every method the JVM compiles; without a
  filter this would be a firehose.
* **OSR compilations** (`isOsr`). They are not what the steady-state path runs,
  and VARKA-32 found them identical across both modes anyway (7303 against 7304
  instructions) - so they carry no signal and would only add noise.
* **Failed compilations** (`succeeded` false). A bailout has no meaningful size.
* **Tier changes are kept, and are part of the key's meaning.** A method
  compiled at tier 3 and later at tier 4 legitimately differs in size, so
  `compileLevel` joins the key rather than being ignored: the comparison is
  between compilations at the *same* level of the *same* method of the *same*
  shape.

## 3. The threshold, measured rather than guessed

The plan does not get to pick a number by feel. Two facts bound it:

* the fast and slow allocations of VARKA-32's kernel differ by roughly **2x** in
  instruction count (1581 against 3000), and `codeSize` tracks that;
* healthy recompilations of the same method at the same level should differ by
  **nothing at all**, since the bytecode is identical.

If the second holds in practice, any threshold between "0%" and "2x" works and
the choice hardly matters. That is the thing to check rather than assume, so
commit 3 measures it: emit one shape, force repeated compilation of it, and
record the observed spread of `codeSize` for the same (shape, method, level) in
a JVM doing nothing pathological. The threshold ships as a constant with that
measurement quoted beside it in section 9, and the default is deliberately
generous - a diagnostic that cries wolf gets turned off.

**Measured (commit 3).** One `year` kernel, emitted under a production-shaped
class name and run hot, in three separate JVMs:

| key | run 1 | run 2 | run 3 |
|---|---|---|---|
| `run#3` / `run#4` | 728 / 2760 | 728 / 2760 | 728 / 2760 |
| `runDense#3` / `#4` | 30160 / 2456 | 30160 / 2456 | 30160 / 2456 |
| `loopDense0#3` / `#4` | 68936 / 3656 | 68936 / 3656 | 68936 / 3656 |
| `epilogueDense#3` / `#4` | 165728 / 1888 | 165728 / 1888 | 165728 / 1888 |

**Byte-identical, everywhere.** The healthy spread is zero, so
`DIVERGENCE_RATIO` sits four times above anything observed and four times below
the 2x failure it hunts. It stays at 0.25 rather than tightening, because three
runs on one host is not proof that the spread is universally zero - a different
JVM version, a different host, or a deoptimised recompilation could all differ
legitimately - and the failure being hunted is nowhere near the boundary.

The table also settles section 2.1's argument far more sharply than the probe
that motivated it: **tier 3 is 20 to 90 times larger than tier 4 for the same
method** (`epilogueDense` 165728 against 1888 - profiled C1 code against
optimised C2). Without the compile level in the key, the first tier-4
compilation after a tier-3 one would be reported as a 98% divergence, on every
method, in every JVM.

## 4. Configuration

One new static conf, following the three Varka keys already in place:

`spark.sql.codegen.varka.compilationWatch.enabled`, internal, default `false`,
`.version("5.0.0")`, `ConfigBindingPolicy.NOT_APPLICABLE` (JVM-wide, cannot
change what a view body resolves to). Static rather than session-level because a
`RecordingStream` is a JVM-wide, thread-owning subscription, not something a
query can turn on and off.

It is read exactly the way `VarkaShapeCache` reads its own capacity - `SparkEnv`
conf first, `SQLConf.get` as the fallback for a catalyst unit test with no
`SparkEnv` - because the same static-conf boundary applies and that file already
documents it.

**Off means off.** With the flag false, no stream is opened, no thread is
started, and nothing is registered. That is asserted, not assumed (section 6).

## 5. What it reports

Three surfaces, in increasing order of how much a reader has to already care:

1. **A `LongAdder` counter** on the watch, in the shape of
   `VarkaShapeCacheImpl`'s existing `hits`/`misses`, so it can be surfaced
   through the evaluator's metrics the way cache hits already are.
2. **A warning log** naming the shape, the method, the baseline size and the
   new one. One per (shape, method, level), not per occurrence - a recompiling
   loop must not be able to flood the log.
3. **A JFR event**, `VarkaCompilationDivergence`, in the `Varka` category
   beside the three that exist (`VarkaEmissionEvent`, `VarkaCacheLookupEvent`,
   `VarkaFallbackEvent`), so a recording already capturing Varka's events picks
   this up with no extra wiring.

## 6. Files

| file | what |
|---|---|
| `VarkaCompilationWatch.java` | new; the stream, the key, the baseline map, the threshold, the three reporting surfaces, and `close()` |
| `VarkaCompilationDivergenceEvent.java` | new; the JFR event, modelled on `VarkaEmissionEvent` |
| `StaticSQLConf.scala` | the one new key |
| `VarkaShapeCache.scala` | reads the key and starts the watch once, JVM-wide, on first use |
| `VarkaCompilationWatchSuite.scala` | new; section 7 |
| `sql/varka/plans/m4/VARKA-50.md` | this file |
| `m4/PLAN.md` | row 50, and 2.20's correction note |
| `SKILLS.md` | what the measurement in section 3 finds |

Deliberately **not** touched: `VarkaLoopEmitter`, `VarkaVectorIR`, the compiler,
or any emitted byte. This task observes; it changes nothing about what Varka
produces, so no committed benchmark number can move and no pinned fixture can.

## 7. Tests

1. **Off by default costs nothing**: with the flag unset, no watch object is
   created and no JFR recording is open. Asserted through the watch's own
   `isRunning`, not by reading a log.
2. **The parser**, tested directly and without JFR: given the event fields as
   plain arguments, the (shape, method, level) key is extracted from a real
   generated class name, and a non-Varka method name is rejected. This is the
   part most likely to break silently when a naming scheme changes, and it is
   pure logic, so it gets a unit test rather than an integration one.
3. **Divergence detection**, driven through the same seam: feed a baseline size
   then a size over threshold, assert the counter moved once; feed a size within
   threshold, assert it did not; feed a *different method* of the same shape,
   assert it establishes its own baseline rather than reporting - the failure
   mode section 2.1 exists to prevent.
4. **JFR absent degrades silently**: opening the stream is allowed to throw and
   the watch must stay quiet and disabled. Simulated by a construction path that
   fails, not by disabling JFR in the test JVM.
5. **End to end, opt-in** (`-Dvarka.jfr=true`, the sweep pattern): a real
   `RecordingStream` sees at least one Varka kernel compilation. This one is
   opt-in because it depends on C2 actually compiling something within the
   test's patience, which is exactly the kind of thing that goes flaky on a
   loaded CI runner.

## 8. Risks

1. **A detector that fires constantly.** Section 2.1's key is the mitigation and
   test 3 pins it. This is the risk that would get the feature deleted.
2. **A firehose subscription.** `jdk.Compilation` fires for every compiled
   method in the JVM. The filter must be applied inside the event handler and
   must be cheap - a string prefix test, nothing more.
3. **Cost when disabled.** Guarded by never constructing the watch at all, and
   asserted by test 1.
4. **JFR unavailable.** Some deployments disable it. Must degrade to silence,
   never to a startup failure (test 4).
5. **A shape compiled once.** The common case for a short query: no comparison
   is possible and nothing is reported. That is a real limit on the feature's
   reach, not a defect, and it belongs in the docs sentence rather than being
   engineered around.
6. **The thread.** A `RecordingStream` owns one. It is started once per JVM and
   closed on shutdown; it must not be started per session or per query.

## 9. Predictions, registered before the work

1. Section 3's measurement finds **zero** spread for the same (shape, method,
   level) on a healthy JVM, making the threshold choice uncritical.
2. The end-to-end test sees kernel compilations easily at tier 3 but may need
   patience for tier 4, which is why it is opt-in.
3. Nothing in this task moves a committed number or a pinned fixture, since it
   emits no bytecode.
4. The most likely defect found in review is the one in 2.1 - keying too
   coarsely - because it is the thing the milestone text says to do.

## 10. Commit 3's outcome: when this can fire at all

The measurement answered the threshold question and raised a better one.

**Every key was compiled exactly once per JVM.** Across the three runs above,
each of the eight (shape, method, tier) keys appeared once and never again. A
baseline that only ever sees one compilation has nothing to compare, so on that
workload the watch could not have reported anything however badly C2 had
allocated.

That matters because of what VARKA-32 actually measured: the bimodality was
**between** JVM runs - "stdev 0 inside a run, 42% between runs". A per-JVM
baseline cannot see a difference that only appears across JVMs. Section 2.20's
risk list says "a shape whose kernel is only ever compiled once in a JVM
produces no comparison at all", which is true but reads as an edge case. It is
the normal case.

**What does produce two compilations of one key is re-emission**, and this is
the finding worth keeping. The same shape emitted into a fresh class of the
*same name* under a different loader compiles again from scratch, and since the
class name is what JFR reports, both compilations land on the same key. Varka
does exactly that in two situations it already supports: `maxEntries = 0`, the
pre-cache per-task class lifecycle, and any eviction under a full cache. It is
also precisely the "resample" the debt register parks - which means the watch is
the instrument that would tell anyone building that whether a resample had
helped.

Measured directly: emitting one shape twice in a single JVM and running both
hot produced **16 compilations across 8 keys** - each key compiled twice, as
predicted - with **zero divergences**, the two compilations being byte-identical.
So the detector has something to compare exactly when re-emission happens, and
C2 allocated identically on this shape both times.

The honest summary of the feature's reach, which belongs in its documentation
rather than being discovered by a user: it reports when a *re-emitted* kernel
compiles differently than the same kernel did earlier in the same JVM. It does
not, and cannot, report that this JVM's allocation is worse than another JVM's.

### Predictions, scored

1. **Held.** Zero spread, byte-identical across three JVMs and across two
   emissions within one JVM. The threshold choice is uncritical, and 0.25 is
   kept for the reason section 3 now gives.
2. **Held.** Both tiers were seen well inside the 30-second budget, tier 3
   first. Opt-in remains right: it still waits on a compiler.
3. **Held.** No emitted byte, no pinned fixture and no committed benchmark
   number moves; the 130-test Varka suite is unchanged.
4. **Missed, in an interesting direction.** The predicted defect was keying too
   coarsely, and that was indeed the plan's first correction - but it was caught
   at planning time, before any code. The defect that survived into code was the
   opposite kind: `compilationWatch` was a `lazy val` touched only by the
   reporting calls, so in production nothing would ever have started the watch.
   It would have run only for a caller already asking what it had seen.

## 11. Sequencing

Three commits, each green on its own:

1. **This plan**, with section 2.1's correction to the milestone's design.
2. **The watch**: `VarkaCompilationWatch`, the JFR event, the static conf, the
   hook on the emission path, and the tests that need no compiler running.
3. **The measurement**: section 3's table, the two opt-in end-to-end cases, the
   threshold confirmed, and sections 10 and 11.
