# VARKA-94: a CI job for the bench module's tests

*Milestone 5, section 2.29. Opened 12 September 2026 by the review of VARKA-62's
chains PR; started 15 September 2026 as milestone 5's first task.*

## 1. Where this came from

`sql/varka/bench` holds the benchmark drivers and their suites - `SurfaceTest`,
`ChainsTest`, `PlanCheckTest`, `ProvenanceTest` and the two harness tests - and
nothing in CI runs them. All three call sites of `bench/pom.xml` under `dev/` and
`.github/` pass `-DskipTests`.

What that leaves unenforced is not decoration. `ChainsTest` is where the chain
list's invariants live - every entry fuses, every entry carries a date, an int and
an interval column, every entry clears `MIN_OPS`, no entry duplicates the surface -
and `SurfaceTest` is where the surface's do. An edit that breaks any of them merges
green. The review that opened this found two already weakened, which is the shape
of the problem: the tests were right when written and nothing would have said when
they stopped being.

## 2. The admission check, done

**The defect is two defects, and the second one is the opposite of the first.**
Section 2.29 records that bench tests never run. Checking `modules.py` shows why,
and shows the other half:

    sql/varka/bench/src/main/java/.../Surface.java  ->  []
    sql/varka/engine/src/main/java/X.java           ->  ['varka-engine']

No module claims the bench sources. `determine_modules_for_files` is documented to
put a file no module claims into **`root`**, and `root` exists to run every test
there is. So a pull request that touches only `sql/varka/bench` today runs the
entire Spark matrix - every sql, pyspark, connect and streaming job - and does not
run the bench module's own tests. Both halves are wrong and they are the same
missing entry.

That is exactly the failure `varka_engine`'s own comment in `modules.py` records
for the engine ("a change to a kernel - production code - matched nothing and fell
through to `root`"), which makes it the worked example for the fix as well as for
the job.

**The job is cheap, which decides its shape.** `bench/pom.xml` declares
`<spark.version>4.2.0</spark.version>` at `provided` scope, so the module compiles
and tests against the *released* Spark from Maven Central rather than against this
checkout. The suites start `local[1]` sessions and assert inventory invariants -
that every entry parses, runs, returns one column and a thousand rows, carries the
columns it claims and clears `MIN_OPS` - not that Varka fuses them. So the job
needs a JDK and Maven and nothing else: no `build/sbt package`, no engine jar, no
assembly. `dev/varka_gate.sh`'s `bench` step runs it locally in about ten seconds.

This is also why one architecture is enough, where `varka-engine` needs two: that
job runs hand-written Vector API kernels whose lane counts differ between x86_64
and aarch64, and this one runs no kernels at all.

## 3. The design

### 3.1 The mechanism

Three edits, each the `varka-engine` shape:

1. **`dev/sparktestsupport/modules.py`** gains a `varka_bench` module: name
   `varka-bench`, `source_file_regexes=["sql/varka/bench/"]`, no dependencies and
   no sbt goals, because its tests run under Maven in their own job. Nothing
   depends on it - the bench module is a leaf, and a change to it should test it
   and nothing else, which is the half of the fix that *removes* work.
2. **`.github/workflows/build_and_test.yml`**: a `varka_bench=` line in the
   precondition's block beside `varka_engine=`, a `"varka-bench"` entry in the
   emitted JSON, and a `varka-bench` job gated on it.
3. The job itself: checkout, JDK, `./build/mvn -f sql/varka/bench/pom.xml test`.

### 3.2 What is deliberately unchanged

The three `-DskipTests` call sites stay as they are. The surface workflow builds
the driver jar to *run a benchmark* and has no business running a test suite on
the way; `dev/varka_bench_surface.sh` likewise. The gate's `bench` step also stays:
it is the fast local answer, and this task is about the machine that does not
forget rather than about replacing the one a person runs.

### 3.3 Registered op counts

Not applicable: no IR or emitter change.

## 4. Files

* `dev/sparktestsupport/modules.py` - the module entry.
* `.github/workflows/build_and_test.yml` - the precondition line, the JSON entry,
  the job.

## 5. Tests, and what each is for

The change is CI configuration, so its own test is the doctest-style check that
`modules.py` already supports plus a direct query of the mapping:

* `sql/varka/bench/...` must map to `['varka-bench']` and no longer to `[]`, which
  is what stops it reaching `root`.
* `sql/varka/engine/...` must still map to `['varka-engine']`, so the new regex
  has not widened over its neighbour.
* `python dev/is-changed.py -m varka-bench` must answer `true` for a commit that
  touches the module and `false` for one that does not.

## 6. The measurement

None. No committed number moves; this task adds no code to any measured path.

### 6.1 Predictions, registered before the run

* The new job runs in **under five minutes** on a cold Maven cache, since it
  resolves released Spark artifacts and runs `local[1]` suites that take ten
  seconds locally.
* A pull request touching only `sql/varka/bench` currently triggers `root`. After
  this change it triggers `varka-bench` alone, so the number of jobs such a PR
  runs falls from the whole matrix to one.

## 7. Risks

* **The regex catching more than the module.** `sql/varka/bench/` is a directory
  prefix and the engine lives at `sql/varka/engine/`, so they cannot overlap; the
  test in section 5 asserts it rather than assuming it.
* **The released-Spark dependency drifting.** The module pins `4.2.0`. If a future
  bench source needs an API only this fork has, the module stops building against
  the release and this job is where that surfaces - which is the point of having
  it, not a risk of it.

## 8. Sequencing

1. The module entry, with the mapping checked locally.
2. The workflow wiring.
3. `./build/mvn -f sql/varka/bench/pom.xml test` locally, so the job is known to
   pass before CI is asked to run it.
4. The milestone row, and the plan's outcome once CI has run it once.

## 9. Outcome

*Implemented 15 September 2026; the job's first CI run is what closes this.*

**Both halves of section 2's finding are fixed by the one module entry.** A bench
source file now maps to `['varka-bench']` where it mapped to `[]`, and
`determine_modules_to_test` returns `varka-bench` alone with `root` nowhere in it.
So a pull request touching only this module goes from running the entire Spark
matrix and none of the module's tests, to running the module's tests and nothing
else.

**The suites pass and the job is as cheap as predicted.** `./build/mvn -f
sql/varka/bench/pom.xml test` runs **23 tests** across `PlanCheckTest` (5),
`ProvenanceTest` (2), `HarnessTimingTest` (2), `HarnessFormatTest` (2),
`SurfaceTest` (7) and `ChainsTest` (5), green, in about twenty seconds of which
`SurfaceTest` is eighteen - it is the only one that starts Spark sessions. Prediction
1 said under five minutes on a cold Maven cache; the local figure is the test time
alone and CI adds the resolve, which the job caches on `bench/pom.xml`'s hash.

**Checked rather than assumed**, per section 5: the bench prefix maps to
`varka-bench` and the engine prefix still maps to `varka-engine`, so the new regex
has not widened over its neighbour; `modules.py`'s doctests pass; and the workflow
parses as YAML with the new job present and gated on the new precondition key.

**The job has now run, on this task's own pull request, and it passed in 101
seconds** - every step green including `Run the bench module's tests`. That settles
prediction 1, which said under five minutes on a cold Maven cache.

It also corrects an expectation recorded while the pull request was open: that the
job would be *skipped* on its own PR, since nothing under `sql/varka/bench/` changed
here. It ran, and the reason is in `determine_modules_for_files`' own docstring - the
`.github` directory is counted when running in GitHub Actions, so a change to the
workflow is a `root` change and everything is required. Worth knowing in both
directions: a workflow edit exercises every job, and a job whose first proof comes
from a PR that touches `.github` has not yet been proven to be *skipped* when it
should be. That second half is what the next bench-only change will show.

**Corrected 16 September 2026, from VARKA-106's documents-only run.** The "second
half" above cannot be shown on this fork as its precondition is written: it diffs
the checked-out tree against apache/spark master, so every pull request's changed
set is the fork's whole delta and every module gate answers true. A bench-only
change will run the bench job, and everything else too, until VARKA-123 makes the
precondition measure the pull request's own files (`VARKA-106.md` 9,
`m5/PLAN.md` 2.58).
