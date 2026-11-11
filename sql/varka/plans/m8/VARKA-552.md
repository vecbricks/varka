# VARKA-552: Varka's stock-Spark comparisons move from 4.2.0 to 4.3.0

## 0. What exists now (admission check)

*The owner, 2 November 2026:* "Spark 4.3.0 has been released recently, see
https://spark.apache.org/releases/spark-release-4-3-0.html. We should plan tasks and compare with
this version instead of 4.2.0 in the current milestone and in the future. Also need to update the
README."

The figures were verified at the fork's master `6e544abcb2d` on 2 November 2026 (the plan
branched from `4b5948d72d9`), with the release tag `v4.3.0` (`98bc0212c94`, "Preparing Spark
release v4.3.0-rc3", 27 October 2026) fetched from apache/spark. Nothing was dispatched and
nothing was run but `git`.

1. **The release.** Spark 4.3.0 was announced on 30 October 2026. Its binary,
   `spark-4.3.0-bin-hadoop3.tgz`, is on `archive.apache.org/dist/spark/spark-4.3.0/` (the lead
   checked HTTP 200). Whether its `.sha512` is served beside it, as the workflows' download step
   expects, is **[I]**: the first run of step 1 shows it.
2. **The row id.** The highest row number in any table of `plans/*/PLAN.md` and `plans/*/SCOPE.md`
   is 551 (m8 rows 547 to 551, added 1 and 2 November); the newest plan file is `VARKA-546.md`.
   `VARKA-901` and `VARKA-902` in `m7/VARKA-384.md` are item labels inside a quoted example, not
   rows. No branch `varka-55*` exists locally or on origin, and none of the five open pull
   requests on vecbricks/varka (#1291, #1303 to #1306; #1307 merged as `6e544abcb2d`) adds a row
   numbered 552 or more. So this row is 552.
3. **What changed between the two releases.** `git merge-base` gives the same commit,
   `0eb4fc1d97d` (Spark master, 1 May 2026), for `v4.2.0` and `v4.3.0`, for `v4.3.0` and the
   fork's `origin/master` (`6e544abcb2d`), and for `v4.2.0` and `origin/master`. `v4.2.0` is not
   an ancestor of `v4.3.0`: the two releases are sibling lines from that commit. So
   `git log v4.2.0..v4.3.0`, 1771 commits, is not a delta over 4.2.0: it is the whole 4.3.0 line
   from the fork point to its tag (`0eb4fc1d97d..v4.3.0` is the same 1771), and 4.2.0 has 360
   commits of its own the other way (`v4.3.0..v4.2.0`). Counted by JIRA id from the subjects: the
   1771 carry 1546 ids (70 commits carry none: release preparation and the like), the 360 carry
   315, and 312 ids are on both lines, changes backported to each. None of the 1771 commit objects
   is in the fork's master, whose history meets the tag only at the fork point; the fork's master
   carries their changes as the master commits they were picked from. 1458 of the 1546 ids are in
   `git log 0eb4fc1d97d..origin/master`; of the 88 that are not, 82 are in apache/spark master
   after the fork's last sync with it (merge base `3d4eb2e2a52`, 14 October 2026), so the fork
   lags rather than lacks them, and 6 are on the 4.3 line only: build, infra and release
   changes and one Kubernetes backport (SPARK-56959, 56974, 57443, 58032, 58756, 59702). The 82
   are mostly Hive, ORC, Parquet pushdown, streaming and pipeline fixes; the few in code a
   comparison runs (SPARK-60403 and 60398, nested-class calls in join and `ExpandExec` codegen;
   SPARK-60119, a skipped rescale in `Decimal.changePrecision`) are exercised by no entry of the
   four tables, but SPARK-60119 is in 4.3.0 and not in the fork's engine-off arm until the next
   sync, which a `DECIMAL` comparison with the release would have to account for. Each change of
   section 2 was checked by its id: in the 4.3.0 line, in none of 4.2.0's 360 commits, and in the
   fork's master. So the fork's engine-off arm, the control that separates Varka from "a newer
   Spark", carries every change of section 2. One change the first draft of section 2 listed,
   SPARK-56032's `FilterExec` follow-ups, is on both lines (4.2.0 has them as `01295846784`,
   `3eace3d6b82` and `360bab82dce`), so it cannot move a 4.2.0-against-4.3.0 number and is dropped.
   Dependencies are unchanged between the tags: Janino 3.1.9, Arrow 19.0.0, Parquet 1.17.0,
   Scala 2.13.18, and Java 17, 21 and 25 supported (`pom.xml`, `docs/index.md` at each tag).
4. **The JIRA release notes** (`ReleaseNote.jspa?projectId=12315420&version=12356944`, linked
   from the release page) were fetched twice and returned only the ASF Jira login page. Section 2
   rests on the tag's own history and on `docs/sql-migration-guide.md` at `v4.3.0`, "Upgrading
   from Spark SQL 4.2 to 4.3", and the notes are left unread (section 9, limitation 1).
5. **No 256-bit date-chain file was ever committed.** `git log --diff-filter=AD --
   'sql/varka/bench/benchmarks/DateChain*'` lists three commits, all additions: the 512-bit
   chains of `52510090f41` (EPYC 9V45, 2e8 rows), the partition files of `4739d0894d2` and a band
   file. The README's datapath paragraph (lines 260-270) is read from `m4/VARKA-62.md`
   lines 1548-1558: 1e8-row runs on an EPYC 9V74 at 256 bits against a 9V45 at 512 bits, whose
   files were not committed, while the committed 512-bit chain is the 2e8 run. So step 2 owes a
   256-bit chain run at the row count of the 512-bit one.

### 0.1 The inventory

Every place Varka compares with or names stock 4.2.0, from `git grep -E '4\.2\.0|STOCK_VERSION'`
over `README.md`, `.github/`, `dev/` and `sql/varka/`. Upstream Spark's own mentions (`@since`,
config `.version("4.2.0")`, migration notes, checkpoint fixtures) are not Varka's and are left out.
Classes: **switch** (changed by this row's first step), **regenerate** (re-measured on runners,
new files committed), **history** (kept: it records what was measured, or names a fact of 4.2.0),
**keep** (names 4.2.0 for a reason that is not a comparison).

| where | what | class |
| :--- | :--- | :--- |
| `.github/workflows/varka-surface-benchmark.yml:254` | `STOCK_VERSION: 4.2.0`, the stock arms' distribution, cache key and labels (`spark-${STOCK_VERSION}-jdk17`, `-jdk25`, :460-461) | switch |
| `.github/workflows/varka-demo.yml:69`, `:159` | `STOCK_VERSION: 4.2.0` in the `demo` and `compiles-times` jobs; the outputs are named by JDK only (`:128`, and `compiles/run.sh` for `times-jdk25-output.txt`), and the upload paths (`:151`, `:234`) and the comment at `:218-220` spell those names literally | switch, outputs renamed by release, the upload paths and the comment with them (7, decision 6) |
| `.github/workflows/varka-silent-giveups.yml:49` | `STOCK_VERSION: 4.2.0`; outputs named by JDK only (`:113`); the upload glob `*-jdk${{ matrix.java }}-output.txt` (`:138`) also matches a release-named file | switch, outputs renamed by release (7, decision 6) |
| `sql/varka/demo/silent-giveups/examples.scala:44` | the heading "transform has none in 4.2 either (SPARK-37019 adds it in 4.3.0)", which a 4.3.0 run would print over a plan where `transform` has generated code; SPARK-58210 (section 2) may also change the aggregate plans the demo prints | switch: the heading rewritten from the 4.3.0 run's plan, any moved plan recorded in section 10 |
| the three workflows' download steps | the `.sha512` is fetched beside the tarball and compared, nothing is pinned, so there is no checksum to switch | none |
| `.github/workflows/varka-surface-benchmark.yml:255-258` | `VECRUNTIME_SPARK_VERSION: 4.1.3`, the vecruntime plugin's own Spark (VARKA-202), not a stock baseline | keep |
| `sql/varka/bench/pom.xml:46` | `spark.version` 4.2.0, the release the driver compiles against at `provided` scope; the same jar runs the 4.1.3 vecruntime arm | keep (7, decision 7) |
| `sql/varka/bench/src/test/.../ProvenanceTest.java:32` | a version string as test data | keep |
| `sql/varka/bench/src/main/.../DateSurfaceBenchmark.java:57`, `LadderBenchmark.java:32` | usage examples, `--label spark-4.2.0` | switch |
| `LadderBenchmark.java:37-39`, `sql/varka/demo/method_size_cliff.scala:29`, `:73` | "stock Spark 4.2.0 crosses 8000 bytes between 48 and 52 entries", a measured fact of 4.2.0; the script's `require` fails the demo on a release where the crossing moved | history; a refusal on 4.3.0 is a finding (7, decision 6) |
| `dev/varka_bench_surface.sh:42`, `:79-80` | the TIME note's "the same 4.2.0 release" and the usage example's two stock arms | switch |
| `dev/varka_issues.py:256` | a doctest quoting row text | keep |
| `README.md:58-62`, `:232-233`, `:235-237` | the headline "about **10x**" on real arithmetic, from the date chains | regenerate, then requote (step 3) |
| `README.md:58-62`, `:229-231`, `:236` | the headline "up to **39x**" and the 37x of `dayofweek(d)`, from the `TIME` and date surfaces | regenerated by row 553 with the surfaces |
| `README.md:68-69` | "stock Spark 4.2.0 on JDK 17, stock 4.2.0 on JDK 25" | regenerate, then requote |
| `README.md:81`, `:100`, `:165`, `:194` | the four tables' column "vs stock 4.2 (JDK 25)" | regenerate, then requote |
| `README.md:119-124` | the `-ymm` and `abs(ymm)` recheck, `DateSurface-spark-4.2.0-jdk25-ymm-recheck-results.txt` | regenerated by row 553 with the surface |
| `README.md:151-156` | `trunc(d, 'QUARTER')`, a date-surface entry, read against the engine-off arm "because ... its `truncDate` is faster than 4.2.0's"; 4.3.0 has master's `truncDate` (SPARK-56769, section 2) | regenerated by row 553 with the surface (P3) |
| `README.md:158` | the heading "The TIME chains, on the same verified 512-bit machine" | requoted with the chains (step 3) |
| `README.md:245-258` | the methodology: the surface "cannot be measured honestly on a CI runner at all" below about 1.4e9 rows | rewritten by row 553, which runs the surface on runners under a stated lifted bound |
| `README.md:260-287` | the 512-bit datapath's worth, from the three scalar arms (two stock) across machines | regenerate, then requote |
| `README.md:309-324`, `:355` | the reproduction recipe: the 4.2.0 download, its two arms, the diff example's file | switch with the requote |
| `sql/varka/bench/benchmarks/DateChain-spark-4.2.0-jdk{17,25}-results.txt` | the README's chain table, EPYC 9V45, 13 September 2026 | history; 4.3.0 files beside them |
| `sql/varka/bench/benchmarks/TimeChain-spark-4.2.0-jdk{17,25}{,-zen3}-results.txt` | the TIME chain table (9V45) and its EPYC 7763 pair | history; 4.3.0 files beside them |
| `sql/varka/bench/benchmarks/DateSurface-spark-4.2.0-*` (five files), `TimeSurface-spark-4.2.0-*` (two) | the two surface tables, laptop, 1B and 500M rows | history; regenerated on runners by row 553 |
| `sql/varka/bench/benchmarks/VarkaLadder-spark-4.2.0-*` (eight files) | milestone 6's ladder (VARKA-194), EPYC 7763; not in the README | history |
| `sql/varka/demo/method_size_cliff-*-output.txt`, `demo/compiles/*-output.txt`, `demo/silent-giveups/*-output.txt` | the outputs behind the published posts of milestones 6 and 7, named by JDK only | history; the 4.3.0 runs write new release-named files beside them (7, decision 6) |
| `plans/m4` to `plans/m7`: `VARKA-62.md`, `VARKA-194.md`, `VARKA-233.md`, `VARKA-258.md`, the `POST_*.md` files and others | the record of what was measured | history |
| `plans/m8/VARKA-337.md:13-14`, `results/VARKA-337-admission.txt` | 4.2.0's hash classes as a correctness oracle, checked equal to master's but for the calendar interval's arm, which 4.3.0's migration note also records | keep |
| `plans/m8/VARKA-511.md:40`, `:215`, `:259` | a Planned row: the compiles post's precedent on 4.2.0 (history), and the row's own arms against "the released `spark-sql_2.13` 4.2.0" (not yet run) | the arms follow step 4's rule when the row starts; one line in `VARKA-511.md` says so (7, decision 8) |
| `plans/m8/VARKA-541.md:414` | a config's `version` 4.2.0, a fact | keep |
| `plans/m9/SCOPE.md:3571` (item 56), `:4448`, `:4493` (item 80) | future work naming stock 4.2.0; item 56's `method_size_cliff-jdk27-output.txt` predates decision 6's release-named outputs | follow step 4's rule when started; item 56's file takes the form `method_size_cliff-spark-<release>-jdk27-output.txt` |
| `plans/m9/SCOPE.md:4562` (item 83) | a measured move against stock 4.2.0 | history |

`docs/sql-varka.md`, `SKILLS.md` and `sql/varka/skills/` name no stock version.

Kept rather than retired, the 4.2.0 result files: `m5/PLAN.md` cites
`TimeSurface-spark-4.2.0-jdk25-results.txt` by name, the plans of milestones 4 to 7 cite the
others, and a reader following a plan should be able to open the file it names. The 4.3.0 files
take new names (`*-spark-4.3.0-*`), so nothing collides. The Varka and engine-off files of a run
keep their names (`DateChain-varka-jdk25-results.txt`), as every regeneration has, so a 4.2.0
stock file's same-run partners are read at the commit that added them (`git show
52510090f41:...` for the date chains).

### What differs from the instruction

- The instruction's step (b) puts every benchmark on GitHub runners. Two of the README's four
  tables, the date and `TIME` surfaces, are laptop files, and the README says why (lines 245-258):
  a runner's job constant of about 36 ms needs some 1.4 billion rows at the lightest entries, more
  than a runner holds. Their regeneration is already designed and decided: scope item 110 (the
  owner, 18 October 2026) makes it a row of its own, on the runner surface workflow at the rows a
  7 GB driver holds, with the laptop's billion-row files kept as the appendix. That row is now
  filed as row 553 (`VARKA-553.md`, the lead's decision of 2 November 2026). So the surfaces are
  not a step here; until row 553 runs, the README quotes their latest committed files with the
  date, as item 110 says.
- Step (a)'s "download/checksum pins" do not exist: each workflow fetches the release's own
  `.sha512` beside the tarball. The only edit is `STOCK_VERSION`.
- Step (a) itself starts CI without a dispatch. `varka-demo.yml` and `varka-silent-giveups.yml`
  list their own file under `push.paths`, and `build_main.yml` runs on a push to any branch
  (section 3).

## 1. Goal and non-goal

**Goal.** Every comparison Varka publishes against stock Spark - the README's four tables and the
paragraphs built on them, and the workflows that make them - is against Spark 4.3.0, from files
committed with their provenance, and the house rules say that the next feature release is
switched the same way.

**Non-goal.** Re-measuring history: the posts of milestones 4 to 7, their demo outputs and the
ladder stay as measured on 4.2.0. The fork's own Janino arm in the micro-benchmarks
(`sql/core/benchmarks/`, README "vs stock Spark (Janino)") is the fork's row engine, not a
release, and is not touched. Nor are the fork's own Spark version or its upstream sync.

## 2. What 4.3.0 changes for the comparisons

Each is in the 4.3.0 line (`0eb4fc1d97d..v4.3.0`), in none of 4.2.0's 360 commits, and in the
fork's master (0, item 3), checked by JIRA id.

| change | what it does | which comparison it can move |
| :--- | :--- | :--- |
| SPARK-57909 (`758afe7de02`) | `ColumnarToRowExec`'s batch-advance code moved into a compiled helper | every stock arm: each reads the cached table through `ColumnarToRowExec` |
| SPARK-57169 (`68dae369983`) | `next_day`'s ANSI codegen without the rethrowing `try`/`catch` | the date chain over `next_day` |
| SPARK-56769 (`f7dcb5a8bb4`; in the fork's master as `3dc736c8186`) | a fast path for `date_trunc` above SECOND, and `truncDate`'s `TRUNC_TO_QUARTER` case rewritten from `IsoFields.DAY_OF_QUARTER` to `withMonth(...).withDayOfMonth(1)`; `truncDate` at `v4.3.0` is now identical to `origin/master`'s | `trunc(d, 'QUARTER')` on the date surface, row 553's (P3) |
| SPARK-56915 (`8ce1a124ba1`), SPARK-57168, SPARK-57167 | `MakeDate`, `MakeInterval`, `GetTimestamp` and `MakeTimestamp` codegen simplified under ANSI | none of the four tables' entries uses them; m8's types if a later table does |
| SPARK-56913 (`a8a156fca1f`), SPARK-56914 (`ca7fa663a2b`), SPARK-57198 (`57adc83df58`), SPARK-57181 (`1c6ed48edf4`), SPARK-57902 (`a8c1bce0996`) | byte and short arithmetic, decimal arithmetic under ANSI, the zero check of a literal divisor, `pmod`, a two-argument `coalesce` | no README entry today; milestone 8's `TINYINT`, `SMALLINT` and `DECIMAL` rows, whenever they are set beside a release |
| SPARK-57268 (`7e43efecc45`) and follow-ups | `ArrowCachedBatchSerializer` ships in a release, opt-in through `spark.sql.cache.serializer`; the fork's copy has moved on since (9 files, 1145 insertions in `execution/columnar/` from `v4.3.0` to master) | none at defaults; it makes a stock arm over the Arrow cache possible, scope item 132 (7, decision 2) |
| SPARK-57370 (`827d26a7c23`) | `spark.sql.codegen.compiler`, internal, default `janino` | none at defaults |
| SPARK-58210 (`00f3be78f2e`) | `replaceHashWithSortAgg` and `combineAdjacentAggregation` default to `true` | the counted rows' `COUNT(*)` plans, if either rule rewrites a global aggregate there **[I]** |
| `spark.sql.timeType.enabled` | still internal and off outside tests at `v4.3.0` (`SQLConf.scala:8316`) | none: the driver turns it on for every arm; `TIME`'s precision grew to nanoseconds (SPARK-57551), and whether the driver's microsecond `TIME` columns read the same is **[I]** until the first `TIME` run |

`git log -S truncDate v4.2.0..v4.3.0 -- sql/` finds one commit, SPARK-56769's, and the reverse
range none. So the reason the README gives for quoting `trunc(d, 'QUARTER')` against the
engine-off arm, a fork `truncDate` faster than the release's, no longer holds against 4.3.0.

## 3. The steps, in order, and the CI each starts

The owner runs CI by hand and approves every dispatch; each step names what it starts.

1. **The workflows and the examples switch** (one commit): `STOCK_VERSION: 4.3.0` in the three
   workflows, the usage examples of `dev/varka_bench_surface.sh`, `DateSurfaceBenchmark.java` and
   `LadderBenchmark.java`, and the TIME note in the script. The demo workflows' outputs are renamed
   by release, `method_size_cliff-spark-4.3.0-jdk25-output.txt` and the like (the `out=` lines of
   both workflows and `compiles/run.sh`, and with them `varka-demo.yml`'s upload paths at `:151`
   and `:234` and its comment at `:218-220`, which spell the names literally), so the 4.2.0
   outputs behind the published posts stay as their evidence under their own names (7, decision
   6). The README's recipe waits for step 3, so the README never names a release its tables do not
   quote.
   *CI:* the push starts the Build (`build_main.yml`, every branch), and through their `push.paths`
   `varka-demo.yml` (three `method_size_cliff` jobs and one `compiles-times`) and
   `varka-silent-giveups.yml` (three jobs). Their outputs are artifacts; the ones read are
   committed under the new names; committing the `method_size_cliff` and `compiles` outputs under
   `sql/varka/demo/` starts `varka-demo.yml` again through its `push.paths`
   (`sql/varka/demo/**`), while the silent-give-ups outputs start nothing, since that workflow
   watches only its `*.scala` and `*.log4j2.properties`. If `method_size_cliff.scala`'s `require`
   refuses on 4.3.0
   because the crossing moved off 48 to 52 entries, that refusal is a finding recorded in
   section 10 (and P4 is scored missed), not a blocker of the row. If the owner wants no runs he
   has not dispatched, the demo workflows' edit goes in its own commit pushed when he says. The
   first run also fills a new cache entry, `stock-spark-4.3.0-hadoop3`,
   about 400 MB, on a fork whose cache is at its quota (`VARKA-531.md` 0, item 4), so other entries
   are evicted.
2. **The chains re-measured on runners against 4.3.0**, every arm in one run, results committed by
   hand after their provenance is read (`create-commit=false`):
   - one `stop-after=build` dispatch, any runner, to warm the commit's jar cache;
   - `benchmark=chains`, 2e8 rows, `require-datapath=512`: dispatched in batches of ten to fifteen,
     since about one runner in eighteen has a full-width datapath (README), so a batch of ten
     lands one with a chance of about 44% and a batch of fifteen about 58% (`1 - (17/18)^n`);
     writes `DateChain-spark-4.3.0-jdk17` and `-jdk25`, and new `DateChain-varka-off-jdk25` and
     `DateChain-varka-jdk25`;
   - `benchmark=chains` at 256 bits, the run 0, item 5 found missing: 2e8 rows,
     `require-datapath=any`, `expected-cpu="AMD EPYC 7763"`, in the same dispatch series as the
     `TIME` pair below, committed with the `-zen3` suffix as that pair is. **[I]**: the workflow's
     `rows` note says 2e8 was not resident on the runners of September 2026; if the 7763 cannot
     hold it, the 512-bit chains run at 1e8 as well, so the pair matches, as `m4/VARKA-62.md`'s
     1e8 pair did;
   - `benchmark=timechains`, 1e8 rows, `max-fixed-share=11`, `require-datapath=512`, batched the
     same way: `TimeChain-spark-4.3.0-*` and its Varka arms;
   - `benchmark=timechains` on an EPYC 7763 (`require-datapath=any`, `expected-cpu="AMD EPYC
     7763"`), retried hourly on a CPU miss and never relaxed, by the owner's word of 27 October
     2026 that benchmark runs are on an EPYC 7763: the `-zen3` files the datapath section reads.
   *CI:* one build dispatch, then two batches of ten to fifteen `run` dispatches and a few 7763
   attempts (the date and `TIME` chains), each the owner's. They wait for any running option
   matrix, as the 40-slot rule asks; each uses one job slot.
   The Varka arms are re-measured on the milestone 8 build as a side effect, since every arm runs
   in one invocation. These are the files row 354 then quotes; the step does not wait for the
   close, and a later engine change is regenerated by its own row, as scope item 110 says. The
   two surfaces are not in this step: row 553 (`VARKA-553.md`) regenerates them on runners.
3. **The README rewritten to quote the new files only**: lines 68-69, the two chain tables'
   column headers, rows and spans, the "about 10x" headline at lines 58-62 and its paragraph at
   232-237, the heading at line 158 ("on the same verified 512-bit machine", kept only if both
   chain files name the same CPU model and a full-width probe), the datapath section (260-287)
   from the new 512-bit and `-zen3` chain files, and the recipe and diff example at 4.3.0. The
   two surface tables, their paragraphs (the `trunc(d, 'QUARTER')` one at 151-156 included), the
   "up to 39x" headline and the methodology at 245-258 are row 553's; until it runs, the surface
   tables keep their 4.2.0 stock column, dated and labelled. In the same commit,
   `silent-giveups/examples.scala:44`'s `transform` heading is rewritten from what step 1's first
   4.3.0 run printed; the edit starts `varka-silent-giveups.yml` once more through its
   `push.paths`, and that run's outputs are the ones committed. `dev/varka_bench_diff.py --git HEAD
   <file> --requote` on each regenerated Varka file lists every document line quoting a moved
   number; `dev/varka_quote_check.py` holds every quoted figure to a committed file. A line that
   quotes a 4.2.0 file on purpose says so.
   *CI:* the push starts the Build, whose docs job runs the quote check.
4. **A standing rule in `sql/varka/AGENTS.md`**, under "Measurements, not adjectives", beside the
   regeneration rules: *"A comparison with stock Spark is with its newest feature release. A new
   release is switched by a row of the milestone in flight: the workflows' `STOCK_VERSION`, the
   comparisons re-measured on the runners, the README requoted from the new files, and the old
   release's files kept as the record. A row planned before the switch and not yet run measures
   against the new release."* It ships in the same pull request as step 3, so the rule and its
   first application land together.
   *CI:* none beyond step 3's push.

The steps are two pull requests: steps 1 and 2's dispatches on the first (the dispatches need the
workflow change on the branch they run on), and steps 2's results, 3 and 4 on the second.

## 4. Predictions, registered before the runs

- P1: the stock 4.3.0 arms move toward the engine-off arm rather than away, since the fork already
  carries every change of section 2. So every chain ratio against stock falls or holds, and the
  gap between the two baselines narrows.
- P2: the entry most likely to move is the date chain over `next_day` (SPARK-57169), and every
  entry moves a little through SPARK-57909 if its read-back is a share of its time; the date
  chains' stock rate rises by no more than the flat threshold on most entries.
- P3: the two baselines agree on `trunc(d, 'QUARTER')` within the README's 20%, since
  SPARK-56769 gave 4.3.0 the same `truncDate` as the fork's master; so the README's one entry
  quoted against the engine-off arm instead of stock goes back to stock. It is a date-surface
  entry, so it is scored in `VARKA-553.md` from row 553's run.
- P4: the method-size cliff's crossing on 4.3.0 is still between 48 and 52 entries, so the demo's
  `require` passes. **[I]**: none of section 2 touches `add_months`, `date_add`, `last_day` or
  `greatest`, but the projection's other generated code was not diffed. A refusal scores P4
  missed and is recorded in section 10 with the crossing 4.3.0 shows (7, decision 6).

## 5. Order, files and size

| file | change |
| :--- | :--- |
| `.github/workflows/varka-surface-benchmark.yml`, `varka-demo.yml`, `varka-silent-giveups.yml` | `STOCK_VERSION`; the demo outputs named by release |
| `sql/varka/demo/compiles/run.sh` | its output named by release |
| `dev/varka_bench_surface.sh`, `DateSurfaceBenchmark.java`, `LadderBenchmark.java` | examples and the TIME note |
| `sql/varka/bench/benchmarks/DateChain-*`, `TimeChain-*` | the 4.3.0 stock files, new; the Varka and engine-off files, regenerated |
| `sql/varka/demo/**/*-spark-4.3.0-*-output.txt` | the demo outputs of the first passing runs, new |
| `README.md` | step 3 |
| `sql/varka/AGENTS.md` | step 4's rule |
| `sql/varka/demo/silent-giveups/examples.scala` | the `transform` heading |
| `sql/varka/plans/m8/VARKA-552.md` | section 10 with P1, P2 and P4 scored |

Size: medium. The edits are small; the runner batches and the requote are not.

## 6. Out of this row

- The surfaces on runners: row 553 (scope item 110's mechanism).
- A stock 4.3.0 arm over the Arrow cache: scope item 132.
- Regenerating the milestone 6 ladder on 4.3.0, or rewriting the published posts from the new
  demo outputs.
- Comparing milestone 8's new types with a release: no README table does yet.
- Upgrading the fork itself; it tracks Spark master.

## 7. Decisions (the lead, 2 November 2026)

The first draft of this plan left three questions for the owner and five open points; the lead
settled all of them on 2 November 2026. One is for the owner to confirm: decision 3 extends his
instruction.

1. **The surface row is filed now**, as row 553 (`VARKA-553.md`), Planned, by scope item 110's
   settled mechanism: a row of its own, on the runner surface workflow, the laptop's billion-row
   files kept as the appendix. Until it runs, the README's surface tables keep their 4.2.0 stock
   column, dated and labelled as such.
2. **A stock 4.3.0 arm over the Arrow cache is not in this row.** It is recorded as scope item 132
   (`m9/SCOPE.md`).
3. **Milestone 8's done-when item 8 names rows 552 and 553**: the README's comparisons are
   against the newest stock feature release at the close. This is the lead's extension of the
   owner's instruction, for the owner to confirm: it makes the milestone 8 close wait on both
   rows.
4. **The Jira release notes stay unread**, a stated limitation (9, limitation 1); the
   `v4.2.0..v4.3.0` history and the migration guide are the substitute.
5. **The 256-bit date chain is re-run**: no such file was ever committed (0, item 5), so step 2
   owes a 256-bit run at the 512-bit run's row count.
6. **The demo workflows switch `STOCK_VERSION`** and write new release-named output files, keeping
   the 4.2.0 outputs as the published posts' evidence. If `method_size_cliff.scala` refuses because
   the crossing moved in 4.3.0, the refusal is a finding the row records, not a blocker.
7. **The bench driver stays on its compile version** (`sql/varka/bench/pom.xml`, 4.2.0 at
   `provided` scope): the same jar runs the vecruntime arm on 4.1.3, and the driver is not a
   comparison target. Whether the 4.2.0-compiled driver runs on the 4.3.0 distribution is an
   open question **[I]** that step 2's first `run` dispatch answers; a linkage failure there is
   recorded in section 10 and the driver's version goes back to the lead.
8. **Row 511** gets one line in `VARKA-511.md` (not its `PLAN.md` row) saying its arms follow the
   new comparison rule when it starts.
9. **The provenance claim was checked** with `git merge-base` and restated in 0, item 3: the 1771
   commits are the 4.3.0 line from the fork point, not a delta over 4.2.0, and SPARK-56032's
   `FilterExec` follow-ups, on both lines, left section 2.

## 8. Done when

- the surface benchmark workflow and the two demo workflows run stock 4.3.0, each with one run on
  it (a `method_size_cliff` refusal recorded as a finding counts as that run);
- `DateChain-spark-4.3.0-jdk{17,25}-results.txt`, `TimeChain-spark-4.3.0-jdk{17,25}-results.txt`
  and the `-zen3` pair are committed with the Varka and engine-off files of the same runs, and the
  datapath paragraph's 256-bit date-chain comparison rests on the new `-zen3` chain files;
- `git grep -n -E '4\.2\.0|stock 4\.2' README.md` shows only lines that say they quote 4.2.0 on
  purpose (the surface tables until row 553 runs; a bare `4\.2` also matches measurements such as
  the 4.2 ns/row at line 167), and `dev/varka_quote_check.py` passes;
- `sql/varka/AGENTS.md` carries the rule of step 4;
- P1, P2 and P4 are scored in section 10 (P3 in `VARKA-553.md`).

## 9. Limitations

1. The Jira release notes could not be read (login page twice). Section 2 is from the tag's
   history and migration guide; a performance change outside `sql/` (core's shuffle or memory,
   which the single-partition cached runs barely touch) could have been missed.
2. The JIRA-id counts of 0, item 3 match changes by the id in a commit subject, not by patch: a
   change carried under another id, or an id reused by a follow-up, is counted by its id only. The
   section 2 entries name their commits.

## 10. Outcome

Not started.
