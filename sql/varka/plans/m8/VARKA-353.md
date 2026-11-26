# VARKA-353: A support table users can read, per type and per family

*Planned 20 November 2026 on master `342ed6851f8`, from milestone 8's row 353 (1.1 item 8,
`m10/SCOPE.md` item 54). Nothing of the row is built; the generated blocks named below belong to
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
  type, fused in part, pairs left with their reason class, and the coverage rows reading a column
  of the type. The census has one `dec` type, so the entry and pair columns stay one `dec` row; only
  the coverage-rows column splits, into *up to precision 18* and *19 to 38*, by the declared
  precision in `coverage.json`'s `columns` of the decimal columns a row reads. A row reading both
  bands (`d18b < d38`, `IF(d18 > 0.00, d38, d38b) IS NULL` today) counts under 19 to 38, the band
  whose lane it needs, and a note under the table says so. A per-band census would need a band
  field in `census_curated.txt`; that is not this row's.
* **The reason classes.** The census's `reason` column is filled only for the owner's decision on
  decimal division (`/`, `div`, `try_divide`); for every other entry the reason an unfused pair is
  left is its `need` (`plumbing`, `semantics`, `ulp`) with the open rows in its `rows` column and
  the band in its `note`. So the class is derived: *owner's decision* from `reason`, *declined band*
  where `note` names one (row 346's bands), and otherwise *owed to row N* from `rows`, with `need`
  beside it.
* **By family.** One row per census section (conditional, predicate, arithmetic, math, datetime and
  the rest): entries in scope, fused for every type, fused for some, and the types still owed.
* **Casts by mode.** The 13 by 13 matrix once per mode, LEGACY, ANSI and TRY, each cell from that
  mode's own legality in `casts.tsv` (an illegal pair is blank, not "declined") and its fused
  state from `casts_fused.tsv`. That file has one line per concrete form (unit pairs of an
  interval, decimal precisions and scales), and forms of one pair can disagree (`dt` to `dt` fuses
  `DAY` to `DAY TO SECOND` and declines `DAY` to `HOUR`), so a cell reads *fused*, *in part, k of
  n forms* or *declined*, and its footnote lists the declined forms, the decimal ones by band.
  This is the cautionary case the row names from the Comet reading: a table that showed LEGACY's
  legality under ANSI would claim pairs ANSI rejects.

Notes are generated too: each cell's footnote is the census note or the cast's kind, and an
entry's reason class is derived as above, so nothing in the block is typed by hand.

### 3.2 The check

`dev/varka_census.py --check` already runs in the `varka-docs` job of `build_and_test.yml`, and in
the pre-commit hook when a staged file matches its `census_inputs` pattern, which does not include
`docs/sql-varka.md`; the pattern gains it, so a hand edit of the block fails at commit. The check
fails when `census.tsv` or `casts.tsv` is stale, and now also when the docs block differs from
what it renders. The chain from a lowering to the table is then closed: a new lowering changes
`coverage.json` (which `VarkaCoverageSuite` fails until it is regenerated), and the census check
then fails until the census and the block are rewritten. The merge driver learns the block's
name, as it knows the other docs blocks (`varka_generated_merge.py`). `varka_regen_generated.sh`
reruns the census after a merge, but it treats any owed
`docs/sql-varka.md` as owing a `VarkaCoverageSuite` run; it learns to skip that JVM run when the
support block alone is owed.

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
| `dev/varka_precommit.sh` | `docs/sql-varka.md` in `census_inputs` |
| `dev/varka_regen_generated.sh` | no coverage run when only the support block is owed |
| `docs/sql-varka.md` | the new section and block; the Overview, Limitations, Configuration prose |

## 5. Tests, and what each is for

* Doctests in `varka_census.py` (`--self-test`): a two-type census renders the by-type table; a
  `^` entry lands in the in-part column; a `d38` coverage row counts under 19 to 38 and not under
  18, and a row reading `d18b` and `d38` once, under 19 to 38; a pair legal under LEGACY only is
  blank in the ANSI and TRY tables; a pair with one fused and one declined form reads *in part, 1
  of 2*; an entry with an empty `reason` and `rows` 359 is classed *owed to row 359*; a stale block
  fails `--check` with the command that rewrites it.
* `dev/varka_regen_generated.sh` with only the support block owed runs the census and no sbt.
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
2. The by-type table's pair columns equal `--summary`'s "pairs by type" line for every type on the
   commit it is built on, `dec` as one row.
3. Of the cast cells legal under ANSI, at least one in five reads *in part* rather than fused or
   declined: 42 pairs fuse at least in part today and the interval and decimal pairs carry many
   forms each. A guess; it decides whether the in-part state earns its own colour.

## 7. Risks

1. The precision split reads column names (`d9`, `d38`): a coverage row over a new decimal column
   not in `columns` would fall through. The renderer reads precision from `columns`' declared type,
   not the name, and fails on a decimal column it cannot place.
2. A block of three 13-column tables is wide on a phone. The by-type and by-family tables stay
   narrow; the cast matrices go under a `<details>` element per mode.
3. Rows landing beside this branch change the counts; the merge driver lists the block as owed and
   the regeneration rewrites it.

## 8. Sequencing

One pull request, about 450 counted lines (the renderer and doctests about 280, the prose about 100,
the merge driver, the hook and the regeneration script about 50; the generated block is not
counted). It depends on no open row and is best built early, so that each type row's pull request
regenerates the table; its contents at the close are what row 354 reads, and row 355's shares are
quoted from it.

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

## 11. What was built (20 November 2026)

Built on master `0b8be5a33e4`, in one branch, steps 1 to 3 of section 8.

* **The renderer** (`dev/varka_census.py`, "the support table" section). `support_block` writes
  four tables, not the three of 3.1: by type, by family, the math functions and the casts by
  mode. The math table is the column 3.4 asks for, with one row per plan row that fuses a group
  of `ulp` entries (356, 357, 358), what each returns by default ("Spark's, bit for bit") and
  under the opt-in mode (`exact; no contract`, the constant `NO_CONTRACT` until row 367 commits
  a contract file). The by-type table reads `pair_counts`, which `summary()` now calls too, so
  the two cannot disagree. A coverage row's columns are read from its `executable` SQL with the
  quoted literals stripped, and a column's type from `coverage.json`'s `columns`
  (`column_type`, which fails on a type it cannot place, risk 1); a cross-check over all 765
  rows found every column type among the row's operand types except the bare `b`, which has no
  operand. The cast cells reuse `legal()`, `cast_kind()`, `CONTEXT_CASTS` and `casts_fused()`,
  and a pair legal in a mode with no probe form fails the run rather than reading *declined*.
  A `CONTEXT_CASTS` pair reads `context` only in a mode where a coverage row casts it. The
  coverage table is compiled under ANSI, so under LEGACY and TRY the two pairs (`int` to `dt`,
  `dec` to `dec`) read *declined*, as the probe finds them, and claim no fusion that no row
  shows in that mode.
* **The block and the check.** `docs/sql-varka.md` has a new section, "What fuses, by type",
  before "Expression coverage", with the math paragraph and the block. `main()` splices the
  block (`splice`, which fails on a missing or doubled marker) and `--check` compares the whole
  file (`stale`, doctested on a hand-edited cell), so a hand edit of one cell fails with `census: docs/sql-varka.md is stale; run
  dev/varka_census.py` (run once on a hand-edited cell and reverted: exit 1, then 0).
* **The hook** matches `docs/sql-varka.md` among `census_inputs`.
* **The merge driver** names the block in `BLOCKS`, so the size check skips it and self-test 22
  holds the map to the tracked markers. A conflict inside it owes the census, not the file
  (`block_debt`), so the driver writes no owed line for it and the regeneration runs no
  coverage suite; the census, which every regeneration runs last, rewrites the block. Self-test
  28 checks both halves. A support-table conflict takes one side and owes only the census. The
  same conflict in a coverage suite's block owes that block. `driver()` itself writes an owed
  line for the second and none for the first.
* **The prose** (3.4): the Overview's "three types" paragraph names the thirteen types and their
  lanes and introduces the int32 detail as the date family's; the Overview's out-of-scope
  sentence and the Limitations section's first bullet state the owner's exclusions of 14
  October 2026; the Expression coverage opening points at `coverage.json`'s column list; the
  Configuration table gains `spark.sql.codegen.varka.math.ulpContract.enabled`.
* **Four more stale claims**, each checked against `coverage.json`:
  * A `TINYINT` or `SMALLINT` day offset column fuses (`date_add(d, y1)`, `date_add(d, s1)`), so
    the Overview's "still declines" and the Limitations bullet's "offset columns decline" are
    rewritten.
  * The int-arithmetic bullet's "the float and long types are not lowered" now points at their
    families.
  * The coverage opening said comparisons fuse "over each of the six types" and `IN` "over dates
    and year-month intervals". It now says comparisons fuse over all thirteen types, decimals in
    part, and `IN` over every type but `BOOLEAN`.
* **The math paragraph** follows `m8/PLAN.md` 2.7.1 and row 345's admission. Spark calls
  `StrictMath` for `exp`, `log`, `pow` and their neighbours, and `Math` for `sin`, `cos`, `tan`,
  `tanh` and `cbrt`. So only the second group's last bit is the host's choice.
* **Stale prose left.** The Troubleshooting section's reason list still quotes "non-integer day
  offset column of type ..." for a `ShortType`/`ByteType` column, a decline the compiler no
  longer makes for those two types. It is a catalogue of reasons rather than this row's prose,
  so it is left for the review to check against the compiler's reasons.

Left: step 4, at the close (row 354 step 3).

### 11.1 Where the plan was wrong

1. **The regeneration script cannot tell one block from another.** 3.2 has
   `varka_regen_generated.sh` skip the coverage run "when the support block alone is owed", but
   the owed list is by path: the driver writes `docs/sql-varka.md` whatever block it could not
   merge. The skip lives in the driver instead (`block_debt`, above), and the script is unchanged
   but for its comment. The driver's hunk parser also tracked only whether it was inside a block,
   not which; it now carries the name (`block_name`), and falls back to the coverage debt where a
   hunk moved a marker.
2. **"Declined band" is not a class the census can attribute.** 3.1 derives it "where `note`
   names one". The one note that does (`round`, "a band declined (346)") covers the double tie
   test of one type while its other unfused types are semantics, so classing every unfused type
   of the entry by the note would misstate float and dec. The table classes by need alone.
3. **The by-type table has no rows column.** The union of the rows that the unfused entries name
   ran to thirty rows per type, most of them rows that landed other types of the entry; the rows
   are in the by-family table, where they are short enough to read, and `left_reason` (owed to
   row N, with the need) is the per-entry class the doctest names.
4. **The family table counts four kinds, not three**: every type, some type, in part, and the
   types owed, since the predicate family's decimal comparisons are in part and would otherwise
   read as unfused.

### 11.2 Predictions, scored

1. **Held.** `--check` takes 0.54 to 0.74 s on the branch (three runs, wall time, laptop not
   idle). The comparison runs were not taken at the branch's base. On the main checkout at
   `1958fd1d7a7` they took 0.34 to 1.21 s, and one run in this worktree before the first edit
   took 0.67 s. Either way the check adds well under the one second predicted.
2. **Held.** The by-type table's *Entries* and *Fused* columns equal `--summary`'s pairs by type
   for all thirteen types on `0b8be5a33e4` (`bool` 28 of 41 to `dt` 34 of 57, `dec` 2 of 66 as
   one row); they are now one function's output.
3. **Wrong.** Of the 98 cells legal under ANSI, 3 read *k of n* (`time` to `time`, `ym` to `int`,
   `dt` to `dt`), 39 fused, 45 declined, 9 identities and 2 `context`: one in thirty-three, not
   one in five. LEGACY (106 legal cells) and TRY (98) have the same 3. The interval and decimal pairs that carry many forms decline whole today rather
   than in part. So the in-part state gets no colour of its own; it reads as text.

## 12. Decisions taken while the owner was away (20 November 2026)

1. **Question 1 (the done-when).** Taken as recommended: the check is `dev/varka_census.py
   --check`, run by the pre-commit hook and CI's `varka-docs` job, not `VarkaCoverageSuite`.
   The census and the cast tables are Python's, and a Scala reader would give them two. The row's
   done-when is left as written for the owner to amend; this file says how it is met.
2. **Question 2 (now or at the close).** Taken as recommended: built now, since a table each
   type row regenerates costs less than one rebuilt at the close.
3. **No per-entry table.** 3.1 names three tables and "notes included"; the census notes are
   written for the plan's readers (row numbers, section references), and a 180-row table of them
   would bury the counts. The notes the block carries are each cast cell's kind and declined
   forms, and the math table's bound. So "notes included" is met for the cast cells and the math
   functions only. Whether to add a per-entry table carrying the census notes is the owner's
   call.
4. **Rows are listed as the census names them**, Done rows included, rather than filtered by
   reading `m8/PLAN.md`'s status column: that would make every row closure a census input and
   stale the docs block on each plan edit.
5. **The cast matrices sit under `<details markdown="1">`**, one per mode (risk 2), which the
   docs had not used before; whether kramdown renders the table inside it is owed to the Jekyll
   build (`SKIP_API=1 bundle exec jekyll build` in `docs/`), not run here.

## 13. Tests

Run on the branch:

* `python3 dev/varka_census.py --self-test`: 41 doctest examples, all pass, 29 of them new.
* `--check`: exit 0, and exit 1 on a hand-edited cell.
* `python3 dev/varka_generated_merge.py --self-test`: 87 cases, 0 failed. Case 28 is new and
  covers `driver()`'s owed line.
* The driver on a support-only conflict in a scratch repository wrote no owed line.
* `dev/varka_regen_generated.sh --dry-run` with nothing owed runs no sbt.
* `python3 dev/varka_quote_check.py` and `dev/varka_precommit.sh --working-tree` pass.

No JVM suite was run, since no Scala or Java changed.

Owed:

* The Jekyll docs build. Ruby is not installed on the laptop, so nothing has checked how
  kramdown reads the tables inside `<details markdown="1">`.
* `VarkaCoverageSuite` on the branch. It reads the docs file but checks only its own three
  blocks, so the new fourth block must not trip it.
* `dev/varka_regen_generated.sh --all` before the push. `docs/sql-varka.md` is one of the
  driver's generated files, and the push check's R3 (`unregenerated` in
  `dev/varka_push_check.py`) accepts a changed generated file only if an `--all` run wrote that
  blob.
