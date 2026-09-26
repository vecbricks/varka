# Task 179: The fuzzers as a standing job, and task 177's check

*Opened 25 September 2026, item 4 of `PLAN_MILESTONE_6.md` 9.2, which pairs
task 179 with task 177 because both are about what CI proves on its own.*

## 1. The question

Task 87 came out of a 35-million-iteration fuzz run, and the last campaign
before it was misconfigured against the suite's twenty-minute cap, so 47 of its
48 failures were timeouts carrying no information (`PLAN_MILESTONE_6.md` 2.8).
Nobody runs a campaign on purpose between tasks. And the campaign that exists
draws IR directly: `VarkaIrFuzzSuite` never sees the compiler admit anything,
so the milestone's acceptance, "a fuzz campaign that produces declines and no
emission failures", was reinterpreted in `PLAN_TASK_169.md` as the coverage
sweep. Two questions, then: **what runs every night without anyone starting
it, and what draws the path a wide query takes through the compiler?**

## 2. The change

### 2.1 The nightly

`.github/workflows/varka-fuzz.yml`, on a schedule at 02:00 UTC and on
dispatch, on this project's repositories and never on apache/spark. It builds
the catalyst tests once, exports their classpath, and runs the IR fuzzer as one
plain JVM per runner core, each with its own seed and log, waited on by process
id, which is the shape `testing-and-debugging.md` records as the one that
works ("A fuzz campaign is sixteen JVMs, not one sbt"). The seeds are the date
followed by the JVM's index, so every night visits new trees and a failure
replays with the seed and iteration the log names. Forty thousand iterations
per lane per JVM: at a runner's rate of about a hundred trees a second that is
under fifteen minutes for both lanes, inside the suite's twenty-minute cap per
test with room. Then the composition fuzzer of 2.2, once, with four hundred
compositions. The logs are kept for thirty days as an artifact, and the step
summary carries the seeds and the verdicts.

The suites' own defaults stay small, three hundred trees and forty
compositions, so a pull request pays seconds and the night pays minutes.

### 2.2 The coverage composition fuzzer

`VarkaCoverageCompositionFuzzSuite`, in catalyst's Varka test package. Each
iteration composes a projection of one to three hundred entries drawn at
random from the coverage table's projection rows, or a filter of one to
sixty-four of its predicate rows joined by `AND`, resolves them against the
table's own columns, and asks the compiler what the planner asks:
`compilePartial` and `declines` for a projection, `explainPredicate` for a
filter. The width is log-uniform, so most compositions are small and some are
very wide. Emit options alternate between the default width and four lanes,
the two the emitted-bytes oracle pins.

The property is the milestone's (`PLAN_MILESTONE_6.md` 1.3): every entry is
fused or declined with a reason; the compiler throws nothing; and since every
row fuses alone, a decline inside a composition can only be a size decline, so
its reason names the budget. The last clause is the sharp one. Task 188's
Varka arm found G17's shape declined for a reason nobody predicted, and this
suite is built to fail the same way: a decline for any other reason is a
finding, not noise.

### 2.3 What it does not draw

Nested random Catalyst trees, where a coverage row's argument is itself a
random expression; the table's rows compose side by side, not inside one
another. And nothing runs: the compositions are classified and emitted, not
executed against the row engine. Both are in section 7.

## 3. Predictions, registered before the run

1. **The first week of nightlies finds no emission failure in the IR fuzzer**:
   task 87 closed the only class the last campaign found, and 8 million trees
   per lane since then found nothing else (`testing-and-debugging.md`).
2. **The composition fuzzer's first four hundred compositions find at least
   one decline whose reason is not a budget**, because the coverage rows were
   each proven to fuse alone and never together, and the compiler has caps
   that count things other than bytes - the IN literal cap and the fused-node
   budget are two. If this prediction holds, the finding is a row.
3. **A runner does about a hundred IR trees a second per lane**, half the
   laptop's rate, so the nightly's fuzz step takes ten to fifteen minutes and
   the whole job under forty with a warm cache.

## 4. Verification

* The composition suite passes at its default forty compositions on the
  laptop, and its failure message for a composition names the seed, the
  iteration, the rows and the options, so a night's finding is replayable.
* The workflow's first dispatched run is green, or its failures are findings
  with replay lines, and its step summary shows the seeds and the counts.
* `varka-fuzz.yml` and `varka-weekly-matrix.yml` parse as YAML and pass the
  repository's linters.

## 5. Task 177: the check item 45 asked for, and what is left of the task

Item 45 of `SCOPE_MILESTONE_7.md` proposed a scoped CI path for refactors the
bytes oracle proves byte-identical, with one precondition to verify first:
that Spark's full matrix still runs somewhere after such a merge, because a
scoped pull request run is only safe if the matrix runs before a release.

**What is already built.** Task 160's scoped job runs the Varka suites of
catalyst and sql/core, in two parallel jobs of about twelve minutes each, for
every change confined to Varka's files. The catalyst job includes the
emitted-bytes oracle, `VarkaEmittedBytesSuite`, so every scoped run already
proves whether the emitter's output moved; the milestone review's statement
that `sql/varka/emitted_bytes.json` was "referenced by no workflow or script"
(`PLAN_MILESTONE_6.md` 9.1) was true of the file's name and wrong about the
guard, which the suite is. What was missing was the verdict being visible: a
reviewer had to open the diff to know whether the file moved. The scoped
catalyst job now prints it in its step summary after the suites pass,
"unchanged, so the oracle proves this change byte-identical" or the changed
lines.

**The precondition, checked: it is not met.** `build_main.yml` skips pushes to
master on forks, on purpose, so that syncing the fork does not rebuild; and
Spark's scheduled builds (`build_java25.yml`, `build_non_ansi.yml`,
`build_maven.yml`, `build_coverage.yml`) are gated on `apache/spark`. So on
this project's master nothing runs after a merge at all, and the last full
matrix a Varka-scoped change ever sees is the one of the last pull request that
touched a Spark file. The fork's most recent run on master is from July. That
is the case item 45 warned about, and it exists today independent of any
oracle path. `varka-weekly-matrix.yml` closes it: Spark's full matrix on
master every Sunday at 01:00 UTC, when no pull request is using the runner
slots, and on dispatch.

**The skip path is withdrawn.** With the scoped job in place, an oracle-proven
path could only skip the sql/core Varka suites, which run in parallel with the
catalyst job: twelve job-minutes saved and no wall time, at the price of a
green run that did not run the suites the change could reach through the
compiler. That trade is not worth a tag. Item 45's done-when is met by this
section: the precondition is checked and written down, and the item says why
the tag does not exist.

## 6. Outcome

**Task 177: done by section 5 on 25 September 2026.**

**The composition fuzzer's first run, 25 September 2026, on the laptop.**
Prediction 2 held on the first iteration of both tests: an entry that fuses
alone declined for "the LONG lane in a kernel on the INT lane: one kernel holds
one lane", in a projection of 68 rows mixing `extract(DAYOFWEEK_ISO FROM d)`
with `time_from_millis(time_to_millis(t2))`, and in a filter of three conjuncts
mixing `d >= d2` with a `TIME` comparison. It is not a new finding: the
compiler's own comment states the rule, `PLAN_TASK_29.md` pins it as a test,
and task 28's width conversion is what lifts it. So the suite admits that one
reason beside the budget reasons and fails on any other, and the first thing
the fuzzer did was confirm that the record's list of composition declines is
complete for its first forty draws. The point of prediction 2 was to learn the
list; it has two entries.

*Task 179 is done when the first scheduled run has reported; the nightly is
dispatched once by hand on the day this merges, and its verdict and rate are
recorded here against section 3.*

## 7. Explicitly out of this task

* A fuzzer over nested random Catalyst expressions, which needs a typed
  grammar of the compiler's admitted forms; the IR grammar is that for the
  emitter, and a compiler grammar is a task of its own.
* Running the compositions against the row engine as a differential; the
  composition suite classifies and emits, and `VarkaCoverageDifferentialSuite`
  runs the rows one at a time.
* Any change to the IR fuzzer's grammar or caps.
