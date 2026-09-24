# Task 192: can vanilla Spark tune its way off the cliff?

## 1. Where this came from

Task 171's ladder, 24 September 2026. Vanilla Spark's projection of
`greatest(add_months(d, k), date_add(d, k), last_day(d))` entries steps several
times over in time per row between 52 and 54 entries, where its generated
consume method crosses HotSpot's 8000-byte `HugeMethodLimit` and is never
compiled again, while Varka's line runs through without a step
(`VarkaSizeLadderBenchmark`'s results, and `VarkaSizeLadderJitSuite` for the
JVM's word on it). Asked frankly
how strong the milestone's claim is - an issue vanilla Spark cannot solve and
Varka has fixed - the answer was that the measurement is sound and the word
*cannot* is untested: Spark and the JVM each have a knob aimed at this cliff,
and nobody has measured what they buy. The owner asked for a task that answers
it ("Let's add a task and answer to the question").

## 2. The admission check, done

The knobs exist and are what a knowledgeable reader will name first.

* **`spark.sql.codegen.hugeMethodLimit`**, default 65535. Past it Spark
  deactivates whole-stage codegen for the subtree and runs it with its
  per-operator code paths. Its own doc says that "when running on HotSpot, it
  may be preferable to set the value to 8000 to match HotSpot's
  implementation" - and it is declared `.internal()`, so it is absent from
  Spark's published configuration reference, and its default is the value its
  doc advises against. A user who never reads `SQLConf.scala` does not know it
  is there.
* **`-XX:-DontCompileHugeMethods`**, a HotSpot product flag, default on. Off,
  HotSpot compiles methods of any size, so the consume method is compiled at
  every rung - at whatever C2 makes of a 17 KB method.
* **`spark.sql.codegen.wholeStage=false`**, the blunt form of the first:
  whole-stage codegen off for every stage, whatever its size.

Nothing is known yet about what any of them costs below the cliff or buys above
it. That is the task.

## 3. The design

### 3.1 The benchmark

`VarkaSizeLadderTuningBenchmark`, its own family and files, the ladder's rungs,
data and query exactly (`VarkaSizeLadderBenchmark`'s constants, shared rather
than copied), and vanilla arms only, each named by what it sets:

* `vanilla, defaults` - the reference, the ladder's own vanilla arm;
* `vanilla, hugeMethodLimit=8000`;
* `vanilla, wholeStage=false`.

`-XX:-DontCompileHugeMethods` is a JVM flag, not a session setting, so it cannot
be one arm among others in one JVM: it changes what every method of the run is
allowed. It is a second run of the same class under the flag, committed as a
companion file (`-dontcompilehugemethods-off-results.txt`), with the flag read
back from the running JVM through the diagnostic MXBean and written into the
file, so a file can never claim a flag its JVM did not have.

Each rung writes, as the ladder does, vanilla's largest generated method and
whether whole-stage codegen actually ran for the rung under each setting - read
from the executed plan, not assumed from the setting.

### 3.2 What it answers

Per rung and setting, time per row against the ladder's two arms. The claim the
post can make is read off it:

* if no setting brings vanilla within a factor of two of its own pre-cliff line
  above the cliff, *cannot* stands for the settings Spark offers;
* if one does, the claim becomes what it then is - a silent cliff in the
  default configuration, whose remedy is an internal setting or a JVM flag, at
  a measured price below the cliff - and the post says that instead.

Either way the Varka arm's advantage at every rung is the ladder's, and this
task adds no claim to it.

### 3.3 What is deliberately unchanged

The ladder's files and band (task 171), and the Varka arm: nothing here tunes
Varka.

## 4. Files

| file | what |
|---|---|
| `VarkaSizeLadderBenchmark.scala` | its rungs, data and query made shareable |
| `VarkaSizeLadderTuningBenchmark.scala` | the vanilla settings |
| `sql/core/benchmarks/VarkaSizeLadderTuningBenchmark-*` | both runs' files, and a band |
| `PLAN_MILESTONE_6.md` | row 192 |

## 5. Tests, and what each is for

* The benchmark refuses to time a setting whose plan does not show what the
  setting promises - whole-stage codegen present under the defaults at every
  rung, absent under `wholeStage=false`, and absent above the cliff under
  `hugeMethodLimit=8000` - so a setting silently ignored cannot be measured as
  a result.
* The flag run refuses to start unless the JVM reports `DontCompileHugeMethods`
  off.

## 6. The measurement

On a quiet machine, the class's default run and its flag run, then a band of the
default run. The runner repeats both for the published figure, as task 171's
ladder does.

### 6.1 Predictions, registered before the run

1. **`hugeMethodLimit=8000` removes the step and raises the line.** Above the
   cliff it runs without whole-stage codegen, compiled, at between one and a
   half and three times the defaults' pre-cliff cost per entry - the per-row
   interpretation of the plan around a compiled projection - so far below the
   interpreted defaults above the cliff and still well above Varka.
2. **`wholeStage=false` is that line at every rung**: slower than the defaults
   below the cliff, like the first setting above it.
3. **`-XX:-DontCompileHugeMethods` removes the step at little cost below it**,
   since it changes nothing there; above it the consume method is compiled by C2
   at between one and two times the defaults' pre-cliff cost per entry, the
   difference being what a 17 KB method costs C2 in register allocation.
4. **None of the three reaches Varka's line at any rung.** Varka's advantage
   below the cliff, several times over on this family in task 171's ladder, is
   not the cliff's and no setting touches it.

## 7. Risks

1. **The flag run changes Varka's JVM too.** Varka's methods are all under 8000
   bytes, so nothing of Varka's changes; the file carries no Varka arm anyway.
2. **A setting may change the plan in ways beyond the cliff** - without
   whole-stage codegen the scan and the projection are separate operators with
   rows between them. That is what the setting does, and it is the cost being
   measured, not a confound.

## 8. Sequencing

1. This plan and row 192, now, with no code: the machine is measuring task 171.
2. The benchmark and both runs, when the machine is free.
3. The answer in 9, and the post's claim worded from it.

## 9. Outcome

### 9.1 The laptop's runs, 24 September 2026

Both runs on the laptop, pinned to its fast cores, one after the other
(`VarkaSizeLadderTuningBenchmark-jdk25-results.txt` and
`-dontcompilehugemethods-off-results.txt`, each with its provenance file).
Every rung passed the check in 5: the defaults ran whole-stage everywhere,
`wholeStage=false` had no whole-stage stage, and `hugeMethodLimit=8000` ran
per-operator exactly from 54 entries on.

**The answer: *cannot* does not stand, and the claim becomes a silent cliff in
the default configuration.** Each of Spark's settings removes most of the step.
Where the defaults go from 886.2 ns a row at 52 entries to 4177.2 at 54,
`hugeMethodLimit=8000` goes from 879.8 to 1029.9 and `wholeStage=false` from
979.5 to 1028.8. The flag run's defaults, compiled past the limit, go from 781.7
to 812.2 with no step at all. What Varka keeps is its line: none of the
settings comes near it. The ladder's Varka arm, measured on the same laptop the
same day, is 70.6 ns at 54 entries against the best tuned vanilla's 812.2, and
122.8 at a hundred against 1872.4. So the post can say three things, and must
say all of them: the cliff is real and silent under the defaults; its remedies
are an internal setting whose default is the value its own doc advises against,
or a JVM flag, or whole-stage codegen off, and each has a price below the cliff
or a limit above it; and Varka is an order of magnitude faster than vanilla
under any of them, which is Varka's advantage and not the cliff's.

**Prediction 1 held on the step and failed on the price, in vanilla's favour.**
Past the limit `hugeMethodLimit=8000` costs about 19 ns an entry (1029.9 at 54,
1872.4 at a hundred) against the defaults' 17 below it (886.2 at 52): about 1.1
times, not the one and a half to three registered. Below the limit it changes
nothing, as it should (288.0 and 284.3 at sixteen).

**Prediction 2 held.** `wholeStage=false` is the same line as
`hugeMethodLimit=8000` above the cliff (1028.8 against 1029.9 at 54 entries,
1868.5 against 1872.4 at a hundred), and about a tenth slower than the defaults
below it (314.2 against 288.0 at sixteen, 979.5 against 886.2 at 52), which is
the per-operator path's price where whole-stage codegen worked.

**Prediction 3 held to 80 entries and failed at a hundred.** Under the flag the
defaults cost the same per entry above the limit as below it (812.2 at 54, 1193.6
at 80), so C2 compiles the 13 KB method well. At a hundred the step comes back:
7999.6, the defaults' interpreted cost (7923.5 in the default run). A forked
probe under `-Xbatch -XX:+PrintCompilation -XX:-DontCompileHugeMethods` shows
C1 giving up on the method at both sizes ("out of virtual registers in LIR
generator") and C2 starting on it at both. Why the benchmark stays slow at a hundred
is not established; row 201 is to find out from the JVM's own output. Until then
the flag is a remedy only up to some size between 80 and a hundred entries.
The flag run's defaults below the cliff are faster than the default run's (259.8
against 288.0 at sixteen), but they are two JVMs and there is no band yet, so
that difference is not read as the flag's.

**Prediction 4 held**, as above.

**Two departures from the design, both on the side of checking more.** The
plan said whether whole-stage codegen ran would be read from the executed plan.
But the plan cannot tell: past `hugeMethodLimit` the whole-stage node stays in
it and executes its child instead, so the benchmark reads the stage's
`pipelineTime` metric, which only the compiled pipeline updates. And the flag
run does not refuse to start without the flag; it reads the flag from the
diagnostic MXBean and names its file after it, so a run without the flag
cannot write the flag's file.

What remains: the band of the default run, in a quiet window; the runner's two
runs for the published figure, as for task 171; and row 201.

### 9.2 The JVM's own account, 24 September 2026: rows 201 and 196

**Row 201: at a hundred entries C2 runs out of nodes.** The flag run repeated
under `-XX:+PrintCompilation -XX:+LogCompilation`, in the fork's own benchmark
JVM. At every size past the limit, C1 gives up on the consume method first
("out of virtual registers", in the LIR generator or in linear scan) and hands
it to C2. At 64 and 80 entries (10184 and 13272 bytes) C2 compiles it, which is
why those rungs cost the same per entry as below the limit. At a hundred (17132
bytes) C2 fails too, both times the rung plans the query:

    project_doConsume_0$ (17132 bytes)   COMPILE SKIPPED: out of nodes during split

This is C2's node budget, `MaxNodeLimit`, which it can run out of while
splitting live ranges during register allocation. With C1 and C2 both failed,
the method stays interpreted, and the rung runs at the defaults' interpreted
cost. So the flag moves the cliff rather than removing it: past a size that
depends on the method's shape, the JIT's own budgets stop it, and nothing is
logged except by `PrintCompilation`. The forked probe of 9.1 had C2 start on the
method but did not show it failing; a compile that starts is not one that
succeeds. Whether raising `MaxNodeLimit` as well removes this step is not
tested; it would be a second JVM tuning flag, which is the point.

The size is not the only factor. The same query on stock Spark 4.2.0 under the
flag (below) generates an 18709-byte method at a hundred entries, and there C2
compiles it. The node count depends on what the bytes do, not only on how many
there are.

**Row 196: the cliff holds on JDK 17, 21 and 25, on stock Spark.** The fork's classes are
built for Java 25 (class-file version 69), so the fork cannot run on JDK 17.
The question is about the Spark users run anyway, so it was asked of the stock
Spark 4.2.0 distribution, the one the date surface compares against. The query is
the ladder's, over two million generated dates rather than the Arrow cache,
through `spark-shell --master local[1]` with `-XX:+PrintCompilation`:

| entries | consume method | JDK 17 | JDK 21 | JDK 25 |
|---:|---:|---|---|---|
| 44 | 7285 bytes | not run | not run | compiled |
| 48 | 7945 bytes | compiled | compiled | compiled |
| 52 | 8677 bytes | never compiled | never compiled | never compiled |
| 54 | 9095 bytes | never compiled | never compiled | never compiled |

"Never compiled" means the method does not appear in the compile log at all,
as for the fork's defaults in `VarkaSizeLadderJitSuite`. The generated bytes
are the same on all three JDKs, since Janino, not the JDK, writes them.

**Stock Spark crosses a little earlier than the fork.** Its consume method is
8677 bytes at 52 entries, where the fork's is 7868, so stock 4.2.0 crosses
between 48 and 52 entries and the fork between 52 and 54. The fork tracks
Spark master, and its codegen differs. The post compares against the Spark a
reader downloads, so it should name the crossing it quotes, and the ladder's
vanilla arm is the fork's; row 194 now carries a stock arm.

### 9.3 The runners' runs, 24 September 2026

Both runs through `.github/workflows/benchmark.yml` at `eafc3e0e340`, each file
beside the laptop's with its provenance. They drew different machines: the
default run an Intel Xeon Platinum 8573C
(`VarkaSizeLadderTuningBenchmark-jdk25-runner-results.txt`), the flag run the
AMD EPYC 9V45 (`-dontcompilehugemethods-off-runner-results.txt`). Every arm is
vanilla Spark's scalar code, so the datapath's width does not bear on them,
but the two files are two machines: each is read against its own defaults arm,
not against the other.

**The laptop's reading holds on both.** On the Xeon the defaults step from
1241.4 ns a row at 52 entries to 4650.0 at 54, while `hugeMethodLimit=8000`
goes from 1239.7 to 1562.3 and `wholeStage=false` from 1485.8 to 1561.1. Past
the limit the tuned line costs about 1.2 times the defaults' pre-cliff cost per
entry (1562.3 at 54, 2860.7 at a hundred, against 1241.4 at 52), a little more
than the laptop's 1.1. `wholeStage=false` costs a fifth below the cliff here
(1485.8 against 1241.4 at 52).

**The flag's second cliff reproduces on the 9V45.** Under
`-XX:-DontCompileHugeMethods` the defaults run with no step through the limit
(1093.5 at 52, 1141.1 at 54, 1681.3 at 80) and return to the interpreted cost at
a hundred entries: 9542.1, against 2496.5 for `hugeMethodLimit=8000` in the same
file. That is 9.2's C2 failure, on a second machine and CPU vendor.

**Varka against the best tuned vanilla, on one machine.** The 9V45 carries both
this flag run and task 171's runner ladder. At 54 entries the best vanilla
setting there is the flag's compiled defaults, 1141.1 ns a row, against Varka's
69.6; at a hundred it is `hugeMethodLimit=8000`, 2496.5 against 111.4. So on the
published machine, tuned vanilla is 16 and 22 times slower.

What remains: the band of the laptop's default run.
