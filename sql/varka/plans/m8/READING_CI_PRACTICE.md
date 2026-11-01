# Reading for the CI: what to run per change, and what to do with a flaky failure

Read on 29 October 2026 at the owner's request, after a day on which three flaky failures were
each rerun by hand (a Spark UI CSP Jest test at its 5 s timeout on a slow runner, a Kafka
real-time-mode streaming test timing out, and the known four-core JIT-timing failure of the
warm-up suites). It bears on rows 508 (a pull request's matrix reruns only what it moves) and 521
(the Build leaves the option configurations to the option matrix), on the weekly full matrix
(row 393) and on `m9/SCOPE.md` item 44 (a CI queue script). All three papers were read in full
from the copies below. The Google and FlakeFlagger copies print no licence, so
`sql/varka/papers/` keeps no transcription of them (its `README.md`, Licensing); the arXiv copy's
CC BY-NC-SA 4.0 may permit one, which is left for a separate decision. **[R]** means read in the
paper; **[I]** inferred here.

| paper | source | licence of the copy |
| :--- | :--- | :--- |
| Memon, Gao, Nguyen, Dhanda, Nickell, Siemborski, Micco: "Taming Google-Scale Continuous Testing" (ICSE-SEIP 2017) | Google Research, `https://research.google.com/pubs/archive/45861.pdf` | IEEE; the copy prints no licence |
| Machalica, Samylkin, Porth, Chandra: "Predictive Test Selection" (ICSE-SEIP 2019, Facebook) | arXiv 1810.05286v2, `https://arxiv.org/abs/1810.05286` | CC BY-NC-SA 4.0 (arXiv page) |
| Alshammari, Morris, Hilton, Bell: "FlakeFlagger: Predicting Flakiness Without Rerunning Tests" (ICSE 2021) | author preprint, `https://www.jonbell.net/preprint/icse21-flakeflagger.pdf` | IEEE; the copy prints no licence (the BSD licence it names is the tool's and dataset's) |

What Varka already has, for reading the sections against [R, from the tree]: a pull request's
Build runs Varka-scoped jobs when only Varka's files change and Spark's whole module matrix
otherwise; the option matrix is dispatched per pull request and also runs on Sunday at 03:00 UTC
(`varka-option-matrix.yml`); Spark's full module matrix runs on Sunday at 01:00 UTC
(`varka-weekly-matrix.yml`). `dev/varka_scoped_suites.sh` keeps a `flaky=` list (today one entry,
`VarkaWarmupEndToEndSuite`, with its reason and tracking item) whose suites, when they fail, are
rerun alone in a fresh JVM up to `retries=2` times, each retry announced as a `::warning`
annotation; a failure outside the list, or one without a JUnit report, fails the job at once. The
option matrix's bins retry a setup step (checkout, Java) once on failure or timeout and record the
runner's CPU and vector width. `sql/varka/matrix/skips.tsv` is not a flake list: its 234 entries
are tests that fail or decline by design under one configuration (186 `fails`, 48 `declines`),
and a listed test that stops failing fails as a stale line.

## 1. Google TAP: run less of what never fails, and run it later

**What it is [R].** An empirical study of TAP, the CI of most of Google's monorepo (about 2 billion
lines; 13K projects, 800K builds and 150 million test runs a day). TAP selects "affected" test
targets by reverse build dependencies from the changed files. It stopped testing each change on
its own because cost grew as submission rate times pool size; instead it cuts a *milestone*,
typically every 45 minutes at peak, and runs every target affected since the last milestone once,
at its latest affecting change. Milestones reached 4.2 million tests and delays of up to 9 hours.
The unit is a test target (a JUnit suite, one Python test or a script), not a test case, and the
authors had no per-test coverage or fault matrix, so their signals are build-graph and history
signals only.

**What it measured [R].** One month (11 February to 11 March 2016): over 500K changes, 5.5 million
affected targets, over 4 billion outcomes.

- Of 5,562,881 targets, 5,082,803 (91.3%) passed at least once and never failed; 115,160 (2.07%)
  both passed and failed, and of those 46,694 were flakes, leaving 68,466 (1.23%) that found a
  breakage or a fix. Per change, pass to fail was 99:1. "More than 99% of all tests run by the CI
  system pass or flake."
- Per change on average: 48.5% passed, 43.1% stayed AFFECTED (not run, saved by milestones),
  7.5% skipped, 0.42% failed.
- *MinDist*, the shortest dependency-graph distance from a changed file to a target: breakages
  and fixes with a known culprit all lay within 10. A simulation running only targets with
  MinDist <= 10 ran 61% of targets and saved 42% of resources; MinDist <= 6 ran 50% and saved 55%;
  neither missed a breakage or a fix in the data.
- Breakage correlates with how often a file changed (77% of files changed 42 times were in edge
  changes), with file type (.hpp 80%, .cc 60%, .java 40%), with some authors and release tools
  (one tool: 39 breakages in 42 commits), and with three or more authors on one file.

**On flakiness [R].** The paper does not describe how TAP detects or quarantines flakes; it
filters them from the data and says it is impossible to weed out all of them. Its one rule is a
negative one: "rerun tests that failed recently" was rejected as a selection heuristic "as we
would end up mostly re-running flaky tests".

**On safety and latency [R].** The results are stated as valid for post-submit TAP data only; the
authors list code review, pre-submit, global presubmit and post-submit as separate stages and say
MinDist = 10 may not generalize. Their proposals are scheduling ones: run a change to a
frequently modified file at once rather than waiting for a milestone, and schedule risky authors'
changes off-peak.

**What Varka takes from it [I].**

1. *Most runs are spent proving passes.* At 99:1 the question for Varka is not whether to skip
   but where the skipped work is caught. TAP's milestone is a batch run that catches it later;
   Varka's batch run is the Sunday matrices.
2. *Distance is a cheap selector, and Varka already uses its coarse form.* The scoped Build (Varka
   files only, Varka jobs only) is a MinDist cut at module granularity. Row 508 is the fine form.
3. *Do not select by recent failure.* With a flaky suite in the list, "rerun what failed last
   week" mostly reruns the warm-up suite. Failure history belongs to flake detection, not to
   selection, unless failures and flakes are recorded apart (section 2).

**What does not transfer [I].** TAP's numbers come from millions of targets and a deep build
graph; Varka has tens of suites in one module and a handful of Spark modules. The MinDist = 10
threshold has no meaning here. Milestone batching assumes many commits between runs; Varka merges
a few pull requests a day, so the batch that matters is a week's merges, not 45 minutes' worth.

## 2. Facebook: predictive selection, and retries that separate failed from flaked

**What it is [R].** A gradient-boosted tree classifier that scores each (change, test target)
pair, among the targets that build dependencies select, for the chance the target fails on the
change. Selected = targets above a score cutoff, plus the top N by score. Features that survived
ablation: file extensions, change history of the files (3, 14, 56 days), historical failure rate
of the target (7, 14, 28, 56 days), project name, number of tests in the target, minimal
dependency distance. Common path tokens and distinct authors made it worse (ratios 0.33 and 0.30
on the classification metric) and were dropped; failure rate (1.37) and minimal distance (1.23)
helped most. The model is retrained weekly on three months of results and replaced automatically
only if it passes an assertion such as SelectionRate < 0.3 at TestRecall 0.9; otherwise the
responsible team member is notified.

**The stages, and why selection is allowed to miss [R].** Diff-time tests each version of a
review; land-time tests again before the push to master, and rejects on failure; *stabilization*
runs all tests on master "once every few hours", and release candidates come only from versions
it passed. Diff- and land-time use an order of magnitude more machines than stabilization, and
the paper calls them a productivity spend, "not only non-essential" but unable to guarantee
quality alone (races between concurrent lands). That is the argument that lets selection be
unsafe: anything it misses is caught within hours.

**What it measured [R].** TestRecall > 0.95 and ChangeRecall > 0.999 at land time, selecting fewer
than a third of the dependency-selected targets; in production, test executions fell threefold
and machines twofold. Two top-scoring targets alone catch a failure on 70% of faulty changes.
Recall is measured on *learning runs*: on a sample of about a quarter of changes, every dependent
target is run, deferred to off-peak hours, and the result is never shown to developers.

**On flakiness [R].** In learning runs a failed target is rerun up to ten times or until it
passes. All attempts failed: *failed*. Some passed: *flaked*. Flaked targets were about four times
as many as failed ones, and the count is a lower bound, since retries do not catch every kind of
flakiness. A model trained without this split looked better than it was: at a selection rate of
0.15 it measured recall with flakes of about 0.9 against a true recall of about 0.7, "three times
as many test failures as expected" missed. The paper treats eradicating flakiness as not
economically viable, so the CI must "operate well in presence of flakiness".

**What Varka takes from it [I].**

1. *Record failed and flaked apart, per suite.* Varka's `flaky=` retry already makes the
   distinction at run time (a retry that passes is a flake) and annotates it, but the annotation
   is not counted anywhere. A suite's flake rate over weeks is the paper's strongest feature, and
   the only honest input to any later selection or quarantine.
2. *The per-pull-request option matrix is a learning run, not a land-time run.* It runs every
   configuration over every suite a configuration moves, which is what row 508 would measure its
   selection against. Once row 508 selects, keep a full matrix on a sample of pull requests (or
   on every merge to master, off-peak) to measure what selection misses, as the paper does.
3. *Gate the selector by an assertion.* If row 508 lands, its weekly check should state a recall
   floor against the full runs, and fall back to running everything when it is not met.

**What does not transfer [I].** Facebook's safety net is a full run every few hours; Varka's is
weekly. The paper's recall figures are acceptable only because a miss lives for hours; on Varka a
miss in a selected pull-request matrix can live up to a week, and through several merges, before
the Sunday matrix sees it. A learned model also needs volume (tens of thousands of changes a
week); Varka has a few changes a day and should use rules, with the measured rates as their check.

## 3. FlakeFlagger: how many reruns find a flake, and what predicts one

**What it is [R].** An empirical study and a classifier. The study ran the whole test suites of
24 Java projects 10,000 times each (over five years of compute), each run on a fresh Ubuntu VM with
4 CPUs and 8 GB, rebooted between runs, with no injected non-determinism and no variation of
platform or load. The classifier (a random forest, trained with SMOTE oversampling) predicts which
tests are flaky from features collected in one instrumented run, without reruns.

**What it measured [R].**

- 811 of 22,245 tests (3.6%) were flaky. Assuming independent failures, only about 26% of them
  would be found with 10 reruns, 45% with 100 and 67% with 1,000; over a third failed 10 times or
  fewer in 10,000.
- Flakes do not transfer between environments: of flakes reported on the same project revisions
  by earlier studies, the 10,000 reruns reproduced 10 of 20 (DeFlaker) and 28 of 70 (non-order-
  dependent iDFlakies), and the authors confirmed from logs that the missed ones were real.
- Classifier, 808 flaky tests: precision 60%, recall 74%, F1 66%, AUC 86%, against a
  vocabulary-based classifier at 11%, 72%, 19% and 75%. False positives 406 against 4,683 (table
  III; the contributions list says 380 and 4,674, and the counts of flaky tests differ, 808
  against 811, because 298 tests lacked features). It found none of the flakes in projects with
  fewer than four known ones.
- Features by information gain: execution time first, then source lines covered, source classes
  covered, lines covered, coverage of recently changed lines (several history windows), test
  lines of code, external libraries. Every test smell (fire and forget, mystery guest, resource
  optimism and five more) fell below the 0.01 threshold. The authors read the top features as
  test size and complexity: large, slow, broad tests flake.
- Its own misses: tests that failed on transient network errors were mostly predicted not flaky,
  because the network rarely failed in 10,000 runs; tests sharing a dependency that failed in one
  run were over-predicted.

**What Varka takes from it [I].**

1. *A rerun that passes proves a flake; a rerun that fails proves little.* At 26% detection in 10
   runs, Varka's two retries cannot certify a suite as not flaky, and a suite that left the
   `flaky=` list because its retries stopped firing for a few weeks may still be flaky.
2. *Execution time is the feature that applies.* Varka's warm-up suites, the Jest CSP test and the
   Kafka real-time test are all timing-bound: each asserts that something completes within a
   wall-clock budget. Coverage breadth and recent-change coverage would also rank the
   JIT-measuring suites high. A first, cheap Varka rule follows from it: a suite whose assertion
   depends on wall time, or on what the JIT did by a moment, is presumed flaky on a shared runner
   until a run history says otherwise.
3. *The environment is a feature the paper held fixed.* All three flakes of 29 October are load or
   machine dependent (a slow runner, a busy C2 queue on four cores), the class the paper's design
   undercounts and its features describe poorly, as with its network failures. For Varka the
   runner's CPU, core count and vector width, already recorded by the option matrix's bins, are
   the predictors to keep next to each failure.

**What does not transfer [I].** The model needs hundreds of labelled flakes and per-test
coverage; Varka has a handful of known flaky suites and would get zero recall, as the paper's
small projects did. Take the study's numbers and its feature ranking, not the classifier.

## For the rows

**(1) What to run per change, and the safety/latency trade [R, then I].** Google selects by
reverse dependency and batches into milestones about every 45 minutes; a distance cut of 10 would
have run 61% of targets for 42% less compute without a miss. Facebook runs under a third of the
dependency-selected targets per change (recall > 0.95 per test, > 0.999 per change) because every
test runs on master every few hours. Both put the guarantee in a post-submit full run and treat
pre-submit as speed for developers. For Varka: the scoped Build and a selected option matrix
(row 508) are pre-submit, and after row 521 the dispatched matrix is the only pre-submit check
of the configurations; the Sunday full Spark matrix and the Sunday option matrix are the
stabilization stage, at a weekly instead of an hourly cadence. The gap is that cadence, so before
row 508 removes work from a pull request's matrix, a full option matrix should run after every
merge to master, off-peak or chained after the merge's own runs (a learning run in Facebook's
sense), and row 508's selection should be checked against it with a stated recall floor. Row 393
(the weekly matrix green on master) is a precondition: a stabilization run that is red every week
does not catch what selection misses.

**(2) Flaky tests [R, then I].** Neither industrial paper describes a quarantine list; Google says
flakes cannot all be removed, Facebook that removing them is not economic, and both make the CI
tolerate them. Facebook's mechanism is retry until pass, up to ten, recording failed and flaked
apart; flaked outnumbered failed four to one. FlakeFlagger shows retries cannot prove a test
clean (26% found in 10 runs) and that size and execution time predict flakiness; timing-bound
tests on shared runners are exactly the class most exposed and least captured by its features.
Varka's `flaky=` retry in a fresh JVM, with a warning annotation, is already the recommended
mechanism for its own suites; what it lacks is (a) a count, (b) coverage of jobs outside the
scoped script (the Jest and Kafka failures were in Spark's matrix, rerun by hand), and (c) an
expiry, which `skips.tsv` has by design (a stale line fails) and `flaky=` does not.

**(3) Scheduling under a cap of 40 jobs [R, then I].** None of the papers works under a fixed
slot cap. The two scheduling ideas they give are Google's "run a target once, at its latest
affecting change" (a queued pull-request run superseded by a newer push can be cancelled rather
than finished) and Facebook's learning runs deferred to off-peak hours (the Sunday matrices
already do this; a per-merge full option matrix should too). Google's suggestion to run risky
changes first, not batched, maps to dispatching the option matrix first for pull requests that
touch the emitter or Spark files. Serializing runs to fit the 40 slots (item 44) is not addressed
by any of the three.

**(4) A small policy to adopt [I].**

1. *One automatic rerun of a failed job, recorded.* A workflow step (or a small `workflow_run`
   job) that, on a failed job in the Build or the option matrix, reruns that job once
   (`gh run rerun --failed`) and writes a line to a committed or uploaded log: run, job, test,
   runner CPU and cores, first and second outcome. A job that fails twice is a failure; one that
   passes on the rerun is a flake and stays an annotated warning, as `flaky=` does now. One rerun
   only: Facebook's ten retries were for labelling training data, and a second retry on a 40-slot
   fork costs a full job's slot.
2. *A flake list with expiry.* Extend the `flaky=` idea to named Spark-side tests: an entry is
   (suite or test, reason, tracking item, added date, expiry date, 30 days). An expired entry
   fails CI until it is renewed with a reason or removed, as a stale `skips.tsv` line does. A test
   that is not on the list and fails, then passes on the rerun, is reported for adding, not added
   automatically.
3. *Timing-bound tests presumed flaky.* A new test that asserts on wall time or on a JIT outcome
   enters the list on day one, or runs in the quiet, single-JVM step only (FlakeFlagger's
   execution-time feature and the four-core evidence of item 103).

What to measure first, before any of this is tuned: for four weeks, per job and per suite, the
count of failed versus flaked outcomes (Facebook's ratio was four flaked to one failed; Varka's is
unknown), the runner CPU and core count of each flake, and the minutes spent on reruns (by hand
and automatic) against the minutes of the runs themselves. The first number decides whether
automatic reruns pay for their slots; the second decides whether a flake is the test's or the
runner's; the third is the cost the policy is meant to cut.
