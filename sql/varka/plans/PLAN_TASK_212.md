# Task 212: A new kernel reaches the JIT on its first query

## 1. Where this came from

Task 195 measured what a query costs the first time its shape is seen
(`PLAN_TASK_195.md` 5.2), and the answer was that Varka loses: over a hundred
thousand cached rows its first run is slower than vanilla's at every rung of
the ladder, and its second run is no better. The JVM said why. A kernel's loop
method is called once per batch - ten calls of about 625 iterations for a
hundred thousand rows at the cache's default batch of 10,000 - and the first
compiler tier wants 200 calls, or 2,000 calls and iterations together with at
least 100 calls, or 60,000 iterations inside one call. The kernel meets none
of them, `-XX:+PrintCompilation` shows it never compiled, and interpreted
Vector API code is a library call per operation. Vanilla escapes because its
generated loop walks every row in one call and the JIT compiles it partway
through that call.

The owner asked, on 26 September 2026, for a task in this milestone. The
reason to do it now rather than in `SCOPE_MILESTONE_7.md`: the milestone's
second post states the first-query cost as one of its bounds (`PLAN_TASK_181.md`
2, bound 4), and a bound that says "slower until the JIT notices" reads very
differently from one that says "slower for the first few batches".

## 2. The admission check

Before any design is built: is the JIT's threshold the whole cause? The check
reruns the fixed cold-start benchmark (task 195's, from #429) with the
thresholds scaled down for the whole JVM, `-XX:CompileThresholdScaling=0.05`
and `0.01`, on the quiet laptop pinned as the committed run was. It is not a
fix - the flag scales every method in the executor - but it bounds what any
fix that makes the kernel compile sooner can win.

Predictions, registered before the run:

1. **At 0.05 the kernel compiles within the first query**: the first tier's
   call threshold becomes 10, which ten batches reach, and Varka's first run
   falls by at least half at the wide rungs.
2. **At 0.01 Varka's first run is faster than vanilla's past the cliff**, at
   54 entries and up, since the kernel reaches C2 within the query and
   vanilla's consume method is still never compiled.
3. **Below the cliff, at 16 entries, Varka's first run stays within a factor
   of two of vanilla's either way**: planning and emission are a fixed cost of
   tens of milliseconds, and at a hundred thousand rows neither engine's loop
   dominates.

The check would reject the task if the scaled runs barely move: that would
mean the cost is elsewhere - the emission, the class definition, Arrow's
allocation - and making the kernel compile sooner would buy nothing.

## 3. The design

Four ways to get a new kernel compiled early, of which the task builds two and
measures them against each other, as this project prefers to settle a design
question (`sql/varka/AGENTS.md`, and the owner's standing preference).

### 3.1 A: one call per partition, not per batch

The evaluator hands the kernel one batch at a time today
(`VarkaKernelEvaluator.computeFused` calls `invokeFused` per batch). Variant A
emits an outer loop over the batches of a partition inside the kernel, so one
call runs every batch and the loop's back-edge counter reaches the
on-stack-replacement threshold within the first query, as vanilla's does. The
cost: the kernel must take a sequence of batches rather than one, the
evaluator's contract changes, and code compiled on stack can be weaker than a
normal compile. Behind a `VarkaEmitOptions` switch, the per-batch form kept as
the reference variant.

### 3.2 B: warm the kernel before it is needed

Variant B runs a newly emitted kernel on a synthetic batch, off the query's
critical path, often enough to cross the thresholds, while the query's first
batches go to Spark's row path until a counter says the kernel has been
invoked enough. It keeps the evaluator's contract. The cost: CPU per new shape
spent on warm-up, and a heuristic for when to switch.

### 3.3 Not built: lower thresholds, and a size guard

Scaling the thresholds for emitted classes only
(`-XX:CompileCommand=CompileThresholdScaling,...`) needs a JVM option on every
executor, so it is a deployment note, not a fix; the admission check measures
what it would give. Declining Varka for inputs the statistics call small
guards against the loss without curing it, and is kept for a later milestone
if A and B both fail.

### 3.4 What is deliberately unchanged

The shape cache (a warm class is already reused across literals), the
emitter's byte budget and grouping, and every benchmark's steady state.

## 4. Files

| file | what |
|---|---|
| `sql/varka/plans/PLAN_TASK_212.md` | this plan |
| `sql/core/benchmarks/VarkaColdStartBenchmark-jdk25-threshold-*-results.txt` | the admission check's runs |
| to be named after the admission check | variants A and B |

## 5. Tests, and what each is for

To be written with the variants: a differential test per variant against the
row engine over the ladder's shapes, and a compile-log test that asserts the
kernel's loop method is compiled within one query of a hundred thousand rows.

## 6. The measurement

The cold-start benchmark, with A and B each as an arm beside the per-batch
kernel, at both widths on the quiet laptop, and then on a runner for the
number the post quotes.

## 7. Risks

1. The admission check shows the thresholds are not the cause; section 2 says
   what that would mean.
2. Variant A's on-stack compile is too weak to pay; the compile-log test and
   the steady-state rungs of the ladder would show it.
3. Variant B's warm-up costs more CPU than it saves on short queries; the
   benchmark's first-run case at 16 entries would show it.

## 8. Sequencing

The plan and the admission check first, in this pull request; the variants in
the next one if the check admits the task.

## 9. Outcome

### 9.1 The admission check, 26 September 2026

Both scaled runs on the quiet laptop, pinned as the committed run was
(`VarkaColdStartBenchmark-jdk25-threshold-0.05-results.txt` and
`-threshold-0.01-results.txt`). Varka's first run, best of five, in
milliseconds for a hundred thousand rows, against the unscaled run of #429:

| Entries | default | scaling 0.05 | scaling 0.01 |
| --: | --: | --: | --: |
| 16 | 239 | 256 | 281 |
| 54 | 694 | 755 | 900 |
| 100 | 1260 | 1372 | 1492 |

1. **Refuted.** At 0.05 Varka's first run does not fall; it is a few percent
   slower at every rung.
2. **Refuted.** At 0.01 Varka's first run is slower still, 900 against
   vanilla's 495 at 54 entries.
3. **Held.** At 16 entries the two first runs stay within a factor of two.

So the threshold is not the whole cause, and section 2's rejection clause
applies to the task as designed. What the cause is instead, the JVM says:

* **The time is in the kernel, interpreted.** A Java Flight Recorder run at the
  default thresholds puts 69% of the executor threads' samples on an
  interpreted top frame; on the Varka arm those are the kernel's
  `loopMasked0` to `loopMasked13` and, under them, the Vector API's library
  code - `lanewiseTemplate`, and `allocateInstance` for the boxes each
  interpreted operation makes. The masked loops run because the ladder's date
  column has nulls.
* **Compiling the kernel costs more than the query.** Recorded at scaling 0.01
  (`VarkaColdStartBenchmark-jdk25-threshold-0.01-compiles-results.txt`), the
  kernels' loop and epilogue methods do compile, 1018 times over the run: the
  first tier in about a tenth of a second each, and C2, the only tier whose
  code runs the Vector API as vectors, in 433.4 ms median and 902.3 ms at worst
  per method. A kernel at 54 entries has fourteen loop methods, several
  seconds of C2 time against a query of under one, so the query ends before
  its kernel is compiled whatever the trigger, and at 0.01 the extra
  compilation competes for the same cores - which is why that run is slowest.

### 9.2 What it does to the design, proposed and not yet decided

* **Variant A (one call per partition) cannot pay**: an on-stack compile costs
  C2 the same time, so the first query would still end first.
* **Variant B survives, reshaped**: a new shape runs its first batches on
  Spark's row path while C2 compiles the kernel in the background, driven by
  the warm-up of 3.2; the first query then costs about what vanilla's does,
  and every later query of the shape has the compiled kernel.
* **A new lever, not in section 3**: C2's time per kernel. The masked loops
  are the obvious first target - a batch with nulls runs a masked method per
  group - and the emitter's grouping decides how many methods there are. A
  kernel that C2 compiles in a tenth of the time moves the break-even query
  from seconds to hundreds of milliseconds.

The owner decides whether the task continues as B plus the compile-time lever,
before any of it is built.
