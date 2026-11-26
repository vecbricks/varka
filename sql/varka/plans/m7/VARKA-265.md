# VARKA-265: How well the tests test: branch coverage and a bounded mutation run

## 1. Where this came from

Row 265 of `m7/PLAN.md`, from the plan review (2.2, "How well the tests test") and `m7/READING.md`
6 and 11. Nothing in the record measured how well the suites test the emitter: no branch coverage
of the emitter's own classes and no mutation testing. Druid fails a pull request on changed lines
under 50% covered and none of the thirteen engines READING.md 6 surveyed mutates at all; Petrovic
and Ivankovic ("State of Mutation Testing at Google", ICSE-SEIP 2018) grew a mutant's usefulness
from 20 to 80 per cent by turning every "not useful" into a rule (pp. 2-6), and READING.md 11 adds
the Varka-specific trap: a mutant that only the emitted-bytes oracle kills changed bytes and no
answer, so it is not a behavioural kill. The row asks for branch coverage of the emitter and the
compiler under the suites and fuzzers first, then a bounded PIT run on `Slots` and `Analysis` in an
idle window, PIT reaching the ScalaTest suites test by test with `NON_VOID_METHOD_CALLS`,
`REMOVE_CONDITIONALS` and the bitwise mutators beside its defaults, three verdicts per mutant, and
each survivor answered by a test or an equivalence note.

It builds on two finished rows. VARKA-285 runs every Varka suite under the JaCoCo agent and reports
the classes the emitter emits; its agent already instruments the emitter's package, so the
emitter's own coverage is a second report over the same execution file, not a second run. VARKA-280
(in review as #861, this branch's base) lists the 39 mechanisms the emitter can apply to a shape
and commits what the IR fuzzer and the coverage suite reached (`sql/varka/coverage/reach/`); the
coverage report reads those files and says, per mechanism, whether the line recording it ran under
all the suites.

The owner set the phasing on 16 October 2026: the plan and branch coverage first; the PIT setup
built and verified on a tiny scope while the laptop is loaded by other agents (load average about
30 on 24 cores), with the full run left as a command for the owner's idle window; and the owner's
rule on tools - name the publisher and coordinates of PIT and any plugin, official Maven Central
artifacts only, and stop to report if the ScalaTest integration needs a third-party plugin.

## 2. The admission check, done

Three things had to be true before building the reports: that PIT reaches the ScalaTest suites
test by test with nothing but official artifacts, that a mutant run means what it says despite the
emitter's caches, and that the bytes oracle's verdict separates from a behavioural one. All three
were checked on 16 October 2026 on the laptop, pinned to cores 12-23 beside three other agents.

**Provenance.** PIT is `org.pitest:pitest`, `org.pitest:pitest-entry` and
`org.pitest:pitest-command-line`, version 1.30.0 (released 27 August 2026; 1.30.1 of 14 October was
two days old and passed over), published by the pitest project (pitest.org, Henry Coles) on Maven
Central, Apache-2.0, fetched by `build/mvn dependency:get` into the local Maven repository as
`dev/varka_gen_coverage.sh` fetches JaCoCo. Their runtime dependencies are Maven Central artifacts
too: ASM 9.10.1 (OW2), `jopt-simple` 4.9 and the HTML report (shaded into the command-line jar),
`stringtemplate` 3.2.1 (pulled by the HTML report, unused since the run writes XML and CSV), and
Commons Text and Lang, which the launcher takes from Spark's own test classpath (1.15.0 and 3.21.0)
rather than a second copy. No build file gains a dependency. **No third-party plugin**: PIT ships
JUnit 4 support only, and the Varka suites are ScalaTest, so the ScalaTest integration is written
here against PIT's SPI (`dev/varka_pit/VarkaPitScalaTest.java`, about 220 lines), as is the filter
that restricts the second run (`dev/varka_pit/VarkaPitOnly.java`). The JUnit 5 `@VarkaSuite`
classes would need `org.pitest:pitest-junit5-plugin` 1.2.3 (official, the same publisher, May 2025),
whose compatibility with the JUnit 6.0.3 Spark uses is unverified; those suites test the method
names, the platform, the range rules and reproduction files, not `Slots` or `Analysis`, so they are
left out of this run (3.2) rather than the plugin added.

**The mutators.** The free 1.30.0 jar lists 22 mutators. `NON_VOID_METHOD_CALLS` and
`REMOVE_CONDITIONALS` are there. No mutator named for bitwise operators is: the bitwise and shift
swaps (`&` to `|`, `|` to `&`, `^` to `&`, `<<` and `>>` and `>>>` exchanged) are part of `MATH`,
which is one of the defaults, so "the bitwise mutators beside its defaults" is met by the defaults
themselves. A dedicated bitwise operator set exists only outside the free artifact, and is not
used.

**The probe.** `dev/varka_pit.sh --classes Analysis --methods planBitmapPass,pureOf --suites
VarkaEmitterValiditySuite --threads 2`: two methods of `Analysis` and their constructor, 108
mutants, one behavioural suite of 24 tests, then the bytes oracle.

* Test by test works: PIT's coverage phase ran the 24 tests one unit each in 15 s and attributed
  each kill to its test by name (`VarkaEmitterValiditySuite.the served and declined root counts,
  per shape` killed most of `planBitmapPass`'s mutants). The suite is an own-JVM suite, run inline
  with `-Dvarka.ownJvm=true`, since a child JVM would load the unmutated classes.
* Caches: every unit empties the shape cache before its test (`VarkaShapeCache.invalidateAll`). A
  kernel cached by an earlier test was emitted by the code before the mutant was swapped in, and
  would hide the mutant or blame it for another's bytes.
* The verdicts separate: 60 killed by the behavioural suite, 45 killed only by the bytes oracle, 3
  survived. With one behavioural suite of thirty the bytes-only count is high by construction; it
  shows the oracle kills what the behavioural tests miss, and so that reading its kills as
  behavioural would have scored this probe 97% where the behavioural score is 56%.
* Cost: the behavioural run took 76 s, the bytes oracle's 743 s at two threads with load average
  about 25: its pinned-shapes test alone took 48 s in the coverage phase, and every mutant it
  covers reruns it. Hence `VarkaPitOnly`: the second run gets only the mutants the first did not
  kill (48 of the 108 here), which `dev/varka_pit_verdicts.py --keep` writes.
* `git status` after the run: `emitted_bytes.json` and every other committed file unchanged.

What the check would have rejected: a ScalaTest integration that needed a third-party plugin
(stop and report), a test unit that could not run one test alone or reset the emitter's cache
between tests, or a bytes oracle that killed nothing the behavioural suites
miss, which would make the second verdict empty and the second run pure cost. None of the three.

## 3. The design

### 3.1 Branch coverage of the emitter and the compiler

`dev/varka_gen_coverage.sh` gains the compiler's classes in the agent's includes - `codegen.Varka*`,
`codegen.CompiledVarka*`, `codegen.PartialVarka*` beside the `codegen.varka.*` it already had - and
a second analyser, `dev/varka_gen_coverage/VarkaEmitterCoverage.java`, run on the same execution
file against catalyst's built classes. The emitted-class report is unaffected: VARKA-285's
analyser keeps only classes carrying a Varka line map, which no source class has. The new report,
`sql/varka/coverage/emitter.md`, gives:

* per area (emitter, compiler) and per source file: methods, methods entered, instructions and
  branches covered, branches missed;
* per mechanism of `VarkaEmitMechanism`, whether the code that records it ran under all the suites,
  beside whether VARKA-280's reach reports saw it. A mechanism is read where it really happens: a
  `reach(...)` call, where a call choosing between two mechanisms ran both only if its condition
  took both branches; a size counter of `VarkaEmitTrace`, paired with its mechanism by the order of
  `counters()` and `COUNTED`, at the lines that increment it; and a guard's reason through
  `VarkaEmitMechanism.guard`, where the report says the guards' line ran and not which reason;
* every line of `Slots` and `Analysis` with a branch no suite took, with its source: the mutation
  run's survivors on these lines are expected, and a survivor elsewhere had a test reach it;
* the methods never entered, per file.

A class whose name has execution data under another class id ran under other bytes than the built
ones, and is listed as stale rather than counted as missed.

### 3.2 The mutation run

`dev/varka_pit.sh` builds catalyst's test classes (or reuses them), compiles the two plugin classes
against the suites' own ScalaTest and PIT, and runs PIT twice over the same mutants of the named
classes (default `Slots,Analysis`, and their nested classes):

1. **Behavioural**: every eligible catalyst ScalaTest suite except `VarkaEmittedBytesSuite`.
2. **Bytes**: `VarkaEmittedBytesSuite` alone, over the mutants the first run did not kill.

`dev/varka_pit_verdicts.py` joins them by PIT's mutant identity (class, method, descriptor,
mutator, instruction indexes, all from the bytecode) into the three verdicts and writes the report:
counts per class and per mutator, and every mutant no behavioural test kills, with its answer from
`sql/varka/mutation/notes.tsv` - the test that now kills it, or why it changes no behaviour - or
"open". A timeout or a crashed minion is a kill, as PIT counts it, and the report counts them apart.

The suites' JVM options are sbt's, plus the memory sanitizer and both bodies checked as in the
matrix, the IR fuzzer at 30 iterations a lane (`-Dvarka.fuzz.iterations`, a tenth of its default, as
every mutant it covers reruns it) and own-JVM suites inline. Spark's sbt exports catalyst as a jar,
so the classpath gives way to the classes directory PIT mutates. Left out of both runs, each with
its reason in the script: the suites whose verdict is the JIT's or the machine's (assembly, tier
witness, inlining cliff, the deoptimization cycle, compilation watch, both warm-up suites), the
tooling checks (watchdog, probe output, the matrix's machinery), and the cost audit, which compares
a committed file as the bytes oracle does. `--methods` restricts the mutants for a probe; PIT
filters methods only by exclusion, so every other method name the classes have is excluded.

### 3.3 What is deliberately unchanged

* The emitter and the compiler: no byte moves, `emitted_bytes.json` untouched.
* `VarkaSparkFuzz*.scala` (row 298's) and the sql/core suites: PIT runs catalyst only, since a
  sql/core suite's `beforeAll` builds a Spark session, which a test-by-test run would build once a
  test; the coverage run does include sql/core, as VARKA-285's does.
* A diff-coverage gate on pull requests (Druid's, READING.md 6) and mutation runs on changed lines
  stay with `m10/SCOPE.md`'s "Mutation runs on each pull request's changed lines".

### 3.4 Registered op counts

None: no emitter change.

## 4. Files

| file | what |
|---|---|
| `dev/varka_gen_coverage.sh` | the compiler's classes in the includes; the second report |
| `dev/varka_gen_coverage/VarkaEmitterCoverage.java` | new: the emitter and compiler report |
| `sql/varka/coverage/emitter.md` | new, generated |
| `sql/varka/coverage/generated.md` | regenerated from the same run |
| `dev/varka_pit.sh` | new: the two PIT runs and the verdicts |
| `dev/varka_pit/VarkaPitScalaTest.java` | new: PIT's test plugin for ScalaTest, test by test |
| `dev/varka_pit/VarkaPitOnly.java` | new: the second run's mutant filter |
| `dev/varka_pit_verdicts.py` | new: the three verdicts, the keep list, the notes |
| `sql/varka/mutation/notes.tsv` | new: the answers to the survivors |
| `sql/varka/skills/testing-and-debugging.md` | the lesson, and the calibration lines |
| `SKILLS.md` | regenerated index |

## 5. Tests, and what each is for

The tools are checked by running them, as VARKA-285's was: the probe of section 2 end to end (two
runs and the verdicts), the coverage report over a two-suite smoke run before the full one (it
caught a guard mechanism reported from the helper's line instead of its own), and the full
coverage run. The verdict script refuses a second run whose mutants are not exactly the first run's
unkilled ones, and a note that matches no open mutant. New tests come from the survivors, each
named in `notes.tsv`.

## 6. The measurement

The coverage run is `dev/varka_gen_coverage.sh -j 4 --split 3` on this branch, beside other agents'
load. The full mutation run is the owner's, in an idle window, with the command and the expected
duration of 9; its dry run here gives the mutant count and the coverage phase's time.

### 6.1 Predictions, registered before the run

1. **Emitter branch coverage 82%, within five points**, over every Varka suite; the compiler's
   80% within five. The two-suite smoke run gave 60.3% and 70.0%, and the fuzzers reach far more.
2. **`Analysis` 90% or more of its branches, `Slots` 85% or more**: both run for every emission,
   and what they miss is a validation's refusal and a rare layout.
3. **The constant pool's decline is the one mechanism whose recording code runs under no suite**;
   the plan's decline and the method-cap refusal, which the reach reports never saw, do run under
   some other suite (the smoke run already shows both).
4. **Under 40 emitter methods never entered.**
5. **Slots and Analysis give 2,000 to 3,500 mutants** under the three mutator groups (the probe's
   two methods and constructor gave 108 over about 110 lines).
6. **Verdicts of the full run: 80% or more behavioural, 8 to 15% bytes only, 10% or less survived**
   (uncovered included), and `Slots`' bytes-only share above `Analysis`', since a slot planned for a
   node that never reads it shifts every later local and changes no answer (`Slots`' class
   comment).
7. **Cost of the full run**: stated in 9 from the dry run's coverage phase, at the calibration
   table's ratio for harness-bound work (about 1.3 to 2 over the arithmetic).

## 7. Risks

1. **A cache other than the shape cache** keeps a kernel or a plan across tests, and hides
   mutants. The probe's kills across tests that share shapes argue against; a survivor whose line
   the coverage shows run by a test that should see it is the sign, and the notes read for it.
2. **A test flaky under PIT** fails in the coverage phase and stops the run. PIT's
   `skipFailingTests` is left off on purpose, so a flake stops the run rather than silently
   dropping a test; the exclusions take out the suites whose verdict is the machine's.
3. **Timeouts read as kills.** A mutant that loops forever is killed, and one that only slows a
   test past the timeout is reported killed too; the report counts timeouts apart.
4. **The bytes oracle kills an equivalent mutant**, which is why its verdict is read as a survivor;
   the notes say which bytes-only mutants are equivalent.

## 8. Sequencing

1. The plan, with the admission check (this commit).
2. The coverage tool and report: the includes, the analyser, the regenerated reports.
3. The PIT setup: the plugins, the runner, the verdicts, the dry run's numbers and the idle-window
   command in 9.
4. The full run (owner, idle window), then the notes and the tests that answer its survivors.

## 9. Outcome

### 9.1 Branch coverage

`dev/varka_gen_coverage.sh -j 4 --split 3` on 16 October 2026, cores 12-23 beside three other
agents: 102 suites in 1268 s, 1352 tests passed, 34 cancelled, one failed - `VarkaTierWitnessSuite`,
which asserts the JIT tier of a call and saw C2's code trap on the memory sanitizer's check under
the load; a machine verdict, not the emitter's, and the coverage it reached is counted. From
`sql/varka/coverage/emitter.md`:

| area | methods | entered | branches |
| :--- | ---: | ---: | ---: |
| emitter (`codegen.varka`) | 868 | 847 | 90.0% (392 missed) |
| compiler (`codegen.Varka*`) | 217 | 217 | 83.7% (179 missed) |

Methods are those a reader can name: lambda bodies count with the code, not as methods (the first
render counted them, 917 and 324, which the review of 9.4 corrected).

`Analysis` takes 96.2% of its branches (14 missed, on 12 lines, five of them validation
refusals a well-formed shape never meets) and `Slots` 92.9% (40 missed, on 24 lines). The
constant pool's decline is the one mechanism whose recording code no suite runs. The plan's
decline and the method-cap refusal, which VARKA-280's reach reports never saw, do run under other
suites.

**Correction to section 4**: `generated.md` is not committed regenerated. The same run moved it
from 14,745 emitted classes to 61,599 and shows families VARKA-284's table names no reason for -
`VarkaFusedFuzzTier`, and `VarkaFusedProjection_w<hash>` classes that `VarkaGenCoverage.family`
does not fold, since their suffix starts with `w` - which is row 284's and 285's to read, not this
row's.

### 9.2 The mutation run, set up

The dry run over the full scope (`dev/varka_pit.sh --dry-run`): **1,744 mutants**, 1,006 in `Slots`
and 738 in `Analysis`; the coverage phase ran 588 tests of 40 suites one unit each in 330 s, with
the IR fuzzer's C2 test off. The first dry run had it on, and it alone took 482 s of 869; the same
run stopped on `VarkaWidthAuditSuite`, which fails under PIT's agent and is now left out.

A sample over all 40 suites (`--classes Analysis --methods planBitmapPass,pureOf`):
`sql/varka/mutation/sample.md`, 104 mutants, 103 killed by a behavioural test, none by the bytes
oracle alone, one survivor, answered in `notes.tsv` as equivalent (`planWordAlgebra` keeps every
`Cond` node out of `pureWord`, so `planBitmapPass`'s `instanceof Cond` test is redundant). The
probe's other survivor, javac's `requireNonNull` on `pureOf`'s pattern switch, is killed here: a
narrow run's survivor is not an answer. A killed mutant ran one test at the median; the survivor
ran 405. The run took 320 s of coverage and 493 s of mutants, serially, since PIT made one unit
of the class - hence `--unit-size` (default 20).

**The command** (16 October 2026: on GitHub's runners, at the owner's request; 9.7 has the
design, the memory and the time), dispatched from any checkout:

    gh workflow run varka-mutation.yml -R MaxGekk/spark --ref varka-265 \
      -f shards=8 -f threads=3 -f heap=3g -f timeout-factor=4

The report is the run's `varka-mutation-verdicts` artifact, `sql/varka/mutation/verdicts.md`,
committed by hand once read. **The alternative, the owner's laptop in an idle window**, from
`/home/max/proj/varka-265`:

    dev/varka_pit.sh --threads 12 --report sql/varka/mutation/verdicts.md \
      > target/varka-pit.log 2>&1

Twelve minions of 4 GB each. **Expected duration on the laptop about two hours, and stop to read it past
four**: the build and the coverage phase about 8 minutes; the killed mutants, about 1,400 at about
2 s, 5 minutes over twelve threads; the mutants the first run leaves, about 300 by prediction 6,
each rerunning its covering tests, about 400. That last cost is inferred, not timed: the sample's
mutant phase took 493 s for 104 mutants under load, 103 of them killed after one test at the
median, so its one survivor took most of it, about 250 s, and perhaps 150 s on an idle machine -
60 to 105 minutes over twelve threads. The second run on those - the bytes oracle's about 14 s a
mutant and the `PinsDefaults` tests' few seconds - takes under 15 minutes. The estimate is the
arithmetic times the calibration table's ratio for harness-bound work, up to 2, which is the four
hours; what decides it is the number the first run leaves, which the report states.

### 9.3 The predictions, scored

1. Emitter branch coverage 82% within five: **missed**, 90.0%; the compiler's 80% within five:
   **held**, 83.7%. The fuzzers and the matrix base reach more of the emitter than the smoke run's
   two suites suggested.
2. `Analysis` 90% or more, `Slots` 85% or more: **held**, 96.2% and 92.9%.
3. The constant pool's decline the only mechanism no suite reaches: **held**.
4. Under 40 emitter methods never entered: **held**, 21 - among them two `Slots.plan` overloads
   with no caller in the main or test tree, dead code whose mutants are uncovered by construction.
5. 2,000 to 3,500 mutants: **missed**, 1,744; the probe's two methods are denser in calls than the
   classes on average.
6. and 7. The full run's verdicts and duration: open, the run on GitHub's runners (9.7).

### 9.4 The review

`/code-review high` on the three commits found nine things, all verified and fixed:

* **Structural tests counted as behavioural.** Seventeen suites hold tests tagged `PinsDefaults` -
  a grouping, an op count, a build count pinned at the defaults, `VarkaEmitCostSuite`'s and
  `VarkaKernelPlanSuite`'s against `emit_cost_audit.json` among them - and a mutant they alone
  kill changed what the emitter emits, not an answer. **Correction to 3.2**: the first run now
  takes the untagged tests, and the second the bytes oracle with every `PinsDefaults` test, both
  read as "the bytes oracle" in the report. The sample of 9.2 ran before the split, as its opening
  line says. Suites that read `coverage.json` (`VarkaGroupingBoundSuite`, the composition fuzzer)
  read it as their corpus, not as a pin, and stay behavioural.
* **A suite status that failed with no event** - an `afterAll` that throws - passed the unit; the
  unit now fails on `Status.succeeds()`.
* **Notes keyed on the line and the first index** went stale on any edit above them, and a note
  naming the test that now kills its mutant failed the next run. Notes are now keyed on PIT's
  bytecode identity (method descriptor and indexes) with the line for the reader, and a note is
  stale only when its method was mutated and no mutant, killed or not, matches it.
* **`--methods` dropped a method's lambdas**, which javap lists as methods of their own; a
  `lambda$<method>$<n>` now goes with its method.
* In the coverage analyser: a missing `Slots` or `Analysis` gave an empty "never taken" table that
  read as full coverage, and now fails; stale classes now fail the run as well as being named; the
  method counts left lambdas in while the list left them out (prediction 4's count moved from 22
  to 21); a choice that ran one arm is reported as unknown for both its mechanisms, never as
  reached (no mechanism is in that state in this run); and percentages render in `Locale.ROOT`.

### 9.5 Left for later

* The full run's survivors, each a test or a note, after the owner's run. Answered (9.8).
* The two dead `Slots.plan` overloads: delete them, or leave their mutants as uncovered by
  construction; an owner's call, since it touches the emitter. Deleted (9.6).
* `generated.md`'s new families (9.1), for rows 284 and 285. Handed to them (9.6).

### 9.6 The owner's decisions, 16 October 2026

The owner answered 9.5 and the open tooling question. **Correction to 3.3**: the emitter is no
longer untouched - item 1 deletes code from `Slots` - though no byte moves.

1. **Delete the two dead `Slots.plan` overloads.** Done: the six- and seven-argument forms, which
   only forwarded to the eight-argument one that both callers in `VarkaBodyEmitter` use, are gone,
   and their documentation merges into the remaining method's. The proof: `VarkaEmittedBytesSuite`
   passes, so `emitted_bytes.json` is byte-identical, and so do the suites whose tests name `Slots`
   or its per-group planning (`VarkaEmitterBudgetSuite`, `VarkaUnreadLocalsSuite`,
   `VarkaEmitterFramesSuite`, `VarkaSmallTreeSuite`), `VarkaIrFuzzSuite` at its default size and
   `VarkaEmitterValiditySuite`: 83 tests passed, 3 opt-in tests cancelled as by default.
2. **Skip `org.pitest:pitest-junit5-plugin` for this run.** Its publisher is fine (the pitest
   project, the same as PIT's, on Maven Central), but its compatibility with the JUnit 6.0.3 Spark
   uses is unverified, and the JUnit 5 `@VarkaSuite` classes test the method names, the platform,
   the range rules and the reproduction files, not `Slots` or `Analysis`, so the run loses no test
   of its two classes by leaving them out (section 2).
3. **`generated.md`'s jump from 14,745 emitted classes to 61,599 is a note for rows 284 and 285,
   not a new row.** Handed to them: `VARKA-284.md` and `VARKA-285.md` each end with a dated line
   naming the families 9.1 lists.
4. **The full mutation run waits for the owner's idle window**; 9.2's command is unchanged.

**Correction to 9.2's mutant count.** The dry run again, on an idle machine after the deletion:
**1,760 mutants**, 1,006 in `Slots` and 754 in `Analysis`; its whole PIT step took 194 s, against
9.2's 330 s for the coverage phase alone under load. The
deletion removed 4 mutants from `Slots` (each overload's forwarding call and its null return), as a
dry run of `Slots` alone before the deletion shows (1,010); the other 20 come from the master merge
of VARKA-314, which reworked `Analysis`' and `Slots`' invariant failures into Spark's internal
errors. 9.2's estimate stands, since the count it scales with moved by under one per cent.

**The coverage report, re-rendered** from a coverage run on this commit (`dev/varka_gen_coverage.sh`
at its defaults, 103 suites in 528 s; the same `VarkaTierWitnessSuite` trap as 9.1, under the
memory sanitizer, the one failure): the two overloads leave the list of methods never entered, and
every `Slots` line of the "never taken" table after them moves up ten. The emitter now counts 873
methods, 853 entered, 90.1% of its branches; the compiler 218 methods, 83.7%; `Analysis` 96.2% and
`Slots` 92.9%, unchanged. The method counts moved with the master merge (`VarkaErrors` grew from 5
methods to 12, and its `illegalState` is never entered), so prediction 4's count is now 20, not 21.
The re-render found the report broken by the same merge: `VarkaDebugInfo`'s read-side mapper now
throws through `VarkaErrors`, whose Spark exception lives in `common/utils`, and the analysers
failed to load it. `dev/varka_gen_coverage.sh` now puts `common/utils`' classes on their classpath.
`generated.md` stays as committed, as 9.1 says.

### 9.7 The full run on GitHub's runners, 16 October 2026

The owner asked on 16 October 2026 for the full run to go to CI instead of waiting for an idle
window on the laptop. **Correction to 9.6 item 4**: 9.2's command is now the workflow's dispatch,
with the laptop's kept as the alternative.

`.github/workflows/varka-mutation.yml`, dispatch only, runs N shards (default 8), one 4-core,
16 GB runner each, then one job that joins them:

* **The split.** `dev/varka_pit.sh --shard K/N` (K from zero, as `dev/varka_matrix.sh` counts)
  hands PIT `-Dvarka.pit.shard=K/N`, and `dev/varka_pit/VarkaPitOnly` keeps the mutants at
  positions K, K + N, ... of each class's mutants sorted by PIT's identity, the class's offset
  from its name's hash. Even in count by construction - over the 1,010 mutants of the last `Slots`
  dry run, 8 shards hold 125 to 127, where a hash of each mutant held 101 to 144 - and, since a
  method's mutants sort together, a method's survivors, which are what a run spends its time on
  (9.2), are dealt across the shards rather than landing on one.
* **A shard** runs both PIT runs over its slice, the bytes oracle's on that slice's survivors, and
  uploads the two `mutations.xml`, its logs, its scope and a `shard` file naming K/N. It writes no
  report: a note in `notes.tsv` on a method whose mutant fell to another shard would read as stale.
* **The join**, `dev/varka_pit_verdicts.py --shards DIR REPORT`, refuses a missing or doubled shard,
  shards that ran different scopes and a mutant in two shards, then writes the report an unsharded
  run over the same scope writes, with "Run in N shards." after its scope. It uploads
  `sql/varka/mutation/verdicts.md` as an artifact and commits nothing. A shard that fails skips
  the join; rerun the failed jobs and the join reruns after them.

**Memory.** Three minions (`threads=3`), each at `-Xmx3g` (`--heap`, which replaces both of sbt's
`-Xmx4g`): 9 GB of heap, up to 3,900 MB of metaspace at its 1,300 MB cap, 384 MB of code cache, and
PIT's own JVM, under the runner's 16 GB with a little to spare. Three at sbt's 4 GB would not fit.
The coverage phase runs every covering test in one JVM at the same heap, so a coverage phase that
passes shows the unmutated tests fit in 3 GB; a mutant that then runs out counts as a kill
(`MEMORY_ERROR`), in the report's "of them timed out or crashed" column.

**Timeouts.** A runner core is about half the laptop's (the Z3 proof that takes 28 s on the laptop
took 59 s on a runner, `skills/testing-and-debugging.md`) and its neighbours vary, so the timeout
factor is 4 against the laptop's 2 (`--timeout-factor`), with PIT's constant of 60 s kept. A
`TIMED_OUT` mutant counts as killed, so the same column is where a runner's slowness would show,
and a shard with many there is read before it is believed.

**Expected time per shard at 8 shards, about two hours (100 to 165 minutes), and up to about four
and a half at the calibration ratio of 2 on the mutant phases; the cap is 330 minutes**, inside
GitHub's six hours with time to upload the logs:

| phase | per shard | from |
| :--- | ---: | :--- |
| build: engine install, catalyst's tests, PIT's fetch | 10 to 25 min | the nightly's same compile step, 222 and 299 s (`VARKA-255.md`); 25 min the brief's bound |
| coverage phase, once in each PIT run | 2 x 7 to 11 min | 194 to 330 s on the laptop (9.2, 9.6), times 2 |
| killed mutants, about 175 at 2 s | 4 min | 9.2, times 2, over 3 threads |
| survivors of the first run, about 38 at 150 to 250 s | 60 to 105 min | 9.2, times 2, over 3 threads |
| the bytes oracle on those | 8 min | 9.2's 14 s a mutant and the `PinsDefaults` tests, times 2 |

The survivors' line decides it, as it does on the laptop. A shard past
four hours is a reason to dispatch again with `shards=16`, which halves every line but the build
and the coverage phase.

**Two predictions for the first run on the runners**, to be scored with 6 and 7: 8. every shard
at 8 shards finishes in 100 to 165 minutes; 9. no shard's coverage phase fails for memory at 3 GB,
and under 1% of the mutants end `MEMORY_ERROR` or `TIMED_OUT`.


**Checked on the laptop, 16 October 2026**, through the commands the workflow runs, over
`--classes Analysis --methods pureOf`: the unsharded dry run has 86 mutants and shards 0/2 and 1/2
43 each, disjoint, their union the 86. Run for real at `--heap 3g --timeout-factor 4` beside each
other, shard 0 had all 43 killed (347 s, its coverage phase about 180 s of it) and shard 1 had 42,
its one survivor through the bytes run in 203 s; `--shards` joined them into 85 behavioural and 1
survived, and refused a missing shard, a doubled one and a mutant in two. The survivor is a
removed `requireNonNull` in `pureOf`, the kind 9.2 records as killed in the sample; why it lives
here, with both runs' tests passing, is not settled by this check, and the full run lists it.

### 9.8 The full run, and every mutant no behavioural test kills answered, 17 October 2026

The run is `varka-mutation.yml` run 38987633834 on vecbricks/varka at master 40e78684bc1, eight
shards. Joined, `sql/varka/mutation/verdicts.md`: **1,760 mutants, 1,504 killed by a behavioural
test (85%), 117 killed only by the bytes oracle (7%), 139 survived (8%), 42 of them with no
covering test.** Of the behavioural kills 12 timed out and 3 crashed (`RUN_ERROR`); none ran out of
memory. `Slots` is 836 of 1,006 behavioural, 92 bytes only, 78 survived; `Analysis` 668 of 754, 25
and 61. Since the run, master has changed `Slots` in comments only and `Analysis` not at all, so
every mutant's bytecode identity, and every note keyed on it, is the same at this branch's base.

**The answers.** Each of the 256 mutants no behavioural test kills has a line in
`sql/varka/mutation/notes.tsv`, and the report regenerated from the run's shard artifacts with
`dev/varka_pit_verdicts.py --shards` lists none as open:

| answer | mutants | what it says |
| :--- | ---: | :--- |
| killed by a test added or changed for it | 37 | the test, named |
| equivalent | 157 | why the mutated code cannot change what the kernel computes |
| structure only | 51 | why the bytes move and no answer can |
| message only | 11 | the mutated call builds an internal error's text, and the refusal throws the same exception |

The equivalents fall into five kinds: javac's `requireNonNull` on a pattern switch's selector,
which is never null (9); a check that cannot fire, or whose failure path the mutant changes
without its ever being taken - the two-reason refusal, the lane-mix refusal (every IR constructor
and `laneOf` refuse a mix first), the conversion refusal (`emitLanes` never bakes the one-lane
width `Divider` would need), the slot accessors, `assertWordAlgebraAgrees`, the null arm chain and
the three `propagate` arms of nodes that alias their child's word (61); a demand another rule
makes too - every `MakeDate`, `GuardedDay` and `GuardedRange` is a refusal whose word the guard
demands, a NULL-mode node's word is demanded from the root down, and a pick's operands are
demanded by its visit (22); a walk that loses only its stop at a node it has seen, over a DAG (13);
and state nobody reads, or reads only through `get` (52). The structure-only ones change which
locals a body plans, which segments it maps, or which loaded dates it visits and drops.

**What the tests found.** No wrong answer in the code: every gap was a test that did not exist,
five of them.

1. *Refusals tested at one bound only* (16 mutants). Nothing fed a negative column ordinal or
   literal slot, a range guard under `Integer.MIN_VALUE` at the int lane, or a constant division
   over a guard whose loose side is its lower one: the one guard-bound test used a symmetric
   guard, which no mutant of one endpoint could fail, and none used a guard from
   `Long.MIN_VALUE`. Nor did a test emit exactly `MAX_FUSED_NODES` ops. Added to
   `VarkaEmitterContractSuite`'s rejection test, `VarkaEmitterLongLaneSuite`'s int-bound test and
   `VarkaEmitterDivisionSuite`'s long-lane refusal test; three of the 16 are a refusal naming the
   value it refuses, which those tests now read.
2. *A filter in a group of its own over a loaded prefix* (6, four of them with no covering test).
   `epilogueReadsMask`'s last clause - an epilogue whose only masked access is a materialized
   prefix's load - was reached only in epilogues with nothing materialized, so the test inside it
   never ran. `VarkaEmitterChronoSuite`'s materialized-prefix test now has such a filter.
3. *Answers filed as a pin* (11, one with no covering test). `VarkaEmitterValiditySuite`'s "a group
   that loads a materialized prefix keeps no word for a guard it does not emit" checked the answers
   with the bitmap pass off, the only test that does with a loaded prefix, and carried
   `PinsDefaults` for the group split it asserts, so the run moved its answers to the bytes run
   and read their kills as the oracle's. Split: the answers untagged, the split a pinned test of
   its own.
4. *The form without a byte budget, under a prefix that would cross groups* (2). The four fields
   one per group now run without the budget, where materializing is refused, and with prefix
   sharing off, where the mutant that materializes anyway turns out structure only.
5. *A conditional's arms, emitted in the other order than planned* (2). The slots follow the
   children-first order, then arm before else arm, while `IfElse` emits the else arm first, so a
   local planned after a then-arm node's temporaries can be written before them. Two mutants that
   step the slot counter back after a node's last temporary - the magic-form long division's, and
   a dense range test's - put an else-arm local there; no test had such a shape.
   `VarkaEmitterFramesSuite` has a test of two now. The same mutant on the masked range test only
   overlaps that node's own result slots, written after its last read of the temporaries.

The word-writer test gained a root the driver writes beside a blend, where the planner's `-1`
fill matters; the mutant without it verifies and answers there, so it is a note, with that test as
its evidence.

**The review.** A reviewer agent read the diff and the notes, checked about fifty notes against
the code and found three things, all fixed. The first draft called the stepped-back slot counters
(item 5) structure only "by the children-first order", which `IfElse`'s emission order breaks; the
test it proposed kills two of the three. `--only` ended every restricted run in a failure, since
the report's stale-note check read notes on mutants the run had left out; that check now reads
the file's mutants only, the scope line says the run was restricted, a missing file is refused,
and a run that finds another number of mutants than the file names fails, since `VarkaPitOnly`
matches whole lines and a misspelt identity is otherwise dropped in silence. And three refusal
messages the new tests reach were left as message-only notes where an assertion of the refused
value costs nothing.

**What the run found about itself.** 87 of the 117 bytes-only kills are one test,
`VarkaExpressionCompilerSuite`'s "under planSize the compiler cuts a projection past the driver's
ceiling in one step" (`PinsDefaults`), and they include removed `requireNonNull` calls whose
selector is never null. Rerun on the laptop over `pureOf`, `ownerOf` and `collectRefusals` against
that suite at the runners' 3 GB, it killed the one in `collectRefusals` and let the two alike
through: under PIT it fails for reasons of its own, not the mutant's. The plugin records no
failure message, so why is not established; the test counts builds through the global
`VarkaShapeCache.buildCount`. Its kills are noise in the bytes-only column, and every mutant it
killed is answered on its own merits. A debt bullet in `m7/PLAN.md` section 10 holds it.

**Correction to 9.2 and 9.7.** The sample's "javac's `requireNonNull` on `pureOf`'s pattern
switch, killed here" was not a kill of that mutant: it is equivalent (`topoOrder` holds no null),
and in the sample the planSize test still ran with the behavioural tests, since `PinsDefaults`
tests left that run only after it (9.4). 9.7's open question - why it lived in the shard check - is
answered the same way. `skills/testing-and-debugging.md`'s bullet on it is corrected.

**Checking the new tests.** `dev/varka_pit.sh --only FILE` (new) mutates only the identities FILE
lists, as `--keep` writes them. Over the 51 mutants the first tests were written for or sit beside,
against the five changed suites, the behavioural run took 83 s and the bytes run 355 s on the
laptop; three smaller runs checked the review's additions. Together they confirmed the 37 kills
and the survivals the notes claim beside them - the word accumulators, materializing with sharing
off, the guard's upper endpoint, the predicates answering true, the masked range test's slot
counter and the canonical rendering in the division's refusal.

**The matrix.** The untagged answer test now runs in every configuration of the option matrix,
where its tag kept it out before. `dev/varka_matrix.sh --all` over the five suites changed before
the frames test was added passed every configuration but for one test none of this changes,
"inside a Spark task, the forms without a scratch address refuse a kernel with scratch", which
fails under `methodByteBudget=0` and `shareChronoPrefix=false` because neither materializes the
prefix it asserts, whatever this change does, and has no line in `sql/varka/matrix/skips.tsv`.

**Test time.** The five changed suites before the frames test was added ran in 55 s at the base and
53 s here, one sbt run each on the laptop: inside the noise. The frames test adds 1.2 s.

**The predictions of 6.1 and 9.7, scored.**

6. 80% or more behavioural: **held**, 85%. 8 to 15% bytes only: **missed** low, 7%, and 87 of
   those 117 are the planSize test's. 10% or less survived: **held**, 8%. `Slots`' bytes-only share
   above `Analysis`': **held**, 9% against 3%.
7. The laptop's duration: **not scored**; the run went to the runners (9.7).
8. Every shard at 8 shards in 100 to 165 minutes: **missed**, early - 63 to 106 minutes a job,
   median about 90. The coverage phase took 2 to 4 minutes, not 7 to 11; the behavioural run 35 to
   62 minutes with it; but the bytes run 23 to 42 minutes, not 8, since 9.7 priced the oracle alone
   and its run also carries every `PinsDefaults` test (9.4), the planSize bisection among them.
9. No coverage phase failed for memory, and under 1% of the mutants ended `MEMORY_ERROR` or
   `TIMED_OUT`: **held**, 12 timed out (0.7%), none for memory.

The calibration table gains prediction 8 (`skills/calibration/VARKA-265-9.7-8.md`).
