# Task 211: The CI queue keeps a verdict across a docs-only change

## 1. Where this came from

The owner asked on 25 September 2026 why the day's Varka pull requests were
taking so long to pass CI. The fork's run history answered. The queue runs one
Build at a time (task 176), a full Build takes about two hours, and seven
merges that day each sent a merge of master into every other open PR, so every
one of them lost its Build and needed another: the day's run list held more
than twenty cancelled Builds. Most of those merges brought only plans and
docs, which a Build does not test. The owner chose this fix from three
offered: the queue keeps a passed verdict when the head has moved by docs
only.

## 2. The admission check, done

The rule needs a way to tell what changed between the head a run tested and
the head the PR has now, without a clone of the fork's branch. GitHub's
compare API gives it: `compare/<tested>...<head>` lists the files and says
whether the head is `ahead` of the tested commit (a descendant) or has
`diverged` (a force push). Checked on three real pairs of 25 September:

| Pair | Files | Verdict |
| :-- | :-- | :-- |
| #390's plans-only push, eec181bfa52 to 98bb9e2ed86 | `sql/varka/plans/PLAN_TASK_210.md` | docs only |
| #391's benchmark push, 47273fb7500 to a345ec9ebdb | a Scala file and a plan | code |
| #388's merge of master, 35bea33f068^ to 35bea33f068 | two Scala test files among eight | code |

The third is the case that decides the design. A merge of master shows as
master's own new commits, so a merge that brought a test file (here #389's
and #390's) still reruns, and one that brought plans alone does not. The check
would have rejected a rule keyed on the PR's own commits, which cannot see what
a merge brought in.

## 3. The design

### 3.1 The rule, in the script

`dev/varka_ci_queue.sh` gains `passed_run_covering <fork> <branch> <head>`: it
reads the branch's ten newest Build runs and, for each that passed, answers
`exact` if it ran at the head, or `docs-only` if the compare from its head to
the current one is `ahead`, under the API's 300-file cut, and every file
matches `VARKA_CI_DOCS_ONLY`, a regex whose default names the plans, skills
and papers under `sql/varka`, any Markdown, and committed benchmark results.
Three commands use it:

* `hold` after a push: when a passed run covers the new head by docs only, the
  push's run is cancelled and the PR is not queued.
* `run` at a PR's turn, before anything else: a covering run means "ready to
  merge" and the PR leaves the queue, the push's own waiting run cancelled.
  And after a run finishes green while the PR was held again, the new head is
  checked the same way, so a docs-only push during a Build does not send the
  PR to the back of the queue.
* `status` says which passed run covers a head that has moved by docs only.

### 3.2 What is deliberately unchanged

The one-Build-at-a-time rule, the queue file, the wait on run status, the
deadline, and the `EXIT` line on every path (task 176). A force push never
qualifies: the compare is not `ahead`. Workflow files, `dev/` scripts and
anything under `src` are code, whatever they change.

### 3.3 Registered op counts

Not applicable; a shell tool.

## 4. Files

| file | what |
|---|---|
| `dev/varka_ci_queue.sh` | `passed_run_covering`, `newest_run` with a count, the rule in `hold`, `run` and `status`, the header paragraph |
| `sql/varka/skills/working-in-this-repo.md` | why a day of Varka CI runs long, from the day's run list |
| `sql/varka/plans/PLAN_MILESTONE_6.md` | row 211 |

## 5. Tests, and what each is for

* `bash -n` on the script, and the regex over nine paths: plans, skills,
  papers, Markdown at the root and under `docs/`, a results file, against a
  Scala source, a workflow file and a `dev/` script.
* `passed_run_covering` on live branches: a branch whose run is in progress
  returns nothing; a branch whose run passed at its head returns `exact`.
* The docs-only branch of the function rests on the compare API's answers in
  section 2; the next docs-only push after a green Build exercises it live,
  and `status` shows the verdict before `run` acts on it.

## 6. The measurement

None in the benchmark sense. The saving is a two-hour Build per docs-only
push after a green one, which the run list of the next merge day will show
as cancelled runs that are not followed by reruns.

## 7. Risks

1. A path the regex calls docs that a Build does test. The default is a short
   allowlist, and `dev/`, workflows and sources are outside it by
   construction; a new documentation location is added to the regex, not
   inferred.
2. A compare cut at 300 files reads as not docs only, never the reverse.
3. The rule trusts a passed run whose head is an ancestor; a rerun of an older
   run after a force push cannot qualify, since the compare has `diverged`.

## 8. Sequencing

One commit: the script, the lesson, the plan and the row.

## 9. Outcome

Built 25 September 2026. The first live use is the next docs-only push after a
green Build; this file records it when it happens.
