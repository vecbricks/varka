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

The owner builds competing designs both ways, so both are built behind a `plan` flag and the
replay chooses:

* **A, part-run seconds.** `times --part-runs FILE` writes each (configuration, suite) part run's
  seconds beside the whole runs. `units` weighs a part run by its measured part seconds where the
  pair is listed, and by `whole * max(share, 0.1)` otherwise.
* **B, planned apart.** A suite named in `sql/varka/matrix/part-alone.tsv` (first entry:
  `VarkaSparkFuzzSuite`) runs as a unit of its own under each configuration that runs it. It is
  weighed at its whole seconds whatever its share, since its time follows the compositions it
  drew and not the share.

The variant that keeps more bins under 600 s in the replanned replay of 43642146943 becomes the
default. A tie goes to B, which needs no new file. The loser stays as a flag, by the house rule
for losing options.

### 3.4 What is deliberately unchanged

The report job, which already uploads the file. The lane target of 480 s and the slot count. The
reach record. Row 570's straggler deadline, which reads the plan's seconds and gains from a
truer plan, but not here.

## 4. Files

| file | what |
|---|---|
| `sql/varka/matrix/config-suite-seconds.tsv`, `suite-seconds.tsv` | rewritten (generated) |
| `dev/varka_matrix_bins.py` | `replay`, `times --part-runs`, variants A and B |
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

The replays, on the laptop from downloaded artifacts, no runner involved: run 43642146943 (155
bins, 38 slots, measured 34.4 minutes) and run 44485292430 (191 bins), "as run" and "replanned"
with the committed file and with the run's own pairs, under A and under B. Written to
`results/VARKA-617-replay.txt`.

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

1. Download the two runs' bin and report artifacts. Done when every bin's `seconds` and JUnit
   are present (155 and 191 bins).
2. Pull request 1: `replay` and its selftest. Done when `dev/varka_matrix_bins.py --selftest`
   passes and prediction 1 is scored in `results/VARKA-617-replay.txt`.
3. Pull request 1: the fresh pair files. Done when prediction 2 is scored there, the replay of
   43642146943 on its own pairs within a fifth of its measured phase.
4. Pull request 2: variants A and B with their selftests, and the replay of both. Done when
   predictions 3 and 4 are scored and the default is chosen by 3.3's rule.
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
