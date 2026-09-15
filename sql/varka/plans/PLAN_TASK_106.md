# Task 106: the quote check runs in CI

*Milestone 5, section 2.41. Opened 15 September 2026 while closing task 94;
started the same day.*

## 1. Where this came from

"Every number the documents quote traces to a committed results file" is a house
rule (`sql/varka/AGENTS.md`, "Measurements, not adjectives"), and
`dev/varka_quote_check.py` is its guard: it reads the Varka documents, finds every
figure shaped like a benchmark rate, and looks for each in the committed results
files and their git history. `dev/varka_toc.py --check` is the smaller sibling
that keeps `SKILLS.md`'s generated index in step with the lesson files.

Neither runs in any workflow. The pre-commit script runs both when a Markdown
file is staged, so the guarantee rests on whoever ran it locally: PR #208 (task
99) merged its numbers on the strength of one local run and said so in its
description. A documentation pull request from a machine without the hook lands
an unbacked number and nothing objects.

Task 94 built the pattern for a small gated job - a module entry in
`dev/sparktestsupport/modules.py` and a job in `build_and_test.yml` that runs
only when that module's files change. This task is that pattern over the
documents.

## 2. The admission check, done

**What the documents map to today is wrong three different ways.** Asking
`determine_modules_for_files` about each document the quote check reads:

    README.md                              ->  []        (ignored: bare README.md)
    SKILLS.md                              ->  []        (ignored: bare SKILLS.md)
    sql/varka/AGENTS.md                    ->  []        (ignored: bare AGENTS.md)
    sql/varka/plans/PLAN_TASK_106.md       ->  []        (ignored: /sql/varka/plans/)
    docs/sql-varka.md                      ->  ['docs']
    sql/varka/skills/working-in-this-repo.md -> ['root']
    sql/varka/VISION.md                    ->  ['root']
    dev/varka_quote_check.py               ->  ['root']
    dev/varka_quote_allowlist.txt          ->  ['root']

So a plan-only or README-only pull request runs *no* job at all, which is why
the quote check could not be running anywhere for those; a lesson-file-only or
`VISION.md`-only pull request falls through to `root` and runs the entire matrix
(PR #212, the prior-art paragraph, ran everything to add two paragraphs to
`VISION.md`); and `docs/sql-varka.md` runs the documentation build, which is
right, and nothing else. The ignore list entry for `/sql/varka/plans/` was the
fork's earlier fix for the second failure and is the cause of the first: it
stops the matrix by stopping everything.

One module entry fixes all three, the way task 94's did for the bench sources: a
`varka-docs` module claims the documents, so they map to it and to nothing
else, and its job is the one thing a documentation pull request runs.

**The checks are cheap and need nothing installed.** Both scripts import only the
standard library; on master today, from a warm disk:

    dev/varka_quote_check.py                                    1.5 s, 0 orphans
    dev/varka_toc.py --check SKILLS.md --from sql/varka/skills  0.03 s

A JDK, Maven, sbt and the assembly are all beside the point. The job is a
checkout and two commands.

**The checkout is the one decision, and the obvious one is wrong.** Every other
job checks out through `.github/actions/checkout-and-sync`: apache/spark at a
pinned ref, with the fork branch *squash-merged* on top as one commit, so the
build tests the combined tree. The quote check accepts a number that appears in
any committed version of a results file - plans legitimately quote the figure a
change moved away from - and reads that history with `git log -p --full-history`
over the results directories. On the synced tree that history is upstream's, in
which no Varka results file has ever existed, plus one squash commit. Simulated
by exporting the tree into a fresh repository with a single commit and running
the check there:

    610 orphan(s) not in the allowlist (102 allowed)

Six hundred and ten of the documents' quotes trace only to history, and on the
tree every other job uses the job would be red from its first run for a reason
that has nothing to do with the documents. So this job checks out the branch
itself, `actions/checkout` with `fetch-depth: 0`, and never syncs with upstream.
That is also the right tree on the merits: the documents are the fork's, and
upstream's tree has no bearing on whether they quote the fork's numbers.

**The job will run on its own pull request, and that is not the proof of the
gate.** Task 94 learned this: a change under `.github/` counts as `root` when
`is-changed.py` runs in Actions, so every job is required on a workflow-editing
pull request, this one included. That gives the job's first run. The gate - that
a documents-only pull request runs this job and nothing else - is proven by the
first such pull request after this merges, which the milestone has several of
queued.

## 3. The design

### 3.1 The mechanism

Four edits.

1. **`dev/sparktestsupport/modules.py`** gains a `varka_docs` module, name
   `varka-docs`, no dependencies and no sbt goals, whose source regexes are the
   documents the two checks read plus the checks themselves: root `README.md`
   and `SKILLS.md`, `docs/sql-varka.md`, every `sql/varka/*.md`,
   `sql/varka/plans/`, `sql/varka/skills/`, and under `dev/` the quote check,
   its allowlist and the index tool. The regexes are anchored by `re.match`
   at the path's start, so `README\.md$` is the root file and not every README
   in the tree.

   The same tuple is consulted first by `is_ignored_file`: a path the docs module
   claims is never ignored, whatever the bare patterns say. Without that, the
   bare `README.md`, `SKILLS.md` and `AGENTS.md` patterns would drop those files
   before module matching ever ran. The `/sql/varka/plans/` ignore entry goes,
   because the module now claims plans; `/sql/varka/papers/` stays, because no
   check reads the papers and nothing should run for them.

2. **`dev/varka_quote_check.py`**'s document list gains the four Varka documents
   the module claims and the check did not read - `VISION.md`,
   `ADDING_AN_EXPRESSION.md`, `ISSUES.md`, `Varka_MVP.md` - so "the job checks
   what the module claims" is true without a footnote. Run over the four today
   they add zero orphans, so this widens the guard at no cost to the ratchet.

3. **`.github/workflows/build_and_test.yml`**: a `varka_docs=` line beside
   `varka_bench=` in the precondition, a `"varka-docs"` key in the emitted JSON,
   and the job.

4. **The job**: `actions/checkout@v6` with `fetch-depth: 0` (section 2 says why
   not `checkout-and-sync`), then the two commands, each as its own named step so
   a red run says which rule was broken. `ubuntu-latest`, ten-minute timeout,
   no Java, no caches.

### 3.2 What is deliberately unchanged

The pre-commit script keeps running both checks locally; this task is the
machine that does not forget, not a replacement for the one a person runs. The
other `dev/varka_*` tools stay unclaimed (`root`), as do `sql/varka/coverage.json`
and the papers: none of them is read by these checks, and claiming files a job
does not test would make the gate lie in the other direction. `docs/sql-varka.md`
keeps its `docs` mapping too - a file can belong to two modules, and the
documentation build is a real check on it.

### 3.3 Registered op counts

Not applicable.

## 4. Files

* `dev/sparktestsupport/modules.py` - the module, the ignore exception, the
  doctests.
* `dev/varka_quote_check.py` - four more documents.
* `.github/workflows/build_and_test.yml` - the precondition line, the JSON key,
  the job.

## 5. Tests, and what each is for

CI configuration, so the tests are direct queries of the mapping, run before
CI is asked:

* each of the nine paths in section 2 maps to `['varka-docs']` (plus `docs` for
  `docs/sql-varka.md`), and none to `root` or to `[]`;
* `python/README.md` is still ignored and `sql/varka/engine/...` still maps to
  `varka-engine` and `sql/varka/bench/...` to `varka-bench`, so the new regexes
  have not widened over their neighbours;
* `determine_modules_to_test` over a plan file returns `varka-docs` alone;
* `modules.py`'s doctests pass with the flipped expectation for a plan path;
* the workflow parses as YAML with the new job present and gated on the new key;
* both commands exit zero on the branch, so the job is known to pass.

## 6. The measurement

None. No committed number moves.

### 6.1 Predictions, registered before the run

1. The job runs on this task's own pull request (the `.github` rule) and passes
   in **under two minutes**, checkout included.
2. The first documents-only pull request after this merges shows exactly one
   job required beyond the always-on ones: `Varka docs checks`, with `Varka
   bench drivers` and the matrix skipped. That closes task 94's open half as
   well.
3. Widening the quote check to the four documents adds zero orphans (measured
   in section 2; the prediction is that it stays zero after the plan files of
   this task are added, since this plan quotes no rate).

## 7. Risks

* **The ignore exception ordering.** If the docs regexes were consulted after
  the bare patterns, `README.md` and `SKILLS.md` would never reach the module.
  The doctests pin the order by asserting the root files are not ignored while
  `python/README.md` still is.
* **A regex reaching a neighbour.** `sql/varka/[^/]+\.md$` cannot descend into
  `engine/` or `bench/`; section 5 asserts it.
* **The quote check on a branch whose history is shallow.** `fetch-depth: 0` is
  the whole fix; if a future edit "optimises" it away the job goes red with
  hundreds of orphans, which is loud rather than silent.
* **A results-file number that exists only in upstream's history.** None can:
  results files live under paths upstream does not have.

## 8. Sequencing

1. This plan. 2. The module, the exception, the doctests, the mapping checked.
3. The quote check's list. 4. The workflow. 5. Both commands run locally.
6. The row; section 9 after the job's first run.

## 9. Outcome

*To be written from the run.*
