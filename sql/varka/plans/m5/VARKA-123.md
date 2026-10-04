# VARKA-123: the module gates measure the pull request

*Milestone 5, section 2.58. Opened 16 September 2026 by VARKA-106; implemented 17
September 2026.*

## 1. Where this came from

`.github/actions/checkout-and-sync` checks out apache/spark, squash-merges the
fork branch on top and exports the apache commit as `APACHE_SPARK_REF`; `Check
changes` then diffs `HEAD` against it. That difference is this fork's whole
divergence from upstream, not the pull request's change, so
`determine_modules_for_files` sees every Varka source, every module gate answers
true, and a plan-file edit costs the full matrix - about 36 jobs and an hour.
VARKA-106's documents-only run (#222, one plan file) measured it: `changed files
vs base: 331`, every module required.

## 2. The admission check, done

**There is a failed attempt in the record, and it constrains the design.** PR
#91 pointed `APACHE_SPARK_REF` at the fork's base by squash-merging
`vecbricks/varka` master *before* squash-merging the branch. It broke every
build. `git merge --squash` records no merge parent, so the second squash
computed its merge base against the old apache/spark commit where the fork
diverged - a tree in which no Varka file exists - and every file both merges
introduced collided (`CONFLICT (add/add)` on ten paths). The revert names the
two approaches that remain: point the reference at the fetched base commit
directly, which leaves apache/spark's drift in the diff, or **compute the pull
request's diff from real history and hand that to `is-changed.py` rather than
having it diff `HEAD`**. This task takes the second.

**What real history is available, and when.** `checkout-and-sync` already
fetches the branch, so its true head is in the repository as `FETCH_HEAD` before
anything is squashed. The branch and the branch it was cut from share real
ancestry, so their merge base is exactly the base the diff wants - and it must
be computed *before* `merge --squash`, which is the ancestry #91 destroyed.

**The base branch is in a third repository.** This branch lives in a personal
fork of apache/spark whose own master tracks apache/spark, while the pull
request is opened against `vecbricks/varka` master. A push event carries no
pull-request payload, so the upstream is named rather than derived, with
`VARKA_UPSTREAM_URL` overriding it for another deployment.

**What a wrong answer costs.** A base that is too old selects too many modules,
which is today's behaviour: wasteful, not wrong. A base that is too new would
select too few and skip a test that should have run. So every failure path - the
fetch failing, no merge base - falls back to the old reference rather than to an
empty diff.

## 3. The design

### 3.1 The mechanism

`checkout-and-sync`, before the squash: keep the fetched branch head, fetch the
Varka upstream's master, and export the pair `VARKA_DIFF_BASE` (their merge
base) and `VARKA_DIFF_HEAD` (the branch head). `dev/is-changed.py` and
`dev/run-tests.py` prefer that pair over `APACHE_SPARK_REF`, and `Check changes`
diffs it for the file list its own patterns read. `APACHE_SPARK_REF` stays
exported and stays the reference for the dependency-exception check, which does
want the upstream comparison, and for the "we are in a fork" test in
`run-tests.py` that reads its presence rather than its value.

### 3.2 What is deliberately unchanged

The squash merge itself, so the build still tests the combined tree. The module
map. Every gate's meaning. A fork that cannot reach the Varka upstream behaves
exactly as it does today.

## 4. Files

* `.github/actions/checkout-and-sync/action.yml` - the pair, computed before the
  squash.
* `dev/is-changed.py`, `dev/run-tests.py` - prefer the pair.
* `.github/workflows/build_and_test.yml` - the file list, and the comment that
  explained the old number.
* `sql/varka/plans/m5/PLAN.md` - row 123.

## 5. Tests, and what each is for

The measurement below, run locally against four real branches, and then the run
this pull request's own push produces - which is the check #91 never got.

## 6. The measurement

Changed-file counts for four branches, computed both ways on 17 September 2026:
against `fork/master` (an apache/spark commit, which is what `APACHE_SPARK_REF`
is) and against the merge base with `origin/master`.

| branch | old | new |
| :--- | ---: | ---: |
| `varka-task-134-partitions` | 2139 | 11 |
| `varka-task-85-emission` | 2134 | 3 |
| `varka-task-123-ci-gate` | 2133 | 4 |
| `varka-task-85-lane-leaves` | 2133 | 6 |

### 6.1 Predictions, registered before the run

1. The counts fall to the size of each pull request. Held: 2133 to 6.
2. A catalyst-only branch stops requiring `yarn` and `kubernetes`. Held for
  `varka-task-85-emission` and `varka-task-85-lane-leaves`, both false.
3. Not every gate narrows, because the module graph is real: a catalyst change
  still reaches `sql`, `hive` and their dependents, which is correct.

## 7. Risks

* **A skipped job that should have run.** The fallback is the old reference on
  every failure path, and the module map is untouched, so the only way to select
  too little is a base that is too new - which a merge base cannot be.
* **`dev/varka_*.sh` still selects `root`.** `dev/varka_bench_surface.sh` maps
  to the `root` module, so a benchmark-tooling change still runs everything.
  That is the module map's question rather than the base's, and it is left for a
  follow-up: a conservative answer is defensible there in a way it is not here.

## 8. Sequencing

1. The pair and the two readers. 2. The local measurement above. 3. This plan
and row 123. 4. The push, and its own `Check changes` job read against the
prediction that it prints a base line and a small count.

## 9. Outcome

Landed 17 September 2026, with its own run read and one limit of that reading
recorded rather than glossed.

**What the run proves.** The `Check changes` job on this branch printed `change
detection base: 18adf0dec19 (merge base with
https://github.com/vecbricks/varka.git master)` and `changed files vs base: 6` -
this pull request's own six files, against the 2133 the old reference gives for
the same branch. The fetch, the merge base and the diff all work in the runner,
which is what PR #91 never got to and what its revert asked for.

**What it cannot prove, and why.** The module verdicts in that run are still
mostly true, and correctly so: two of the six files are `dev/is-changed.py` and
`dev/run-tests.py`, which map to the `root` module - a pull request that changes
the test selector is exactly the one that should run the whole matrix. A
documents-only branch cannot demonstrate the narrowing either, because the
workflow loads the action from the branch under test, so the branch must carry
the fix, and carrying it means carrying those two files. **The end-to-end
demonstration therefore arrives with the first documents-only pull request
opened after this merges**, and section 6's local table is the evidence until
then: the same computation over four real branches, where a catalyst-only branch
stops requiring `yarn` and `kubernetes`.

**A probe was tried and thrown away.** A branch stacked on this one with a
single added comment line reported the same six files, for the reason above; it
was deleted and its run cancelled rather than left to look like a result.
