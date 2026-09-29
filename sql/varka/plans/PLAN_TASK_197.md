# Task 197: does the gap survive parallelism?

## 1. The question

The size ladder (task 171) runs on `local[1]`: one core, one task, vanilla Spark's
interpreted consume method against Varka's kernel. The published figure is the
one-core ratio, 82 times at a hundred entries on the 9V45 runner
(`VarkaSizeLadderBenchmark-jdk25-runner-results.txt`). A reader will ask what
happens on a machine that runs the query on every core, which is every machine.
The two arms scale for different reasons and so may scale differently: an
interpreter costs the same on every core, so vanilla's time divides by the cores;
a kernel reads the Arrow cache and writes one column per entry - four hundred
bytes a row at a hundred entries, about 3.6 GB/s from the one-core figure - at a
rate the cores together may saturate. Where that happens the ratio shrinks, and
by how much is the measurement. Milestone 6 row 197, research, optional.

## 2. The change

`VarkaSizeLadderParallelBenchmark`: the ladder's rungs, data and query
(`VarkaSizeLadder`'s, shared), with both sessions on `local[*]`
(`VarkaArrowSessions.createSession` gains a `master` parameter, `local[1]` by
default, so no other benchmark moves). The cached table comes in as many
partitions as the session has cores, since `range` follows the default
parallelism, which is what puts a task on every core; each rung's note records
the core count and the partition count beside vanilla's method size. Its own
class and files, so the one-core file stays what it is and the two are read side
by side.

The run is one dispatch of `benchmark.yml` over both classes,
`VarkaSizeLadder{Benchmark,ParallelBenchmark}`, so the one-core and the every-core
ladders come from the same machine and the same hour; the runner's CPU names both
files (`-runner-<cpu>-`). The runner is not pinned: the question is the ratio of
the two files on whatever machine they share, not an absolute number, and the
pool's four-vCPU runners are what "every core" means on a shared VM.

## 3. Predictions, registered before the run

On a four-vCPU runner, against the one-core file of the same dispatch:

1. **Vanilla divides by three or more** at every rung past the cliff (54 entries
   and up), where it runs interpreted: no memory bound, one method per task, and
   the SMT pair of a vCPU costs it a little short of four.
2. **Varka divides by less at the top than at the bottom.** At sixteen entries,
   about seventy bytes a row, it scales like vanilla, three or more; at a hundred
   entries, four hundred bytes a row, it divides by between two and three, the
   machine's memory bandwidth showing.
3. **The ratio at a hundred entries shrinks, and stays large**: from the
   one-core file's figure to between half and nine tenths of it, so from about 82
   to between 40 and 70 on a 9V45. Below the cliff (52 entries and under) the
   ratio moves by less than 30%.
4. **The cliff does not move.** The method sizes are the query's, not the
   master's, so vanilla steps between 52 and 54 entries as before.

**What the answer decides.** If the ratio past the cliff stays above twenty at
every rung, the post quotes the one-core ladder and says in a sentence what the
every-core run measured. If it falls under ten anywhere, the post quotes the
parallel run instead, since a one-core figure a reader cannot reproduce on a
machine of theirs is not the figure to publish.

## 4. Verification

* `sql/Test/compile`; the class is the ladder's loop with the master changed,
  and its rung check (`fused == n`) fails the run if the Varka arm falls back.
* The dispatch's two files carry the same CPU in their provenance and the same
  method sizes per rung; the parallel file's notes carry the core count.

## 5. Files

| file | what |
|---|---|
| `VarkaArrowSessions.scala` | the `master` parameter |
| `VarkaSizeLadderParallelBenchmark.scala` (new) | the benchmark |
| `sql/core/benchmarks/VarkaSizeLadderParallelBenchmark-jdk25-runner-<cpu>-{results,provenance}.txt` | the every-core run |
| `sql/core/benchmarks/VarkaSizeLadderBenchmark-jdk25-runner-<cpu>-{results,provenance}.txt` | the one-core run of the same dispatch |
| `PLAN_MILESTONE_6.md` | row 197 |

## 6. Explicitly out of this task

A laptop run: the laptop has twenty-four cores and a memory system no runner
shares, so its scaling says nothing about the machine a reader has, and it would
cost an hour of a quiet machine. A pinned CPU: the ratio of two files from one
machine is the answer, whatever the machine. Anything that changes a kernel.

## 7. Outcome, 29 September 2026

One dispatch, run 36519637464 on the fork, landed on an AMD EPYC 7763 with four vCPUs
(`VarkaSizeLadderBenchmark-jdk25-runner-7763-results.txt` and
`VarkaSizeLadderParallelBenchmark-jdk25-runner-7763-results.txt`, with provenance). A 256-bit
machine, so its Varka numbers are below the 9V45 file's; the comparison is within the
dispatch. Per row, nanoseconds, best iteration:

| entries | vanilla, 1 core | vanilla, 4 | divides by | Varka, 1 core | Varka, 4 | divides by | ratio, 1 core | ratio, 4 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 16 | 552 | 196 | 2.8 | 69.2 | 44.7 | 1.55 | 8.0 | 4.4 |
| 32 | 1076 | 373 | 2.9 | 117.3 | 55.4 | 2.1 | 9.2 | 6.7 |
| 48 | 1609 | 548 | 2.9 | 159.8 | 93.9 | 1.7 | 10.1 | 5.8 |
| 52 | 1749 | 586 | 3.0 | 181.8 | 108.5 | 1.7 | 9.6 | 5.4 |
| 54 | 9096 | 3157 | 2.9 | 171.6 | 105.4 | 1.6 | 53.0 | 30.0 |
| 64 | 11263 | 3788 | 3.0 | 196.8 | 123.8 | 1.6 | 57.2 | 30.6 |
| 80 | 14110 | 4804 | 2.9 | 243.5 | 144.9 | 1.7 | 58.0 | 33.2 |
| 100 | 17714 | 6031 | 2.9 | 301.0 | 177.5 | 1.7 | 58.8 | 34.0 |

The predictions of section 3, scored:

1. **Just under.** Vanilla divides by 2.9 to 3.0 past the cliff against "three or more": the
   SMT pair of a vCPU costs the interpreter a little more than allowed for.
2. **Failed, and not the way the prediction was shaped.** Varka divides by 1.6 to 1.7 at every
   rung from 48 up, and by 1.55 at sixteen entries, where it was to scale like vanilla. The
   bottom of the ladder scales no better than the top, so this is not the memory system
   filling as the rows widen. At a hundred entries four cores move 2.3 GB/s where one moved
   1.3, far under what the machine has; the bound is the vector unit, not the memory. The
   runner's four vCPUs are two cores with two threads each on this pool, and a kernel that
   keeps the SIMD pipes busy gains nothing from the sibling thread of a core it already
   fills, where the interpreter, bound on latency, gains the second thread's whole share.
   Read that way the every-core Varka figure is the two-core figure, and the ratio's fall is
   two physical cores against four interpreter threads.
3. **Held at the top, failed below the cliff.** At a hundred entries the ratio is 0.58 of the
   one-core file's, inside the predicted half to nine tenths, and it is 0.52 to 0.58 at every
   rung past the cliff. Below the cliff it moves by 27% to 45%, over the 30% the prediction
   allowed, for the reason in 2.
4. **Held.** Vanilla steps between 52 and 54 entries in both files; the method sizes are the
   query's.

**What the answer decides.** The ratio past the cliff is 30 to 34 at every rung, above the
twenty the plan set, so the post quotes the one-core ladder and says in a sentence that on a
four-vCPU runner every core cuts the ratio to about six tenths of it, thirty times and more.

**A finding beside the answer.** The parallel Varka arm's iterations spread where the one-core
arm's do not: at sixteen entries the best iteration is 89 ms and the average 429, with a
standard deviation of 740 ms, and every rung shows the same shape, where the one-core file's
average sits within a fifth of its best. The best iterations are what the table above reads;
what the slow iterations are - four tasks each warming its own evaluator, allocation, or the
sibling threads contending - is not established here and belongs with row 231's benchmark
cases for the row loops, or a task of its own if the spread shows on a wider machine.
