# VARKA-354: Close milestone 8

*Planned 20 November 2026 on master `342ed6851f8`, from milestone 8's row 354 (1.1 item 8). The
close runs after the rows it reads land; this plan writes its steps so they can run then, in the
shape milestone 7's close gave it (`m7/VARKA-261.md`, `m7/PLAN.md` sections 11 and 12, #919 and
#927).*

## 1. Where this came from

`CONTRIBUTING.md` ("Finding work") gives every milestone a closing task as its last row: the
documents that name the milestone in flight corrected, the lessons in the skills files, the
oracles regenerated on the closing commit, the `CHANGELOG.md` entry, an annotated tag `varka-m<n>`,
and the worktrees and branches of merged pull requests removed. Row 354 adds this milestone's own:
1.1 read row by row, the census and the cast matrix regenerated on the milestone's build, and the
README's account updated. Its done-when: each item of 1.1 is met or its gap is named.

The standing decisions this plan follows, each the owner's:

* **The close's five parts** (the owner's decision of 3 October 2026, at milestone 6's end): a tag,
  every document checked against the resolved rows, worktrees and branches cleaned, a changelog
  entry, and a few proposals for him to pick from. A dirty worktree is left and reported; a fork
  branch is deleted only for having a merged or closed vecbricks/varka pull request, never for
  lacking one, since the fork also holds his Apache Spark branches.
* **The close needs no laptop** (the owner's decision of 18 October 2026, superseding for the close
  itself the rule of 4 October that a close regenerates the README's surfaces): from this close on
  it is a documents-only change. It quotes the latest committed surface files with their dates,
  surface regeneration is a row of its own (553 here), the cold-start row quotes the runner pair
  with its spread, and no laptop benchmark arm is queued unless he asks.
* **The census totals are refreshed here, not before** (the owner's decision of 1 November 2026):
  the milestone's text quotes 183 entries and 881 pairs; since row 349's reading they are 180 and
  878, and the close updates them with the other quoted numbers.
* **The tag goes where the close's last fix and post land** (the owner's decision of 18 October
  2026 for milestone 7, taken here as the model): the tag sat on the merge of the post's sources
  behind the fix the close had found, and the close's documents-only pull request merged after it.

## 2. The admission check, done

Read on master `342ed6851f8`, 20 November 2026. The close cannot run yet, and what it will find is
already partly visible:

1. **The rows.** Section 3 of `m8/PLAN.md` holds 240 rows. By each row's first bold marker, the
   rule `dev/varka_issues.py` reads, 103 are **Done**, 62 **Started** and 71 **Planned** (552 and
   553 among them, their marker after a sentence); 180 is marked **From milestone 7**, and 353, 354
   and 355 had no marker before this plan. The close waits on every
   row that 1.1 reads (section 8 below names them), and the owner decides what happens to the rest
   (question 1).
2. **1.1 against today.** Item 2 asks for two thirds of the in-scope entries fused for every type
   and three quarters of the pairs; on 180 and 878 that is 120 entries and 659 pairs, and
   `dev/varka_census.py --summary` reads 65 and 533. Item 3: 42 of the 106 legal cast pairs are
   fused at least in part (`casts_fused.tsv`). Item 5: no math function fuses yet (rows 356 to
   358). Item 7: the post is unwritten (row 355).
3. **Stale documents already known.** The README's status bullet says `DECIMAL` "up to precision
   18" (the owner's decision of 14 October 2026 made it 38) and its Contributing section names
   milestone 8 as the one in flight; `m8/PLAN.md` quotes 183 and 881 in ten places;
   `docs/sql-varka.md`'s Overview and Limitations say three types and no `bigint` (row 353 rewrites
   them); the README's surface tables quote stock 4.2.0 (rows 552 and 553).
4. **The tag and the changelog.** `varka-m6` and `varka-m7` exist, `varka-m8` does not;
   `CHANGELOG.md` ends at milestone 7.
5. **The cleanup.** `git worktree list` shows 159 worktrees and there are 159 local `varka-*`
   branches in the main checkout.

The check would have rejected the row if the close's work were already done or owned elsewhere; it
is not, and only 552 and 553 (the stock comparisons and the surfaces) and 353 (the user table)
take parts of 1.1 item 8 off it.

## 3. The design: the close's steps

Nothing in the engine moves. The order is milestone 7's: the rows are closed or moved before the
tag, so the tagged plan has row 354 alone open; the tag marks the milestone's last code and its
post; and the close's documents, which quote the tag, merge after it. Each step ends in the
command that says it is done.

1. **The rows.** Every row of section 3 reads **Done**, **Withdrawn** or **Moved** (to
   `m10/SCOPE.md` with its plan, as rows 180 and 386 moved at milestone 7's close), on the owner's
   word for each move. Row 180, promotion, is a cadence and moves again. The mirror reads only a
   row's first bold marker, so a move is written at the head of the cell, before **From milestone
   7** in 180 and before the sentence that precedes **Planned** in 552 and 553. These status edits
   are a small pull request of their own, merged before the tag. Row 354 reads **Started** while
   the close runs. Done when `python3 dev/varka_issues.py --plan sql/varka/plans/m8/PLAN.md`
   (dry run) lists row 354 alone as open.
2. **The record.** This file's section 9 scores 1.1 item by item with its evidence, and the
   predictions the plan registered in its own text. The commands per item: (1) for each of the
   thirteen types and both decimal representations: a coverage row for a load, a comparison, a
   choice and an output (`coverage.json`), its Arrow vector in `isArrowBacked` and `allocateVector`,
   `sql/varka/fuzz/types.tsv` with no `Owed` cell (row 352), the emitted-bytes oracle unchanged at
   each lane's landing (the lane rows' records), and the three both-ways measurements with their
   losing arms kept (329, 347, 359); (2) the census counts against 120 and 659, every unfused pair
   with its reason; (3) `casts.tsv` and `casts_fused.tsv`, each of the 106 pairs fused or declined
   with its reason and proof column, and row 368's record; (4) the edge rows' tests, by plan; (5)
   the 27 math entries exact, by `VARKA-345.md` 2.3's three tests; (6) rows 104, 30, 95, 103, 39 and
   89 and catalogue items 32, 33, 34, 55, 93 and 94; (7) the post's address; (8) rows 353, 552, 553
   and this close. Done when every item reads met, or met with a named gap that points at a row of
   `m10/SCOPE.md`.
3. **The numbers.** 183 and 881 become 180 and 878 wherever they are the milestone's current
   count, with the dated originals kept where a sentence records the past; the census and the cast
   matrix are rewritten on the closing commit. Done when `python3 dev/varka_census.py --check` exits
   0, `grep -n '\b183\b\|\b881\b' sql/varka/plans/m8/PLAN.md` lists only dated history, and
   `python3 dev/varka_quote_check.py` finds no orphan.
4. **The documents.** Every sentence that names milestone 8 in flight or carries an older truth:
   the README's status list and Contributing pointer, `CONTRIBUTING.md`'s "as this is written",
   `sql/varka/VISION.md`'s header, `m10/SCOPE.md`'s header, and the skills files the rows overtook.
   The README's numbers are quoted, not regenerated: the surfaces from row 553's committed files
   with their dates, the stock comparisons from row 552's, and the cold-start row from the runner
   pair with its spread. 1.1 item 8 asks for the newest stock feature release at the close, so the
   step first reads the Apache archive's release list; a feature release newer than 4.3.0 is a
   finding for the owner, not a rerun inside the close. Done when the grep of
   `m7/VARKA-261.md` 4 step 3 (`in flight`, `m8/PLAN.md`, `milestone 8`) over the README,
   `CONTRIBUTING.md`, `VISION.md`, the plans' headers and the skills files finds nothing stale, and
   the quote check passes.
5. **The oracles, without a laptop.** The close branch's Build is the evidence: its
   `VarkaEmittedBytesSuite`, `VarkaCoverageSuite` (the coverage table and `coverage.json`), the
   census check and `dev/varka_generated_merge.py --check` fail a stale file with its command. Done
   when that Build is green with no generated file changed by the branch; a red one is a finding
   and a row before the tag, as milestone 7's cold-start regression was.
6. **The lessons.** The lessons of milestone 8's task records that the skills files lack, the
   largest first, into `sql/varka/skills/`; the rest listed as owed by source, as
   `m7/VARKA-261.md` 7 did. Done when `dev/varka_toc.py SKILLS.md --from sql/varka/skills` leaves
   `SKILLS.md` unchanged after the commit.
7. **The debt register.** Each entry of `m8/PLAN.md` section 11 rewritten in the past tense with
   what the sweep found and where it went; fixes in hand go in a pull request of their own before
   the tag, debts without one to `m10/SCOPE.md` items. Done when no entry of section 11 is in the
   present tense without a pointer.
8. **The changelog.** The milestone 8 entry at the top of `CHANGELOG.md`, in the file's style: the
   theme, the counts of rows, the types and the census shares, the wrong answers fixed, what moved
   to milestone 9, and every post's address (the milestone's posts are read from the rows marked
   published, 587's included). Done when the quote check passes on it.
9. **The tag.** An annotated `varka-m8` on master once row 355's post sources and the debt sweep
   have merged, its message the changelog's headline and the posts' addresses; pushed only on the
   owner's word. Done when `git show -s varka-m8` names that merge commit.
10. **The cleanup.** `dev/varka_worktree.sh list` then `gc` for clean worktrees of merged pull
    requests and their local branches; dirty worktrees left and listed in section 9; fork
    branches deleted only for a merged or closed vecbricks/varka pull request. Done when `git
    worktree list` holds only open work and the dirty ones the record names.
11. **The plan's close section and the review.** `m8/PLAN.md` gains section 12, "The close", in
    `m7/PLAN.md` section 11's shape (rows by status, 1.1 item by item, what milestone 9 still
    reads, found at the close inside and outside its scope); row 354 **Done**. A review at the
    close, as `m7/PLAN.md` section 12 was, follows as its own pull request (question 3). Done when
    `python3 dev/varka_plan_lint.py --offline` passes and the issue mirror's dry run closes row
    354's issue.
12. **Proposals.** A short list of further closing items for the owner to pick, per the decision
    of 3 October 2026, in section 9.

### 3.1 What is deliberately not in the close

* Regenerating surfaces or any benchmark (row 553 and the decision of 18 October 2026).
* Opening milestone 9's plan: renaming `m10/SCOPE.md` to a plan is its own task.
* The user support table (row 353) and the post (row 355), which the close reads.

## 4. Files

| file | what |
|---|---|
| `sql/varka/plans/m8/VARKA-354.md` | the record, section 9 |
| `sql/varka/plans/m8/PLAN.md` | the rows' statuses, the counts, section 11's sweep, section 12 |
| `README.md`, `CONTRIBUTING.md`, `sql/varka/VISION.md`, `m10/SCOPE.md` | stale sentences |
| `CHANGELOG.md` | the milestone 8 entry |
| `sql/varka/skills/*.md`, `SKILLS.md` | the lessons |

## 5. Tests, and what each is for

The close adds no test. Its checks are the commands each step ends in, the close branch's Build
(step 5), and `dev/varka_msg_check.py pr` on its pull request body.

## 6. The measurement

None; the close quotes committed files.

### 6.1 Predictions, registered before the close

1. At the close the census reads between 90 and 115 entries fused for every type, short of item
   2's 120, and at least 659 pairs. Today it is 65 and 533. The pairs gain with every type row;
   an entry gains only when its last type fuses, and the 66 in-scope entries that admit `dec`
   wait on the lane pair's rows (359, 360, 363), since the census credits `dec` only when every
   form fuses. The range is the uncertain part: how many of those 66 the decimal rows finish.
2. The document check finds fewer than twenty stale places outside `m8/PLAN.md`'s counts, as
   milestone 7's audit listed fourteen.
3. The close branch's Build is green with no generated file to commit, as milestone 7's oracles
   regenerated byte-identical.
4. The cleanup removes more than 100 worktrees, against 91 at milestone 7's close, since 159 exist
   today.

## 7. Risks

1. The close finds a regression, as milestone 7's did. Step 5's Build is where it shows; the
   owner holds the close for it or files it, and the tag moves behind the fix.
2. Rows keep landing while the close is written, so the counts drift. Steps 2 and 3 are rerun on
   the commit the tag will sit on, and the pull request body names that commit.
3. The documents check is a grep, and a stale sentence that names no milestone escapes it. The
   record reads the README's and `docs/sql-varka.md`'s numeric claims against the census summary
   as well.
4. A worktree removed with uncommitted results. `gc` removes only clean ones, and the dirty ones
   are listed, never forced.

## 8. Sequencing, and what it waits on

The close starts when these rows are **Done**, or moved on the owner's word: every type and
function row that 1.1 items 1 to 6 read (the wave tables of `m8/PLAN.md` 4.1, waves 0 to 4), 352,
368 and 369 (items 1 and 3), 367 (item 5's opt-in mode), 353 (item 8's table), 552 and 553 (item
8's comparisons and surfaces), and 355, whose sources merge before the tag. Row 180 moves; the
harness, CI and test-speed rows of the 400s to 600s that no 1.1 item names are question 1's.

Pull requests, each under 1000 counted lines:

1. The rows' statuses and moves (step 1), before the tag.
2. The debt sweep's fixes, if any, before the tag.
3. The close: steps 2 to 8, 11 and 12, documents and plans only; estimated 600 to 900 counted
   lines, milestone 7's #919 being the model. It merges after the tag.
4. The review at the close, its findings filed where they go.

## 9. Outcome

*Filled at the close.*

## 10. Questions for the owner

1. **Rows still open when the type rows are done.** Recommended: close once every row 1.1 reads
   is done, and move the rest to `m10/SCOPE.md` with their plans and status cells kept, as rows 180
   and 386 moved at milestone 7's close; the alternative, holding the close for all 240 rows, keeps
   milestone 8 open for rows no done-when item needs. Which rows move is the owner's call.
2. **Where the tag sits.** Recommended: milestone 7's shape, on master after row 355's post sources
   and the debt sweep merge, with the close's documents-only pull request after it; milestone 6
   tagged the close's own merge. The owner chose between these once, for milestone 7 only.
3. **A review at the close as its own pull request.** Recommended: yes, as `m7/PLAN.md` section 12
   and #927 did; it found the unread nightly failures there. Not a standing rule yet.
4. **A newer stock feature release at the close.** Recommended: name it as a gap of item 8 and a
   new row, not a rerun inside the close, since the decision of 18 October 2026 keeps benchmarks
   out of it.

## 11. Done when

Each item of `m8/PLAN.md` 1.1 is met or its gap is named in section 9, the census and the cast
matrix are current on the closing commit with the counts quoted as 180 and 878, the documents say
milestone 8 is closed, the changelog entry and the `varka-m8` tag exist, and the worktrees and
branches of merged pull requests are removed.
