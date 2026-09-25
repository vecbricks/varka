# Task 171: the size ladder, and the figure

## 1. Where this came from

Milestone 6 row 171 and section 2.5 of `PLAN_MILESTONE_6.md`, absorbing
milestone 4's row 44: a benchmark whose x-axis is the number of expressions in
one projection and whose y-axis is time per row, on two arms - stock Spark and
Varka - where vanilla is expected to step and Varka to stay a line. It is the
figure milestone 6's post rests on: "an issue that vanilla Spark cannot solve
but Varka has fixed".

## 2. The admission check, done

Run on 24 September 2026 against an Arrow-cached date column, before any of
this task's code, by a probe that compiled each rung's whole-stage codegen and
asked Varka's compiler what it fuses. It changed the task in three ways.

**2.1 Varka did not fuse the widths that matter.** For the family chosen below,
vanilla's largest generated method, in bytes, against the entries Varka fused:

| entries | 16 | 32 | 48 | 64 | 80 | 100 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| vanilla whole-stage method | 2416 | 4800 | 7184 | 10184 | 13272 | 17132 |
| Varka fused, under the op cap | 15 | 15 | 15 | 15 | 15 | 15 |

`MAX_FUSED_NODES` admitted 64 ops a kernel and each entry is four. Task 190's
first step replaces the cap with the byte budget; with it, all a hundred fuse
in one kernel (`PLAN_TASK_190.md` 9.1). This task is stacked on that step.

**2.2 The vanilla cliff this range shows is the JIT's, and Spark does not log
it.** Section 2.5 of the milestone expected both cliffs, and the vanilla arm to
quote "Spark's own log line per rung". The 65535-byte limit, where Spark logs
"the whole-stage codegen was disabled for this plan", is out of reach: at a
hundred entries the method is 17132 bytes, and past a hundred
`spark.sql.codegen.maxFields` switches whole-stage codegen off before any byte
limit is met (task 185's cliff). What the range does cross is
`HugeMethodLimit`, 8000 bytes, between 48 and 64 entries: past it the method is
generated and loaded and HotSpot never compiles it, at any tier, and nothing in
Spark says so. So the evidence for the vanilla step is the JVM's own output -
`-XX:+PrintCompilation` naming `processNext` compiled below the crossing and
absent above it - which is also how task 87 pinned the same limit on Varka's
side (`VarkaHugeMethodSuite`).

*Corrected on 24 September 2026, by the suite of 3.2 failing on this sentence:
the method that crosses is not `processNext`. Spark's
`spark.sql.codegen.splitConsumeFuncByOperator`, on by default, puts each
operator's consume code in a method of its own, so the projection is
`project_doConsume_0$` and `processNext` is a 279-byte loop that calls it and is
compiled at every rung. The size Spark reports as the stage's largest method is
the consume method's, and it is that method which is compiled at 32 entries and
never at 80. A first pattern for it also dropped the trailing `$` of the name,
which matched nothing at either rung, and so read below the limit exactly like
the absence it was meant to detect above it.*

**2.3 The family.** `greatest(add_months(d, k), date_add(d, k), last_day(d))`
for entry `k`: four ops, each entry distinct in its literal, and the heaviest
per entry of the seven families probed - about 190 bytes of whole-stage method
each - so the crossing falls well inside the range `maxFields` leaves. A
`CASE` family crosses near 90 entries and the one-op families never do before a
hundred.

## 3. The design

### 3.1 The benchmark

`VarkaSizeLadderBenchmark`, in `sql/core`, its own family and its own results
files. One Arrow-cached date column, a projection of `n` entries of 2.3's family
ending in `noop()`, at rungs chosen to straddle the crossing rather than be
round - 16, 32, 48, 52, 54, 56, 64, 80 and 100 - each measured on two arms by
explicit label:

* `vanilla Spark (whole-stage codegen)`: the Varka rule off. The source is the
  same Arrow cache the Varka arm reads, as `VarkaThroughputBenchmark`'s baseline
  is, so the arms differ in the engine and nothing else.
* `Varka`: the rule on.

Before the timed cases each rung prints, into the results file, what the rung
*is*: vanilla's largest generated method in bytes, whether it is past
`HugeMethodLimit`, and how many entries Varka fused. So the file carries the
x-axis's meaning beside its timings, and the figure is read from one file.

### 3.2 The JVM's word for the vanilla step

`VarkaSizeLadderJitSuite` forks a JVM under `-Xbatch -XX:+PrintCompilation`,
runs the vanilla projection at two rungs either side of the crossing, and
asserts from the JVM's output that the projection's consume method is compiled
below it and never compiled above it, each rung's method size printed beside the
verdict. The same shape as `VarkaHugeMethodSuite`, pointed at Spark's generated
class instead of Varka's.

### 3.3 The published run

The committed files are the laptop's, with a band, for development - the
laptop's 512-bit datapath is double-pumped, and a public number comes from a
GitHub runner. The figure in the post is read from a run of the same class
through `.github/workflows/benchmark.yml` on the fork, whose artifact is
committed beside the laptop's files under its own name, with the runner's CPU
named. The JIT cliff is a property of the JVM, not of the vector width, so any
runner shows it; the runner is for reproducibility.

### 3.4 What is deliberately unchanged

* Spark's 64 KB and `maxFields` cliffs are tasks 185 to 188's to study; this
  ladder stops at a hundred, where both arms keep their mode.
* Varka past its driver ceiling is task 190's step 2.

### 3.5 Registered op counts

None: no emitter change.

## 4. Files

| file | what |
|---|---|
| `VarkaSizeLadderBenchmark.scala` | the ladder |
| `VarkaSizeLadderJitSuite.scala`, `VarkaSizeLadderJitProbe.scala` | the vanilla step from the JVM |
| `sql/core/benchmarks/VarkaSizeLadderBenchmark-*` | the laptop's files and band; the runner's file |
| `PLAN_MILESTONE_6.md` | row 171 |

## 5. Tests, and what each is for

* **The JIT suite**, above: the vanilla step asserted from the JVM, not read
  from a timing.
* **The benchmark's rung facts** assert as they print: every rung's Varka arm
  fuses every entry, so a regression in admission fails the run rather than
  quietly measuring a partly per-row Varka arm.

## 6. The measurement

The ladder at both arms, regenerated with `dev/varka_bench_regen.sh` on a quiet
machine, banded; then the runner's run.

### 6.1 Predictions, registered before the run

1. **Vanilla steps once, between 48 and 64 entries**, where its method crosses
   8000 bytes: time per row per entry jumps there by more than a factor of
   three, the interpreter against C2.
2. **Varka is a line**: time per row grows in proportion to the entries across
   the whole range, with no step, since every method of its kernel is under
   8000 bytes at every rung.
3. **Below the crossing vanilla is within a factor of two of Varka**; above it
   Varka is faster by more than the factor the step costs vanilla.

## 7. Risks

1. **The vanilla step may be smaller than predicted** if the interpreted
   `processNext` is not the dominant cost. Then the figure shows a real but
   modest step, and the post says so rather than leaning on it.
2. **The generated class name is shared**: every query's first stage is
   `GeneratedIteratorForCodegenStage1`. The JIT probe runs exactly one query in
   its JVM, so the name is unambiguous there.

## 8. Sequencing

1. This plan and the benchmark, the JIT suite, and the laptop's committed files
   with their band, in one pull request stacked on task 190's first step.
2. The runner's run, and its file committed.
3. The figure, and row 171 done.

## 9. Outcome

### 9.1 The laptop's ladder, 24 September 2026

`VarkaSizeLadderBenchmark` regenerated at both widths on a quiet machine
(pinned, load 0.21 at start, canary clean) and banded over ten runs at 512 bits.
Time per row in nanoseconds, from `VarkaSizeLadderBenchmark-jdk25-results.txt`
and its 128-bit companion, beside vanilla's largest generated method as the file
records it:

| entries | vanilla method (bytes) | vanilla, 512-bit | Varka, 512-bit | vanilla, 128-bit | Varka, 128-bit |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 16 | 2416 | 252.0 | 30.2 | 279.5 | 57.4 |
| 32 | 4800 | 477.7 | 46.7 | 536.2 | 102.6 |
| 48 | 7184 | 703.5 | 64.9 | 800.7 | 148.0 |
| 52 | 7868 | 763.2 | 71.0 | 860.6 | 156.2 |
| 54 | 8254 | 4016.9 | 70.6 | 4148.3 | 163.0 |
| 56 | 8640 | 4232.9 | 71.9 | 4341.1 | 167.3 |
| 64 | 10184 | 4919.9 | 79.5 | 5038.6 | 190.3 |
| 80 | 13272 | 6256.0 | 99.6 | 6407.6 | 235.0 |
| 100 | 17132 | 8010.6 | 122.8 | 7866.4 | 292.9 |

**Prediction 1 held, and the step is sharper than registered**: between 52 and
54 entries, where vanilla's consume method crosses 8000 bytes, its time per row
rises from 763.2 to 4016.9 ns - more than five times, where more than three was
registered - and it rises no further in steps after that, only with the entries.
`VarkaSizeLadderJitSuite` names the cause from the JVM: the method is compiled
by C2 below the limit and never compiled above it.

**Prediction 2 held**: Varka's line has no step anywhere - 71.0 ns at 52
entries, 70.6 at 54 - and grows more slowly than the entries, from 30.2 ns at
sixteen to 122.8 at a hundred.

**Prediction 3 fell, in Varka's favour.** Below the crossing vanilla was
registered within a factor of two of Varka; it is eight to eleven times slower
(252.0 against 30.2 at sixteen entries, 763.2 against 71.0 at fifty-two). Above
it Varka is faster by more than the step costs vanilla, as registered: 57 times
at fifty-four entries and 65 at a hundred. The figure therefore shows two
things at once, and the post has to keep them apart: a speed gap that is
Varka's on this family whatever the width, and a cliff that is vanilla's alone.

At 128 bits vanilla is unchanged - its generated code is scalar - and Varka's
kernel, at half the lanes, takes about twice as long; the step and the line are
the same.

**The trial runs that preceded these files measured the wrong thing.** At a
hundred thousand rows, the first choice, each case's fixed cost - planning a
wide projection and its first row, tens of milliseconds a query - was most of
the Varka arm's time per row, and the kernel's wide methods barely reached C2
within ten batches; the numbers read as Varka slower than vanilla. At two
million rows, as `VarkaThroughputBenchmark` reads, the kernel dominates. The row
count is now two million and the class doc says why.

**The band has one unreadable case, and it is the tool's.** Vanilla at
fifty-four entries is tier 3 with a spread of 50 per cent, because
`dev/varka_bench_band.py` reads the Rate column, which the harness prints to one
decimal, and that case runs at about a quarter of a million rows a second: its
runs print 0.2 or 0.3. Its time per row is stable to a few per cent. A band
read from the time column, or from the rate at more digits, is task 178's to
build; every other case is tier 0 or 1.

**Also found on the way**, and corrected in 2.2 above: the method that crosses
is the projection's consume method, not `processNext`; and the 128-bit file's
first rung note was lost, because `dev/varka_bench_regen.sh` cuts that file from
the console from the first table on, so the notes now follow their tables.

What remains is the plan's step 2 - the same class through the GitHub runner,
for the published figure - and the figure itself.

### 9.2 The runner's ladder, 24 September 2026

The same class through `.github/workflows/benchmark.yml` on a GitHub-hosted
runner, which drew the pool's full-width machine, the AMD EPYC 9V45
(`VarkaSizeLadderBenchmark-jdk25-runner-results.txt`, and its provenance file
beside it). The workflow needed Varka's engine jar on its `--jars` list first; a
Varka benchmark had never been run through it.

**The laptop's shape holds.** Vanilla steps between the same two rungs, from
892.8 ns a row at 52 entries to 4766.3 at 54, where its method passes 8000
bytes; Varka is 66.9 and 69.6 across the same pair. Varka is 9.2 to 13.3 times
faster below the step and 68.5 to 82.1 times above it. The ratios are a little
larger than the laptop's because the runner's vanilla is slower (304.7 ns a row
at sixteen entries against the laptop's 252.0) while Varka's wide rungs are
faster (111.4 at a hundred against 122.8), which is the 512-bit datapath the
laptop does not have.

**The Varka arm's average is far above its best, on both machines.** At a
hundred entries the runner's best is 223 ms and its average 1923, with a
standard deviation of 3787; the laptop's file has the same pattern (246 and
1183). The per-row figures, like every ratio above, are the harness's, computed
from the best time. The shape of the spread - one iteration of several seconds
among fast ones - fits the kernel's widest methods still reaching C2 after the
two-second warmup, but that is not measured. It is the first-query cost row 195
asks about, and it belongs in the post's reproduction notes: a reader who reads
the Avg column will see a smaller gap than the headline.

What remains is the figure.

### Correction, 24 September 2026: the cliff is logged

This plan says Spark does not report a method past HotSpot's 8000-byte limit. It does: since 2.4.0
`CodeGenerator` logs "Generated method too long to be JIT compiled: <class>.<method> is N bytes" at
INFO, which a `spark-submit` job's log shows and `spark-shell`, at WARN, hides. Spark notices the
cliff, says so once at INFO, and runs the method uncompiled anyway. `PLAN_TASK_188.md` section 5
has the evidence.

### 9.3 The figure, 25 September 2026

`sql/varka/plans/figures/svg/fig11-the-size-ladder.svg`, drawn by `figures/fig11.py` in the style
of the milestone 5 post's figures. It plots time per row against the number of expressions in the
projection, on a log scale, for stock Spark and for Varka, from the runner's ladder of 9.2 - the
EPYC 9V45 on JDK 25, both named on the figure from the provenance file. The script reads every
value from `VarkaSizeLadderBenchmark-jdk25-runner-results.txt` when it runs, rungs, times per row,
ratios and the generated method's bytes alike, so the figure cannot drift from the committed file.

It keeps apart the two things 9.1 says the post must not blur. The step is stock Spark's alone: a
dashed line where its generated method passes 8000 bytes, 7868 bytes at 52 entries and 8254 at 54,
and the jump from 892.8 to 4766.3 ns a row across it, after which the method is never compiled
again. The gap is Varka's at every width: its line runs through the same rungs, 66.9 and 69.6 ns
either side of the step, and it is 13.3 times faster than stock Spark at 52 entries and 82.1 times
at a hundred.

To regenerate, from `sql/varka/plans/figures/`, with `fonttools` and `brotli` installed:
`python3 fig11.py`. The axis ticks, 10 to 10 000 ns, are the scale, not measurements; every other
number on the figure is in the results file.
