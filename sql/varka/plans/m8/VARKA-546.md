# VARKA-546: Generated files that concurrent pull requests do not hand-merge

## 0. What exists now (admission check)

From `origin/master` at `ccd3c180bcb` with the plans branch merged, read on 1 November 2026.

1. **How each file is generated and what fails when it is stale.** Every file already has a check
   that fails on a stale copy and prints its own regeneration command:

   - `coverage.json` and the three generated blocks of `docs/sql-varka.md`: written and checked
     by `VarkaCoverageSuite` in the catalyst job, which prints
     `VARKA_COVERAGE_REGEN=true build/sbt 'catalyst/testOnly *VarkaCoverageSuite'`.
   - `emitted_bytes.json`: `VarkaEmittedBytesSuite`, catalyst job, `VARKA_BYTES_REGEN=true ...`,
     naming the block or the arm that moved.
   - `width_audit.json`: `VarkaWidthAuditSuite`, `VARKA_AUDIT_REGEN=true ...`; **its check is
     cancelled on any other CPU model**, so it runs on the laptop only.
   - `types.tsv`: `VarkaTypesSuite`, catalyst job, `VARKA_TYPES_REGEN=true ...`.
   - `casts_fused.tsv`: `VarkaCastMatrixSuite`, catalyst job, `VARKA_CASTS_REGEN=true ...`, then
     the census.
   - `census.tsv` and `casts.tsv`: `dev/varka_census.py`; `--check` in the docs job prints
     `census: <path> is stale; run dev/varka_census.py`.

   So the row's second done-when clause, "a stale file fails CI with its regeneration command",
   holds today for every file but one. `width_audit.json` is a property of the CPU and the JDK
   (`VarkaWidthAuditSuite`'s header): its check runs only on the CPU model in the file's `host`,
   the laptop's Ryzen AI 9 HX PRO 370, and CI's runners cancel it with the reason. A branch whose
   new coverage row has no width-audit entry passes CI and fails the next laptop run.
2. **How often they conflict.** `results/VARKA-546-merges.txt`: of 265 merges of master into a
   branch between 1 October and 1 November 2026, 37 resolved at least one generated file inside
   the merge commit, 13 of them on 1 November alone. By file: `docs/sql-varka.md` 25,
   `coverage.json` 22, `width_audit.json` 22, `emitted_bytes.json` 20, `census.tsv` 15,
   `types.tsv` 8. `PLAN.md` was resolved in 195, which is row edits and outside this row
   (section 6).
3. **What the conflicts are.** The combined diffs of the 1 November merges show three kinds.
   - *Adjacent insertions.* Two rows added at the same place: the coverage table appends a
     family's rows together, and `width_audit.json`'s per-width maps are sorted by key, so two
     rows whose SQL sorts together touch the same lines (merge `861f26af7a7`, six hunks there).
     A per-key merge resolves these exactly.
   - *Aggregates over every row.* `emitted_bytes.json`'s `option_arms` holds one digest per arm
     and width over every shape, so any two branches that add a shape both rewrite all 22 lines
     (merge `ebd130ce20c`, its only hunk). The docs file's verification-counts block counts
     rows. No merge can compute these; they are regenerated.
   - *Status cells.* `census.tsv` rows whose `fused_today` both sides changed. The census is
     rendered by Python from its inputs, so it is regenerated after the merge, never merged.
4. **The files' structure allows an exact per-key merge.** A Python renderer of Jackson's pretty
   printer (`"key" : value`, objects indented two spaces per object level, arrays inline,
   `[ ]` and `{ }` when empty, a final newline) reproduces `coverage.json`,
   `emitted_bytes.json` and `width_audit.json` byte for byte, and also the last twelve committed
   versions of each (36 files). A merged object can therefore be written back in the exact form
   the suites write, and a suite's check passes on it when the merge was right.

   Within each file the per-row parts are independent of other rows: a coverage expression is
   keyed by its `sql`; `emitted_bytes.json`'s `lanes.<n>.coverage` maps are keyed by row;
   `width_audit.json`'s `widths.<bits>` maps are keyed by shape (the coverage rows plus the
   `construction:` shapes of `VarkaWidthAuditShapes`) and sorted; `catalyst_classes_admitted`
   is a sorted set; `types.tsv` rows are keyed by `id`, `casts_fused.tsv` rows by `from`, `to`.
   The width audit's per-shape refusals are taken on one host; two sides with the same `host`
   merge exactly, two with different hosts do not (their `host` fields conflict).
5. **Who can regenerate where.** CI could regenerate `coverage.json`, `emitted_bytes.json`,
   `types.tsv`, `casts_fused.tsv` and the census, but not `width_audit.json` (a runner's census is
   another CPU's), and the fork has no token to push to vecbricks/varka (the owner's "no token"
   for the fork sync, row 525). The lead's merges of master into a branch happen in the lead's
   clone, where a git merge driver can run.

### What differs from the row text

- The row offers "regenerate in CI and commit". That is excluded: the fork has no token for
  vecbricks/varka, and CI cannot produce the laptop's width audit. Its check-only half is option
  (i), which exists already but for the width audit.
- "Without a conflict in a generated file" is met by a merge that leaves no conflict markers. The
  aggregates of item 3 still need one regeneration after such a merge until (ii) below reshapes
  `option_arms`; the merge says so and names the one command.

## 1. Goal and non-goal

**Goal.** Merging master into a type branch, or a type branch into master, never stops on a
generated file. The parts a merge can compute are merged exactly; the parts it cannot are
written from one side and listed, with one command that regenerates them in the documented
order. A stale file still fails CI with its command, now including the width audit's missing
rows.

**Non-goal.** `PLAN.md`'s row conflicts (a different problem: hand-written prose; the lead's rule
"master plus own rows" stands). Regenerating on CI. Changing what any suite checks.

## 2. Design options

### 2.1 (i) A CI check that fails a stale file and prints its command

Exists for all files but the width audit (item 1). The gap is closed by a host-independent
check in Python: every coverage row is a key of every width of `width_audit.json`, and every
key there is a coverage row or a `construction:` shape; and the same for
`emitted_bytes.json`'s per-lane coverage maps against `coverage.json` minus
`coverage_rows_skipped`, which fails in a second rather than after the bytes suite's seven
minutes. It runs in the docs job beside `dev/varka_census.py --check`, which already runs on
every change to `coverage.json`. Cost: small. It does not remove a conflict; it makes the
resolution "take master's side, run the script".

### 2.2 (ii) Sharding

What each file allows, measured by its readers (`git grep` over sources and scripts):

- `coverage.json`: 25 readers (suites in catalyst and core, `dev/varka_census.py`, the fuzzers,
  a demo). Sharding it by family moves every reader to a directory walk. Not cheap, and the
  per-key merge of 2.3 gives the same result for its rows.
- `width_audit.json`: four readers, but its conflicts are adjacent sorted keys, which 2.3
  merges exactly. Sharding buys nothing more.
- `emitted_bytes.json`: the bytes suite alone reads it. Its row maps merge per key; the one
  shared part is `option_arms`, a digest per arm and width over every shape. Keeping the check
  without one shared digest means making it row-local: each coverage row's entry carries, per
  arm, its digest only where the arm's emission differs from the default's, and the fuzz
  sequences keep their per-arm digests (their shapes are fixed by seed and do not grow with
  rows). Then two rows never touch the same line, and the merge needs no regeneration. Cost: a
  Scala change to `VarkaEmittedBytesSuite` and one regeneration, about 445 seconds on CI's
  runners (`sql/varka/ci/suite-times.tsv`); the file grows by the rows an arm moves.
- The docs file's verification counts: rendered from the rows; they could be dropped from the
  docs file or moved to a generated file of their own that is regenerated rather than merged.
  Deferred to the owner (question 2).

### 2.3 (iii) A merge driver that merges the generated files per key

A `.gitattributes` `merge=varka-generated` rule on the files, and `dev/varka_generated_merge.py
driver %O %A %B %P` as the driver, installed by `dev/varka_precommit.sh --install-hook` into the
clone's config, which every worktree shares:

- **JSON** (the three files): a recursive three-way merge. Objects merge per key; lists of
  objects with an `sql` key merge per row; a sorted list of strings merges as a set. Order: the
  base's order, each side's additions placed after the base key that precedes them on that side;
  where both sides add after the same key, the two groups are ordered by their first key, so the
  result is the same whichever side is "ours"; a map whose keys are sorted on every side stays
  sorted. A scalar both sides changed differently is a conflict the driver cannot compute: it
  writes the smaller of the two (again the same in either order) and lists the path as owed.
- **TSV** (`types.tsv`, `casts_fused.tsv`, `census.tsv`, `casts.tsv`): the same merge over rows
  keyed by the leading key columns. The census pair is listed as owed whenever both sides moved
  it, because `dev/varka_census.py` rewrites it from the merged inputs in a second.
- **The docs file**: `git merge-file`; a conflict inside a `BEGIN generated` block is resolved to
  the smaller side and coverage is listed as owed; a conflict in the prose outside the blocks is
  a real one and is left with its markers.
- **What is owed** goes to stderr during the merge and to `$(git rev-parse --git-path
  varka-regen-owed)`, each entry tagged with the merge that owed it (the commit merged into and
  the commit merged in), so an aborted merge's entries are never read. A squash merge,
  cherry-pick, revert or rebase is read while it is uncommitted or stopped on a conflict; the
  comment above `owed_live` names the debt no state in the repository records (those operations
  once committed, `stash pop`, `checkout -m`), for which the stderr line is the record. A file
  whose only debt is the census goes on no list, since the census runs last on every
  regeneration.
  `dev/varka_regen_generated.sh` reads the live entries (`--owed`) and runs only the owed
  steps, in the documented order (types and casts, coverage, width audit, bytes, coverage again,
  the census), under `flock` on a heavy slot with `SPARK_LOCAL_IP=127.0.0.1`, refusing the width
  audit on a CPU that is not the file's host. With nothing owed it says so and runs nothing.
- A branch that predates the script falls back to git's text merge inside the driver line, with
  a one-line warning, so the rule never breaks a merge. The inner merges of a criss-cross merge
  (`merge.varka-generated.recursive=text`) take the text merge too.

GitHub's own merge preview ignores custom drivers, so a pull request page can still show
"conflicts"; the lead's local merge of master is what resolves them, as today.

### 2.4 (iv) Regenerate in CI and commit

Excluded: the fork's workflows have no token to push to vecbricks/varka (the owner chose no token
for the fork sync), and CI cannot write the laptop's width audit (item 5). Option (i) is its
check-only half.

### 2.5 The recommendation

(iii) and (i) now, (ii) for `option_arms` after them. (iii) removes every hand merge in a
generated file and makes the exact parts right without a JVM; (i) catches the width audit's gap
on CI and catches a skipped regeneration for every file; (ii) for `option_arms` removes the last
regeneration a merge of two row-adding branches owes, and is the owner's question 1 because it
changes the bytes oracle's format.

## 3. Tests and proofs

- `dev/varka_generated_merge.py --self-test`: the merge on synthetic files in a temporary git
  repository: two branches adding rows at the same anchor merge in both orders with no markers
  and byte-identical results; a scalar conflict is listed as owed; a sorted map stays sorted; a
  prose conflict in the docs file is left with markers; the renderer round-trips the committed
  JSON files byte for byte. Run in the docs job beside the other tools' self-tests.
- `dev/varka_generated_merge.py --check` on the tree passes; with a coverage row added to
  `coverage.json` alone it fails, naming the row and printing `dev/varka_regen_generated.sh`.
- The done-when's demonstration, scripted in a temporary clone of this branch
  (`results/VARKA-546-demo.txt`): branches A and B each add a coverage row at the same place in
  every generated file, as the suites would write them. Plain `git merge` conflicts in every
  file; with the driver installed, master+A+B and master+B+A merge with no conflict, give
  byte-identical trees, and list `option_arms` and the verification counts as owed.

## 4. Predictions and costs, registered before the build

- P1: with the driver, a merge of master into a branch where both added coverage rows leaves no
  markers in any generated file, and owes exactly the bytes regeneration (`option_arms`) and the
  coverage regeneration (the docs counts), plus the census when both moved it.
- P2: the width audit is never owed between two branches regenerated on the laptop; of item 2's
  22 width-audit resolutions none would have needed the laptop's seven-to-eight-minute audit.
- P3: the owed regeneration is one command whose JVM cost is the bytes suite plus two coverage
  runs, against today's four commands plus the audit.

## 5. Order, files and size

| File | Change |
|---|---|
| `dev/varka_generated_merge.py` | the driver, `--check`, `--self-test` |
| `dev/varka_regen_generated.sh` | the owed regenerations, in order, under a heavy slot |
| `sql/varka/.gitattributes` | the `merge=varka-generated` rule for the files under `sql/varka` |
| `dev/varka_precommit.sh` | `--install-hook` also sets the driver and the docs file's rule |
| `.github/workflows/build_and_test.yml` | the docs job runs `--check` and `--self-test` |
| `dev/sparktestsupport/modules.py` | the new tools in the docs module's sources |
| `sql/varka/ADDING_A_TYPE.md`, `sql/varka/AGENTS.md` | the merge and the one regeneration command |
| `results/VARKA-546-demo.txt` | the demonstration |

The docs file's rule goes to `$(git rev-parse --git-path info/attributes)` at install, because a
`docs/.gitattributes` or a root `.gitattributes` is not Varka-scoped (`modules.py`'s
`varka_scoped_regexes`) and would send its pull request through Spark's whole matrix.

Order: the plan; the driver with its self-test; the install path, the check and the CI step; the
docs; the demonstration. (ii) after the owner's answer.

Size: medium.

## 6. Out of this row

- `PLAN.md` row conflicts (195 resolutions in item 2): prose, merged by hand under the lead's
  rule.
- (ii) for `option_arms` until question 1 is answered.
- Regeneration in CI (2.4).

## 7. Questions for the owner

1. **`option_arms`.** (a) Keep one digest per arm and width over every shape, and accept one
   bytes regeneration (CI: about 445 seconds for the suite) after each merge of two row-adding
   branches, run by `dev/varka_regen_generated.sh`. (b) Make the arms row-local (2.2): each
   coverage row carries an arm's digest only where it differs from the default emission, the
   fuzz sequences keep theirs. Recommended: (b), as a follow-on row after this one lands; it
   removes the last owed regeneration and keeps the check as strong.

   *1 November 2026, by the owner's word:* (b), as its own row, 547: a digest per row for
   each option setting that changes that row's emitted code. Question 2 is decided with it.
2. **The docs file's verification counts.** (a) Keep them in the docs file, regenerated after a
   merge with the coverage run that (a) of question 1 needs anyway. (b) Move them out. Recommended:
   (a) while question 1 is (a); revisit with (b) of question 1, when they would be the last owed
   regeneration.

## 8. Done when

- two branches that each add a coverage row merge with master in either order with no conflict
  in a generated file (`results/VARKA-546-demo.txt`);
- a stale file fails CI with its regeneration command, the width audit's missing rows included
  (`--check` in the docs job).

## 9. Open points for the lead

1. The driver acts only in a clone that ran `dev/varka_precommit.sh --install-hook` after this
   lands. Run it once in the main checkout; worktrees share its config.
2. A merge that owes a regeneration leaves the merge commit with stale aggregates. Commit the
   regeneration on top before pushing; CI's checks fail the push otherwise.

## 10. Outcome

Built on 1 November 2026: (iii) and (i) as section 5 lists them; (ii) waits for question 1.

- **The demonstration** (`results/VARKA-546-demo.txt`). Without the driver, both orders stop on
  four files: `docs/sql-varka.md`, `coverage.json`, `emitted_bytes.json`, `width_audit.json`.
  With it, both merges exit 0 with no unmerged path, give the same tree (`2af1c3cab23`, rerun
  after the review below and again after the fixes of 2 November 2026) and pass `--check`.
  `width_audit.json` merged per key with nothing owed. Owed: `emitted_bytes.json` (the 22
  `option_arms` digests and the place of the two rows), `coverage.json` (the place of the two
  rows, and the census, which reads it) and the docs file's generated blocks (the coverage
  table and the verification counts, which both sides changed).
  `dev/varka_regen_generated.sh --dry-run` prints one sbt call: coverage, bytes, coverage
  again, then the census.
- **A stale file on CI.** `--check` fails a `coverage.json` row that the width audit and the
  bytes file lack, printing the width audit's command with its host and the bytes command.
  `--self-test` runs this case, the merge cases, the round trip of the three committed JSON
  files, and a two-branch merge in a temporary repository. Both run in the docs job. Neither
  has run on CI yet, because nothing was pushed.
- **Predictions.** P1 held: no markers, and what was owed is what P1 named; the census is owed
  whenever both sides moved `coverage.json`, since it reads it. P2 held for the
  demonstration's merge: the width audit owed nothing. Item 2's 22 historical resolutions
  were not replayed. P3 held: one command, the bytes suite plus two coverage runs, and no
  width audit.
- **Not planned.** When both sides add rows at the same place in the docs table, the merge keeps
  one side's block until the coverage run rewrites it. So the merge commit can lack the other
  row in the docs table. The block is owed and the coverage suite fails until it is
  regenerated (section 9, item 2).
- **The review.** A code review of the driver reproduced ten faults; each has a `--self-test`
  case that failed before its fix (36 cases now, from 20).
  - (1) `casts_fused.tsv` is keyed by `from`, `to`, `from_type`, `to_type`: one row per form
    pair, so `from`, `to` repeats (46 pairs) and the per-key merge dropped rows, owing
    nothing (item 4 above names the two-column key). A TSV or a keyed list whose key repeats
    on any side is now owed and written from one side.
  - (2) Two groups added after one anchor are ordered by the whole group, not its first key, so
    two groups that both start with a row both sides added order the same in either merge.
  - (3, 6) The merge sorted any structure whose keys happened to be sorted on all three sides
    (2.3's "a sorted list of strings merges as a set"), so a TSV header two sides each widened
    became two header lines, comment lines united, and a row's `switches` or a shape's
    refusals united with nothing owed. Now only what a writer sorts is merged as sorted: the
    width audit's per-width maps and `catalyst_classes_admitted`, declared per file. Every
    other order keeps its anchors, a value list in one row that both sides changed is owed,
    and a TSV's header and comments are whole text, owed when both sides changed the file
    under different ones.
  - (7) A docs block both sides changed is owed even when `git merge-file` merged its lines.
  - (8) Any error in the driver falls back to git's text merge, with markers.
  - (4) `width_audit.json` and `emitted_bytes.json` were added to `varka_docs_regexes`, which
    made a change to them alone "docs" (the docs job only, no catalyst suites). They are a
    separate tuple now, added to the `varka-docs` module's sources alone.
  - (5) `dev/varka_regen_generated.sh` skips an owed width audit on another CPU, keeps it owed
    and runs the rest; the CPU name is trimmed as the suite trims it.
  - (9) The installer adds a newline to an `info/attributes` lacking one before its rule, and
    never adds the rule twice.
  - (10) `--check` builds its row sets once (no behaviour change, so no case).
- **Left.** The lead runs `dev/varka_precommit.sh --install-hook` in the main checkout once this
  merges. The two checks run green on the pull request's Build. Question 1, then (ii) for
  `option_arms` as its own row if the answer is (b).
