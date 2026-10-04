# VARKA-143: the pre-commit hook judges the commit, not the file

*Milestone 5, section 2.78. Opened and done 17 September 2026, from a finding in
VARKA-142's own commit.*

## 1. Where this came from

Merging master into the VARKA-142 branch produced a commit that changed no Python
at all, and `dev/varka_precommit.sh` reported four findings against it: three
lines over 100 columns in `dev/sparktestsupport/modules.py`, a file the merge
merely carried across unchanged from master, and a missing `ruff`. Committing it
needed `--no-verify`, which switches off every other check in the hook at the
same time.

Correcting `CLAUDE.md` in this task showed the same shape one step worse: the
file's own prose uses em-dashes and arrows, so touching one paragraph raised
twenty-two non-ASCII findings, none of them this commit's.

A check that fires on lines the committer did not write, and cannot fix, teaches
everyone to bypass it. That is worse than not having the check.

## 2. The admission check, done

**The three long lines are not violations at all.** Spark's `[tool.ruff.lint]`
sets `extend-select = ["I", "G010", "RUF001-003", "RUF100"]`, which extends
ruff's default `E4, E7, E9, F`; `E501` lives in `E5` and is therefore never
selected - `--show-settings` lists 66 enabled rules and `line-too-long` is not
among them. Even forced on with `--select E501`, ruff exempts a line of one
whitespace-separated chunk, which is what `"pyspark.sql.tests.pandas.streaming.
test_pandas_transform_with_state_state_variable_checkpoint_v2",` is. Measured on
the pinned ruff 0.14.8 with a six-shape probe: a 112-character one-chunk line and
a 110-character one-word comment pass; two-chunk and longer lines are flagged.

**So `ruff format` is the only Python authority on width**, and it cannot split a
string literal or a comment either. The hook's column scan for Python could only
ever report what nothing asks to be fixed.

## 3. The design

Two changes, one narrow and one general.

**The narrow one.** The column scan exempts a Python line of fewer than two
whitespace-separated chunks, which is ruff's own rule, measured rather than
recalled. Scala and Java stay strict, because scalastyle and checkstyle grant no
such exemption.

**The general one.** Every line-anchored finding - non-ASCII, the column scan,
the TODO marker - is raised only for a line in the commit's own diff, computed
from `git diff -U0` against the staged index or `HEAD`. An untracked file is all
new, so all of it counts. Naming files on the command line turns the scoping off,
since naming a file is a request to see everything in it. The whole-file checks
(the quote check, the contents index, the bench gate, the tool self-tests) are
unaffected: they answer about the repository, not about a line.

## 4. Files

* `dev/varka_precommit.sh` - both changes, the header that explains them, and
  `--selftest`.
* `CLAUDE.md` - the Python line-length claim, corrected.
* `sql/varka/skills/build-and-environment.md` and `SKILLS.md` - the ruff lesson
  and the one about a hook's nested git commands.
* `sql/varka/plans/m5/PLAN.md` - section 2.78 and row 143.

## 5. Tests, and what each is for

`dev/varka_precommit.sh --selftest`, run by the hook itself whenever the hook is
among the committed files, so a change to it cannot land unchecked:

* a wrappable Python line is reported - the rule still works;
* a single-chunk Python line is not - the exemption applies;
* a single-chunk Scala line is - the exemption is Python's alone;
* in a throwaway repository, a long line already committed is not reported under
  an unrelated edit, and a long line the commit adds is.

A fifth assertion guards the fixture itself: the outer repository's `HEAD` must
be what it was when the self-test returns. Section 7 says why.

Each case was mutation-checked: removing the exemption, widening it to every
language, forcing the scope to always or never report, and neutralising the
environment stripping each make exactly the expected case fail and no other. The
last of those has to be checked with an absolute `GIT_DIR`, as a real hook
receives - with a relative one the leak does not reproduce, because `GIT_DIR=.git`
re-resolves against the fixture's own directory.

## 6. The measurement

None. A tooling change with no runtime effect.

## 7. Risks

**A finding hidden because its line was not touched.** Real, and the reason the
scoping is off in the explicit-file mode that `dev/varka_gate.sh` and a manual
audit use. CI's linters remain unscoped and authoritative; this hook is the cheap
hint, and a hint that cries wolf is not cheap.

**A self-test that commits into the repository it is checking.** This one
happened rather than being foreseen. A pre-commit hook is run by `git commit`
with `GIT_DIR` and `GIT_INDEX_FILE` exported, and every nested git command
inherits them - so the fixture repository's `git init`, `git add` and `git
commit` operated on the real repository instead: they created a commit named
`old` on the task branch, carrying the staged changes and the fixture file, and
the outer commit then failed with `cannot lock ref 'HEAD'`. Nothing was pushed
and `git reset --mixed` restored it. The fix is to run every command inside the
fixture through `env -u GIT_DIR -u GIT_WORK_TREE -u GIT_INDEX_FILE ...`,
including the nested invocation of this script, whose own
`git rev-parse --show-toplevel` would otherwise answer with the outer
repository. The `HEAD` assertion is there so that a future edit that reintroduces
it fails loudly instead of committing something.

**The diff parse.** `git diff -U0` hunk headers are parsed for `+start,count`,
with the count defaulting to 1 when absent. A misparse would silently scope
everything out, which is what the "a long line this commit adds" case exists to
catch.

## 8. Sequencing

Independent of every other open task. Not stacked on anything.

## 9. Outcome

The merge commit that started this reports nothing, and the `CLAUDE.md`
correction in this task commits without `--no-verify`. The self-test passes and
fails in the four ways it is supposed to.
