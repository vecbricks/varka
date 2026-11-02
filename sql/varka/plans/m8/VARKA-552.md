# VARKA-552: Varka's stock-Spark comparisons move from 4.2.0 to 4.3.0

## 0. What exists now (admission check)

*The owner, 2 November 2026:* "Spark 4.3.0 has been released recently, see
https://spark.apache.org/releases/spark-release-4-3-0.html. We should plan tasks and compare with
this version instead of 4.2.0 in the current milestone and in the future. Also need to update the
README."

Read from master at `4b5948d72d9` on 2 November 2026, with the release tag `v4.3.0` (`98bc0212c94`,
"Preparing Spark release v4.3.0-rc3", 27 October 2026) fetched from apache/spark. Nothing was
dispatched and nothing was run but `git`.

1. **The release.** Spark 4.3.0 was announced on 30 October 2026. Its binary,
   `spark-4.3.0-bin-hadoop3.tgz`, is on `archive.apache.org/dist/spark/spark-4.3.0/` (the lead
   checked HTTP 200). Whether its `.sha512` is served beside it, as the workflows' download step
   expects, is **[I]**: the first run of step 1 shows it.
2. **The row id.** The highest row number in any table of `plans/*/PLAN.md` and `plans/*/SCOPE.md`
   is 551 (m8 rows 547 to 551, added 1 and 2 November); the newest plan file is `VARKA-546.md`.
   `VARKA-901` and `VARKA-902` in `m7/VARKA-384.md` are item labels inside a quoted example, not
   rows. No branch `varka-55*` exists locally or on origin, and none of the six open pull requests
   on vecbricks/varka (#1291, #1303 to #1307) adds a row numbered 552 or more. So this row is 552.
3. **What changed between the two releases.** `git log v4.2.0..v4.3.0` has 1771 commits. Both tags
   branch from master at the same commit, `0eb4fc1d97d` (1 May 2026), so 4.3.0's changes are
   backports to `branch-4.x`. Every change of section 2 is also in this fork's master, found by its
   JIRA id in `git log 0eb4fc1d97d..origin/master`, and none is in 4.2.0. So the fork's engine-off
   arm, the control that separates Varka from "a newer Spark", already carries all of them.
   Dependencies are unchanged between the tags: Janino 3.1.9, Arrow 19.0.0, Parquet 1.17.0,
   Scala 2.13.18, and Java 17, 21 and 25 supported (`pom.xml`, `docs/index.md` at each tag).
4. **The JIRA release notes** (`ReleaseNote.jspa?projectId=12315420&version=12356944`, linked
   from the release page) were fetched twice and returned only the ASF Jira login page. Section 2
   rests on the tag's own history and on `docs/sql-migration-guide.md` at `v4.3.0`, "Upgrading
   from Spark SQL 4.2 to 4.3", and the notes are left unread (section 9, point 1).

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
| `.github/workflows/varka-demo.yml:69`, `:159` | `STOCK_VERSION: 4.2.0` in the `demo` and `compiles-times` jobs | switch, or keep (9, point 3) |
| `.github/workflows/varka-silent-giveups.yml:49` | `STOCK_VERSION: 4.2.0` | switch, or keep (9, point 3) |
| the three workflows' download steps | the `.sha512` is fetched beside the tarball and compared, nothing is pinned, so there is no checksum to switch | none |
| `.github/workflows/varka-surface-benchmark.yml:255-258` | `VECRUNTIME_SPARK_VERSION: 4.1.3`, the vecruntime plugin's own Spark (VARKA-202), not a stock baseline | keep |
| `sql/varka/bench/pom.xml:46` | `spark.version` 4.2.0, the release the driver compiles against at `provided` scope; the same jar runs the 4.1.3 vecruntime arm | keep (9, point 4) |
| `sql/varka/bench/src/test/.../ProvenanceTest.java:32` | a version string as test data | keep |
| `sql/varka/bench/src/main/.../DateSurfaceBenchmark.java:57`, `LadderBenchmark.java:32` | usage examples, `--label spark-4.2.0` | switch |
| `LadderBenchmark.java:37-39`, `sql/varka/demo/method_size_cliff.scala:29`, `:73` | "stock Spark 4.2.0 crosses 8000 bytes between 48 and 52 entries", a measured fact of 4.2.0; the script's `require` fails the demo on a release where the crossing moved | history, re-read after step 2 |
| `dev/varka_bench_surface.sh:42`, `:79-80` | the TIME note's "the same 4.2.0 release" and the usage example's two stock arms | switch |
| `dev/varka_issues.py:256` | a doctest quoting row text | keep |
| `README.md:68-69` | "stock Spark 4.2.0 on JDK 17, stock 4.2.0 on JDK 25" | regenerate, then requote |
| `README.md:81`, `:100`, `:165`, `:194` | the four tables' column "vs stock 4.2 (JDK 25)" | regenerate, then requote |
| `README.md:119-124` | the `-ymm` and `abs(ymm)` recheck, `DateSurface-spark-4.2.0-jdk25-ymm-recheck-results.txt` | requote or drop with the surface (7, question 1) |
| `README.md:151-156` | `trunc(d, 'QUARTER')` read against the engine-off arm "because ... its `truncDate` is faster than 4.2.0's" | regenerate, then rewrite from the new run |
| `README.md:260-287` | the 512-bit datapath's worth, from the three scalar arms (two stock) across machines | regenerate, then requote |
| `README.md:309-324`, `:355` | the reproduction recipe: the 4.2.0 download, its two arms, the diff example's file | switch with the requote |
| `sql/varka/bench/benchmarks/DateChain-spark-4.2.0-jdk{17,25}-results.txt` | the README's chain table, EPYC 9V45, 13 September 2026 | history; 4.3.0 files beside them |
| `sql/varka/bench/benchmarks/TimeChain-spark-4.2.0-jdk{17,25}{,-zen3}-results.txt` | the TIME chain table (9V45) and its EPYC 7763 pair | history; 4.3.0 files beside them |
| `sql/varka/bench/benchmarks/DateSurface-spark-4.2.0-*` (five files), `TimeSurface-spark-4.2.0-*` (two) | the two surface tables, laptop, 1B and 500M rows | history; regeneration per 7, question 1 |
| `sql/varka/bench/benchmarks/VarkaLadder-spark-4.2.0-*` (eight files) | milestone 6's ladder (VARKA-194), EPYC 7763; not in the README | history |
| `sql/varka/demo/method_size_cliff-*-output.txt`, `demo/compiles/*-output.txt`, `demo/silent-giveups/*-output.txt` | the outputs behind the published posts of milestones 6 and 7, named by JDK only | history (9, point 3) |
| `plans/m4` to `plans/m7`: `VARKA-62.md`, `VARKA-194.md`, `VARKA-233.md`, `VARKA-258.md`, the `POST_*.md` files and others | the record of what was measured | history |
| `plans/m8/VARKA-337.md:13-14`, `results/VARKA-337-admission.txt` | 4.2.0's hash classes as a correctness oracle, checked equal to master's but for the calendar interval's arm, which 4.3.0's migration note also records | keep |
| `plans/m8/VARKA-511.md:40`, `:215`, `:259` | a Planned row: the compiles post's precedent on 4.2.0 (history), and the row's own arms against "the released `spark-sql_2.13` 4.2.0" (not yet run) | the arms follow step 4's rule when the row starts (9, point 5) |
| `plans/m8/VARKA-541.md:27` | a config's `version` 4.2.0, a fact | keep |
| `plans/m9/SCOPE.md:3571` (item 56), `:4448`, `:4493` (item 80) | future work naming stock 4.2.0 | follow step 4's rule when started |
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
  7 GB driver holds, with the laptop's billion-row files kept as the appendix. That row is not
  filed. So the surfaces are not a step here; until it runs, the README quotes their latest
  committed files with the date, as item 110 says, and whether to file it now is question 1.
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

Each is in `v4.2.0..v4.3.0` and not in 4.2.0, and is in the fork's master (0, item 3).

| change | what it does | which comparison it can move |
| :--- | :--- | :--- |
| SPARK-57909 (`758afe7de02`) | `ColumnarToRowExec`'s batch-advance code moved into a compiled helper | every stock arm: each reads the cached table through `ColumnarToRowExec` |
| SPARK-57169 (`68dae369983`) | `next_day`'s ANSI codegen without the rethrowing `try`/`catch` | the date chain over `next_day` |
| SPARK-56915 (`8ce1a124ba1`), SPARK-57168, SPARK-57167 | `MakeDate`, `MakeInterval`, `GetTimestamp` and `MakeTimestamp` codegen simplified under ANSI | none of the four tables' entries uses them; m8's types if a later table does |
| `0abe65a2432` (SPARK-56032 follow-up) | `FilterExec` skips its subexpression-elimination prologue when the predicates share nothing | the surfaces' filter rows |
| SPARK-56913 (`a8a156fca1f`), SPARK-56914 (`ca7fa663a2b`), SPARK-57198 (`57adc83df58`), SPARK-57181 (`1c6ed48edf4`), SPARK-57902 (`a8c1bce0996`) | byte and short arithmetic, decimal arithmetic under ANSI, the zero check of a literal divisor, `pmod`, a two-argument `coalesce` | no README entry today; milestone 8's `TINYINT`, `SMALLINT` and `DECIMAL` rows, whenever they are set beside a release |
| SPARK-57268 (`7e43efecc45`) and follow-ups | `ArrowCachedBatchSerializer` ships in a release, opt-in through `spark.sql.cache.serializer`; the fork's copy has moved on since (9 files, 1145 insertions in `execution/columnar/` from `v4.3.0` to master) | none at defaults; it makes a stock arm over the Arrow cache possible (7, question 2) |
| SPARK-57370 (`827d26a7c23`) | `spark.sql.codegen.compiler`, internal, default `janino` | none at defaults |
| SPARK-58210 (`00f3be78f2e`) | `replaceHashWithSortAgg` and `combineAdjacentAggregation` default to `true` | the counted rows' `COUNT(*)` plans, if either rule rewrites a global aggregate there **[I]** |
| `spark.sql.timeType.enabled` | still internal and off outside tests at `v4.3.0` (`SQLConf.scala:8316`) | none: the driver turns it on for every arm; `TIME`'s precision grew to nanoseconds (SPARK-57551), and whether the driver's microsecond `TIME` columns read the same is **[I]** until the first `TIME` run |

`git log -S truncDate v4.2.0..v4.3.0 -- sql/` finds no change to `truncDate`, the reason the README
gives for quoting `trunc(d, 'QUARTER')` against the engine-off arm (SPARK-56769 is `date_trunc` on
timestamps).

## 3. The steps, in order, and the CI each starts

The owner runs CI by hand and approves every dispatch; each step names what it starts.

1. **The workflows and the examples switch** (one commit): `STOCK_VERSION: 4.3.0` in the three
   workflows, the usage examples of `dev/varka_bench_surface.sh`, `DateSurfaceBenchmark.java` and
   `LadderBenchmark.java`, and the TIME note in the script. The README's recipe waits for step 3,
   so the README never names a release its tables do not quote.
   *CI:* the push starts the Build (`build_main.yml`, every branch), and through their `push.paths`
   `varka-demo.yml` (three `method_size_cliff` jobs and one `compiles-times`) and
   `varka-silent-giveups.yml` (three jobs). Their outputs are artifacts, not commits. If the owner
   wants no runs he has not dispatched, the demo workflows' edit goes in its own commit pushed when
   he says (9, point 3). The first run also fills a new cache entry, `stock-spark-4.3.0-hadoop3`,
   about 400 MB, on a fork whose cache is at its quota (`VARKA-531.md` 0, item 4), so other entries
   are evicted.
2. **The chains re-measured on runners against 4.3.0**, every arm in one run, results committed by
   hand after their provenance is read (`create-commit=false`):
   - one `stop-after=build` dispatch, any runner, to warm the commit's jar cache;
   - `benchmark=chains`, 2e8 rows, `require-datapath=512`: dispatched in batches of ten to fifteen,
     since about one runner in eighteen has a full-width datapath (README); writes
     `DateChain-spark-4.3.0-jdk17` and `-jdk25`, and new `DateChain-varka-off-jdk25` and
     `DateChain-varka-jdk25`;
   - `benchmark=timechains`, 1e8 rows, `max-fixed-share=11`, `require-datapath=512`, batched the
     same way: `TimeChain-spark-4.3.0-*` and its Varka arms;
   - `benchmark=timechains` on an EPYC 7763 (`require-datapath=any`, `expected-cpu="AMD EPYC
     7763"`), retried hourly on a CPU miss and never relaxed, as the benchmark rule says: the
     `-zen3` files the datapath section reads.
   *CI:* one build dispatch, then two batches of ten to fifteen `run` dispatches and one to a few
   7763 attempts, each the owner's. They wait for any running option matrix, as the 40-slot rule
   asks; each uses one job slot.
   The Varka arms are re-measured on the milestone 8 build as a side effect, since every arm runs
   in one invocation. These are the files row 354 then quotes; the step does not wait for the
   close, and a later engine change is regenerated by its own row, as scope item 110 says.
3. **The README rewritten to quote the new files only**: lines 68-69, the four column headers, the
   chain tables' rows and spans, the `trunc(d, 'QUARTER')` paragraph rewritten from what the new
   run shows, the datapath section from the new chain and `-zen3` files, and the recipe and diff
   example at 4.3.0. The surfaces follow question 1. `dev/varka_bench_diff.py --git HEAD <file>
   --requote` on each regenerated Varka file lists every document line quoting a moved number;
   `dev/varka_quote_check.py` holds every quoted figure to a committed file. A line that quotes a
   4.2.0 file on purpose says so.
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
- P3: the `trunc(d, 'QUARTER')` gap between the two baselines stays, since no `truncDate` change
  is in the range.
- P4: the method-size cliff's crossing on 4.3.0 is still between 48 and 52 entries, so the demo's
  `require` passes. **[I]**: none of section 2 touches `add_months`, `date_add`, `last_day` or
  `greatest`, but the projection's other generated code was not diffed.

## 5. Order, files and size

| file | change |
| :--- | :--- |
| `.github/workflows/varka-surface-benchmark.yml`, `varka-demo.yml`, `varka-silent-giveups.yml` | `STOCK_VERSION` |
| `dev/varka_bench_surface.sh`, `DateSurfaceBenchmark.java`, `LadderBenchmark.java` | examples and the TIME note |
| `sql/varka/bench/benchmarks/DateChain-*`, `TimeChain-*` | the 4.3.0 stock files, new; the Varka and engine-off files, regenerated |
| `README.md` | step 3 |
| `sql/varka/AGENTS.md` | step 4's rule |
| `sql/varka/plans/m8/VARKA-552.md` | section 10 with P1 to P4 scored |

Size: medium. The edits are small; the runner batches and the requote are not.

## 6. Out of this row

- The surfaces on runners: scope item 110.
- Regenerating the milestone 6 ladder or the posts' demo outputs on 4.3.0.
- Comparing milestone 8's new types with a release: no README table does yet.
- Upgrading the fork itself; it tracks Spark master.

## 7. Questions for the owner

1. **When the surface row is filed.** How the two surface tables are regenerated is scope item
   110's design, the owner's of 18 October 2026: a row of its own, on the runner surface workflow
   at the rows a 7 GB driver holds, the laptop's billion-row files kept as the appendix. Until it
   runs, the README's surface tables keep their 4.2.0 stock column, dated and labelled as such,
   which is item 110's own rule for the close. What is open is only when: (a) file that row now in
   milestone 8, the next id after this one, so the surface tables are on 4.3.0 before the close;
   or (b) leave it to the close. Recommended: (a), since the owner asked for 4.3.0 in this
   milestone and the close is the first to run item 110's way.
2. **A stock arm over the Arrow cache.** 4.3.0 ships `ArrowCachedBatchSerializer`, so
   `spark-4.3.0-arrow-cache-jdk25` (the script's `arrow-cache` token on the stock home) would
   separate what the cache format is worth from the kernels on a release, which today only the
   fork's own `varka-cache` arm does. Add it to the chains now, later, or not? Recommended: not in
   this row; a row of its own if wanted, since it adds an arm to every table and the fork's
   serializer has moved on from the release's.
3. **Milestone 8's done-when.** This row is in milestone 8 by the owner's word but serves no item
   of 1.1 directly. Should 1.1 item 8 say that the README's comparisons are against the newest
   stock feature release (552)? Recommended: yes, one clause, so the close's reading of 1.1 checks
   it.

## 8. Done when

- the surface benchmark workflow runs stock 4.3.0, and the demo workflows as 9, point 3 is
  answered, each with one passing run on what it then runs;
- `DateChain-spark-4.3.0-jdk{17,25}-results.txt`, `TimeChain-spark-4.3.0-jdk{17,25}-results.txt`
  and the `-zen3` pair are committed with the Varka and engine-off files of the same runs;
- `git grep -n '4\.2' README.md` shows only lines that say they quote 4.2.0 on purpose (the
  surfaces under question 1's answer (c)), and `dev/varka_quote_check.py` passes;
- `sql/varka/AGENTS.md` carries the rule of step 4;
- P1 to P4 are scored in section 10.

## 9. Open points for the lead

1. The JIRA release notes could not be read (login page twice). Section 2 is from the tag's
   history and migration guide; a reader with an ASF Jira session could check that no performance
   change outside `sql/` was missed (core's shuffle or memory, which the single-partition cached
   runs barely touch).
2. The README's date-chain datapath paragraph (lines 260-270) compares the chains on "a 256-bit
   machine and the 512-bit one"; the committed `DateChain-*` files are all from the EPYC 9V45 or
   the laptop. The 256-bit date-chain files it read were not found under `benchmarks/`. The row
   finds them (perhaps in a milestone 4 results folder) or reruns that arm before requoting the
   paragraph; which is the lead's call.
3. **The demo workflows.** Their outputs are named by JDK only and back published posts. Switching
   `STOCK_VERSION` and committing the next outputs would overwrite those posts' evidence (git
   history keeps it, and the quote check reads history). Options: keep the demos at 4.2.0, the
   release the posts name; switch them and name new outputs by release
   (`method_size_cliff-spark-4.3.0-jdk25-output.txt`), a script change; or switch and never commit
   their outputs again. Not settled here; scope item 56's JDK 27 rung waits on the answer too.
4. **The driver's compile version.** `sql/varka/bench/pom.xml` compiles against 4.2.0 at
   `provided` scope, and the same jar runs the vecruntime arm on 4.1.3. This plan keeps it, since
   compiling against 4.3.0 could stop the jar running on 4.1.3. If the lead prefers the jar built
   against the release it measures, the vecruntime arm's run is the check.
5. Row 511 is Planned with arms against "the released `spark-sql_2.13` 4.2.0". Under step 4's rule
   it would build against 4.3.0 when it starts; whether to note that in its row now or leave it to
   the rule is the lead's call.

## 10. Outcome

Not started.
