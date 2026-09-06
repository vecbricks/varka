# Task 69: an upward limit for the civil-from-days decomposition

## 1. Where this came from

`PLAN_MILESTONE_4.md` row 69 and the debt register entry "A shift above a
guarded day producer declines conservatively", both opened by the review of
task 60 (PR #128).

Task 60's review found a composition hole: `dayRange` admitted a subtree
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
wrongly before task 60's fix, on master as well.

## 2. The admission check, to do first

This task rests on a claim that is **not yet established**, and the whole of
section 2 is establishing it. Do not write the constant first.

`NARROW_MAX_DAYS` is `(1 << NARROW_ERA_K) - 1 - NARROW_BIAS`: the ceiling of
the *shift domain* of the era step, which computes `(w * NARROW_ERA_M) >>>
NARROW_ERA_K` with `NARROW_ERA_M = 114` and `NARROW_ERA_K = 24`. Task 60's
review observed that what actually binds above is not `w < 2^NARROW_ERA_K`
but the multiply's own overflow, `w * NARROW_ERA_M < 2^31`, which is the
looser of the two - `2^31 / 114` is about 18.8 million against `2^24`'s 16.8
million, roughly 5600 years of headroom.

That observation is a starting point, not a result. What has to be shown:

1. **The identity, not merely the absence of overflow.** That `w * 114`
   does not overflow says nothing on its own about whether `(w * 114) >>> 24`
   is still the era over the extended range. Prove the era identity over the
   whole extended domain, the way `PLAN_TASK_53.md` proves its three
   identities over their exact domains.
2. **Every step downstream of the era.** The era is the first step; the
   year-of-era, day-of-year, month and day-of-month steps all run on its
   remainder. Each has its own exactness domain, and the binding one may not
   be the era's. `VarkaChrono.narrowed`'s contract is the conjunction.
3. **Both `julianMap` forms.** The Julian map (task 54) and the
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

* The four pinned declines in `VarkaExpressionCompilerSuite`, "task 60
  review: an upward shift over a guarded day offset declines
  conservatively", flip from `!fuses` to `fuses`. That test exists to be
  flipped by this task; rewrite its comment in the past tense rather than
  deleting it, so the register entry it names stays traceable.
* The downward siblings in the same test are unmoved, and the composition
  test beside it ("a column count over a column day offset does not escape
  the narrow range") stays green: `add_months`' `31 * MONTH_ARITH_MAX_MONTHS`
  is far past even the loosened ceiling.
* The boundary at `+-1` of the new constant, in the compiler suite, derived
  from the constant rather than retyped, the way task 52's shift tests are.
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
