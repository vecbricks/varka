# VARKA-637: a pull request's option matrix stops a red run early, then runs what its diff can move

*Moved to milestone 9 on 23 November 2026 with row 637 (`SCOPE.md` item 138). A bare
`PLAN.md`, `results/` path or other `VARKA-<n>.md` below names milestone 8's, in `m8/`,
unless that plan moved too (`SCOPE.md` item 138).*

## 1. Where this came from

Row 637 of `PLAN.md`, added on 22 November 2026 by the owner's word, from the day's option matrices.
Five pull request matrices took 116 to 185 minutes from their first job's creation to their last
job's end (runs 45013534970, 45014204596, 45017980114, 45019774322 and 45023435545), but no bin in
them took more than 29.8 minutes. The rest of the time was queueing: up to five matrices of about
175 jobs each shared the fork's 40 slots, and the median bin waited 33 to 103 minutes to start. A
documents-and-tooling pull request (#1627, run 45058286930) and a planner-only one (#1632, run
45060246559) each ran the whole matrix. The Builds waited over an hour behind the matrices.
`results/VARKA-637-pr-matrix-scope.txt` holds every dispatch from 15 November on.

*22 November 2026, by the lead's settlement:* the plan was restructured to lead with the first
stage (3.1), which drops no coverage on a green run and so needs no safety net and does not wait
for row 522. The path tiers became a later, optional step behind row 522's weekly full run (3.2).
No option-reader tier is built (3.3), and no daily full run of master joins the weekly (3.5).

## 2. The admission check, done

Read through the Actions API on 22 November 2026 (`results/VARKA-637-pr-matrix-scope.txt`).
There were 58 pull request dispatches in eight days, using 67,612 bin-minutes.

* **Red runs cost a fifth, and a first stage stops two thirds of them.** 12 runs failed, using
  14,924 bin-minutes (22%), and each of them ran all its bins. 12 of the 37 branches were
  dispatched more than once. Scored against the results file's 12 red runs, a first stage of
  the changed suites under every configuration, plus the units that failed in the branch's last
  red run, holds a red bin of 8 of them, which used 10,011 bin-minutes. The other 4, 4,913
  bin-minutes, failed only in suites their pull requests did not change (#1619's among them).
  10,011 is a ceiling on the saving: a stopped run still pays for its build and its first stage.
  The failed-run rule stopped no run that the changed suites did not.
* **Path scope saves at most about a seventh.** Each diff was taken against master as it stood at
  the dispatch and sorted by a draft of 3.2's rules (the results file says where the draft is
  coarser; the totals do not move). 50 runs (85.4% of the bin-minutes) change main code or
  shared test classes, so they need the whole matrix under any path rule. 6 runs change only
  suites, and their suites are at most 7.6% of the matrix's suite seconds. One run changes only
  tooling and one only the matrix's own files. Those 8 runs used 9,878 bin-minutes (14.6%), the
ceiling of a tier saving, since `suites` and `smoke` runs still run bins. On
  22 November alone, 3 of the 14 dispatches were in this group, 23% of the day's bin-minutes.
* **An option-reader scope saves nothing.** 46 runs change main code, and nearly all of them
  touch a class that every kernel's compile runs. Only 4 of the 73 options in
  `VarkaEmitOption.TABLE` are read by a single node kind's emission (`readOnlyBy`). The main
  classes that name those node kinds also serve other kinds, apart from two lowerings, and the
  two runs that change one of those lowerings also change core files (the results file lists
  them). Row 518 measured the same thing at run time:
  most options are read by nearly every kernel, and a scope built from option reads missed 22
  tests that the byte comparison names.

Of the three red matrices of 22 November that led to `~/varka-ops/pre-pr.sh`'s arm sweep,
#1617 and #1625 failed in suites they changed, and #1619 failed in `VarkaFloatingLaneSuite`,
which it did not change. A changed-suite stage catches the first kind only.

What the check would have rejected: a scope built per option, and a daily full run paid for by
the tiers (3.5). What it keeps: a first stage for every run, and path tiers for the diffs that
touch no main code, later.

## 3. The design

### 3.1 The first stage, built first

The build job plans two bin lists, `first` and `rest`. `first` holds the units of the changed
suites under every configuration without the reach record, which is `pre-pr.sh`'s arm sweep.
The changed suites are `pre-pr.sh`'s rule: every `*Suite.scala` or `*Suite.java` under
`sql/catalyst/src/test` or `sql/core/src/test` that the diff adds or changes, plus
`VarkaMatrixSuite` when `VarkaEmitOption`, `VarkaEmitOptions` or a `VarkaMatrix*` class changes.
`dev/varka_matrix_scope.py BASE HEAD --suites` prints them, each once. BASE is the merge base
with vecbricks/varka's master; the build job fetches that master, which is public, so no token is
needed.

`first` also holds the units of any bin that failed in the branch's last run with a failed bin,
whatever that run's conclusion, so a run cancelled after a red bin counts (#1626's 45060620623
was). They are read from that run's `varka-option-matrix-plan` artifact (new, 30 days) and its
jobs, which the build job's token reads with `actions: read`. When row 638's
sweep already passed on the same head, `first` holds only the failed-run units, and the swept
units leave the run. `rest` is every other unit of today's matrix, so a green run covers what
today's run covers and nothing is dropped.

GitHub refuses an empty matrix, so each list's job carries `if: <list> != '[]'`. `rest` waits
for `first` and runs when `first` succeeded or was skipped for an empty list: `if: ${{
!cancelled() && needs.build.result == 'success' && needs.first.result != 'failure' && <rest> !=
'[]' }}`, wrapped because a bare `if:` that starts with `!` is a YAML tag. A
plain `needs` would skip `rest` with a skipped `first`, and a main-code diff with no changed
suite would end green with no bins. The report job reads both. A red `first` ends the run after
its own bins.

### 3.2 The path tiers: later, optional, behind the weekly full run

This step is built only after row 522's weekly full matrix runs on the fork, which is the net
for what a tier skips (3.5), and only if the live week of 4 still shows bin waits worth it.

`dev/varka_matrix_scope.py BASE HEAD` prints one tier and, for the narrower tiers, the
configurations and suites. Every changed path is matched against `sql/varka/matrix/scope.tsv`,
an allow-list of path patterns with one tier each. The widest tier any path takes wins, and a
`smoke` path adds the `smoke` checks to a `suites` run (the planner's selftest and its full-plan
check). A path no pattern lists is `full`, and so is a scope computation that fails for any
reason. The rules fail closed.

| tier | the diff touches only | the run |
|---|---|---|
| `none` | documents no test source names: `*.md`, `sql/varka/plans/`, `skills/`, `papers/`, figures, benchmark results and provenance files, workflows that neither the matrix runs nor make its artifacts | no bins; the report job ends green and prints the scope |
| `smoke` | the matrix's own machinery: `dev/varka_matrix*`, `varka-option-matrix.yml`, `.github/actions/` (whose actions the bins use), the planner's weight files, and the Build workflows that make the classes archive and the reach record (`build_and_test.yml`, `build_main.yml`, `varka-master.yml`) | one bin of each kind (the defaults, one configuration, one platform), plus the planner's selftest and a full plan checked so that every (configuration, suite) lands once |
| `suites` | test sources, `dev/` scripts and documents that test sources name, where every changed file maps to suites by the rule below | the mapped suites under every configuration and platform, with the reach record |
| `full` | anything else: main code, generated data files read by suites (`coverage.json`, `emitted_bytes.json` and the rest), build files, `VarkaEmitOption*`, `skips.tsv`, unknown paths | today's matrix, with 3.1's two stages |

A document or a `dev/` script is `none` only when no test source names its path. Otherwise it
takes the suites that name it. For example, `VarkaCoverageSuite` checks `docs/sql-varka.md`'s
generated table, and `VarkaMethodNamesSuite` reads two figure scripts under
`sql/varka/plans/figures/`. About 75 test sources name a `dev/` script today, the count
depending on the pattern, mostly in comments, and the rule does not tell a comment from a
call.

A changed suite maps to itself. A changed test class that is not a suite maps to every suite
that reaches it through the test sources' references: the transitive closure of the class names
mentioned in each test source. `scope.tsv` also lists the test classes that suites load by name
or that walk every sealed IR type (the registry suites, which #1338's new node failed on CI
though the branch's own suites passed). A change to one of those is `full`. So is a change to
any class `VarkaMatrixRuns`, `VarkaTestReach` or the suite runner loads, because every suite
runs through them.

### 3.3 No option-reader tier

*22 November 2026, by the lead's settlement:* no tier maps a main class to the configurations of
the options its node kind reads. 2 found no run of the eight days that such a tier would have
narrowed, and row 518 found the same at run time, where a scope built from option reads missed
22 tests.

### 3.4 Inputs, and what a run prints

`stages: two | one` (default `two`) comes with 3.1. `one` gives today's single list. The tier
step adds `scope: auto | full`, defaulting to `full` until that step's last pull request turns it
to `auto`. The step summary's first line says the stage counts (units and bins in `first` and
`rest`) and, once the tiers exist, `scope: <tier>`, the reason (the first path that set the
tier), and the counts of configurations, suites and bins. A run that passes is green, so the
owner's rule of flagging a merge without a green matrix still reads one run per head. The scope
is recomputed from the head, so a flag can be checked.

### 3.5 The net for the tiers: the weekly full run, not a daily one

The first stage needs no net: a green run runs every unit today's does. A tier does skip units,
and can miss two things: a configuration that a test helper's change moves through a path 3.2's
references do not see (reflection, resources, a class loaded by name), and a tooling script that
a suite runs without naming it. The full matrix on master catches both. **It does not run
today:** the weekly schedule is disabled on vecbricks/varka, and row 522, which moves it to the
fork, is Planned. So the tiers are not built until row 522's weekly runs on the fork. A miss
reaches the owner through `report-failure` on the fork (522 7 question 5). The issue lists the
pull requests merged since the last green full run, each with the tier its matrix ran, so a miss
is traced to the tier that let it through and the rule is widened.

*22 November 2026, by the lead's settlement:* no daily full run. The tiers save at most 14.6% of
the bin-minutes (2). The window averaged about seven matrices a day (58 in eight days), so the
tiers save at most about one matrix a day, which is what a daily full run of master costs
(1,457 bin-minutes for the window's master run). A daily run would spend the tiers' whole
saving, so the weekly is the net, and a miss can go up to a week undetected.

### 3.6 What is deliberately unchanged

The reach record and row 508's digest selection, which trims inside a configuration and which
`full` still needs to scale: 2 shows 85.4% of the bin-minutes are `full`. The planner's weights
(row 617). The platform bins (row 505).

## 4. The measurement

For the first stage: one live week of dispatches after it merges, written to
`results/VARKA-637-live-week.txt`, with each red run's stage (`first` or `rest`), its bin-minutes,
and the bin-minutes and wall of every run against `results/VARKA-637-pr-matrix-scope.txt`.

For the tiers, if built: the replay of the results file's 58 runs on the laptop.
`dev/varka_matrix_scope.py --replay` gives each head its tier and bin count. From those, the
bin-minutes each run would have used are computed from its own measured unit seconds (row 617's
`replay`, when merged, or that run's artifacts).

### 4.1 Predictions, registered before the build

These are read from the runs' own measured seconds rather than priced. The calibration table
(`dev/varka_calibration.py`) has no row for CI bin-minutes, so no ratio applies.

1. In the live week, the first stage stops at least half of the red runs, as it would have
   stopped 8 of the window's 12. Each red run it misses is named with the suite that failed.
2. The stopped runs use under half of what they would have used whole. The window's 8 stoppable
   runs used 10,011 bin-minutes, the ceiling on this saving.
3. A green run's wall time rises by up to its longest `first` bin, 33 minutes at most (the
   window's longest bin was 32.2), when slots are free. When slots are short it falls, because red
   runs no longer hold 170 jobs.
4. The live week's median bin wait falls under 30 minutes, from 22 November's 33 to 103.
5. If the tiers are built: the replay scores the 58 runs at 10% to 14.6% fewer bin-minutes from
   the tiers alone (14.6% is the ceiling, every narrower run's whole matrix), and the 8 narrower
   runs drop to under a tenth of their bins.

## 5. Tests

* `dev/varka_matrix_scope.py --selftest`: the changed suites of a planted diff, `VarkaMatrixSuite`
  joining on an option change and kept once, a failed `git diff` listing no suite (so `first`
  holds only failed-run units and `rest` runs everything). The tier step adds one planted path
  per tier, an unknown path widening to `full`, a helper reached through two references mapping
  to its suites, and a name-loaded helper widening to `full`.
* `dev/varka_matrix_bins.py --selftest`: the `first` list holds the changed suites under every
  configuration and the failed units, `rest` never repeats a unit of `first`, and `first` and
  `rest` together are today's plan.
* One dispatch on a main-code head that changes no suite: `first` is empty and skipped, and
  `rest` still runs every bin. This is the case a plain `needs` would turn green with no bins.
* One dispatch on a head that changes a suite: `first` runs it under every configuration, then
  `rest` runs.
* The tier step: `--replay` over the 58 heads, committed as the results file of 4.

## 6. Risks

1. A main-code change that fails a suite the branch did not change, as 4 of the window's 12 red
   runs did. The first stage then costs its own bins and the run goes on to `rest`, as today.
2. The second stage waits for the first. 4.1's prediction 3 says by how much. `stages: one` is
   the way out.
3. A red run's failed units come from an artifact that may have expired or been lost. Then
   `first` holds the changed suites alone, as for a first dispatch.
4. A stopped run shows only the failures inside `first`. 335-bit-get's run would have shown its
   changed suite's failures and left two coverage suites' failures for the next run.
5. For the tiers: a tests-only diff whose helper reaches suites by a path the scan does not see.
   3.2's name list widens such a helper to `full`, 3.5's weekly catches the rest, and each miss
   widens the list. Master moving after the branch's merge base is covered by master's own run.

## 7. Sequencing

Each pull request under 1,000 counted lines.

1. The first stage: `dev/varka_matrix_scope.py --suites` with its selftest, the `first` and
   `rest` lists in `dev/varka_matrix_bins.py`, the plan artifact, the `stages` input, the two
   bin jobs and the summary line. Done when the two dispatches of 5 have run and one live week
   is committed and predictions 1 to 4 are scored.
2. Optional, after row 522's weekly run on the fork has run twice, and only if the live week's
   median bin wait is still over 30 minutes: `scope.tsv`, the tiers, `--replay` and its results
   file, and the `scope` input defaulting to `full`. Done when the replay is committed and
   prediction 5 is scored.
3. With step 2: the default turned to `auto`. Done when one live week of tiers is committed,
   with every miss of the weekly traced to its tier.

## 8. Questions for the owner

1. **The daily full run.** Recommended: daily at 02:00 UTC when master moved, beside 522's
   weekly. It is about 1,300 bin-minutes a day against the 8,400 a day the eight days averaged.
   The alternative is the weekly alone, which leaves a miss up to a week undetected.

   *22 November 2026, by the lead's settlement:* the weekly alone (3.5). The question did not go
   to the owner, since the evidence settles it. 3.5 prices the daily run at 1,457 bin-minutes,
   the window's master run, in place of the 1,300 above.

2. **Building 3.3 anyway.** Recommended: no. 2 found no run it would scope.

   *22 November 2026, by the lead's settlement:* no (3.3).

## 9. Done when

Every run plans a first stage that stops a red run after its own bins, and a run with an empty
first stage still runs the rest. One live week shows how many red runs the first stage stopped,
the bin-minutes per dispatch and the median bin wait against
`results/VARKA-637-pr-matrix-scope.txt`. The path tiers are optional: if built, a pull request
whose diff touches no main code runs the tier 3.2 gives it, the run says so, and every miss of
the weekly full run is traced to its tier.
