# VARKA-563: A push check that runs what the branch's changed files imply

## 1. Where this came from

By the owner's decision of 4 November 2026. Three CI rounds in two days were lost to tests far
from the change, which no brief named and no local step ran:

- **#1317**: a decline reason in `VarkaKernelPlan.java` was reworded, and `VarkaFilterExecSuite`
  pins the old text verbatim.
- **#1323**: the part was replayed onto master, git text-merged the generated files, and
  `dev/varka_regen_generated.sh` without `--all` regenerates only what a merge driver listed as
  owed, which a rebase lists nothing for; `docs/sql-varka.md`'s counts and `emitted_bytes.json`'s
  option-arm digests were stale.
- **#1338**: a new IR record, `CrossLane`, which the registry suites that enumerate every node
  type do not know (`VarkaWithChildrenSuite`, `VarkaLaneTypeSuite`, `VarkaEmitterBudgetSuite`,
  `VarkaReproductionSuite`).

A memory note catches only the same mistake again; a script turns each lesson into a rule. Rows
508 and 509 select tests on CI; this row is the local complement, run before the push.

## 2. The admission check

- Nothing local runs a test from the changed files. `dev/varka_prepush.sh` checks messages and
  lines; `dev/varka_new_tests.py` reruns tests a change adds, on CI; `dev/varka_scoped_suites.sh`
  runs every suite of a module.
- `dev/varka_matrix.sh --defaults --suites A,B` already builds once (sql's classpath holds
  catalyst's test classes) and runs the named suites of both modules with the sanitizer and the
  both-bodies check, as CI does, writing JUnit reports. The check drives it; it adds no runner.
- `dev/varka_generated_merge.py --check` and `--owed` both pass on #1323's pre-fix head
  `0a0e82df11f`: a rebase owes nothing, and `--check` looks for a missing coverage row, not a
  stale digest. Only the generated-file suites without their `REGEN` variables see it.
- The suites that enumerate node types are found by a `git grep -l -E` over the test trees for
  `getPermittedSubclasses`, `concreteNodeTypes`, `a node type was added`, `never drew` or
  `NOT_PARSED_YET`: seven files today, `VarkaEmitCostSuite`, `VarkaIrFuzzSuite` and
  `VarkaVectorIRSharingSuite` among them. All 53 IR records live in `VarkaVectorIR.java`.

## 3. The design

`dev/varka_push_check.py` reads the branch's diff against its merge base with origin/master
(`--base` names another) and turns it into steps, each owned by a rule:

- **R1, IR registry.** A record of `VarkaVectorIR.java`, or of a main file that implements its
  sealed interfaces, added, removed or with its components changed (the headers compared between
  base and head) runs every suite the grep above finds, read at run time, with the three named.
- **R2, reason text.** Every string literal on a removed line of a Varka main file (as
  `is_varka_scoped` reads the path; comments skipped) is cut into windows of five words. A window
  still in the head's main sources moved and is dropped; any other is looked for in the test
  trees, `sql/varka/coverage.json` and `docs/`, with each file's `" + "` joins collapsed, since
  source and test break a long string at different places. A hit fails at once with the file and
  the old text, and no JVM is needed to see it. The suites holding a hit, or a window of the
  added text, run.
- **R3, generated files.** `--owed` and `--check` always. When the branch changes a Varka main
  file or a generated file, `VarkaCoverageSuite`, `VarkaEmittedBytesSuite`, `VarkaTypesSuite` and
  `VarkaCastMatrixSuite` run without their `REGEN` variables; `--full` adds `VarkaWidthAuditSuite`,
  whose census is one CPU's. A failure names `dev/varka_regen_generated.sh --all`.
- **R4, house checks.** `dev/varka_precommit.sh --range`, `dev/varka_plan_lint.py --offline`,
  `dev/varka_quote_check.py`, `dev/varka_census.py --check` and `dev/varka_msg_check.py size`
  always; `--lint` adds `dev/varka_errorprone.sh`, `dev/scalastyle` and `dev/lint-java`.

The JVM steps are one `dev/varka_matrix.sh` call over the selected suites, `--module catalyst`
when no sql suite is selected, under `flock` on `VARKA_HEAVY_LOCK` (default
`~/proj/.varka-locks/heavy.lock`, `none` for no lock, as on CI) with `SPARK_LOCAL_IP=127.0.0.1`.
A failed suite is reported under the rules that chose it. `--dry-run` prints the steps and runs
nothing, `--no-jvm` runs the rules that need no JVM, and `--self-test` runs the rules over fixture
diffs and trees; `dev/varka_precommit.sh --selftest`, which the Build's lint job runs, calls it,
so no workflow changes.

The pre-push hook runs it only on request: `VARKA_PUSH_CHECK=fast` adds the `--no-jvm` rules,
`full` everything, for a push of the branch checked out. Unset, a push is as fast as before.

## 4. Open points for the lead

1. Should `fast` be the hook's default? It costs seconds, and since section 7 it catches all
   three incidents without a JVM (the table there). Open, for the owner.
2. R3's JVM step costs `VarkaEmittedBytesSuite` (445 s of CI weight) on every branch that touches
   a Varka main file. A source stamp in each generated file's header would make staleness a hash
   compare; worth a row? Section 7 sketches it, and says what the reflog signal leaves to it.
3. **Settled by the lead, 4 November 2026: no `--quick` dropping `VarkaIrFuzzSuite`.** R1 fires
   only when an IR record changes, which is exactly when the fuzz suite's "the generator never
   built" matters.
4. **Settled by the lead, 4 November 2026: R2 errs toward a false positive.** It matches literals
   only, so a reason built by `String.format` is matched by its fixed parts, and one moved into a
   constant of another file is still found there. A hit on a moved reason is the right side to
   err on; `VARKA_PUSH_CHECK_SKIP=R2` drops R2 for one push (any rule's name, comma-separated),
   which costs a word in the push and no change to the script.

## 5. Done when

The self-test proves each rule fires on its fixture and stays quiet beside it, and replays of the
three incidents' pre-fix heads each fail under the rule that names their cause. Added by the
second round (section 7): each also fails at `fast`, with no JVM, and a clean branch passes it.

## 6. Outcome

Built 4 November 2026 as one pull request, and replayed on the three incidents' pre-fix heads in
scratch worktrees (`results/VARKA-563-replays.txt`). Each fails, under the rule its lesson names:

- **#1317** at `d94ca2ad5a4`, the push before the suite was repinned: R2 names
  `VarkaFilterExecSuite` as still holding the removed text, in about six seconds with no JVM.
- **#1338** at `7897bd11a2f`: R1 runs eleven suites, and `VarkaWithChildrenSuite`,
  `VarkaLaneTypeSuite` (two tests) and `VarkaEmitterBudgetSuite` fail on `CrossLane`, as the
  pull request's Build did; 247 seconds of suites, 589 with the lock's wait and the build.
- **#1323** at `0a0e82df11f`: `--owed` and `--check` pass, and R3's suites fail on the docs'
  counts and `emitted_bytes.json`; 73 seconds of suites, 303 in all.

A branch that changes a Varka main file and no IR record pays R3's four suites, 73 seconds on
the laptop beside the build, as #1323 did; one that changes only plans or docs starts no JVM.

## 7. The second round: `fast` catches all three

By the lead's brief of 4 November 2026, after the replays above: only #1317 failed at `fast`.

- **R1 reads the registries as text.** For each record R1 names that the head's IR still has,
  every file the registry grep finds must name it where that suite looks, a table in the script
  (`REGISTRIES`, one line a suite): `VarkaWithChildrenSuite` and `VarkaIrFuzzSuite` a quoted
  entry in their exemption lists or a construction in `VarkaIrGrammar.java`; `VarkaLaneTypeSuite`
  an instance recipe `case "<Name>"`; `VarkaEmitterBudgetSuite` a register row, `new <Name>(` or
  `<Name>.of(`; `VarkaReproductionSuite` a round trip or a `NOT_PARSED_YET` entry; and
  `VarkaEmitCostSuite` a quoted feature, its own or `VarkaEmitCostFit`'s.
  `VarkaVectorIRSharingSuite` names no record, enumerating them reflectively; a registry missing
  from the table is printed as unchecked, the JVM run covering it. Run over every record rather
  than the changed ones, the table finds nothing on origin/master (53 records) or `4c7fd1c90d8`
  (54), and names `CrossLane` in exactly the three suites CI failed at `7897bd11a2f`. It is
  literal, so a name that appears for another reason passes it; the suites still run at `full`.
  This corrects section 1, which counts four registries: `VarkaReproductionSuite` had its round
  trip at `7897bd11a2f`, and CI failed four tests of three suites.
- **R3 reads the branch's reflog.** `--owed` reads the list the merge driver appends to, and its
  liveness rule cannot tell a committed rebase's entries from an aborted one's, so it drops them;
  the files carry no record of their inputs. The reflog keeps what it needs: a `rebase (finish)`
  entry names the commit it made and the commit it went onto, and the entry before it the tip it
  started from. A generated file both sides changed since their merge base is one the driver
  merged and owed, a rebase naming no other side. It stays owed until a commit after the rebase
  changes any of them (a regeneration commits only the files that moved: `d83b86566e2`, #1323's
  fix, changed two of the six) or `dev/varka_regen_generated.sh --all` runs on a commit since,
  which now leaves that commit under the git dir (`varka-regen-all`) for a regeneration that
  changed nothing. `--branch` names the branch when HEAD is detached, as in the replays. One
  over-count: the driver owes `docs/sql-varka.md` only for its generated blocks, and this rule
  sees the whole file, so prose both sides edited owes a run of `--all` that changes nothing.
- **The reflog is local, so the source stamp is still owed** (point 2). A fresh clone, another
  machine or a reflog past `gc.reflogExpire` has no entry, and a rebase by `git am` or by hand
  leaves none to read; there the JVM step is the check. The stamp: each generated file's header
  carries a SHA-256 over the sorted blob ids of the paths `FEEDERS` in
  `dev/varka_generated_merge.py` names for it (the docs blocks in an HTML comment), written by the
  suite that writes the file, and `--check` recomputes it from `git ls-files -s` and the working
  tree's changed files and names the stale file. A change to any feeder then owes a
  regeneration even where no row moves, which the author pays once and CI no longer does.
- **A missing ruff names itself.** The hook's refusal reads the pin from `dev/requirements.txt`
  (`ruff==0.14.8`, the pin `build_and_test.yml`'s lint job installs, held by `pyproject.toml`'s
  `internal_lint` group too) and prints the venv to make: `python3 -m venv ~/.venv-ruff`, `pip
  install` the pin, and its `bin` on PATH. The hook installs nothing.
- **The replays** (`results/VARKA-563-replays.txt`, second round), in scratch worktrees:

  | Head | `fast` | `full` |
  |---|---|---|
  | #1317 `d94ca2ad5a4` | fails, R2, 25 s | fails, R2 and its suite, 569 s |
  | #1338 `7897bd11a2f` | fails, R1, three registries, 27 s | fails, R1 and three suites, 452 s |
  | #1323 `0a0e82df11f` | fails, R3's rebase of six files, 32 s | fails, R3 and two suites, 668 s |
  | origin/master `85eb50e5e60` | passes, 4 s | passes, no suite, 4 s |
  | `4c7fd1c90d8`, #1338 fixed | passes, 54 s | passes, eleven suites, 403 s |
  | `d83b86566e2`, #1323 fixed | passes, 30 s | not run |

  `--range` of `dev/varka_precommit.sh` on `varka-348a3-choices`, which merged master twice,
  reads the 19 files of the branch's own change from the merge base and passes.
