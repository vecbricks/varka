# VARKA-53: the Neri-Schneider month block

## 1. Where this came from

Not from the milestone. Velox was read side by side with Varka's calendar
lowering during VARKA-48, and its scalar date decomposition
(`velox/type/FastDate.h`, reached from `year(date)` through
`Timestamp::epochToCalendarUtc`) turns out not to be Hinnant's
`civil_from_days` - the algorithm Varka's VARKA-26 lowering follows - but
**Neri-Schneider 2022**, "Euclidean affine functions and their application to
calendar algorithms". That paper's decomposition is a strictly better shape in
two ways, and exactly one of them is expressible on `IntVector` lanes. This
task takes that one.

The half worth taking is the month block. Neri-Schneider computes the month
index and the day of month from a **single affine numerator**, taking the
quotient with a shift and the day of month out of the remainder, where Varka
computes the month with a magic multiply and then recovers the day of month by
running a second magic multiply forwards (`emitMonthStart`) and subtracting.
Same answers, fewer lane ops, and the intermediate that survives the prefix is
one the day-of-month tail can use directly rather than one it has to invert.

Velox's own file carries an MIT header for the Neri-Schneider code it adapts.
The identities are from the published paper and free to implement; **this task
derives the lowering from the paper's form and from the verification below, and
does not transliterate that file**, which is also why section 2 re-derives every
constant against Varka's own domain rather than importing one.

An interaction with VARKA-48, stated before it is discovered: after this task a
`year`-only kernel elides the *numerator* step rather than the magic-multiply
month step, so VARKA-48's elision shrinks from four ops to two. It stays correct
and stays a win; the number in `VARKA-48.md` 9.1 just becomes a number about
code that no longer exists, and section 5's op-count test moves with it.

**Corrected once PR #64 was integrated against VARKA-48**: the elision covers
*two* nodes, not one. `dayofyear`'s tail blends `doy - 305` against
`doy + 60 + L` and reads no month index at all, so it too skips the month step
and its `tailReadsMarchMonth` arm is `false`. Everything this section says about
`year` therefore applies to `dayofyear` as well, and the arithmetic below counts
both. Nothing about the design moves - what moves is a count this plan
registered in advance, which is the kind of thing worth fixing on the record
rather than discovering mid-task.

## 2. The admission check, done

Same gate VARKA-26 applied before any emitter work, and it splits the paper
cleanly in half.

### 2.1 What is expressible: the month block

Over the March-based day of year the prefix leaves in `rem`, domain `[0, 365]`:

```
num  = 2141 * doy + 197913        // max 979378, comfortably inside int32
m3   = num >>> 16                 // the month, March = 3 .. February = 14
dom0 = (num & 0xFFFF) / 2141      // the zero-based day of month
```

Verified over the whole domain, against the forms Varka ships today: `m3 - 3`
equals `(5 * doy + 2) / 153` and `dom0` equals
`doy - ((153 * mp + 2) / 5)` for **all 366 values, zero mismatches**. The
divisor in the third line is the only thing that needs a magic, and it has an
exact one well inside a lane: `/2141` over `[0, 65535]` is
`(x * 31345) >>> 26`, exact at every one of the 65536 values, with a maximum
product of 2054194575 - under `2^31 - 1` with room to spare.

The paper derives the same two constants and states their range itself, which is
a stronger check than the sweep above because it covers the reasoning and not
only the values: Example 10 applies its Theorem 3 with `k = 16` to
`m(N_Y) = (5 * N_Y + 461) / 153`, yielding `a' = 2141`, `b' = 197913` and
`U = 734`, and Equation (20) gives the remainder form over the same range,
`for all N_Y in [0, 734[`. Varka's domain is `[0, 365]`, comfortably inside it.
The transcription is in `sql/varka/papers/`; **read the PDF for the theorem
statements themselves**, for the reason that directory's README records.

The inverse direction's month-start map is expressible too, and is also a
shift rather than a magic:

```
monthStart(m3) = (979 * m3 - 2919) >>> 5      // m3 in [3, 14]
```

equal to Varka's `(153 * mp + 2) / 5` at all twelve months, with the numerator
running from 18 to 10787 - no overflow, and never negative, so the shift may be
arithmetic or logical.

### 2.2 What is not expressible: the era and year steps

The paper's real headline is that its century and year steps need **no
correction carries at all**, because it divides a `4x + 3` numerator and reads
the remainder out of the same operation. Neither reaches an int lane:

* `century = (4 * shiftedDay + 3) / 146097` has a dividend up to `2^26`. The
  largest multiplier whose product still fits a signed 32-bit lane at that
  dividend is **32**, and an exact or round-down magic for `/146097` needs
  `M ~ 2^k / 146097`, so `k <= 22` and `M = 29` - a divisor error near 1%,
  which is not a carry away from correct, it is a different algorithm.
  Searched rather than argued: no `(M, k)` is exact over `[0, 2^26)` with the
  product inside a lane.
* the year step is `(2939745 * n) >> 32`, that is the **high half** of a 64-bit
  product. `VectorOperators` has no multiply-high on any lane type - the fact
  VARKA-26's admission check turned on, and still true.

Both would fit `LongVector` lanes at half the lane count, which is VARKA-49's
question (`m4/PLAN.md` row 49), not this one. **This task changes
nothing above `rem`.** The era, century and year-of-century steps, their
round-down magics and their carries stay exactly as they are, and the year tail
- which since VARKA-48 reads only `rem` - is untouched.

## 3. The design

### 3.1 The prefix stores a numerator, not a month

`emitChronoPrefix`'s last step becomes the two-op numerator, stored in the slot
`marchMonth` uses today (`t[5]`, renamed `monthNumerator`). VARKA-48's
`tailReadsMarchMonth` becomes `tailReadsMonthNumerator` with the same
membership - `year` and `dayofyear` are the two tails that read neither - and
its elision, its per-lane-group consumer set and its `VarkaEmitOptions` switch
all carry over unchanged in shape.

The switch is exhaustive with a throwing `default`, and that is load-bearing
rather than decorative: it is what made PR #64 and PR #78 fail immediately on
merging VARKA-48 instead of computing a wrong month quietly. By the time this
task starts it carries six arms - `Year` and `DayOfYear` false, `Month`,
`DayOfMonth`, `Quarter`, `AddMonths` and `LastDay` true - and the rename must
carry all of them.

### 3.2 The month index becomes 3-based, everywhere

Varka's `mp` counts March as 0; Neri-Schneider counts March as 3, which is what
makes the reported month a subtraction with no addition in front of it:

| | today, from `mp` | with `m3` |
|---|---|---|
| month (1-12) | `mp + 3`, minus 12 where `mp >= 10` | `m3`, minus 12 where `m3 >= 13` |

That is one lane op less, and it is why the convention changes rather than
being converted back with a `- 3`: converting back would spend the op this
saves. The consequence is that every reader of the month index moves at once -
`emitChronoMonth`, `emitChronoDayOfMonth`, `Quarter`, `emitMonthStart`,
`emitAddMonths`, `emitChronoLastDay` (PR #78) and VARKA-35's `TruncDate` - along
with `VarkaChrono.MARCH_YEAR_JANUARY`, which becomes 13 on the new axis, and the
scalar twin's `fromEra`, which must keep mirroring the emitted arithmetic.

`VarkaChrono.MARCH_TO_JANUARY_DAYS` (VARKA-48) does **not** move: it lives on the
day-of-year axis, which this task does not touch. That is a small piece of
evidence that VARKA-48 put the year's January test on the right axis.

### 3.3 The tails

```
month        = m3 blended with m3 - 12 where m3 >= 13
dayOfMonth   = ((num & 0xFFFF) * 31345 >>> 26) + 1
monthStart   = (979 * m3 - 2919) >>> 5
truncToMonth = d - dom0                      // no monthStart at all
```

The last line is worth calling out for VARKA-35: `trunc(d, 'MONTH')` is today
`d - doy + monthStart(mp)`, and becomes a single subtraction of the zero-based
day of month, which this block already has.

### 3.4 Registered op counts, from `rem`

Deterministic, and section 5 asserts them off the class file rather than from
this table:

| tail | today | this task | delta |
|---|---|---|---|
| `month` | 7 | 5 | -2 |
| `dayofmonth` | 10 | 6 | -4 |
| `quarter` | 10 | 8 | -2 |
| `trunc(d, 'MONTH')` | 10 | 7 | -3 |
| `emitMonthStart`, per call | 4 | 3 | -1 |
| `year` | unchanged | unchanged | 0 |
| `dayofyear` | unchanged | unchanged | 0 |

`add_months` and `last_day` call `emitMonthStart` twice each and go through the
month and day-of-month tails, so they take the largest absolute saving; they
are also the two nodes where a mistake is least likely to be caught by a
bounded test, which is what section 5's sweep is for.

`year` and `dayofyear` are zero for the same reason: both elide, so neither
emits the step this task replaces, in either form. What does move for them is
the *kept* side of the A/B, from four ops to two, which is section 6.1's
prediction 6 and not a shipped number. `DAY_OF_YEAR_WEIGHT` (51 since PR #64
was integrated against VARKA-48) therefore stays 51 - stated because a weight
that does not move is as easy to get wrong by adjusting it as one that does.

## 4. Files

| file | what |
|---|---|
| `VarkaChrono.java` | `MONTH_NUM_M`/`MONTH_NUM_ADD`/`MONTH_NUM_K`, `DOM_M`/`DOM_K`, `MONTH_START_M`/`MONTH_START_SUB`/`MONTH_START_K`, each with its own domain and its verification in the javadoc; `MARCH_YEAR_JANUARY` to 13; `fromEra`'s month block |
| `VarkaLoopEmitter.java` | `emitMonthNumerator`; `emitChronoMonth`, `emitChronoDayOfMonth`, `emitMonthStart`, the `Quarter` arm, `emitAddMonths`, `emitChronoLastDay` on the new axis; `tailReadsMarchMonth` renamed with its javadoc; the `CHRONO_WEIGHT`/`ADD_MONTHS_TMP_COUNT`/`LAST_DAY_TMP_COUNT` accounting re-derived, not adjusted by eye (`DAY_OF_YEAR_WEIGHT` is expected to stay 51; re-count it rather than assume it) |
| `VarkaEmitOptions.java` | `neriSchneiderMonth`, so the old block stays a live reference variant the differential checks against, per `FloorMod7`'s precedent |
| `VarkaChronoSuite.scala` | the three identities over their exact domains (366, 65536 and 12 cases), and the existing exhaustive sweep, unchanged and still green |
| `VarkaLoopEmitterSuite.scala` | section 5 |
| `VarkaEmitterParityBenchmark.scala` + committed results | section 6 |
| `VARKA-48.md` 9.1, `VARKA-35.md` 7.3, `m4/PLAN.md` | the elision's new size; `trunc(d, 'MONTH')`'s new form; the row and the catalogue entry |

## 5. Tests

1. **The three identities**, each over its whole domain through the shipped
   constants: the month and day-of-month pair over `[0, 365]`, the `/2141`
   magic over `[0, 65535]`, and the month-start map over the twelve months.
2. **Op counts** off the class file (`VarkaEmitterTestSupport.invocationCount`,
   added by VARKA-48), one assertion per row of the 3.4 table.
3. **Both variants agree** on every shape the emitter suite drives, at both
   widths - the `FloorMod7` reference-variant pattern, which is what makes the
   old block worth keeping rather than deleting.
4. **The exhaustive sweep** over all 16777216 covered days, five fields, both
   variants, both sharing modes, both widths. This is the gate that matters:
   `add_months` and `last_day` recompose through these constants, and a
   month-axis mistake in either is exactly the kind of thing a boundary set can
   miss and a sweep cannot.
5. **The pinned fixtures**, expected to move: every calendar node's emitted
   bytes change, so the line map and the ladder both re-pin, and the ladder's
   unshared boundary moves for a **fourth** time.

## 6. The measurement

The same instrument and the same discipline as VARKA-48, for the same reason its
section 9.2 records: the parity file must be regenerated by the task that
changes the bytes.

Adjacent A/B cases in the `year` section for `dayofmonth` and `month`, plus the
four-field shape, null-free and mixed-null each, at both widths, five
regenerations compared by minimums, with `per-row LocalDate year` as the in-run
control that says whether the machine moved.

### 6.1 Predictions, registered before the run

1. The op counts in 3.4 land exactly; a miss there is a bug, not a surprise.
2. `dayofmonth`, null-free, gains 4-9% at AVX-512 - four ops off a body of
   about forty, on the same latency-bound dependent chain VARKA-48's four came
   off, where the measured answer was 1.01x. **Inside noise is the expected
   outcome again**, and the default ships on the op count.
3. `year` does not move at either width, in either direction. It shares only
   `rem` with this block.
4. The four-field shared kernel gains more than any single-field one, because
   it pays the month block once and the tails three times.
5. The unshared `HugeMethodLimit` crossing moves out again, to 21 or 22.
6. VARKA-48's elision shrinks from four ops to two, for **both** nodes that take
   it - `year` and `dayofyear` - and `VARKA-48.md` 9.1's number is
   superseded rather than contradicted. The elided bodies do not move at all;
   only the kept side of the switch does.

## 7. Risks

1. **The 3-based axis touching seven call sites at once.** The month index is
   read in more places than any other prefix value. Mitigated by the rename:
   `marchMonth` becomes `monthIndex3`, so a call site left on the old
   convention does not compile rather than computing a month three off.

   This plan originally named two in-flight call sites, PR #64 and PR #78.
   **Only PR #78 is one**, and the correction is worth keeping rather than
   quietly deleting, because it was an assumption about another task's lowering
   made without reading it: `dayofyear` was assumed to read the month index and
   does not. `last_day` is the real exposure and the worst of the seven - two
   `emitMonthStart` calls plus both the month and day-of-month tails - so this
   task should start after PR #78 merges, and edit that call site directly
   rather than hand-resolve it in a merge.
2. **`MARCH_YEAR_JANUARY` changing value.** A constant whose meaning is
   unchanged but whose number moves is the classic silent-merge hazard. It gets
   a javadoc sentence naming the axis, and the identity test in section 5 fails
   loudly if the two axes are ever mixed.
3. **A month-axis error surviving the boundary set.** See test 4; `add_months`
   and `last_day` are the exposure.
4. **Nothing to gain being mistaken for something to lose.** If the measurement
   comes back flat, the op-count reduction still ships - the same argument VARKA-48 made and measured.
5. **Scope creep into the era and year steps.** Section 2.2 is the boundary,
   and it is a hard one until int64 lanes exist. A reviewer noticing that the
   paper's headline result is not implemented here should read 2.2, not open a
   follow-up.

## 8. Sequencing

Four commits, each green on its own. Commit 1 landed with PR #80; the
corrections in sections 1, 3.1, 3.4, 6.1 and 7 are a follow-up to it, made
before commit 2 rather than folded into the work, so the plan a later reader
diffs against is the one the work actually started from.

Commit 2 waits on PR #78. Section 7's risk 1 says why: `emitChronoLastDay` is
the worst of the seven month-axis call sites, and editing it directly on master
is a different job from hand-resolving it inside a merge.

**Correction to commit 2's scope, found while writing it.** The list below says commit 2
carries "the 3-based axis", and it cannot: `MARCH_YEAR_JANUARY` is read by the *emitter* in
five places (`emitChronoMonth`, `emitChronoDayOfMonth`, `emitMonthStart` twice, and the
`Quarter` arm), so moving it to 13 would break every calendar shape before the emitter commit
lands. The axis therefore arrives as a **parallel** constant, `MONTH3_JANUARY`, with
`MARCH_YEAR_JANUARY` left in place; commit 3 moves the emitter onto the new axis and deletes
the old one. What commit 2 does move is the scalar twin's own month block, whose outputs are
unchanged and are checked against the same exhaustive oracle - which is the point of doing it
first.

1. **This plan**, with section 2's admission check. **DONE** (PR #80).
2. **The constants and the scalar twin**: `VarkaChrono`'s new constants, the
   3-based axis, `fromEra`'s month block, and the three identity tests. No
   emitter change, so the case is on record before the code that rests on it.
3. **The emitter**, behind `neriSchneiderMonth`: the numerator, the four tails,
   the two recomposing nodes, tests 2 through 5, the fixtures re-pinned.
4. **The measurement**: the A/B cases, five regenerations at each width, the
   results file, the numbers recorded in section 9 with the default confirmed.

## 9. Outcome

Filled in when the work lands: the op counts against 3.4, the A/B by minimums
at both widths, the ladder's fourth boundary, and which of 6.1's predictions
held.

## 12. Commit 2's outcome

Every constant in section 2.1 was re-verified over its exact domain before being committed,
rather than transcribed from the plan:

| identity | domain | result |
|---|---|---|
| `(num >>> 16) - 3` equals `(5 * doy + 2) / 153` | all 366 days | exact |
| `(num & 0xFFFF) / 2141` equals `doy - (153 * mp + 2) / 5` | all 366 days | exact |
| numerator `2141 * doy + 197913` | all 366 days | peaks at 979378, inside int32 |
| `(x * 31345) >>> 26` equals `x / 2141` | all 65536 values | exact, max product 2054194575 |
| `(979 * m3 - 2919) >>> 5` equals `(153 * (m3 - 3) + 2) / 5` | all 12 months | exact, numerator 18..10787, never negative |

One relationship worth naming because it is load-bearing for VARKA-48 and was not obvious from
the paper: **`monthIndex3 >= 13` and `dayOfYear >= 306` are the same test.** Over the whole
domain the month indices at or after day 306 are exactly 13 and 14, and those before it are 3
through 12. So `MONTH3_JANUARY` and `MARCH_TO_JANUARY_DAYS` have to move together or a year
computed on one axis and a month on the other disagree by a year - which the identity test
asserts on every one of the 366 days rather than leaving to this paragraph.

The scalar twin's month block is now the Neri-Schneider form and its outputs are unchanged,
which the opt-in exhaustive sweep confirms against `LocalDate` over all 16,777,216 covered
days. That is the whole value of doing this before the emitter: the new constants are checked
against the same oracle the old ones were, with no emitted bytecode depending on them yet.

128 catalyst tests pass with the sweeps enabled.

## 13. Commit 3's outcome

The emitter carries both axes behind `VarkaEmitOptions.neriSchneiderMonth`, default on, with
the 0-based lowering kept as a live reference variant on `FloorMod7`'s precedent.

### 13.1 The op counts, against 3.4

Measured off the class file, not reasoned from the helpers:

| tail | 0-based | 3-based | delta | 3.4 predicted |
|---|---|---|---|---|
| `year` | 39 | 39 | 0 | 0 |
| `month` | 40 | 38 | -2 | -2 |
| `dayofmonth` | 43 | 39 | -4 | -4 |
| `quarter` | 43 | 41 | -2 | -2 |
| `last_day` | 71 | 68 | -3 | "largest absolute saving" |
| `add_months` | 121 | 117 | -4 | "largest absolute saving" |

**Prediction 1 holds exactly** for the four registered per-tail figures. The two recomposing
nodes were predicted to "take the largest absolute saving"; they take -3 and -4, and
`dayofmonth` ties `add_months` at -4, so that half of the sentence is wrong - they take a
large saving, not the largest. The reason is that they were expected to save twice on
`emitMonthStart` and only one of their two calls reads the prefix slot; the other operates on
a locally computed month in the inverse direction, which section 2.2 leaves alone.

`CHRONO_WEIGHT` is re-counted from 50 to 40, which is what its javadoc's "re-count it if the
lowering changes shape" asks for. It still exceeds `GROUP_BUDGET` several times over, so no
grouping decision changes. `ADD_MONTHS_TMP_COUNT` and `LAST_DAY_TMP_COUNT` do not move: the
3-based paths reuse the same scratch slots and add none.

### 13.2 Two corrections to the plan

**The scope is smaller than 3.2 implies.** That section lists seven call sites that "move at
once", including `emitAddMonths`. Measured against the code: `emitAddMonths` reads the prefix's
month slot *only* through `emitChronoMonth` and `emitChronoDayOfMonth`. Its own `mp`
arithmetic - `mp2`, the clamp, `emitMonthStart` - is the inverse direction, computed from a
reported month rather than from the prefix, and section 2.2 leaves that alone. So it needed no
change beyond the two tails it already called. `emitChronoLastDay` is the one node whose
month-length arithmetic reads the prefix slot directly, and it is therefore the only place the
axis had to be handled inside a recomposing node.

**Test 5's expectation is wrong: the pinned fixtures do not move.** Section 5 says "every
calendar node's emitted bytes change, so the line map and the ladder both re-pin". The emitted
bytes do change; the fixtures do not, because neither is taken over emitted bytes. The shape
hash is over the IR's canonical rendering, the input counts and `VarkaEmitOptions.canonical()`,
which is empty for `DEFAULTS`; the line map is over the IR's topological schedule. VARKA-24
recorded exactly this property and VARKA-26 is the only task so far that legitimately moved
them, by adding IR node types. VARKA-53 adds none. Both fixtures were left untouched and both
still pass, which is the proof rather than the claim.

### 13.3 What certifies it

The exhaustive sweep, run over all 16,777,216 covered days under **both axes**, both sharing
modes and both switch positions - eight combinations - at 512-bit and at 128-bit. That is what
makes the older lowering a reference variant rather than dead code: the two compute the same
four fields through different constants on differently-based month indices, so agreeing with
`LocalDate` over the whole range is also them agreeing with each other over it.

`last_day` is swept separately on both axes for the reason 13.2 gives: it is the one node
where the axis reaches inside a recomposing lowering, so it is the one most worth sweeping
twice.

129 catalyst tests with the sweeps enabled, at both widths.

### 13.4 One thing section 4 promised and commit 3 did not do

Section 4's files table lists `VARKA-35.md` 7.3 - "`trunc(d, 'MONTH')`'s new
form" - and commit 3 did not touch it. Corrected here rather than left for VARKA-35 to discover, because the staleness is the dangerous kind: 7.3 described
`t[5]` as holding `marchMonth`, and an implementer following it would emit the
0-based lowering against a slot that now holds a numerator. That compiles, and
produces plausible wrong dates. 7.3 now carries the numerator's slot layout,
both axes' `MONTH` derivations, and the single-subtraction form the new axis
allows: `trunc(d,'MONTH') = d - dom0`, since the numerator's low half already is
the zero-based day of month and the `+ 1` the day tail ends with is one this
lowering would only subtract again.

## 14. Commit 4's outcome: the measurement, and the predictions scored

Adjacent A/B cases, interleaved by null pattern, regenerated in one run on the
merged tree. M rows/s, higher is better.

**AVX-512:**

| shape | Neri-Schneider | 0-based | gain |
|---|---|---|---|
| `dayofmonth`, null-free | 2265.7 | 1997.1 | +13.5% |
| `dayofmonth`, mixed nulls | 2139.3 | 1911.6 | +11.9% |
| `month`, null-free | 2260.0 | 2167.8 | +4.3% |
| `month`, mixed nulls | 2077.9 | 2063.0 | +0.7% |
| four-field, null-free | 517.9 | 499.9 | +3.6% |

**128-bit:**

| shape | Neri-Schneider | 0-based | gain |
|---|---|---|---|
| `dayofmonth`, null-free | 742.4 | 659.3 | +12.6% |
| `dayofmonth`, mixed nulls | 734.8 | 651.8 | +12.7% |
| `month`, null-free | 748.2 | 707.7 | +5.7% |
| `month`, mixed nulls | 735.2 | 705.8 | +4.2% |
| four-field, null-free | 179.9 | 171.4 | +5.0% |

Stdevs are 0-1 ms on 9-31 ms best times and the pairs ran adjacent under one JIT
and thermal state, so every gap here is outside the run's resolution. Each shape
gains slightly *more* at 128-bit than at 512, which is the mechanism showing
itself: half the lanes is twice the loop iterations for the same rows, so a
per-iteration saving is paid back more often.

### 14.1 Predictions, scored

Three held, one was beaten, two missed.

1. **Held.** The op counts landed exactly (commit 3, section 13.1).
2. **Beaten.** 4-9% was predicted for `dayofmonth` null-free at AVX-512, with
   "inside noise is the expected outcome again" registered as the likely result.
   It is +13.5% there and +12.6% at 128-bit, above the predicted band, and it
   reproduces under mixed nulls at both widths (+11.9%, +12.7%). This is the
   tail that stops running `emitMonthStart` forwards entirely, and it is the
   most stable number in the task.
3. **Held.** `year` does not move; the op-count test pins that it cannot.
4. **Missed.** The four-field shape was predicted to gain more than any
   single-field one, "because it pays the month block once and the tails three
   times". It gains the least: +3.6% and +5.0%. The reasoning had the
   denominator backwards - fragment sharing already collapses its four prefixes
   into one, so it saves the block *once* spread across four outputs and a body
   five times the size, while `dayofmonth` alone saves it on every row of a
   small body. Paying once is why it gains little, not why it gains most.
5. **Missed.** The unshared `HugeMethodLimit` crossing was predicted to move out
   to 21 or 22 outputs. It stays at 19 fits / 20 crosses, **on both axes**. The
   numerator does shrink the epilogue - 7953 bytes to 7804 at nineteen outputs -
   but an output costs roughly 400 bytes there, and two ops saved per date's
   fragment over five fragments is 149. A boundary measured in whole outputs
   does not move for a saving smaller than one.
6. **Held.** VARKA-48's elision shrinks from four ops to two, for both nodes that
   take it (commit 3).

### 14.2 What the default rests on

The op counts, which are proven and were never in doubt, and now a timing that
agrees with them at both widths. Prediction 2's caveat - that the default would
have shipped on the op count even if the timing came back flat - did not need to
be used.

The 0-based lowering stays as a reference variant. It is not dead code: the
exhaustive sweep runs both axes over all 16,777,216 covered days under both
sharing modes and both switch positions, which is how the two check each other
rather than only checking against `LocalDate`.
