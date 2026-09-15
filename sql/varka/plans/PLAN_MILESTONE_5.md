# Varka Milestone 5 Plan: 64-bit lanes and TIME

**Re-scoped on 15 September 2026**, after its first four tasks (94, 97, 99, 100)
merged, to one lane and the types that share it. The owner's brief: "focus on
64-bit lanes and the related types: Long (int64), time and day-time intervals",
take "some infrastructure related tasks", and "close the gaps in already supported
data types". The milestone ends the way milestone 4 did, in a public message - that
Varka supports the new `TIME` data type, which Apache Spark is about to enable by
default, and that operations over it are much faster with Varka on. That message
is what "done" means here: a committed, banded benchmark of `TIME` expressions
against stock Spark with `spark.sql.timeType.enabled=true` on both arms, and
coverage-table rows for what is supported.

Fourteen open rows had no bearing on that and moved to `SCOPE_MILESTONE_6.md`
item 15, text and task numbers unchanged, on the 4 and 11 September precedents:
the int32 performance work (25, 64, 72, 73), the calendar algorithms (49, 65,
66), the validity-algebra optimisations (74, 75), strings (80), two
micro-optimisations (82, 87) and the read-back floor (98); boolean outputs (27)
went with them as the one borderline call, recorded in 1.1.

Eleven rows were added. 102 to 106 (sections 2.37 to 2.41) are the `TIME`
expressions, the day-time interval expressions, `Long` arithmetic, the `TIME`
benchmark and the quote check in CI; 116 (2.51) is the proof that a `TIME`
column survives Varka's Arrow cache, which comes before any lane code; and 117
(2.52) is the sync of the fork with `apache/spark` master, which it trails by 375
commits and which the owner made the milestone's first task; and 118 (2.53),
the closing task in task 62's shape - the final benchmarks on a proven
full-width runner, the README, and the post - last by definition; and, from the
review of what the plan assumed, 119 (2.54, the long-lane oracle), 120 (2.55,
the coverage table as a differential corpus) and 121 (2.56, the `TIME` surface
under `-XX:UseAVX=2`, the timing 2.19 owes). Task 29 was widened
rather than replaced: `TIME` nanoseconds, day-time interval microseconds,
timestamp microseconds and `bigint` are one lane.

Rows 107 to 115 were opened the same day for `TIME` features vanilla Spark lacks,
surveyed against the upstream umbrella
[SPARK-57550](https://issues.apache.org/jira/browse/SPARK-57550), and then
**withdrawn from the milestone** on the owner's decision that it takes only what
Varka needs - vectorised expressions and benchmarking - and not vanilla Spark's
row-engine work. Their numbers stay, their sections are one-line stubs, and
section 8 keeps the survey with its tickets, because a gap Varka meets later
starts from that record. Section 1.1 has the order; the sections below it are as they were,
with a note under each moved heading so citations still resolve.


Milestone 4 opened as *breadth* - the engine learning the types, expressions
and loop schedules a query contains - and grew, task by task, into the date
family and the emitter under it. On 4 September 2026 the owner re-scoped it to
exactly that: milestone 4 is `DateType` plus the emitter and evaluator
infrastructure, and every task whose subject is another lane or output type
moved here, unchanged. This file is those tasks. It is a task plan, not a scope
catalogue: each task below was designed in milestone 4's plan, several with
measurements already committed, and they keep the numbers they were given
there. The coverage milestone that used to be called milestone 5 - decimals,
strings as keys, grouped aggregation, published benchmark numbers - is now
milestone 6, and its scope document is `SCOPE_MILESTONE_6.md`.

## 1. What moved, and why this order

*The list below is the 4 September 2026 framing, when this milestone was "the
other lanes"; section 1.1 supersedes its order and its scope as of 15 September.
It is kept because it records where each row came from in milestone 4.*

Six tasks, in the dependency order milestone 4's plan already gave them:

* **27, boolean outputs** - comparisons and connectives as projection results.
  Independent of the rest; the one pure continuation of milestone 3's mask
  machinery. Its `toVector`-against-`blend` measurement is committed.
* **28, lane-width conversion** - the width machinery 29, 30 and 39 lean on.
  Its loop-shape measurement (narrowest-drive against part loops) is
  committed and decided: narrowest-drive.
* **29, int64 lanes** - `TimestampNTZ`, `bigint`, `LongType` and `TimestampType`
  comparisons and differences: the second `LaneType`.
* **30, ANSI-correct integer arithmetic** - `try_*` first, the throw path
  second, `Multiply` overflow through 28's widening; the blend-then-`DIV`
  mechanism for the masked epilogue's zero-safety invariant is pre-measured.
* **39, `date - date`** - the first mixed-width kernel, an int32 input pair and
  an int64 output; depends on 28 and 29. Its recipe (`PLAN_TASK_39.md`) was
  written against machinery that does not exist yet and says so.
* **49, exact civil-from-days in long lanes** - depends on 29; its admission
  check is committed (`verify_long_lane_magic.py`) and its gate registered.
* **65, Joffe's `fast32` civil-from-days in int lanes** - added 5 September
  2026 (section 2.7): the int32-lane alternative to 49, admitted or declined
  by a sweep before any emitter change. Independent of 29.
* **66, second-level chrono fragments** - added 5 September 2026 (section
  2.8): the calendar tails' shared parts (year and leap flag, January month,
  month start) factored the way task 32's prefix was, behind the same
  fragment mechanism. Pays only inside one lane group, so it follows task
  32's step B2 grouping decision, which it does not change.
* **74, the validity-word algebra's missing axioms** - added 7 September
  2026 (section 2.9) from the owner's question, over task 70's PR, of what
  the word algebra is mathematically and which of its laws the emitter does
  not yet use. Two it does not: the coalesce axiom and absorption. Not a
  lane, but an emitter follow-up the owner placed in this milestone so that
  milestone 4 can close.
* **75, zero-copy validity for leaf words** - added the same day (section
  2.10): an output whose word *is* an input's bitmap shares the buffer
  instead of copying it. Small and bounded by a committed number - and the
  number moved under it when task 70 was regenerated a third time, to 0.4%,
  below this task's own decline line, so read 2.10 before starting it.
* **81, Spark's own date tests as a differential corpus** - added the same day
  (section 2.11), from the owner's question about estimating test coverage:
  every oracle this project checks itself against, it also wrote, so the
  expressions Spark's own suites exercise are run over Arrow-cached fixtures in
  both engines with the plan classified fused, partial or declined. The
  golden-file inputs are the first source, once each statement's literals are
  rewritten into columns - without that they constant-fold and reach no
  kernel. Follows task 74 and nothing else.

What stays true from milestone 4's plan and is not repeated here: the three
invariants (one lane width per kernel, every value lane-shaped, no lane reads
its neighbour), the standing gates in `PLAN_MILESTONE_4.md` section 5, and the
debt register there, which these tasks keep sweeping. Cross-references inside
the moved text were repointed: "2.5's committed results file" now reads 2.1,
the catalogue's "see 2.x" pointers follow the new section numbers, and the
old milestone 5 is named milestone 6 where the text meant the coverage
milestone.

### 1.1 The re-scope of 15 September 2026: the spine, and what left it

Five facts, checked against the tree on the day, decided the shape:

* `TIME` is a long. `TimeType(precision)` is eight bytes of nanoseconds since
  midnight, range 0 to 86 399 999 999 999, physical type `PhysicalLongType` like
  `bigint`, `TimestampType`, `TimestampNTZType` and `DayTimeIntervalType`. One
  lane serves all of them. It is `@Unstable` since 4.1.0 and gated by
  `spark.sql.timeType.enabled`, whose default is `Utils.isTesting` - off in
  production, which is the "about to enable by default" window the message is
  aimed at.
* The stock 4.2.0 distribution the benchmarks compare against ships the type
  *and* every `TIME` function this milestone lowers (`HoursOfTime`, `MakeTime`,
  `TimeTrunc`, `TimeAddInterval`, `SubtractTimes`, `TimeDiff`, and the
  `TimeFrom*`/`TimeTo*` pairs are all in its catalyst jar), so the baseline arm
  exists with the flag turned on.
* Arrow can carry it: `ArrowUtils.isSupportedByArrow` admits `TimeType`
  (`Time64`, precision in field metadata, [SPARK-57661](https://issues.apache.org/jira/browse/SPARK-57661)) and
  `DayTimeIntervalType` (`Duration(MICROSECOND)`), and
  `ArrowCachedBatchSerializer`'s column-stats switch has a `LongColumnStats` arm
  for each. *An earlier draft of this section said it had neither; that read
  stopped one line short, and the correction is recorded here rather than
  erased.* What nothing has done is cache a `TIME` column through Varka's
  serializer path and map its buffer as eight-byte lanes, so that proof is a
  task (116) rather than a formality.
* The emitter's `LaneType` enum has one member, `INT`. Everything above sits
  behind widening it, which is task 85, which its own row says follows 84.
* Long lanes have no division. The Vector API has no 64-bit divide, and a
  64-bit magic multiply needs a 128-bit product it does not have. But
  nanoseconds-of-day lie below 2^47, and a floor division through doubles is
  exact well past that: the true quotient's fractional part is either 0 or at
  least `1/d`, the computed quotient errs by at most `v * 2^-53` under a true
  division (twice that under 2.19's reciprocal multiply), so `floor` is safe
  while `v < 2^53` - `2^52` in 2.19's form - and `TIME`'s `v` is under both by
  a factor of thirty. That is why task 88's double-lane division stops being a
  curiosity and becomes the way `hour(t)`, `minute(t)`, `time_trunc` and the
  interval extracts are computed at all. For day-time interval microseconds,
  which span the whole int64, the same bound is a real one - `2^52`
  microseconds is about 52 000 days - and above it the division is the recorded
  decline task 29's row already anticipated as "range-narrowed constants or a
  recorded decline". Fitting a double exactly is necessary and not sufficient;
  the error bound is the argument, and 2.19's admission check owes the constant.

**The spine, in dependency order:** 117 first (the sync with `apache/spark`
master, so everything below is built on the tree the message is about) -> 84
(the lattice) -> 85 (the lane parameter)
-> 29 (the long lane, widened) with 28 (width conversion) and 92 beside it - 92
because `VarkaEmitOptions.DEFAULTS` has `validityByWord = false` today, so at the
four lanes a 256-bit long vector holds, the emitter takes the byte
read-modify-write that task 47 measured as a 6 to 9% loss against the word
write; `wordWrites` already accepts four lanes mechanically (`64 % lanes == 0`),
so 92 is the `lanes < 8` default 2.23 specifies and nothing structural -> 88 (division) -> 102 (`TIME` expressions), 103 (day-time interval
expressions, absorbing 39), 104 (`Long` arithmetic, task 30's int64 half) ->
105 (the `TIME` benchmark) -> 101 (the band, required before any per-entry
number is quoted) -> 121 (the AVX2 arm of the `TIME` surface) -> 118 (the closing task: the
final measurement on a proven full-width runner, the README, and the post -
task 62's shape, last by definition). Two testing rows sit beside the spine
rather than on it: 119, the long-lane oracle, lands with 29; 120, the coverage
table as a differential corpus, starts today and grows with 102 to 104. Gaps in the supported types - 95, 96, 89, and 83/86 folded in
as 85 touches them - run alongside; 81 extends to `time.sql` and `interval.sql`
once the first `TIME` expression fuses; 106 (the quote check in CI) can go any
time.

**The vanilla gaps (107 to 115) - surveyed, then withdrawn.** The owner
first asked that `TIME` features vanilla Spark lacks become rows here, then that
they be checked against the upstream umbrella [SPARK-57550](https://issues.apache.org/jira/browse/SPARK-57550) ("Extend
support for the TIME data type", 58 subtasks), and then - with the survey in hand
- that the milestone keep only what Varka needs: vectorised expressions and
benchmarking. So the rows were withdrawn the same day - their sections cut to
one-line stubs - and what the survey found is kept in section 8 with a ticket per
line, because it is the record a later gap starts from. Two of its findings matter to the rows that stay. **The fork
carries nearly every resolved subtask of the umbrella, and one it does not.** Of
the 42 resolved tickets, 36 trace to commits in both histories (three of them
inside other tickets' commits - 51403 under 57587, 57552 and 57554 under 57551);
[SPARK-53368](https://issues.apache.org/jira/browse/SPARK-53368), reading Parquet `TIME` written with `isAdjustedToUTC`, is
in `upstream/master` and **not** in the fork; and five (52617, 53520, 57553,
57559, 53579) leave no commit message in either history - 57559's feature is
present anyway through `TimeTypeOps`, and the other four are checked by feature
in 116 rather than by ticket. So the expressions 102 lowers are the ones
vanilla Spark has today, and task 117 carries one piece of `TIME` work after
all. *An earlier draft of this paragraph said the fork carried every resolved
subtask; that rested on a title-prefix grep that passed silently for tickets
absent from both histories, and the correction stands here.* And two readings made while opening those rows
were wrong and are corrected in place rather than erased: Avro `TIME` is
implemented ([SPARK-54473](https://issues.apache.org/jira/browse/SPARK-54473), [SPARK-57581](https://issues.apache.org/jira/browse/SPARK-57581), under `sql/core`'s
Avro code rather than `connector/avro`, where the grep looked), and the
Arrow-cache stats switch has its `TimeType` and `DayTimeIntervalType` arms - the
read that said otherwise stopped one line short. One row came back from the
withdrawal because it is Varka's own: 117, the sync with upstream, which the
owner then made the milestone's first task. 116, the cache proof, was never
among them - it is Varka's path and nobody else's.

**Boolean outputs (27) is the borderline call.** `SELECT t1 < t2 AS flag` is a
projection output, distinct from filters, which already run through masks.
Nothing in the message needs it; it is also the first thing a `TIME` user might
type. It moved out, and a `TIME` benchmark row is what argues it back in.

Section 2 carries the six design sections as milestone 4 wrote them, section 3
the task table, sections 4 to 8 what milestone 4's files, verification, risks,
open questions and exclusions said about these tasks, and section 9 the scope
catalogue items about other lanes, item numbers preserved because other plans
cite them.

## 2. Design

### 2.1 Boolean outputs (task 27, item 5)

*Moved to `SCOPE_MILESTONE_6.md` item 15 on 15 September 2026, when the milestone was re-scoped to 64-bit lanes and `TIME`: the one borderline call: a projection output the `TIME` message does not need, argued back in by a benchmark row if one wants it. The text below is unchanged and the heading stays so citations of this section number still resolve.*

*Moved from `PLAN_MILESTONE_4.md` section 2.5 on 4 September 2026, text
unchanged except for the cross-references noted in section 1.*

The cheapest item and the only pure continuation of milestone 3: comparisons
and `And`/`Or`/`Not` as projection *results*, built on task 21's mask-as-value
machinery. `VectorMask.toVector` against a `blend` of one and zero was
pre-registered as a measurement, not a debate, and it is now measured
(`VarkaMilestone4MeasurementsBenchmark`, committed forked-JVM run in
`sql/varka/engine/benchmarks/VarkaMilestone4MeasurementsBenchmark-jdk25-results.txt`,
which superseded four in-process runs; their reading is in git history and
the file lists what moved): `toVector` is ahead by 1.12x at AVX-512 and
`blend` by 1.04x at 128-bit - a small, width-dependent gap where the
in-process runs had reported a tie, and not the clear winner the
pre-registration expected either way. The real,
width-dependent finding is a different question the pre-registration did not
ask: whether to materialize an int column at all. Skipping it - packing
`VectorMask.toLong()` straight into the output bitmap - wins by 1.18x at
AVX-512 but *loses* by 1.34x-1.39x at 128-bit. A compound predicate, `(a > b)
AND (c < d)` kept in mask space the whole way through versus materialized as
an int column at every node, shows the same direction with a smaller margin
than the in-process runs claimed: mask-space is ahead at both widths, by 1.02x
at AVX-512 and 1.07x at 128-bit - never worse; the 1.24x-1.37x the earlier
runs reported at 128-bit was the harness. Two consequences for the task: walk
boolean sub-expressions in mask space and materialize only once at the output
boundary (never worse, at either width), and the single-comparison bits-only
shortcut needs a width check rather than a single
committed choice, since its sign flips between the two vector widths this
project already tests at. The two real questions the pre-registration also
named are format and nulls: Spark's bit-packed boolean vector against
Arrow's validity-style bitmap at the output boundary, and the three-valued
rules holding there exactly as they hold in the interior - a null input
produces a null output, never a false one. The differential runs every null
pattern for exactly that reason.

*Added 7 September 2026, from section 2.9's reading of task 70's word
algebra.* A boolean output is where that algebra's domain ends, and the task
has to say so in code. Task 70's bitmap pass serves a root whose validity is
a pure function of the inputs' validity bitmaps, which holds for every date
node because each is either strict (null in, null out) or null-skipping.
SQL's connectives are neither: `a AND b` is *false*, not null, when one side
is false and the other null, so the validity of a boolean output depends on
the operands' values as well as their bitmaps - Kleene's three-valued
lattice, not the bitmap lattice. `Analysis.pureOf` falls to `null` for any
node without an arm, so a boolean root is fail-safe today by omission; task
27 makes it fail-safe by statement: a test that every boolean root - a
comparison, `And`, `Or`, `Not` over nullable operands - has no pure word and
is never served, and a sentence in the emitter's algebra javadoc naming the
boundary. Mask space, where 2.1 already keeps the connectives, is the right
place for the known-true and known-false pair the connectives need.

### 2.2 Lane-width conversion (task 28, item 1)

*Moved from `PLAN_MILESTONE_4.md` section 2.6 on 4 September 2026, text
unchanged except for the cross-references noted in section 1.*

The width machinery items 2 and 4 lean on. The hard part is not the
conversion, it is the lane count: at one shape an int32 species holds twice
the lanes of an int64 species, so a mixed-width kernel either drives the loop
at the narrowest lane count and leaves wide lanes half empty, or emits a part
loop per conversion and carries two trip counts. That is the one decision in
this item that is expensive to reverse, so the scope's open question 2 was
pre-registered as a measurement before the task opens: both shapes on a
`cast(int AS long) + long` chain. Measured
(`VarkaMilestone4MeasurementsBenchmark`, same committed results file as 2.1):
narrowest-drive and part-loop are statistically tied at both vector widths,
on every run - four total - narrowest-drive slightly ahead most of the time
(within 1.01x-1.07x, inside this file's own noise band). Part-loop's extra
bookkeeping - two trip counts, two stores per int chunk - buys nothing
measured, so task 28 opens already knowing the winner: narrowest-drive, for
the simpler build (one trip count) at the same throughput. The recorded
fallback if a wider mixed-type shape measures differently once task 28 is
under way: items 2 and the multiply half of 4 can be built width-locked and
retrofitted.

### 2.3 int64 lanes: `TimestampNTZ` and `bigint` (task 29, item 2)

*Widened on 15 September 2026: the long lane this section designs also carries
`TIME` (nanoseconds of day) and `DayTimeIntervalType` (microseconds), which are
`PhysicalLongType` like the two types named in the heading. The expressions over
those two types are 2.37 and 2.38; what this section owns is the lane itself -
the species, the loads and stores, the halved headroom, and the division rule -
and its "range-narrowed magic constants for 1000000 and 86400 or a recorded
decline" is now decided by 2.19's double-lane division (exact for `TIME` by
range, bounded for intervals). The heading stays so citations resolve.*

*Moved from `PLAN_MILESTONE_4.md` section 2.7 on 4 September 2026, text
unchanged except for the cross-references noted in section 1.*

The first new lane type, and the natural one: the only type whose semantics
are already written down (milestone 2 section 2.6 quality, for dates) and
whose expressions Varka already compiles at another width. `TimestampNTZType`
is pure int64 microseconds; comparisons, differences and literal arithmetic
come with it, plus comparisons and diffs on `TimestampType` and `LongType`
columns generally. Zoned day and month arithmetic stays out until its
semantics are written down with the same care - the tzdata-as-interval-arrays
technique is recorded in the catalogue for that day.

`LongVector` halves the lanes, so every parity gate reruns at both widths and
the same expression has roughly half the headroom it had at int32 - a number
to commit, not a surprise to discover. Micros-to-second and second-to-day are
divisions by invariant constants (1000000, 86400); there is no multiply-high
on long lanes, so the range-narrowed magic multiply is the first thing to try
(the parity file prices `DIV` at roughly an eighth of the magic rate, 652
against 5657 M rows/s on the `dayofweek` case). This task also lands the field
differential mode task 22 explicitly left to it, because this is where the
correctness surface widens.

### 2.4 ANSI-correct integer arithmetic (task 30, item 4)

*Narrowed on 4 September 2026:* the int32 add, subtract, multiply and negate, in both
evaluation modes and the `try_*` forms, are milestone 4's task 63 (`PLAN_MILESTONE_4.md`
2.30); this section's design carries over to it, and what remains here is division,
remainder and the int64 forms.

*Moved from `PLAN_MILESTONE_4.md` section 2.8 on 4 September 2026, text
unchanged except for the cross-references noted in section 1.*

Most arithmetic in most queries, and the `datediff(d2, d1) + 1` shape that
keeps appearing in date work. The order inside the task is the risk order:

* **`try_add`, `try_subtract`, `try_multiply` first.** They want nulls, not
  throws: the wrap-versus-saturate difference mask *is* the output validity,
  no branch needed. If the ANSI path prices badly, `try_*` alone still ships.
* **The ANSI throw path second**: compute the wrapping op and the saturating
  op over the same inputs, `compare(NE, ..).anyTrue()` decides whether to
  leave the vector loop, and a scalar re-walk of the offending lane group
  raises the error against the right row - the ghost-fallback discipline the
  project already runs on. On the no-overflow path that is one vector op and
  one well-predicted branch, and the prediction to register is that this
  prices acceptably.
* **`Multiply` overflow rides task 28's widening** - there is no saturating
  multiply, so detection widens to long lanes and compares against the
  narrowed result. It lands only if 28's machinery makes it cheap.

`date_add` stays exempt: it wraps by spec. The validation is a kind of
assertion the suites have never made: an error-*identity* differential - the
same `SparkException` as the row engine, attributed to the same row.

One obligation task 24 left at this task's door, sharpened by its review: the
masked epilogue's invariant that **no operation in the walk may trap on `0`**
(inactive lanes read `0` from a masked load) currently lives only in the
emitter's class doc, and division is the first node that will violate it. This
task must not just remember the paragraph - it should make the invariant
structural when the first trapping node lands: an explicit zero-safety member on
the sealed `VarkaVectorIR` (no default), so a node that can trap does not
compile until the epilogue emitter blends a safe divisor or takes the masked
lanewise form. A prose invariant fails only on unaligned batch lengths, which
task 24 measured as the lengths no committed harness ever runs.

Which of those two mechanisms the enforcement should reach for is
pre-measured (`VarkaMilestone4MeasurementsBenchmark`, same committed results
file as 2.1): blend-then-`DIV` beats masked `DIV` at both vector widths, on
every run - four total - 1.08x-1.10x at AVX-512, 1.18x-1.19x at 128-bit by
minimum. The smallest margin of the five measurements in that file, but the
only one where all eight data points (two widths times four runs) agree in
both direction and rough magnitude, which is the interleaved comparison the
under-1.3x rule asks for. Blend a safe divisor into inactive lanes; the
structural check exists to make sure some such mechanism runs before an
unmasked `DIV`, not to leave the choice open each time.

### 2.5 `date - date`, the first mixed-width kernel (task 39)

*Moved from `PLAN_MILESTONE_4.md` section 2.13 on 4 September 2026, text
unchanged except for the cross-references noted in section 1.*

The natural first consumer of tasks 28 and 29, and a better one than the
synthetic `cast(int AS long) + long` chain their measurement used: int32 inputs,
an int64 output, exactly one width conversion, one output, and an error path.
The smallest real expression with that shape.

It is not `datediff`, which Varka already compiles and which returns an
`IntegerType` day count. Since Spark 3.2 the `-` operator between two dates
returns `DayTimeIntervalType(DAY)` - physically **long microseconds** - as
`Math.multiplyExact(Math.subtractExact(l, r), MICROS_PER_DAY)`. Two facts about
that line shape the task: it throws unconditionally, not only under ANSI, since
`SubtractDates` carries no `failOnError`; and the legacy
`CalendarIntervalType` variant behind `spark.sql.legacy.interval.enabled` is a
different result type that must decline.

**The finding that made this worth writing down now is that it does not need
task 30.** A lane cannot throw, but it does not have to: task 26 built the
channel where a kernel notices what it cannot compute, returns a status, and
the row engine recomputes the batch - and the row engine then raises the
identical exception at the identical row, because it *is* the row engine. Both
overflow tests are cheap and branchless (`((l ^ r) & (l ^ diff)) < 0` for the
subtraction, and a comparison against `Long.MAX_VALUE / MICROS_PER_DAY =
106751991` for the multiply), and overflow needs a date range of 292,000 years,
so the fallback costs nothing anyone will measure. Task 30 exists for
expressions where declining is too expensive; this is not one, and the recipe
says so rather than reaching for machinery because it is there.

The recipe is the first written **against machinery that does not exist yet**,
so it names tasks 28's and 29's plumbing provisionally and tells the executing
agent to stop and report if the real thing differs rather than adapt on the
fly. The gap between what it assumed and what 28 and 29 actually build is the
most useful thing its outcome section can record - and it is a cheap trial of
whether a recipe can usefully be written ahead of its dependencies at all.

### 2.6 Exact civil-from-days in long lanes (task 49)

*Moved to `SCOPE_MILESTONE_6.md` item 15 on 15 September 2026, when the milestone was re-scoped to 64-bit lanes and `TIME`: date-algorithm precision; it uses long lanes but serves dates. The text below is unchanged and the heading stays so citations of this section number still resolve.*

*Moved from `PLAN_MILESTONE_4.md` section 2.19 on 4 September 2026, text
unchanged except for the cross-references noted in section 1.*

Task 26's whole design rests on one absence: `VectorOperators` has no
multiply-high on any lane type, so a full-range Granlund-Montgomery magic
division is not expressible on int lanes, and what ships instead is a
*range-narrowed* round-down magic with correction carries, a narrow-range guard,
a batch-decline path and a `VarkaChrono` constant table to support it. That
absence was re-checked during task 32 and is not temporary: no `MUL_HIGH` in
JDK 25 or in openjdk/jdk master, and JDK-8219881, the nearest request, has been
Open at P4 since February 2019 on `repo-panama` (`SKILLS.md` has the detail).

**But multiply-high was never the only route to an exact magic. A 64-bit low
product is enough, and `LongVector`'s `MUL` provides one today.** Widen the
dividend to int64 lanes and the product of a 32-bit value and a ~30-bit magic
lands well inside a signed 64-bit lane, so the quotient is exact with a single
multiply and a shift - no round-down, no carries, no range restriction.

Checked, over the range the lowering actually needs rather than a round number.
Days are int32 and the March-based bias makes the dividend
`w = days + 2^31 + 719468`, so `w` spans `[0, 2^32 + 719468)`:

| division | dividend range | k | M | largest product |
|---|---|---|---|---|
| `/146097` | `[0, 2^32 + 719468)` | 47 | 963315389 | 2^61 |
| `/36524` | `[0, 2^24)` | 38 | 7525953 | 2^46 |
| `/365` | `[0, 2^24)` | 31 | 5883517 | 2^46 |

Three bits of headroom on the widest one, and none to spare beyond it: the same
search over `[0, 2^33)` finds no exact pair at all. So the margin is real but
thin, and the admission check is not a formality.

That table is reproducible rather than asserted:
`sql/varka/plans/verify_long_lane_magic.py` searches for each pair, checks it at
every multiple-of-`d` boundary in range - which is where an inexact magic must
first disagree, the error being monotone between them - and fails loudly if the
`[0, 2^33)` search unexpectedly succeeds, since that would mean this section
understates the headroom. It is committed for the same reason
`verify_chrono_tails.py` and `verify_days_from_civil.py` are.

**What it deletes.** The narrow-range guard and its two compares; both
round-down magics and their correction carries; `STATUS_CHRONO_RANGE` as a
reason a chrono batch declines, with the evaluator fallback and metric that
serve it; the `NARROWED` variant and the range constants in `VarkaChrono`; and
the standing caveat that `year(date_add(d, n))` can decline for a large enough
`n`. The status ABI itself stays - task 30's ANSI path wants its own bit - but
the calendar family stops being a reason a batch is recomputed on the row
engine.

**Update: the guard half of this is already gone (task 51).** Before this task
was picked up, the owner had the emitter's per-extraction guard removed for a
different reason - it re-verified a fact CSE and task 32's fragment sharing had
usually already established, on every calendar node, when the one case that
actually needs a fresh check is a value a *producer* node manufactured from
unbounded runtime arithmetic (`date_add`/`date_sub` with a column offset, not a
literal). `PLAN_TASK_51.md` and `PLAN_TASK_52.md` have the detail; task 52 is
where the check returns, at the producer, not the extraction. So by the time
task 49 is picked up, `emitEra` no longer carries the two compares or the
`s.guardAcc` wiring, `hasChrono` is gone, and `STATUS_CHRONO_RANGE` already goes
unset - what remains for *this* task to delete is the round-down magics and
their carries, the `NARROWED` variant, and `VarkaChrono`'s range constants,
plus reconciling with whatever task 52 has done to the producer nodes by then
(an exact lowering needs no range check for the calendar extraction itself, but
task 52's producer-side check is about the query's arithmetic, not the
extraction, and stays relevant regardless of which lowering reads its output).

**What it costs.** Half the lanes: eight per vector at AVX-512 instead of
sixteen, four instead of eight at 128-bit. Plus an `I2L` on the way in and an
`L2I` per output on the way out. Counting ops out of what `emitChronoPrefix`
would become, this is roughly 25-28 ops over eight lanes against today's ~45
over sixteen - about 3.2 against 2.8 ops per row before conversions - so the
honest expectation is a **small throughput loss bought with a large
simplification**, not a win. That is a legitimate trade and it is the owner's
call, but it has to be made on a number.

**Sequencing.** Depends on task 29, which brings int64 lanes and the second
`LaneType`; there is no cheap way to prototype this before it lands, and no
reason to try. It is also an *alternative* to task 32's step B rather than a
complement: the fragment mechanism is lane-type agnostic and would compose
mechanically, but the two wins overlap, since a long-lane prefix is a different
prefix to share. Whichever lands second inherits the smaller half, and the
milestone should not pretend otherwise.

**The gate, and it is the strict one.** Task 26 verified its narrowed lowering
against `LocalDate` over all 16,777,216 days of its range and its total variant
against a long-arithmetic reference over **all 2^32 days**, as an opt-in
committed test, on the grounds that a vector kernel at sixteen lanes makes that
seconds rather than hours. This lowering claims exactness over a wider range
than either, on a three-bit margin, so it inherits that standard and not a
smaller one: the sweep is commit 1, before any emitter change, and the
boundary set gains `2^31 - 1`, `-2^31`, and both ends of the biased dividend.

**Predictions, registered here.** The lowering lands at 25-30 emitted ops; it
runs 0.75x to 1.0x the shipped narrowed lowering on `year` at AVX-512 and
relatively better at 128-bit, where halving an already-small lane count costs
less than the corrections it removes; and no committed number for a non-calendar
shape moves. If it clears 1.0x anywhere, that is a surprise worth writing down
rather than a result to assume.

**Declined if** the sweep finds any day where the exact form disagrees, or the
measured cost at AVX-512 is worse than 0.75x - at which point the simplification
is not worth a quarter of the calendar family's throughput, and the entry goes
to the debt register with the number attached.

### 2.7 Joffe's `fast32` civil-from-days in int lanes (task 65)

*Moved to `SCOPE_MILESTONE_6.md` item 15 on 15 September 2026, when the milestone was re-scoped to 64-bit lanes and `TIME`: a calendar algorithm for the int32 date path. The text below is unchanged and the heading stays so citations of this section number still resolve.*

*Added 5 September 2026, from a reading of the Habr translation of Ben Joffe's
"fast-date-64" post and of the `benjoffe_fast32_v2.hpp` (2026) and
`benjoffe_fast32_v1_wide.hpp` files in `benjoffe/fast-date-benchmarks`, on the
owner's request. The same repository was read in September 2026 for task 54
(`SKILLS.md`, "The Julian map"); what follows is what that review did not
cover, because the `fast32_v2` file postdates it.*

**What the prefix already took from this source.** The Julian map (task 54,
+25% on `year` at both widths) and the month numerator whose low half is the
day (task 53). What the earlier review set aside was the rest of `fast64`: it
reads the *fractional* part of the year division - the low word of a 64x64
product - as the year-part, and folds the leap day into `(yrs % 4) * 512`, so
the month/day split never computes a day of year at all. That is four
multiplies for the whole date against Neri-Schneider's seven, and the review
filed it under task 49's long lanes because every multiply reads a high half.

**What is new: `fast32_v2`.** Joffe's own 32-bit rewrite of the same chain
("based on the 64-bit algorithm, but using smaller constants throughout,
avoiding umulh"), backwards-counting, with the year-part read off the low word
of a 32x32 product and the month/day split as `m_num = (yrs & 3) * 64 + shift +
ypt`, `month = m_num >> 8`, `day = ((m_num & 255) * DAY_MUL) >>> 32`. His
option A is exact from -284,449-07-13 to +284,449-01-30, wider than the
narrowed prefix's range by an order of magnitude; the scalar measurement puts
it at 1.18-1.38x Neri-Schneider's time against `fast64`'s 1.00x, on three
machines. The `fast32_v1_wide` file is the bucket technique the task 54 review
already recorded as the guard-free fallback (full int32 range, 100% overflow
safe, at more ops).

**Why it is not a port.** Each of his multiplies is still a 32x32->64 product
read from the high half (`>> 47`, `>> 32`): scalar-friendly, but the Vector
API has no multiply-high on any lane, which is the absence task 49 works
around by halving the lanes. So the transfer to int lanes is what tasks 53 and
54 did by hand - re-derive each stage as a low-32-bit magic with its own exact
range - and it is not obvious that every stage survives it: the year-part is
*defined* as a high-half fraction, and the `(yrs & 3)` absorption of the leap
day depends on the year-part's scale. The two ideas that transfer without that
question are the backwards count (no `+ 3` alignment terms and one subtraction
off the critical path) and the split's shape, in which the month and the day
come out of one add and one shift.

**Why it may be worth it.** The prefix is latency-bound on its dependent chain
(task 54's lesson: count stages, not ops), and this chain is one stage shorter
than the prefix's - no day of year before the month/day split - with one fewer
correction. Registered expectation: 5-15% on the prefix at both widths if the
low-product derivation holds over at least the narrowed range, and a wider
covered range as the second prize, which would shrink what task 52's producer
guard has to protect. Against that: the numerator of task 53 already gives
month and day from one multiply, so part of the gain may already be banked.

**The admission check, before any emitter change.** As for task 49, a sweep
first, committed as a script beside `verify_long_lane_magic.py`:

1. Transcribe the two files' algorithm text and constants into
   `sql/varka/papers` under the BSL-1.0 notice they carry, with the reading
   notes; they are code, not a paper, so the notes are the load-bearing part.
2. Derive, for each stage, a low-32-bit magic (round-down plus at most one
   carry, as `emitChronoPrefix` does today) and its exact range, and sweep the
   whole chain against `LocalDate` over the union of the derived ranges. The
   gate is the narrowed range at minimum
   (`VarkaChrono.NARROW_MIN_DAYS..NARROW_MAX_DAYS`); a wider exact range is
   recorded, a narrower one declines the task.
3. Count the dependent stages of the surviving chain against the prefix's.
   If it is not shorter, decline: the op count alone did not predict task 54.

**If admitted:** a `VarkaEmitOptions` variant and an A/B in
`VarkaEmitterParityBenchmark` beside the task 53 and 54 pairs, at both widths;
the register and the `HugeMethodLimit` ladder re-pinned, since every prefix
change moves them; the default chosen from the committed numbers.

**Relation to task 49.** An alternative, not a complement, in the same sense
2.6 gives for task 32's step B: both shorten the prefix, and whichever lands
second inherits the smaller half. This one needs no int64 lanes and can run
before task 29; if it admits and measures well, task 49's own expectation
(0.75x-1.0x) gets harder to justify on throughput and stands on the
simplification alone.

**Declined if** step 2's exact range is narrower than today's, or step 3 finds
no shorter chain, or the A/B is under 1.0x at either width; the numbers go to
the debt register either way.

### 2.8 Second-level chrono fragments (task 66)

*Moved to `SCOPE_MILESTONE_6.md` item 15 on 15 September 2026, when the milestone was re-scoped to 64-bit lanes and `TIME`: a calendar refactor for the int32 date path. The text below is unchanged and the heading stays so citations of this section number still resolve.*

*Added 5 September 2026, from the owner's question over the IR data-flow
drawing (`docs/img/varka/varka-ir-levels.svg`): where the prefixes are, and
whether there are only two.*

**What exists.** One shared fragment kind: `FragmentKind.CHRONO_PREFIX`, task
32's step B1 - the civil-from-days decomposition run once per distinct date
per lane group into eight locals that every `Chrono` tail and `AddMonths` read.
The mod-7 lowering is not a fragment; `emitFloorMod7` is emitted inside each
node that needs it. And the calendar tails are in the same state: their
helpers are factored in the Java source (task 35 and task 61 did it for
`trunc`) but every node re-emits them against the prefix's locals.

**The register of repeated tails**, read off `VarkaLoopEmitter` on the task 61
branch as call sites of each helper, each site a node that recomputes the same
value when it sits beside another consumer over the same date:

| shared value | helper | emitted by |
|---|---|---|
| plain year, then the leap flag, then the January day of year | `emitChronoYear` (6 sites), `emitLeapFlag` (4), `emitJanuaryDayOfYear` (2) | `Year`; `DayOfYear`; `TruncDate` YEAR and QUARTER; `TruncDateDynamic`; `LastDay` and `AddMonths` (the leap flag only) |
| January-based month | `emitChronoMonth` (6 sites) | `Month`; `Quarter`; `TruncDate` QUARTER; `TruncDateDynamic`; `AddMonths`; the recompose `trunc` form |
| month start, zero-based day of month | `emitMonthStart` (6 sites), `emitZeroBasedDayOfMonth` (2) | `DayOfMonth`; `TruncDate` MONTH; `TruncDateDynamic`; `LastDay`; `AddMonths` |
| `floorMod(d, 7)` over the same date | `emitFloorMod7` (4 sites) | `DayOfWeek`; `WeekDay`; `TruncDateDynamic`'s week result. Not `NextDay`, whose mod is over `k - d`; the week-rule tasks (37, 57, 58) add consumers through their Thursday shift, which node-level CSE already shares |

So `year(d), dayofyear(d), trunc(d, 'YEAR')` runs the year-and-leap chain three
times, `month(d), quarter(d)` the month step twice, and `dayofweek(d),
weekday(d)` the twelve-op mod twice.

**The design is the existing one, one level down.** Three or four more
`FragmentKind`s (`YEAR_PARTS`, `JANUARY_MONTH`, `MONTH_START`, `FLOOR_MOD_7`),
keyed by the same decomposed child, body mode and lane group as the prefix
(`fragmentKey`), allocated once per fragment in `planSlots` and emitted once
per lane group by the first consumer (`emittedFragments`), the later consumers
reading the locals. The prefix's slot discipline carries over unchanged: a
fragment writes only its own locals and never a sibling's, which is the lesson
`PLAN_TASK_36.md` recorded after doing it the other way first. The tails'
helpers already take their inputs and outputs as slot numbers, so the change is
in the planning and the once-per-group check, not in the arithmetic. The
`elideChronoMonth` question repeats one level down: a fragment is emitted only
if some consumer in the group reads it, decided over the group as
`fragmentsReadingMonth` decides the month step today.

**What it is worth, honestly.** A tail is 4-12 ops against the prefix's ~30,
so this pays only when three or more fields of one date sit in one lane group -
which is exactly the shape task 32's step B2 makes common by relaxing
`GROUP_BUDGET` for calendar outputs, and no other. Registered expectation:
10-25% on the four-field shared row, under 5% on any two-field one, nothing on a
single-field query. The register test's counts move for every shared shape and
the `HugeMethodLimit` ladder may move again (every prefix change has), so both
are re-pinned as fixtures, not as findings.

**Sequencing.** After B2's grouping decision is in (`PLAN_TASK_32.md` 7.2 says
its gate cleared; the default is a policy the owner sets from both widths'
numbers). Independent of tasks 29 and 65; if 65 replaces the prefix, the
year-parts fragment changes shape but not its existence.

**The gate.** The four-field parity row shared under the new fragments against
the same row under B1 alone, both widths, three runs, minimum best-time, on the
shape `year(d), dayofyear(d), trunc(d, 'YEAR'), month(d)` and on the committed
`year+month+day+quarter` row. Under 1.05x at AVX-512 on both: decline, and the
register above goes to the debt register as the record of what re-emission
costs.

**Beyond dates.** The next first-level prefixes are milestone 5's timestamp
work, where sharing will matter more than here because every field pays the
split first: micros to days plus micros-of-day (under `date(ts)`, the calendar
tails and the time-of-day tails), seconds-of-day under `hour`/`minute`/`second`,
and the per-timestamp zone offset once item 2's tzdata design lands. They are
recorded in section 9's item 2 as the shape to design the fragment keys for,
not as tasks.

### 2.9 The validity-word algebra's missing axioms (task 74)

*Moved to `SCOPE_MILESTONE_6.md` item 15 on 15 September 2026, when the milestone was re-scoped to 64-bit lanes and `TIME`: an optimisation of the existing validity pass. The text below is unchanged and the heading stays so citations of this section number still resolve.*

*Added 7 September 2026. The owner, reading task 70's PR (#145), asked what
the word algebra is from a mathematical point of view and whether known laws
of that structure could be applied to more complex expressions. This section
is the answer, with the census that turned it into a task.*

**What the algebra is.** Task 70's `WordExpr` is the `{AND, OR, 1}`-reduct
of a finite Boolean algebra: the validity bitmaps of a batch are the direct
power of the two-element Boolean algebra over the batch's rows, and the
emitter uses only the two lattice operations and the top element. For each
operator alone that is a bounded semilattice - associative, commutative,
idempotent, with `1` as AND's unit and OR's annihilator - and the free
bounded semilattice on the input ordinals is exactly the set of leaves plus
the operator that `BitmapPass` stores as a served root's normal form. The
pass's soundness (`PLAN_TASK_70.md` 3.1) is the fact that a direct power's
operations are componentwise, so evaluating a word per lane group and
concatenating equals evaluating it over the whole batch. Two laws of the
lattice the folding does not use: **absorption** (`x AND (x OR y) = x` and
its dual) and the fact that `coalesce`, which the compiler builds as
`IfElse(IsNotNull(x), x, y)`, denotes `x OR y` - the compiler's own comment
on `compileCoalesce` proves this (`(kT AND v(x)) OR (NOT kT AND v(y))` with
`kT = v(x)` reduces to `v(x) OR v(y)`), and `pureOf` still returns nothing
for it because `IfElse` has no arm.

**The census, and a caveat found on 12 September 2026.** Its `surface` corpus is
not the whole surface: `resolve` had no analyzer pass, so every date/interval
shape task 67 added parsed to an `Add` the compiler declined, and the corpus was
truncated by hand to "the `Surface` projections that resolve without the
analyzer's type coercion". The figures below are therefore over about two thirds
of the surface while reading as though they were over all of it. The resolver is
fixed and the interval columns are declared, so widening the corpus is now a
matter of adding the entries and requoting this paragraph - work for whichever of
tasks 74 and 75 next touches the census, since it moves every number here.

`VarkaWordCensus` (catalyst test scope,
`dev/varka_word_census.sh`) classifies every value root of three corpora by
its word today and under the two extensions, and checks its verdict against
the emitter's by emitting each single-root shape with the pass on and off:
a served root changes `loopMasked0`'s bytes. Run on 7 September 2026 over
the `Surface` projections, sixteen composites on the operator boundary, and
20000 shapes from the fuzzer's value grammar (seed 7): 6686 single-root
shapes cross-checked, 0 disagreements. What it found:

| corpus | leaf | chain | no expression | mixed |
|---|---|---|---|---|
| `Surface` projections (30) | 22 | 5 | 3 (`if`, `CASE`, `coalesce`) | 0 |
| fuzzer grammar (39742 roots) | 25584 | 6405 | 6183 | 1570 |

* **The coalesce axiom** turns `coalesce(d, d2)` - the one `Surface` entry
  with no expression that is not a comparison blend - into an OR chain, and
  with it `coalesce(d, d2, d3)` (a chain of three), `year(coalesce(d, d2))`
  and `greatest(coalesce(d, d2), d3)`. In the fuzzer's grammar it gives a
  word to 1674 of the 6183 roots that have none; the rest are comparison
  blends and `make_date`, which are not pure and stay so.
* **Absorption** turns `datediff(greatest(d, d2), d)` and
  `greatest(date_add(d, i), d)` from mixed trees into a single leaf, and
  `datediff(coalesce(d, d2), d)` likewise once the coalesce axiom is in. In
  the grammar it resolves 1014 of the 1570 mixed roots. No `Surface` entry is
  mixed, so on the inventory alone this law is worth nothing; it is two
  rewrite rules, and it is what makes the coalesce axiom compose.
* **What stays mixed** after both: 556 of 39742 grammar roots, 1.4%, and
  composites such as `datediff(greatest(d, d2), greatest(d3, d4))` and
  `date_add(greatest(d, d2), i)`. Their Strahler numbers are 2 or 3 in all
  but two of the 40000; section 9's item 14 is the evaluator that would
  serve them, and this census is its admission threshold.

**Two things the reading found already done or not worth doing.** The unit
and annihilator laws have a run-time half - a null-free column is the top
element and drops out of an AND or decides an OR - and the engine's five
pass entry points already resolve it per batch from the null counts, before
touching a bitmap. And cross-output sharing, which the normal form makes
exact (two roots want the same bitmap iff their leaf sets and operator
agree), is not worth a mechanism: in the grammar's 13284 multi-root shapes
only 403 served roots repeat a *non-leaf* form of a sibling and 62 pairs
stand in the subset relation under one operator; a repeated leaf is already
a copy. Recorded so that nobody designs it twice.

**The design.** Two `pureOf` arms and two folding rules, no new node, no
new option component:

* `IfElse(IsNotNull(x), x, y)` denotes `or(w(x), w(y))`, matched
  structurally (the condition's child is the then-branch, by reference).
  `WordOwner` stays `Own` for the node, since the blend still computes its
  slot when a consumer wants it; Theorem 1's one-directional check therefore
  constrains nothing new. Liveness already demands both operand words for
  the blend's known-true mask, so serving the root removes its write and
  nothing else.
* `andExpr(a, Or(p, q))` with `p == a` or `q == a` folds to `a`, and the
  dual in `orExpr`. `andRef`, the slot-level folding, is deliberately left
  without absorption: the emitter may stay more conservative than the
  algebra (the plan's Theorem 1), and the agreement assertion already
  allows an `Own` owner under any expression.

Behind no switch of its own: both are extensions of `validityByBitmap`'s
rule and ride its option. The census tool loses its mirror: task 74's first
commit exposes the emitter's own classification through a test hook and
re-runs the census through it, which is also the test that the mirror was
right.

**A third arm, held as a question.** A filter whose predicate is a null
test - `WHERE d IS NOT NULL`, `IS NULL`, and their conjunctions - has a
selection mask that is a pure function of the input bitmaps too, with
complement: the `Cond` sub-algebra over `IsNotNull` leaves is the full
Boolean algebra, De Morgan gives it a negation normal form, and the driver
could write the selection with the same pass plus an and-not entry point.
It is not in this task's deliverables because the filter's masked loop for
that predicate is already a load and a store per group, and compaction, not
the mask, is where the filter's time goes. Measured before it is built:
one row in `VarkaFilterBenchmark`.

**Validation.** `coalesce(d, d2)` served: `loopMasked0` and
`epilogueMasked` byte-equal to the dense twins, the way task 70 pinned the
four-field shape; the parity benchmark gains a `coalesce(d, d2)` A/B pair
(pass on against the per-group reference arm) beside task 70's, both
widths; the differential over the nullable fixtures for `coalesce` with two
and three operands, `datediff(greatest(d, d2), d)` and
`greatest(date_add(d, i), d)`, values byte-identical to the row engine over
every null pattern; the fuzzer with both extensions randomised, two million
shapes clean; the census re-run through the emitter's analysis with the
composites' verdicts unchanged. Registered expectation: `coalesce(d, d2)`
masked with mixed nulls lands on its dense row at both widths, as `year`
and the four fields did; no other committed row moves.

### 2.10 Zero-copy validity for leaf words (task 75)

*Moved to `SCOPE_MILESTONE_6.md` item 15 on 15 September 2026, when the milestone was re-scoped to 64-bit lanes and `TIME`: an optimisation below its own decline line. The text below is unchanged and the heading stays so citations of this section number still resolve.*

*Added 7 September 2026, from the same reading.*

**The observation.** After task 70, 22 of the 30 `Surface` projections have
a leaf word: the output's validity *is* one input's bitmap, and the driver's
pass is a copy of it - `copyColumnValidity`, `(rows + 7) / 8` bytes per
output per batch. Arrow lets a vector share a buffer instead: the fork's
`ArrowCachedBatchSerializer` already hands vectors buffers it does not own,
and `BaseFixedWidthVector.loadFieldBuffers` retains a foreign validity buffer
through its `ReferenceManager` when the null count is strictly between zero
and the row count (checked against `arrow-vector` 19.0.0's bytecode: the
retain at the end of `BitVectorHelper.loadValidityBuffer`; the all-valid and
all-null cases allocate a constant buffer instead). So an output with a leaf
word can be assembled from a fresh data buffer and the input's own validity
buffer, retained, and the copy disappears. The null count travels with it,
which removes the second thing this task is about: Arrow's `getNullCount`
is a scan of the bitmap on every call (`BitVectorHelper.getNullCount`, no
cache in 19.0.0), and the evaluator asks for it once per input per batch in
`extractMorsel` and once per compacted column in the filter - on a vector
Varka itself just wrote, whose count the pass could have kept.

**What it is worth, bounded before it is built.** *Requoted on 7 September
2026, and the requote moves the bound. This paragraph was written against
task 70's second regeneration; the review of #145 forced a third, and the
gap it rested on fell from 3.4% to 0.4%. The superseded figures are in this
file's history and in `PLAN_TASK_70.md` 9.2, which scores its predictions
against the run they came from.*

The committed parity file puts masked `year(d)` at 3446.7 M rows/s against
its dense twin at 3459.3 at AVX-512, adjacent rows in one run: a **0.4% gap**
on identical loop bytes, where the earlier run read 3.4%. At 128-bit the
masked row is 1332.5 against the dense 1331.4, which is to say ahead of it.
The four-field shape's masked row also sits above its dense row, 1703.9
against 1687.4. So the ceiling this task could recover is 0.4% on the
cheapest single-field kernel at the wide width and nothing anywhere else, and
the null-count scans are of that same order (a `popcount` per 64 rows).

**Which is below this task's own decline threshold**, stated in the next
paragraph as 2% at AVX-512. On today's committed numbers the admission check
would decline before it ran. Two things follow rather than one. The
*zero-copy validity* half is, on this evidence, already answered: the copy it
would remove is not visible in the file, and the honest outcome is a recorded
decline unless someone wants the probe for its own sake. The *cached null
count* half is untouched by that arithmetic, because the masked-against-dense
gap does not measure it at all - `getNullCount` is a bitmap scan the evaluator
pays per input per batch and again per compacted column in the filter, on
vectors Varka itself just wrote, and no row in the parity file prices it. If
this task survives, that is what it is about, and it wants its own
measurement rather than this one.

**The admission check.** In the parity harness, the masked `year(d)` row
with the destination validity pre-filled and the copy skipped, against the
row as committed, both widths, three runs, minimum best-time. Under 2% at
AVX-512: decline, and the bound above goes to the debt register as the
record. At or above: the task is the buffer sharing in `computeFused` (the
leaf case of the pass resolved to a retained input buffer, keyed off the
same `served` table the driver reads), a cached null count on
`VarkaOwnedArrowColumnVector` for every output the pass wrote, and the
filter's compaction reading it; validated by the differential over every
null pattern (a shared buffer must never be written by the kernel - the
loop's skipped write for a served root is what makes this safe, and a test
asserts the output's validity address equals the input's) and by Arrow's
allocator accounting closing to zero at task end with the retained buffers
released.

**Sequencing.** After #145 (task 70), which it reads; independent of task
74, though the two share the driver's `served` table and merge trivially in
either order.

### 2.11 Spark's own date tests as a differential corpus (task 81)

*Added 7 September 2026, from the owner's question about how test coverage
could be estimated, and his agreement with the shape the answer proposed:
"run them under both engines and assert both the answers and that Varka
actually ran".*

**Why this and not line coverage.** Every oracle this project checks itself
against, it also wrote: `VarkaReferenceEvaluator` for the fuzzer, the hand-built
matrices in the emitter suite, the fixtures in the differential suite. They are
good instruments and they share one weakness - an assumption held by the author
of the lowering is held by the author of the oracle. Spark's own date tests are
the corpus this project did not write, encoding what the engine is supposed to
do rather than what we thought it did. That is worth more than a coverage
percentage, and it is the only instrument here that can find a misreading of
Spark's semantics rather than a slip in their implementation.

**What can be reached, and what cannot.** Two kinds of date test exist upstream
and only one can meet Varka at all.

* `DateExpressionsSuite` (catalyst, 105 tests) drives Catalyst expressions
  through `checkEvaluation`. There is no plan, no physical operator and no
  columnar batch in that path, so Varka is structurally absent. Nothing to do
  here, and saying so is the point: a task that set out to "run Spark's date
  tests under Varka" would otherwise spend its first day discovering it.
* `DateFunctionsSuite` (sql/core, 68 tests) and the date parts of
  `ColumnExpressionSuite` go through SQL and the DataFrame API, so they reach a
  physical plan. They still miss Varka as written, because Varka needs an
  Arrow-backed columnar source and these build 77 DataFrames from local
  sequences and cache none of them - the same gap `SCOPE_MILESTONE_6.md`'s
  benchmark survey found in Spark's benchmarks.

**The design: harvest the corpus, not the suite.** The obvious approach -
subclass the suite with the Arrow cache serializer set - founders on the
fixtures: the data is built inline at 77 sites and never cached, so the
subclass would run every test through the row engine and pass, measuring
nothing. Making it work by rewriting every `LocalRelation` into a cached Arrow
relation is a session-extension change wide enough to alter what the rest of
the suite tests.

So the task harvests instead: extract the date expressions and SQL the upstream
tests exercise, as a committed list, and run each over the differential suite's
own Arrow-cached fixtures in both engines. `VarkaSharedSessions` already builds
the pair of sessions and sets `SPARK_CACHE_SERIALIZER`; the corpus is the new
part. A harvested entry that Varka cannot fuse is kept and marked, because the
count of what is in and what is out is itself the coverage number this task
exists to produce - and unlike a percentage it says *which* expressions.

**The golden files are the better source, and they need a rewrite step.**
`sql-tests/inputs/date.sql` and its siblings are the same semantics corpus
written as SQL text rather than Scala, which makes them far cheaper to harvest
than a suite body: the statement is already a string and the expected answer is
already committed beside it in the `.sql.out`. Seven date-family inputs carry
254 `select` statements between them.

They cannot be run under Varka as they stand, and it is worth writing down why
so that nobody tries: **the files carry no data.** In `date.sql`, 94 of the 101
statements are literal expressions - `make_date(2019, 1, 1)`, `date '2019-01-01'`
- which constant folding evaluates during optimisation, so no scan, no columnar
batch and no physical operator ever exists for the rule to rewrite. The seven
that do read a relation read `date_view`, one row of two *string* columns built
from literals. Across the seven files, 40 of 254 statements have a `FROM` at all,
and those read views of the same kind. Running them under Varka today would
exercise exactly zero kernels, and every test would pass.

So the harvest's real work is a **rewrite**: turn each statement's literal
operands into columns of an Arrow-cached fixture, so `make_date(2019, 1, 1)`
becomes `make_date(y, m, d)` over a fixture whose rows include that triple. That
rewrite is not incidental - it is precisely what moves an expression out of
constant folding and into a kernel, which is the whole reason the corpus is
worth having.

One consequence to state plainly: **the golden `.sql.out` files stop being the
oracle** once the operands become columns, because the answers change with the
data. The oracle remains the row engine over the same fixture, as everywhere
else in the differential suite. What the golden corpus contributes is the list
of expressions and edge cases Spark's own maintainers thought worth pinning -
the part this project cannot write for itself - not the expected values.

**Asserting that Varka ran is half the task.** A declined entry falls back to
the row engine and answers correctly, so a corpus run that only compares
answers passes whether or not Varka executed a single kernel. Task 62 met this
exactly and built the classification for it: `Fusion.PARTIAL` when a row-engine
`Filter` or a non-empty `Project` sits above the Varka node, and a partial shape
fails a run that asked for fusion. This task inherits that rather than
reinventing it, and the per-entry verdict - fused, partial, declined - is what
gets committed beside the answers.

**What it will not catch, stated so nobody reads more into a green run.** These
tests use a handful of rows each, so they exercise the epilogue and never a full
lane group, which is where several of this project's bugs have lived - the
fuzzer and the emitter suite's length matrices stay the instrument for that. And
the corpus is only as good as the harvest: an expression the harvest misses is
invisible, so the extraction is committed and reviewable rather than done once
by hand.

**Sequencing.** After task 74, because the fuzzer covers shape space far more
densely than 68 hand-written tests will and its one node-type gap should close
first. Independent of everything else in this milestone.

### 2.12 The mask-to-long disposal in a checked kernel (task 82)

*Moved to `SCOPE_MILESTONE_6.md` item 15 on 15 September 2026, when the milestone was re-scoped to 64-bit lanes and `TIME`: a micro-optimisation of the int32 checked path. The text below is unchanged and the heading stays so citations of this section number still resolve.*

*Added 8 September 2026, from task 63's measurement.*

**The observation.** Task 63's ANSI overflow check is five lanewise
operations, and in a dense body it costs what five operations cost: 1.4% on
`i + 1` at AVX-512 and 26.6% at 128-bit (`VarkaArithmeticBenchmark`, requoted
from `80d06a51560`). In a masked body at 128 bits the same node costs 65.8% -
6522.9 M rows/s against 19073.8 with the check off - on arithmetic that did not
change. At AVX-512 the masked cost is 3.9% - though 2.21 finds the cross-run
diff behind that reading is inside the file's noise band, and asks this task to
re-take it pinned before scoping itself on it. The first run read 19.0%, and
that turned out to be a dead local slot rather than the disposal, so this task
is a 128-bit finding and the wide width is not evidence for it. The
difference is not the sign test. It is what happens to the mask afterwards:
`emitGuardCollect` converts it to a `long` through `VectorMask.toLong`, ANDs
it with the node's validity word, and ORs the result into the batch
accumulator, once per lane group. `try_add`, which disposes of the same mask
by narrowing the word rather than accumulating it, is slower again - 3780.6
against the wrapping add's 18946.9 at 128-bit.

**Why it is worth its own task.** `emitGuardCollect` is not task 63's code.
Task 52's range guard on a column-offset day producer uses the same helper and
the same accumulator, and task 42's `make_date` year check and task 60's
month-count check reach it too. So every runtime refusal Varka emits pays this
in its masked body, and none of them has ever been priced against a version
that does not - task 52's A/B measured the guard whole, mask and disposal
together, at 10-15%, which is consistent with this but does not separate them.

**The shape of a fix, to be decided by measurement rather than here.** Three
candidates, cheapest first. Keep the accumulator as a `VectorMask` and OR the
masks lanewise, converting once per batch at `emitStatusReturn` instead of
once per lane group - which is a smaller change than it sounds, because the
accumulator is already a local. Or keep a `long` but skip the AND where the
node's word is `WORD_DEAD` or all-ones, which task 70's algebra can already
say. Or hoist the whole collect out of the group when the analysis proves the
mask empty for the batch, which is task 64's statistics-directed selection
arriving at the same place from the other side, and the reason these two want
to be read together.

**The admission check.** `VarkaArithmeticBenchmark`'s masked rows, which
exist and are committed, are the before. A candidate has to move the checked
mixed-null `i + 1` row at 128-bit by more than 10% without moving the dense
rows or any unguarded shape's bytes - and at AVX-512 there is only 3.5% to win,
so that width decides nothing here, and `VarkaEmitterParityBenchmark`'s
task 52 guard pair has to move with it or the change is not what it claims.

### 2.13 What a new node type costs, and which of it is avoidable (tasks 83 to 86)

*Added 8 September 2026, from what task 63 cost to build and what its review
found afterwards.*

**The observation, measured rather than remembered.** Task 63 added two node
types to a mature emitter, which makes it a natural experiment in where the
cost of a new type actually falls. The emitter holds **seventeen switches over
the IR: ten exhaustive, seven carrying a `default`**. Sorting task 63's own
mistakes by where the decision lived predicts what each cost almost exactly:

| where the decision lives | a missing arm | what it cost this task |
|---|---|---|
| an exhaustive switch - `childrenOf`, `analyze`, `emitValue`, `liveWords` twice, `assertWordAlgebraAgrees` | a compile error | minutes, at the keyboard |
| a switch with a `default` - `ownerOf`, `pureOf`, `planWordRef`, `chronoChild`, `emitChrono`, `tailReadsMarchMonth`, `collectColumnOffsetProducers` | a silent pessimisation: right answer, worse code | never noticed; found by reading |
| **no switch at all** - the bound analysis, and `requireDayOffsetShape` against `compileOffset` | **a wrong answer, or a ghost fallback** | a max-effort review, after the PR was open |

All three of task 63's wrong answers and its one ghost fallback came from the
last row. That is the ranking rule these four tasks are ordered by: not how
much code a refactor removes, but how far the decision it touches sits from an
exhaustive match. The rule matters twice over because the expression-porting
work is meant to be delegated to cheaper agents - the "recipe for a cheap
agent" shape tasks 33, 39 and 40 are written in, and the reason task 70 chose
the form that "keeps the emitter smaller, which the delegation goal wants"
(`PLAN_TASK_70.md` 3). For that reader the goal is not that a weak model
writes less, it is that a weak model **cannot fail quietly**; `SKILLS.md`'s
"a recipe for a cheap agent ages at the rate of the emitter" is the same
lesson from the other side.

**What is deliberately not here.** The chrono fragment machinery. Thirty-odd
lane ops behind a shared prefix is genuinely unlike every other node, and
folding it into a general scheme would cost more than it returns - the same
judgement `VarkaVectorIR`'s own javadoc already records.

### 2.14 One refusal, instead of four (task 83)

**The observation.** Four node kinds now refuse lanes at run time: task 42's
`make_date` year check, task 52's range guard on a column-offset day producer,
task 60's month-count guard, and task 63's overflow check. Each arrived with
its own analysis set - `guardedProducers`, `selfGuarding`, `checkedArith` -
its own slot rule, and its own arm in `planSlots`, and all four dispose of
their mask through the same `emitGuardCollect` into the same accumulator and
report the same `STATUS_CHRONO_RANGE`.

The accretion is visible in one predicate. `guardedWord` was written for task
52, gained a disjunct for task 60, gained a third for task 63, and its javadoc
argued that keeping it single is what stops the next kind being added to one
reader and forgotten in the other. Task 63's review then found that the two
readers were asking different questions - `planSlots` wants "does this node
need a scratch local", `liveWords` wants "must this node's word stay alive" -
and that checked arithmetic answers yes to the second and no to the first, so
every checked node had been reserving a local nothing ever loaded. The
predicate had to split, which is the argument for a real abstraction rather
than a fourth disjunct.

The shared status bit is the same story at the telemetry end: an ANSI overflow
decline reports "chrono range", and `VarkaFusedKernel`'s own javadoc invites a
new lowering to take its own bit. Task 63 left this registered rather than
fixed (`PLAN_TASK_63.md` 9.7, from its 7.4).

**The shape.** Refusal becomes a property a node declares - the mask it
computes, the word that qualifies it, and the reason it refuses - with one
analysis set, one slot rule, one collect, and a status bit per reason. A fifth
refusing node is then one arm, and the "which of the four fired" question that
telemetry cannot answer today becomes free.

**The admission check.** No emitted byte moves for any shape that exists
today: the pinned line map, the shape hash and every `codeSize` assertion hold
unchanged, and `dev/varka_emit.sh --table` shows the same op counts for
`year(date_add(d, off))`, `add_months(d, m)`, `make_date` and `i + 1` under
ANSI. That is the whole check - this task buys legibility and a status bit,
not speed, and if it moves a byte it has changed something it should not have.

### 2.15 One value-range lattice, instead of two overlapping analyses (task 84)

**The observation.** Two analyses compute overlapping facts about what a node
can hold. `dayRange` answers "which epoch days can this subtree produce", for
admitting a calendar node's child (task 52). `intBound` answers "how large can
this int be in absolute value", for removing an overflow check (task 63). They
disagree about what a runtime guard proves, they duplicate the literal-slot
lookup, and task 63 had to bridge them - `intBound`'s `datediff` arm now calls
`dayRange` with a flag that turns the guard assumption off.

That bridge is where the bugs were. Of the three wrong answers task 63's
review found, two were in exactly this seam: `datediff`'s bound assumed the
date contract for operands that a literal shift had already pushed out of int
range, and nested bounds were combined with wrapping `Long` arithmetic, so a
bound that passed 2^63 came back small and positive and proved anything at all
safe. The third was an off-by-one in the comparison the bound feeds. Three
bugs, one region, and none of them reachable by a missing switch arm - which
is why they survived to a review.

**The shape.** One interval domain over lane values, with the two questions as
queries on it rather than as separate traversals. Two properties do the work.
The lattice's operations saturate by construction, so the `Math.addExact`
discipline task 63 had to add by hand is not something a later arm can forget.
And "what does a runtime guard prove" becomes an explicit parameter of a query
rather than a fact baked into one traversal's arms, because that is the
distinction both the calendar admission and the overflow check need and only
one of them had.

It is also the natural first piece of the compiler to write in Java: a sealed
interval type and its lattice operations are pure data, with no Catalyst
surface to speak of.

**The admission check.** Every shape the compiler admits or declines today is
admitted or declined identically - the compiler suite's decline reasons and
fused shapes are the oracle, unchanged - and the differential's fusion
classification does not move on any committed query. Then one new property
test the current code cannot pass: over randomly generated IR, the interval a
node reports contains the value the reference evaluator computes, for every
lane pattern. If that test is not written, the task has not been done, because
it is the only thing standing where three bugs already stood.

### 2.16 Lane type as a parameter (task 85)

**The observation.** `INT_VECTOR` appears **204 times** in
`VarkaLoopEmitter.java`, beside sixteen four-byte stride assumptions and
eighteen species references. Every one is a place int64, boolean or decimal
lanes have to reach, and milestone 5's own headline tasks - 28's lane-width
conversion and 29's int64 lanes - are blocked behind exactly this.

The IR does not carry a lane type at all: `ColumnRef(int ordinal)` names a
column and nothing else, and the emitter supplies int32 by assumption. That
worked while every node was int32 and will not survive the second lane.

**The forcing function, which is cheap and already wanted.** Year-month
intervals are int32 months - the *same* physical lane as DATE and INT (the
owner's standing interest, `PLAN_MILESTONE_4.md` 2.x). Three logical types
over one physical lane is precisely the case that decides the design question:
it shows that the lane belongs to the node's physical representation and not
to its Spark type, which is also what `SCOPE_MILESTONE_6.md`'s "several
representations per logical type" will need. So that type can land before this
refactor and be its test rather than wait behind it.

**The option space.** (a) Parameterise the emitter on a lane descriptor -
vector class, species field, byte stride, load and store descriptors - and
switch on it at the sites that genuinely differ. (b) Generate a per-lane
emitter from a template. (c) Duplicate the emitter per lane. The count above
is the argument: of 204 sites most are mechanical and roughly twenty carry
real per-lane behaviour, which favours (a); (b) and (c) both re-create the
"two copies drift" failure this milestone is trying to remove. Measure before
committing, since (a) risks a megamorphic descriptor call in the hot path and
that is a benchmark question, not an argument.

**The admission check.** The int32 lane's emitted bytes are unchanged - the
pinned oracles again - and a second lane type reaches the same green
differential and fuzz matrices as the first, at both vector widths. The fuzz
suite's reachability test, which today asserts the generator can build every
node type in the sealed hierarchy, is widened to node type times lane type;
that test is what will fail when a later type arrives without an arm, and it
is the cheapest thing in this whole section.

### 2.17 One operand admission, stated once (task 86)

**The observation.** Four near-copies decide whether an expression may be an
operand: `intOperand`, `compileIntOperand`, `compileOffset` and `compare`'s
own `operand`. Task 63's review found them independently and called them
duplicated. Worse, the emitter restates their conclusions in four
`require*Shape` helpers, and those are a second, independent statement of the
same rule.

They drifted, exactly as a second statement does. Task 63 widened
`compileOffset` to admit arithmetic but `requireDayOffsetShape` admits only
three node kinds, so `date_add(d, weekday(d2) + 1)` - which lowers to task
57's `DayOfWeekIso` - was accepted by the compiler, marked fused in EXPLAIN,
and then refused at emit time, where the evaluator turns the refusal into a
silent per-batch fallback. That is the ghost fallback `sql/varka/AGENTS.md`
forbids, and it shipped in the PR until a review found it.

**The shape.** One admission function taking what the position accepts, and an
emitter check *derived from the same table* rather than written beside it -
so that widening the compiler either widens the emitter or fails to compile.
The check's fail-fast value is worth keeping; what is not worth keeping is
stating the rule twice in two languages.

**A widening this task carries, added 8 September 2026 from task 79's admission
check.** `compare`'s `operand` admits an int *literal* - which is what makes
`month(d) = 6` fuse - and sends everything else to `compileNode`, whose value
leaf is `DateType` and the year-month interval, so a bare `IntegerType` column
in predicate position declines. One case for an `IntegerType` `BoundReference`
fuses `CASE WHEN m > 0 THEN d ELSE d2 END` and
`CASE WHEN m >= -1000 AND m <= 1000 THEN add_months(d, m) ELSE ... END`,
verified by patching the compiler and reverting; `PLAN_TASK_79.md` 2.2 has the
IR.

It belongs here rather than in a row of its own precisely because it is a
widening of one of the four copies. Doing it standalone is the move that
produced the ghost fallback this task exists to prevent, and the reasoning that
it is safe standalone - `Compare` takes arbitrary IR operands, both lanes are
int32, so the emitter probably needs nothing - is the same shape of reasoning
that was wrong last time. Under this task the table decides and the enumeration
test proves it. It is also the natural first exercise of the unified table: a
position gains a kind, and nothing else in the file has to be touched for the
emitter to agree.

`BETWEEN` comes along for free and needs no arm of its own, which is worth
saying because the first reading of it said the opposite. `Between`'s
replacement is `With(input) { ref => And(...) }`, and the compiler has no arm
for `With`/`CommonExpressionRef` - so `dev/varka_emit.sh` declines it. That tool
resolves names and functions and nothing else; it never optimizes, and
`RewriteWithExpression` inlines a binding whose child is `CollapseProject.isCheap`,
which an `Attribute` or `BoundReference` is. `d BETWEEN <lit> AND <lit>` fusing
whole at 7.96x in task 62's run is the standing proof. A `BETWEEN` over an input
`isCheap` refuses is a different question - the rewrite hoists it into a
`Project` rather than inlining - and nobody has looked at it.

Int arithmetic in predicate position stays out and where task 63's comment put
it, since a widened *leaf* is not a widened *tree*.

**The admission check.** A test that enumerates the operand positions and,
for each, asserts that the set the compiler admits and the set the emitter
accepts are the same set - the assertion whose absence let the drift above
ship. Then every decline reason in the compiler suite unchanged *except* the
one the widening above removes, which is the single shape this task is allowed
to move from residual to fused, and which its own test names.

### 2.18 The epilogue is the one method no budget bounds (task 87)

*Moved to `SCOPE_MILESTONE_6.md` item 15 on 15 September 2026, when the milestone was re-scoped to 64-bit lanes and `TIME`: kept for a future fix at the owner's request; noted in section 6 because 64-bit lanes widen every node and may make it bite sooner. The text below is unchanged and the heading stays so citations of this section number still resolve.*

*Added 8 September 2026, from a 35-million-iteration fuzz run. **Absorbs
milestone 4's row 44, "the epilogue's size", on 11 September 2026**: that row
asked for a size ladder that can see the problem - 4095 and 63 rather than only
4096 - and the epilogue measured against `HugeMethodLimit`, with the mechanism
chosen on the numbers. This section has the same defect at a harder threshold,
with a one-iteration reproducer, and the mechanism it needs - partitioning the
epilogue the way `GROUP_BUDGET` partitions the loop - is the one row 44 would
have had to choose. Two rows would have designed one partitioning twice. Row
44's size ladder and its `HugeMethodLimit` measurement become requirements
here: the 65535-byte cap says the epilogue must be split, and
`HugeMethodLimit` (8000 bytes, past which C2 declines to compile at all) says
how small the pieces have to be for the split to be worth anything.*

**The observation.** `VarkaLoopEmitter.emit` built a 67244-byte
`epilogueMasked` for a nested `make_date` tree and the Class-File API refused
it: the JVM caps a method at 65535 bytes. It reproduces as one iteration,
`-Dvarka.fuzz.seed=2026092800 -Dvarka.fuzz.only=73411`, and the tree that
produced it contains no arithmetic node at all - this is a date-op finding that
predates task 63 and was reached by volume rather than by anything new.

**Why the existing caps did not catch it.** There are three, and each bounds
something other than the bytes of the method that failed. `MAX_CHAIN_DEPTH` (16)
bounds the depth of one output. `MAX_FUSED_NODES` (64) bounds the distinct ops
in the whole kernel after CSE. `GROUP_BUDGET` (16) bounds the weight of one
*loop* method, and the emitter partitions the loop into `loopDense<g>` and
`loopMasked<g>` accordingly - which is exactly what `MAX_FUSED_NODES`' javadoc
leans on when it says the ops "are spread over loop methods of at most
GROUP_BUDGET ops each, so this caps the kernel, not any one compiled method".

The epilogue is not partitioned. `emitBody` is called once for it with
`group = -1`, so every group's ops land in a single `epilogueMasked`, and the
one bound that was supposed to keep a method small is the one that does not
apply to it. The claim quoted above is therefore true of the loop methods and
false of the epilogue, which is the sentence to correct along with the code.

Weight is also not bytes, and `make_date` is where the two diverge most:
`MAKE_DATE_WEIGHT` is 60 against a `GROUP_BUDGET` of 16, so a single
`make_date` already exceeds a group on its own and rides the `FUSED_CEILING`
escape. Sixty-four of the heaviest op the emitter has, all in one method, is
about 67KB - which is the number observed. A budget counted in weight cannot
bound bytes unless the weight-to-bytes ratio is bounded too, and across the op
set it spans more than an order of magnitude.

**What a user sees today, which is why this is not urgent.** Nothing wrong.
`VarkaKernelEvaluator.fusedRunner` catches the emission failure by name - its
comment already says "an IR shape past the emitter's caps" - logs a warning,
counts `numEmissionFailures`, emits an `EMISSION_FAILURE` fallback event, and
every batch takes the per-row path. Answers are the row engine's. The costs are
that the kernel is built and thrown away once per task, and that a shape inside
the documented caps degrades silently rather than being declined at compile
time with a reason, which is the outcome the ghost-fallback contract in
`sql/varka/AGENTS.md` asks for everywhere else.

**The task.** Either partition the epilogue the way the loop is partitioned, or
give the emitter a byte budget it can check before it hands the class to the
Class-File API - and in both cases turn the failure into a decline with a
reason rather than an exception the evaluator has to catch. The choice is worth
measuring rather than arguing: partitioning adds a call per group to a body that
runs once per batch, and the epilogue is the tail, so the per-batch cost lands
on short batches hardest.

**The admission check.** The fuzz iteration above, as a pinned emitter test,
declining with a reason instead of throwing; every shape that fits today
emitting the same bytes, against the pinned line map and the `codeSize`
assertions; and `MAX_FUSED_NODES`' javadoc corrected to say which methods its
guarantee covers.

### 2.19 An exact division through double lanes (task 88)

*Added 9 September 2026, from task 68's admission check.*

**The observation.** Task 26's whole design, and task 65's replacement for it,
rest on one absence: `VectorOperators` has no multiply-high on any lane type, so
an exact Granlund-Montgomery magic is not expressible on int lanes. 2.7 takes
that as given and routes around it by widening the dividend to int64, where a
64-bit low product is enough.

There is a second route, and nothing in this repository has considered it: widen
to *double* lanes instead. `(double) v` is exact for every int32, IEEE
multiplication is correctly rounded, and `D2I` narrows by truncating toward zero
- which is Java's `/` exactly. So `trunc((double) v * (1.0 / d))` is a candidate
lowering for `v / d` with no magic constant, no round-down correction and no
range restriction.

**Why it is exact, which decides whether this is worth a task at all.**
If `d` divides `v` the quotient is an integer under 2^31, exactly representable,
and the multiply is correctly rounded to it. If it does not, the true quotient
`v / d` has denominator `d` in lowest terms, so it lies at least `1 / d` from
every integer; the floating error is at most about `2^31 / d * 2^-52`, which is
`2^-21 / d`. Truncation therefore lands on the same integer for every divisor
below roughly 2^21. The margin does not thin out at the top of the range - it
scales with the quotient - which is the difference from an int-lane magic, whose
exact range is a fixed fraction of the type's.

Checked as well as argued: over structured and random int32 dividends, for
divisors 12, 3, 7 and 100, both `(double) v / (double) d` and the faster
`(double) v * (1.0 / d)` matched Java's `/` on every case, and an `I2D` /
multiply / `D2I` round trip runs on this machine's preferred species - sixteen
int lanes to two eight-lane double halves.

**What it would delete, if it wins.** The same list 2.7 offers - the round-down
magics and their correction carries, the `NARROWED` variant, `VarkaChrono`'s
range constants - but without task 65's precondition, since it needs no int64
lane and therefore none of milestone 5's lane-width work. It also removes the
range bound from `extract(YEAR FROM ym)`, which task 68 deferred for exactly
that bound (`PLAN_TASK_68.md` 2.2: the int-lane magic for `/12` is exact over
0..49,151, about one forty-thousandth of a year-month interval's range). It does
not rescue `extract(MONTH FROM ym)`, whose output is a `ByteType` Varka cannot
emit; that is task 89's other blocker and no division removes it.

**Why it is an A/B and not a decision.** It costs what 2.7 costs and possibly
more: half the lanes, two conversions in and two out per int vector, and a
double multiply rather than an integer one. 2.7's own estimate for the int64
route is "a small throughput loss bought with a large simplification"; this one
has the same shape of cost and must be measured against both the shipped magic
*and* task 65's widening, on the same shapes, before either is chosen. Three
arms, one benchmark.

**The admission check.** The exactness argument above, verified exhaustively
rather than sampled - for `/12` over the whole int32 range, and for the calendar
divisors 146097, 36524, 1461 and 365 over the dividend ranges
`emitChronoPrefix` actually produces - by a committed script beside
`verify_long_lane_magic.py`, which is the precedent. Then the op counts for one
extraction under each of the three lowerings, from `dev/varka_emit.sh --table`,
before any measurement is taken.

**What would reject it.** A divisor at or above 2^21 (none of Varka's are); a
lowering that needs the *remainder* at full width, where the double route gives
the quotient and the remainder costs a multiply back; and the conversion cost
exceeding the magic it replaces on the shapes that matter, which is what the A/B
is for. `Float16` and single-precision floats are not candidates - 24 mantissa
bits cannot hold an int32 dividend - so this is a double-lane question only.

### 2.20 The year-month interval divisions (task 89)

*Added 9 September 2026, split out of task 68 by its admission check.*

**The observation.** Of the eight expressions `PLAN_MILESTONE_4.md` 2.33 gave
task 68, two need division by a constant: `extract(YEAR | MONTH FROM ym)` and
`ym / num`. `PLAN_TASK_68.md` 2.2 to 2.4 found three things about them the
section had assumed away. The exact int-lane magic for `/12` is the one the
emitter already has, `MONTH_ARITH_M`, and it is exact over `0..49,151` - about
one forty-thousandth of a year-month interval's int32 range. That magic
computes a floor, and `extract` is Java's `/`, which truncates; they differ on
every negative with a remainder. And `ym / num` rounds `HALF_UP`, ties away
from zero, which needs the remainder as well as the quotient, over an exact
range that depends on the divisor.

A fourth blocker is not about division at all: `extract(MONTH FROM ym)` returns
a **`ByteType`** (`ExtractIntervalPart[Int](ByteType, getMonths, ...)`), and
Varka has no byte lane and no `ByteType` arm in `allocateVector`. A perfect
division still leaves that expression un-emittable.

**Why it waits.** Two routes in this milestone remove the range bound outright:
task 65's int64 widening and task 88's double lanes. Building a range-guarded
int-lane version first means building the thing either exists to delete, and
then owning both. So this task is sequenced after whichever of 65 and 88 the
three-arm A/B chooses, and takes its division from that.

**The task, once a route is chosen.** `extract(YEAR)` as the chosen division
with a truncation correction on the negative side; `extract(MONTH)` as
`months - 12 * q` over it, *once a byte output exists* - which is its own
question and may leave `extract(MONTH)` residual for longer than its twin;
`ym / num` for a literal `num` as the chosen division plus the `HALF_UP` step,
with a power-of-two `num` taking the shift; `ym / col` declining, since the
divisor is then not a constant.

**The admission check.** The truncation and `HALF_UP` corrections verified over
the full int32 month range against `IntervalUtils.getYears`, `getMonths` and
`IntMath.divide`, by a committed script; the byte-output question answered - an
`IntVector` narrowed at the store, or a decline with a reason - before
`extract(MONTH)` is attempted; and a throughput pair per shape against the row
engine, since these are new lowerings and not, as task 68's group A is, old
kernels under a new type.
### 2.21 The benchmark files are not reproducible run to run (task 90)

*Added 9 September 2026, from the investigation task 79's section 9 asked for.
The band this section asks for was measured on 10 September and is committed
beside each results file; the numbers below come from three runs and understate
it, and the row records what ten runs per width give instead.*

**The observation.** Two regenerations of `VarkaEmitterParityBenchmark` with no
code change between them disagree, on rows nothing touched. Pinned to one core
complex the median case moves 1.6%, but 73 of 211 cases move more than 3%, 22
move more than 10%, and the worst reaches 26%. Unpinned the worst reaches 75%.
Task 79's 9.1 refused to commit a regeneration on that evidence; this is what
the evidence turned out to be.

**What it is not**, each eliminated by measurement rather than argument:

* *Within-run noise.* Across all 207 cases of a committed run, the ratio of the
  average iteration to the best iteration has a median of 1.007 and never
  exceeds 1.5. Every case is tight inside its own run - the harness reports the
  best of tens of thousands of iterations, and the average is the same number.
  So the compiled code is in place before measurement starts, which rules out
  compiler-queue saturation, code-cache exhaustion and deopt storms.
* *The clock.* Sampling `scaling_cur_freq` through six runs of one case gives
  5.08 to 5.14 GHz, a 1.2% spread, while the throughput of those same runs
  moves 31%. At a fixed clock, the work per cycle is what changed.
* *Address layout.* Disabling ASLR with `setarch -R` does not narrow the
  spread; interleaved, the randomised runs were tighter than the fixed ones.
* *Contention.* The machine is idle; nothing but the JVM is on the pinned cores.

**What it is, and task 32 got here first.** A per-fork JIT and code-layout
lottery: the same bytecode produces slightly different machine code and
placement in each JVM. `-Xbatch`, which removes the compilation timing races,
narrows one case's spread from 49% to 14% and does not remove it.

`PLAN_TASK_32.md` 11 investigated this and went further on the JIT side than
this section does. It found the upstream report - JDK-8380195, "Vector API
produces bimodal performance - nondeterministic C2 intrinsification across JVM
forks", roughly 2x across identically configured forks, closed **Not an Issue**
in April 2026 - and it tested and refuted the obvious levers: buffer alignment
raised to 64 bytes, which pinned the *slow* mode rather than the fast one;
`-XX:-UseOnStackReplacement`; `-XX:LoopUnrollLimit` at 250; and forced
inlining. Its conclusion was that whatever picks the mode is inside C2's code
generation and is not reachable from those levers, and it measured one kernel
21 times at 128-bit, landing the fast mode 4 times. Nothing here contradicts
it. What this section adds is the size of the effect across a whole file, the
proof that it is not within-run, and the machine half below.

**The machine half is new, and it is the fixable part.** The Ryzen AI 9 HX 370
is heterogeneous: four Zen5 cores at 5.16 GHz and eight Zen5c at 3.29 GHz, on
two separate 16 MB L3 slices. An unpinned thread is rescheduled between them
during a 40-minute run, so each case is measured wherever it happened to be.
The clock alone is worth 1.57x - and the datapath is not the difference, since
the measured ratio of 1.5632 matches the clock ratio of 1.5680 to 0.3%, so both
core types retire this code at the same rate per cycle. Migration also costs L3
residency where the working set fits one slice: `fused, depth 1` reads 154 GB/s
resident and 37.7 GB/s after, the 4x collapse that started this. Pinning to the
fast complex takes the worst case from 75% to 26%. That is done, in
`dev/varka_bench_regen.sh`, and recorded in each provenance file.

**What follows, and it is mostly reassuring.** An A/B whose two arms sit in the
same run is sound: they share a JVM, a layout and a clock. Every A/B in this
project is built that way, which is why task 79's arm-context pair read 0.5%
and 1.4% across two runs whose absolute rates disagreed by 75%, and why task
67's interval pair was trustworthy. The project's *decisions* are not in
question wholesale. What is in question is a number compared against a previous
run, below the band - and the regeneration diff's "moved by at least 3%" report
is below the band for every memory-bound row.

**One decision does rest on such a diff**, and it is named here so it is
re-tested rather than inherited: `PLAN_TASK_63.md` 9.7 attributes 26.1%
(14706.5 to 18542.2 M rows/s at AVX-512) to removing a dead local slot, from a
comparison of two *unpinned* regenerations, and 2.12 above narrows task 82 to
"a 128-bit task" on the strength of it. 26.1% is at the very top of the band
measured here, from runs where the worst case is 75%. The mechanism may be real
- the emitted bytes did change, and a dead local does change register pressure
- but the magnitude is not evidence until it is re-measured pinned.

**The task.** Establish the band per file with `dev/varka_bench_repeat.sh`,
commit it beside the results, and make the regeneration diff report against the
band rather than a flat 3%. Then decide, with numbers, whether the absolute
rates are worth buying back: N forks per case with a median, which is what JMH
does and would cost N times a regeneration. The alternative is to stop treating
absolute rates as comparable across runs and let the A/Bs carry every claim,
which is close to what the plans already do in practice.

**The admission check.** The band measured for the parity, arithmetic and
throughput files, three runs each, committed; and one shape whose A/B is known
tight - task 79's arm context - shown to stay tight across those same runs
while its absolute rate wanders, which is the evidence that the two kinds of
number deserve different treatment.

### 2.22 A guard bound the shift above it chooses (task 91)

*Added 10 September 2026, from task 69's outcome.*

**The observation.** Task 69 gave the upward direction its own limit and three
of four conservatively-declining shapes fused again. The fourth did not:
`weekofyear(date_add(d, off))` and its `yearofweek` twin, which
`PLAN_MILESTONE_4.md` 9 named as the ordinary query shape the debt was really
about. `ThursdayOf` shifts `+-3`, and the `-3` side reaches
`NARROW_MIN_DAYS - 3`, where the narrowing is not conservative but genuinely
undefined: `w = days + NARROW_BIAS` goes negative and `(w * NARROW_ERA_M) >>>
NARROW_ERA_K` reads it as about 4.29e9. No headroom exists below the way it
did above, because `NARROW_MIN_DAYS` is exactly `w = 0`.

**The lever is the guard, not the constant.** Task 52's runtime guard on a
column-offset `date_add`/`date_sub` compares its result against
`[NARROW_MIN_DAYS, NARROW_MAX_DAYS]` and falls the batch back when it leaves.
Those two bounds are already *parameters*: `emitRangeGuard` takes `lo` and
`hi`, because task 60 reuses the same block for the month count against
`MONTH_ARITH_MIN/MAX_MONTHS`. Only `emitAndValidatedOp`'s call site hardcodes
the day pair. Nothing requires them to be *those* constants: the compiler
knows, from `dayRange`, exactly how far the subtree above the producer shifts
the day, and
could ask the guard to enforce `[NARROW_MIN_DAYS + 3, NARROW_MAX_DAYS]` for a
`ThursdayOf` consumer, or `[NARROW_MIN_DAYS + 365, ...]` for a `trunc` one. The
run-time cost is identical - the same compare against a different immediate -
and the compile-time decline becomes a batch fallback only for the batches that
actually contain a day in the last three (or 365) of a thirteen-thousand-year
window, which is to say never, in practice.

**Why it is worth a row.** It closes the debt register entry task 69 swept only
half of, it turns two more ordinary shapes from residual into fused, and it
generalises: every downward-shifting consumer over a guarded producer becomes
admissible by the same rule, which is the whole `trunc` family. It also
subsumes the asymmetry task 69 shipped, since the upward side is the same idea
with the shift added to the ceiling instead of the floor.

**Why it is not free.** The guard's bound stops being a constant of
`VarkaChrono` and becomes a per-node property the emitter reads, which touches
`VarkaEmitOptions`' canonical form and the shape key: two subtrees identical
except for the shift above them must not share a kernel. That is the same
question task 84's value-range lattice answers for `dayRange` and `intBound`,
so this task belongs after 84 and should take its interval representation
rather than inventing a second one.

**The admission check.** Half of it is already answered: `NARROW_MIN_DAYS`
appears three times in `VarkaLoopEmitter`, and only one is a call site - the
guard block itself is parameterised. What remains is a `VarkaEmitDump`
op-count diff between a guard at the floor and one three days above it,
showing the kernel differs by an immediate and nothing else, and the shape key
shown to separate two otherwise-identical subtrees whose shifts differ, both
before any downward consumer is admitted.
### 2.23 The validity write, keyed on the bit layout (task 92)

*Added 10 September 2026, from task 47's measurement.*

**What task 47 established.** The per-group validity write is a
read-modify-write, and at four lanes a validity group is half a byte, so two
consecutive groups rewrite the same byte and serialise on it - the regime
task 76 found its helper choice inverting inside. Task 47 built the writer
that removes it: the bits accumulate in a register and the whole 64-bit word
is stored, with no read. Over ten pinned runs it is a **6 to 9% win at 4
lanes** at one and two writes, and the inversion disappears with it. It is an
**11 to 20% loss at 8 and 16 lanes**, where a group owns whole bytes and
there was no chain: an eight-byte store plus an accumulator, a mask, a shift
and a branch is more work than a one-byte read-modify-write whose helper
already inlines.

**The rule that follows, and why it is not task 76's rejected one.** Task 76
declined a rule keyed on the write count because it would be two thresholds
fitted to one machine. This is one condition and it is not fitted: **a
validity group smaller than a byte**, which is `lanes < 8` - 2 and 4 lanes,
and nothing else, forever, because a group is `lanes` bits. It is the
mechanism written down rather than a number tuned. `widthSpecialised` already
reads `analysis.lanes`, so the plumbing exists.

**Why it is a task and not a line of task 47.** Not because the rule is
academic - this section said that at first and it was wrong, corrected here
rather than tidied away. `SPECIES_PREFERRED` for an int lane is 128 bits on
every NEON-only aarch64, Apple Silicon among them, and on x86 without AVX2:
four lanes, which is exactly the regime task 47 measured its 6 to 9% win in,
and the reason this project commits 128-bit companion results files at all.
The rule is a real target's default, not a `MaxVectorSize` flag's.

What it is a task for is confirmation. Task 47's four-lane numbers come from
`-XX:MaxVectorSize=16` on an x86 whose preferred width is 16 lanes, which
simulates the lane count and not the store behaviour of a machine that has
only 128-bit registers. A default worth 6 to 9% on a whole class of hardware
should be measured on that hardware once. That makes this task's admission
check a hardware question, and it shares one with `PLAN_MILESTONE_4.md` row
62's pinned runner - though not the same machine: 62 wants a wider one than
this laptop and this wants a narrower one.

**Two more items ride with it, because they share a ladder run.** Option B of
`PLAN_TASK_47.md` 3.1 - store once per word rather than once per group - is
where the two widths that lose might be recovered, since what they pay for is
the wider store and not the removed read; it is a branch per group against
three stores in four saved at 16 lanes. And `PLAN_TASK_47.md` 3.4's driver
item, the masked driver's dead null-state prologue (`PLAN_TASK_70.md` 9.5),
which pays per batch rather than per group and is the whole of the remaining
gap on a 64-row batch at AVX-512 - 1080.1 against the dense 1549.6.

**One thing to settle first, and it is cheap.** Task 47's ladder has a step
at three writes that neither task's model predicts: both per-group arms fall
away sharply there and the word writer does not, so its advantage reads 22 to
38% at every width against 5 to 9% at its neighbours. The emitted code is
ruled out - all four rungs are one loop method growing ~130 bytes per write,
asserted in `VarkaLoopEmitterSuite`. The leading hypothesis is task 46's own
mechanism, the caller's node count crossing C2's inlining cutoff so one more
OR call stops being inlined. `-XX:+PrintInlining` on the k=2 and k=3 rungs
answers it in one run, and until it does, no rule may be fitted across k=3 in
either task's table.

### 2.24 Instruction-level parallelism (task 25, item 13)

*Moved to `SCOPE_MILESTONE_6.md` item 15 on 15 September 2026, when the milestone was re-scoped to 64-bit lanes and `TIME`: tuning of the int32 paths, with a harness that first has to be re-established. The text below is unchanged and the heading stays so citations of this section number still resolve.*

The debt register's rule applies: a prediction goes in writing before the
first measurement, and the honest null hypothesis is that C2 plus the
out-of-order engine already collect most of the available overlap on a 16-op
body, so K pays only on the long chains. The three confounders move together,
never one at a time: K, the broadcast strategy (pinned locals collapsed
throughput 7x at ~32 broadcasts, so unrolling and pre-broadcasting *compete*),
and `GROUP_BUDGET`, which unrolling multiplies against a ~1 ms-per-vector-op
C2 compile (**confirmed by task 43**: 1.1 ms per op at AVX-512 and 2.0 at
128-bit, measured across a 20-to-248-op ladder - see `PLAN_TASK_43.md` 8.2, and
the reconciliation note in 2.16). The candidates are the shapes that are compute-bound and already
carry a committed number to beat: `dayofweek` (a 20-op fold), `CASE WHEN` on
an unpredictable condition, and the depth-8 chain. Row-consumer shapes are
bounded by the ~25 ns/row read-back floor and the filter path by compaction;
no kernel-side ILP moves either, so neither is a candidate. One negative
result worth carrying in: a 2-way unrolled add kernel over a misaligned
buffer still lost 50-60% to the aligned case (section 8's buffer-alignment
entry) - unrolling does not incidentally hide the alignment penalty, so this
task's outcome and that entry's are independent questions, not one deferring
to the other.

Open question 4 is answered, ahead of the task and with the broadcast
confounder held fixed at "emitted per use" so it does not contaminate the
result (`VarkaUnrollFactorBenchmark`, committed results file in
`sql/varka/engine/benchmarks/`, four runs total including two taken after
merging task 24's PR and enabling the machine's performance mode, neither of
which changed a conclusion): on an 8-op chain, K = 1, 2 and 4 are flat at
both vector widths, on every run - within 4% either way, no consistent
winner. The honest null hypothesis holds exactly on a body this short. On a
20-op chain (the `dayofweek`-length candidate), K = 2 wins reproducibly at
both widths and on every run - +2.6% to +9.2% at AVX-512, +1.2% to +6.2% at
128-bit - and K = 4 adds no further, consistent benefit over K = 2 on either
width (the sign varies run to run, always within a few percent). So "K pays
only on the long chains" is confirmed rather than merely predicted, and the
planner version below should cap K at 2 rather than search further: 4 was
measured to buy nothing on the one shape where unrolling helped at all, while
still paying `GROUP_BUDGET`'s doubled cost over K = 2. This measurement is
also where a real methodology trap surfaced and was caught: comparing K = 1
(straight-line unrolled source, the shape a real emission carries) against an
earlier K = 2/4 written as a small constant-bound runtime loop over the op
index produced a spurious 30-60% *loss* at K = 4 - an artifact of the loop
shape, not of unrolling. Rewriting K > 1 as straight-line interleaved code,
matching K = 1's shape exactly, is what produced the numbers above (`SKILLS.md`
carries the general lesson).

Re-measured in forked JVMs after the harness debt closed (the results file's
header says how): the same picture. Depth 8 flat at both widths; depth 20
gains +4.4% from K = 2 at AVX-512 and +4.3% at 128-bit by min, and K = 4 over
K = 2 is +3.4% at one width and -1.9% at the other. The conclusion did not
depend on the harness, which is what a plain add/sub chain should show.

If a factor above 1 pays, the deliverable is the planner version: the emitter
already knows the DAG's live-temporary count per lane group, so K is chosen
per shape, and a shape whose live set fills the register file declines to
unroll. That version exists only because the loop is generated - it is the
whole reason this item belongs to Varka rather than to hand-written kernels.

Task 24 goes first because an unrolled body's remainder is `K * lanes - 1`
rows, so the tail question and the unroll question share a harness (open
questions 4 and 5) - and the batch-size knee sweep (question 6) rides the same
harness for the wide-shape case. Whatever the outcome, the `SKILLS.md`
unrolling bullet is rewritten with the numbers, as it promises itself.

### 2.25 Output order for prefix affinity (task 72)

*Moved to `SCOPE_MILESTONE_6.md` item 15 on 15 September 2026, when the milestone was re-scoped to 64-bit lanes and `TIME`: tuning of the calendar prefix. The text below is unchanged and the heading stays so citations of this section number still resolve.*

Added 7 September 2026, from B2's one pinned limitation (`PLAN_TASK_32.md`
10.2 and 7.6). `groupOutputs` is greedy in output order, so in
`year(d), year(d2), month(d)` the month is offered to the group holding
`year(d2)`, whose prefix it cannot reuse, and forms a third loop method that
recomputes the decomposition of `d` the first method already ran; adjacent,
the same three outputs take two methods. B2 pinned that as a limitation
rather than fixing it, because the driver's output order is the projection's
and other things key on it.

**The admission check, which is most of the task.** Nothing in the emitter
requires a group's output indices to be contiguous: `groupOutputs` already
returns lists of indices, each loop method takes its list, and the driver
calls the methods in group order while the destinations stay indexed by
output. So the change may be a two-pass grouping - gather each calendar
output into the group whose prefix it reuses, wherever that group is, then
fill the rest greedily as today - with no change to the evaluator's per-output
vectors or to `VarkaDebugInfo`'s line map, both of which key on the output
index and not on which method computes it. The check is to establish that:
every consumer of a group walked, the line map and the pinned oracles asserted
unmoved, and the differential suite run with a deliberately permuted grouping
before the rule is written. If a consumer does depend on contiguity, the task
says which and stops, and the debt entry stays.

**What it is worth.** The shapes B2 measured, with one output between the
siblings: `year(d), year(d2), month(d)` against `year(d), month(d), year(d2)`
in the parity harness, at both widths, with the prediction registered that
the permuted grouping matches the adjacent one within noise. Date columns in
a projection are usually adjacent, so this is a small task with a bounded
win; it is a row because the limitation is pinned in a test that should be
flipped by a change, not silently.

### 2.26 A stopping rule for the guard walk (task 73)

*Moved to `SCOPE_MILESTONE_6.md` item 15 on 15 September 2026, when the milestone was re-scoped to 64-bit lanes and `TIME`: tuning of the int32 guard walk. The text below is unchanged and the heading stays so citations of this section number still resolve.*

Added 7 September 2026, out of task 70's fuzz run (see the debt register).

**The observation.** `Analysis.collectGuardedProducers` calls
`collectColumnOffsetProducers(chronoChild(node), ...)` for every `isChrono`
node, and that walk descends the entire subtree, adding every
`AddDays`/`SubDays` with a column offset it meets. It has no stopping rule.
So in `month(dayOfWeek(date_add(date_add(d, off), off)))` the producer is
guarded against `[NARROW_MIN_DAYS, NARROW_MAX_DAYS]` on its own value, when
the value `month` actually decomposes is the `dayOfWeek` result and is always
1 to 7. `weekday`, `dayofweek_iso`, `weekofyear` and `datediff` behave the
same way: each bounds its output, and none of them stops the walk.

The guard is not wrong, it is unnecessary. A batch whose producer leaves the
range is declined and recomputed on the row engine, so the answers are right;
what is lost is the fusion.

**Why the task starts with an admission check, like task 69.** Through the
compiler this shape never reaches the emitter. `dayRange` has no rule for a
mod-7 node, so it returns `Unknown`, and `checkedForCalendar` declines the
entry at compile time with "day producer the calendar range analysis does not
bound". The entry is residual either way, and only a caller that builds IR
directly - `VarkaIrFuzzSuite`, and any future planner-side rewrite - reaches
the over-guard. So the first question is whether any SQL shape observes the
difference at all. If none does, the honest outcome is to record that and
close the task, exactly as task 69's section 2 is allowed to.

If the check finds the shape does matter, the two halves have to move
together, and the compiler half is the one that changes what a user sees:
`dayRange` would gain a rule that a mod-7 node re-bases its child to a known
small interval regardless of what the child's interval was, which is the same
observation stated on the other side of the compiler. Then
`year(dayofweek(date_add(d, off)))` fuses instead of going residual.

**What closing the emitter half takes.** A stopping rule on the walk: descend
only through nodes that pass a day through to the decomposition - `AddDays`,
`SubDays`, `Greatest`, `Least`, `IfElse`, `NextDay`, `ThursdayOf`, `LastDay`,
`AddMonths`, `TruncDate`, `MakeDate` - and stop at any node whose output is a
bounded quantity of its own: the mod-7 family, `DateDiff`, `WeekOfYear` and
every calendar field extraction. The set is the same one `dayRange` would
need, which is the argument for taking both halves in one task rather than
letting the two analyses drift apart again - drift between them is what this
finding is.

**How it was found, which is part of what it is.** Not by reading the
emitter: by running `VarkaIrFuzzSuite` at 1.84 million iterations across
twenty jobs on 7 September 2026, where every one of the twenty stopped on a
shape of this form. At the shipped budget of 300 iterations it is
unreachable. The suite's own half of the mismatch - a `Gen.bound` of 7 on a
mod-7 node hid the producers beneath it from the `chronoBound` check - was
fixed with task 70, because the fuzzer is unusable past about ten thousand
iterations without it.

### 2.27 String-column compaction that keeps the Arrow layout (task 80)

*Moved to `SCOPE_MILESTONE_6.md` item 15 on 15 September 2026, when the milestone was re-scoped to 64-bit lanes and `TIME`: strings. The text below is unchanged and the heading stays so citations of this section number still resolve.*

Added 7 September 2026, out of task 59's review (see the debt register).

**The observation.** A derived int32 leaf (task 59's weekday, task 61's trunc
level) reads its source through a `VarCharVector`. A fused Varka filter ahead
of the projection hands it a compacted batch whose string columns went through
the generic on-heap compaction, so the leaf's source is no longer Arrow-backed
and the projection refuses the batch: a stacked `next_day(d, s)` over a Varka
filter is counted in `numFallbackBatchesNonArrow` and computed on the row
path, with correct answers.

**Why it is a task now.** Every future derived leaf over a string column
inherits it, and milestone 6's item 3 puts string columns under filters and
group keys, so the shape stops being a corner exactly when that milestone
starts. Fixing it late means fixing it under a benchmark rather than under a
differential.

**The design.** A string-column compaction that keeps the Arrow layout,
writing offsets and data buffers rather than materialising rows - task 21's
`filterCompact` for fixed-width columns is the pattern, and the shape of the
work is one pass to sum the selected lengths, one to write offsets, one to
copy bytes. Measured on the task 59 differential's own fixture, with the
metric as the gate: the stacked shape must stop counting
`numFallbackBatchesNonArrow` at all.

### 2.28 Statistics-directed guard selection (task 64)

*Moved to `SCOPE_MILESTONE_6.md` item 15 on 15 September 2026, when the milestone was re-scoped to 64-bit lanes and `TIME`: tuning of the int32 guard path. The text below is unchanged and the heading stays so citations of this section number still resolve.*

Added on 4 September 2026 from a question the owner asked about task 52's
runtime guard: the input batch, or the node before, may already know the
range of a column, and then the per-lane check is work the batch has proved
unnecessary. Task 52 (#115) puts a per-lane range check on a `date_add` whose
offset is a column and whose result a calendar node reads, at a measured 5-15%
of that kernel null-free and 13-14% with mixed nulls (`PLAN_TASK_52.md` 11).
The check exists because the compiler cannot bound a column at compile time;
a batch can.

**Three sources of the bound, in order of plumbing.** First, compute it: a
vector minimum and maximum over the offset column before the kernel runs,
which task 56 already does for the interval bound through
`IntRangeOps.allWithin` and which the throughput benchmark could not measure.
A date column holds the contract range (`CONTRACT_MIN_DAYS..CONTRACT_MAX_DAYS`),
so if every offset of the batch lies in `[NARROW_MIN_DAYS - CONTRACT_MIN_DAYS,
NARROW_MAX_DAYS - CONTRACT_MAX_DAYS]` no lane of `date_add(d, off)` can leave
the calendar range and the batch runs the **unguarded** kernel - the class
task 52's option already emits, since the shape cache keys on options. Second,
read it: the cached-batch serializers, the Arrow one included, compute count,
null count, lower and upper bound per column for every cached batch, and use
them today only to prune batches under a filter at the scan; the fork owns the
serializer and the scan-to-batch iterator, so the bounds can ride with the
`ColumnarBatch` to the exec node, where the check costs nothing - the null
count already travels that way for the null-free fast path. Third, the file:
Parquet row-group and page statistics, which the Arrow-native datasource
(`SCOPE_MILESTONE_6.md`, item 8's neighbourhood) is the place to attach.

**The design, in two steps.** Step one, the pre-pass: the evaluator, for each
compiled projection whose plan carries a guarded producer, runs
`IntRangeOps.allWithin` over the offset input with the bound above and picks
the unguarded kernel when it holds, the guarded one when it does not - both
from the shape cache, both already tested by task 52's suite, so the change is
in `VarkaKernelEvaluator` alone and the emitter does not move. The in-kernel
guard stays as the answer for the batch whose offsets say "maybe", which in
the corpus is never. Step two, the statistics: `ArrowCachedBatchSerializer`'s
per-batch bounds attached to the batch it deserializes, read by the evaluator
before it computes anything, so the pre-pass is skipped when the bound is
already known; the same channel answers task 56's interval bound for free and
opens batch pruning inside the fused pipeline later. Both steps behind their
own switch, with the pass and the lookup priced against the guard on the
parity benchmark's `year(date_add(d, off))` pair and on the throughput
benchmark's `date_add(d, i)` control.

**What it does not change.** Task 52's compile-time analysis is what says
which producers need a check at all; this task decides per batch whether a
given one does. A batch with a far offset still declines, through the same
route, and the differential's far-offset fixtures hold that. Depends on #115
and on task 56's kernel, both on master before it starts.

**Why this moved out of milestone 4** (11 September 2026). The milestone's
remaining subject is task 62's closing measurement and the README written from
it, and this task cannot reach either. `DateSurfaceBenchmark`'s surface has 39
entries and exactly two carry a column offset: `date_add(d, i)`, which has no
calendar consumer and so is never given task 52's guard - the parity file's own
`task 52 control` pair is the proof, being the same number with the option on
and off - and `add_months(d, i)`, whose guard is task 60's count guard, which
3.2 of `PLAN_TASK_64.md` excludes by name. There is no `year(date_add(d, i))`
in the surface at all.

So this task changes no number the public table will show. Its own section 6
proposes *adding* a surface entry for the shape, and that entry should be
justified as coverage if it is wanted - "the surface should cover a calendar
node over a column offset" is a good reason; "so that this task has somewhere
to appear" is not, and conflating them would put a shape in the public table
because it flatters us.

Two smaller reasons point the same way. Landing it would move the parity and
throughput files, so after task 62 (B) runs the committed companions go stale,
and before it the closing measurement waits on a task that adds nothing to its
output. And the prize is shrinking on the width that matters: the requote of
`PLAN_TASK_64.md` 1 found the masked 16-lane row had already lost a third of
the guard's share to task 70's bitmap pass, and a genuine 512-bit datapath
makes the surrounding compute faster again while the guard stays two vector
compares.

It sits here beside row 82, which its own text says "shares its ground with
task 64".

### 2.29 The bench module's tests are enforced by hand or not at all (task 94)

*Opened 12 September 2026, by the review of task 62's chains PR.*

`sql/varka/bench` holds the benchmark drivers and their suites - `SurfaceTest`,
`ChainsTest`, `PlanCheckTest`, `ProvenanceTest`, the two harness tests - and
nothing runs them. `git grep 'bench/pom.xml' -- dev/ .github/` finds three call
sites and all three are `-DskipTests package`: the surface workflow's build step,
`dev/varka_bench_surface.sh`, and the jar-cache warm. `build_and_test.yml` has a
`varka-engine` job and no bench one, and `dev/varka_precommit.sh` does not reach
the module either.

What that leaves unenforced is not decoration. `ChainsTest` is where the chain
list's invariants live - every entry fuses, every entry carries a date, an int
and an interval column, every entry clears `MIN_OPS`, no entry duplicates the
surface - and `SurfaceTest` is where the surface's do. An edit that breaks any of
them merges green. The review that opened this found two such invariants already
weakened (a bound of 8 where all twelve qualified, and a predicate counting the
`INTERVAL` type keyword as a column), which is the shape of the problem: the
tests were right when written and nothing would have said when they stopped
being.

A `bench` step in `dev/varka_gate.sh` landed with that review as a stopgap - it
runs in about fourteen seconds - but a gate step is a thing a person remembers to
run. The task is the CI job: a `varka-bench` module in
`dev/sparktestsupport/modules.py` so `dev/is-changed.py -m varka-bench` answers,
the `precondition` wiring that consumes it, and a job modelled on `varka-engine`
(which is the worked example, including its own comment about having had no
condition and so building on every prose-only PR).

### 2.30 Two int32 shapes decline that a reader would expect to fuse (task 95)

*Opened 12 September 2026, while choosing task 62's chain entries.*

`datediff(d2, d) * i` declines, and so does `i % 20`. Multiplying by a *literal*
is fine - `CAST(month(d) AS INTERVAL YEAR) * 3` is in the surface - so the gap is
an int multiply whose right operand is a column, and an int remainder at all.

Both are ordinary arithmetic over the int32 lane the engine already owns, and
both are spellings a reader reaches for immediately: "how many months between
these dates, times a factor from the row", "bucket this by seven". The task is to
find out which of the two is worth the lowering and what the guard costs -
`IntArith` already carries task 63's checked/wrapping overflow discipline, so the
question is that discipline's shape for `MUL` with a runtime operand and for
`REM`, not whether the lane exists.

It is scoped here rather than in milestone 4 because nothing in the date surface
or the chains needs it: they are why it was found, not why it matters.

### 2.31 `make_ym_interval` takes only arguments derived from a date (task 96)

*Opened 12 September 2026, the same way.*

`make_ym_interval(year(d), month(d))` fuses and is in the surface;
`make_ym_interval(i, i)` declines. So the constructor for the third type cannot
be fed from the int columns the engine already reads - only from ints it derived
from a date itself.

That is a narrow hole with a wide-looking edge, because year-month intervals are
part of the public claim that Varka covers three types: a reader who writes
`make_ym_interval(years, months)` over two int columns, which is the obvious
spelling, falls back. The task is to establish whether the restriction is the
compiler's admission rule or something the emitted lowering genuinely needs -
task 67 built the type's arithmetic and this is the one shape of it that is
column-hostile - and to lift it if it is only the former.


### 2.32 A bandwidth-bound row lost 22.6% and nothing in its lowering changed (task 97)

*Opened 14 September 2026, out of task 78's regeneration.*

`date_add(d, 3)` read 1811.6 M rows/s in the surface committed on 7 September and
1401.4 M/s in the one committed on 13 September - **-22.6%** - with a clean
machine canary on both runs and the same host, governor and row count.

**It is not the lowering.** The expression emits four `IntVector` operations
today, which is what it has always emitted; `date_add(d, i)` emits four as well.
Of the fifty-five commits between the two runs, one touches the `AddDays`
neighbourhood at all, and every hunk of it is a `case GuardedDay` arm or a slot
allocation behind `reachesGuardedDay`, which a literal-offset tree never reaches.
The column-offset support that the shape might otherwise be blamed on predates
the first of the two runs.

**The hypothesis to test, and it is only that.** This is the most
memory-bandwidth-bound entry in the surface - about 0.7 ns/row, four bytes in and
four out, roughly single-core DRAM speed - so it is the row where the arithmetic
is irrelevant and the memory system is everything. Between the two runs the
benchmark table went from three int32 columns to six, because task 67 added the
year-month interval columns, and the cached table grew from about 12 GiB to
23.2 GiB. The kernel still reads only `d`, the same four gigabytes, but that
column is now interleaved among twice as much data across the batch sequence.

**What the task is.** Build the table at three columns and at six, time the same
entry against both, and settle it. If the working set explains it, then every
bandwidth-bound row in the surface - roughly a third of the entries - is being
measured against a table shaped by columns those rows never read, and the
surface's fixture needs a decision rather than a drift. If it does not, fifty-four
other commits are in scope and the question is a real regression hunt.

This is worth doing before the numbers are quoted publicly, because it decides
whether a third of the coverage table is understated.

### 2.33 Two filter rows stay under 1.0x, and the boundary is why (task 98)

*Moved to `SCOPE_MILESTONE_6.md` item 15 on 15 September 2026, when the milestone was re-scoped to 64-bit lanes and `TIME`: the read-back floor, which is large and not a type. The text below is unchanged and the heading stays so citations of this section number still resolve.*

*Opened 14 September 2026, out of task 78's outcome.*

Task 78 fixed the loss it was opened for - `WHERE d < d2` with a columnar
consumer went 0.59x to 1.14x - and two counted rows stayed below stock Spark:
`WHERE d < d2` counted at 0.80x, and `WHERE d IS NOT NULL` counted at 0.45x.
They are the only losses left in the surface.

**The cause is the same for both and it is not the kernel.** The same
`d IS NOT NULL` predicate runs at 1.2 ns/row with a columnar consumer - 9.5x over
stock - and at 11.7 ns/row counted. Identical kernel, identical data; the only
difference is what happens after the filter. The results file names the reason on
the next line: the predicate selects 96.8% of a billion rows, so about 968 million
of them cross into Spark's row world at roughly 25 ns each, which is task 19's
read-back floor and very nearly the whole 11.7 ns.

What makes these two rows the worst in the table rather than middling is that
both variables are at their extremes at once: **the maximum number of rows
crossing the floor, and the minimum amount of work saved before it**. A null-bit
test is nearly free for stock Spark, so a vector loop has almost nothing to win
back. Move either variable and the loss goes: a columnar consumer gives 9.5x, and
a heavier predicate counted (`d < d2 AND month(d) = 6`) gives 3.4x with the same
crossing.

**What the task is.** Price the one lever that removes the boundary rather than
narrowing it: making the filter node `CodegenSupport`, so an aggregate consuming
it fuses into the vector loop instead of reading rows back. That is milestone 4's
scope item 13, which task 78's outcome explicitly declined to inherit on the
evidence that the narrowing fix alone was enough for the shapes it owned. It is
not enough for these two, and they are what is left.

The debt register's `COUNT(*)` entry - the kernel at 0.9x and 0.8x on a
fully-selecting range with nothing crossing the boundary - belongs to the same
owner, because both questions are about what a predicate kernel is worth when the
consumer counts rather than reads.


### 2.34 The Varka arm may be measured in the session's worst memory state (task 99)

*Opened 14 September 2026, out of task 97's measurement.*

Task 97 eliminated three explanations for a 22.6% swing in `date_add(d, 3)`
between two committed surfaces, with numbers for each: the lowering is
unchanged, the six-column table costs about 6% and costs it nearly uniformly
across a four-op and a sixty-four-op entry alike, and running the Varka arm
fourth rather than first costs 1% in the favourable direction.

**The control is what makes the remainder worth a task.** Stock Spark measured
82.9 M rows/s for that entry on 13 September and 82.7 today, a difference of
0.2%. The machine was in the same state. The Varka arm on the same entry, same
fixture and same ordinal position reads 28% higher today than it did then.

The one variable task 97 did not hold fixed is *how long the machine had been
working when the arm began*: five hours and twenty minutes on 13 September,
forty minutes in task 97's run. Ordinal position and elapsed duration are
different things and only the first was controlled.

The mechanism that would fit is memory. The Varka arm allocates a 56g heap and
holds 23.2 GiB of Arrow-cached data; the stock arms hold 1.2 GiB. Hours of
allocation churn degrade huge-page availability and fragment the address space,
and the entry most exposed to the resulting TLB cost is exactly the one that
reads four bytes per row and does almost no arithmetic - which would also
explain why the stock arm shows nothing.

**What this would mean if it holds.** The committed surface understates Varka
rather than flattering it: every Varka number is taken in the most degraded
memory state of the session and divided by a baseline taken in the least. A
ratio wrong in the unflattering direction is not a lie, but it is not the
engine's number either, and it should be settled before the surface is quoted
publicly.

**The experiment.** Two full-length surface runs differing only in what precedes
the Varka arm - one in the committed order, one with the Varka arm first - on the
same host on the same day. About eleven hours of a quiet machine. *`PLAN_TASK_99.md`
runs this as one session with the Varka arm measured first and last instead, which
holds the day fixed rather than arguing it away and costs six and a half hours; see
its section 3.1.* If the two
Varka arms disagree by anything like 28%, the arm order in
`dev/varka_bench_surface.sh` is a measurement artefact and the script needs to
say so or stop producing it.

### 2.35 An `--only` run silently truncates its label's committed file (task 100)

*Opened 14 September 2026, found while running task 97's measurement.*

`dev/varka_bench_surface.sh --only <regex>` writes `DateSurface-<label>-results.txt`
for the entries it ran. It does not merge into the existing file and it does not
refuse: the file is replaced. So a three-entry partial run in a checkout turns
that label's committed fifty-two-entry coverage table into a three-entry one, and
`git status` reports an ordinary modification.

This happened during task 97. The measurement's Varka arms used custom labels and
wrote new files, but its two stock arms were passed under the canonical
`spark-4.2.0-jdk17` and `spark-4.2.0-jdk25` names and truncated both committed
files. They were restored before staging, and only because the working tree was
read before the commit rather than after.

The sharded path already solves this: task 62 gave `--shard I/N` a filename
suffix precisely so shards of one run could share a directory without
overwriting each other. `--only` never got the same treatment, and it is the flag
a person reaches for by hand.

**The fix is small.** With `--only` set, either refuse a label whose committed
file holds more entries than this run will write, or write to a name that says
the file is partial. The first is better: it fails at the start rather than
leaving a file to notice.

A partial file that looks complete is the same class of defect as the ones task
62 spent a week on - a run that reports success and measures something other than
what its name says.

### 2.36 The surface's per-entry numbers have a tail that one run cannot see (task 101)

*Opened 15 September 2026, out of task 99's measurement.*

Task 99 ran five surface arms in one session to test whether elapsed time
degrades the Varka arm. It does not - the two Varka arms, five hours and twenty
minutes apart, differ by a median of 0.1%. What the session did show is a
property of the benchmark rather than of the engine.

Each of the four arms with a committed counterpart reproduced it almost exactly
at the median - `varka` -0.1%, `varka-off` -0.2%, stock JDK 25 +0.0%, stock
JDK 17 -0.1%, over 52 entries each - and each carried **exactly one entry** 8%
to 27% away from it, a different entry in every arm, in both directions:
`date_add(d, 3)` +27.3%, `d = d2` +8.5%, `weekofyear(d)` +14.2%,
`trunc(d, 'MONTH')` -9.1%.

**Stock Spark carries these as readily as Varka does**, which is what settles
what they are. A 14.2% swing on stock's `weekofyear(d)` is not about a Varka
heap, a Varka kernel or a Varka arm's position. The 22.6% that opened task 97,
and the 27.3% on the same entry here, are draws from this distribution rather
than events with causes of their own - four independent measurements of
`date_add(d, 3)` over the identical fixture read 1780.6, 1763.5 and 1783.7,
and the committed 1401.4.

**What to do about it.** `dev/varka_bench_repeat.sh` and
`dev/varka_bench_band.py` already answer exactly this question per case, and
`band.py`'s own documentation records the finding that makes a band worth
committing: over ten runs, *which* cases are noisy reproduces strongly - 37 of
52 in the worst quartile - while *how* noisy a given case is reproduces only
weakly. Neither has ever been pointed at the surface. Doing so gives the
surface a band file, lets `dev/varka_bench_diff.py` stop reporting a
single-entry move inside the band as a change, and decides what the README does
with its per-entry rows.

**What this does not mean.** The aggregate figures are unaffected and were
confirmed by this session: four arms, two days apart, reproducing their
committed medians to within 0.2%. The rule this yields is narrow - do not quote
a *single entry's* ratio without a band behind it - and it is not a licence to
re-run a benchmark until a number looks better.


### 2.37 `TIME` expressions over the long lane (task 102)

*Opened 15 September 2026 by the re-scope; the milestone's subject.*

`TimeType(p)` stores nanoseconds since midnight in a long whatever `p` is - the
precision changes casting and formatting, not the lane - so every `TIME` value
lies in `[0, 86 399 999 999 999]`, below 2^47. Two things follow. The lane is task
29's long lane with nothing added, and every division a `TIME` expression needs -
by 3 600 000 000 000 for the hour, 60 000 000 000 for the minute, 1 000 000 000
for the second, and the truncation levels - is exact through 2.19's double-lane
division, by the bound in 1.1: the computed quotient's error is under `v * 2^-52`
and the gap between a non-integer quotient and the integer above it is at least
`1/d`, so `floor` cannot cross an integer while `v < 2^52`, and `TIME`'s `v` is
thirty times smaller than that. Task 29's "or a recorded decline" does not apply
here; it applies to intervals.

**The expressions Spark has, and what each is in the lane.** `HoursOfTime`,
`MinutesOfTime`, `SecondsOfTime` are a division and a `floorMod`, the second with
`SecondsOfTimeWithFraction` returning a decimal that this milestone declines.
`MakeTime(h, m, s)` is the reverse: two multiplies and adds under the range check
that `make_date` already has for dates, with the fractional-second argument a
decimal and therefore declined unless it is a literal. `TimeTrunc(level, t)` is a
division and a multiply at a level that must be foldable, `trunc(d, fmt)`'s rule.
`TimeAddInterval(t, dt)` is `t + micros * 1000` under a range guard, because
vanilla's `timeAddInterval` does **not** wrap: it is `addExact` and a check that
the result lies in `[0, 24h)`, throwing otherwise (`timeAddIntervalOverflowError`;
SPARK-57853 is open on whether that becomes ANSI's modulo-24). So the lowering is
`make_date`'s pattern - the guard fails the batch into the ghost fallback, which
raises the same error, or returns null under `TRY` - and it changes to a
`floorMod` by `NANOS_PER_DAY` the day 57853 says so. `SubtractTimes(t1, t2)` and
`TimeDiff`
produce a day-time interval in microseconds, a division by 1000 that is again
exact by range. `TimeFromSeconds/Millis/Micros` and `TimeToSeconds/Millis/Micros`
are multiplies and divisions by powers of ten. Comparisons, `IN`, `CASE WHEN`,
`coalesce`, `greatest` and `least` arrive through the generic arms once the
compiler admits a long-lane column where it admits a date one. Out: `ToTime`
(string parsing), `CurrentTime` (foldable anyway), and `DateFormatClass` over
`TIME` (a string result).

**The admission check, first - task 116.** The serializer's stats switch has
`LongColumnStats` arms for both `TimeType` and `DayTimeIntervalType` (an earlier
draft here said it did not; 1.1 records the correction), and upstream's
[SPARK-54203](https://issues.apache.org/jira/browse/SPARK-54203) put `TimeType` through `RowToColumnConverter`. What no test
has done is cache a `TIME` column under `spark.sql.cache.serializer` set to the
Arrow serializer, read it back, and map its buffer through the morsel as
eight-byte lanes at the declared precision. Task 116 is that test, for both
types, and nothing else in this section is built until it passes.

**Coverage.** `VarkaCoverageSuite` enforces that every Catalyst class the
compiler matches is documented, so the new arms cannot land undocumented; the
suite's column list gains `t` and `t2` (`TIME`), `l` (`bigint`) and `dt`
(`INTERVAL DAY TO SECOND`) at the same time, and `sql/varka/coverage.json` grows
the families with it.

### 2.38 Day-time interval expressions (task 103)

*Opened 15 September 2026 by the re-scope; absorbs task 39.*

`DayTimeIntervalType(start, end)` is microseconds in a long across the whole
int64, which makes it the lane's harder type: no range bound comes free, so a
division is exact through 2.19 only where a bound proves the operand below 2^53,
and is otherwise the recorded decline task 29 anticipated. The arithmetic that
needs no division is the bulk of it: `+`, `-`, negate and `abs` (the year-month
interval's arms at the other width), comparisons, and `MultiplyDTInterval` by an
int literal or a bounded column with the same checked-multiply rule int32 has.
`DivideDTInterval` is division and follows the bound. `ExtractANSIIntervalDays/
Hours/Minutes/Seconds` are divisions by 86 400 000 000, 3 600 000 000, 60 000 000
and 1 000 000, and every one of them needs the bound: in 2.19's reciprocal form
the floor is exact while the operand is under 2^52 microseconds, about 52 000
days, so even the days extract is exact only below that - most intervals a
query holds, and not all of them.
`MakeDTInterval` is multiplies and adds under overflow checks.

**Task 39 lands here.** `date - date` is Catalyst's `SubtractDates`, and its result
is a day-time interval: int32 days in, `days * 86 400 000 000` out in a long. It
is the milestone's first mixed-width kernel exactly as `PLAN_TASK_39.md` says, and
what it needs from 28 (the width conversion) and 29 (the long lane) is unchanged;
what changes is that it is one row of this family rather than a task of its own,
because `DateAddInterval` (a date plus a whole-day interval; anything finer
becomes a timestamp and declines), `TimestampAddInterval` and
`SubtractTimestamps` are the same kernel with the operands swapped around.

### 2.39 `Long` arithmetic, task 30's int64 half (task 104)

*Opened 15 September 2026 by the re-scope.*

Task 30 was narrowed on 4 September to the int32 forms and shipped them in task
63; what it deferred was the int64 half, and the long lane makes it due: checked
and wrapping `+`, `-`, `*` and negate over `bigint` columns and literals under the
same value-range lattice (2.15), comparisons, and `Cast` between int and long as
2.2's conversion. `div`, `%` and `pmod` are divisions and take 2.19's rule - exact
under a proven bound, declined otherwise - which for `bigint` columns with no
statistics means declined until task 64's statistics-directed bounds return from
milestone 6. `/` is a double and stays out with item 3.

### 2.40 The `TIME` benchmark: its own class and its own files (task 105)

*Opened 15 September 2026 by the re-scope; the number the message quotes.
Upstream's [SPARK-57562](https://issues.apache.org/jira/browse/SPARK-57562) ("Add benchmarks for the TIME data type") is
open, and milestone 6's item 8 already argues for extending Spark's benchmarks
rather than only writing our own - so the inventory built here is shaped to be
offered there as well.*

A new feature family gets its own benchmark class and its own committed results
files, not rows in `DateSurfaceBenchmark`: `TimeSurfaceBenchmark` in
`sql/varka/bench`, with a `Surface`-shaped inventory of one entry per `TIME` and
day-time interval expression 2.37 and 2.38 admit, the projection and the filter
form of each, over a table of `TIME`, `bigint` and interval columns generated
the way `varka_dates` is. Both arms run with `spark.sql.timeType.enabled=true`;
the stock arm is the same 4.2.0 distribution the date surface uses, which
carries the type and the functions. Run through `dev/varka_bench_surface.sh`
with a `--benchmark time` selector beside `surface` and `chains`, so the
canary, the datapath probe, the fixed-share rule and task 100's guard all apply
unchanged; `TimeChains` follows once the single expressions are measured - with one
expectation registered now. The date chains reached `MIN_OPS = 280` because every
calendar field pays the 31-op civil-from-days prefix and the chains compose four
of them; a `TIME` field is a division - four or five ops in either lowering - so
`hour(time_trunc('MINUTE', t + INTERVAL '90' MINUTE))` is perhaps twenty ops and
nothing over `TIME` reaches the compute-bound regime the date chains live in. The
`TIME` story's number is the surface's, not a chain's, and `TimeChains` exists to
show the composition works rather than to set the headline.

**What the stock arm is made of, and the prediction that forces.** Vanilla
Spark's `TIME` field extraction goes through `java.time`: `getHoursOfTime(nanos)`
is `nanosToLocalTime(nanos).getHour`, `getMinutesOfTime` and `getSecondsOfTime`
likewise, `timeTrunc` builds a `LocalTime`, truncates it and converts back, and
`makeTime` constructs one - each a `StaticInvoke` per row, each allocating. The
rest is integer arithmetic already: `timeAddInterval`, `subtractTimes`,
`timeDiff`, `getSecondsOfTimeWithFraction` and the `time_to_*` / `time_from_*`
family. So the surface will split in two: rows where Varka beats an allocation,
and rows where it beats arithmetic - and the honest message quotes them apart. The date family's baseline was integer arithmetic; this
one is object construction. So the registered prediction, written before any
`TIME` kernel exists: **the `hour(t)`/`minute(t)`/`time_trunc` rows read a larger
ratio than `year(d)` did, and most of the difference is the baseline's
allocation, not the lane.** The message says so, the way the README explains
the date surface's 38x as per-row overhead rather than arithmetic - and the
honest companion number is the ratio against the fork with the engine off, which
runs the same `LocalTime` path and isolates what Varka adds. Whether vanilla
should compute those fields in integer arithmetic is upstream's question
(section 8's rule), and worth a ticket from whoever benchmarks it first; until
then the plan records that a chunk of the `TIME` ratio is vanilla's to close.

**Before the message, the band.** Task 99 showed one entry in every arm of a
surface landing 8% to 27% from its committed value with stock as exposed as
Varka, and task 101 exists to give the surface a band. The `TIME` surface is
measured under that band from its first regeneration, and the message quotes a
median and a banded range, never a single row.

### 2.41 The quote check runs in no workflow (task 106)

*Opened 15 September 2026, found while closing task 94.*

`dev/varka_quote_check.py` is the guard behind "every number the documents quote
traces to a committed results file", and no CI job runs it: its guarantee rests on
whoever ran it locally - PR #208 (task 99) merged its numbers on the strength of
one local run, and said so. Task 94 built the pattern - a module entry in
`dev/sparktestsupport/modules.py` and a small job gated on it - and the same
pattern covers this: a `varka-docs` module over `sql/varka/plans/`,
`sql/varka/skills/`, `docs/sql-varka.md`, `README.md` and `SKILLS.md`, whose job
runs the quote check and `dev/varka_toc.py --check SKILLS.md --from
sql/varka/skills`. Minutes, no Spark build, and the first documentation-only
pull request after it proves the skip half of task 94 in passing.

### 2.42 `time_add(unit, quantity, time)` (task 107) - withdrawn

*Opened and withdrawn on 15 September 2026: vanilla Spark's row-engine work, which this milestone does not take - it keeps vectorised expressions and benchmarking. Upstream: no upstream ticket; the wrap rule is [SPARK-57853](https://issues.apache.org/jira/browse/SPARK-57853)'s open question. Section 8 has the survey; the heading stays so citations resolve.*

### 2.43 `try_make_time` (task 108) - withdrawn

*Opened and withdrawn on 15 September 2026: vanilla Spark's row-engine work, which this milestone does not take - it keeps vectorised expressions and benchmarking. Upstream: [SPARK-57846](https://issues.apache.org/jira/browse/SPARK-57846). Section 8 has the survey; the heading stays so citations resolve.*

### 2.44 `sequence()` over `TIME` (task 109) - withdrawn

*Opened and withdrawn on 15 September 2026: vanilla Spark's row-engine work, which this milestone does not take - it keeps vectorised expressions and benchmarking. Upstream: [SPARK-57852](https://issues.apache.org/jira/browse/SPARK-57852). Section 8 has the survey; the heading stays so citations resolve.*

### 2.45 `avg` over `TIME` (task 110) - withdrawn

*Opened and withdrawn on 15 September 2026: vanilla Spark's row-engine work, which this milestone does not take - it keeps vectorised expressions and benchmarking. Upstream: [SPARK-58044](https://issues.apache.org/jira/browse/SPARK-58044). Section 8 has the survey; the heading stays so citations resolve.*

### 2.46 Avro `TIME` (task 111) - withdrawn

*Opened and withdrawn on 15 September 2026: vanilla Spark's row-engine work, which this milestone does not take - it keeps vectorised expressions and benchmarking. Upstream: not a gap - present by [SPARK-54473](https://issues.apache.org/jira/browse/SPARK-54473) and [SPARK-57581](https://issues.apache.org/jira/browse/SPARK-57581), under `sql/core`'s Avro code; the row was opened on a grep of `connector/avro` and withdrawn when the umbrella showed it done. Section 8 has the survey; the heading stays so citations resolve.*

### 2.47 `DATE + TIME` as an operator (task 112) - withdrawn

*Opened and withdrawn on 15 September 2026: vanilla Spark's row-engine work, which this milestone does not take - it keeps vectorised expressions and benchmarking. Upstream: no upstream ticket; the function form is [SPARK-53579](https://issues.apache.org/jira/browse/SPARK-53579). Section 8 has the survey; the heading stays so citations resolve.*

### 2.48 The survey of what else vanilla Spark lacks for `TIME` (task 113) - withdrawn

*Opened and withdrawn on 15 September 2026: vanilla Spark's row-engine work, which this milestone does not take - it keeps vectorised expressions and benchmarking. Upstream: the umbrella [SPARK-57550](https://issues.apache.org/jira/browse/SPARK-57550); the lines and their tickets are section 8's table. Section 8 has the survey; the heading stays so citations resolve.*

### 2.49 `time_bucket` over `TIME` (task 114) - withdrawn

*Opened and withdrawn on 15 September 2026: vanilla Spark's row-engine work, which this milestone does not take - it keeps vectorised expressions and benchmarking. Upstream: [SPARK-54507](https://issues.apache.org/jira/browse/SPARK-54507). Section 8 has the survey; the heading stays so citations resolve.*

### 2.50 `time_format` (task 115) - withdrawn

*Opened and withdrawn on 15 September 2026: vanilla Spark's row-engine work, which this milestone does not take - it keeps vectorised expressions and benchmarking. Upstream: [SPARK-54588](https://issues.apache.org/jira/browse/SPARK-54588). Section 8 has the survey; the heading stays so citations resolve.*

### 2.51 A `TIME` column through Varka's Arrow cache, proven (task 116)

*Opened 15 September 2026 at the owner's request, from 1.1's admission check.*

The pieces exist upstream and in the fork - `isSupportedByArrow` admits the
type, the cache serializer's stats switch has its `LongColumnStats` arm,
[SPARK-54203](https://issues.apache.org/jira/browse/SPARK-54203) put it through `RowToColumnConverter`,
[SPARK-57661](https://issues.apache.org/jira/browse/SPARK-57661) preserves its precision across the Arrow boundary - and no
test composes them along Varka's path. The task is that test, in `sql/core`'s
Varka suites: a `TIME(p)` column for p in {0, 3, 6, 9} and a day-time interval
column cached under `spark.sql.cache.serializer` set to the Arrow serializer,
read back equal, and the batch's buffers mapped through the morsel as
eight-byte lanes with the validity word right at every null pattern the date
fixtures use. Two facts from reading the path on 15 September shape the test.
The serializer writes the Arrow schema with `losslessInternalTypes = true`, so
the precision rides in field metadata under `SPARK::time::precision`; but the
*read* side rebuilds column vectors from the Spark schema
(`DataTypeUtils.fromAttributes`), not from that metadata - so precision reaches
Varka through Catalyst's `DataType`, and the test asserts it on the output of
`time_trunc` at each `p`, where a wrong `p` would show. And
`VarkaKernelEvaluator`'s output allocation switches on `DateType`,
`IntegerType` and `YearMonthIntervalType` only (line ~1087): the `LongType`,
`TimeType` and `DayTimeIntervalType` arms are task 29's, and this test is what
they are written against. It passes before any long-lane code is written, or it
fails and becomes the milestone's first fix; either way it is known rather than
assumed.

### 2.52 Sync the fork with `apache/spark` master (task 117) - the milestone's first task

*Opened 15 September 2026, found while checking the umbrella; withdrawn with the
vanilla rows for an hour, then made **the first task of the milestone** on the
owner's instruction - a sync is Varka's own infrastructure, not vanilla Spark's
feature work, and everything after it merges more easily from a tree that
matches upstream.*

`origin/master` is 375 commits behind `upstream/master` and 209 ahead of it.
One of the missing commits is `TIME` work - [SPARK-53368](https://issues.apache.org/jira/browse/SPARK-53368), reading Parquet
`TIME` written with `isAdjustedToUTC`, is upstream and not here (1.1 has the
count) - and a milestone that ends in a message
about tracking vanilla Spark should not be a month behind it, and the record in
section 8 is easier to act on from a tree that matches. There is a second
reason: the fork's CI already tests the branch *merged with upstream master*
(the `Auto-merging` lines at the top of every Precompile log), so for a month
the tree CI has tested has not been the tree in the repository - which is how an
upstream test that exists nowhere in this repository has been failing every
pull request. After the sync, local and CI test the same tree. The task is the
merge and the gate green after it. A dry run on 15 September - `git merge-tree
--write-tree origin/master upstream/master` - reports **no conflicting file**
across the 375 upstream and 209 fork commits: the `Auto-merging` lines CI prints
are clean three-way merges of `modules.py`, the workflows and `AGENTS.md`, not
conflicts. The same day the merged tree was built and run as a scratch commit:
`build/sbt catalyst/Test/compile sql/Test/compile` in 276 seconds, then the
gate's `wide` step - every `*Varka*` suite in catalyst and `sql/core` at the
host width - in 199 seconds, **297 and 183 tests succeeded, 0 failed**, the ten
canceled being the opt-in sweep and JFR cases that cancel on master too. So the
task is an afternoon of gate rather than days of resolution, and its real risk
is the behavioural one below: whether a committed Varka number moves, which only
a regeneration under the canary answers. Two things it can move: the committed
Varka numbers, since both fork arms run on the merged tree while the stock arm
is a fixed 4.2.0 distribution - so the canary runs and the requote rule applies
if a surface has to be regenerated - and the `TIME` functions themselves, if
upstream changed one, which is why 117 precedes 102 rather than following it.
Infrastructure, and the first thing the milestone does: before 84 opens, so
that the lane work, the `TIME` rows and the benchmark are all built on the tree
the message will be about.



### 2.53 The closing task: final benchmarks, the README, and the post (task 118)

*Opened 15 September 2026 on the owner's instruction: the milestone ends the way
milestone 4 did, with task 62's shape.*

Task 62 closed milestone 4 in three parts - (A) the plan of the measurement,
(B) the measurement itself on a runner proven full-width by the datapath probe,
(C) the README written from those files - and the LinkedIn post followed from
(C). This task is the same three parts for the `TIME` story, plus the post,
because the milestone's stated exit is the message and nothing before this task
produces it.

**(A) The plan of the measurement.** Which arms: stock 4.2.0 on JDK 17 and JDK
25 with `spark.sql.timeType.enabled=true`, the fork with the engine off, and
Varka - the four the date surface has, so the two tables read alike. Which
benchmarks: the `TIME` surface (105) and, if it exists by then, `TimeChains`;
and the date surface and chains again only if task 117's merge or anything since
is suspected to have moved a committed number, decided by the canary and a
`dev/varka_bench_diff.py --git` against the committed files rather than by
assumption. Which machine: the same rule as 62 - a runner the datapath probe
proves full-width at 512 bits (`require-datapath=512` through
`varka-surface-benchmark.yml`, the Zen 5 lottery), because that is where the
lane story is true without qualification; the laptop's double-pumped 1.14 is
recorded beside it as the other end of the range, as it was for dates.

**(B) The measurement.** Under task 101's band from the first regeneration, so
every per-entry figure carries its tier and no single row is quoted bare; the
fixed-share rule met; provenance complete (CPU, flags, datapath, canary, rows,
table shape, both flags' values). Two numbers the message needs that the date
close did not: the split of the surface into rows where Varka beats an
allocation (`hour`, `minute`, `second`, `time_trunc`, `make_time` - vanilla's
`LocalTime` path) and rows where it beats arithmetic (the rest), reported apart;
and the engine-off ratio beside the stock ratio on every row, which is what
isolates Varka's part from vanilla's baseline.

**(C) The README and the docs.** The benchmark section gains the `TIME` table
beside the date ones - one row per expression, the query text beside the
number, losses printed beside wins, the median and the banded range leading and
the per-row figures under them - and the "which figure generalises" paragraph
says plainly that the allocation rows measure vanilla's baseline as much as
Varka's lane. The reproduction guide gains the flag on both arms and the
`--benchmark time` selector. The coverage table and `coverage.json` are already
current by construction (`VarkaCoverageSuite`), so (C) checks them rather than
edits them. "Reading the source" gains the long-lane entry points.

**The post.** Ideas first, numbers last, the shape the milestone 4 post took:
what a 64-bit lane is in this engine and why one lane serves five types; why
`TIME` is the type to show it on (a new type, about to be on by default, whose
vanilla implementation allocates per row); the honest split of the ratio; and
the median, never the best row. A short and a long variant, as before; the
short one is what gets posted, the long one is the blog. The draft lives in a
file the way `LINKEDIN_POST_VARKA.md` did, quotes only committed figures, and is
checked by `dev/varka_quote_check.py` like every other document before it is
pasted anywhere.

**Done when** the four results files per benchmark are committed with the band,
the README quotes them and nothing else, the quote check is at zero orphans,
and the two post drafts exist and name their sources. The message itself is the
owner's to send.

### 2.54 The oracle for the long lane: reference evaluator and fuzzer at `long` (task 119)

*Opened 15 September 2026, from the review of what task 29's validation assumes.*

`VarkaReferenceEvaluator` is `Option[Int]` end to end - `row: Seq[Option[Int]]`,
`lits: Array[Int]` - and `VarkaIrFuzzSuite`'s grammar generates int32 trees only.
Task 29's validation says "every parity gate re-run at the long species", and
nothing says who teaches the *oracle* long semantics. Without this, the fuzzer
covers the new lane not at all and the differential against the row engine is
the only oracle - the situation task 63's review found bugs in. The task: the
reference evaluator over `long` lanes including the exact-division nodes (2.19,
in both lowerings), the range guards, `TIME`'s day-modular arithmetic and the
interval overflow checks; the fuzz grammar generating long, `TIME` and day-time
interval trees over random null patterns, lengths and `VarkaEmitOptions`
variants; and the fuzzer asserting it reaches every long-lane node type, as it
does for int32, so a node the generator cannot build is a gap it reports rather
than one it silently skips. Sequenced with 29: the evaluator arms land with the
nodes they check.

### 2.55 The coverage table as a differential corpus (task 120)

*Opened 15 September 2026, from the review of what the published table proves.*

`VarkaCoverageSuite` proves every row of the coverage table *compiles*;
`VarkaDifferentialSuite` (96 tests) never reads `coverage.json`, so the rows a
reader trusts are not the corpus the differential checks. One `sql/core` suite
that runs every `coverage.json` row through both engines over the null-pattern
fixtures - projection form and predicate form as the table says, columnar and
row consumer - closes that, and it inherits every future row automatically: a
new arm cannot be documented without being differentially tested, because the
coverage suite forces the row and this suite runs it. Cheap, starts today on the
57 int32 rows, and grows with 102 to 104 without a further change. It does not
replace the differential suite's hand-chosen shapes; it is the floor under them.

### 2.56 The AVX2 arm: the `TIME` surface under `-XX:UseAVX=2` (task 121)

*Opened 15 September 2026, from section 6's owed timing.*

Section 6 records that C2 does not intrinsify the long-to-double casts under
AVX2 and that the magic-number conversion is the vectorised answer there, and
that the one thing 2.19 still owes is a timing: the magic form's cost against
the native casts. The CI pool's Zen 3 runners are AVX2-only, so half the
machines the benchmark workflow lands on take the second lowering. This task is
that measurement, as its own committed companion files the way the date
benchmarks have their 128-bit ones: the `TIME` surface (105's inventory) run
under `-XX:UseAVX=2` on the laptop, and on a Zen 3 runner through
`varka-surface-benchmark.yml` with `require-datapath=any`, which lands there most
dispatches; the datapath probe and CPU flags in the provenance so a reader can
tell which lowering a file measured. The decision it feeds: one lowering
everywhere if the magic form is within the band of the native casts at AVX-512,
two lowerings selected by `UseAVX` if it is not. Task 118 quotes the AVX2 file
beside the full-width one, so the message does not quote a Zen 5 number for a
path that is a different lowering on the machines most readers have.

## 3. Task breakdown

The rows as milestone 4's table carried them, task numbers unchanged. *(The order
in this paragraph is the 4 September one; the paragraph after it has the order
that applies since 15 September.)* 28 opens
the milestone; 29 and 30 follow it; 39 and 49 wait on 29; 27 can run at any
point; 65 waits on nothing and is an admission check before it is a task; 66
follows task 32's B2 grouping decision; 74 and 75 follow #145 (task 70) and
nothing else, 75 being an admission check before it is a task; 81 follows 74.
83 to 86 are the engine refactors task 63 argued for (section 2.13), ordered by how
far the decision each touches sits from an exhaustive match rather than by size: 84
before 85, because the lattice is what makes a new lane safe and the lane parameter
only makes one possible - **the owner confirmed that order on 8 September 2026**,
against the alternative of taking 85 first to unblock tasks 28 and 29 sooner and
following it with 84, which would have ported today's two bound functions into a
third and let the next lane inherit the bugs task 63's review found. 83 and 86 are
independent of both and of each other.

**After the 15 September 2026 re-scope** (section 1.1) the order that matters is
the long-lane spine: 117 first, then 84, 85, then 29 with 28 and 92 beside it,
then 88, then 102, 103 and 104, then 105, with 101 before anything from 105 is
quoted. 116 comes before any lane code. Rows marked
*moved to milestone 6* are kept in this table so the numbers and the citations
into their design sections stay valid; their text is unchanged and
`SCOPE_MILESTONE_6.md` item 15 has the reason for each.

### 3.1 The dependency graph, and the order it sorts to

*Built 15 September 2026 from the rows' own "after / blocked on / before" text
and 1.1's spine; every edge below is stated in a row or in 1.1, none is
inferred.* An arrow reads "must land before".

    117 sync ----+
                 +--> 84 lattice --+--> 85 lane param --+--> 28 width conv ---+--> 39 date-date --+
    116 cache ---+                 |                    |                     |   (closes w/ 103) |
      proof      |                 +--> 91 guard bound  +--> 29 long lane ----+--> 88 division ---+--> 102 TIME exprs --+
                 |                                      ^                     |                   |                     |
                 +--------------------------------------+                     +--> 104 Long arith |--> 103 DT exprs ----+--> 105 TIME bench --> 121 AVX2 arm --> 118 close
                                                                                  (closes 30)     |                     ^
                                                                                                  +--> 89 YM divisions  |
    101 band -----------------------------------------------------------------------------------------------------------+

    beside the spine:  119 long-lane oracle (lands with 29)   120 coverage differential (starts now)
    no predecessor, any time:  106 quote check in CI   83 one refusal   86 one admission (fold into 85)
                               92 validity at 4 lanes (land with 29)    95, 96 int32 gaps
                               81 differential corpus (its TIME half after 102)   90 -> absorbed by 101

Sorted by dependency, independent tasks first (a wave holds tasks with no edge
between them; within a wave the order is free):

| wave | tasks | why they wait |
| ---: | :--- | :--- |
| 0 | **117**, **116**, 101, 106, 120, 83, 86, 92, 95, 96, 81 | nothing - 117 and 116 are first by decision, the rest by independence; 120 starts on the int32 rows and grows |
| 1 | 84 | after 117: the lattice is built on the merged tree |
| 2 | 85, 91 | after 84: the lane parameter and the guard bound both take the lattice's interval type |
| 3 | 28, 29, 119 | after 85 (its row: 85 blocks both); 29 also after 116; 119 lands with 29, whose nodes it checks |
| 4 | 88, 104, 39 | 88 at the long lane needs 29; 104 needs 29, 84 and 28's cast; 39 needs 28 and 29 |
| 5 | 102, 103, 89 | 102 and 103 need 29 and 88 (and 103 absorbs 39); 89 needs 88 |
| 6 | 105 | after 102 and 103, under 101's band |
| 7 | 121 | after 105: the same inventory under `-XX:UseAVX=2`, the timing 2.19 owes |
| 8 | 118 | last by definition: the final measurement, the README and the post need every number above it committed, 121's AVX2 file included |

Three rows are not nodes of their own: 30 closes when 104 does (its int32
half shipped in task 63), 39 closes when 103 does, and 90 is absorbed by 101.
The critical path is 117 -> 84 -> 85 -> 29 -> 88 -> 102 -> 105 -> 121 -> 118,
nine tasks deep, and it is serial: each of those needs the previous one's code. The ten
wave-0 tasks are what fills the time while it runs, and three of them belong
*with* a spine task rather than merely before it: 86 folds into 85 (the same
code), 92 lands with 29 (its default only matters once four-lane vectors
exist), and 101 has to exist before 105 quotes anything. 81's date corpus can
start at once; its `TIME` half waits for 102.

**The first week, read off the graph:** 117 and 116 in parallel - one is a
merge, the other a test, and they touch nothing in common - with 106 and 101
alongside, since neither touches the engine; then 84 the moment 117's gate is
green. Nothing on the critical path can start before that, and everything that
can start has.


| # | Task | Deliverables | Validation |
|---|---|---|---|
| 25 | **Moved to milestone 6** (15 September 2026, `SCOPE_MILESTONE_6.md` item 15): int32 tuning with a harness to re-establish first. ILP: the unroll factor as a plan decision (section 2.24). **Not started** and **moved from milestone 4** (11 September 2026), where nothing waited on it: its harness stopped measuring a degraded JIT state with PR #105, so its first job is re-establishing what it measures rather than measuring | The registered prediction, then the three-confounder matrix (K x broadcast strategy x `GROUP_BUDGET`) on `dayofweek`, unpredictable `CASE WHEN`, and the depth-8 chain; if K > 1 pays, per-shape K chosen from the live-temporary count the emitter already computes; the `SKILLS.md` bullet rewritten with the numbers; the batch-size knee sweep (question 6) on a wide fused shape | A committed number per candidate shape against its existing baseline; prediction scored honestly; no committed number regresses on shapes where K stays 1 |
| 27 | **Moved to milestone 6** (15 September 2026, `SCOPE_MILESTONE_6.md` item 15): a projection output the TIME message does not need; the borderline call, see 1.1. Boolean outputs | Mask-to-column materialisation (`toVector` against `blend`, measured); the bit-packed format decision at the Spark/Arrow boundary; three-valued rules holding at the output boundary | Differential over every null pattern - a null input never becomes false; `SELECT d > DATE '2000-01-01' AS flag` and filter-leftover boolean columns compile; committed number on one boolean-output shape |
| 28 | Lane-width conversion | The mixed-width loop-shape measurement (open question 2: narrowest-drive against part loops) on `cast(int AS long) + long`, committed before integration; `convert`/`convertShape` emission following the winner; numeric `Cast` and Catalyst's implicit promotions over the supported types | Differential on mixed int32/int64 trees at both widths; the loop-shape decision recorded with its numbers; no regression on single-width shapes |
| 29 | The long lane: `bigint`, the timestamps, day-time intervals and `TIME`. **Widened** (15 September 2026; was int64 lanes: `TimestampNTZ`, `bigint`) - the lane is one because all five are `PhysicalLongType`; the expressions over the two new types are tasks 102 and 103 | The second `LaneType`, serving all five `PhysicalLongType` types (widened 15 September 2026; the `TIME` and interval expressions themselves are 102 and 103); `TimestampNTZ` comparisons, differences, literal arithmetic; `TimestampType` and `LongType` comparisons and diffs; the division rule from 2.19 under its bound, replacing "range-narrowed magic constants for 1000000 and 86400 or a recorded decline"; the field differential mode from task 22 | Every parity gate re-run at the long species and both vector widths; the halved-headroom number committed rather than discovered; zoned operations demonstrably declined, not wrong |
| 30 | ANSI integer arithmetic - **narrowed on 4 September 2026**: the int32 add, subtract, multiply and negate over fused fields, int columns and literals, with the ANSI overflow decline and the `try_*` validity form, moved into milestone 4 as task 63, **which shipped** (`PLAN_TASK_63.md` 9); what stays here is the rest | `/` (a double), `div` (task 29's long lane), `%` and `pmod` with the divide-by-zero rule, the int64 forms, and `Multiply` overflow through 28's widening where task 63's saturating check is not enough | The error-identity differential: same `SparkException`, same row, as the row engine under ANSI; `try_*` differential over overflow-dense and overflow-free data; committed number on the no-overflow path against Janino |
| 39 | `date - date`. **Planned** (`PLAN_TASK_39.md`), blocked on tasks 28 and 29; **retargeted** (15 September 2026): `SubtractDates` yields a day-time interval, so it lands as a row of task 103 rather than a kernel of its own, its recipe unchanged, and this row closes when 103 does | The node, the int32-to-int64 conversion, the eight-byte output, and both overflow tests routed through task 26's decline channel rather than task 30's throw path; the legacy `CalendarInterval` variant declining. The int-to-long step is the two-part `convertShape` from the preferred int species, never a load through a half-width int species: two species of one lane type in one JVM turn the shared `IntVector` templates bimorphic and C2 keeps a heap box per loop iteration (`SKILLS.md`, "Every operator the plans rely on"), and the lane-width "tie" in `VarkaMilestone4MeasurementsBenchmark-jdk25-results.txt` was measured in exactly such a JVM | The overflow boundary exact in both directions (106751991 succeeds, 106751992 declines); Varka's exception identical to the row engine's, compared by running both; `datediff` unaffected; green at both widths, where an int64 lane holds a different number of rows |
| 49 | **Moved to milestone 6** (15 September 2026, `SCOPE_MILESTONE_6.md` item 15): date-algorithm precision in long lanes. Exact civil-from-days in long lanes. **Planned in section 2.6** (PR #69; there is no `PLAN_TASK_49.md`), blocked on task 29 | The admission check first, over all 2^32 days against a long-arithmetic reference: exact magic division with a 64-bit low product and no correction carries, run for **both** decompositions - the three-division era/century/year form (146097, 36524, 365) and task 54's two-division Julian map (146097 on `4 * d + 3`, then 1461), which Ben Joffe's `fast64` shows reaching four multiplies for the whole date where Neri-Schneider needs seven; then the lowering, and the guard, the decline path, the `NARROWED` variant and `VarkaChrono`'s range constants removed with it. Verified before starting (`SKILLS.md`, "Every operator the plans rely on"): `LongVector.mul` by a constant compiles to one `vpmullq` on this CPU (AVX-512DQ with VL), not the three-multiply emulation plain AVX2 gets, and unsigned long compares are one `vpcmpuq` into a k-mask. Plan B if the 0.75x gate fails: Joffe's bucket technique for a guard-free int-lane total - `bucket = (d + 2^31) >>> 20`, reduce by `bucket * 1022679`, add `bucket * 2800` to the year - about 14 ops against task 26's `TOTAL` at 16 and without the deliberate wrap; his `article_2_l1` variant replaces two of those multiplies with an eight-entry offset table, one lane permute on a 256-bit int species | The exhaustive sweep as a committed opt-in test, at both widths; the parity `year` case measured against the shipped narrowed lowering in one run; declined on the record if the sweep disagrees anywhere or AVX-512 costs more than 0.75x |
| 64 | **Moved to milestone 6** (15 September 2026, `SCOPE_MILESTONE_6.md` item 15): int32 guard tuning. Statistics-directed guard selection (section 2.28). **Planned** (`PLAN_TASK_64.md`, requoted 11 September 2026) and **moved from milestone 4** the same day: the surface task 62 measures carries no shape that pays task 52's producer guard, so this task cannot change a number the public table shows | Step one: the evaluator runs `IntRangeOps.allWithin` over a guarded producer's offset column against `[NARROW_MIN_DAYS - CONTRACT_MIN_DAYS, NARROW_MAX_DAYS - CONTRACT_MAX_DAYS]` and picks the unguarded or the guarded kernel from the shape cache per batch; step two: the Arrow cache's per-batch column bounds attached to the `ColumnarBatch` and read before the pass, answering task 56's bound too; each behind a switch | The guarded kernel never runs on the differential's in-range fixtures and the far-offset fixtures still decline; the pass and the lookup priced against the guard on the parity `year(date_add(d, off))` pair, both widths, with a registered prediction that the null-free and mixed-null cost of task 52's guard is recovered; byte identity of the emitter |
| 65 | **Moved to milestone 6** (15 September 2026, `SCOPE_MILESTONE_6.md` item 15): a calendar algorithm. Joffe's `fast32` civil-from-days in int lanes. **Scoped in section 2.7** (5 September 2026); independent of 29 | The admission check first: the two source files transcribed into `sql/varka/papers` with reading notes; a committed script deriving a low-32-bit magic and its exact range per stage and sweeping the chain against `LocalDate`; the dependent-stage count against the prefix's. If admitted, an emit-option variant, the A/B beside the task 53 and 54 pairs at both widths, the register and the `HugeMethodLimit` ladder re-pinned, and the default chosen from the numbers | Exact over at least the narrowed range, or declined; a shorter dependent chain than the prefix's, or declined; the A/B at or above 1.0x at both widths, or the numbers go to the debt register |
| 66 | **Moved to milestone 6** (15 September 2026, `SCOPE_MILESTONE_6.md` item 15): a calendar refactor. Second-level chrono fragments. **Scoped in section 2.8** (5 September 2026); after task 32's B2 grouping decision | `FragmentKind`s for the year parts, the January month, the month start and `floorMod(d, 7)`, keyed and planned as the prefix is; emitted once per lane group, elided when no consumer in the group reads them; the register and the `HugeMethodLimit` ladder re-pinned; the A/B beside task 32's shared rows at both widths | The matrix and the whole-range sweep under a widened group budget over every pair and triple of calendar outputs; the byte identity of every single-field kernel; the gate in 2.8 (at or above 1.05x at AVX-512 on both shapes), or the register goes to the debt register |
| 72 | **Moved to milestone 6** (15 September 2026, `SCOPE_MILESTONE_6.md` item 15): calendar-prefix tuning. Output order for prefix affinity (section 2.25): `year(d), year(d2), month(d)` takes three loop methods where the adjacent order takes two | The admission check first - no consumer of a group depends on contiguous output indices - then a two-pass grouping that gathers a calendar output into the group whose prefix it reuses wherever that group is; the evaluator and the line map untouched | The pinned limitation in `VarkaLoopEmitterSuite` flipped to two methods; the pinned oracles unmoved; the permuted and adjacent orders within noise in the parity harness at both widths; the differential suite green with the two orders |
| 73 | **Moved to milestone 6** (15 September 2026, `SCOPE_MILESTONE_6.md` item 15): int32 guard-walk tuning. A stopping rule for the guard walk (section 2.26). **Planned** (`PLAN_TASK_73.md`), and its admission check is **done and overturns the premise of `PLAN_MILESTONE_4.md` 2.37** (this file's 2.26): the section expected no SQL shape to reach the over-guard, but `dayRange` bounds `make_date` from task 42's published years without looking at its children, so `year(make_date(2020, 1, dayofweek(date_add(d, off))))` fuses and the emitted kernel carries a guard the shape cannot need - 1274 to 1317 bytes in the masked loop. The task is therefore a fix rather than the decline 2.37 expected: a column-offset day producer is guarded on its own value even when a mod-7 node between it and the calendar node has already re-based the day (task 70's fuzz run; see the debt register) | The admission check first - whether any SQL shape observes the difference, given that `dayRange` returns `Unknown` for a mod-7 child and declines the entry at compile time before the emitter is reached, which can legitimately close the task with the finding recorded. If it does: a stopping rule on `collectColumnOffsetProducers` that descends only through nodes passing a day to the decomposition and stops at any node whose output is bounded in itself, and the matching rule in `dayRange`, taken together so the two analyses cannot drift apart again | The reproducer from the fuzz run served rather than declined at both widths (seed 20260907005 iteration 61379's shape, and the nine siblings substituting `weekday`, `dayofweek_iso` and `datediff`); the compiler suite's decline for `year(dayofweek(date_add(d, off)))` flipped to `fuses` if the compiler half moves, or the reason requoted if it does not; every guarded shape task 52 and task 60 pin still declining, since the rule may only remove guards a bounded node stands under; `VarkaIrFuzzSuite` at a million iterations per width with the `chronoBound` check relaxed to match, which is the oracle that found it |
| 74 | **Moved to milestone 6** (15 September 2026, `SCOPE_MILESTONE_6.md` item 15): a validity-pass optimisation. The validity-word algebra's missing axioms. **Scoped in section 2.9** (7 September 2026); after #145 | The coalesce axiom (`IfElse(IsNotNull(x), x, y)` denotes `x OR y`) and absorption in `pureOf`'s folding, behind task 70's switch; the census tool re-run through the emitter's own analysis rather than a mirror; the `coalesce(d, d2)` parity A/B pair | `coalesce(d, d2)` masked byte-equal to its dense twin and on its dense row at both widths; the differential over the nullable fixtures for two- and three-operand `coalesce`, `datediff(greatest(d, d2), d)` and `greatest(date_add(d, i), d)`; two million fuzz shapes with both extensions randomised; no other committed row moves |
| 75 | **Moved to milestone 6** (15 September 2026, `SCOPE_MILESTONE_6.md` item 15): an optimisation below its decline line. Zero-copy validity for leaf words. **Scoped in section 2.10**, and **the bound moved under it** (7 September 2026): task 70's third regeneration puts the masked-against-dense gap on `year(d)` at 0.4% at AVX-512 and below zero at 128-bit, under this task's own 2% decline line, so the zero-copy half is answered before the probe runs and what may survive is the cached null count, which that gap does not measure | The probe: masked `year(d)` with the copy skipped against the committed row, both widths. If admitted, the leaf case of the pass resolved to the input's validity buffer retained through Arrow's reference manager, a cached null count on Varka-owned output vectors, and the filter's compaction reading it | Under 2% at AVX-512 on the probe: declined on the record. Otherwise the differential over every null pattern with the output's validity address asserted equal to the input's, allocator accounting closing to zero with the retained buffers released, and the `year(d)` masked row on its dense row |
| 80 | **Moved to milestone 6** (15 September 2026, `SCOPE_MILESTONE_6.md` item 15): strings. String-column compaction that keeps the Arrow layout (section 2.27): a derived int32 leaf over a string column is refused per batch when a fused Varka filter sits under it, because the filter's compaction leaves the column on-heap (task 59's review; see the debt register) | The compaction writing offsets and data buffers rather than materialising rows, on task 21's `filterCompact` pattern; sized before milestone 6's item 3 puts string columns under filters and group keys | The stacked `next_day(d, s)` over a Varka filter counting no `numFallbackBatchesNonArrow` at all on task 59's own fixture, answers unchanged, and the fixed-width compaction's numbers not moving |
| 81 | Spark's own date tests as a differential corpus (section 2.11). **Scoped** (7 September 2026) | The harvest, from the golden-file inputs first - `sql-tests/inputs/date.sql` and its six date-family siblings, 254 `select` statements already written as SQL text - and from `DateFunctionsSuite` and `ColumnExpressionSuite` after them; `DateExpressionsSuite` excluded on the record, because `checkEvaluation` never reaches a physical plan. Then the rewrite that makes the corpus reachable at all: each statement's literal operands turned into columns of an Arrow-cached fixture, since 94 of `date.sql`'s 101 statements are constant-folded before any operator exists and the rest read one row of strings. Per entry: the answers compared against the row engine on the same fixture, and the plan classified fused, partial or declined on task 62's `Fusion` rule, so a declined entry cannot pass as a silent fallback | Every harvested entry agreeing with the row engine; the fused/partial/declined split committed as the coverage number, naming which expressions are out rather than a percentage; a harvest and rewrite that are re-runnable rather than a hand-copied list, so an upstream statement added later is picked up; and the limits stated in the plan - the golden `.sql.out` files stop being the oracle once operands become columns, and a handful of rows per entry reaches the epilogue and never a full lane group |
| 82 | **Moved to milestone 6** (15 September 2026, `SCOPE_MILESTONE_6.md` item 15): a checked-path micro-optimisation. The mask-to-long disposal in a checked kernel (section 2.12). **Scoped** (8 September 2026); reads task 63's committed numbers and shares its ground with task 64 | `emitGuardCollect`'s per-lane-group `VectorMask.toLong`, the AND with the node's word and the OR into the accumulator, which every runtime refusal shares - task 52's range guard, task 42's year check, task 60's month-count check and task 63's overflow check; the candidates are a mask-typed accumulator converted once per batch, skipping the AND where the algebra says the word is dead or all-ones, and hoisting the collect where the batch's statistics prove the mask empty | The checked mixed-null `i + 1` row at 128-bit (63.7% of the unchecked row today) moves by more than 10% while the dense rows and every unguarded shape's bytes do not, and task 52's guard pair in the parity file moves with it |
| 83 | One refusal, instead of four (section 2.14). **Scoped** (8 September 2026), from task 63's review; independent of 84 to 86 | The four runtime refusals - task 42's `make_date` year check, task 52's range guard, task 60's month count, task 63's overflow check - behind one node property carrying its mask, its qualifying word and its reason, with one analysis set, one slot rule, one collect and a status bit per reason, replacing `guardedProducers`/`selfGuarding`/`checkedArith`, the `guardedWord`/`guardScratch` pair and the shared `STATUS_CHRONO_RANGE` | No emitted byte moves for any shape that exists today: the pinned line map, the shape hash, every `codeSize` assertion and `dev/varka_emit.sh --table`'s op counts for `year(date_add(d, off))`, `add_months(d, m)`, `make_date` and ANSI `i + 1` all unchanged - this task buys a status bit and legibility, not speed |
| 84 | One value-range lattice (section 2.15). **Planned** (`PLAN_TASK_84.md`, 15 September 2026; first on the spine after 117). Originally scoped (8 September 2026), from the three bugs task 63's review found in the seam between `dayRange` and `intBound`; before 85 | One saturating interval domain over lane values, with the calendar admission (task 52) and the overflow check (task 63) as queries on it rather than two traversals, and "what a runtime guard proves" as an explicit parameter of a query rather than a fact baked into one traversal's arms; written in Java, being pure data | Every shape the compiler admits or declines today unchanged, decline reasons included, and the differential's fusion classification unmoved; plus the property test the current code cannot pass - over random IR, the interval a node reports contains the value the reference evaluator computes, for every lane pattern |
| 85 | Lane type as a parameter (section 2.16). **Scoped** (8 September 2026); after 84, and blocking milestone 5's own tasks 28 and 29 | The emitter parameterised on a lane descriptor - vector class, species, byte stride, load and store descriptors - against the 204 `INT_VECTOR` references, 16 four-byte stride assumptions and 18 species references it carries today; the lane on the node's physical representation rather than inferred from the Spark type, with year-month intervals (int32 months, the same lane as DATE and INT) as the forcing function that can land first; measured against a generated-per-lane emitter, since a descriptor risks a megamorphic call in the hot path | The int32 lane's emitted bytes unchanged against the pinned oracles; a second lane type reaching the same green differential and fuzz matrices at both vector widths; and the fuzz reachability test widened from every node type to node type times lane type |
| 86 | One operand admission, stated once (section 2.17). **Scoped** (8 September 2026), from the ghost fallback task 63's review found; independent of 83 to 85 | `intOperand`, `compileIntOperand`, `compileOffset` and `compare`'s `operand` as one function taking what the position accepts, and the emitter's four `require*Shape` checks derived from that same table rather than restated beside it, so widening the compiler either widens the emitter or fails to compile; carrying one widening as the table's first exercise - a bare `IntegerType` column in comparison operand position, which declines today and which task 79's admission check verified fuses with one case added, and which is folded in here rather than taken alone because widening one copy in isolation is what produced the ghost fallback | A test enumerating the operand positions and asserting that the set the compiler admits and the set the emitter accepts are the same set - the assertion whose absence let `date_add(d, weekday(d2) + 1)` ship as fused in EXPLAIN and a silent per-batch fallback at run time; every compiler decline reason unchanged, since no shape may move |
| 87 | **Moved to milestone 6** (15 September 2026, `SCOPE_MILESTONE_6.md` item 15): kept for a future fix at the owner's request; see section 6. The epilogue is the one method no budget bounds (section 2.18). **Scoped** (8 September 2026), from a 35-million-iteration fuzz run; independent of 83 to 86. **Absorbs milestone 4's row 44** (11 September 2026), which asked for the same partitioning at a softer threshold - its size ladder (4095 and 63, not only 4096) and its `HugeMethodLimit` measurement are requirements here | The epilogue emitted as one method holding every group, so a tree inside `MAX_FUSED_NODES` can pass 65535 bytes and the Class-File API refuses the class - `epilogueMasked` at 67244 bytes for nested `make_date`, replaying at `-Dvarka.fuzz.seed=2026092800 -Dvarka.fuzz.only=73411`; either partition it as the loop is partitioned or give the emitter a byte budget, and in both cases decline with a reason rather than throw | The pinned fuzz iteration declining with a reason instead of throwing; every shape that fits today emitting identical bytes against the pinned line map and the `codeSize` assertions; and `MAX_FUSED_NODES`' javadoc no longer claiming a per-method guarantee it only has for the loop |
| 88 | An exact division through double lanes (section 2.19). **Scoped** (9 September 2026), from task 68's admission check; independent of 65 but competes with it | `trunc((double) v * (1.0 / d))` as a lowering for integer division by a constant, exact for every int32 dividend and any divisor below about 2^21 - no magic, no correction carries, no range restriction, and no int64 lane, so none of this milestone's lane-width work is a precondition. Verified numerically and round-tripped through `I2D`/`D2I` on the preferred species; the admission check makes that exhaustive over the calendar divisors and commits the script | The exhaustive check committed beside `verify_long_lane_magic.py`; op counts per lowering from `dev/varka_emit.sh --table` before any timing; then a three-arm A/B - today's range-narrowed magic, task 65's int64 widening, and this - on one benchmark over the same shapes, with the choice made from the numbers rather than from the simplification each offers |
| 89 | The year-month interval divisions (section 2.20). **Scoped** (9 September 2026), split out of task 68; after whichever of 65 and 88 the A/B chooses | `extract(YEAR FROM ym)` and `ym / num` on the chosen exact division, with the truncation correction `extract` needs and the `HALF_UP` step `ym / num` needs; `extract(MONTH FROM ym)` behind the further question of a `ByteType` output the evaluator does not have; `ym / col` declining, the divisor not being a constant | The two rounding corrections verified over the full int32 month range against Spark's own `getYears`, `getMonths` and `IntMath.divide` by a committed script; the byte-output question settled before `extract(MONTH)` is built; a throughput pair per shape against the row engine, these being new lowerings |
| 90 | The benchmark files are not reproducible run to run (section 2.21). **Partly done** (10 September 2026): the band is measured and committed for the parity and throughput benchmarks at both widths, and the regeneration diff classifies against it. That half landed under task 77, which had re-scoped itself onto this row's work without noticing this row existed - recorded here rather than quietly absorbed. Scoped 9 September 2026 from the investigation task 79's section 9 asked for; the pinning half was already done | Two regenerations with no change between them disagree on 73 of 211 cases by more than 3% and 22 by more than 10%, pinned; unpinned the worst is 75%. Measured out: within-run noise (avg/best median 1.007), the clock (constant to 1.2% while throughput moves 31%), ASLR, contention. What remains is the per-fork C2 lottery `PLAN_TASK_32.md` 11 already traced to JDK-8380195. `dev/varka_bench_repeat.sh` measures the band; `dev/varka_bench_regen.sh` now pins to the fast core complex and records it. **Measured, 10 September 2026**, over ten runs per width on an idle pinned machine: the parity file's median spread is 5.34% at AVX-512 and 1.72% at 128-bit, p90 22.30% and 11.88%, worst 227.15% and 39.06%; the throughput file 5.31% and 3.67%. Two findings the section did not predict. The narrow width is the *quieter* of the two by a factor of three at the median, so collapses have been found at 128-bit because that file is quiet enough for one to stand out, not because it is unstable - and the two widths need separate bands for that reason. And three runs understate the band: this row's own 1.6% median comes from three runs, where ten give 5.34% at the same width | The band committed per file for the parity and throughput benchmarks: **done**. The regeneration diff reported against the band rather than a flat 3%: **done**, cutting held-out false alarms on an unchanged file from 32.9% of rows to 5.6% at AVX-512 and 11.4% to 2.1% at 128-bit. Still open: the arithmetic benchmark's band; task 63's 9.7 dead-local figure re-taken pinned before task 82 scopes itself on it; the decision on N-fork medians taken from the cost, with the fallback stated - that absolute rates stop being compared across runs and the within-run A/Bs carry the claims; and the cause itself, which task 77's census leaves open with one method taking 100 runtime deoptimisations across 194 compiles and the `task_queued` records unread |
| 91 | A guard bound the shift above it chooses (section 2.22). **Scoped** (10 September 2026), from task 69's outcome: it closed the upward half of `PLAN_MILESTONE_4.md` 9's conservative-decline entry and left the half that motivated it, `weekofyear`/`yearofweek` over a column offset, still residual because `ThursdayOf` shifts downward and there is no headroom below `NARROW_MIN_DAYS`; after 84, whose interval representation it should take | Task 52's runtime guard comparing against a bound the compiler chooses from the shift `dayRange` already computes for the subtree above the producer - `[NARROW_MIN_DAYS + 3, NARROW_MAX_DAYS]` under a `ThursdayOf` consumer, `+ 365` under a `trunc` one - so every downward-shifting consumer over a guarded producer becomes admissible at the same run-time cost, one compare against a different immediate | The guard's compare shown to be the only place `NARROW_MIN_DAYS` enters these kernels, by grep and a `VarkaEmitDump` op-count diff; the shape key shown to separate two subtrees identical but for the shift above them; `weekofyear(date_add(d, off))` fused with a differential over a batch that straddles the moved bound |
| 92 | The validity write, keyed on the bit layout (section 2.23). **Scoped** (10 September 2026), from task 47's measurement: its word writer wins 6 to 9% at 4 lanes and loses 11 to 20% at 8 and 16, so it shipped as an option defaulting off. **Worth more than it was first written as** (corrected 11 September 2026): four int lanes is `SPECIES_PREFERRED` on every NEON-only aarch64 and on x86 without AVX2, so this is a real target's default rather than a `MaxVectorSize` flag's; what it needs is one confirming run on such a machine, since task 47's four-lane numbers simulate the lane count on a 16-lane x86 | `validityByWord` defaulting on where a validity group is smaller than a byte (`lanes < 8`) - one condition read off the bit layout rather than two thresholds fitted to a machine, which is what task 76 declined; plus option B of `PLAN_TASK_47.md` 3.1, storing once per word rather than once per group, and 3.4's masked-driver liveness item, both of which share this task's ladder run | The k=3 step in task 47's ladder explained from `-XX:+PrintInlining` before any rule is fitted across it - the emitted code is already ruled out - and the width rule's win reproduced on a machine that runs at that width rather than under a `MaxVectorSize` flag |
| 94 | The bench module's tests are enforced by hand or not at all (section 2.29). **Done** (`PLAN_TASK_94.md`, 15 September 2026): a `varka-bench` module in `modules.py` and a CI job gated on it, which also stops a bench-only change falling through to `root` and running the whole matrix. Originally scoped (12 September 2026), from the review of task 62's chains PR: `sql/varka/bench` is only ever `-DskipTests package`d - three call sites, all of them - so `ChainsTest` and `SurfaceTest` run when someone types the Maven line and never in CI. The review found two of their invariants already weakened, which is the shape of it: right when written, and nothing to say when they stopped being | A `varka-bench` module in `dev/sparktestsupport/modules.py`, the `precondition` wiring that consumes `dev/is-changed.py -m varka-bench`, and a job modelled on `varka-engine` | The suites run on a pull request that touches `sql/varka/bench` and not on one that does not; a deliberately broken `ChainsTest` invariant fails that job |
| 95 | Two int32 shapes decline that a reader would expect to fuse (section 2.30). **Scoped** (12 September 2026), while choosing task 62's chain entries: `datediff(d2, d) * i` and `i % 20` both decline, though multiplying by a *literal* is fine - so it is an int multiply whose right operand is a column, and an int remainder at all | Whichever of `MUL` with a runtime operand and `REM` is worth the lowering, under task 63's existing checked/wrapping overflow discipline rather than beside it | Both spellings fuse or one is declined with a reason a reader can act on; the differential covers the overflow edge of whichever lands |
| 96 | `make_ym_interval` takes only arguments derived from a date (section 2.31). **Scoped** (12 September 2026), the same way: `make_ym_interval(year(d), month(d))` fuses and is in the surface, `make_ym_interval(i, i)` declines - so the constructor for the third type cannot be fed from the int columns the engine already reads, which is the obvious spelling and part of the three-types claim | Establish whether the restriction is the admission rule or something the lowering needs, and lift it if it is the former | `make_ym_interval` over two int columns fuses and matches the row engine over the ANSI overflow edge, or declines with a reason that says why it must |
| 97 | A bandwidth-bound row lost 22.6% between two surface regenerations and nothing in its lowering changed (section 2.32). **DONE** (`PLAN_TASK_97.md` 9): three explanations eliminated with numbers - the lowering is unchanged, the six-column table costs about 6% and costs it near-uniformly across a four-op and a sixty-four-op entry, and ordinal arm position costs 1% in the favourable direction. The control settles the rest: stock read 82.9 M rows/s on 13 September and 82.7 today while the Varka arm reads 28% higher, so the machine was not slower and only the Varka arm was. The surviving hypothesis - elapsed session time before the arm - becomes task 99, and a hazard found on the way becomes task 100. Scoped 14 September 2026, out of task 78's run: `date_add(d, 3)` went 1811.6 to 1401.4 M rows/s with a clean canary both times, while emitting the same four `IntVector` ops it always has and taking none of the paths the one nearby commit added. The table it is measured over grew from three int32 columns to six between the runs, and from about 12 GiB to 23.2 GiB cached, which is the hypothesis to test on the row where memory is everything and arithmetic is nothing | Time the same entry over a three-column and a six-column table, same host and row count, and attribute the 22.6% | Either the working set explains it - in which case a third of the surface is measured against a fixture shaped by columns those rows never read, and the fixture needs a decision - or fifty-four other commits are in scope |
| 98 | **Moved to milestone 6** (15 September 2026, `SCOPE_MILESTONE_6.md` item 15): the read-back floor, not a type. Two filter rows stay under 1.0x because the consumer counts (section 2.33). **Scoped** (14 September 2026), out of task 78's outcome: `WHERE d < d2` counted at 0.80x and `WHERE d IS NOT NULL` counted at 0.45x are the only losses left in the surface, and the same `d IS NOT NULL` predicate reads 9.5x with a columnar consumer at 1.2 ns/row against 11.7 ns counted - identical kernel, and 968 million of a billion rows crossing the read-back floor at about 25 ns each | Price making the filter node `CodegenSupport` so a counting consumer fuses into the vector loop instead of reading rows back - milestone 4's scope item 13, which task 78 declined to inherit and these two rows now need | The two rows at or above 1.0x, or a recorded measurement saying what the boundary costs and why it cannot go; the debt register's `COUNT(*)` entry belongs to the same owner |
| 99 | The Varka arm may be measured in the session's worst memory state (section 2.34). **Done, negative** (`PLAN_TASK_99.md`, 15 September 2026): the two Varka arms of one session, five hours and twenty minutes apart, differ by a median of 0.1% and by 1.1% on the entry in question, so elapsed time is not the variable. What the five arms did show is section 2.36's finding. Originally, out of task 97: three explanations for a 22.6% swing in `date_add(d, 3)` are eliminated with numbers, and the control is what makes the rest worth a task - stock Spark read 82.9 M rows/s on 13 September and 82.7 today, 0.2% apart, while the Varka arm on the same entry, fixture and ordinal position reads 28% higher today. The uncontrolled variable is elapsed session time before the arm: five hours twenty on 13 September, forty minutes in task 97's run, against a 23.2 GiB Arrow-cached table where the stock arms hold 1.2 GiB | Two full-length surface runs on one host on one day, differing only in whether the Varka arm runs first or last, about eleven hours | The two Varka arms agree, or they do not and the arm order is a measurement artefact the script must stop producing; either way the committed surface's ratios get a stated confidence |
| 100 | An `--only` run silently truncates its label's committed results file (section 2.35). **Done** (`PLAN_TASK_100.md`, 15 September 2026): refused before the machine checks for any label whose results file is tracked by git, with `--replace` as the deliberate override, kept separate from `--force`. Originally scoped (14 September 2026), found while running task 97: the driver replaces `DateSurface-<label>-results.txt` rather than merging, so a three-entry partial run turned two committed fifty-two-entry files into three-entry ones and `git status` showed an ordinary modification. Restored before staging, and only because the tree was read first | With `--only` set, refuse a label whose committed file holds more entries than this run will write, or write a name that says the file is partial - the sharded path already suffixes filenames for exactly this reason | A partial run cannot overwrite a fuller committed file; a test covers the refusal |
| 101 | The surface's per-entry numbers have a tail that one run cannot see (section 2.36). **Scoped** (15 September 2026), out of task 99: each of four arms measured overnight reproduced its committed twin to within 0.2% at the median over 52 entries, and each carried exactly one entry 8% to 27% away from it, in both directions, stock Spark as readily as Varka | Point `dev/varka_bench_repeat.sh` and `dev/varka_bench_band.py` at the surface the way they are pointed at the parity benchmark: N repeats per arm, the per-case band written and committed, and the split-half check run to see whether *which* entries are noisy reproduces here as it does there | A committed band file for the surface at both widths; the README's per-entry rows either carry their band or are replaced by the aggregate figures; `dev/varka_bench_diff.py` reads the band so a single-entry move inside it stops being reported as a change |
| 102 | `TIME` expressions over the long lane (section 2.37). **Scoped** (15 September 2026) by the re-scope; the milestone's subject. `TimeType` is nanoseconds of day in a long, below 2^53, so every division it needs is exact through 2.19 | After task 116 has passed (the cache proof, which this row does not repeat): `hour`/`minute`/`second`, `make_time`, `time_trunc`, `t + INTERVAL`, `t - t`, `time_diff`, the `time_from_*`/`time_to_*` pairs, and the generic comparison, `IN`, `CASE` and choice arms over a long-lane column; coverage-suite columns `t`, `t2`, `l`, `dt` | Differential against the row engine over every shape and null pattern at both vector widths with `spark.sql.timeType.enabled=true`; every arm documented, which the coverage suite enforces; `to_time`, decimal seconds and string formatting demonstrably declined |
| 103 | Day-time interval expressions, absorbing task 39 (section 2.38). **Scoped** (15 September 2026) | `+`, `-`, negate, `abs`, comparisons, `MultiplyDTInterval` under the checked-multiply rule; `DivideDTInterval` and the `extract` family under 2.19's bound or a recorded decline; `MakeDTInterval`; `date - date` (`SubtractDates`), `DateAddInterval` for whole days, `TimestampAddInterval`, `SubtractTimestamps` as one mixed-width kernel family | Differential at both widths over intervals spanning the int64 including the overflow edges; each division either exact under its bound or declined with the reason in the plan; `PLAN_TASK_39.md`'s recipe outcome written here |
| 104 | `Long` arithmetic, task 30's int64 half (section 2.39). **Scoped** (15 September 2026) | Checked and wrapping `+`, `-`, `*`, negate over `bigint` under the 2.15 lattice; comparisons; int-to-long `Cast` through 2.2; `div`, `%`, `pmod` only under a proven bound | The error-identity differential from section 5 at the long width; the halved-headroom number committed per shape, not discovered |
| 105 | The `TIME` benchmark: `TimeSurfaceBenchmark`, its own files (section 2.40). **Scoped** (15 September 2026); the number the message quotes; upstream's [SPARK-57562](https://issues.apache.org/jira/browse/SPARK-57562) is open for the same | The class and inventory in `sql/varka/bench`, a `--benchmark time` selector in `dev/varka_bench_surface.sh`, both arms with the flag on against stock 4.2.0, committed results files per label, **the `TIME` surface's own band file built with task 101's tooling** (a band is per benchmark, and 101's is the date surface's), `TimeChains` after | Every entry `--expect-fused`; the fixed-share rule met; measured under task 101's band before any figure is quoted, and the message quotes a median and a banded range |
| 106 | The quote check runs in no workflow (section 2.41). **Scoped** (15 September 2026), found while closing task 94 | A `varka-docs` module in `modules.py` over the plans, the lesson files, the docs and the READMEs, and a job on task 94's pattern running `dev/varka_quote_check.py` and the `SKILLS.md` index check | The job green on its own PR in minutes; the next documentation-only PR shows `Varka bench drivers` skipped, closing task 94's open half |
| 107 | `time_add(unit, quantity, time)` (section 2.42). **Withdrawn** (15 September 2026): vanilla Spark's work, not Varka's; section 8 has the survey and the ticket | - | - |
| 108 | `try_make_time` (section 2.43). **Withdrawn** (15 September 2026): vanilla Spark's work, not Varka's; section 8 has the survey and the ticket | - | - |
| 109 | `sequence()` over `TIME` (section 2.44). **Withdrawn** (15 September 2026): vanilla Spark's work, not Varka's; section 8 has the survey and the ticket | - | - |
| 110 | `avg` over `TIME` (section 2.45). **Withdrawn** (15 September 2026): vanilla Spark's work, not Varka's; section 8 has the survey and the ticket | - | - |
| 111 | Avro `TIME` (section 2.46) - not a gap, present. **Withdrawn** (15 September 2026): vanilla Spark's work, not Varka's; section 8 has the survey and the ticket | - | - |
| 112 | `DATE + TIME` as an operator (section 2.47). **Withdrawn** (15 September 2026): vanilla Spark's work, not Varka's; section 8 has the survey and the ticket | - | - |
| 113 | The survey of what else vanilla Spark lacks for `TIME` (section 2.48). **Withdrawn** (15 September 2026): vanilla Spark's work, not Varka's; section 8 has the survey and the ticket | - | - |
| 114 | `time_bucket` over `TIME` (section 2.49). **Withdrawn** (15 September 2026): vanilla Spark's work, not Varka's; section 8 has the survey and the ticket | - | - |
| 115 | `time_format` (section 2.50). **Withdrawn** (15 September 2026): vanilla Spark's work, not Varka's; section 8 has the survey and the ticket | - | - |
| 116 | A `TIME` column through Varka's Arrow cache, proven (section 2.51). **Scoped** (15 September 2026) at the owner's request; the milestone's first admission check | A `sql/core` Varka suite caching `TIME(p)` for p in {0, 3, 6, 9} and a day-time interval column under the Arrow serializer, reading back equal, and mapping the buffers through the morsel as eight-byte lanes | Passes at every null pattern the date fixtures use before any long-lane code is written, or fails and becomes the first fix |
| 117 | Sync the fork with `apache/spark` master (section 2.52). **Scoped** (15 September 2026) and **first in the milestone** by the owner's instruction: infrastructure, done before 84 opens | The merge of the 375 upstream commits (dry run 15 September: no conflicting file; the merged tree compiles and passes the wide Varka suites, 480 tests, 0 failed), the gate green, a surface regeneration under the canary if any Varka number is suspected to have moved | `dev/varka_gate.sh` green on the merged tree; [SPARK-53368](https://issues.apache.org/jira/browse/SPARK-53368) present afterwards, and the 36 traceable [SPARK-57550](https://issues.apache.org/jira/browse/SPARK-57550) subtasks still present, by a full-message grep of the log rather than a title prefix |
| 118 | The closing task: final benchmarks, the README, and the post (section 2.53). **Scoped** (15 September 2026) on the owner's instruction - the milestone ends as milestone 4 did, with task 62's shape; **last in the milestone**, after 105 and 101 | (A) the measurement's plan - arms, benchmarks, the full-width runner; (B) the `TIME` surface and chains measured under the band on a runner the datapath probe proves, with the allocation/arithmetic split and the engine-off ratio beside stock; (C) the README's `TIME` table and reproduction guide, the docs checked; the short and long post drafts, ideas first, numbers last | Four results files per benchmark committed with provenance and band; the README quotes them and nothing else; `dev/varka_quote_check.py` at zero orphans; both drafts exist and name their sources |
| 119 | The oracle for the long lane: reference evaluator and fuzzer at `long` (section 2.54). **Scoped** (15 September 2026); lands with 29 | `VarkaReferenceEvaluator` over long lanes including both exact-division lowerings, the range guards, `TIME`'s day-modular arithmetic and the interval checks; the fuzz grammar over long, `TIME` and day-time interval trees; the reaches-every-node assertion at the long lane | The fuzzer at ten thousand iterations clean at both vector widths over the long-lane grammar; a deliberately wrong evaluator arm is caught by the fuzzer, not only by the differential |
| 120 | The coverage table as a differential corpus (section 2.55). **Scoped** (15 September 2026); independent, starts on today's rows | A `sql/core` suite running every `coverage.json` row through both engines over the null-pattern fixtures, projection and predicate forms, both consumers | Every row of the committed table passes; adding an arm without a row fails `VarkaCoverageSuite`, adding a row without correctness fails this suite - the two together are the guarantee |
| 121 | The AVX2 arm: the `TIME` surface under `-XX:UseAVX=2` (section 2.56). **Scoped** (15 September 2026); after 105, quoted by 118 | Companion results files for the `TIME` surface under `UseAVX=2` on the laptop and on a Zen 3 runner via the workflow, provenance naming the lowering; the one-or-two-lowerings decision for 2.19 recorded from the numbers | Files committed with datapath and flags; the decision written in 2.19 with its numbers; 118's README table shows the AVX2 column beside the full-width one |

## 4. Files

From milestone 4's section 4, the parts that belong to these tasks:
`VarkaVectorIR` (the second `LaneType`, conversion nodes, the boolean output),
`VarkaLoopEmitter` (conversions, the overflow detectors, the zero-safety
member the first trapping node makes structural), `VarkaExpressionCompiler`
(casts, arithmetic, boolean roots), `VarkaShapeCacheImpl` only if the key
vocabulary grows; in `sql/core`, the evaluators (int64 buffers, boolean output
vectors) and `VarkaColumnarRule` (new eligible roots); in the engine module,
hand-written reference kernels only where a parity anchor is needed for a new
lane type, per the reference-code commenting rule. Tasks 74 and 75 touch
`VarkaLoopEmitter`'s `Analysis` (two `pureOf` arms, two folding rules, a
test hook for the census), `VarkaWordCensus` and `dev/varka_word_census.sh`
in catalyst test scope, and, if 75 is admitted, `VarkaKernelEvaluator`'s
output assembly and `VarkaOwnedArrowColumnVector`.

*Added 15 September 2026, for the re-scoped rows:* `VarkaVectorIR`'s `LaneType`
gains its second member and the emitter its lane descriptor (85); the compiler
gains the `TIME`, day-time interval and `Long` arms (102 to 104);
`VarkaCoverageSuite` gains the `t`, `t2`, `l` and `dt` columns and
`sql/varka/coverage.json` the new families; `sql/varka/bench` gains
`TimeSurfaceBenchmark` and its inventory, `dev/varka_bench_surface.sh` a
`--benchmark time` selector, and `sql/varka/bench/benchmarks/` its results and
band files (105); `dev/sparktestsupport/modules.py` and
`.github/workflows/build_and_test.yml` gain the `varka-docs` module and job
(106); a `sql/core` Varka suite gains the cache proof (116). The boolean output
and tasks 74 and 75 named above moved to milestone 6 and touch nothing here.

## 5. Verification

Milestone 4's standing gates, inherited whole, plus the two this milestone
adds:

* Differential against the row engine over every new shape, null patterns
  included, at the preferred width and `-XX:MaxVectorSize=16` - now at every
  lane width this milestone adds, not just every vector width.
* **The error-identity differential** (task 30): the same `SparkException`
  attributed to the same row, which the suites have never had to assert
  before.
* The byte-exact oracle still holds everywhere this milestone goes; it stops
  being universal only when item 3's doubles enter, which is why item 3's
  oracle decision (section 7) is taken early even though the item is
  deferred.

*Added 15 September 2026:* every `TIME` differential runs with
`spark.sql.timeType.enabled=true` on both engines, since the type is off by
default; task 116 passes before any long-lane code is written; and the
byte-exact oracle survives 2.19's double-lane division only because that
division is proven exact under its bound (1.1) - a division outside the bound is
a recorded decline, never an approximate result, which is what keeps the oracle
byte-exact through this milestone. The `TIME` surface (105) is quoted only from
under its own band file, built with task 101's tooling.

## 6. Risks

* **The mixed-width decision is expensive to reverse.** Task 28's loop-shape
  choice is baked into the emitter; that is why it was measured first
  (narrowest-drive won) and why width-locked retrofit is the recorded
  fallback.
* **Half the lanes.** Every int64 shape has roughly half the headroom of its
  int32 sibling; task 29 commits that number rather than discovering it.
* **`TIME` through the cache is assumed, not proven.** Every piece exists - the
  serializer's stats arm, `isSupportedByArrow`, upstream's converter and
  precision work - and no test composes them along Varka's path. Task 116 is
  that test and comes before any lane work, because a milestone whose subject
  cannot be cached has no benchmark. (An earlier draft called the stats arm
  missing; it is not, and 1.1 keeps the correction.)
* **Long lanes have no division.** Exact through double lanes only while the
  operand is under 2^52 (2.19's reciprocal form; 2^53 with a true division):
  always for `TIME`, only under a bound for intervals and `bigint`. Every
  division either carries its bound or is a recorded decline; none is computed
  wrongly - and 2.19's admission check states the error constant it relies on
  rather than the "fits a double" shorthand an earlier draft of this plan used.
* **The long-to-double conversion does not vectorise on AVX2 - settled, with
  the fallback in hand.** Checked on 15 September 2026 from C2's own output
  (`dev/varka_canary/L2DProbe.java`, `-XX:+PrintIntrinsics` and hsdis): at the
  host width `VectorSupport::convert` inlines and the loop is `vcvtqq2pd` /
  `vcvttpd2qq`; under `-XX:UseAVX=2` the convert intrinsic **fails to inline
  every time** and the loop degrades to scalar `vcvttsd2si` / `vcvtsi2sdq` with
  reboxing - slower than a scalar loop, on the CI pool's Zen 3 and every AVX2
  machine. The fallback is not a scalar tail. It is the magic-number
  conversion (`dev/varka_canary/MagicProbe.java`): for `v < 2^52`,
  `(v | 0x4330000000000000) reinterpreted as double, minus 2^52` is exactly
  `v`; the quotient is rounded with the same `+2^52 -2^52` trick and stepped
  down where rounding went up (there is no lanewise floor in the Vector API);
  and `+2^52, reinterpret, mask` recovers the integer. Under `UseAVX=2` C2
  compiles that to 18 `vsubpd`, 12 `vaddpd`, 6 `vpor`, 6 `vpand`, 6 `vmulpd`,
  6 `vcmpgtpd` - no conversion, no extraction, no call - and it returns 0
  wrong quotients over 65 536 nanos-of-day values at 4 and at 8 lanes. So 2.19
  at the long lane is **two lowerings selected by `UseAVX`**, or the magic form
  everywhere if its cost at AVX-512 is within the band of the native casts -
  which is the one measurement 2.19's admission check still owes, and it is a
  timing, so it comes with a committed file.
* **The epilogue (task 87) moved out and may move back.** It is the one method no
  budget bounds, kept for a future fix at the owner's request; 64-bit lanes
  widen every node, so a `TIME` shape may reach the 65535-byte cap sooner than
  a date shape did. If one does, 87 re-enters with that shape as its case.
* **A recipe written ahead of its machinery.** Task 39's recipe names 28's and
  29's plumbing provisionally; its outcome section is where the gap between
  assumption and reality gets recorded, and the executing agent stops rather
  than adapts when the real thing differs.

## 7. Open questions

From milestone 4's section 7, the two owned by these tasks:

1. **The ULP oracle** (item 3): a reading task - what accuracy Spark promises
   for `exp`, `log`, `pow` and the trig family, and what bound a vector
   differential asserts. Cheap, the item's gating decision, and what lets item
   3 be argued back in without a design pause. Recorded in this file when
   settled.
2. **Mixed-width loop shape**: measured before task 28 opens (2.2);
   narrowest-drive, unless a wider mixed-type shape measures differently once
   28 is under way.

*Added 15 September 2026:*

3. **How does C2 lower the long-to-double casts at each width?** *Answered 15
   September 2026* - see section 6: native `vcvtqq2pd`/`vcvttpd2qq` at
   AVX-512, no intrinsic at all under AVX2, and the magic-number conversion
   vectorises fully at both. What remains is the timing: the magic form's cost
   against the native casts at AVX-512, which decides one lowering or two.
4. **`TIME + INTERVAL` out of range: modulo-24 or overflow?** Upstream's
   SPARK-57853 is open on it, and 102's `TimeAddInterval` lowering must match
   whatever vanilla does on the day and follow the ticket if it changes. The
   differential catches a divergence; the plan records the dependency so the
   divergence is expected rather than discovered.

## 8. Explicitly out of milestone 5

**Moved to `SCOPE_MILESTONE_6.md` item 15 on 15 September 2026**, when the
milestone was re-scoped to 64-bit lanes and `TIME` (section 1.1) - fourteen rows,
text and numbers unchanged, each with its reason there: 25, 27, 49, 64, 65, 66,
72, 73, 74, 75, 80, 82, 87 and 98. Each re-enters with its own argument; 27 and 87
name in 1.1 and section 6 what that argument would be.

**`TimestampNTZ` decomposition - considered on 15 September 2026, set aside.**
The long lane carries `TimestampNTZType` for comparisons, differences and
interval arithmetic (task 29's row), and that is where this milestone stops with
it. What was considered and declined is the *decomposition*: `year(ts)` and the
calendar fields through `floorDiv(micros, 86 400 000 000)` into the int32
civil-from-days prefix - the first kernel where a long lane would feed the
calendar machinery, and the type whose value range spans the int64 and so would
force the full-range exact division (estimate in doubles, remainder in long
arithmetic, a one-step correction) instead of `TIME`'s bounded one. Both are
real, and the owner's decision is that neither belongs to a milestone whose
message is `TIME`; they are milestone 6's, with this paragraph as the argument
they arrive with. `TimestampType` (LTZ) stays out as before: zone rules per row.

**Vanilla Spark's `TIME` gaps - surveyed on 15 September 2026, left to upstream.**
Rows 107 to 115 were opened for them and withdrawn the same day, on the
owner's decision that this milestone takes only what Varka needs: vectorised
expressions and benchmarking. Vectorising an expression vanilla Spark does not
have means first adding it to vanilla Spark, which is upstream's work under
[SPARK-57550](https://issues.apache.org/jira/browse/SPARK-57550); when one of these lands there, its Varka lowering is a
row of task 102's family, not a task of its own. The survey, so it is not redone:

| row | gap in vanilla Spark | upstream | Varka side, if it ever lands |
| ---: | :--- | :--- | :--- |
| 107 | no additive `time_add(unit, qty, t)`; `time_diff` has no twin | none; wrap rule is [SPARK-57853](https://issues.apache.org/jira/browse/SPARK-57853) | `TimeAddInterval`'s kernel over two operands |
| 108 | no `try_make_time` | [SPARK-57846](https://issues.apache.org/jira/browse/SPARK-57846) | task 63's null-on-overflow lowering |
| 109 | no `sequence()` over `TIME` | [SPARK-57852](https://issues.apache.org/jira/browse/SPARK-57852) | none - a generator |
| 110 | no `avg` over `TIME` | [SPARK-58044](https://issues.apache.org/jira/browse/SPARK-58044) | none until milestone 6's aggregation |
| 111 | Avro `TIME` - *not a gap*: present | [SPARK-54473](https://issues.apache.org/jira/browse/SPARK-54473), [SPARK-57581](https://issues.apache.org/jira/browse/SPARK-57581) | - |
| 112 | no `date + time` operator; the function exists | none; function is [SPARK-53579](https://issues.apache.org/jira/browse/SPARK-53579) | a mixed-width kernel after `date - date` |
| 113 | the rest, unverified | see 2.48's list, each with its ticket | - |
| 114 | `time_bucket` returns timestamps only | [SPARK-54507](https://issues.apache.org/jira/browse/SPARK-54507) | `time_trunc`'s kernel with a literal width |
| 115 | no `time_format` | [SPARK-54588](https://issues.apache.org/jira/browse/SPARK-54588) | none - a string |

Present and confirmed, so not gaps: `extract`, every cast, CSV, JSON, Parquet,
ORC, JDBC and Avro, `to_char` ([SPARK-57575](https://issues.apache.org/jira/browse/SPARK-57575)), Hive interop
([SPARK-57556](https://issues.apache.org/jira/browse/SPARK-57556)), the Connect proto and its tests, the Python type and
pandas conversion, and DSL parity in Scala and Python for every `TIME` function.
The flag's history is [SPARK-54609](https://issues.apache.org/jira/browse/SPARK-54609) - disabled by default ahead of 4.1,
with no ticket yet to turn it on.

* **Item 3, float and double lanes** - the taxi benchmark's item; re-enters
  whenever that target is argued for, with its catalogue entry intact below.
* **Items 7 to 10** - aggregation, string functions, string keys, cross-lane
  movement: the follow-on ladder, carried in the catalogue below with full
  design input; each enters only with its own argument, and the aggregate
  wiring milestone 6 depends on is item 7.
* **`DecimalType`** - per milestone 4's item 12; its design pass is
  `SCOPE_MILESTONE_6.md` items 1 and 2.
* **Zoned timestamp arithmetic** - stays out until its semantics are written
  down; item 2 below keeps the tzdata-as-interval-arrays design for that day.

## 9. Scope catalogue

Milestone 4's pre-plan catalogue items about other lanes and about the
follow-on ladder, item numbers preserved because `SCOPE_MILESTONE_6.md`,
`PLAN_TASK_21.md` and `SKILLS.md` cite them. Items 6, 11, 12 and 13 stay in
`PLAN_MILESTONE_4.md` section 10.

### Item 1. Lane-width conversion, and mixed-type expression trees

Adopted as task 28 (see 2.2). The design input carried over whole: the hard
part is the lane count, not the conversion; `convertShape(I2L, longSpecies,
part)` yields one long vector per part with `partLimit` parts; the
narrowest-drive-versus-part-loop choice is measured before either is built in;
Spark's narrowing `Cast` throws under ANSI and wraps without it, tying this to
item 4.

### Item 2. int64 lanes: `TimestampNTZ`, `bigint`, and the second lane width

Adopted as task 29 (see 2.3). Kept for the zoned day when it comes: pack the
IANA tzdata transitions into flat `long[]` interval arrays and resolve a
vector of timestamps against them with a SIMD binary search, rather than
per-row `ZoneRules` lookups.

What a production instance of that design looks like, from
`NVIDIA/spark-rapids-jni` (`datetime_utils.cuh`, `timezones.cu`, read
September 2026), so the zoned task starts from its corners rather than
rediscovering them:

* **Two sorted arrays per zone, not one.** Converting *from* UTC searches the
  UTC instants; converting *to* UTC searches the local instants, because the
  same transition sits at different positions on the two axes. Each entry
  carries both instants and the offset after it.
* **The table is finite and the rules take over past its end.** Beyond the
  last stored transition the zone's two DST rules (month, day-of-week rule,
  time, offsets before and after) are evaluated arithmetically for the row's
  year - in lanes, that is the calendar family's own arithmetic, not a lookup.
  Java's `ZoneRules` has the same shape: `getTransitions()` then
  `getTransitionRules()`.
* **Gaps and overlaps decide the rounding.** A UTC instant one microsecond
  before a gap must floor-divide to seconds, or truncation snaps it onto the
  transition and picks the post-gap offset (their issue #14861); a local
  wall-clock inside a gap resolves to the post-gap offset to match
  `LocalDateTime.atZone`. Both are one-line decisions that a differential
  against Spark finds only if the fixtures straddle a transition by less than a
  second.
* **Scope by zone kind.** UTC and fixed-offset session zones are a constant
  add and belong to task 29's first kernels; region zones are the design above
  and decline until it is built. The taxi benchmark's `year(pickup_datetime)`
  (`SCOPE_MILESTONE_6.md` 1.5) is the first query that needs the region case.

### Item 3. Float and double lanes, and the numeric function family

**Deferred by the headline decision** (section 1) - the survey found zero
`DOUBLE`/`FLOAT` columns in TPC-DS and TPC-H; this is the taxi benchmark's
item and re-enters with that target. Design input kept in full:

* *The transcendentals are real vector calls.* JDK 25 ships `libjsvml.so`
  inside `jdk.incubator.vector`, and `VectorMathLibrary` looks its symbols up
  through a `SymbolLookup` at first use - so `lanewise(EXP, ..)` on x64
  reaches Intel's SVML port rather than a per-lane `Math.exp` loop. What
  aarch64 does instead must be checked before any doc claims the same.
* *So the oracle has to change.* SVML is not bit-identical to `Math` and
  `StrictMath`, so a double differential must be ULP-bounded, and Spark's own
  accuracy guarantee has to be read before a bound is picked. That reading is
  this milestone's open question 1 (section 7).
* *Comparison is not IEEE.* Spark's `SQLOrderingUtil.compareDoubles` makes
  NaN equal NaN and sort above everything, and `-0.0` equal `0.0`;
  `VectorOperators.EQ`/`LT` are IEEE. Every emitted double comparison needs an
  explicit NaN fix-up on the mask, and `NormalizeFloatingNumbers` does not
  save us - it rewrites only window partition keys and equi-join keys.
* *`round` and `DecimalType` are not this item* - `round(x, n)` is
  scale-dependent and decimals are not a lane type (item 12).

**Vector API it needs**: `DoubleVector` and `FloatVector`; `lanewise(Unary)`
with `SQRT`, `EXP`, `LOG`, `LOG10`, `CBRT`, `SIN` through `TANH`, `EXPM1`,
`LOG1P`; `lanewise(Binary)` with `POW`, `ATAN2`, `HYPOT`; the `FMA` ternary;
`Vector.test` with `IS_NAN`, `IS_INFINITE`, `IS_FINITE`.

### Item 4. ANSI-correct integer arithmetic, priced rather than assumed

Adopted as task 30 (see 2.4). The pricing argument carried over whole:
wrap-versus-saturate difference lanes are exactly the overflowed lanes, one
vector op and one well-predicted branch on the common path, `try_*` as the
branchless easy case worth shipping alone.

### Item 5. Boolean outputs

Adopted as task 27 (see 2.1).

### Item 7. Aggregation: the first horizontal reduction

**Deferred - first in the follow-on ladder**, and milestone 6's aggregate
wiring depends on it. Design input kept in full:

**Spark surface.** `HashAggregateExec`'s partial aggregation without grouping
keys: `sum`, `min`, `max`, `count`, `avg`, `bit_and`, `bit_or`, `bit_xor`,
`bool_and`, `bool_or`. Then the shape milestone 3's survey named and declined:
`CASE WHEN <date cmp> THEN x ELSE 0 END` inside `sum(..)` (TPC-DS q21 and
q40) - aggregate-*input* fusion, a different wiring from the projection path.

**Vector API it needs**: `reduceLanes(Associative)` and its masked overload,
`reduceLanesToLong`, and the `Associative` set - `ADD`, `MUL`, `MIN`, `MAX`,
`AND`, `OR`, `XOR`, `FIRST_NONZERO`.

**Design input.** The reduction belongs at the *end* of the batch: accumulate
into vector accumulators inside the loop and reduce once, with
multi-accumulator unrolling (acc0-acc3, breaking the dependency chain) - item
13's principle, applied at the one place a loop-carried dependency makes it
mandatory rather than measurable, and task 25's numbers will already exist.
The masked `reduceLanes` overload handles nulls without a branch. `sum` over
`LongType` inherits item 4's overflow question; `avg` is `sum` plus a
`trueCount`. Grouped aggregation is *not* this item - grouping is hashing and
partitioning (item 9's machinery, probably its own milestone). It changes what
an operator *is* rather than what an expression computes, so it wants the
plan-shape lessons from filters behind it - which it now has.

### Item 8. String functions, and the byte lanes they need

**Deferred - last in the ladder by frequency, named for completeness.** Kept
in full:

**Spark surface.** `length`, `upper`/`lower` on the ASCII fast path, `LIKE
'prefix%'`, `startswith`/`endswith`/`contains`, `substr`/`substring`,
`concat`, and `cast(string AS DATE)` done properly rather than folded. Four of
the six corpus functions still missing after milestone 6 are here
(`SCOPE_MILESTONE_6.md` section 1.7) - most of what stands between the roadmap
and the whole corpus function surface, and a long thin tail: 37 uses against
item 9's 275 key references.

**Vector API it needs**: `ByteVector` and `ShortVector`; `compare` with
`anyTrue`/`allTrue`; `rearrange` for byte permutation inside a value.

**Design input.** Variable width is the whole problem: Arrow strings are
offsets plus bytes, every operation is data-dependent in length, and the
fixed-lane-count loop stops being the right shape - which is why SWAR date
parsing stays in its own design pass.

**`cast(string AS DATE)`, designed** (September 2026, from Daniel Lemire's
`sse_date.c`, the 2023 "Parsing time stamps faster with SIMD instructions"
post, and his 2018 `eightchartoi.c`; `SKILLS.md`, "Validate a fixed-format
string with a saturating subtraction"). Spark's `stringToDate` grammar is wide:
trimming, an optional sign, a 4-to-7-digit year, 1-or-2-digit month and day,
and an optional `T` or space tail. The corpus writes `yyyy-MM-dd`. The kernel
accepts exactly that 10-byte form and sends every other row to the row engine,
the way task 26's guard does - it is a shape mask, not a parser.

* **Validation, branch-free.** XOR the bytes with `0x30`, so digits become
  0..9 and the dashes become `0x1D`. One saturating unsigned subtraction
  (`SUSUB`, in JDK 25's `VectorOperators`) against a per-position limit vector,
  `9 9 9 9 1D 1 9 1D 3 9`, leaves a nonzero residue for any non-digit, a
  wrong separator, a leading month digit above 1 or a leading day digit above
  3. Pair the digits into two-digit values and subtract again against `12` and
  `31` to catch 13..19 and 32..39. Subtract the other way against a minimum
  vector - `1` under the month and day pairs, `1D` under each separator, so a
  separator must equal `0x1D` exactly rather than merely fall below it - to
  reject month and day zero and a digit where a dash belongs. OR the residues;
  the row is in shape iff the OR is zero.
  The row's length must be 10 as well, which the offsets say before any byte is
  read. Day-in-month and the leap rule are not checked here: they fall out of
  `emitDaysFromCivil`'s month-length compare, which is already emitted.
* **Digits to fields, without `maddubs`.** The Vector API has no byte
  multiply-add, and does not need one. With the eight digits `yyyyMMdd` packed
  into one long lane, `eightchartoi.c`'s SWAR ladder - multiply by
  `1 + (10 << 8)`, shift 8, mask `0x00FF..`; multiply by `1 + (100 << 16)`,
  shift 16, mask `0x0000FFFF..` - stops after two steps with the four two-digit
  fields `yy yy MM dd` in 16-bit slots, which is what `emitDaysFromCivil`
  (task 40) takes after `year = 100 * hi + lo`. Four rows per 256-bit vector,
  in `long` lanes.
* **The load is the open question, and it is item 3's of milestone 6.** A
  10-byte record does not align to a long lane. The candidates are the
  index-spill path the gather probe used (per-row scalar loads into a `long[]`,
  then `fromArray`) or a `ByteVector.rearrange` compacting three rows out of a
  32-byte load when the column is known fixed-width. Neither is measured.

Not taken from the same source: its `is_leap_year_fast` and `leap_days_fast`
assume 1970..2106 and special-case only 2100, a narrowing Varka has no use for
under a total decomposition; and its `HHmmSS` combine, two 64-bit magic
multiplies over the `pmaddubsw` output, is tuned to a time part Spark dates do
not carry and Spark timestamps do not arrive with as strings at the kernel.

**The fallback's boundary, pinned from a second port of the same grammar**
(`NVIDIA/spark-rapids-jni`, `cast_string_to_datetime.cu`, ported from Spark
3.5's `SparkDateTimeUtils` and tested against Spark; read September 2026).
Its kernels are scalar C++ run once per thread - digit loops, early returns -
so nothing of their shape transfers to lanes, but the facts they pin do:

* Trimming treats `c <= 32 || c == 127` as whitespace, which is
  `UTF8String.trimAll`'s definition, not Java's `isWhitespace` alone.
* The year takes 4 to 7 digits for a date (4 to 6 for a timestamp); a date is
  valid only for years within +-10,000,000, so `1000000-01-01` parses and
  `10000001-01-01` does not.
* After the day, one space or `T` ends the parse and anything may follow:
  `2025-01-01T`, `+2025-01-01Txxx` and `-2025-01-01 xxx` are all valid.
* Its `castStringToDate` fixture list is the fallback test for the fixed-form
  kernel, every row of it a shape the mask must decline and the row engine must
  then accept: `"  2025"`, `"2025-01 "`, `"2025-1  "`, `"2025-1-1"`,
  `"2025-1-01"`, `"2025-01-1"`, `"2025-01-01"`, `"2025-01-01T"`,
  `"+2025-01-01Txxx"`, `"-2025-01-01 xxx"`, and the two large years above.
* Its ANSI protocol is the status contract Varka already has: parse to a
  nullable column, and under ANSI fail the batch if the null count grew. It
  also documents one deliberate deviation - its pattern parser accepts
  one-digit month and day for `yyyy/MM/dd` where Spark's strict formatter
  rejects them - which is the kind of shortcut a differential against Spark
  refuses by construction.

### Item 9. String keys: equality, hashing, dictionaries

**Deferred - its near-term half is already milestone 6's item 3** (fixed-width
equality against a literal, hashing short values for grouping); what stays
here is the machinery that subset does not need. Kept in full:

**Spark surface.** Strings as keys: equality against a literal, `IN` against
a small set, grouping and join keys (275 group-by references, 60% of all).
`hash`, `xxhash64`, `murmur3`. The plain bit expressions that share the
operators: `bit_count`, `shiftleft`, `shiftright`, `shiftrightunsigned`, `&`,
`|`, `^`, `~`.

**Vector API it needs**: `ROL`/`ROR` (murmur3's mix is rotate-multiply-xor);
`BIT_COUNT`, `LEADING_ZEROS_COUNT`, `TRAILING_ZEROS_COUNT`, `REVERSE`,
`REVERSE_BYTES`; `BITWISE_BLEND`; `COMPRESS_BITS`/`EXPAND_BITS`; `LSHL` and
`ASHR`; `VectorShuffle` in full with `rearrange` and two-vector `selectFrom`.

**Design input.** *There is no off-heap gather.* Gather and scatter exist
only on the `int[]` array overloads, never on `MemorySegment`. A dictionary
decode over an off-heap Arrow dictionary either copies the dictionary on-heap
or uses `rearrange`/`selectFrom` with a dictionary small enough to sit in one
vector - and a low-cardinality `CHAR(n)` column is exactly what a Parquet
reader dictionary-encodes, so this is the common case. Scatter is missing
too, which is why grouped aggregation expects to vectorise the hash and key
compare while keeping the probe and accumulator update scalar.

**What a gather costs, now measured** (`VarkaVectorApiProbeBenchmark`, added
because this item rested on an assumption). Reading `year` out of a day-indexed
table - the shape Impala ships for 1950-2049 - against the civil-from-days
arithmetic, over 20M dates at AVX-512: the `IntVector` gather runs at 3573.9 M
rows/s over the whole 143 KB table and 3728.2 over a seven-year span, against
2379.8 and 2368.3 for the arithmetic. **A gather is not the slow primitive this
item assumed it was**, which makes the copy-the-dictionary-on-heap option more
attractive than the paragraph above implies, and it is worth re-reading before
item 9 is planned rather than inheriting the assumption.

Two findings come with it. A plain **scalar** `int[]` loop over the same table
is faster still - 4630.0 M rows/s on the seven-year span, 1.95x the vector
arithmetic - because the Vector API takes a gather's index map as an `int[]`,
so the index vector is stored and read back, and that spill is the API's rather
than the machine's.

**Fused, the ranking inverts.** The same three measured as `year(d) = 1998`,
counted, where the vector paths never leave a register: the gather reaches
3999.8 M rows/s against the arithmetic's 1453.0 - **2.8x** - while the scalar
loop that led the unfused table falls to 848.1, and the hybrid an emitter would
have to produce (spill the lane group, scalar-lookup, reload) is a wash with the
arithmetic at 1446.9. So emitting scalar code for a calendar node buys nothing
once the result is compared and counted.

**Correction, measured after the above was written.** That paragraph went on to
say the only lowering which would pay is one the API forbids. It does not. The
missing `fromMemorySegment` index-map overload blocks gathering *from* an
off-heap table - this item's dictionary case, where the claim still stands - and
says nothing about gathering an **on-heap constant table** indexed by off-heap
data. A calendar table is the second kind, because Varka owns it: the column
loads with `fromMemorySegment`, the index vector spills with `intoArray`, and
the gather reads a table on the heap. Measured in that shape, with the column in
a `MemorySegment` the way a real kernel has it, an era-indexed table reaches
2070.8 M rows/s against the arithmetic's 1329.3 - a **1.6x**.

The table's size is worth taking from ClickHouse, whose `DATE_LUT_SIZE` is
146097: one Gregorian era. Indexed by day of era rather than by an arbitrary
year window, such a table needs **no fallback for any `int32` date**, and the
index is what `emitEra` already produces, so the table replaces everything after
it. That is a candidate lowering for the whole calendar family and belongs in
its own task. What it changes for this item is narrower: the
copy-the-dictionary-on-heap option is attractive on a measured basis rather than
on an assumption about what gathers cost.

### Item 10. Cross-lane movement: windows, prefix sums, row indices

**Deferred - after item 7 in the ladder**; it shares the not-lane-shaped
problem and adds a state contract on top. Kept in full:

**Spark surface.** `WindowExec` where the frame lives inside one batch: `lag`
and `lead` by a small constant offset, running aggregates over `ROWS BETWEEN
UNBOUNDED PRECEDING AND CURRENT ROW`, `row_number` within a batch,
`monotonically_increasing_id`.

**Vector API it needs**: `slice(int, Vector)` and `unslice`; `addIndex`;
`VectorSpecies.iotaShuffle`; `rearrange`.

**Design input.** `lag(x, 1)` across a lane group is exactly `slice(lanes -
1, previousVector)`; a running sum is the log-step prefix scan (shift by 1,
2, 4 and add). What makes it an operator change is the carry: a frame crosses
batch boundaries, so the kernel needs carry-in and carry-out state and a
visible partition boundary - a contract like milestone 3's selection vector,
not a new IR node.

### Item 14. A lockstep validity evaluator for arbitrary pure words

*Added 7 September 2026 with section 2.9; the item that section's census
declined to make a task.*

**Design input.** Task 70's pass serves a root whose word is a chain of one
operator, because the engine folds a chain into the destination bitmap in
place and a tree that mixes AND and OR needs a second live intermediate. That
restriction is an artefact of evaluating one operator at a time over the
whole column. Walk every input bitmap in lockstep instead, evaluating the
whole term word by word in registers, and the registers needed are the
tree's Strahler number - Ershov's theorem gives it as the exact minimum, and
the census (2.9) puts it at 2 or 3 for all but two of 40000 grammar shapes.
Every pure term becomes servable in one pass with no scratch buffer; the
chain evaluator is the case where the tree is left-leaning; and the same
walk evaluates every root of a projection over one pass of the inputs, with
shared subterms held in registers, which is the only form in which
cross-output sharing (2.9) would pay. The emitted form is either a small
interpreter over a postfix encoding of the term - a handful of opcodes, one
dispatch per operator per 64 rows, negligible beside a kernel - or the loop
emitted into the driver as bytecode, which the driver's `HugeMethodLimit`
ladder (task 70 pinned it) would have to absorb.

**Why it is an item and not a task.** After task 74, the words it would
serve are 1.4% of the fuzzer grammar's roots and none of the `Surface`
inventory; the shapes are `datediff(greatest(d, d2), greatest(d3, d4))`
and `date_add(greatest(d, d2), i)` and their kin, which no corpus query in
`SCOPE_MILESTONE_6.md`'s survey has. It enters when one does, with the
census re-run over that corpus as its admission check, and it should then
also take the `Cond` null-test predicates 2.9 holds as a question, since
complement is one more opcode to the interpreter and a fourth entry point
to the chain.
