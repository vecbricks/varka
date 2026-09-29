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
