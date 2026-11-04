# VARKA-563: A push check that runs what the branch's changed files imply

## 1. Where this came from

By the owner's decision of 4 November 2026. Three CI rounds in two days were lost to tests far
from the change, which no brief named and no local step ran:

- **#1317**: a decline reason in `VarkaKernelPlan.java` was reworded; `VarkaFilterExecSuite` pins
  the old text verbatim.
- **#1323**: replayed onto master, git text-merged the generated files, and
  `dev/varka_regen_generated.sh` without `--all` regenerates only what a merge driver listed as
  owed, nothing for a rebase; the docs' counts and `emitted_bytes.json`'s digests were stale.
- **#1338**: a new IR record, `CrossLane`, unknown to the suites that enumerate every node type
  (`VarkaWithChildrenSuite`, `VarkaLaneTypeSuite`, `VarkaEmitterBudgetSuite`).

A script turns each lesson into a rule; rows 508 and 509 select tests on CI, this runs locally.

## 2. The admission check

- Nothing local runs a test from the changed files (`dev/varka_prepush.sh` checks messages and
  lines; `dev/varka_new_tests.py` reruns added tests on CI). `dev/varka_matrix.sh --defaults
  --suites A,B` builds once and runs named suites of both modules as CI does; the check drives it.
- `dev/varka_generated_merge.py --check` and `--owed` both pass on #1323's `0a0e82df11f`: a
  rebase owes nothing, and `--check` looks for a missing coverage row, not a stale digest.
- `git grep -l -E` of the test trees for `getPermittedSubclasses`, `concreteNodeTypes`, `a node
  type was added`, `never drew` or `NOT_PARSED_YET` finds seven registry suites; all 53 IR
  records live in `VarkaVectorIR.java`.

## 3. The design

`dev/varka_push_check.py` turns the branch's diff against its merge base with origin/master
(`--base` names another) into steps, each owned by a rule:

- **R1, IR registry.** A record of `VarkaVectorIR.java`, or of a main file implementing its
  sealed interfaces, added, removed or with changed components runs every suite the grep finds,
  plus `VarkaEmitCostSuite`, `VarkaIrFuzzSuite` and `VarkaVectorIRSharingSuite`.
- **R2, reason text.** Each string literal on a removed line of a Varka main file is cut into
  five-word windows. One still in the main sources moved; any other is looked for in the test
  trees (comments dropped), `sql/varka/coverage.json` and `docs/`, `" + "` joins collapsed. A hit
  fails at once, a document's named as one (a stale example is a true finding); the suites
  holding a hit, or a window of the added text, run.
- **R3, generated files.** `--owed` and `--check`; a change to a Varka main or generated file
  runs `VarkaCoverageSuite`, `VarkaEmittedBytesSuite`, `VarkaTypesSuite` and
  `VarkaCastMatrixSuite` without `REGEN`, `--full` adds `VarkaWidthAuditSuite`. A failure names
  `dev/varka_regen_generated.sh --all`.
- **R4, house checks.** `dev/varka_precommit.sh --range`, plan lint, quote check, census check
  and the size cap; `--lint` adds Error Prone, scalastyle and lint-java.

The suites run in one `dev/varka_matrix.sh` call under `flock` on `VARKA_HEAVY_LOCK` with
`SPARK_LOCAL_IP=127.0.0.1`. `--dry-run` runs nothing, `--no-jvm` only what needs no JVM, and
`--self-test`, which `dev/varka_precommit.sh --selftest` calls, runs the rules over fixtures. A
failure names `VARKA_PUSH_CHECK_SKIP=<rule>` and `VARKA_PUSH_CHECK=off` before `--no-verify`,
which skips the message and line checks too. The pre-push hook runs `--no-jvm` on each push of
the branch checked out (`VARKA_PUSH_CHECK=fast`); `full` runs everything, `off` none.

## 4. Open points for the lead

1. **Settled by the owner, 4 November 2026: `fast` is the hook's default** (3 s; replays 25-54 s).
2. R3's JVM step costs `VarkaEmittedBytesSuite` (445 s of CI weight) on every branch touching a
   Varka main file; a source stamp, a SHA-256 of its `FEEDERS`' blob ids
   (`dev/varka_generated_merge.py`) in each generated file's header, which `--check` recomputes,
   would make staleness a hash compare. A row?
3. **Settled by the lead, 4 November 2026: no `--quick` dropping `VarkaIrFuzzSuite`**, which R1
   runs only when a record changes, exactly when "the generator never built" matters.
4. **Settled by the lead, 4 November 2026: R2 errs toward a false positive**, and
   `VARKA_PUSH_CHECK_SKIP=R2` (rules comma-separated) drops a rule for one push.

**Owed: a follow-up row** (third-round review, 4 November 2026; the reviewer's probe each):

- An IR record in another file implementing a sealed interface passes R1: the name filter reads
  only `VarkaVectorIR.java`, and `IMPLEMENTS_IR` misses `implements Chrono`.
- R2 misses a main-side reason built from short `" + "` pieces or a text block (`windows()`).
- `sql/varka/spark_suites/served.json` (`VARKA_GOLDEN_SUITE_REGEN`) is outside the driver's list.
- A reason moved and re-wrapped differently fails R2 (`w in other` reads one wrapping).
- R1 noise: a non-node helper record in `VarkaVectorIR.java`, spacing-only reformats, annotated
  components and generic records each read as changed.
- Hook: a missing origin/master skips the per-ref checks silently; an unknown `VARKA_PUSH_CHECK`
  acts as `off`; the working tree is read, not the pushed commit; a local master ahead of
  origin/master runs R1-R4 on a master push.

## 5. Done when

The self-test proves each rule fires on its fixture and stays quiet beside it, and the three
incidents' pre-fix heads each fail under the rule that names their cause at `fast`, with no JVM
(#1323 with the second part, section 7), while a clean branch passes.

## 6. Outcome

Built 4 November 2026; replays (`results/VARKA-563-replays.txt`, with times) failed #1317 under
R2, #1338 under R1 (three suites on `CrossLane`, as CI) and #1323 under R3, only #1317 at `fast`.

## 7. The second and third rounds

- **R1 reads the registries as text** (`REGISTRIES`, one line a suite): each record R1 names must
  appear where each registry looks, from a quoted exemption or a `VarkaIrGrammar.java`
  construction to `case "<Name>"`, a `new <Name>(` or a quoted cost feature;
  `VarkaVectorIRSharingSuite` enumerates reflectively, and an unlisted registry is printed as
  unchecked. Over every record it finds nothing on origin/master or `4c7fd1c90d8`, and names
  `CrossLane` in exactly the three suites CI failed (`VarkaReproductionSuite` had its round trip).
- **R3's rebase debt is a second part, `varka-563b-rebase-debt`** (section 8), stacked on this: the
  branch's reflog read for a rebase's text merges, and `dev/varka_regen_generated.sh --all`'s
  marker. Without it `fast` does not catch #1323, whose `--owed` and `--check` pass; `full` does.
- **A missing ruff names its pin**: `dev/varka_precommit.sh` reads `ruff==` from
  `dev/requirements.txt`, which the Build's lint job installs, and prints a venv recipe.
- `dev/varka_precommit.sh --range` on `varka-348a3-choices` (master merged twice) reads 19 files.
- Third round (the high review's fixes): on this part #1317 fails under R2 and #1338 under R1 at
  `fast`, #1323 only at `full`, and the clean heads pass (`results/VARKA-563-replays.txt`).

## 8. The second part: R3's rebase debt

- **R3 reads the branch's reflog** (`--branch` names it for a detached HEAD). `--owed` cannot tell
  a committed rebase's entries from an aborted one's. A `rebase (finish)` entry, a `pull
  --rebase`'s too, names its commit and the one it went onto, the entry before it the old tip: a
  generated file both sides changed since their merge base is owed, held at the commit made.
- **Held on the commit that merged each file**: the oldest commit of the rebase's `onto..tip`
  that changes the file carries its debt, so a reset or re-commit of the tip (`reset --soft
  HEAD~1`, a dropped WIP tip, a `rebase -i` edit stop) still owes. The walk keeps every debt and
  payment as an event, and a file is owed when the latest event HEAD descends from is a debt: a
  `reset --hard` to `ORIG_HEAD`, the old tip or `origin/<branch>` undoing the rebase owes
  nothing, a reset back to its tip owes again, and a later rebase or amend of a commit that owes
  carries the debt to its new commits (master moved and rebased onto again still owes).
- **Paid file by file**: a commit pays the files it changes (a regeneration commits only what
  moved), so prose in `docs/sql-varka.md` pays no other file, and a paying commit reset away pays
  nothing; `dev/varka_regen_generated.sh --all` leaves in the git dir's `varka-regen-all`,
  written whole, the commit it started on and the files its run left as that commit has them, so
  a regenerated file nobody committed, or a width audit skipped on another CPU, stays owed; a
  HEAD moved during the run writes no marker. A file the branch's commits leave as the base has
  it owes nothing; uncommitted edits count for nothing. Prose both sides of `docs/sql-varka.md`
  edited owes an idle `--all`.
- **#1323's fix `d83b86566e2` changed two of the five owed files**, `docs/sql-varka.md` and
  `emitted_bytes.json`; the other three were paid by `--all` on `0a0e82df11f` (every step passed,
  the audit's too) before the marker existed, so its replay writes the marker; without it they owe.
- **The reflog is local**: a fresh clone, another machine or a reflog past `gc.reflogExpire` has
  no entry, nor has a rebase by `git am` or by hand, nor a branch made from a rebased one; where
  there is no reflog, the JVM step is the check, and the source stamp (point 2) would close it.
- Third round: with this part all three incidents fail at `fast` and the clean heads pass; the
  table is in `results/VARKA-563-replays.txt`.
