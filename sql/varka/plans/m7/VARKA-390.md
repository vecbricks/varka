# VARKA-390: Retry a listed flaky suite in a fresh JVM, and say so on the run

## 1. Where this came from

The Build of #921 on 18 October 2026 went red in one job, "Varka suites: sql": the two tests of
`VarkaWarmupEndToEndSuite` that VARKA-295 was opened on released at their deadline, on an
Int256Vector runner. The failure is the one `VARKA-389.md` 9.5 traced with the tiered policy's
own events: on a four-core host a template's first compile fires after the warm-up's rounds and
lands at tier 2 when C2's queue is over ten entries at that instant, after which the kernel that
inlines the template keeps a virtual call and boxes. It is a coin toss per JVM, master and the
fix alike (nine runs on the laptop's four cores: four passed every test, five released the same
two), and its fix is scope item 103's, a row of its own. Until that row lands, every pull request
that draws a 4-core AVX2 runner for that job can go red, and the owner asked that CI mark the
flaky suites and run them again when they fail rather than have a human read the log and press
rerun.

## 2. The admission check, done

**Where the suite runs.** `dev/varka_scoped_suites.sh` runs each module's Varka suites in two
steps: the answer suites over two JVMs, then the suites that measure the JIT rather than answers
(`VarkaAssemblySuite` and `VarkaTierWitnessSuite` in catalyst, `VarkaWarmupEndToEndSuite` in
sql) alone, in a quiet JVM of their own, without the memory sanitizer (VARKA-295.md 9.2). Both
steps go through `dev/varka_matrix.sh`, which leaves a JUnit report per suite under
`<out>/runs/defaults/<module>/junit/TEST-<class>.xml` and exits 1 when any suite failed.

**Why a retry inside the JVM cannot work.** ScalaTest's `Retryable` tag and `SparkFunSuite`'s
`testRetry` run the test body again in the same JVM. What fails here is the JVM's compiled code:
the template's profile froze at the tier-2 compile and stays frozen, so a second warm-up of the
same shape in that JVM meets the same virtual call. Only a new JVM draws the coin again, and
only a suite that already runs alone can be given one without changing what it measures.

**What CI shows today.** A failed job with "Process completed with exit code 1" as its one
annotation; the test names are in the job log. A human reads the log, recognises the suite, and
presses rerun: 17:53 to 17:55 on 18 October, for a run that had been green everywhere else.

## 3. The design

A list in `dev/varka_scoped_suites.sh`, beside the suites that run alone, of the suites CI may
give a fresh JVM, each entry a simple name, the reason, and where the fix is tracked:

    flaky=(
      "VarkaWarmupEndToEndSuite: on a four-core 256-bit host a template's first compile ...
       (m7/VARKA-389.md 9.5, m9 SCOPE item 103)"
    )
    retries=2

The quiet step then runs until it passes or the retries are spent: after a failed run the
script reads the JUnit reports for the suites with a failed or errored test; if every one of
them is in the list, it prints a GitHub warning annotation naming the suites, the attempt and
the failed tests, and runs those suites again, alone, in a new JVM and a new directory
(`target/varka-scoped-quiet-retry<n>`). A failed suite outside the list fails the job at once,
with its name; a failure that left no JUnit report (a crashed JVM) fails the job at once; and
the retries spent fail it with a line saying in how many JVMs the suite failed. Two retries,
three JVMs in all: at the coin's measured rate of about one pass in two, three JVMs pass about
seven runs in eight, and a suite that fails three fresh JVMs in a row is worth a human's look.

The list is the marker the owner asked for: it is where a reader finds what CI tolerates and
why, every entry points at the row that removes it, and the annotation on the run makes a pass
that needed a retry visible on the pull request's checks page, so a flake cannot quietly become
the norm. The runner is taken from `VARKA_MATRIX` when set, which is how the flow is tested
without a JVM.

## 4. Files

- `dev/varka_scoped_suites.sh`: the list, the retry loop and its three helpers (the failed
  suites and tests from the JUnit reports, the list membership), the header's paragraph.
- `sql/varka/plans/m7/PLAN.md`: row 390.

## 5. Tests, and what each is for

*19 October 2026: the stub runner below was not committed, so the six scenarios cannot be rerun
from the tree (`m7/PLAN.md` 12.2); `m9/SCOPE.md` item 112 commits one beside the script.*

A stub runner in place of `dev/varka_matrix.sh` (`VARKA_MATRIX`), writing the JUnit reports a
run would leave and exiting as a scenario says, drives the script through six runs: a pass (one
quiet run, no annotation); a failure then a pass (two runs, one annotation naming the suite and
the test); two failures then a pass (three runs, two annotations); three failures (exit 1 after
three runs); a failure in a suite not in the list (exit 1 after one run, no retry); a failure
without a JUnit report (exit 1 after one run). All six behave as listed. The stub writes the
report as ScalaTest does, with the `<testsuite` tag's counts on the line after the tag: the
first version of the parser looked for them on the tag's line, the stub of that version did
the same, and the pull request's own first run, a real release on an Int256Vector runner, took
the "no JUnit report" exit instead of retrying; the parser now looks anywhere in the file, where
no other element carries those attributes. The real run is the pull request's own "Varka
suites: sql" job, since a change to this script runs the scoped jobs.

## 6. The measurement

A retry costs one more quiet run of the failed suites: about a minute when the suite passes,
about five when it releases both warm-ups at their two-minute deadlines, on a 4-core runner. The
job's other step is not repeated. What it buys is the reruns a human would otherwise press, one
per red draw, each a full round trip of the person's attention.

## 7. Risks

- **A retry hides a real regression.** Only listed suites are retried, each with the reason that
  admits it, and every retry is a warning annotation on the run; a suite that becomes red on
  every attempt still fails. A regression in a listed suite would show as a run of annotations
  across pull requests, which is the signal to look.
- **The list outlives its reason.** Each entry names the row that removes it; the row's close
  deletes the entry, and the annotation count on recent runs says whether it still fires.

## 8. Sequencing

One pull request: the script and this record. Row 391 or scope item 103's row removes the entry
when the warm-up's first compiles land at tier 3 on four cores.

## 9. Outcome

18 October 2026: in. The stub scenarios of section 5 pass; the pull request's own run of the
"Varka suites: sql" job is the first real draw, and its annotations, if any, are the first
measurement of how often the retry is needed.
