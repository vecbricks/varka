# VARKA-69: an upward limit for the civil-from-days decomposition

## 1. Where this came from

`m4/PLAN.md` row 69 and the debt register entry "A shift above a
guarded day producer declines conservatively", both opened by the review of
VARKA-60 (PR #128).

VARKA-60's review found a composition hole: `dayRange` admitted a subtree
containing a column-offset `date_add`/`date_sub` on the strength of that
producer's runtime guard, without accounting for anything that shifted the
day *above* the producer. `year(add_months(date_add(d, off), m))` therefore
fused and answered year 87585 where the truth is -14848 - both runtime
guards passed on their own operands, and nothing checked the composition.

The fix was to stop treating a guarded producer as a special verdict and
have it contribute the interval its guard actually establishes,
`[NARROW_MIN_DAYS, NARROW_MAX_DAYS]`, so a shift above it widens a known
interval and `admitCalendar` tests the widened one. That is correct in both
directions, and it is what closed the hole.

It is also tighter than it needs to be upward, and this task is that
tightening. These four fused before the fix, are exact when they fuse, and
are residual now:

| shape | shift |
|---|---|
| `year(last_day(date_add(d, off)))` | `+0..30` |
| `year(next_day(date_add(d, off), 'MON'))` | `+1..7` |
| `year(date_add(date_add(d, off), k))`, `k > 0` literal | `+k` |
| `weekofyear(date_add(d, off))`, `yearofweek(...)` | `ThursdayOf`, `+-3` |

The last is the one that matters in practice: `weekofyear` over a column
offset is an ordinary query shape now running on rows. It is listed here
rather than with the two purely-upward shapes because `ThursdayOf` shifts
both ways, so only its `+3` side is recoverable by this task; its `-3` side
keeps declining, as does everything else downward.

**Not in scope, and must keep declining:** `year(trunc(date_add(d, off),
...))` (`-365..0`), a negative literal shift, and the `-3` side of
`ThursdayOf`. Below `NARROW_MIN_DAYS` the lowering is genuinely undefined -
`w = days + NARROW_BIAS` goes negative and `(w * NARROW_ERA_M) >>>
NARROW_ERA_K` reads it as about 4.29e9. The `trunc` shape was answering
wrongly before VARKA-60's fix, on master as well.

## 2. The admission check, to do first

This task rests on a claim that is **not yet established**, and the whole of
section 2 is establishing it. Do not write the constant first.

`NARROW_MAX_DAYS` is `(1 << NARROW_ERA_K) - 1 - NARROW_BIAS`: the ceiling of
the *shift domain* of the era step, which computes `(w * NARROW_ERA_M) >>>
NARROW_ERA_K` with `NARROW_ERA_M = 114` and `NARROW_ERA_K = 24`. VARKA-60's
review observed that what actually binds above is not `w < 2^NARROW_ERA_K`
but the multiply's own overflow, `w * NARROW_ERA_M < 2^31`, which is the
looser of the two - `2^31 / 114` is about 18.8 million against `2^24`'s 16.8
million, roughly 5600 years of headroom.

That observation is a starting point, not a result. What has to be shown:

1. **The identity, not merely the absence of overflow.** That `w * 114`
   does not overflow says nothing on its own about whether `(w * 114) >>> 24`
   is still the era over the extended range. Prove the era identity over the
   whole extended domain, the way `VARKA-53.md` proves its three
   identities over their exact domains.
2. **Every step downstream of the era.** The era is the first step; the
   year-of-era, day-of-year, month and day-of-month steps all run on its
   remainder. Each has its own exactness domain, and the binding one may not
   be the era's. `VarkaChrono.narrowed`'s contract is the conjunction.
3. **Both `julianMap` forms.** The Julian map (VARKA-54) and the
   century-then-year split are both live, and the limit must hold for
   whichever is the default and for the reference variant.
4. **An exhaustive sweep**, against `java.time`, over the extended range and
   a margin past it, in `VarkaChronoSuite` beside the sweeps the other
   `VarkaChrono` limits carry. If the sweep is too large to run per build,
   follow whatever the existing whole-range sweeps do about that.

If the claim does not survive, the honest outcome is to close this task with
the finding recorded and leave the conservative bound in place: it is the
safe direction, and correctness is not the thing being traded here.

## 3. The design, if the check passes

### 3.1 One new constant
A `NARROW_DECOMPOSE_MAX_DAYS` beside `NARROW_MAX_DAYS` in `VarkaChrono`,
derived in source from whichever bound section 2 found binding rather than
typed as a number, with a javadoc saying what distinguishes it from
`NARROW_MAX_DAYS`: the latter is the shift domain the era step's `>>>` needs
and the range the *guards* enforce, this one is how far the decomposition
stays exact once a value is already in hand. Nothing may guard against the
new constant - the runtime guards keep using `NARROW_MAX_DAYS`, because a
producer's own result should stay inside the tighter range.

### 3.2 `dayRange` tests the two directions separately
`admitCalendar`'s `Bounded` arm becomes asymmetric: `lo >= NARROW_MIN_DAYS`
as today, `hi <= NARROW_DECOMPOSE_MAX_DAYS` instead of `NARROW_MAX_DAYS`.
That is the whole compiler change. The decline reason keeps its shape; only
the interval it reports against moves.

### 3.3 Nothing in the emitter
No emitted byte moves: this is a compile-time admission bound. The pinned
oracles and every committed number stay where they are, which is the
cheapest way for the reviewer to see the blast radius.

## 4. Tests

* The four pinned declines in `VarkaExpressionCompilerSuite`, "VARKA-60
  review: an upward shift over a guarded day offset declines
  conservatively", flip from `!fuses` to `fuses`. That test exists to be
  flipped by this task; rewrite its comment in the past tense rather than
  deleting it, so the register entry it names stays traceable.
* The downward siblings in the same test are unmoved, and the composition
  test beside it ("a column count over a column day offset does not escape
  the narrow range") stays green: `add_months`' `31 * MONTH_ARITH_MAX_MONTHS`
  is far past even the loosened ceiling.
* The boundary at `+-1` of the new constant, in the compiler suite, derived
  from the constant rather than retyped, the way VARKA-52's shift tests are.
* A differential over a column offset landing on the new ceiling and one
  past it, checked against the row engine and asserting the fused/residual
  split rather than only the values.
* `VarkaChronoSuite`: the sweep from section 2, plus an assertion pinning
  the ordering `NARROW_MAX_DAYS < NARROW_DECOMPOSE_MAX_DAYS` and that both
  sit inside what the guards enforce.

## 5. Verification

    build/sbt catalyst/Test/compile sql/Test/compile
    build/sbt 'catalyst/testOnly *Varka*' 'sql/testOnly *Varka*'
    JAVA_OPTS="-XX:MaxVectorSize=16" build/sbt 'catalyst/testOnly *Varka*'
    JAVA_OPTS="-XX:MaxVectorSize=16" build/sbt 'sql/testOnly *Varka*'
    dev/lint-java && dev/scalastyle && build/sbt catalyst/doc
    dev/varka_precommit.sh --working-tree

Task-specific gate: `git diff --stat` shows no `benchmarks/` file and no
pinned fixture moving. If either moves, something reached the emitter and
the change is not what this plan describes.

## 6. Outcome

**The admission check passed, and by more than the estimate.** Section 2's
claim was that the multiply's own overflow, `w * NARROW_ERA_M < 2^31`, binds
above rather than the shift domain `w < 2^NARROW_ERA_K` - about 18.8 million
against 16.8 million, which the plan put at roughly 5,600 years of headroom.
Neither is the limit. `eraOf` adds one era when the magic undershoots, and
that correction keeps the era split exact past the point the multiply wraps.
What ends it is an undershoot of *two* eras, which one correction cannot
absorb, and that happens at `w = 20161385`:

| bound | `w` | as an epoch day | in calendar terms |
|---|---|---|---|
| shift domain, `NARROW_MAX_DAYS` | 16777215 | 11382643 | 15 August 33134 |
| multiply overflow | 18837575 | 13443003 | 11 September 38775 |
| the real limit, `NARROW_DECOMPOSE_MAX_DAYS` | 20161385 | 14766813 | 29 February 42400 |

That is 3,384,170 days of headroom over the shipped ceiling, about 9,266
years rather than 5,600. Both checks section 2 asked for are in
`VarkaChronoSuite`: the era identity asserted exact at the bound and failing
one day past it, and an exhaustive sweep of both lowerings - the Julian map
and the century-then-year split - against `java.time` from `NARROW_MIN_DAYS`
to the new ceiling, run under `-Dvarka.sweep=true` beside the suite's other
whole-range sweeps.

**Three of the four pinned declines flipped, not four.** `last_day`,
`next_day` and a positive literal `date_add` over a column offset fuse again.
`weekofyear`/`yearofweek` does not, and section 1 says why while listing it:
`ThursdayOf` shifts `+-3`, so only its `+3` side was ever this task's to
recover, and a shape declines on the union of its directions. The test
carries that as its own assertion with the reason beside it, so the next
reader does not have to re-derive why the fourth stayed put.

**The shipped change is one condition.** `admitCalendar`'s `Bounded` arm
became asymmetric - `NARROW_MIN_DAYS` below, the new constant above - and
nothing else moved. Two main-source files carry it, the constant and that
arm; the other four changed files are tests and fixtures. No emitted byte, no
pinned oracle, no committed number, and no `benchmarks/` file in
`git diff --stat`, which is the blast radius section 3.3 predicted.

**What the differential adds over the compiler suite.** The compiler suite
asserts which shapes are admitted; `varka_dates_narrow_ceiling` asserts the
admitted ones are right. Every row sits inside the range VARKA-52's runtime
guard enforces, so no batch declines at run time and the kernel really
answers, and the literal on top puts the intermediate on
`NARROW_DECOMPOSE_MAX_DAYS` exactly. `year` and `month` at that day agree
with the row engine; one day further the entry is residual with the interval
in its reason ending at `NARROW_DECOMPOSE_MAX_DAYS + 1`.

**What it did not close, and where that went.** The debt-register entry this
task swept named `weekofyear(date_add(d, off))` as the ordinary query shape
the conservatism cost, and that shape is still residual. It cannot be
recovered this way: `NARROW_MIN_DAYS` is exactly `w = 0`, so there is no
headroom below to find. The lever is the other one - VARKA-52's runtime guard
compares against `lo`/`hi` that `emitRangeGuard` already takes as parameters,
and the compiler could pass a floor raised by whatever `dayRange` says the
subtree above the producer subtracts. That is `m5/PLAN.md` 2.22,
VARKA-91, scoped from this outcome rather than noted here, and it generalises
past `ThursdayOf` to the whole `trunc` family.
