# VARKA-36: `last_day`

One of four small vocabulary tasks (34-37) written as recipes for a cheap
agent. Read `VARKA-33.md` section 3 for the mechanics of adding a node
type, and `VARKA-34.md` section 2.1 for the leap flag.

**Depends on VARKA-26** for `emitChrono`, and on **VARKA-34** only for
`emitLeapFlag`. If 34 has not landed you may write that helper here instead -
it is twelve lines - but write it in the same place and with the same
signature VARKA-34 specifies, so the two do not collide.

**VARKA-32 may move the plumbing; it will not move the arithmetic.** A separate
task is measuring whether one shared decomposition can feed several fields,
instead of each node carrying its own copy of it. If that goes ahead, the tails
will read `doy` and `dom` from somewhere else - and every formula in section 2
will be unchanged, because what moves is where the intermediates live, not what
they are. So do not restructure `emitChrono`, do not try to share anything with
the other tails, and do not read the duplicated decomposition as a bug to fix.
Write the tail this recipe describes and let VARKA-32 do its own job.

## 1. What you are building

`last_day(d)` returns the last date of the month `d` falls in.
`last_day(DATE '2024-02-05')` is `2024-02-29`.

Spark's reference is `DateTimeUtils.getLastDayOfMonth`:

```scala
def getLastDayOfMonth(days: Int): Int = {
  val localDate = daysToLocalDate(days)
  (days - localDate.getDayOfMonth) + localDate.lengthOfMonth()
}
```

`LocalDate` again, so exact, no wrapping, and no intermediate below may
overflow. The output is a **date**, not a number - `LastDay.dataType` is
`DateType`, so check the output type in the compiler test the way VARKA-35 does.

## 2. The lowering

`emitChrono` already has the March-based month index `mp` (0 = March, 11 =
February) and the day of month `dom`. The month's length comes from the same
linear form the day-of-month tail already uses:

```
cum(m)  = (153 * m + 2) / 5                    // magic: M = 838861, k = 22
length  = mp < 11 ? cum(mp + 1) - cum(mp) : 28 + L
last_day = d + length - dom
```

`cum(m)` is the count of days from 1 March to the first of March-month `m`, and
the difference of two consecutive values is the length of month `mp`. It is
exact for every `mp` in `[0, 10]` and **wrong at 11**, because the linear form
only models March through January; February is the year's last month and
carries the leap day, so it is the special case. That is why the comparison is
there, and it is not optional.

`L` is the leap flag of the **reported** year, which is the right year for
February precisely because a February date's reported year is already the
following one - `emitChrono` adds that 1 when `mp >= 10`.

**Verified, not assumed**: this formula was checked against `java.time` over
all 3,652,059 days of `0001-01-01..9999-12-31` during planning - zero
mismatches, including every 28/29 February and every century boundary.

Expected size: about a dozen ops on top of the decomposition, most of them the
leap flag.

## 3. The edits

Mechanics per `VARKA-33.md` section 3. Specifics:

* **IR**: `LastDay(VarkaVectorIR days)`, rendering as `(lastDay <days>)`. A
  plain unary chrono node - copy `Quarter`.
* **Emitter**: add to `isChrono`, the four routine cases, and a
  `case LastDay n -> {...}` in `emitChrono`'s tail switch emitting section 2.
* **Compiler**: `case LastDay(child) => ... .map(new IRLastDay(_))` beside the
  other calendar arms. Note that Spark's expression is
  `LastDay(startDate)` - one child, no format argument.

## 4. The tests

1. `evalValue` gains a `LastDay` arm whose oracle is
   `DateTimeUtils.getLastDayOfMonth(v)` - the definition, not your formula.
2. A boundary test over: every month of a leap year and of a common year (so
   all twelve lengths are exercised twice), 28 and 29 February, 1 and 31
   March, a century non-leap year (1900-02-15) and a century leap year
   (2000-02-15), and the ends of `VarkaChrono`'s covered range.
3. The two pinned fixtures, extended and re-pinned.
4. One compiler test, asserting `outputTypes === Seq(DateType)`.
5. A differential test, including a `date_add(last_day(d), 1)` case so the
   `DateType` output is proved to feed further date arithmetic.

## 5. What to run, and what must pass

VARKA-33's section 4, unchanged.

## 6. Explicitly out of VARKA-36

* **`add_months`, `months_between`, `make_date`.** All three need the *inverse*
  of the decomposition - days-from-civil - which does not exist yet. Its
  divisions are by 4 and 100 on small dividends, so it is expressible, but it
  is its own task and not a tail on `emitChrono`.
* **`day_of_month`** - shipped by VARKA-26.

## 7. Outcome

Built as the recipe described - `LastDay` copying `Quarter`'s shape, the four
routine emitter cases, the tail inside `emitChrono`'s switch - with one
addition the recipe did not anticipate: VARKA-34 had not landed, so
`emitLeapFlag` was written locally, per the recipe's own opening
dependency note.

**The escape hatch's own caveat ("write it here instead") did not save this
from the bug it was trying to prevent.** This recipe's leap-flag derivation
(section 2.1 was actually inherited from `VARKA-34.md` section 2.1, quoted
into this file's own section 2 by reference rather than copied) names
`M = 167773` at `k = 24`/`26` for `/100` and `/400`, calling `y`'s bound of
46334 "well inside both bounds." It is not: `46334 * 167773` is over three and
a half times past `2^31`, so a vector lane's signed 32-bit multiply wraps, and
`(y * M) >>> k` returns a wrong quotient for every biased year past roughly
25600 (year 12400) - silently, since the arithmetic never traps. The
differential's own boundary list (1900, 2000, 2100, 2400, and the century
years already in `VARKA-26.md`'s set) all sit under that threshold, so
nothing short of an exhaustive sweep or a deliberately-chosen far-future date
would have caught it; `ProbeLastDay`, a throwaway sweep of the whole covered
range through the real emitted kernel (16,777,216 days), found one mismatch at
day 4576527 (+14500-02-08) and none anywhere else. That sweep is now the
permanent opt-in test in section 4's list, specifically because a bounded
boundary list is what missed this the first time.

The fix is the standard one this codebase already uses for every other large
division in `emitChrono` - round-down magic plus one `emitCarry` correction,
not an exact one-shot magic: `M = 41943` at `k = 22` for `/100` and `k = 24`
for `/400`, chosen so the largest product (`46334 * 41943 = 1,943,386,962`)
stays under `2^31`, and exhaustively verified (zero mismatches, correction
never more than one step) over every `y` in `[0, 46334]` before being written
into `emitLeapFlag`.

**This was already found and already fixed on VARKA-34's own branch (PR #64),
independently, before this task started - its `VARKA-34.md` (unmerged)
carries a fuller account in its own section 7, and its shipped
`VarkaChrono.LEAP_CENTURY_M`/`LEAP_ERA_M` are `41943` at `22`/`24`, the exact
pair this task re-derived from scratch.** The version of `VARKA-34.md` on
`master` - what this task actually read, since VARKA-34 has not merged - still
carries the disproven `167773` derivation; PR #64 will correct it, so no
edit is made to that file from here to avoid fighting that PR's own, more
complete rewrite of the same section. Two things follow for whoever merges
both:

1. **`VarkaLoopEmitter.emitLeapFlag`, written independently by both tasks,
   will very likely collide by name and signature once both branches meet.**
   Whichever of #64 and this task's PR merges second should delete its own
   copy and either call the other's or confirm they are identical (they use
   the same constants and the same round-down-plus-carry shape, so they
   should be) rather than leaving two copies to drift.
2. **The stale `167773` text should not survive into whichever `VARKA-34.md`
   lands last** - PR #64's own version already fixes it; if this task's branch
   is what ends up merged into the shared plan history first for some reason,
   its `VARKA-34.md` copy needs the same correction PR #64 already has.

`SKILLS.md` gains the general lesson (a magic-multiply-then-shift pair can
overflow a 32-bit lane at the top of its range even when a derivation claims
otherwise, and only an exhaustive sweep against the real emitted kernel is
proof) - found independently twice now, which is exactly the signal that it
belongs there rather than in a second task-plan footnote.

No other step of this recipe misled: `LastDay`'s IR shape, the four routine
emitter cases, the `blend`-based length selection and the reuse of `century`
and `yearOfCentury` as scratch all worked as designed on the first pass once
the leap flag itself was correct.

## 8. The predicted collision, and how it actually resolved

Section 7's prediction came true, but not against VARKA-34: by the time this
branch was rebased onto master, VARKA-34 (`dayofyear`) still had not merged,
but VARKA-40 (days-from-civil and month arithmetic) had, and it shipped its
own `emitLeapFlag(y, scratch1, scratch2, remScratch, maskA, maskB,
carryMask)` for `emitAddMonths`'s own February case - same round-down-plus-
carry shape, same `M = 41943` at `k = 22`/`24`, differing only in *how* the
non-negative bias is supplied: VARKA-40's version takes the plain reported
year and adds its own internal `VarkaChrono.YEAR_BIAS` (15200), while this
task's local copy expected the caller to pre-bias by +13200 before calling.
Per section 7's own instruction ("whichever merges second should delete its
own copy... or confirm they are identical"), this task's copy was deleted
during the master-merge and `emitChronoLastDay` (the `emitChrono`-tail
switch's `case LastDay` arm, factored out the way `emitChronoYear`/
`emitChronoDayOfMonth` already were by the same merge) was rewritten to call
VARKA-40's shared helper directly - passing the plain year `emitChronoYear`
leaves, with no local bias - and to reuse `emitMonthStart` (VARKA-40's own
factored-out `cum(m)`) for both `monthStart`/`monthStartNext`, clamping the
second call the way `emitAddMonths` does, since `emitMonthStart`'s magic is
only exact through `mp` 11. The formula in section 2 is unchanged; what moved
is which method's body it lives in, exactly as section 7 anticipated for
VARKA-32's eventual sharing - it happened one merge earlier than expected, and
for a different task. Re-verified by the full opt-in sweep (all 16,777,216
days of `VarkaChrono`'s covered range against `DateTimeUtils.getLastDayOfMonth`,
zero mismatches) rather than by inspection alone.

## 9. What VARKA-48 changed here

VARKA-48 (PR #80) merged while this branch was open. It observed that
`marchMonth >= 10` and `dayOfYear >= 306` are the same test one step apart in
the chain, so a tail that only needs the January turn can read it off the day
of year and let the prefix skip the month step entirely. The visible
consequence for every other task is that **`emitChronoYear`'s last parameter
changed meaning**, from the March-based month to the March-based day of year,
without changing its type - so a textual merge compiles and is wrong.

`emitChronoLastDay` calls it, and the call site was corrected to pass `rem`.
This was not caught by reading the diff; it was caught by running the tests,
and it is worth recording exactly how loudly it failed, because the two
symptoms were very different:

- The missing `tailReadsMarchMonth` arm for `LastDay` throws
  `IllegalStateException` on the first `last_day` compile - the exhaustive
  switch is doing its job, and nothing subtle happens.
- The wrong `emitChronoYear` argument is silent. `mp >= 306` is never true,
  so the reported year is short by one for every date on or after 1 January,
  the leap flag is then computed for the wrong year, and February's length is
  wrong in exactly the years where the two disagree about leapness. Reverting
  the fix to check: `day -5394235: emitted -5394207, DateTimeUtils
  .getLastDayOfMonth -5394208`. Both the bounded test and the opt-in sweep
  fail; neither the compiler nor any type would have.

`last_day` keeps the month step - `tailReadsMarchMonth` is `true` for it,
since the month-length arithmetic is the whole lowering - so no emitted op
count moves and no weight is re-counted here.
