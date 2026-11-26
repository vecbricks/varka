# VARKA-248: `VarkaEmitOptions` from one table of options

*Row 248 of `m7/PLAN.md`, wave 0, from `m10/SCOPE.md` item 74.1 and `m7/READING.md` 3 and 11;
opened 4 October 2026, the second of milestone 7's refactors, after VARKA-249.*

## 1. Where this came from

`VarkaEmitOptions` is a record of 40 options - switches between two lowerings, size budgets, the
target's lane count and AVX level, fault injectors. A new option touches about nine places: the
record component, the positional `DEFAULTS` (a row of bare booleans), the compact constructor, the
builder's field and setter and copy, a `with*` method, `canonical()`, the bytes suite's option
inventory, and - through reflection over the `with*` methods - the IR fuzzer's draws and
`VarkaEmitDump`'s parser. Nothing records why an option exists, so VARKA-247's question, whether
each still earns its place, has to be asked from scratch.

## 2. The admission check, done

**What reads the options generically.** `canonical()` renders them into the shape hash: the first
26 by position, the 14 added since as tags, each tag shown when its option is true, false, or off
its default - `predictGrouping` and `planSize`, now on by default, still render when true, so a
non-default variant's string carries them. The bytes suite's inventory lists arms by hand and has
fallen behind (`rangeSets`, `splitConditions`, `groupLocalSlots`, `materializeChronoPrefix`,
`callSiteBudget` and `heavyGroupOutputs` are missing). The fuzzer reflects over the `with*`
methods in name order with a hand list of int domains; `VarkaEmitDump` reflects to parse
`name=value`. `VarkaShapeCacheSuite` reflects over the record's components on purpose, so that a
forgotten field fails it, and stays.

**Why each option exists**, read off its own javadoc, falls into five kinds: a reference form kept
for its A/B (most switches, the trunc and mod-7 lowerings); a winner that depends on the machine
(the division lowering, the AVX level, the lane count); a size knob that tests and benchmarks vary
(the four budgets); a check kept switchable so its cost can be priced, off being wrong
(`checkIntOverflow`, `guardDayProducers`); and a fault injector (the three `misdescribe`
options). None is retired today; an option VARKA-247 retires is removed, not marked.

**Scope.** The row also asks for the suites to run under the options' configurations with a
reasoned skip list, and for a shape marked as declining to fail when it starts to fuse. That is a
test harness of its own on top of the table, so it is this task's second step, its own pull request.

## 3. The design

### 3.1 Step 1: the table

`VarkaEmitOption`, a sealed interface of three records - `Flag` (a boolean), `Count` (an int) and
`Choice` (an enum) - each holding the option's name, its `Reason`, its default, its accessor on the
record and its setter on the builder, how it renders into `canonical()`, and for ints the values the
fuzzer draws and the inventory audits. `VarkaEmitOption.TABLE` lists all 40 in the record's
declaration order. From it:

* `DEFAULTS` stays a positional row, and a test checks each of its values against the table
  (see 9.1: building it from the table costs every executor about 17 ms at startup).
* `canonical()` joins the positional options and appends the tags, in table order.
* The bytes suite's inventory is every option's audit values: both values of a flag, every
  constant of a choice, a count's listed values.
* The IR fuzzer draws each option from its kind, in setter-name order, consuming the random stream
  as the reflection did.
* `VarkaEmitDump` parses `name=value` through the table.

The record, its validation, its builder and its `with*` methods stay: every caller keeps its API.
**Considered and set aside:** generating the record from the table (an annotation processor or a
source generator), more machinery than 40 entries need.

### 3.2 Step 2: the configuration matrix

The Varka suites rerun once per *configuration*: the defaults with one option changed, applied
to every kernel the suites emit - `cse=false`, `groupBudget=8`, `division=DOUBLE_DIV`,
`lanesOverride=4`. A reference form or a machine's alternative must give the defaults' answers
under every test, where today it meets only the few tests that set it. The idea is DuckDB's
`test/configs`, and the declining marker is Druid's `cannotVectorize` (`m7/READING.md` 3).

**3.2.1 The configurations.** Every non-default arm of every table entry but the fault
injectors: a flag's other value, a choice's other constants, a count's audit values, and
`lanesOverride` at 4 and 16. The table gives 43 arms today, plus the two lane counts: 45
configurations (28 reference arms, 6 machine, 9 knob, 2 priced checks). They are derived from
`VarkaEmitOption.TABLE` and named as `canonical` `name=value` pairs, so a new option joins the
matrix with its entry.

**3.2.2 Reaching every kernel.** One base value, `VarkaMatrix.base` in the catalyst test jar,
read from `-Dvarka.matrix.config=<name=value,...>` and `DEFAULTS` without it:

* the SQL suites: `VarkaColumnarToRowExec`'s existing test hook starts at the base instead of
  `DEFAULTS`, and its nine resets restore the base;
* the emitter suites: `VarkaEmitterTestBase`'s four defaulted parameters, and the test code's
  other `VarkaEmitOptions.DEFAULTS` uses that build a variant (`DEFAULTS.withX(...)`), start from
  the base - a mechanical change over about 300 sites;
* production code is unchanged: `DEFAULTS` stays a constant and no system property reaches it.

The alternative, `DEFAULTS` itself reading the property, is one line and reaches the defaulted
parameters in main code too, but it puts an emitter knob on every production JVM, which the
existing hook's comment keeps off the configuration surface on purpose. A matrix run counts, per
suite, the emissions whose options are not the base; the first run's counts name the paths the
base does not reach, and each is closed or listed.

**3.2.3 What stays out, and why.** Whole suites whose subject is the defaults or the machine, not
an answer: the bytes oracle and the cost audit (they compare with committed output of the
defaults), `VarkaEmitOptionSuite`, `VarkaMatrixSuite`, `VarkaAssemblySuite` (it reads the JIT's
output) and `VarkaWarmupEndToEndSuite` (it times a kernel's compilation, which a loaded machine
moves). The list is in the runner with a reason per suite.

Within the suites that run, a test whose subject is the defaults' emitted structure - registered
op counts, a `HugeMethodLimit` crossing, a loop-method count, bytes compared byte for byte, the
size at which a shape declines - carries the tag `PinsDefaults`, and the matrix cancels it under
any configuration: run there, it measures nothing new, and its failure would say only that a
configuration changed what it pins. The first laptop run (4 October 2026, 14 configurations)
showed such tests to be almost every failure.

**3.2.4 The skip list.** `sql/varka/matrix/skips.tsv`, one line per answer test a configuration
breaks by design: the configuration, the suite, the test, the kind and the reason. Kind `fails`:
the test is expected to fail, as a guard test does under `guardDayProducers=false` and an ANSI
overflow test under `checkIntOverflow=false`. `VarkaMatrixTests`, which `VarkaTestWatchdog`
extends and the suites without the watchdog mix in, runs a listed test, cancels it with the
reason if it fails, and fails it as a stale line if it passes.

**3.2.5 The declining marker.** A configuration that makes the emitter decline everything would
pass every SQL test vacuously: the row path answers instead. So the SQL sessions register a
query listener that sums each query's `numVarkaBatches` over its plan, the wrapper reads the sum
before and after each test, and the run records each test's fused batches; the defaults run is
configuration zero. (The shape cache's build count cannot serve: a shape built in an earlier
test is a cache hit in a later one.) A test that fused under the defaults and fuses nothing
under a configuration fails, unless the skip list marks it kind `declines` with a reason - a
single output over a small `methodByteBudget` declines by design (VARKA-87) - and a `declines`
entry whose test fuses fails as stale. VARKA-275, the guard on hidden kernel-failure fallbacks,
is the per-test half of the same concern and lands independently.

**3.2.6 Running it.** `dev/varka_matrix.sh [--config <name=value>]... [--all] [-j N]` builds once,
then runs each configuration's catalyst and SQL suites as two ScalaTest runner JVMs on the test
classpath sbt exports, N JVMs at a time, each in its own directory, so parallel runs share no sbt
lock, warehouse or temporary directory, and a shape a catalyst suite compiled is not warm when a
SQL suite asks for its first query. Each JVM writes a JUnit report and its tests' fused batches;
`dev/varka_matrix_report.py` prints one line per configuration and exits non-zero on any failure.

Measured on the laptop at ten JVMs, a configuration takes 15 to 18 minutes for catalyst and 22
to 27 for SQL, about 40 JVM-minutes, so the 45 are about 31 JVM-hours: three hours at ten at a
time, which the 60 W the laptop's USB-C supply negotiated does not quite cover (the battery fell
3.6 W net). Three places run it:

* **PR CI**, one configuration per pull request: the option the PR's files touch if there is one,
  otherwise `configurations[PR number mod 45]`, so a rerun draws the same one and consecutive PRs
  walk the list. A configuration that fails is rerun on the merge base; failing there too, the
  job reports it pre-existing rather than blaming the PR. Two jobs, catalyst and SQL, about 25
  minutes beside the existing ones.
* **Weekly**, every configuration: `varka-option-matrix.yml` on Sunday beside the weekly full
  Spark matrix, about 30 runner-hours on four-core runners, two configurations per job.
* **The laptop**, by hand: a PR that changes an option's code path runs its configurations with
  `--config`, in about one configuration's time.

**3.2.8 The CI pull request, planned 5 October 2026.** Two pieces, both on the runner script
and its report as they are, with two runner options added: `--module catalyst|sql` (one module's
JVMs only) and `--shard I/N` (every N-th configuration from the I-th, for the weekly jobs).

*The PR job.* The existing `varka-scoped` job in `build_and_test.yml` runs each module's Varka
suites under the defaults in sbt. It gains a step after that one, in the same two jobs: pick one
configuration, then `dev/varka_matrix.sh --module <m> --config <picked> -j 2`, which runs the
defaults and the configuration as two JVMs side by side on the runner's four cores. The defaults
run is needed again: the declining check compares each test's fused batches with it, and sbt's
run records none. Expected cost: 15 to 25 minutes more per job.

* **The pick**, `dev/varka_matrix_pick.py`: if the PR's diff against `VARKA_DIFF_BASE` names an
  option - its record component, its `with` method or its builder setter on an added or removed
  line - one of that option's configurations; otherwise a stable hash of the branch name modulo
  the configuration count. The branch name rather than the PR number, because the fork's push
  builds do not know their PR; a rerun or a later push draws the same configuration, and
  different branches spread over the list. The step summary names the pick and why.
* **The merge-base check**: when the configuration fails, the same step checks out
  `VARKA_DIFF_BASE` into a worktree, builds it and runs the same configuration and module there.
  If the same tests fail on the base, the job passes with a warning naming the configuration and
  the tests as pre-existing; otherwise it fails. This costs a second build and run, only on
  failure.

*The weekly workflow*, `varka-option-matrix.yml`: Sunday 03:00 UTC, after the weekly full Spark
matrix starts, on this project's repositories only, and by `workflow_dispatch`. Twelve jobs, each
`--shard I/12` of all 45 configurations for both modules, `-j 2`, with the defaults run in each
job for its comparison: about four configurations, roughly 80 to 100 minutes, a job. Each job
uploads its report and run logs, and its summary is the report's table. The first run is the
confirmation 9.2 defers to.

Not in this pull request: the option-to-files map 3.2.6 suggested; matching the diff on option
names covers the same intent without a list to keep in step.

**3.2.7 Done when.** All 45 configurations have run, every red test either tagged
`PinsDefaults`, fixed, or listed with its reason, and the weekly workflow's first run of all 45 is
green; the PR job runs one configuration with the
merge-base check; the weekly workflow runs all of them; `sql/varka/AGENTS.md` says a new option
gets its matrix arms from its table entry, a structure test its tag, and a broken answer test a
skip line with its reason.

### 3.3 What is deliberately unchanged

Every option, its default, its validation and its `with*` method; `canonical()`'s output for every
value; the emitted bytes; the fuzzer's draws for every seed; `pinnedArms`, the bytes oracle's
user-selectable arms.

### 3.4 Registered op counts

None: no emitted byte moves.

## 4. Files

`VarkaEmitOption.java` (new), `VarkaEmitOptions.java` (`DEFAULTS`, `canonical()`),
`VarkaEmittedBytesSuite.scala` (the inventory), `VarkaIrFuzzSuite.scala` (the draws),
`VarkaEmitDump.scala` (the parser), `VarkaEmitOptionSuite.scala` (new).

## 5. Tests, and what each is for

* `VarkaEmitOptionSuite`: the table names every record component once, in declaration order;
  `DEFAULTS` holds every option's default; each entry reads and sets the component it is named
  for and no other; initialising and rendering the defaults never loads the table; a flipped
  option and a changed count render as the committed renderings do; each reason kind is what the
  table says for the options the javadoc names.
* During development, not committed: the old and new `canonical()` over random option values, and
  the old and new fuzzer draws over many seeds, compared for equality before the old code goes.
* The bytes oracle and the cost audit, unchanged; the Varka suites through the gate.

## 6. The measurement

None.

### 6.1 Predictions, registered before the run

1. `emitted_bytes.json` and `emit_cost_audit.json` byte-identical.
2. The old and new `canonical()` agree on 100,000 random option values, and the old and new draws
   on 10,000 seeds.
3. Adding an option after this lands touches the record component, the builder, a `with*` method,
   the javadoc and one table entry: five places, where it was about nine.

## 7. Risks

1. **A rendering that drifts** would move non-default variants' shape hashes; the side-by-side
   comparison before the old code is deleted is what rules it out.
2. **A static initialisation cycle** between the table and `DEFAULTS`: the table holds method
   references only, so building `DEFAULTS` from it does not reenter the record's initialiser.

## 8. Sequencing

Step 1, one pull request. Step 2 in two: the base value, the wrapper's tag, skip list and counts,
the runner script and a green laptop run of all 45 configurations; then the PR job and the weekly
workflow.

## 9. Outcome

### 9.1 Step 1, the table, 4 October 2026

1. **Held.** `emitted_bytes.json` and `emit_cost_audit.json` are byte-identical.
2. **Held.** The table-built `DEFAULTS` equals the positional one; the table's `canonical()` agrees
   with the hand-written one on 100,000 random option values of 100,000; and the table's fuzzer
   draws agree with the reflection's on 10,000 seeds of 10,000, the random stream left at the same
   position after each, so every fixed seed draws what it drew. The comparisons ran with the old
   code beside the new and were deleted with it.
3. **Held, counting the builder as one place.** Adding an option is now the record component, the
   builder (its field, setter, copy in `toBuilder` and argument in `build`), its `with*` method, its
   javadoc, its value in `DEFAULTS` and one table entry; `canonical()`, the inventory, the fuzzer
   and the dump's parser follow from the entry. Before, each of those four was an edit too.

`dev/varka_gate.sh` passed every step. Two things the table needed that the plan did not foresee:
Scala cannot express a Java enum's bound, so `Choice` gained `withIndex` and `withNamed`, which
are what the fuzzer and the dump call; and the bytes suite's hand-written inventory had fallen six
options behind the record, which reading it off the table repairs. `VarkaEmitDump` was run end to
end with options of every kind and still refuses a boolean that is neither `true` nor `false`.

The review of the pull request changed four things. `DEFAULTS` went back to its positional row:
building it from the table linked the table's 80-odd method references when the record
initialised, which a fresh JVM timed at 17 to 26 ms (median about 19 ms over fourteen runs, with
and without lambdas linked beforehand) against 4 ms for the record itself, and every executor
would have paid it for options only tests vary. `VarkaEmitOptionSuite` checks the row against the
table and shows, in a class loader of its own, that the record and its rendered defaults never
load the table. A second test moves each entry off its default and checks that exactly the
component it is named for moved, which the defaults test could not see, since it reads each option
through its own entry; a deliberately miswired setter fails it. `lanesOverride` is a knob, not a
machine-dependent winner: production always uses the preferred width, and the override exists so
one JVM's suites can emit for every width.

### 9.2 Step 2, the matrix and its first runs, 4 and 5 October 2026

The base value, the `PinsDefaults` tag, the skip list, the fused-batch count and the runner landed
as 3.2 describes, and all 45 configurations ran on the laptop, in three sittings: 14 on the
evening of 4 October before the run was stopped for the battery, 21 overnight, and the remaining
13 on the morning of 5 October. **No configuration found a wrong answer.** Every failure was one
of three things:

* **A test pinning the defaults' structure** - registered op counts, a `HugeMethodLimit`
  crossing, a method count, the size a shape declines at: 79 tests now carry `PinsDefaults`.
* **A by-design break**, now one of 71 skip lines: a switched-off check computing what it exists
  to price (`checkIntOverflow`, `guardDayProducers`, `guardUnderArm`), or a reference form of the
  size machinery that cannot hold the wide shape a test builds (`methodByteBudget=0`,
  `driverOutputTable=false`, `splitDriver=false`, `severalKernels=false`,
  `splitConditions=false`).
* **A test that assumed the base was the defaults**: the 64-bit division test's conversion arm
  now keeps the default AVX level, and the IR fuzzer's reach check runs under the defaults only.

The declining marker earned its place: under `methodByteBudget=0` three SQL tests passed while
fusing nothing, the row path answering alone, and each turned out to be a designed decline past
the 64 distinct ops a kernel holds without the byte budget. Two apparent defects were the tests'
own: an Arrow "memory leaked" under `useAVX=2` was a failed assertion skipping a test helper's
cleanup, and "got 0, Java says 1250" past 2^52 was the magic division form answering in the shape
its lowering predicts, under an arm that assumed the conversion form.

`VarkaWarmupEndToEndSuite` left the matrix: it times a kernel's compilation, and at ten JVMs its
first-query tests read a kernel `RELEASED` where they wait for `COMPILED`.

Measured cost: a configuration is 15 to 18 minutes of catalyst and 22 to 27 of SQL at ten JVMs,
about 40 JVM-minutes, so the 45 are about 31 JVM-hours - 2.5 to 3 hours on the laptop at eight
or ten JVMs. The runner gained `--deadline` and a pause while the battery discharges below 30%,
after the 60 W the laptop's USB-C supply negotiated on 4 October did not cover ten JVMs.

Not rerun on the laptop: the configurations whose tags or skip lines were added after they ran.
Each line came from that configuration's own observed failure, so the confirmation is the weekly
workflow's first run in the second pull request, beside the PR job; a missing tag fails there,
and a stale skip line fails as stale.

### 9.3 Step 2's CI pull request, 5 October 2026

The PR job and the weekly workflow as 3.2.8 plans them, with the runner's `--module`, `--shard`
and `--sbt-arg`. `dev/varka_matrix_ci.sh catalyst` ran on the laptop as the PR job runs it: the
picker drew `materializeChronoPrefix=false` from the branch name's hash, since this branch's diff
names no option, and the defaults and that configuration passed side by side, two JVMs, in about
six minutes. The merge-base comparison was checked on two made-up reports: a failure the base
shares drops out, a new one is reported. The picker carries doctests, which the PR job runs before
every pick. The weekly workflow's first run, the confirmation 9.2 defers to, comes after merge.

### 9.4 The weekly workflow's first run, 5 October 2026

The first dispatch of `varka-option-matrix.yml` stopped on every shard about five minutes in
with "no classpath or JVM options": under CI sbt colours its output, and the runner read the
classpath and the JVM options off the raw log, which on the laptop is never coloured (#626 strips
the escape sequences first, as `varka-fuzz.yml` does; the PR step from #625 had the same defect
and had not run yet). The second dispatch, [run 37291764919](https://github.com/vecbricks/varka/actions/runs/37291764919),
passed: all 45 configurations in twelve shards of 35 to 60 minutes each, 47,464 tests passed, none
failed or aborted, and no test that fused under the defaults fused nothing under a configuration;
every shard's own defaults run passed 926. That is the confirmation 9.2 deferred to, so every tag
and skip line added after its configuration last ran holds.

The PR step's first run in CI was #626's own: both modules under `useAVX=0`, picked by the branch
name's hash, passed beside the defaults in about eight minutes for catalyst and thirteen for SQL,
under the fifteen to twenty-five 3.2.8 estimated. Its merge-base path has not yet met a failure.
