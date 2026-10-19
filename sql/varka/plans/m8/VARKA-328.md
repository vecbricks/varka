# VARKA-328: the census and the cast matrix kept true

*Row 328 of `m8/PLAN.md`, wave 0. Planned on 16 October 2026, with the record and the code read at
`8a50f61a368`; nothing was built for this plan. Section 2 says what reading settled and which
checks still need a run.*

## 1. Where this came from

Milestone 8's done-when list counts its progress in two committed files, `m8/census.tsv` (183
in-scope entries, 881 entry-and-type pairs, 96 pairs fused today) and `m8/casts.tsv` (106 legal
cast pairs), both written by `dev/varka_census.py` (`m8/PLAN.md` 2.1 and 2.2). Item 2 of 1.1 says
the share of fused pairs is the milestone's headline, and item 3 says the cast matrix is
transcribed from Spark. Neither file is checked by anything today, so a coverage row that stops
fusing, a registry change upstream, or a hand edit of the curated half can move the count without
anyone noticing. Row 328 makes every later count honest: the script's `--check` in CI, a suite
holding the matrix to `Cast` itself, and the coverage rows that turn the census's fused claims into
one class per row. It is wave 0 because every lane row after it reports its progress through these
files.

## 2. The admission check, done

What reading the code and the record settled, at `8a50f61a368`:

1. **`--check` exists and is cheap.** `dev/varka_census.py --check` (declared at line 835) runs the
   full build, fails on any of its errors (840 to 844), then renders both files in memory and
   fails if either differs from the committed one (845 to 849). It is standard-library Python and
   needs no Spark build: run on cores 3 to 5 it returned 0 in under a second. So the CI step costs
   nothing measurable, and no cost prediction is needed for it.
2. **Nothing runs it.** Neither `dev/varka_census.py` nor `dev/varka_catalogue_move.sh` appears
   in any `.github/workflows` file, in `dev/varka_precommit.sh`, `dev/varka_nightly.sh` or
   `dev/varka_gate.sh`. Varka's document checks run in the `varka-docs` job of
   `.github/workflows/build_and_test.yml` (lines 1346 to 1360: `varka_quote_check.py` at 1358,
   `varka_toc.py --check` at 1360), not in Spark's `lint` job. That job is plain Python on
   `ubuntu-latest` with a full-depth checkout, so the two new steps fit there.
3. **The job's trigger misses the census's inputs.** `varka-docs` runs only when
   `is-changed.py -m varka-docs` says so (`build_and_test.yml` 283 and 1348), whose patterns are
   `varka_docs_regexes` in `dev/sparktestsupport/modules.py` (89 to 103). They include
   `sql/varka/plans/` but not `dev/varka_census.py`, `dev/varka_catalogue_move.sh`,
   `sql/varka/coverage.json` (claimed by the catalyst module, line 592) or
   `FunctionRegistry.scala`. A pull request that changes only the registry or the coverage table
   would not run the check. So `modules.py` is a file this row touches, and the row's first test
   is that such a pull request triggers the job.
4. **The script does not read `Cast.scala`.** Legality is copied by hand into `legal()` (line 553
   on), and its docstring (554 to 557) names this row's suite as what should check it. That is
   the reason the suite exists: the script alone would keep a wrong transcription consistent with
   itself.
5. **The multi-class weakness is real and sized.** `coverage_index()` (339 to 350) gives every
   class a row names all the types of every column the row reads (`for cls in row["catalyst"]:
   index[cls] |= types`, 346 and 347); the docstring admits it (35 to 38). Of the 92 rows of
   `sql/varka/coverage.json`, 25 name more than one class.
6. **The documentation claims more than a coverage row shows.** `docs/sql-varka.md` 54 to 56 says
   a year-month interval fuses "in the ordered comparisons and `IN`", and no coverage row reads a
   year-month column in either (the seven year-month rows are `d + ym`, `d - ym`, `abs(ym)`,
   `ym - ymm`, `CAST(ymy AS INTERVAL MONTH)`, `extract(YEAR FROM ym)` and
   `extract(YEAR FROM ym) - 1`). The null tests are missing on four of the six types:

   | type | `IS NULL` | `IS NOT NULL` |
   | :--- | :--- | :--- |
   | `DATE` | yes | yes |
   | `INT` | **no** | yes |
   | `BIGINT` | yes | yes |
   | `TIME` | **no** | **no** |
   | year-month interval | **no** | **no** |
   | day-time interval | **no** | yes |

   The census shows the same for the ordered comparisons on the other types: of the 36 pairs of
   `=`, `==`, `<`, `<=`, `>` and `>=` over the six existing types, 18 are fused by a coverage
   row; the twelve missing outside the year-month ones are `=` and `==` on day-time, `>` on
   `TIME`, `>=` on `INT`, `TIME` and day-time, `<` on `INT` and `BIGINT`, and `<=` on `INT`,
   `BIGINT`, `TIME` and day-time. `docs/sql-varka.md` 251 says a bare `IntegerType` column in
   comparison position declines, so the `INT` ones may be real declines rather than missing rows.

7. **`dev/varka_catalogue_move.sh --check 8 9` exists** (lines 21 and 37 to 40): it fails if any
   tracked file still cites a moved name under `m8/` or a moved file still exists there.

**What still needs a run, and what admits the row.** Whether the rows item 6 asks for compile
today, which decides whether 328 adds coverage rows only or also a compiler arm. With a catalyst
test build:

    dev/varka_emit.sh "ym < ym2" "ym IN (ym2, ym)" "ym IS NULL" "ym IS NOT NULL" \
      --columns ym:ym,ym2:ym
    dev/varka_emit.sh "t IS NULL" "t IS NOT NULL" "dt IS NULL" "i IS NULL" \
      --columns t:time,dt:dt,i:int
    dev/varka_emit.sh "dt = dt2" "t > t2" "t >= t2" "dt >= dt2" "t <= t2" "dt <= dt2" \
      "l < l2" "l <= l2" "i < 7" "i <= 7" "i >= 7" \
      --columns t:time,t2:time,dt:dt,dt2:dt,l:bigint,l2:bigint,i:int

Each expression that prints an IR and a shape hash is a coverage row only. One that declines is
evidence the documentation is wrong: the row then corrects `docs/sql-varka.md` and hands the arm
to the row that owns it (333 for `IN` over a type it lacks, 334 for a null test), rather than
building a compiler arm here. The read of `VarkaConditionCompiler` 334 to 350 and 409 to 411
(`IN` gated by `isDateOrInterval`, which includes year-month) predicts that all of these compile.
The check would have rejected the row's "coverage rows only" framing if they did not.

*Run 19 October 2026, at `0d055fa623f`.* `dev/varka_emit.sh` compiles projections only, and a
comparison or null test has no output lane until `BOOLEAN` lands (331), so all 19 expressions above
printed "unsupported expression" there: a fact about the tool, not the compiler. Asked as
predicates, the way `VarkaCoverageSuite` compiles its predicate families, all 19 fuse, and so do
year-month `=`, `<=`, `>` and `>=`, the `INT` comparisons included, so the row is coverage rows
only, as predicted, and `docs/sql-varka.md` 255 to 257 ("`i > 0` does not") was wrong since
VARKA-122.

## 3. The design

### 3.1 Four checks

1. **The census in CI.** Two steps in the `varka-docs` job after the TOC check:
   `./dev/varka_census.py --check` and `./dev/varka_catalogue_move.sh --check 8 9`, and the
   census's inputs added to `varka_docs_regexes` (`dev/varka_census.py`,
   `dev/varka_catalogue_move.sh`, `sql/varka/coverage.json`, the `FunctionRegistry.scala` path).
   The same two lines in `dev/varka_precommit.sh` when any of those paths is staged, as the TOC
   and quote checks already run there (323 to 332).
2. **The cast suite**, `VarkaCastMatrixSuite`, a JUnit 5 class under `@VarkaSuite` in the
   catalyst test tree beside `VarkaRangeRulesSuite`. It reads `m8/casts.tsv` and, for every
   ordered pair of the thirteen types (169), asks `Cast.canCast`, `Cast.canAnsiCast` and
   `Cast.canTryCast` and requires the file's three mode columns to agree, and requires a pair
   absent from the file to be illegal in all three. Then Comet's four-way rule, per mode: a legal
   pair the file marks fused has a coverage row that casts that pair in that mode, an illegal or
   identity pair has none, and a legal pair without a coverage row is not marked fused. Today the
   fused column holds seven partial pairs, so the second half checks little until 332 lands; it
   is written now so that 332 cannot mark a pair fused without the row.
3. **One class per fused claim.** The census's fused check reads only coverage rows that name one
   Catalyst class, and a type is credited to a class only by such a row. The 25 multi-class rows
   stay (they test compositions), but each claim that rests on one alone gets a single-expression
   row beside it, which is the "single-expression coverage row behind every fused claim" of the
   row. The script change is in `coverage_index()`: a second index over single-class rows, the
   check moved onto it, and the multi-class index kept for the derived-operand (`~`) notes.
4. **The rows the documentation already claims**, from item 6 of section 2: year-month `=`,
   `<`, `<=`, `>`, `>=`, `IN` with two and with eleven literals (the two `IN` lowerings), the null
   tests on `INT`, `TIME`, year-month and day-time intervals, and the twelve comparison pairs on
   the other types that compile. Each is a coverage row; the census's fused count moves with them
   and with nothing else.

### 3.2 What is deliberately unchanged

* No compiler or emitter change. An expression that section 2's run finds declining goes to its
  owning row (333, 334), not here.
* The census's judgement half (`census_curated.txt`) changes only where a coverage row moves a
  fused type; its types, needs and row assignments stay as `m8/PLAN.md` 2.1 has them. Each lane
  row re-reads and corrects the lines it touches (risk 1 of the milestone).
* Spark's own cast suites through the Varka evaluator are row 368's; this suite asks `Cast` about
  legality only, never about answers.
* The support table's generation is 353's.

### 3.3 Registered op counts

None: this row emits no kernel. The new coverage rows compile to kernels that already exist; their
op counts are the existing nodes', and the bytes oracle gains their rows only.

## 4. Files

* `.github/workflows/build_and_test.yml`: the two steps in the `varka-docs` job
* `dev/sparktestsupport/modules.py`: the census's inputs in `varka_docs_regexes`
* `dev/varka_precommit.sh`: the census and catalogue checks when their inputs are staged
* `dev/varka_census.py`: the single-class index behind the fused check
* `sql/catalyst/src/test/java/.../codegen/varka/VarkaCastMatrixSuite.java`: new: the matrix against
  `Cast`, and the four-way rule
* `sql/catalyst/src/test/scala/.../codegen/varka/VarkaCoverageSuite.scala`: the year-month
  comparison and `IN` rows, the null-test rows, the single-class rows
* `sql/varka/coverage.json`, `docs/sql-varka.md`: regenerated by `VarkaCoverageSuite`
* `sql/varka/emitted_bytes.json`: new rows only
* `sql/varka/plans/m8/census.tsv`, `census_curated.txt`: the fused types the new rows prove

## 5. Tests, and what each is for

1. **A pull request touching only `coverage.json` runs `varka-docs`.** The trigger gap of section
   2 item 3; checked once by `dev/is-changed.py -m varka-docs` against a scratch diff.
2. **`--check` fails on a stale census.** Delete one fused type from a coverage row locally and
   the job fails with "is stale"; the planted fault is recorded in the pull request, not kept.
3. **`VarkaCastMatrixSuite`, legality**: every one of the 169 ordered pairs against the three
   `Cast` predicates. It catches a transcription error in `legal()`, which nothing else can.
4. **`VarkaCastMatrixSuite`, the four-way rule**: a planted "fused" mark on a pair without a
   coverage row fails it; catches a cast row claiming more than its coverage.
5. **The single-class index**: a planted claim resting on a two-class row fails the census; the
   weakness of section 2 item 5.
6. **The new coverage rows** with the coverage oracle's poisoned null lanes and both validity
   fills (`VARKA-138.md`), at both widths.

No pinned fixture moves except `coverage.json`, `docs/sql-varka.md`, the census and the bytes
oracle's new rows.

## 6. The measurement

None of throughput: the row adds checks and coverage rows. What it measures is cost.

### 6.1 Predictions, registered before the run

1. **The census check adds nothing measurable to `varka-docs`**: it ran in under a second here,
   below the job's own setup time.
2. **`VarkaCastMatrixSuite` runs in under 5 s of test time.** 169 pairs times three predicate
   calls is microseconds; the suite's own start and the TSV read are the cost. Stated at the
   calibration table's observed ratio for a new suite (VARKA-243's, about 2, which VARKA-244
   repeated): the estimate counts the suite's setup and not only its tests.
3. **The census's fused pair count moves from 96 by at most 26** with the new rows: 14 from the
   year-month pairs of `=`, `==`, `<`, `<=`, `>`, `>=`, `in`, `in_set`, `isnull` and
   `isnotnull` and the null tests on `INT`, `TIME` (two) and day-time, and up to 12 from the
   other comparisons, fewer by each `INT` comparison that section 2's run finds declining. The
   fully fused entry count stays at 15: every entry these rows touch admits a new type too.
4. **No existing coverage row's `catalyst` list loses a class** under the single-class index;
   a claim that disappears is a claim that rested on a composition, and is restored by a
   single-expression row in the same pull request.

## 7. Risks

1. **A coverage row that declines silently.** If one of the new rows declines,
   `VarkaCoverageSuite` fails before the census sees it; section 2's run checks this before the
   rows are written.
2. **The four-way rule's per-mode reading.** A coverage row's mode is read from its expression
   (`try_cast`, the ANSI setting of the row); a row that runs under the suite's default mode
   only would satisfy LEGACY and not ANSI. Test 4 plants one per mode.
3. **The trigger change runs `varka-docs` more often.** Its runs are short; the cost is a few
   runner minutes per affected pull request.
4. **`--check` blocks an unrelated upstream merge.** A registry addition from upstream fails the
   census until a curated line exists, which is the check working; the failure text names the
   missing name and the file to edit.

## 8. Sequencing

1. This plan, then section 2's run of `dev/varka_emit.sh`, recorded here.
2. The CI and pre-commit steps and the trigger patterns, green against today's files.
3. `VarkaCastMatrixSuite`, legality first, then the four-way rule.
4. The coverage rows, the single-class index and the census regenerated in one commit, so the
   fused count moves exactly once and the diff shows which claims moved.

Explicitly out of this row: any compiler arm (333, 334), the support table (353), Spark's own cast
suites through the evaluator (368), and the census's curated judgements beyond the types the new
rows prove.

## 9. Outcome

*19 October 2026.* The four checks are in, with two departures from section 3, each for a reason
the work found.

**1. The census in CI.** `dev/varka_census.py --check` and `dev/varka_catalogue_move.sh --check 8 9`
run in the `varka-docs` job and in `dev/varka_precommit.sh` when an input is staged, and the job's
trigger now includes the census's inputs: a scratch commit touching only `coverage.json`, and one
touching only `FunctionRegistry.scala`, printed `false` from `dev/is-changed.py -m varka-docs`
before and `true` after (test 1). A one-character edit of `census.tsv` fails the check with "is
stale" (test 2). The catalogue check failed on master: it took every `READING_*.md` under `m8/` for
a catalogue note to move, milestone 8's own reading notes included, so `m9/SCOPE.md`'s citation of
`m8/READING_SLEEF.md` failed it, and a real run would have moved those notes to `m9/`. It now takes
the old folder's notes only while its `SCOPE.md` is still there; a planted citation of the layouts
note under its old milestone 8 path still fails it.

**2. `VarkaCastMatrixSuite`** asks `Cast.canCast`, `canAnsiCast` and `canTryCast` about all 169
ordered pairs, every form of each type against every form of the other (two decimals, two `TIME`
precisions, two interval field ranges each), and `casts.tsv` agrees everywhere. A planted ANSI `yes`
on `date` to `int` fails it (test 3). *Departure:* the four-way rule is in the census, not in the
suite. It needs the cast pair and mode of each coverage row, which only the resolved expression
shows, so `VarkaCoverageSuite` now writes each row's casts to `coverage.json` (`int>ym:ansi`), and
the census, which renders the fused column, checks it there, in the docs job with no JVM: a pair
marked fused has a row casting it, a row's cast is legal in its mode and not an identity, and a pair
a row casts is marked fused. A planted fused mark with no row, and a row's cast with no mark, each
fail it (test 4). It found the matrix claiming more than its rows: `date` to `date` was marked fused
as an identity, which the analyzer removes before any cast exists, so it is no longer marked; `int`
to `ym` claimed the MONTH and YEAR units, and `CAST(i AS INTERVAL YEAR)` declines (a checked
multiply by twelve), as does `CAST(ymy AS INT)`, so the claim is the MONTH unit, and row 332 carries
the two YEAR casts. `int` to `ym`, `ym` to `int` and `time` to `time` had no coverage row casting
them, and now have one each (`CAST(i AS INTERVAL MONTH)`, `CAST(ymm AS INT)`, and a `TIME` literal
widened to `TIME(6)` and compared with `t`). Six pairs are fused in part, from seven.

**3. One class per fused claim.** *Departure:* not single-class rows but operand types. A class
never alone in its row cannot have a single-class row: `IF` and `CASE` carry a comparison, an
`extract` its replacement, `ym - ymm` its widening cast. So `VarkaCoverageSuite` writes, per row,
the census types each class's own node takes (a replaceable class's are its replacement's), and the
census credits a class only with those: `year(d) = 2021 AND i > 0` now supports a date claim for
`Year` and an int one for the comparisons, and no int one for `Year`. A planted int claim for
`year` fails (test 5). No existing claim was lost (prediction 4).

**4. The rows the documentation claims**: 24 predicates (year-month `=`, `<`, `<=`, `>`, `>=`, `IN`
with two and with eleven literals, the null tests on `INT`, `TIME`, year-month and day-time, and the
eleven comparison rows, twelve census pairs since `=` and `==` share a class), and the three cast
rows above, 27 in all, 119 rows from 92. They pass `VarkaCoverageSuite`,
`VarkaCoverageDifferentialSuite` (332 tests: each row alone and in Spark's skipped positions under
ANSI, and over the edges; `CAST(ymm AS INT)` joins the int-valued rows' known decline as a later
`COALESCE` argument, item 55) and the suites that read the table (family switches and chains,
widths, cost, grouping, composition fuzz) at both widths (test 6). `emitted_bytes.json` gains the
new rows, and its option-arm hashes, taken over the whole table, move with them; no existing row's
bytes moved. `width_audit.json`, regenerated on the host it names, gains the new rows; its 128-bit
refusals on the long lane are those its existing long-lane rows have. `docs/sql-varka.md` now says
every comparison and null test fuses over the six types.

### 9.1 The predictions

| | prediction | measured | |
| :-- | :-- | :-- | :-- |
| 1 | the census check adds nothing measurable to `varka-docs` | both checks under a second on the laptop; the job's own run is the pull request's | held |
| 2 | `VarkaCastMatrixSuite` under 5 s of test time | 0.15 s | held, far under |
| 3 | the fused pair count moves from 96 by at most 26; fully fused entries stay 15 | 96 to 122, +26; 15 | held, at the bound: every `INT` comparison fuses |
| 4 | no existing claim lost under the new crediting | none lost | held |
