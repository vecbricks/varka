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
| to be named after the admission check | variants A and B; *26 September 2026: B was built as `VarkaKernelWarmth`, `VarkaKernelWarmup` and `VarkaKernelCompileDirective` (10.1, 10.2), A was not (9.2)* |

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

## 10. The design as built, 26 September 2026

The owner asked on 26 September 2026 for the task to be done and a solution
found, which settles 9.2: the task continues as the reshaped variant B. What
was built, what the JVM said on the way, and what the committed run is
predicted to show.

### 10.1 The mechanism

* **A cold shape's batches take the row path.** Each cached shape carries its
  warm state (`VarkaKernelWarmth`: cold, warming, compiled, released), shared
  by every task that runs the shape. `VarkaEvaluatorBase.serveBatch` sends a
  batch the kernel could serve to the node's own row path while the shape is
  not ready, and counts it in `numWarmupBatches`, apart from the fallbacks:
  nothing failed, the row path is simply the faster of the two until C2 has
  the kernel.
* **The first task to meet a cold shape queues its warm-up.**
  `VarkaKernelWarmup` is one daemon thread per JVM. The claiming task copies
  its batch's kernel inputs - after the evaluator has filled them, derived
  inputs included - tiled to 1024 rows, and the thread runs a fresh instance
  of the kernel on that copy. Each call runs 32 rows plus the batch's length
  modulo 32, which keeps the batch's remainder past its last whole lane group
  at every lane count that divides 32, so the epilogues see what the real
  batches give them. Short calls advance the invocation counters HotSpot's
  thresholds read hundreds of times faster per row than 625-iteration calls
  on real batches do.
* **The verdict is the JVM's, by allocation.** A kernel that is not compiled
  boxes a vector per operation; a compiled one allocates only the memory
  segments its driver makes per column. A probe block of sixteen calls is
  clean when it allocates no more than the species-pollution check's
  allowance plus 256 bytes per column per call, and at most a quarter of the
  first block; two clean blocks in a row are the verdict. A warm-up without
  one after sixty seconds, a full queue (sixteen), a shape that leaves the
  cache and a failing kernel all release the shape, whose tasks then run the
  kernel as they would have without the warm-up.
* **Configuration.** `spark.sql.codegen.varka.warmup.enabled`, on by default.
  The test sessions and every benchmark session except the cold-start
  benchmark's warm-up arm pin it off, because they check that the kernel
  served a shape's first batch.

### 10.2 What the JVM said on the way

**A wide kernel's compiled calls allocate.** The first verdict used the
species-pollution check's allowance alone, 4096 bytes plus one a row. On a
54-entry kernel the warm-up's last probes allocated about 7 KB a call - three
orders of magnitude under the first probe's rate, so compiled - and never met
it: the driver makes a memory segment per column it reads or writes, and on a
wide kernel C2 leaves some of the calls they are passed to out of line, so
they escape. Hence the per-column term.

**Then a stall, three runs in three: a kernel stranded at tier 2.** With the
allowance fixed, a 54-entry warm-up that followed a 16-entry one never reached
its verdict in sixty seconds, its probes still allocating at a boxing rate.
`-XX:+PrintTieredEvents` showed why. The first C1 request for four of the
fourteen loop methods and for every epilogue came while C2's queue held 45
tasks - the concurrent queries' own compiles - past `Tier3DelayOn` times the
compiler count, so HotSpot asked C1 for tier 2, limited profiling, instead of
tier 3, and C1 compiled it. Its later tier-3 request failed with "out of
virtual registers in LIR generator (retry at different tier)", as every
tier-3 compile of these methods does, which marks the method not
C1-compilable. From tier 2 the policy climbs only to tier 3, and tier-2 code
does not update the profile a direct climb to C2 would read, so the method
stayed on C1 code, boxing every vector operation, for the life of its class;
a dump of the compile queues twenty seconds in showed both empty and nothing
compiling. The methods whose first request found the queue short went
interpreter to C2 and compiled. The trap is not the warm-up's: any kernel on
the per-batch path whose methods cross their first threshold while C2 is
busy meets it the same way.

**The fix keeps C1 off the kernel classes.** `VarkaKernelCompileDirective`
adds one compiler directive per JVM, before the first kernel class is
defined: `c1: { Exclude: true }` for the shape cache's class names, through
the DiagnosticCommand MBean's `compilerDirectivesAdd`, the in-process form of
`jcmd Compiler.directives_add`. A kernel method's first C1 request is refused
and marks it not C1-compilable - where the tier-3 failure leaves it anyway -
so the interpreter profiles it and C2 compiles it, whatever the queues are
doing. C1 code for these methods boxes as the interpreter does, so nothing is
lost. It is skipped where C2 is not the top tier. The A/B is committed as
`VarkaWarmupDirectiveBenchmark`, which runs the sequence in fresh JVMs with
the directive and with an override that enables C1 again.

**The shape cache keys on the class loader, and so does the warm-up.** A
query run as an SQL execution - `noop()`, `collect()` - runs its tasks under
the session's own artifact class loader; `queryExecution.toRdd` run outside
one uses the default loader, and so meets a different entry of the cache,
with its own warm state. The cold-start benchmark's check that the compiled
kernel served every batch therefore runs its query as an SQL execution, as
the timed ones do; the ladder's `varkaFused` check does not need to, because
with the warm-up off every entry serves its first batch.

### 10.3 Predictions, registered before the committed run

The cold-start benchmark gains the warm-up arm (first run, second run, and
once compiled - the second run started only after the verdict), a line per
rung with each verdict, a back-to-back section at 16, 54 and 100 entries, and
a steady-state section over the ladder's two million rows. Every timed
iteration starts with the JIT quiet. Against the committed run of #429 and the
new arms:

1. **Past the cliff the warm-up arm's first run beats vanilla's.** From 54
   entries up its first run is faster than vanilla's first run and at least
   1.5 times faster than the per-batch arm's: the row path's projection is
   split into methods the JIT compiles, where vanilla's consume method is not
   compiled at all.
2. **Below the cliff it is close to vanilla's.** From 16 to 52 entries its
   first run is within 1.5 times vanilla's first run, and no slower than the
   per-batch arm's.
3. **Every verdict is a compile.** Every once-compiled iteration at every
   rung ends COMPILED, none released; the verdict takes under 2 seconds at 16
   entries, under 5 at 54 and under 10 at 100.
4. **Once compiled, the kernel is the fast one.** The once-compiled query is
   faster than vanilla's second run at every rung from 32 entries up, and at
   least five times faster from 54 up.
5. **The warm-up's profile costs nothing.** Per row over two million rows,
   the kernel compiled from the warm-up's short calls is within 10% of the
   kernel compiled from its own batches, at 16, 54 and 100 entries.
6. **Back to back, the switch shows.** At 16 and 54 entries the warm-up arm's
   queries fall to the once-compiled level within fifteen; the per-batch
   arm's do not at 54.
7. **The directive A/B.** Without the directive at least one of three fresh
   JVMs strands the 54-entry kernel; with it all three compile.

### 10.4 What happened to the compile-time lever

9.2 proposed a second lever, C2's time per kernel. Two pieces of it are in the
design above and one is not:

* **The epilogues cost the warm-up no C2 time.** An early version ran every
  other call with a seven-row tail, so every epilogue ran its body and C2
  compiled all fourteen of them at full size - as much C2 work again as the
  loops, for rows real 10,000-row batches never have. Mirroring the batch's
  own remainder leaves them the empty calls real batches give them.
* **C1 does no work on a kernel.** Its tier-3 compiles of these methods all
  fail, and before the directive each attempt cost a C1 thread tens of
  milliseconds on the way to failing.
* **Not built: fewer or cheaper C2 compiles per kernel.** C2 still compiles
  every loop method the warm-up runs. The measured costs of what the warm-up
  waits for are in 10.5; the options - more than one warm-up thread for the
  interpreted phase, which is serial in one thread today, or loop methods
  shared between repeated entries, which the ladder's identical entries would
  compile once instead of fourteen times - are follow-up rows, if the numbers
  say the verdict is what a user waits for.

### 10.5 Outcome, 26 September 2026

The committed run: `VarkaColdStartBenchmark-jdk25-results.txt` (512-bit),
`-128bit-results.txt` and `VarkaWarmupDirectiveBenchmark-jdk25-results.txt`,
on the quiet laptop with the canary passing. Best of five, milliseconds for a
hundred thousand rows, 512-bit lanes:

| Entries | vanilla first | vanilla second | Varka first | warm-up first | warm-up second | once compiled | verdict after |
| --: | --: | --: | --: | --: | --: | --: | --: |
| 16 | 115 | 54 | 244 | 164 | 92 | 25 | 0.8 to 1.1 s |
| 32 | 158 | 70 | 420 | 221 | 150 | 26 | 1.6 to 2.0 s |
| 48 | 217 | 149 | 622 | 339 | 222 | 28 | 2.5 to 3.0 s |
| 52 | 201 | 146 | 669 | 339 | 226 | 28 | 2.8 to 3.1 s |
| 54 | 461 | 436 | 699 | 346 | 232 | 27 | 2.9 to 3.3 s |
| 56 | 467 | 430 | 716 | 350 | 248 | 27 | 3.0 to 3.3 s |
| 64 | 545 | 513 | 808 | 428 | 268 | 29 | 3.5 to 3.8 s |
| 80 | 685 | 641 | 1026 | 489 | 336 | 33 | 4.5 to 4.9 s |
| 100 | 874 | 826 | 1268 | 639 | 409 | 40 | 6.3 to 6.8 s |

At 128-bit lanes the per-batch arm's interpreted kernel is four times the
calls, so the warm-up's first run gains more: 142 against 736 at 16 entries,
621 against 4457 at 100; once compiled, 28 to 56.

1. **Held.** From 54 entries up the warm-up arm's first run is 0.73 to 0.79
   times vanilla's first run, and 1.9 to 2.1 times faster than the per-batch
   arm's.
2. **Partly refuted.** Within 1.5 times vanilla's first run at 16 and 32
   entries (1.43, 1.40), not at 48 and 52 (1.56, 1.69): the row path below
   the cliff is the per-row projection, slower than vanilla's whole-stage
   loop. Never slower than the per-batch arm.
3. **Held.** Every verdict at both widths is a compile, none released:
   under 1.1 seconds at 16 entries, 3.3 at 54, 6.8 at 100.
4. **Held, and past it.** Once compiled the query is faster than vanilla's
   second run at every rung, 16 included (25 against 54), and 16 times faster
   at 54.
5. **Held.** Over two million rows the kernel compiled from the warm-up runs
   at 25.0, 65.4 and 117.0 ns a row at 16, 54 and 100 entries, against 24.7,
   66.8 and 118.8 for the kernel compiled from its own batches.
6. **Partly refuted.** Back to back, the 16-entry shape switched at its
   thirteenth query (62 to 13 ms); the 54-entry kernel landed during the
   fifteenth (115 ms), one query short. The per-batch arm never improved.
7. **Held, more strongly than predicted.** Without the directive all six
   kernels in three fresh JVMs were stranded, the 16-entry one included, still
   boxing at the sixty-second deadline; with it all six compiled, in 1.3
   seconds at 16 entries and 3.3 to 3.6 at 54.

What it means for the second post's bound 4: a new shape's first query is
now faster than vanilla's past the cliff and within 1.7 times of it below,
and the kernel takes over one to seven seconds after the shape is first seen,
depending on its width. The follow-up candidates are the row path below the
cliff (2) and the time to the verdict at wide shapes (6, 10.4).

### 10.6 What the directive cost, and where it is installed now, 26 September 2026

10.2 said nothing is lost by keeping C1 off the kernel classes. That was wrong
for a kernel with no warm-up. A steady-state check of this change on the quiet
laptop - `VarkaSizeLadderBenchmark` and `VarkaThroughputBenchmark` at 512 bits
against their committed files, not committed themselves - found the ladder's
best times unchanged at 48 entries and up (within 4%) but its average times
three to seven times higher at every rung, and the throughput benchmark's 46
Varka cases unchanged in both (median ratio 1.00). A timing of the ladder's
54-entry query over two million rows, per batch, twice each way, put the
difference in one query: the second query took 7.2 and 7.0 s with the
directive against 0.86 and 0.75 s without, and the rest were the same.

The failed tier-3 request is what creates a method's profile. With C1
excluded there is no such request, and the interpreter creates the profile
only at twice the tier-3 threshold, so a kernel fed by 625-iteration batches
starts counting toward C2 about a hundred batches later. The throughput
benchmark's kernels are small and each query is long, so they reach C2 in the
first query either way; the ladder's wide kernels do not.

So the directive is installed when the first warm-up starts, before its first
call, rather than at the first emission. A JVM that never warms a kernel -
every session with the warm-up off - compiles its kernels as before, and the
tier-2 strand stays possible there, as it always was. With the warm-up on, the
default, every warmed kernel is covered. The committed runs of 10.5 are
unaffected: the cold-start benchmark's warm-up arm installs the directive
before its first warm-up as it did at its first emission, and its per-batch
arm's kernels are never compiled within a hundred thousand rows either way.

### 10.7 The review, and what changed, 26 September 2026

A `/code-review max` of the branch found no wrong answer and fifteen defects
that cost speed, metrics, CI or tests. Two of them undercut this plan as
written.

**The directive reached kernels nobody warmed.** 10.6 installed it at the
first warm-up and concluded that a JVM which never warms a kernel compiles as
before. True, and not enough: a directive matches by class name for the rest
of the JVM's life, so once any session had warmed a kernel, every kernel class
emitted afterwards paid 10.6's price - a session with the warm-up off, and a
shape released because the queue was full. The committed cold-start run shows
it in its steady-state section: the per-batch arm, run after earlier rungs'
warm-ups in the same JVM, averages 429, 1726 and 3416 ms at 16, 54 and 100
entries against best times of 49, 134 and 238. Now the decision to warm is
made when the kernel is emitted (`VarkaKernelWarmup.warms`: the session's
flag, and a JVM that can warm), it is a component of the shape key, and a
warmed kernel's hash starts with `w`, which no hex digit is; the directive
matches `VarkaFusedProjection_w*` and nothing else. The planner's size
admission builds the same key, so a shape is still emitted once. A full queue
hands the claim back instead of releasing the shape, so every warmed class
gets the warm-up calls that create its profile.

**The warm-up compiled one of the kernel's two drivers.** It ran the claiming
batch's null class, dense or masked, and its verdict then sent batches of the
other class to methods that had never run. The calls now alternate between the
drivers and the verdict waits for both, unless no kernel input is nullable
(derived inputs count as nullable), when the masked driver cannot be reached.
The masked calls pass each input with and without validity, so every per-input
null test is profiled both ways; the dense calls read the values under a
batch's nulls, which are replaced with the input's first valid value. An
all-null claiming batch no longer takes the all-null shortcut in every call,
so it no longer releases the shape unwarmed. Which drivers a shape will need
could be predicted from statistics - the Arrow cache's per-batch null counts,
file and table statistics - which is a follow-up row, not this task.

The rest, each with a test where one could be written:

* A JVM that cannot warm - C1 alone (`TieredStopAtLevel` below 4,
  `CompilationMode=quick-only`, `NeverActAsServerClassMachine`), a JVMCI
  compiler, the interpreter, no allocation accounting, or a directive it did
  not accept - emits its kernels unwarmed instead of warming into the tier-2
  strand. A JVM with C2 alone warms without a directive. The directive file's
  path is passed in quotes, because the MBean splits its command line at
  spaces and `=`.
* The sixty-second deadline counts from the queueing, not from when the
  worker takes the job. A dead worker is replaced, and a job whose start
  failed frees its copy. The worker takes no thread-locals or context class
  loader from the task that first claims a shape.
* A Varka node above another counts the batches the node below sends from its
  row path while its kernel warms as warm-up batches, not non-Arrow
  fallbacks. The order of the two warm-ups costs no query: one worker runs
  them one after another either way.
* A batch the evaluator declines while copying it for a warm-up is counted as
  declined, as on the kernel path.
* `VarkaWarmupDirectiveBenchmark`'s child JVM gets the class path the
  benchmark workflow's `spark-submit` adds through `--jars`, and its output
  goes to a file, so its timeout can fire.
* The README's quick start runs its query again after the warm-up and names
  `numWarmupBatches`.

Two corrections to the sections above, which stay as written. 10.5's
prediction 1 range is 0.71 to 0.79, not 0.73 to 0.79: the 80-entry rung is
489 against 685. Prediction 3's verdict times are the 512-bit file's; at
128 bits the committed verdicts reach 1.29, 3.58 and 7.43 s at 16, 54 and 100
entries, every one of them a compile. And 10.6's timings - the ladder's
averages and the 7.2 and 0.8 s queries - come from runs that were not
committed; the numbers a reader can check are the steady-state averages
quoted above.

*Registered before the cold-start and directive benchmarks are run again:*

1. The per-batch arm's steady-state averages fall to within 1.5 times their
   best times at 16, 54 and 100 entries, from 8.8, 12.9 and 14.4 times.
2. The benchmark's column is nullable and has nulls in every batch, so the
   warm-up now compiles the dense driver as well: the verdict comes 1.2 to 2.0
   times later at 54 and 100 entries.
3. The warm-up arm's first run moves by less than 10%: its batches take the
   row path either way. Its second run rises, but stays below its first run.
4. Once compiled, and over two million rows, the times move by less than 5%.
5. The directive A/B is unchanged: without it every kernel strands, with it
   every kernel compiles.

### 10.8 The re-run, 26 September 2026

`VarkaColdStartBenchmark` at both widths and `VarkaWarmupDirectiveBenchmark`,
on the quiet laptop with the canary passing, at the commit of 10.7; the
committed files replace those of 10.5.

1. **Held at 16 entries, not at 54 and 100.** The per-batch arm's
   steady-state averages at 512 bits fell from 429, 1726 and 3416 ms to 68,
   471 and 1152, 1.45, 3.5 and 4.8 times their best times. The directive's
   price is gone; what remains at the wide rungs is the per-batch path's own
   warm-up, which two seconds of batches before the timing do not cover at
   512 bits. At 128 bits the averages were within 2% of the best before, and
   are now.
2. **Held, at the top of the range.** Every verdict at every rung and both
   widths is 1.6 to 2.0 times later, and every one is a compile: 5.3 to 6.0 s
   at 54 entries and 11.1 to 12.2 s at 100 at 512 bits, against 2.9 to 3.3
   and 6.3 to 6.8.
3. **Half held.** The warm-up arm's first run moved by 3% at most at
   512 bits, and by 5% at most at 128 bits but for one cell, 16 entries,
   142 to 165 ms. The second runs did not rise: they ran before the verdict
   in the old files too, at every rung, so a later verdict changes nothing
   they see. The prediction's reason was wrong.
4. **Held but for one cell.** Once compiled, within 5% except 100 entries at
   512 bits, 40 to 37 ms; over two million rows, the kernel compiled from the
   warm-up within 3%.
5. **Held.** Without the directive all six kernels stranded to the deadline;
   with it all six compiled, in 1.9 to 2.0 s at 16 entries and 5.8 to 6.2 s
   at 54.

So the first query is where it was - past the cliff the warm-up arm's first
run is 0.71 to 0.76 times vanilla's, below it 1.40 to 1.82 times - and what
roughly doubled is the time until the kernel takes over: 1.4 to 12.2 s after
the shape is first seen, where it was 0.8 to 6.8. The back-to-back section
shows it: no rung now switches within its fifteen queries. The benchmark's
column has a null in every batch, so the dense driver the warm-up now
compiles as well serves none of its batches. That is the case row 213 is
for: the Arrow cache's per-batch null counts would have said so, and the
verdict would come as early as before.
