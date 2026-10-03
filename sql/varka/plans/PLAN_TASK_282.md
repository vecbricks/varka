# Task 282: Close milestone 6

*Scoped 3 October 2026 (milestone 6 section 2.13, row 282), on the owner's
decision that every milestone ends with a task of this shape.*

## 1. The question

A milestone has ended when its last row read **Done** and its post was
published. Nothing marked the commit, nothing summarised the milestone in one
place, and what the work left behind on the machine and in the repository
stayed: on 3 October 2026, with milestone 6 one row from its end, a survey
found forty worktrees under the project directory, seventy-one local branches
and about a thousand on the fork, three worktrees on branches already merged,
no tag of any Varka milestone (the only tags are Spark's release tags) and no
changelog. The documents had drifted too: the README's Contributing section
still named milestone 5 as the one in flight and its milestone 6 bullet linked
the milestone 7 scope instead of the milestone 6 plan, and `VISION.md` named
milestone 5 as current in its header and in its section on the plans.

This task closes milestone 6 and is the first instance of the rule recorded in
`CONTRIBUTING.md` under "Finding work": every milestone ends with a closing
task, the last row of its table, that does this housekeeping. Milestone 7's
plan carries its own.

## 2. The change

Nothing in the engine moves. The task has six steps, in this order, because
the tag must sit on the commit where every row is closed and the changelog
entry quotes the tag.

1. **The rows.** Every row of `PLAN_MILESTONE_6.md` 3 but 236 and this one
   reads **Done** or **Moved** today. Row 236 is marked **Done** by its own
   step 3 (the runner run scored, `planSize` and `predictGrouping` defaulted
   by `PLAN_TASK_236.md` 3.6); this task waits for it and does not do it.
2. **The documents.** `PLAN_MILESTONE_6.md` 3 is read row by row against
   master, and 1.3 item by item, in the way the halfway review of 9.1 did.
   Every document that names the milestone in flight is corrected: the README
   (its status list and its Contributing section), `CONTRIBUTING.md`'s "as
   this is written" pointer, `VISION.md`'s header and its section on the
   plans, `SKILLS.md` and the skills files where they say what milestone 6 is
   doing. `dev/varka_issues.py --apply` brings the mirror to the table. The
   close itself is recorded as the plan's last section: the date, the tag,
   the posts, the rows that moved to milestone 7 and why, and which
   `PLAN_TASK` files milestone 7 still reads against which are records only.
3. **The lessons.** What this milestone's last weeks cost benchmark time goes
   to the skills files with a pointer from here: a full-load night drains the
   laptop's battery through its charger and `power-profiles-daemon` demotes
   the profile to `balanced`, which a regeneration then records; an
   sbt-forked benchmark JVM shows as `java @argfile` and a kill or a wait that
   matches the class name misses it; a regeneration script's exit code is not
   evidence that every section of the file was written; `SparkFunSuite` fails
   a test past twenty minutes unless `spark.test.timeout` says otherwise.
4. **The oracles.** `sql/varka/emitted_bytes.json` and
   `sql/varka/emit_cost_audit.json` are regenerated on the closing commit and
   expected unchanged; a difference is a finding and becomes a row before the
   tag is cut.
5. **The changelog.** `CHANGELOG.md` is created at the repository root with
   one section per milestone, newest first: the milestone's headline, a few
   lines per theme written from the plan's **Done** rows, the tag, and links
   to the plan and the posts. Milestones 1 to 5 get short entries from their
   plans; milestone 6 gets the full one. From here on a milestone's entry is
   written by its closing task.
6. **The tag and the cleanup.** An annotated tag `varka-m6` on the commit
   that closes the last row, its message the changelog entry's headline and
   the posts' addresses, pushed to `vecbricks/varka`; no GitHub release,
   since Varka ships inside Spark and has no artifact of its own. Then the
   worktrees and local branches of merged or closed pull requests are
   removed, judged against the pull request list and not git ancestry
   because the merges squash; the fork's branches of merged or closed pull
   requests are deleted after a dry run that prints the count; the worktrees
   of open pull requests stay.

Two alternatives were considered and set aside. A GitHub release per
milestone: a landing page is easy to add later and nothing consumes it today.
A changelog line added by every pull request: it would conflict on every
merge, and the milestone's **Done** rows already hold the material.

## 3. Predictions, registered before the run

1. The document check of step 2 finds the four stale pointers section 1
   names and fewer than ten in all.
2. The oracle and the audit of step 4 are byte-identical to master's.
3. `dev/varka_issues.py` closes exactly two issues, 236's and then this
   task's own, and changes no other.
4. The fork's cleanup deletes more than nine hundred branches, and the local
   branches that remain are `master` and those of the open pull requests.

## 4. Verification

<!-- Filled as the steps run. -->

## 5. Outcome

<!-- Filled when the tag is pushed. -->

## 6. Explicitly out of this task

* Row 236's step 3 and its defaults, which `PLAN_TASK_236.md` owns; this task
  starts its tag only after that row reads **Done**.
* The SPARK-33301 follow-up tickets upstream, which the owner files.
* Promoting `SCOPE_MILESTONE_7.md` to the milestone 7 plan and pointing the
  README at it, which #558 does when it merges.
