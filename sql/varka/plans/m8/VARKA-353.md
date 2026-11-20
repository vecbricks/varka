# VARKA-353: A support table users can read, per type and per family

*Planned 20 November 2026 on master `342ed6851f8`, from milestone 8's row 353 (1.1 item 8,
`m9/SCOPE.md` item 54). Nothing of the row is built; the generated blocks named below belong to
earlier rows.*

## 1. Where this came from

Item 54 of the catalogue (24 September 2026, from the review of spark-vector): Comet publishes one
row per Spark expression saying whether it runs natively, for which types and why it falls back,
and Varka has the facts but writes them for itself. Milestone 8's row 353 makes that the user's
table: `docs/sql-varka.md` states, per type and per family, what fuses, from the census and
`coverage.json`, every column generated; the decimal per representation; the casts per mode by
each mode's own legality; the "three types" paragraph made true; and the math configuration
stated where users look. Its done-when: the table is generated and a check fails when it drifts.

## 2. The admission check, done

Read on master `342ed6851f8`, 20 November 2026.

1. **No user table of the census exists.** `docs/sql-varka.md` has three generated blocks, all
   written by `VarkaCoverageSuite`: the coverage table (765 rows, one per fused spelling), the
   family switches and the verification counts per type. None states the census: `grep -n census
   docs/sql-varka.md` finds nothing. The census itself (`m8/census.tsv`, `m8/casts.tsv`,
   `m8/casts_fused.tsv`) is written by `dev/varka_census.py`, whose `--summary` prints the per-type
   counts a user would want (`bigint` 58 of 85 pairs fused, `dec` 2 of 66, `ntz` 49 of 54, and so
   on; 533 of 878 pairs, 65 of 180 entries for every type) but writes them nowhere a user reads.
2. **The prose a user reads first is stale in three places.** The Overview says "Varka reads three
   Spark types", the three int32 ones; `coverage.json`'s `columns` hold all thirteen. The
   Limitations section says "no compiler arm admits a `bigint` column yet" and lists decimals and
   timestamps as absent; `bigint`, `TIMESTAMP_NTZ` and `DECIMAL` to precision 38 all have coverage
   rows. The Expression coverage section's opening names only the date table's columns.
3. **The row's math wording is superseded.** It asks for "the math configuration's default (on)
   and how to turn it off". By the owner's decision of 30 October 2026 every math function is
   bit-exact to Spark by default, and the departure is an opt-in mode,
   `spark.sql.codegen.varka.math.ulpContract.enabled`, off by default (`m8/PLAN.md` 2.7.1). That
   configuration exists in `SQLConf.scala` but is missing from the docs' Configuration list, which
   is hand-written; only the generated `family.math.enabled` line mentions it. No math function
   fuses yet (rows 356 to 358), and no bound is contracted yet (row 367).
4. **A decimal entry is credited only when it is whole.** The census credits `dec` to `coalesce`
   only when every form fuses; today a form at one band is marked `^`, fused in part, and kept
   out of the count (`census_curated.txt`, the `fused_in_part` column), although `coverage.json`
   holds 179 decimal operands. A table that printed only the counted column would tell a user that
   decimals barely fuse, which is wrong for the comparisons and null tests to precision 18. So the
   table needs the in-part column, and the representation split must be read from the columns a
   row reads (`d9` to `d18` long-backed, `d19` and `d38` the lane pair), since the operand type in
   `coverage.json` is only `dec`.

What the check would have rejected: a table already generated somewhere, or a census that agreed
with the coverage rows without curation. Neither holds, so the row stands.

## 3. The design

### 3.1 The renderer

`dev/varka_census.py` gains a renderer for one new block, `<!-- BEGIN generated support table -->`,
in a new docs section "What fuses, by type" placed before "Expression coverage". The block has
three tables, all read from the files the script already reads:

* **By type.** One row per census type in registry order: entries admitted, entries fused for that
  type, fused in part, pairs left with the reason class (a named `m9/SCOPE.md` row, a declined
  band, an owner's decision), and the coverage rows reading a column of the type. `dec` splits into
  two rows, *up to precision 18* and *19 to 38*, by the precision of the columns each coverage row
  reads.
* **By family.** One row per census section (conditional, predicate, arithmetic, math, datetime and
  the rest): entries in scope, fused for every type, fused for some, and the types still owed.
* **Casts by mode.** The 13 by 13 matrix once per mode, LEGACY, ANSI and TRY, each cell from that
  mode's own legality in `casts.tsv` (an illegal pair is blank, not "declined") and its fused
  state in `casts_fused.tsv`. This is the cautionary case the row names from the Comet reading: a
  table that showed LEGACY's legality under ANSI would claim pairs ANSI rejects.

Notes are generated too: each cell's footnote is the census note or the cast's kind, and an
entry's reason comes from the census's `reason` column, so nothing in the block is typed by hand.

### 3.2 The check

`dev/varka_census.py --check` already runs in the pre-commit hook and in the `varka-docs` job of
`build_and_test.yml`, and fails when `census.tsv` or `casts.tsv` is stale. It now also fails when
the docs block differs from what it renders. The chain from a lowering to the table is then
closed: a new lowering changes `coverage.json` (which `VarkaCoverageSuite` fails until it is
regenerated), the census check then fails until the census and the block are rewritten. The merge
driver learns the block's name, as it knows the other docs blocks (`varka_generated_merge.py`), and
`varka_regen_generated.sh` already reruns the census after a merge.

### 3.3 The alternative, and why not

`VarkaCoverageSuite` could render the block, as the row's done-when names it. It holds
`coverage.json` but not the census, which lives in Python; reading `census.tsv` from Scala would
give the census two readers and the table a JVM run to regenerate. The Python renderer runs in
under a second and is checked where the census already is. Question 1 asks the owner to accept
the done-when as "a check fails when it drifts", item 54's own wording.

### 3.4 The prose, by hand

* The Overview's "three types" paragraph becomes one sentence naming the thirteen types and their
  lanes, pointing at the new section; the int32 detail moves under the date family.
* The Limitations section's first bullet is rewritten to what holds: the types out of scope by the
  owner's decision of 14 October 2026 (`STRING` and its casts, `TIMESTAMP` with the local zone,
  decimal division), nested types, and the Parquet fallback.
* The math paragraph at the head of the new section's math row and a line in the Configuration
  list: exact by default; the opt-in `ulpContract` mode, what it trades, and the caveat for a
  consumer above the kernel (the owner's answer of 30 October 2026 to question 2 of `m8/PLAN.md`
  section 7). Each function's bound per host library, beside what Java's "within 1 ulp"
  specification is worth, is a column the renderer fills from row 367's contract file once 367
  commits one; until then it reads "exact; no contract".

### 3.5 What is deliberately unchanged

The coverage table, the switches and the verification counts stay `VarkaCoverageSuite`'s. The
census's crediting rule (whole or `^`) is the census's, not this row's.

## 4. Files

| file | what |
|---|---|
| `dev/varka_census.py` | the three tables, the block and `--check` over it, with doctests |
| `dev/varka_generated_merge.py` | the block in `BLOCKS`: merges own it, the size check skips it |
| `docs/sql-varka.md` | the new section and block; the Overview, Limitations, Configuration prose |

## 5. Tests, and what each is for

* Doctests in `varka_census.py` (`--self-test`): a two-type census renders the by-type table; a
  `^` entry lands in the in-part column; a `d38` coverage row counts under 19 to 38 and not under
  18; a pair legal under LEGACY only is blank in the ANSI and TRY tables; a stale block fails
  `--check` with the command that rewrites it.
* `varka_generated_merge.py`'s own tests: two sides changing the block take one side and list it
  as owed.
* `python3 dev/varka_census.py --check` on the branch: exit 0 after the rewrite, non-zero after a
  hand edit of one cell (run once, reverted).

## 6. The measurement

None: a document and its generator.

### 6.1 Predictions, registered before the run

1. `--check` adds under a second to the `varka-docs` job. The census check takes under half a
   second today, and the calibration table's checks that start no JVM came in far under their
   estimates (VARKA-335 6.1 #4 under 0.01, VARKA-328 6.1 #2 about 0.03), so no ratio is applied.
2. The by-type table's counted column equals `--summary`'s "pairs by type" line for every type on
   the commit it is built on.

## 7. Risks

1. The precision split reads column names (`d9`, `d38`): a coverage row over a new decimal column
   not in `columns` would fall through. The renderer reads precision from `columns`' declared type,
   not the name, and fails on a decimal column it cannot place.
2. A block of three 13-column tables is wide on a phone. The by-type and by-family tables stay
   narrow; the cast matrices go under a `<details>` element per mode.
3. Rows landing beside this branch change the counts; the merge driver lists the block as owed and
   the regeneration rewrites it.

## 8. Sequencing

One pull request, about 400 counted lines (the renderer and doctests about 250, the prose about
100, the merge driver 20; the generated block is not counted). It depends on no open row and is
best built early, so that each type row's pull request regenerates the table; its contents at the
close are what row 354 reads, and row 355's shares are quoted from it.

1. The renderer with doctests; done when `python3 dev/varka_census.py --self-test` passes.
2. The block written and the check wired; done when `python3 dev/varka_census.py --check` exits 0
   and fails on a hand-edited cell.
3. The prose; done when `python3 dev/varka_quote_check.py` and the pre-commit hook pass on
   `docs/sql-varka.md`.
4. At the close (row 354 step 3): `python3 dev/varka_census.py --check` exit 0 on the closing
   commit, and the math column re-read against row 367's contract file.

## 9. Questions for the owner

1. **The done-when names `VarkaCoverageSuite`; the plan puts the check in `varka_census.py
   --check`.** Recommended: accept "a check fails when it drifts", run in the pre-commit hook and
   CI's `varka-docs` job, because the census and the cast tables are Python's and a second
   reader in Scala would duplicate them. Not mine to decide, since it changes a row's done-when.
2. **Build now or at the close.** Recommended: now, because a generated table kept current by
   every type row costs less than one rebuilt at the end; the row sits in the close's wave
   (`m8/PLAN.md` 4.1, wave 5), so the timing is the owner's call.

## 10. Done when

The support table is generated into `docs/sql-varka.md` per type (the decimal per representation)
and per family, the casts per mode by each mode's own legality, notes included; the Overview,
Limitations and Configuration list say what the code admits, the math functions exact by default
and the opt-in mode documented; and `dev/varka_census.py --check` fails when the block drifts.
