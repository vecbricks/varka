# VARKA-637: a pull request's option matrix runs what its diff can move

## 1. Where this came from

Row 637 of `PLAN.md`, added on 22 November 2026 by the owner's word, from the day's option matrices.
Five pull request matrices took 116 to 185 minutes from their first job's creation to their last
job's end (runs 45013534970, 45014204596, 45017980114, 45019774322 and 45023435545), but no bin in
them took more than 29.8 minutes. The rest of the time was queueing: up to five matrices of about
175 jobs each shared the fork's 40 slots, and the median bin waited 33 to 103 minutes to start. A
documents-and-tooling pull request (#1627, run 45058286930) and a planner-only one (#1632, run
45060246559) each ran the whole matrix. The Builds waited over an hour behind the matrices.
`results/VARKA-637-pr-matrix-scope.txt` holds every dispatch from 15 November on.

## 2. The admission check, done

Read through the Actions API on 22 November 2026 (`results/VARKA-637-pr-matrix-scope.txt`).
There were 58 pull request dispatches in eight days, using 67,612 bin-minutes. Each diff was
taken against master as it stood at the dispatch and sorted by a draft of 3.1's rules (the
results file says where the draft is coarser; the totals do not move):

* **Path scope saves about a seventh.** 50 runs (85.4% of the bin-minutes) change main code or
  shared test classes, so they need the whole matrix under any path rule. 6 runs change only
  suites, and their suites are at most 7.6% of the matrix's suite seconds. One run changes only
  tooling and one only the matrix's own files. Those 8 runs used 9,878 bin-minutes (14.6%). On
  22 November alone, 3 of the 14 dispatches were in this group, 23% of the day's bin-minutes.
* **An option-reader scope saves nothing.** 46 runs change main code, and nearly all of them
  touch a class that every kernel's compile runs. Only 4 of the 73 options in
  `VarkaEmitOption.TABLE` are read by a single node kind's emission (`readOnlyBy`), and no run's
  diff stays inside those node kinds' lowering. Row 518 measured the same thing at run time:
  most options are read by nearly every kernel, and a scope built from option reads missed 22
  tests that the byte comparison names. So 3.3 is designed but not built.
* **Red runs cost a fifth.** 12 runs failed, using 14,924 bin-minutes (22%), and each of them
  ran all its bins. 12 of the 37 branches were dispatched more than once. Three of the day's red
  matrices (#1617, #1619, #1625) failed on configurations nobody had run locally, which is why
  `~/varka-ops/pre-pr.sh` now sweeps the changed suites' arms. A first stage that runs those
  arms and stops on red ends such a run after its first bins.

What the check would have rejected: a scope built per option. What it keeps: path tiers for the
diffs that touch no main code, and a first stage for every run.

## 3. The design

### 3.1 The tiers, from the changed paths

`dev/varka_matrix_scope.py BASE HEAD` prints one tier and, for the narrower tiers, the
configurations and suites. BASE is the merge base with vecbricks/varka's master; the build job
fetches that master, which is public, so no token is needed. Every changed path is matched
against `sql/varka/matrix/scope.tsv`, an allow-list of path patterns with one tier each. The
widest tier any path takes wins, and a `smoke` path adds the `smoke` checks to a `suites` run
(the planner's selftest and its full-plan check). A path no pattern lists is `full`, and so is a
scope computation that fails for any reason. The rules fail closed.

| tier | the diff touches only | the run |
|---|---|---|
| `none` | documents no test source names: `*.md`, `sql/varka/plans/`, `skills/`, `papers/`, figures, benchmark results and provenance files, workflows that neither the matrix runs nor make its artifacts | no bins; the report job ends green and prints the scope |
| `smoke` | the matrix's own machinery: `dev/varka_matrix*`, `varka-option-matrix.yml`, `.github/actions/` (whose actions the bins use), the planner's weight files, and the Build workflows that make the classes archive and the reach record (`build_and_test.yml`, `build_main.yml`, `varka-master.yml`) | one bin of each kind (the defaults, one configuration, one platform), plus the planner's selftest and a full plan checked so that every (configuration, suite) lands once |
| `suites` | test sources, `dev/` scripts and documents that test sources name, where every changed file maps to suites by 3.2 | the mapped suites under every configuration and platform, with the reach record |
| `full` | anything else: main code, generated data files read by suites (`coverage.json`, `emitted_bytes.json` and the rest), build files, `VarkaEmitOption*`, `skips.tsv`, unknown paths | today's matrix |

A document or a `dev/` script is `none` only when no test source names its path. Otherwise it
takes the suites that name it. For example, `VarkaCoverageSuite` checks `docs/sql-varka.md`'s
generated table, and `VarkaMethodNamesSuite` reads two figure scripts under
`sql/varka/plans/figures/`. About 75 test sources name a `dev/` script today, the count
depending on the pattern, mostly in comments, and the rule does not tell a comment from a
call.

### 3.2 From changed test classes to suites

A changed suite maps to itself. A changed test class that is not a suite maps to every suite
that reaches it through the test sources' references: the transitive closure of the class names
mentioned in each test source.
`sql/varka/matrix/scope.tsv` also lists the test classes that suites load by name or that
register IR node types (the registry suites, whose misses failed #1338). A change to one of
those is `full`. So is a change to any class `VarkaMatrixRuns`, `VarkaTestReach` or the suite
runner loads, because every suite runs through them.

### 3.3 From option readers to configurations: designed, not built

If it is built, a main class whose every use sits in the lowering of a node kind K would map to
the configurations of the options whose `readOnlyBy` is K, plus the defaults and the platforms.
This uses `readOnlyBy`, never a hand list. 2 already shows that no run of the eight days would
have qualified. The row builds it only if the owner asks, after 4's replay has scored it on the
red runs of the recorded window.

### 3.4 Two stages

The build job plans two bin lists, `first` and `rest`. `first` holds the units of the changed
suites under every configuration without the reach record, which is `pre-pr.sh`'s arm sweep. It
also holds the units of any bin that failed in the last red run of the same branch, read from
that run's `varka-option-matrix-plan` artifact (new, 30 days) and its jobs. When row 638's sweep
already passed on the same head, `first` holds only the failed-run units, and the swept units
leave the run. `rest` is the tier's other units. GitHub refuses an empty matrix, so each list's
job carries `if: <list> != '[]'`. `rest` waits for `first` and runs when `first` succeeded or
was skipped for an empty list: `if: !cancelled() && needs.build.result == 'success' &&
needs.first.result != 'failure' && <rest> != '[]'`. A plain `needs` would skip `rest` with a
skipped `first`, and a main-code diff with no changed suite would end green with no bins. The
report job reads both. A red `first` ends the run after its own bins.

### 3.5 Inputs, and what a run prints

`scope: auto | full` and `stages: two | one` (default `two`). `scope` defaults to `full` until 7's
step 3 turns it to `auto` (3.6). `full` and `one` give today's run for any diff. The step summary's
first line says `scope: <tier>`, the reason (the first path that set the tier), and the counts of
configurations, suites and bins. A scoped or empty run that passes is green, so the owner's rule of
flagging a merge without a green matrix still reads one run per head. The scope is recomputed from
the head, so a flag can be checked.

### 3.6 The safety net

A scoped run can miss two things: a configuration that a test helper's change moves through a
path 3.2 does not see (reflection, resources, a class loaded by name), and a tooling script that
a suite runs without naming it. The full matrix on master catches both. **It does not run
today:** the weekly schedule is disabled on vecbricks/varka, and row 522, which moves it to the
fork, is Planned. So this row's scoped tiers ship disabled (`scope` defaults to `full`) until
row 522's weekly runs on the fork. The default then turns to `auto` in the row's last commit,
and a daily full run joins the weekly. The daily run is the fork's schedule at 02:00 UTC, run
only when master moved since the last full run and waiting for other matrices as 522's 2.3
does. A miss reaches the owner through `report-failure` on the fork (522 7 question 5). The
issue lists the pull requests merged since the last green full run, each with the tier its
matrix ran, so a miss is traced to the tier that let it through and the rule is widened.

### 3.7 What is deliberately unchanged

The reach record and row 508's digest selection, which trims inside a configuration and which
`full` still needs to scale: 2 shows 85.4% of the bin-minutes are `full`. The planner's weights
(row 617). The platform bins (row 505).

## 4. The measurement

The replay of `results/VARKA-637-pr-matrix-scope.txt`'s 58 runs, on the laptop:
`dev/varka_matrix_scope.py --replay` gives each head its tier and bin count. From those, the
bin-minutes each run would have used are computed from its own measured unit seconds (row 617's
`replay`, when merged, or that run's artifacts). Then one week of live dispatches after the
default turns gives the same table, written to `results/VARKA-637-live-week.txt`.

### 4.1 Predictions, registered before the build

These are read from the runs' own measured seconds rather than priced. The calibration table
(`dev/varka_calibration.py`) has no row for CI bin-minutes, so no ratio applies.

1. The replay scores the 58 runs at 12% to 17% fewer bin-minutes from the tiers alone. The 8
   narrower runs drop to under a tenth of their bins.
2. The first stage cuts the 12 red runs' 14,924 bin-minutes by at least half, provided each
   failed in a unit `first` holds (an arm of a changed suite, or a unit that failed on the
   branch before). Runs whose failure lay elsewhere are counted and named.
3. A green `full` run's wall time rises by up to its longest `first` bin, 33 minutes at most (the
   window's longest bin was 32.2), when slots are free. When slots are short it falls, because red
   runs no longer hold 170 jobs.
4. The live week's median bin wait falls under 30 minutes, from 22 November's 33 to 103.

## 5. Tests

* `dev/varka_matrix_scope.py --selftest`: one planted path per tier, an unknown path widening
  to `full`, a helper reached through two references mapping to its suites, a name-loaded
  helper widening to `full`, and a failed `git diff` giving `full`.
* `dev/varka_matrix_bins.py --selftest`: the `first` list holds the changed suites under every
  configuration and the failed units, and `rest` never repeats a unit of `first`.
* `--replay` over the 58 heads, committed as the results file of 4.
* One dispatch with `scope=auto` on a main-code head that changes no suite: `first` is empty and
  skipped, and `rest` still runs every bin. This is the case a plain `needs` would turn green
  with no bins.

## 6. Risks

1. A tests-only diff whose helper reaches suites by a path the scan does not see. 3.2's name
   list widens such a helper to `full`, 3.6's full runs catch the rest, and each miss widens the
   list.
2. Master moved after the branch's merge base. The scope is the branch's own diff, and master's
   changes are covered by master's own full run.
3. The second stage waits for the first. 4.1's prediction 3 says by how much. `stages: one` is
   the way out.
4. A red run's failed units come from an artifact that may have expired or been lost. Then
   `first` holds the changed suites alone, as for a first dispatch.

## 7. Sequencing

Three pull requests, each under 1,000 counted lines, one per step.

1. `dev/varka_matrix_scope.py` with `scope.tsv`, its selftest and `--replay`, plus the replay's
   results file. Done when the replay is committed and 4.1's prediction 1 is scored.
2. The workflow: the `scope` and `stages` inputs, the two bin lists, the plan artifact and the
   summary line, with `scope` defaulting to `full`. Done when a dispatch with `scope=auto` on a
   documents-only head ends green with no bins, one on a suites-only head runs its suites, and
   one on a main-code head with no changed suite runs every bin (5).
3. After row 522's weekly run on the fork has run twice: the daily full run and the default
   turned to `auto`. Done when one live week is committed and predictions 2 to 4 are scored.

## 8. Questions for the owner

1. **The daily full run.** Recommended: daily at 02:00 UTC when master moved, beside 522's
   weekly. It is about 1,300 bin-minutes a day against the 8,400 a day the eight days averaged.
   The alternative is the weekly alone, which leaves a miss up to a week undetected.
2. **Building 3.3 anyway.** Recommended: no. 2 found no run it would scope.

## 9. Done when

A pull request whose diff touches no main code runs the tier 3.1 gives it, and the run says so.
Every run plans a first stage that stops a red run after its own bins. A full matrix on master
runs at least daily. One live week shows the bin-minutes per dispatch and the median bin wait
against `results/VARKA-637-pr-matrix-scope.txt`, with every miss of the full runs traced to its
tier.
