# VARKA-617: the option matrix's planner checked against its own run, on pairs of this month

## 1. Where this came from

Row 617 of `PLAN.md`, added on 15 November 2026 when closing row 533. The replay of run
40961304094 put the bin phase at 27.4 minutes against that run's measured 35.3, 22 per cent
under. The fresh runs 43642146943 and 43655530012 measured 34.4 and 32.3 minutes with 155 bins
at 38 slots, against the replay's 106 bins at 30, so the two figures are not comparable
(`results/VARKA-513-ci-wall-20261115.txt`, row 533). `sql/varka/matrix/config-suite-seconds.tsv`
still holds the October runs, although each run's report job writes the same file and uploads
it. And `VarkaSparkFuzzSuite`'s part runs, whose seconds follow neither their reach share nor a
whole run's weight, are the tail (`results/VARKA-533-config-weights.txt`: planned 229 to 274 s,
replayed 402 to 454 s, each unit led by that suite).

## 2. The admission check, done

Read on the laptop from two runs' report artifacts (`results/VARKA-617-pairs.txt`).

* **The pair file is stale, and it matters.** The committed file was last written on 30 October
  2026 (#1159), 5,718 pairs. Run 43642146943's own file (14 November, with a reach record) has
  8,392 pairs. Run 44485292430's (19 November, on master's head `e5d70012bdc`, no reach record,
  so every configuration ran every test whole) has 10,846. 51 and 52 suites of those runs have no
  pair in the committed file at all, so the planner weighs them by the October median times a
  configuration factor. Over the pairs both files hold, the median ratio is 1.01, but the
  middle 80 per cent spans 0.65 to 1.65 (0.63 to 1.70 for the master run). The planner packs
  bins by these seconds, so a pair half or twice its weight is a bin half or twice its plan.
* **No replay can be rerun.** The October replays (rows 508 and 533) are recorded as results,
  not as a command: `dev/varka_matrix_bins.py` has `plan`, `times` and `--selftest`, and no
  replay. The replay's model is written down in `results/VARKA-533-config-weights.txt`: each
  unit's seconds are its suites' JUnit seconds plus 1.7 s, two lanes a bin, a bin's job its
  longer lane plus 38 s, jobs started in plan order on 30 slots. That model was 22 per cent under
  its own run. It counts no wait for a runner, and its 38 s setup sits beside the measured
  median of 30 s before the first test (`results/VARKA-513-ci-wall-20261115.txt`, 514).
* **The tail holds.** The planner weighs a part run as `whole * max(share, 0.1)` (`units`). The
  pair file holds whole runs only, so `VarkaSparkFuzzSuite` has 22 pairs committed, 18 in the 14
  November run and 58 in the master run. Under a reach record most configurations run it in
  part and are weighed at a share of 0.15 to 0.30 of about 189 s, where they took 136 to 188 s.
* **Both fresh artifacts are still downloadable**, until 14 and 19 December 2026.

What the check would have rejected: committing a fresh file and calling the planner right. The
row's point is that only a replay of a run on its own inputs can say that, and that replay is not
a command yet.

## 3. The design

### 3.1 The pair file from fresh runs

`sql/varka/matrix/config-suite-seconds.tsv` is rewritten from runs 43642146943 and 44485292430,
the mean where both hold a pair, by the existing `write_config_seconds`. The header names both
runs and heads. The 14 November run meets the done-when's date. The 19 November run is master's
head with every pair whole, the most complete record there is. `suite-seconds.tsv` is rewritten
from the master run's `suite-seconds.tsv` in the same commit.

### 3.2 The replay as a command

`dev/varka_matrix_bins.py replay RUN_DIR --plan-from REF` reads one run's downloaded bin
artifacts and its jobs from the Actions API. It prints the run's measured bin phase (the first
bin job's start to the last bin job's end) next to two replays:

* **as run**: the run's own bins, read back from each artifact's unit directories, with each
  unit's measured `seconds`. Each bin job's setup is its measured setup, and the replay starts the
  jobs in plan order on the run's `max-parallel`, waiting the run's median queue time (a job's
  `created_at` to `started_at`). This checks the model alone. The planner is not involved.
* **replanned**: `plan` rerun over the run's configurations and suites, on the pair file given,
  then timed the same way from the run's measured unit seconds. This checks the planner.

The done-when's check is "replanned" on the run's own pairs against "measured". "as run" says how
much of any gap is the model's. The October gap of 22 per cent could be either, and the record
cannot tell.

### 3.3 `VarkaSparkFuzzSuite`'s part runs: both variants, chosen by the replay

Under either variant, `times --part-runs FILE` writes each (configuration, suite) part run's
measured seconds beside the whole runs, and the committed pair files carry them. That record is
what the done-when asks for, so the row can close whichever variant wins. The owner builds
competing designs both ways, so the two ways of weighing the record are built behind a `plan`
flag and the replay chooses:

* **A, part-run seconds.** `units` weighs a part run by its measured part seconds where the pair
  is listed, and by `whole * max(share, 0.1)` otherwise.
* **B, planned apart.** A suite named in `sql/varka/matrix/part-alone.tsv` (first entry:
  `VarkaSparkFuzzSuite`) runs as a unit of its own under each configuration that runs it. It is
  weighed at its whole seconds whatever its share, since its time follows the compositions it
  drew and not the share. Its recorded part seconds are read by the replay only.

The variant that keeps more bins under 600 s in the replanned replay of 43642146943 becomes the
default. A tie goes to A, which uses the recorded part seconds the planner already reads and
needs no list of its own, where B adds `part-alone.tsv`. The loser stays as a flag, by the house
rule for losing options.

### 3.4 What is deliberately unchanged

The report job, which already uploads the file. The lane target of 480 s and the slot count. The
reach record. Row 570's straggler deadline, which reads the plan's seconds and gains from a
truer plan, but not here.

## 4. Files

| file | what |
|---|---|
| `sql/varka/matrix/config-suite-seconds.tsv`, `suite-seconds.tsv` | rewritten (generated) |
| `dev/varka_matrix_bins.py` | `replay`, `times --part-runs` (both variants), A and B |
| `sql/varka/matrix/part-alone.tsv` | variant B's list |
| `m8/results/VARKA-617-replay.txt` | the replays of 6 |

## 5. Tests, and what each is for

* `dev/varka_matrix_bins.py --selftest`, new cases. "as run" over a two-bin fixture equals the
  fixture's measured phase when setup and queue are taken from it, which catches a model that
  drops a term. "replanned" with the fixture's own pairs gives the fixture's bins. A part run
  under A weighs its listed seconds. A suite under B forms a unit of its own.
* `python3 dev/varka_matrix_bins.py plan` over master's configurations at both file versions,
  bin counts printed, to catch a file that does not parse into the planner.

## 6. The measurement

The replays, on the laptop from downloaded artifacts, no runner involved. Run 43642146943: 38
slots, measured 34.4 minutes, 155 bin artifacts (`results/VARKA-513-ci-wall-20261115.txt`)
against 156 bin jobs in the jobs API (`results/VARKA-570-bin-times.txt`), a difference step 1
resolves. Run 44485292430: 191 bins. Each "as run" and "replanned", with the committed file and
with the run's own pairs, under A and under B. Written to `results/VARKA-617-replay.txt`.

### 6.1 Predictions, registered before the run

1. "as run" of 43642146943 is within 10 per cent of its measured 34.4 minutes once queue time
   and measured setup are counted. If it is not, the model is wrong and section 3.2 is reworked
   before any planner conclusion is drawn.
2. "replanned" of 43642146943 on its own pairs, at the current default, is within a fifth of
   34.4 minutes (27.5 to 41.3), since in sample the weights are its own times apart from the
   part runs. This is the done-when's condition.
3. Under A or B, the 43642146943 replanned bins past 600 s fall from the current default's
   count, and the longest bin falls by at least a minute. The part runs were the 533 replay's
   three longest-underplanned units.
4. The fresh pair file moves the planned bin count of a master run by under 10 per cent.

## 7. Risks

1. The two runs' reach records differ, so a part run of one is only an estimate for the other
   (`results/VARKA-533-config-weights.txt`'s last paragraph). Variant A weighs a part run by its
   own run's pair, and the selftest keeps such a pair out of the other run's replay.
2. A run's queue time depends on what else holds the fork's slots that hour. The replay takes it
   from the run itself, so it explains a run and does not predict the next one.
3. The artifacts expire in December. Step 1 downloads them into the scratch directory before
   anything else.

## 8. Sequencing

Two pull requests, each under 1,000 counted lines (the pair file is generated and not counted).

1. Download the two runs' bin and report artifacts. Done when every bin job the jobs API lists
   has its artifact with its `seconds` and JUnit, or the plan names the job that has none.
2. Pull request 1: `replay` and its selftest. Done when `dev/varka_matrix_bins.py --selftest`
   passes and prediction 1 is scored in `results/VARKA-617-replay.txt`.
3. Pull request 1: the fresh pair files. Done when prediction 2 is scored there, the replay of
   43642146943 on its own pairs within a fifth of its measured phase.
4. Pull request 2: `times --part-runs`, the pair files rewritten with the part runs' seconds,
   variants A and B with their selftests, and the replay of both. Done when predictions 3 and 4
   are scored and the default is chosen by 3.3's rule.
5. On the owner's word, one matrix dispatch on master after pull request 2. Its bin phase,
   against the "replanned" figure for its own pairs, is the out-of-sample score.

## 9. Questions for the owner

1. **Which runs go into the committed file?** Recommended: 43642146943, which meets the
   done-when's "a run of 14 or 15 November", and the master run 44485292430, which is newer,
   on master's head, and holds every pair whole. The done-when names only the first, so adding
   the second is the owner's call. The plan is built on both. With the 14 November run alone,
   step 3 still passes, on 8,392 pairs instead of the 10,846 the two hold together.

## 10. Done when

The committed file holds a run of 14 or 15 November. A replay of that run on its own pairs is
within a fifth of its measured bin phase. `VarkaSparkFuzzSuite`'s part runs carry their own
seconds.

## 11. What was built

### 11.1 Step 1, the artifacts (20 November 2026)

Both runs downloaded on the laptop with `gh run download`, into the worktree's `target/`: run
43642146943, 155 bin artifacts, its reach record and its report; run 44485292430, 190 bin
artifacts and its report (it had no reach record). The jobs API lists 155 and 190 bin jobs, and
every one has its artifact with each unit's `seconds` and JUnit reports. The 156 and 191 of
`results/VARKA-570-bin-times.txt`, which section 6 asked step 1 to resolve, count the job "Option
matrix: build and plan" as a bin: its name starts with "Option matrix: b" too. The build job's
own artifact, which held the run's configurations, expired after its one day; the configurations
are read back from the bins' `runs/*/name` files instead.

### 11.2 Step 2, `replay` (pull request 1)

`dev/varka_matrix_bins.py replay RUN_DIR --jobs JOBS --suites MODULE=FILE...` as section 3.2
designs it, with one more line than it planned: "jobs as run", the run's own jobs at their
measured lengths on the slot model, which says how much of a gap is the slot model and how much
the bin model. The replay was checked before it was scored: given the run's configurations, the
suites its build listed and its reach record, the planner at the run's head cuts 43642146943's
155 bins with the same units in the same order (73197 s planned, the figure its build job's
summary prints), and 44485292430's 190. The first try cut 229 bins, because the suites were
listed with `--defaults`, which lists 14 suites the matrix never runs under a configuration; CI
lists them without it, and so does the replay's input now.

Selftest cases, both passing: "as run" over a two-bin, one-slot fixture equals its measured 98 s,
and so does "jobs as run"; a fixture laid out as the planner cut it, its pairs read back by
`whole_runs`, replans to its own bins and its own phase.

**Prediction 1, scored: right.** "as run" of 43642146943 is 32.5 minutes against the measured
34.4, 5.5 per cent under, inside the 10 per cent. "jobs as run" is 32.8, so most of the gap is
the slot model's (jobs start a little out of bin order, and a runner's wait once a slot is free
has a median of 10 s but a longest of about four minutes), not the lanes'. On 44485292430 the
three figures are 45.6 measured, 45.6 and 45.2 minutes. Section 3.2 needs no rework.

### 11.3 Step 3, the fresh pair files (pull request 1)

`config-suite-seconds.tsv` rewritten from both runs' report artifacts, 10846 pairs, 8392 in both
runs and taken as their mean; `suite-seconds.tsv` from 44485292430's.

**Prediction 2, scored: right.** 43642146943 replanned on its own pairs (its report artifact's
two files) is 30.4 minutes against the measured 34.4, inside 27.5 to 41.3. On the committed file
of both runs it is 31.0. The figure is in sample by construction, as the prediction said; the
out-of-sample score is step 5's.

What the replays also show, for step 4: on its own pairs 43642146943 still has 3 bins over 600 s
(the longest 697 s), where 44485292430, which ran every pair whole, has none (the longest
515 s). The difference is the part runs, which the pair file does not hold.

Prediction 4's figure on this pull request's files alone, before step 4 rewrites them again: the
planner over master's configurations and suites (44485292430's, no reach record) cuts 216 bins on
the fresh file against 190 on October's, 13.7 per cent more, past the predicted 10. It is scored
after step 4, as section 8 says.

### 11.4 The review's fixes (22 November 2026, pull request 1)

The high review of #1632 on `9032bbbb3d1` found one important fault and seven nits, all fixed:

* `--plan-from` takes only a ref that is a commit, a path the commit lacks still reads empty and
  any other `git show` failure stops the replay; a given `--pairs` or `--suite-seconds` that is
  no file stops it too. Before, each planned by fill values alone and printed a believable figure.
* The jobs file reads in every form `gh api --paginate` saves, with or without `--jq`.
* Bin jobs whose last attempts differ are refused; a unit with a `run.log` and no `seconds`, killed
  by a cancel or the timeout, is listed beside "no artifact"; inputs that match nothing stop with
  the counts found and the paths read; the median set-up counts only jobs that ran their units.
* `plan` fails, with an `::error::` line the workflow step now prints, when its cut holds more than
  the 256 jobs a GitHub matrix may: this pull request's data takes a no-reach run from 190 bins to
  216, 40 under the cap.

The CI plan path's output is unchanged for valid input: `plan` as the workflow calls it, with and
without `--platforms` and with run 43642146943's reach record, writes the same JSON and summary
line before and after.

### 11.5 Step 4, the part runs and variants A and B (pull request 2)

Built on `varka-617b-part-runs`, first stacked on pull request 1's branch and rebuilt on master
after pull request 1 merged as #1632.

* `run_seconds` reads each (configuration, suite)'s JUnit seconds with its share under the run's
  reach record; `whole_runs` keeps the share-1 pairs as before and the new `part_runs` the
  pairs strictly between 0 and 1.
* `times --part-runs FILE` writes them to their own file,
  `sql/varka/matrix/config-suite-part-seconds.tsv`, with the share each was measured at. A part
  pair sits in a file of its own so that no reader can take it for a whole run and apply the
  share to it a second time. The committed file holds 43642146943's 565 part runs, 36 of them
  `VarkaSparkFuzzSuite`'s; 44485292430 had no reach record and ran none.
* `plan --part-weight share|part-seconds|alone`, threaded through `units`: `share` is the
  weighing before this step, `part-seconds` is variant A, and `alone` is variant B with its
  list in `sql/varka/matrix/part-alone.tsv`. A part-run pair is read only where the
  configuration's share is under 1, so a run with no reach record, or the defaults, never reads
  one.
* `replay` prints the replanned figure under all three, side by side.

Before choosing, the premise was checked: the 3 bins over 600 s on 43642146943's own pairs come
from part runs, and of the 4,967 s those part runs ran past their plan, `VarkaSparkFuzzSuite`
holds 3,257 s, two thirds. The rest is spread over other suites, led by
`VarkaGoldenCorpusSuite` (401 s). This is why B's single-entry list leaves a bin over 600 s that
A does not.

**Prediction 3, scored: right.** On this branch's files, 43642146943 replanned has 4 bins over
600 s under `share`, 0 under A and 1 under B. Its longest bin falls from 752 s to 585 s under A
and to 605 s under B, both by more than a minute. A is in sample, because its part seconds are
this run's own. B's replay charges a new JVM the run's median overhead of 1.4 s. A cold JVM with
a Spark session may cost more than that, which would weigh against B.

**Prediction 4, scored: wrong.** Over a master run's configurations and suites (44485292430's,
with no reach record and so no part run under any weighing), the planner cuts 216 bins on the
fresh pairs against 190 on October's, 13.7 per cent more, past the 10 per cent predicted. The
October file under-weighed the suites: the fresh one weighs that cut at 101711 s against 89557.
That run took 45.6 minutes with 26 bins over 600 s, and its replay on the fresh file takes 38.0
with none. More bins, but a shorter phase.

**The default, by 3.3's rule: `part-seconds` (A).** It has fewer bins over 600 s on this
branch's files (0 against B's 1), and the tie at 0 on the run's own files goes to A as well. B
stays as `--part-weight alone`, with its list, by the house rule for losing options.

**Out of sample, run 43655530012.** This run is not in any committed file. It is the other
fresh 14 November run that section 1 names, with its own reach record, and its part runs weigh
43642146943's seconds on this branch's files. The replay cuts its 155 bins back as its build
job planned them. On this branch's files it leaves 7 bins over 600 s under `share`, 1 under A
and 1 under B, with the longest bin at 695 s, 625 s and 624 s. A still wins by 3.3's
tie rule, and it beats `share` out of sample: the tail falls from 7 bins to 1, and the longest
bin by 70 s. Decision 7 below rests on this. "as run" is 32.0 minutes against the measured
32.3, so the model holds on a third run.

Section 10's three conditions now hold on the branch under `--part-weight part-seconds`; with
`share` the default (decision 9), the default path reaches them only with decision 8's refresh. The
committed file holds the 14 November run. Its replay on its own pairs is 30.4 minutes against the
measured 34.4 (31.0 on the committed two-run file). `VarkaSparkFuzzSuite`'s part runs carry their
own seconds in `config-suite-part-seconds.tsv`. Still owed: this pull request's review and merge
(the first merged as #1632), and step 5's dispatch on master. A master dispatch reads a reach record
only when a Build of the same commit has left one (`dev/varka_matrix_reuse.py`); 44485292430 found
none. Without a record, step 5 scores the pair file alone, because every configuration runs every
test and no part run is weighed. A's own out-of-sample score in CI then waits for the first matrix
that has a reach record, a pull request's.

**The default is `share`, after all (23 November 2026, decision 9).** 3.3's rule picked A, but
nothing in CI refreshes the part file (decision 8), so A ships as the opt-in `--part-weight
part-seconds` and `share` stays the default.

**The CI path after the rebuild on master (23 November 2026).** `plan` as the workflow calls it,
with no `--part-weight`, on 43642146943's and 44485292430's configurations and master's suites, with
and without `--platforms`, with no reach record and with either 14 November reach record: the JSON
and the summary line are byte for byte master's in all twelve cases, and the default reads no part
file. Under `--part-weight part-seconds` the cut changes on purpose, as above: 4 bins more (186 to
190 with the platforms on 43642146943's configurations, 191 to 195 on 44485292430's) and about 1,900
planned seconds more, every bin still at most 480 s. The largest cut, no record with the platforms,
stays at 227 bins, 29 under the cap of 256. `times` as the report job calls it writes the same two
files and the same messages as master on all three runs' artifacts.

## 12. Decisions taken while the owner was away (20 November 2026)

1. **Section 9's question: both runs.** The committed file holds 43642146943 and 44485292430, as
   the plan recommended and built on: the first meets the done-when's date, the second is
   master's head with every pair whole.
2. **A runner's wait is measured from the slot, not from the job's creation.** Section 3.2 took
   the wait as a job's `created_at` to `started_at`. Every bin job is created the moment the
   build job ends (all 155 within a second), so that median, 884 s, is the wait for a slot of
   `max-parallel`, which the slot model already counts. The replay takes a job's start less the
   end that freed its slot (its creation for the first wave), median 10 s and 2 s in the two
   runs.
3. **The slots are the most bin jobs the run ran at once**, 38 in both runs, which is the
   workflow's `max-parallel`; `--slots` overrides it.
4. **The suites are an input, `--suites` as `plan` takes them**, listed at the run's head with
   `dev/varka_matrix.sh --list-suites` (no build, sources only), since no artifact keeps them.
5. **`--plan-from REF` takes `--pairs` and `--suite-seconds` beside it.** A run's own pairs are
   its report artifact's files, not a revision, so the two files can be named directly.
6. **"Keeps more bins under 600 s" is read as "fewer bins over 600 s".** A larger cut can have
   more bins under 600 s and still more over. The rule is about the tail, so it is the count
   over 600 s that is compared.
7. **A uses a part-run pair only where the configuration's share is under 1, whatever share it
   was measured at.** Scaling the pair by the ratio of shares would bring back the linear model
   that fails for this suite, whose part runs took 78.5 to 225.0 s at shares of 0.034 to 0.345.
   Ignoring the pair when the shares differ would leave A almost never applying, since every
   pull request's reach record differs. Section 7's risk 1 is answered by the share-1 rule: a
   run with no reach record never reads a part pair.
   Rechecked on 23 November 2026, after the rebuild on master: under 43655530012's reach record,
   5 of the pairs whose share grew matter, all `VarkaSparkFuzzSuite`'s. The run measured them
   at 101, 100, 80, 203 and 126 s; the file gives 125, 93, 99, 87 and 126, and scaling by the
   ratio of shares 312, 189, 138, 122 and 140. The file is nearer in four of five, so the ratio
   scaling stays rejected. The high review of the rebuilt branch then named the case this check
   could not see: a suite whose time does follow its share, measured at a small share and run
   at a large one, as a pull request's record can ask. So a pair whose share today, at the
   file's three decimals, is above the one it was measured at now weighs no less than its share
   (`read_part_seconds` keeps the share). Under 43655530012's record that raises 8 of 22 such
   pairs, by 7.3 s in all, none of the fuzz pairs, since each measured part run exceeds its
   share weighing. A floor for every pair was tried first and lost: on 43642146943 it left 1
   bin over 600 s and the longest at 605 s, against 0 and 585 s, and on 43655530012 the longest
   at 653 s against 625 s. The share-aware form reproduces every step-4 replay of section 11 but
   one figure, 0.1 minutes (`results/VARKA-617-replay.txt`).
8. **The report job is unchanged, as section 3.4 says.** So it does not yet write
   `config-suite-part-seconds.tsv` beside the other two. The file is refreshed as this row
   refreshed it, with `times --reach ... --part-runs` over a run's downloaded bin artifacts.
   Adding `--part-runs` to the report job's "Suite seconds" step is a two-line follow-up for
   the owner to approve, since it changes the workflow; row 617's "left:" names it, so it is
   tracked until the owner decides.
9. **`share` stays the default; A is an option (the lead's settlement, 23 November 2026).** A
   whole-run pair's fit does not depend on the pull request, but a part pair's does: it was measured
   at one record's shares, and each pull request's record gives others, so an unrefreshed part file
   drifts from every new record with no sign. The flip to A goes with the refresh workflow of
   decision 8, on the owner's approval, and two replays decide it first: whether A shrinks the 23 to
   29 minute bins of b098, or those were slow runners; and what a pair missing from the file weighs
   on a pull request's record (it falls back to its share, so a fresh suite or configuration is
   weighed as now).
