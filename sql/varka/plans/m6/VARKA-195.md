# VARKA-195: What the first query costs

*Opened 25 September 2026, item 3 of `m6/PLAN.md` 9.2 and the one
measurement `VARKA-181.md` 4 says the post cannot go out without.*

## 1. The question

Every number the size ladder publishes is steady state: the harness warms a
case for two seconds and reports the best iteration. A reader who runs a query
once pays something else. Vanilla plans, generates Java source, has Janino
compile it, and then runs it while HotSpot warms the generated class; past the
cliff the consume method is never compiled and the steady state is the
interpreter's. Varka plans, emits a class through the Class-File API, defines
it, and then runs a kernel whose loop and epilogue methods C2 compiles while the
first batches go by. `VarkaEmissionBenchmark` prices the emission alone, ten
milliseconds for a hundred four-op outputs, and `VARKA-171.md` 9.2 saw the
Varka arm's average several times its best at the wide rungs and did not
measure why. So the question is: **at each rung of the ladder, what does the
first run of a query cost on each arm, and how far is it from the steady
state?**

## 2. The change

`VarkaColdStartBenchmark`, beside the ladder and on its rungs and data, over a
hundred thousand rows rather than two million so the steady state does not
drown the first run. Three cases per arm, five iterations each, every
iteration printed rather than summarised because a first run is one event and
its spread is the finding:

* **plan only** on each arm: analysis, optimization and physical planning of
  a fresh shape up to the executed plan, without running it. On the Varka arm
  the planner asks the compiler, which classifies and emits, so the emission
  is in this case; vanilla generates its code at execution, so its case holds
  Catalyst alone. *Added after the smoke run; see the note below.*
* **first run** on each arm: a shape the JVM has not compiled. Each iteration
  uses fresh offsets, so vanilla's generated source is new and Janino compiles
  it again. The Varka arm also clears the shape cache first
  (`VarkaShapeCache.invalidateAll`), because literal values are not part of a
  shape and the same tree with new constants would be a hit. The timer covers
  planning, compiling and the run.
* **second run** on each arm: the same query again in the same session. The
  difference between the two cases is the first run's price, and the second
  run's distance from the ladder's steady state is what the JIT still owes
  after one query.

*Note, 25 September 2026, from the smoke run.* The first build of the
benchmark ran every rung at the reduced row count on the laptop, beside
another build, so its numbers are not a measurement and are not committed.
They showed one thing worth acting on before the measurement: the two arms'
second runs were within ten percent of each other at every rung, where the
ladder has Varka an order of magnitude ahead at the wide rungs. Either the
Varka arm was not fusing, which the first build did not check, or planning a
hundred-entry projection costs most of a second on both arms and a hundred
thousand rows do not outweigh it. So the benchmark now refuses an arm that did
not fuse, as the ladder does, and splits planning from running with the
plan-only case, so the measurement says which it is rather than leaving it to
be guessed from totals.

**A second phase, decided by the first.** The benchmark above measures a new
shape in a warm JVM, which is what a long-lived session pays per new query. A
cold JVM adds Spark's own start and the JIT's warmup of everything, on both
arms alike; if phase one shows the arms' first runs within a factor of two of
each other at the wide rungs, the cold-JVM number is the same story plus a
constant and is not measured; if they differ by more, the cold-JVM run is one
`spark-submit` per arm and rung from a small driver script and is added here.

## 3. Predictions, registered before the run

1. **Vanilla's first run below the cliff is two to three times its second**:
   Janino on a method of a few kilobytes plus C2's warmup of it, tens to a
   hundred milliseconds on top of a run of a hundred thousand rows.
2. **Vanilla's first run past the cliff is close to its second**, within 1.5
   times: the consume method is interpreted on the first run and on every run,
   so only Janino's compile is added, and the interpreted run is the larger
   number.
3. **Varka's first run is two to five times its second at the wide rungs**,
   and the gap grows with the rung: emission and definition are tens of
   milliseconds at a hundred entries, and the kernel's methods reach C2 only
   after enough lane groups have run through them, so a good part of the first
   hundred thousand rows runs in C1 or the interpreter. This is the effect
   VARKA-171 saw in the averages.
4. **Varka's first run still beats vanilla's first run at every rung past the
   cliff**, by at least three times at a hundred entries, because vanilla's
   steady state there is already 16 to 19 times slower on the full-width
   runner (`VARKA-192.md` 9.5) and its first run is no faster than that.
5. **Below the cliff, at 16 entries, the two first runs are within a factor of
   two of each other**, either way. That rung is where the post has to say
   that the first query is not Varka's advantage.
6. *Registered after the smoke run, before the measurement.* **Planning a
   hundred-entry projection costs more than running it over a hundred thousand
   rows, on both arms**, and the plan-only case grows faster than linearly
   with the rung. If this holds, the first query's cost at the wide rungs is
   Catalyst's before it is either engine's, and the post says so.

## 4. Verification

* The benchmark refuses to time an arm that did not do what its name says: the
  Varka arm's plan carries a Varka projection, as the ladder checks.
* `dev/varka_bench_regen.sh sql VarkaColdStartBenchmark` writes the results and
  provenance files under `sql/core/benchmarks/`; the runner run through
  `benchmark.yml` adds the runner file with its CPU.
* Every number quoted from the run in this plan and in the post traces under
  `dev/varka_quote_check.py`.
* The smoke mode (`VARKA_COLDSTART_SMOKE=true`, one rung, ten thousand rows,
  two iterations) runs before the measurement, to check the benchmark and not
  the numbers.

## 5. Outcome

### 5.1 The first quiet run measured vanilla twice, 26 September 2026

The benchmark as merged gave each iteration fresh offsets of
`10000 * (iteration + 1)`, and those offsets are also `add_months` month
counts. The Varka kernel runs a batch only while a month count is within
`VarkaChrono.MONTH_ARITH_MAX_MONTHS`, 24564; past it the batch goes to Spark's
row path. The first-run case crossed that bound from its third iteration and
the second-run case, at `100 + iteration`, on every iteration, so the Varka
arm's timings were largely vanilla's own code, and they tracked the vanilla arm
within a few percent at every rung. The fusion check at the top of each rung
ran only the first iteration's query, which was in range, and it required only
that some batch had run on the kernel. The results file this task merged with
was measured the same way.

The fix, in this commit: iterations are a hundred apart and the largest offset
any case uses is checked against the kernel's bound when the benchmark starts;
the fusion check runs at the largest offset of each executed case; and the
ladder's shared check, `VarkaSizeLadder.varkaFused`, now also requires every
fallback counter of the node - non-Arrow input, kernel failure, row path,
declined - to be zero, so the size ladder is held to it as well.

### 5.2 The corrected run, and what the JVM says

`VarkaColdStartBenchmark-jdk25-results.txt` and its 128-bit companion, on the
quiet laptop, canary passing. Times are the best of five, in milliseconds for
a hundred thousand rows:

| Entries | vanilla first | vanilla second | Varka plan only | Varka first | Varka second |
| --: | --: | --: | --: | --: | --: |
| 16 | 134 | 56 | 15 | 239 | 229 |
| 48 | 235 | 154 | 16 | 624 | 605 |
| 54 | 461 | 437 | 16 | 694 | 677 |
| 100 | 872 | 829 | 27 | 1260 | 1233 |

Varka is slower than vanilla at every rung, on the first run and on the
second, and its second run is its first run minus the emission. Run under
`-XX:+PrintCompilation` (a diagnostic run, not committed), the benchmark shows
why: across the whole run not one of the kernels' `loopDense`, `loopMasked`,
`epilogueDense` or `epilogueMasked` methods is compiled at any tier, while
vanilla's generated `processNext` is compiled 506 times in the same log and the
Varka compiler's own classes compile normally. Each iteration emits a new
kernel class; its loop method is called once per batch - the cache's default
batch is 10,000 rows, so ten calls for a hundred thousand rows - and loops
about 625 times per call at sixteen lanes, some 6,250 iterations in all. The
first compiler tier needs 200 calls, or 2,000 calls and iterations together
with at least 100 calls, or 60,000 iterations inside one call
(`Tier3InvocationThreshold`, `Tier3CompileThreshold`,
`Tier3MinInvocationThreshold`, `Tier3BackEdgeThreshold` on this JDK), and the
kernel meets none of them. So the
whole query runs in the bytecode interpreter, where every Vector API operation
is a library call that allocates, which is far slower than the interpreted
scalar code of vanilla's uncompiled method. Vanilla escapes the same fate
because its generated loop runs over all the rows in one invocation, and the
JIT compiles it partway through that one call.

**The predictions.**

1. **Partly held.** Vanilla's first run below the cliff is 2.4 and 2.1 times
   its second at 16 and 32 entries, but 1.5 and 1.8 at 48 and 52.
2. **Held.** Past the cliff vanilla's first run is within 1.1 of its second.
3. **Refuted.** Varka's first run is only about 1.03 times its second, because
   the second run is not steady state either: the class is new and has not
   been compiled.
4. **Refuted.** At a hundred entries Varka's first run is 1.4 times slower than
   vanilla's, not three times faster.
5. **Held, the wrong way round.** At 16 entries the two first runs are within
   a factor of two, Varka 1.8 times slower.
6. **Refuted.** Planning is 10 to 27 ms and running a hundred thousand rows is
   55 to 1260; the smoke run that suggested this ran ten thousand rows.

**What it means.** The post's bound 4 stands and is sharper than written: a
new shape's first query on Varka is slower than vanilla's until its kernel is
compiled, and at a hundred thousand rows it never is. That makes the shape
cache, which keys a kernel by its tree rather than its literals, the thing that
carries Varka in practice: a dashboard that reruns a shape with new values
reuses a class the JIT has already compiled. It also puts three design
questions on the table for the next milestone, none of them this task's: run a
new shape's first batches on Spark's row path until the kernel is compiled;
emit one loop over all of a partition's batches, so that one invocation
reaches the back-edge threshold as vanilla's does; and ahead-of-time or
lowered-threshold compilation for emitted classes. The number the post quotes
still comes from a runner; this laptop run decides what to measure there.

## 6. Explicitly out of this task

* Reducing the first-query cost: a kernel cache across sessions, a smaller
  emission (row 191), or warming the JIT on purpose. This task measures; a
  reduction is a row of its own with this file as its baseline.
* The cold-JVM run, unless phase one calls for it (section 2).
* Parallelism (row 197): every run here is `local[1]`, as the ladder's are.
