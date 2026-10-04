# VARKA-22: operational debuggability, and the charter answer

Milestone 3 section 2.4 (`m3/PLAN.md`), the catalogue's item 14
remainder plus item 10: fallback-cause metrics on both exec nodes speaking
the task-16 decline vocabulary, JFR events for emission, cache hit/miss and
fallback, and the whole-stage charter question answered in writing in
`VISION.md`. The gate: a fallen-back production query is diagnosable from
metrics alone.

## 1. The fallback map this task instruments

Before this task, the paths where the fast path degrades and what they
left behind:

| Path | Granularity | Before VARKA-22 |
|---|---|---|
| Per-entry declines (task-16 taxonomy) | static per plan | verbose EXPLAIN only |
| Emission failure (`fusedRunner` = None) | per task | one warning log |
| `canRun` false (input not Arrow-backed) | per batch | nothing at all |
| Kernel failure (the ghost fallback) | per batch | warning log |
| Class-cache hit/miss | per task | metrics + counters (VARKA-18) |

The `canRun`-false row is the sharp one: a production query whose cache
serializer was misconfigured ran entirely per-row with no signal beyond a
missing `numVarkaBatches` count, and no stated cause anywhere.

## 2. Decisions, and who made them

* **The whole-stage charter: kept.** The project owner decided - against
  the planning assistant's recommendation - that whole-stage code
  generation remains in Varka's charter as an eventual goal rather than
  being written out of it. The recorded answer stays honest about the
  present: nothing through milestone 6 builds or plans it, today's engine
  is the columnar fast path beside whole-stage codegen, the milestone-5
  census prices what full ownership entails, and a future whole-stage
  generator starts from the vector IR and the loop emitter (item 9's
  closed record), with the 64 KB method limit entering scope only then.
* **Metrics stay bounded: three runtime causes plus the residual-entry
  count** per node, rather than a metric per taxonomy term. The per-reason
  detail is static per plan and already in verbose EXPLAIN; the JFR
  fallback event carries it at runtime. A taxonomy-term metric set would
  grow with every compiler task (VARKA-20 alone added four reasons).
* **JFR event classes are Java, in catalyst** - the Java-first rule lands
  them exactly where the house style already lives, and the engine module
  cannot host them: it is test-scope in catalyst and absent at runtime in
  precisely the fallback scenarios being instrumented. `jdk.jfr` is a
  default root module on JDK 25 (verified with
  `java --show-module-resolution`), so no build, pom or module-flag change
  is needed anywhere - unlike `jdk.incubator.vector`, which is threaded
  through five places.

## 3. Design

* **Metrics.** Four new `SQLMetric`s on both exec nodes -
  `numFallbackBatchesNonArrow`, `numFallbackBatchesKernel`,
  `numEmissionFailures`, `numResidualEntries` - alongside the existing
  five. The residual count is added once driver-side in `doExecute*` (a
  per-task add would multiply the static count by task count in the UI).
  The evaluator-facing metrics move into one `VarkaExecMetrics`
  bundle, replacing the two `Option[SQLMetric]` constructor parameters
  VARKA-18 threaded - the same pattern, made scalable before it grew to
  five parameters.
* **JFR.** Three event classes under `@Category("Varka")`:
  `VarkaEmissionEvent` (timed over emit + define; shape hash, class name,
  IR sizes, byte count), `VarkaCacheLookupEvent` (shape hash, hit,
  execution identity - the line VARKA-18 left the counters on for exactly
  this), `VarkaFallbackEvent` (cause, kernel identity, exception class).
  Events populate only when `isEnabled()`; every emission site is on a
  fallback or per-task path, never in the fused loop. No overhead claim is
  written anywhere: none was measured, and none of the committed numbers
  moves (nothing here touches a hot loop).
* **The charter text.** `VISION.md` gains a status note under Principle 2
  (whose "within the same method" describes the charter's end state, not
  the shipped engine) and a new numbered section stating the kept charter;
  the stale 8.2 loader-lifecycle line gets the task-18 correction in the
  same pass. `m3/PLAN.md` section 7 item 3 is marked settled.

## 4. Outcome

Status: **DONE.** What shipped, against section 3:

* Four cause-keyed metrics on both exec nodes, with the evaluator-facing
  set bundled into `VarkaExecMetrics` (the task-18 two-option threading
  would have grown to five parameters; the bundle updated six suite call
  sites once and ends the growth). The residual count is added driver-side
  exactly once, pinned by the mixed-projection test.
* Three JFR event classes in catalyst's Java package, wired into the shape
  cache (emission timed over emit plus define; lookups with the truncated
  execution identity) and the fallback sites. The suites carry the repo's
  first JFR tests: `jdk.jfr.Recording` + `RecordingFile`, filtered by
  shape hash because the cache is a JVM-wide singleton shared across
  suites.
* The charter answer in `VISION.md` section 13, plus status notes fixing
  Principle 2's un-annotated end-state claim and 8.2's twice-stale loader
  lifecycle; `m3/PLAN.md` section 7 item 3 marked settled.

One deviation found and fixed during implementation, not in the plan: an
emission failure makes every subsequent batch fail `canRun`, so the
per-batch counter would have mislabeled those batches as "input not
Arrow-backed". The exec nodes now carve that case out (the evaluator
exposes `emissionFailed`), counted once per task under its own cause -
pinned by the emission-failure test, which drives the path through a set
emitter hook and asserts the JFR event's cause field.

No committed number moves: every addition is on a fallback path, per-task,
or driver-side, and the fused loop is untouched - so no benchmark was run,
and no overhead claim is written anywhere (none was measured).

## 5. Explicitly out of VARKA-22

The field differential mode (milestone 4, with the int64 lanes), the
loop-method-grouping debug attribute (catalogue item 14's remainder, still
waiting on wide kernels), any emitter or kernel change, VARKA-21's filters,
and building any part of the whole-stage generator the charter retains.
