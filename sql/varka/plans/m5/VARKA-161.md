# VARKA-161: the quote check reads the merge it is asked to commit

*20 September 2026. Milestone 5 section 2.97, row 161.*

## 1. The finding

`dev/varka_quote_check.py` promises that every number a plan quotes traces to a
committed results file, in the current tree or anywhere in the history of the
results directories, and `dev/varka_precommit.sh` runs it on every commit. The
history walk was `git log -p --full-history -- <dirs>`, which starts at `HEAD`.

While a merge is being committed `HEAD` is the branch alone; the incoming side
is `MERGE_HEAD`, and nothing it committed is reachable yet. So a number that
only the incoming side's history holds is an orphan to the hook and a committed
fact one command later. The check itself was right about master and right about
the committed merge; it was blind in the one state where the hook runs.

VARKA-154's branch met it. VARKA-149 had committed the route A measurement of
`VarkaTimeBenchmark` and quoted it in `VARKA-149.md`; the later restore of
the files that `VARKA-102.md` 8.6 quotes replaced those numbers in the
current files, so on master they are historical only. VARKA-154's branch
predates VARKA-149, and merging master into it left 13 quotes - the
`VARKA-149.md` table and two sentences of `VARKA-102.md` - with no
justification in the branch's own history.

| tree | orphans |
|---|---|
| master | 0 |
| VARKA-154's branch, merge of master staged, before the change | 13 |
| the same merge committed past the hook | 0 |

## 2. The change

The walk takes `MERGE_HEAD` beside `HEAD` when `git rev-parse --verify
MERGE_HEAD` succeeds, so the hook sees the union of both parents' histories,
which is exactly what the merge commit's history will be. A tree with no merge
in progress runs the same command as before.

## 3. Verification

A synthetic merge reproduces the state without a real branch: check out the
commit before VARKA-149's (`ee290a476f0^`), start `git merge --no-ff --no-commit
origin/master`, and run both versions of the checker on that tree. The
unpatched checker reads 13 orphans, the patched one 0, and on a plain tree both
read 0. The numbers in section 1 are those runs.

The pre-commit hook needs no change: it calls the checker, which now decides
its own revision list.
