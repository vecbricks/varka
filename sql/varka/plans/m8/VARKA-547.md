# VARKA-547: Each row records the options that change its emitted code

Row 547 of `PLAN.md`. Added on 1 November 2026 by the owner's word, from row 546's question 1
(`VARKA-546.md` 7, answered (b)); planned on 8 November 2026. Row 546's merge driver merged as
#1293 on 2 November 2026 and leaves `option_arms` as the one part of `emitted_bytes.json` no
merge can compute; this row makes that part per row, and decides row 546's question 2 with it.

Everything below was read from the tree; no JVM was run. What was not read is marked **[I]**, an
assumption **[A]**.

## 0. What exists now (admission check)

Against master at `9eefe2a2d13`.

1. **How `option_arms` is computed.** `VarkaEmittedBytesSuite.pinnedArms` (`:420`) lists eleven
   arms: `useAVX` at -1, 0, 1, 2 and 3, the two `validityOrFirst` arms over their subject
   (`subjectArms`, `:451`), `narrowLanes` at both values (`laneArms`, `:432`), and the two
   reference forms `groupLocalSlots=false` and `methodByteBudget=0` (`referenceArms`, `:443`).
   `armDigest` (`:475`) emits every shape under one arm at one width and hashes one line per shape
   into one digest: the 581 coverage rows **and** the three fuzz sequences of 10,000 shapes each.
   `render` (`:498`) writes one digest per arm and width (`:526`, `:547`), 11 x 2 = 22 values, the
   22 lines row 546's merges rewrote (`VARKA-546.md` 0 item 3).
2. **How it is checked.** The suite renders the whole file and compares it byte for byte with the
   committed one; on a difference its walker names coverage rows and methods, fuzz blocks, and for
   the arms only `option arm <name>` at a width (`:671`), since an arm's digest covers every shape.
   `dev/varka_generated_merge.py --check` (`check`, `:994`) reads no arm: it requires each
   `lanes.<n>.coverage` map to hold exactly `coverage.json`'s rows, in its order.
3. **What the committed digests already say.** Three arms equal the defaults over every shape
   the oracle holds (`useAVX=-1`, `useAVX=3` and `narrowLanes=true` are all `3a2044a908e1e0ac` at
   four lanes), and `useAVX=0`, `1` and `2` are one emission (`17ff391c0b0acbc9`). The test "an arm
   the option matrix runs in another's place emits that arm's bytes" (`:763`) asserts three of
   those equalities and one inequality through `armDigest`; it reads no file.
4. **How many rows each arm moves.** The last option audit (`m8/results/VARKA-507-option-audit.txt`,
   28 October 2026, 280 coverage rows, both widths summed): `methodByteBudget=0` 560 of 560 (every
   row), `narrowLanes=false` 76, `useAVX=0`, `1`, `2` 36 each, `groupLocalSlots=false` 0 (its
   moves are in fuzz shapes). The two `validityOrFirst` arms were measured against their subject,
   not the defaults; against the defaults they move at least what `denseValidityOnce=false` does,
   276 of 560. **[I]** the counts at today's 581 rows; section 4 scales them.
5. **How the merge treats it.** `.gitattributes` names `emitted_bytes.json` for the driver
   (`sql/varka/.gitattributes:4`). The driver merges the `lanes` maps per key, cannot compute an
   `option_arms` value both sides changed, writes the smaller and owes the file (`merge_value`,
   `:167`; docstring `:36`). It also owes the file for two more reasons this row's text does not
   name:
   - **Code both sides changed** (`code_both_sides`, `:886`; `FEEDERS`, `:873`; row 546's third
     review, item 22, 2 November 2026). A merge owes the bytes file whenever both sides changed a
     file under `sql/catalyst/src/main/` or one of the row sources (`VarkaCoverageSuite`,
     `VarkaCoverageRows`, ...). The comment says it outright (`:858`): "two branches that each add
     rows through VarkaCoverageSuite owe all three files".
   - **The place of two rows.** Two rows added after the same coverage row are an order the
     driver cannot decide, so it owes it (546's demonstration owed "the place of the two rows",
     `VARKA-546.md` 10), and `--check` requires `coverage.json`'s order.
6. **What regenerating costs.** `VarkaEmittedBytesSuite` takes about 445 s on CI
   (`sql/varka/ci/suite-times.tsv:36`); `dev/varka_regen_generated.sh` runs it when the bytes file
   is owed (`:69`, `:97`). The file is 12,557 lines, 648 KB.
7. **Row 546's question 2.** The verification counts (`docs/sql-varka.md:1106-1122`) are rendered
   by `VarkaCoverageSuite.verificationCounts` from the rows; the coverage table block beside them
   (`:297-1093`) is rendered by the same suite. The driver owes a docs block both sides changed
   even where `git merge-file` merged its lines (546's review item 7). `VarkaCoverageSuite` takes
   18 s on CI (`suite-times.tsv:23`).
8. **No branch of this row exists** (`git branch --list 'varka-547*'`: none).

### What differs from the row text

- **The done-when cannot be met by reshaping `option_arms` alone.** Item 5: a merge of two
  branches that each add coverage rows owes the bytes file through `code_both_sides`, which row
  546 added on 2 November, the day after this row was written, and through the place of two rows
  added at the same anchor. Per-row arm digests remove the first reason the row names and neither
  of these. This is question 1.
- **`option_arms` is not a digest "over every coverage shape's emitted bytes"**: it also covers
  the 30,000 fuzz shapes (item 1). They are fixed by seed and do not grow with rows, so they keep
  an aggregate digest per arm (row 546's design, `VARKA-546.md` 2.2) and only the coverage part
  becomes per row.
- **"Each option setting that changes that row's emitted code against the defaults"** leaves out
  three of the eleven arms on every row today (item 3), and records `useAVX=0`, `1` and `2` three
  times over. Both follow from the rule; neither is a reason to change it.
- **Question 2 of row 546 is not freed by this row.** The coverage table block is owed by the
  same merges as the counts block (item 7), so moving the counts out of the docs file saves no
  coverage run.

## 1. Goal and non-goal

**Goal.** Two branches that each add coverage rows merge with master, in either order, with no
`option_arms` value and no row order owed in `emitted_bytes.json`; an arm whose emission changes
on one coverage shape fails `VarkaEmittedBytesSuite` naming that shape and that arm.

**Non-goal.** Changing which arms are pinned; a per-block digest of the fuzz sequences per arm;
removing the feeder rule from `coverage.json` or `width_audit.json`; regenerating anything in CI.

## 2. Design

### 2.1 The record

The top-level `option_arms` keeps one entry per arm and gains the per-row part:

    "option_arms" : {
      "useAVX=0" : {
        "fuzz" : { "4" : "...", "16" : "..." },
        "fuzz_long" : { ... }, "fuzz_mixed" : { ... }
      }, ...
    },
    "option_arm_rows" : {
      "<sql>" : { "methodByteBudget=0" : "...", "narrowLanes=false" : "..." },
      ...
    }

- A fuzz digest is today's `armDigest` restricted to one sequence: 11 arms x 3 sequences x 2
  widths, 66 values, which move only when code moves fuzz shapes, never when a row is added.
- `option_arm_rows` holds, for each coverage row, each arm under which the row's method hashes
  differ from its default ones, with one digest of the row's method hashes under that arm **over
  both widths** (question 2). A row no arm moves has no entry. An arm over a subject
  (`validityOrFirst`) is compared against the defaults, as the row says.
- The map is **sorted by `sql`**, and so is each `lanes.<n>.coverage` map (today in
  `coverage.json`'s order). Sorted maps merge per key exactly in the driver (`RULES`, `:161`, as
  the width audit's are), so two rows added at one anchor owe no order. `--check` compares the
  row sets instead of the order, and requires every key of `option_arm_rows` to be a coverage row.

### 2.2 The failure names the shape and the arm

The walker's arm branch (`:671`) becomes: per row, an arm gone, new, or changed, printed as
`row <sql>: option arm <name>`, and per fuzz sequence `option arm <name>: <sequence>` at a width.
The walker moves into a function of two parsed documents so a test can feed it a pair (3.2).

### 2.3 The merge

`sql/varka/.gitattributes` is unchanged. The driver's `RULES` gains the sorted maps
`lanes.\d+.coverage` and `option_arm_rows`. What question 1 decides is whether the feeder rule
still owes the bytes file when both sides changed code: under (b), for `emitted_bytes.json`
alone, that reason is printed as "checked by the Build's VarkaEmittedBytesSuite" and not written
to the owed list, so `dev/varka_regen_generated.sh --owed` does not run the suite; `--all` still
does. `coverage.json` and `width_audit.json` keep the rule.

### 2.4 What does not change

The emission count: every arm already emits every shape inside `armDigest`, and the per-row
digests reuse those method hashes and the defaults' hashes the `lanes` maps already compute. The
folded-arms test (`:763`) keeps its in-memory digests. The pinned arm list.

## 3. Tests

1. **The oracle itself**: regenerated once; its committed check passes on CI.
2. **The walker names what moved**: a test feeds the walker a document and a copy with one
   row's arm digest changed, one arm entry added to a row and one removed, and requires the three
   lines `row <sql>: option arm <name>`; and a copy with one fuzz arm digest changed.
3. **The merge** (driver `--self-test`, which the docs job runs): two sides each adding a row
   after the same anchor, with arm entries, merge to the same text in either order with nothing
   owed for the bytes file but the code reason; under question 1 (b), that reason is not on the
   owed list. A row removed on one side drops its arm entry.
4. **`--check`**: a bytes file whose `option_arm_rows` names a row `coverage.json` lacks fails
   with the bytes command.
5. **The demonstration**, as row 546's (`results/VARKA-546-demo.txt`): two branches off one
   master, each adding a coverage row after the same row, merged with master in both orders; the
   bytes file merges with no markers, the two trees are equal, and a regeneration after the merge
   leaves `emitted_bytes.json` unchanged. Committed as `results/VARKA-547-demo.txt`.

## 4. Predictions, registered before any build

- **P1. Suite time** within 10% of its 445 s on CI. The emission count does not change (2.4); the
  calibration table has no row for the same work recorded another way, so no ratio is applied.
- **P2. File size.** Scaling item 4 from 280 rows to 581: per row, `methodByteBudget=0` always,
  `validityOrFirst` both arms on at least half, `narrowLanes=false` on about one row in seven,
  `useAVX=0`, `1`, `2` on about one in sixteen. About 2.3 to 3.3 entries a row, so 1,300 to 1,900
  entry lines, two lines a row for its object, and about 100 for the fuzz digests: about +2,600
  to +3,200 lines (+20 to 25%) over 12,557. **[A]** the new type families move arms at the old
  rate.
- **P3. Nothing else moves**: the `lanes` digests are the same values in sorted order, and the
  folded-arms test passes unchanged.
- **P4. The demonstration** (3.5): a regeneration after the merge changes nothing.
- **P5. Size**: under 450 hand-written lines.

## 5. Order, files and size

One pull request (small), after row 546's checks (merged):

- `VarkaEmittedBytesSuite.scala`: `render`, the per-row arm map, the walker as a function and its
  test, the `description` string (`:536`).
- `dev/varka_generated_merge.py`: `RULES`, `check`, the docstring (`:36`), the code reason for
  the bytes file under question 1 (b), self-test cases.
- `dev/varka_regen_generated.sh`: comments only.
- The record of the aggregate, rewritten: `VarkaEmitOptions.java:63`, `sql/varka/AGENTS.md:21`,
  `sql/varka/ADDING_A_TYPE.md:104`.
- `emitted_bytes.json`, regenerated (generated, not counted).

About 150 lines of Scala, 120 of Python, 30 of documents and 60 of plan: about 360 counted lines.

## 6. What it unlocks

Type rows merge with each other without the 445-second bytes regeneration, under question 1 (b);
a moved arm names its row, so a reviewer reads what an option changed on which shape.

## 7. Out of this row

- **Rendering the docs blocks without a JVM.** The verification counts are computable from
  `coverage.json` alone (each row holds `operands` and `verified`), and so may be the coverage
  table; a Python renderer would let the merge compute both and owe no coverage run. Out: it is
  a second renderer of one table, and `coverage.json` stays owed by the feeder rule anyway.
- **The feeder rule for `coverage.json` and `width_audit.json`**: unchanged.
- **A per-block arm digest for the fuzz sequences**: 6,600 values for a sharper failure on a
  shape no row covers; a fuzz move is found today by re-emitting the sequence.

## 8. Questions for the owner

1. **The feeder rule owes the bytes file on every merge of two type rows.** (a) Keep it; the
   done-when becomes "owing no `option_arms` value and no row order", and the regeneration stays
   owed whenever both sides changed code, though with per-row digests it usually changes nothing.
   (b) For `emitted_bytes.json` alone, a both-sides code change is checked by the pull request's
   Build (which runs the suite on every Build) instead of owed locally. (c) Narrow `FEEDERS` so a
   coverage suite change that only adds rows is not a feeder change; type rows also change main
   code, so this reaches few merges. **Recommended (b)**: it is the row's done-when, the Build
   fails loudly and names the shape and arm, and with per-row digests the merged guess is wrong
   only where one side's code moved the other side's rows, which type plans predict as "no
   existing shape's bytes move". The cost is a red Build where that prediction fails.
2. **One arm digest per row over both widths, or one per width?** (a) Over both widths, in
   `option_arm_rows`. (b) Per width, under `lanes.<n>`. **Recommended (a)**: half the entries,
   and the failure names the row and arm; which width is one re-emission of one row away.
3. **Row 546's question 2, the verification counts.** (a) Keep them in the docs file. (b) Move
   them to a generated file of their own. **Recommended (a)**: the coverage table block is owed
   by the same merges (0 item 7), so (b) saves no coverage run (18 s on CI); section 7 names
   what would.

## 9. Done when

Two branches that each add a coverage row after the same row merge with master in either order
with no `option_arms` value or row order owed in `emitted_bytes.json` (and, under question 1 (b),
with the bytes file not owed at all), a regeneration after the merge changes nothing
(`results/VARKA-547-demo.txt`); and an arm whose emission changes on one coverage shape fails
`VarkaEmittedBytesSuite` with a line naming that shape and that arm (test 3.2).

## 10. Outcome

Not started.
