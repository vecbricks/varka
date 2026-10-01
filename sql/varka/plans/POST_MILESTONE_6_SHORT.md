# The short post: the trailer for milestone 6's second long read

*Drafted 1 October 2026 with the first draft of `POST_MILESTONE_6.md` (`PLAN_TASK_181.md` 14). It
lives in its own file for the reason milestone 5's does: `dev/varka_post_page.py` publishes
everything in the post's file, and a trailer there would appear on the page.*

## What it is for

The long read is for people who work on Spark's code generator, so the trailer is for them too:
it names the mechanism in two sentences, gives two numbers from the post, and points at the
link. Like milestone 5's, it is written in the first person and kept to what the post measured.
Every number in it is the post's, and the post's are from committed results files.

## The draft

> Spark's code generator writes Java. The JVM counts bytecode. Between the two sits a guess.
>
> Spark splits its generated methods by the length of their source, because, in its own
> documentation's words, "we cannot know how many bytecode will be generated". Most of the time
> the guess is fine. When a projection, a CASE WHEN or a filter grows wide enough, one method
> passes 8000 bytes, HotSpot never compiles it, and every row runs in the interpreter: five times
> slower on a wide projection, a hundred and sixty times on a filter of date ranges.
>
> I have been building Varka, a research fork of Apache Spark that emits its kernels as bytecode
> with JDK 25's Class-File API. It does not guess. It measures every method of the class it
> built, and splits or declines before anything runs. A hundred date expressions in one
> projection run a hundred times faster than stock Spark on a 512-bit runner, and 22 times
> faster than Spark tuned to step around the cliff.
>
> The second post of the pair is about how: every place Spark's code generation gives up, the
> class Varka emits instead, what the first query costs, and where it stands beside Comet, Gluten
> and an interpreted vector engine.
>
> https://vecbricks.github.io/under-8000-bytes-by-construction/

**Image:** Figure 1 of the post, the hundred expressions as one method of Spark's and as Varka's
class, rendered to a 1200x630 PNG as the page's link card.

**Alt text:** "A hand-drawn bar chart in bytes, with a dashed line at 8000. Spark's one generated
method for a hundred expressions is a single bar of 17,132 bytes, past the line. Varka's class
for the same expressions is a hundred narrow bars, four for each of 25 groups, none above 3,856
bytes, and two short bars for the drivers that call them."

## Mechanics

* **The link's place is the owner's call.** Milestone 5's file set the next post's arm as the
  link in the first comment rather than in the body, to be read from GitHub's traffic API by
  referrals; the first post of this pair does not record which arm it ran. Take a traffic
  snapshot with `dev/varka_traffic_snapshot.sh` before posting and another a week after.
* **The first post links this one.** Its closing sentence gets this post's link on the day this
  one is published (`PLAN_TASK_181.md` 8), so a reader arriving at either finds both.

## What it leaves out

The census's entry numbers, the accelerators' numbers, the first-query cost and every caveat:
they are one click away in the long read, which is where a reader who wants them will look.
