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

<!-- Filled in when the work lands. -->
