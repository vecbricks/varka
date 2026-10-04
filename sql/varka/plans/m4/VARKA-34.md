# VARKA-34: `dayofyear`

One of four small vocabulary tasks (34-37) written as recipes for a cheap
agent, in the shape VARKA-33 established. Read `VARKA-33.md` section 3
first: the mechanics of adding a node type - the five emitter switches, the
compiler arm, the two pinned fixtures - are the same here and are not repeated
in full.

**Depends on VARKA-26.** Everything below builds on `VarkaChrono` and
`VarkaLoopEmitter.emitChrono`, so this task cannot start until VARKA-26 has
merged.

If you find yourself making a design decision, stop and say so in the pull
request instead of choosing. Everything here has been decided and checked.

**VARKA-32 may move the plumbing; it will not move the arithmetic.** A separate
task is measuring whether one shared decomposition can feed several fields,
instead of each node carrying its own copy of it. If that goes ahead, the tails
will read `doy` and `dom` from somewhere else - and every formula in section 2
will be unchanged, because what moves is where the intermediates live, not what
they are. So do not restructure `emitChrono`, do not try to share anything with
the other tails, and do not read the duplicated decomposition as a bug to fix.
Write the tail this recipe describes and let VARKA-32 do its own job.

## 1. What you are building

`dayofyear(d)` returns 1 for 1 January and 365 or 366 for 31 December.
Spark's reference is `DateTimeUtils.getDayInYear`, which is
`daysToLocalDate(days).getDayOfYear` - `LocalDate`, which is exact at every
int day and never wraps. So unlike VARKA-33, the ordinary rule applies: **do
not let any intermediate overflow.** Nothing below can.

## 2. The lowering

`emitChrono` already computes, for every calendar node, a day-of-year `doy` in
a **March-based** year: `doy == 0` is 1 March, and the year runs to the
following February. January-based day-of-year is one comparison away:

```
L        = leap(reported year)                 // section 2.1
dayofyear = doy >= 306 ? doy - 305 : doy + 60 + L
```

306 is the number of days from 1 March to 31 December, so `doy >= 306` is
exactly "January has arrived" - the same test the `year` tail already uses,
and VARKA-26 verified that equivalence exhaustively. 60 is the day-of-year of
1 March in a common year (31 + 28 + 1).

**Verified, not assumed**: this formula was checked against `java.time` over
all 3,652,059 days of `0001-01-01..9999-12-31` during planning - zero
mismatches. The first draft used 59 instead of 60 and failed on 84% of days;
if your differential fails everywhere at once, this constant is the first
place to look.

### 2.1 The leap flag, which three of these four tasks need

The reported (January-based) year is a leap year iff the usual rule holds, and
it is computed with two magic multiplies over a **biased** year, because the
magic form `(v * M) >>> k` requires a non-negative `v` and Varka's covered
range starts at year -12800:

```
y   = year + 13200          // 13200 is a multiple of 400, so leapness is unchanged
L   = ((y & 3) == 0) && ((y mod 100) != 0 || (y mod 400) == 0)
```

with `mod` done as `v - ((v * M) >>> k) * divisor`, round-down, so the
quotient can undershoot by one and the remainder must be checked against
**both** `0` and the divisor (`r == 0 || r == divisor`), not just `0`:

| divisor | M | k | exact (no-overflow) for `v` up to |
|---|---|---|---|
| 100 | 41943 | 22 | 51150 |
| 400 | 41943 | 24 | 51150 |

`y` reaches at most 46334 over the covered range, inside both bounds. These
are round-down magics, not exact-quotient ones - `M=167773` at `k=24`/`26`
looks exact under unbounded-precision arithmetic (as `verify_chrono_tails.py`
confirms, since Python integers do not overflow) but violates the
`v * M < 2^31` no-overflow bound every other magic in `VarkaChrono` respects:
at `y=46334`, `46334 * 167773` is over three and a half times past `2^31`.
Section 7 of this file's own Outcome records finding this the hard way; do
not copy `167773` from an earlier draft or from `VARKA-37.md` without
checking it carries the same fix. 13200 rather than 12800 because VARKA-37
needs `year - 1` to stay non-negative too, and one constant for all four
tasks is easier to keep right than two.

Do **not** try to shortcut this from `yoc` and `century` with bit tricks. It
is tempting - `y mod 4 == yoc mod 4` and so on - and it goes wrong at the
century and era boundaries where the reported year has already rolled over.
Ten honest ops beat a clever four that fail two days a century.

## 3. The edits

Follow `VARKA-33.md` section 3 for the mechanics. The differences:

* **IR** (`VarkaVectorIR.java`): add `DayOfYear(VarkaVectorIR days)` beside
  `Quarter`, rendering as `(dayOfYear <days>)` in both `canonical` and
  `canonicalShallow`. Copy `Year` exactly - it is a unary node with the same
  shape.
* **Emitter**: `DayOfYear` is a *chrono* node, so unlike VARKA-33 it does not
  get its own `emitValue` body. Instead:
  - add it to `isChrono` so it gets the chrono slots and the `CHRONO_WEIGHT`;
  - add the four routine cases (`childrenOf`, `analyze`, `planWordRef`, and an
    `emitValue` arm that calls `emitChrono` exactly as the `Year` arm does);
  - add a `case DayOfYear n -> {...}` to the switch **inside** `emitChrono`
    that picks the per-field tail, emitting section 2's formula from `rem`
    (which holds `doy` at that point) and the leap flag.
* **The leap flag** does not exist in `emitChrono` yet. Add a private helper
  beside the other emit helpers - `emitLeapFlag`, leaving a `VectorMask` -
  and give the chrono temporaries one more slot for the biased year. VARKA-35
  and VARKA-37 will both call it, so write it to be called, not inlined.
* **Compiler** (`VarkaExpressionCompiler.scala`): one arm beside the other
  four calendar arms, `case DayOfYear(child) => ... .map(new IRDayOfYear(_))`.

## 4. The tests

1. `VarkaLoopEmitterSuite.evalValue` gains a `DayOfYear` arm whose oracle is
   `LocalDate.ofEpochDay(v.toLong).getDayOfYear` - the definition, not your
   formula.
2. A test in the shape of VARKA-26's chrono one, driving the boundary set that
   matters here: 1 January and 31 December of a leap year (2000, 2024), of a
   common year (2023), of a century non-leap year (1900) and a century leap
   year (2000), 28 and 29 February, 1 March, and the ends of the covered range
   (`VarkaChrono.NARROW_MIN_DAYS`, `NARROW_MAX_DAYS`).
3. The two pinned fixtures, extended and re-pinned per VARKA-33 section 3.4.
4. One compiler test and one differential test, both copying the shapes VARKA-26 added for `year`.

## 5. What to run, and what must pass

The command block and acceptance criteria are VARKA-33's section 4, unchanged.
One addition: a batch containing a day outside `VarkaChrono`'s covered range
must still decline rather than answer, exactly as `year` does - the guard is in
`emitChrono` and you inherit it, but the differential should prove it, so
include the `date_add` push-past-the-range case VARKA-26 added.

## 6. Explicitly out of VARKA-34

* **`extract(DOY from d)`** - it desugars to this same node, so it is covered
  for free; do not add a second path for it.
* **`dayofweek`, `weekofyear`** - already shipped and VARKA-37 respectively.
* **Hoisting the leap flag out of the tails.** Write `emitLeapFlag` so VARKA-35, VARKA-36 and VARKA-37 *can* call it, which is a shared helper; do not make
  `emitChrono` compute it once for every node, which is a restructure - see the
  note at the top of this file.

## 7. Outcome

Built as planned: `VarkaVectorIR.DayOfYear`, wired through the five emitter
switches and `emitChrono`'s tail switch, a `VarkaExpressionCompiler` arm, and
tests in all four files section 4 named. This worktree was branched directly
off `master` (post PR #60, which already carries VARKA-26) rather than off a
merged VARKA-33 - VARKA-33 was still sitting in its own unmerged worktree at the
time, so this task never saw `NextDay` or the `LANEWISE_UNARY` descriptor
`VARKA-33.md` section 3 mentions. That is not a deviation: the "follow
section 3 for the mechanics" pointer is about the shape of the edit (five
switches, a compiler arm, two pinned fixtures), which does not depend on
`NextDay` existing.

**One step in this recipe was wrong, and it was a real defect, not a
clarity problem.** Section 2.1's leap-flag constants (`M=167773` at `k=24`
for mod 100, `k=26` for mod 400) are exact only under unbounded-precision
arithmetic. They silently violate the `v * M < 2^31` no-overflow bound this
very file's own opening javadoc derives and every other magic in it respects:
at the covered range's top (biased year 46334), `46334 * 167773 ~ 7.77e9` -
over three and a half times past `2^31`. `plans/verify_chrono_tails.py`'s
`mod_magic` helper never caught this because Python integers do not overflow,
so the script confirmed the formula was mathematically exact without ever
computing what a 32-bit lane multiply actually produces. `VarkaChronoSuite`'s
boundary/random sweep did catch it - `Fields[year=14500, ..., dayOfYear=280]`
against `LocalDate`'s `279` on day 4576767 - because that suite's oracle is
`LocalDate` compared against `VarkaChrono`'s own Java arithmetic, which is
genuine `int` arithmetic and genuinely overflows where the constant is wrong.

The fix: round-down magic constants that respect the bound (`M=41943` at
`k=22` for mod 100, `k=24` for mod 400 - equal by construction, since
`400 = 100 * 4` and `k` is two higher, which cancels exactly). Round-down
means the quotient can undershoot by one, so unlike a same-shape `== 0` test
the corrected form checks the remainder against **both** `0` and the divisor
(`r == 0 || r == d`), the same "round-down plus one correction" idiom
`emitCarry`/`CENTURY_M` already use elsewhere in this file, adapted to a
modulo test rather than a quotient one. Verified over the full covered range
(years -12800..33134) against the reference leap rule with a Python
simulation of true 32-bit truncating multiplication, not the unbounded
arithmetic the first pass trusted - zero mismatches.

That fix needed **one more scratch slot than the plan anticipated**: ten
chrono temporaries in total (eight int-vector temporaries plus two masks),
not the nine section 3 predicted (seven int-vector temporaries plus two
masks, after adding one slot for the biased year). The corrected leap test
also needs a slot to hold each remainder across its two-way equality check,
since it is read twice - that tenth slot is the one section 3 did not
anticipate.

Everything else held exactly as written: the `doy >= 306 ? doy - 305 :
doy + 60 + L` formula, `MARCH_TO_JANUARY_DAYS = 306` and
`MARCH_DAY_OF_YEAR = 60`, and the "do not hoist the leap flag into
`emitChrono`'s main body" instruction - `emitLeapFlag` is a private helper
called only from `DayOfYear`'s case, ready for VARKA-35 and VARKA-36 to call
(`VARKA-35.md` names it directly; `VARKA-36.md` needs its boolean
output for February's special case) and not forced on
`Year`/`Month`/`DayOfMonth`/`Quarter`. VARKA-37's `weekofyear` does **not**
call `emitLeapFlag` at all - its `weeksIn` formula computes a day-of-week-style
value from the same 13200 bias directly (divisions by 4, 100, 400), and
`VARKA-37.md` section 3 says to add that as its own `emitWeeksInYear`
helper "beside `emitLeapFlag`", not on top of it. The first version of this
paragraph said "35 and 37", conflating "shares the same bias constant" with
"calls this helper" for VARKA-37 - caught by this task's own code review.

**Cross-checked against VARKA-40's overflow finding, and the corrected
constants hold.** While this review was in flight, VARKA-40 found the same
class of bug in its own new magic constants and flagged, correctly, that the
overflow bound depends on whether the emitted shift is signed or unsigned -
for the unsigned shift this class uses, the safe bound is `v * M < 2^32`, not
the `2^31` this class's comments (conservatively) state. Checked rather than
assumed: `emitMagic` hard-codes `LSHR` (`emitShift(cb, "LSHR", k)`), matching
`VarkaChrono`'s scalar `>>>` exactly, so there is no scalar/emitted mismatch
to worry about here specifically. Re-ran the verification as an exhaustive,
32-bit-truncation-aware simulation (not the unbounded-precision check that
missed the first bug) over every one of the 45,935 years `narrowed` covers,
computing `biased * M` mod `2^32`, an unsigned `>>> k`, and the remainder
test exactly as emitted - zero mismatches against Python's `calendar.isleap`.
The largest product either magic reaches is `46334 * 41943 = 1,943,386,962`,
comfortably under even the conservative `2^31` bound (some 9% of headroom
left), let alone the `2^32` the unsigned shift actually allows - so this
constant does not carry the defect VARKA-40 found in its own.

**Pinned values moved**, both re-pinned here as the update rule requires:
`VarkaLoopEmitterSuite`'s line map key (line 21 `(dayOfYear 1)` inserted, the
`if`'s line moving 25 -> 27) and `VarkaShapeCacheSuite`'s shape hash,
`041e35db20d62e91` -> `e8314287849e8cf8`.

**Review pass, addressing 13 findings.** A `/code-review` run against this
commit found thirteen issues. All were fixed except one, investigated and
deliberately left as-is with the reason recorded:

1. **Real risk, fixed.** `CHRONO_WEIGHT` stayed flat at 50 even though
   `DayOfYear`'s tail is heavier than the other three fields' - about 70 real
   ops by exact instruction counting (the shared ~40-op prefix plus
   `emitYearValue`'s 6, `emitLeapFlag`'s 19, and the January-based blend's 5),
   past the 59-op width this file's own comments call "healthy" and close to
   the 64-op loop VARKA-26 measured triggering a ~10 second compile stall.
   Gave it its own `DAY_OF_YEAR_WEIGHT = 70`. Since both weights already
   exceed `GROUP_BUDGET`, this changes no grouping decision today - a lone
   `DayOfYear` output already got its own loop method either way - but
   whether that lone method's real size is itself a live compile-stall risk
   is VARKA-43's question ("what bounds a loop method inside one output"),
   not answered here.
2. **Real risk, fixed.** The outcome's "nine int-vector chrono temporaries,
   not eight" was itself wrong: the shipped layout is ten slots total (eight
   int-vector plus two masks), not nine. Corrected above.
3. **Real risk, fixed.** Section 2.1's own derivation table still listed the
   disproven `M=167773` constants and claimed no correction step was needed.
   Replaced with the shipped `M=41943` constants and the correction the code
   actually needs, so a future reader copying section 2.1 copies the right
   thing.
4. **Real risk, fixed, cross-plan.** `VARKA-37.md`'s own `weeksIn` table
   carried the identical disproven constants for the same divisors. Corrected
   to the same `M=41943`, with a note on the one real difference from this
   task's use of them: `weeksIn` needs the actual quotient, not just a
   boolean, so a round-down magic there needs an explicit correction step
   VARKA-37 must add, which the original draft did not have.
5. **Documentation bug, fixed, two locations.** `emitLeapFlag`'s javadoc and
   this file's own outcome section both said "ready for VARKA-35 and VARKA-37,"
   conflating "shares the leap-flag bias constant" (true of VARKA-37) with
   "calls this helper" (false of VARKA-37 - it needs its own
   `emitWeeksInYear`). Corrected to name VARKA-35 and VARKA-36, with VARKA-37's real
   relationship spelled out.
6. **Real defect (documentation, no live bug), fixed.** `isLeapYear` had no
   documented input-range precondition, unlike `narrowed`'s explicit
   "undefined outside `inNarrowRange`" contract, even though it silently
   returns wrong answers past the range this class is swept against for the
   same reason `narrowed` does. Documented the same contract; no guard added,
   since the one caller (`fromEra`) never supplies an out-of-range year.
7. **Test-coverage gap, fixed.** No committed test compared the emitted
   kernel directly against `VarkaChrono`'s scalar model - both were only
   checked against `LocalDate` separately, on different day sets, so a
   coordinated drift between the two could pass every existing test. Added
   a committed (non-opt-in) test over a curated-plus-pseudo-random sample
   that runs the emitted kernel and asserts its output against
   `VarkaChrono.narrowed` directly, backing the class doc's "any disagreement
   is an emission bug" promise without needing the exhaustive opt-in sweep.
8. **Reuse debt, fixed.** `DayOfYear`'s tail re-emitted `Year`'s entire
   year-assembly formula instruction-for-instruction. Factored into
   `emitYearValue`, called by both, mirroring `emitChronoMonth`'s existing
   precedent for `Month`/`Quarter`.
9. **Reuse debt, fixed.** `emitLeapFlag` emitted its mod-100/mod-400
   round-down correction twice by hand. Factored into `emitDivisibleBy`,
   called once per divisor.
10. **Design question, investigated and left as-is, with the reason
    recorded.** The review asked whether `isLeapYear`'s bias-plus-two-magic
    scheme is the cheapest design, unmeasured against a boundary-aware
    composition of already-computed `century`/`yearOfCentury`, or against
    `java.time.Year.isLeap`. Two things settle this without a rewrite. First,
    `VarkaChrono`'s own class doc states its role as "the exact lane
    arithmetic the emitter emits, one lane at a time" so a sweep over it is a
    sweep over the algorithm - swapping in `java.time.Year.isLeap` would
    break that contract by making the scalar twin no longer mirror the
    emitted bytecode, so it is not a candidate here regardless of cost.
    Second, a boundary-aware composition off `century`/`yearOfCentury` is
    exactly the shortcut this task's own code (and comment) already names
    and rejects: it needs the *reported* year's leap-ness, which differs
    from the March-based year's whenever the March year has turned January,
    and correctly handling that rollover needs conditionally reading either
    this March-year's or the next one's century/yearOfCentury - a
    non-trivial blend, not a quick edit, and exactly the kind of subtle
    boundary logic this task's own comment warns goes wrong under a hasty
    rewrite. Building and measuring it properly is task-26-section-11.2-sized
    work, not a fix folded into a review response; left as-is rather than
    risk a hurried rewrite of the exact trap already flagged in the code.
11. **CLAUDE.md convention violation, fixed.** VARKA-34's new section in
    `VarkaChrono.java` was wedged inside the pre-existing "day of era to the
    fields" section, splitting its header from its own record and methods.
    Moved to its own trailing section, after `fromEra`.
12. **Documentation gap, fixed, four locations.** Stale "four calendar
    nodes"/"four fields" doc comments (`VarkaVectorIR.java`'s `Year` javadoc,
    `VarkaLoopEmitter.java`'s `emitChrono` class doc and its `chronoTmp`
    field comment, `VarkaChrono.java`'s two section references) were not
    updated to five when `DayOfYear` was added. All four corrected.
13. **Test-coverage/maintainability gap, fixed.** The new `dayofyear`
    boundary test hand-copied a narrower boundary array instead of extending
    the existing four-field boundary test's roots, and was missing the
    near-epoch days, the March-based epoch origin, 1600-02-29, and the
    absolute SQL `DATE` range endpoints the sibling test covers. Merged
    `DayOfYear` into that test's roots and folded its leap-year-specific
    dates into the shared boundary array, deleting the separate test.

While this review was in flight, VARKA-40 independently found and flagged the
same class of overflow bug in its own new constants and asked that VARKA-34's
corrected leap-flag constants be re-checked against it specifically - see the
paragraph above this section for that cross-check, which confirmed the
constants shipped here do not carry the defect.

**Final state**: 94 catalyst / 127 sql-core Varka tests green at both vector
widths, `catalyst/doc`, `dev/lint-java` and `dev/scalastyle` all pass, no
non-ASCII/no TODO/no line over 100 characters in any changed file. No
committed benchmark number moved (none was expected to: `dayofyear` adds a
node type and shares the existing shape's guard, touching no existing shape).

## The predicted leap-flag collision, and how it resolved

`VARKA-36.md` section 7 predicted that this task and VARKA-36 would each
write an `emitLeapFlag` and collide, and gave the rule: whichever merges
second deletes its own copy and calls the other's rather than leaving two to
drift. What actually happened is that **VARKA-40 got there first** - it needed
the same flag for `emitAddMonths`'s February case and merged ahead of both -
so by the time this branch was rebased past it, master already carried
`emitLeapFlag(cb, y, scratch1, scratch2, remScratch, maskA, maskB,
carryMask)` and this branch still carried its own two-parameter one. VARKA-36
resolved its half the same way in PR #78; this section records this one.

**VARKA-40's survives, and it is not the cheaper of the two.** This task's copy
was 19 emitted ops against VARKA-40's 22, and took two scratch slots against
seven, because it replaced VARKA-40's `emitCarry` correction with a two-way
remainder test (`rem == 0 || rem == divisor`) that needs no corrected
quotient. The deciding difference is range, not cost: the two use the same
magic (41943 at shifts 22 and 24) and differ only in bias - 13200 here,
15200 in VARKA-40's - and both are multiples of 400, so both preserve
leapness. 13200 covers every year `narrowed` can report, but `add_months`
reaches about year -14848, where a 13200 bias goes negative and the magic
multiply is then meaningless. So VARKA-40's is the one that can serve both
callers, and this task's cannot; the swap is in that direction for that
reason and not because it was written first.

The cost of the swap, stated rather than buried: `DayOfYear` now emits 73 ops
rather than 70 (`DAY_OF_YEAR_WEIGHT` updated with it), and its `chronoTmp`
grows from `CHRONO_PREFIX_SLOTS + 2` to `+ 6`, since VARKA-40's helper takes
three scratch vectors and two mask slots where this one took two locals.
`t[6]`, the prefix's carry scratch, doubles as its `maskA` exactly as
`emitAddMonths` does. `VarkaChrono.isLeapYear` and the `LEAP_*` constants stay
as they are - they are the scalar model, swept by `VarkaChronoSuite`, and
their javadoc now says why the model's bias and the emitter's differ.

A better single helper is available and was not taken here: VARKA-40's bias
with this task's two-way remainder test would be 15200-ranged *and* three ops
cheaper. That would rewrite a shipped, tested helper and move
`emitAddMonths`'s emitted bytes, so it is left as a follow-up rather than
folded into a merge fix.

**Verified rather than reasoned**: the opt-in exhaustive sweep
(`-Dvarka.sweep=true`) checks the emitted five-field kernel - `dayofyear`
among them - against `LocalDate` over all 16,777,216 days of the covered
range, under both the shared and unshared prefix, and passes at both vector
widths. 116 catalyst / 131 sql tests green with the sweeps enabled at both
widths; `dev/lint-java` and `dev/scalastyle` clean.

## What landed after this branch was written, and what it changed here

Two things merged to master while this task sat open, and both touched the
op count the section above quotes. They are recorded here because that
section is a snapshot of one moment and re-editing it would erase the
history rather than continue it.

**The perfect-hash leap flag (PR #83).** `emitLeapFlag` became Huffner's
`((y * 1073750999) & 0xC001F00F) <= 126976`, four ops against twenty-two,
taking the year and nothing else. `DayOfYear` dropped the five scratch
locals VARKA-40's helper needed, its slot count fell from
`CHRONO_PREFIX_SLOTS + 6` to `+ 1`, and its weight fell from 73 to 55. This
branch also deleted its own `VarkaChrono.isLeapYear` and the four constants
behind it: master's is exact over a strictly wider domain
(-15200..87299 against this one's narrowed range), so there was nothing to
weigh.

**VARKA-48's month elision (PR #80).** VARKA-48 observed that
`marchMonth >= 10` and `dayOfYear >= 306` are the same test one step apart,
so a tail that only needs the January turn can read it off the day of year
and let the prefix skip the month step. It shipped that for `Year` and
recorded the decision in `tailReadsMarchMonth`.

**`DayOfYear` is the second node with that property**, and this branch is
where that shows up. Its tail computes the reported year (for the leap flag)
and then blends `doy - 305` against `doy + 60 + L` - the March month appears
in neither. So its arm of `tailReadsMarchMonth` is `false`, its call to
`emitChronoYear` passes `rem` rather than `marchMonth` (VARKA-48 changed that
parameter's meaning, and the textual merge did not), and the four ops of the
month step come off: `DAY_OF_YEAR_WEIGHT` is now 51, re-counted rather than
reasoned - `laneOps(loopDense0)` reads 52 with the step kept and 48 with it
elided, and the weight's own basis counts three mask ops the helper does
not. The exhaustive sweep is what certifies the elision here, since a tail
reading an unwritten local would produce a plausible wrong answer rather
than a crash: it runs the five-field kernel, `dayofyear` among them, against
`LocalDate` over all 16,777,216 days of the covered range under both sharing
modes and both switch positions.
