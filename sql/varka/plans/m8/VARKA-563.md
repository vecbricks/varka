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

1. Should `fast` be the hook's default? It costs seconds and would have caught #1317.
2. R3's JVM step costs `VarkaEmittedBytesSuite` (445 s of CI weight) on every branch that touches
   a Varka main file. A source stamp in each generated file's header would make staleness a hash
   compare; worth a row?
3. R1 costs `VarkaIrFuzzSuite` (298 s of CI weight). A `--quick` that drops it?
4. R2 matches literals only; a reason built by `String.format` from a pattern is matched by its
   fixed parts, and a reason held in a constant moved to another file is still found there.

## 5. Done when

The self-test proves each rule fires on its fixture and stays quiet beside it, and replays of the
three incidents' pre-fix heads each fail under the rule that names their cause.

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

A branch that changes a Varka main file and no IR record pays R3's four suites, 73 seconds of
suites on the laptop beside the build, as #1323 did; one that changes only plans or docs starts no JVM.
