# Task 195: What the first query costs

*Opened 25 September 2026, item 3 of `PLAN_MILESTONE_6.md` 9.2 and the one
measurement `PLAN_TASK_181.md` 4 says the post cannot go out without.*

## 1. The question

Every number the size ladder publishes is steady state: the harness warms a
case for two seconds and reports the best iteration. A reader who runs a query
once pays something else. Vanilla plans, generates Java source, has Janino
compile it, and then runs it while HotSpot warms the generated class; past the
cliff the consume method is never compiled and the steady state is the
interpreter's. Varka plans, emits a class through the Class-File API, defines
it, and then runs a kernel whose loop and epilogue methods C2 compiles while the
first batches go by. `VarkaEmissionBenchmark` prices the emission alone, ten
milliseconds for a hundred four-op outputs, and `PLAN_TASK_171.md` 9.2 saw the
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
   task 171 saw in the averages.
4. **Varka's first run still beats vanilla's first run at every rung past the
   cliff**, by at least three times at a hundred entries, because vanilla's
   steady state there is already 16 to 19 times slower on the full-width
   runner (`PLAN_TASK_192.md` 9.5) and its first run is no faster than that.
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

*To be written from the results files. Each prediction above is scored.*

## 6. Explicitly out of this task

* Reducing the first-query cost: a kernel cache across sessions, a smaller
  emission (row 191), or warming the JIT on purpose. This task measures; a
  reduction is a row of its own with this file as its baseline.
* The cold-JVM run, unless phase one calls for it (section 2).
* Parallelism (row 197): every run here is `local[1]`, as the ladder's are.
