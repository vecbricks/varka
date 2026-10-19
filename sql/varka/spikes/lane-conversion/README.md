# Lane conversion spike (VARKA-28)

Why did the first kernels of both lanes run at half the long lane's rate, and what form of the loop
gets it back? `sql/varka/plans/m8/VARKA-28.md` section 9.3 has the answer and quotes the numbers in
`results/`. None of this is in the build: no sbt or Maven source set reads this folder.

## What is here

| file | what |
|---|---|
| `src/Hand28.java` | five hand-written forms of `cast(i AS bigint) + l`: the long lane alone (`longs`), the int load under the low-half mask (`masked`), unmasked (`unmasked`), the int-driven pair of long vectors (`pair`) and the loop stopped a long lane group early (`bounded`), the form the emitter now takes |
| `run.sh` | every form at 4096 and 16384 rows, at the host's width and under `-XX:MaxVectorSize=16` |
| `results/hand-loop.txt` | `run.sh`'s output, taken under both JVM slots at a load of 1.0 or less |
| `results/before-fix-*.txt` | `VarkaLaneConversionBenchmark` at `e412cb42b8d`, the kernels as first emitted, with its provenance |
| `results/first-run-*.txt` | the first of the two runs after the fix, at `512bb292da3`; the second is the committed file under `sql/catalyst/benchmarks/` |
| `results/convert-intrinsic.txt` | the two `-XX:+PrintIntrinsics` lines that refuse the conversion when its target species is a local |
| `results/branch-form.txt` | the probe numbers of an emitter variant that branched between a whole and a masked int load inside the loop, which was not kept |
| `results/int-arm-tiering.txt` | the benchmark's int-lane reference arm at 16384 rows, tiered and not |

## Commands

    sql/varka/spikes/lane-conversion/run.sh > sql/varka/spikes/lane-conversion/results/hand-loop.txt

`Hand28.java` runs as a single-file program (`java --add-modules jdk.incubator.vector
-Dn=16384 src/Hand28.java bounded`); each form runs five rounds of about 330 million rows and
prints the last. The buffers have slack, so `unmasked` reads past the batch's last int without
faulting; it is the bound on what a whole load can gain, not a correct kernel.
