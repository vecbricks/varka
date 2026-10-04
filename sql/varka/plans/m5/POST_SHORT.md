# The short post: the trailer for milestone 5's long read

*Drafted 23 September 2026. It lives in its own file rather than at the end of
`m5/POST.md`, because `dev/varka_post_page.py` publishes everything in
that file and a draft trailer was appearing on the page as if it were part of
the piece.*

## What it is for

The previous post (17 August 2026) put the whole argument in the post itself:
one dense paragraph, a link-preview card, no image. It reached about 15.5k
impressions and 81 reactions, and converted roughly 150 clicks into 16 stars -
a healthy star rate among visitors and about a 1% click-through. The reach was
never the problem. So this one is a trailer, on the owner's instruction kept to
performance alone and written to sound like a person rather than a release
note: one allocation, two rates, one ratio, an invitation, and the argument at
the other end of the link.

## The draft

*Written in the first person and meant to sound like one, because a trailer
that reads as a specification gives nobody a reason to follow it. The numbers
are unchanged; only the voice is.*

> Spark builds a `LocalTime` object every time it reads the hour out of a TIME
> value. One object, per row.
>
> It is the right call for a row-at-a-time engine - `java.time` is the proper
> library and `getHour()` is the proper method. It just costs three divisions
> and an allocation to get one number back out. On my laptop that is 16.3
> nanoseconds a row.
>
> I have been building Varka, a research fork of Apache Spark that compiles a
> whole projection into a single vector loop: bytecode emitted with JDK 25's
> Class-File API, running over Arrow columns through the Vector API and
> Panama. The same `hour(t)` turns into one 64-bit division, eight rows at a
> time. 1.0 nanosecond a row, and nothing allocated.
>
> Chain a few TIME expressions together the way a real query does, and it comes
> out 31.8x faster than stock Spark 4.2 on a machine with a true 512-bit
> datapath. That run happened on GitHub Actions, so you can dispatch the same
> workflow and check it yourself - the CPU and the datapath probe's own reading
> are written into the results file.
>
> I have written the whole thing up: how the loop is built, where every number
> comes from, and ten hand-drawn diagrams (drawn, if I am honest, by a
> script that pretends to be hand-drawn). If this is your kind of thing, I
> think you will enjoy it.
>
> https://vecbricks.github.io/eight-rows-per-instruction/

**Image:** figure 2, the `LocalTime` per row against the one division
(`card.png` beside the published page, 1200x630).

**Alt text:** "A hand-drawn diagram. One TIME value, 52 349 000 000 000
nanoseconds since midnight, takes two paths: stock Spark builds a LocalTime
object with four fields and reads one of them, 16.3 nanoseconds a row; Varka
divides by 3 600 000 000 000, eight lanes at a time, 1.0 nanosecond a row."

## Published

23 September 2026:
<https://www.linkedin.com/posts/maxgekk_spark-builds-a-localtime-object-every-time-share-7508422005449322497-Szne/>

The arm this post runs is **the link in the body**, so the next one puts it in
the first comment and the referral counts decide. LinkedIn rendered it as a
link-preview card rather than as a native image - which is what happens when
the body carries a URL - so `card.png` is what a reader sees, at 1200x630,
through the page's own Open Graph tags. That is a better outcome than the
August post's bare card, but it is not the native-image arm, and the mechanics
below still describe what that arm would be.

## Mechanics, and the one thing to test

* **Post the image, not a link preview.** The previous post was a link card,
  which is the format that reaches fewest people and gives a reader the whole
  story without clicking.
* **The link is in the post above, and that is the arm to test.** Whether
  LinkedIn suppresses outbound links enough to justify moving it to the first
  comment is folklore in both directions; this project has two posts and can
  settle it with an A/B rather than an opinion. Put the link in the post this
  time, and in the first comment next time, and read GitHub's traffic API for
  referrals rather than guessing from impressions.
* **Snapshot the traffic first.** Done: `dev/varka_traffic_snapshot.sh` writes
  one capture into `sql/varka/traffic/`, and the baseline taken before this
  post is `2026-09-23T070301Z.json` - 2675 views and 303 uniques over the
  fourteen days the API keeps, 1214 clones, 17 stars, and 159 LinkedIn
  referrals still on the books from the August post. Take another a week
  after, and compare **referrals** rather than views, which move with
  anything.
* **Same day, not the same hour, on the other channels.** A Show HN, r/java
  (the JDK 25 and Vector API angle is the draw there and nobody has told them)
  and r/apachespark reach engineers who do not read LinkedIn, and the
  reproduction recipe is what makes such a post survive scrutiny.

## What this post deliberately leaves out

Everything that is not a performance claim. The JVM-versus-native argument,
which the August post made and which is a post of its own. The emitter's
design, the fallback contract, and the coverage table. The finding that the
same twelve expressions read about 4x on a machine without AVX-512, because the
division lowering changes - it is the most interesting thing the milestone
measured and it is one scroll away in the long read, which is where a reader
who wants the caveat will look for it. And any question to the reader: the
post asks for a click and nothing else.
