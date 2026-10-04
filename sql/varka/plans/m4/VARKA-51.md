# VARKA-51: remove the per-extraction range guard

## 1. Where this came from

Not a scope-document item and not a measurement, unlike every other unplanned
addition in this milestone (32, 45-48, 49). It came from the owner reviewing
VARKA-36's copy of the guard `emitEra` has carried since VARKA-26 and asking why
it exists at all, given the SQL standard's mandated range is `[0001, 9999]`
and the project's own contract already promises column data arrives inside
it. See `m4/PLAN.md` section 2.21 for the full argument and its
resolution; this file is the executable record of acting on that resolution.

The ruling, in one sentence: **the guard re-verified the same fact at every
calendar extraction reading a value, when the fact usually only needed
establishing once, and the one case where it genuinely was not redundant -
a value a producer node manufactured from unbounded runtime arithmetic -
deserves a guard of its own, at that node, not a blanket check repeated at
every extraction downstream of it.** This task does the removal. VARKA-52
(`VARKA-52.md`) is the replacement, planned but not built.

## 2. What was removed

`sql/catalyst/.../codegen/varka/VarkaLoopEmitter.java`:

* `hasChrono(VarkaVectorIR)` - deleted. Its only caller decided whether a
  loop/epilogue body allocated a guard accumulator (`Slots.guardAcc`); with
  no guard left to allocate for, the method has no reason to exist.
* `planSlots`'s `guarded` computation and the `s.guardAcc = slot++` it gated -
  deleted. `s.guardAcc` is now always null.
* `emitEra`'s guard block - the two `VectorMask` compares against
  `VarkaChrono.NARROW_MIN_DAYS`/`NARROW_MAX_DAYS`, ORed together, ANDed with
  the node's validity word and (in an epilogue) the bounds mask, then ORed
  into `s.guardAcc` - deleted outright. What remains of `emitEra` is only the
  day-of-era arithmetic: `w = days + NARROW_BIAS`, the round-down magic, the
  carry. `emitEra`'s signature dropped the `node`, `dense`, `analysis` and `s`
  parameters it no longer needs.
* `isChrono`'s javadoc updated: it now decides `CHRONO_WEIGHT` only, not
  guarding, since guarding is nothing it drives anymore.

**What was deliberately left alone**, because VARKA-52 is the very next task
and would need every piece of it back immediately:

* `VarkaFusedKernel`'s `int run(...)` ABI and `STATUS_CHRONO_RANGE` constant.
* `emitStatusReturn` and the `DRIVER` mode's OR-across-methods status
  reduction. Both were already written to treat "no node set a guard" as
  "return a constant zero" - `s.guardAcc == null` was already the generic
  case, not a special one added for this task - so removing the one caller
  that used to set `s.guardAcc` required no change here at all. This is the
  detail that keeps this task's blast radius small: the ABI was already
  guard-agnostic infrastructure, not guard-specific code.
* `VarkaKernelEvaluator`'s fallback routing and its
  `numFallbackBatchesDeclined`/`numFallbackBatchesKernel` metrics - unchanged,
  currently unreachable (nothing sets a non-zero status today), and exactly
  what VARKA-52 will exercise again.
* `VarkaChrono.NARROW_MIN_DAYS`/`NARROW_MAX_DAYS`/`NARROW_BIAS`/
  `NARROW_ERA_M`/`NARROW_ERA_K`, `inNarrowRange`, and `narrowed` - all
  unchanged. These describe the arithmetic's actual correctness bound, not
  the guard; `narrowed` is exactly as undefined outside that bound after this
  task as before it. Removing the runtime check does not touch the math.

## 3. What was removed from the test suites

**`VarkaLoopEmitterSuite.scala`:**

* `"a batch holding a day outside the covered range is declined, not answered"`
  renamed to `"a day outside the covered range is no longer declined (VARKA-51)"` and rewritten to assert `status === 0` (computed, not declined) for
  the same two shapes (a lane the vector loop covers, a lane only the
  epilogue covers). The null-row sub-case, which asserted the guard was ANDed
  with validity, was dropped - there is no guard left to AND.
* `"the epilogue's inactive lanes do not decline a batch whose real rows are
  in range"` renamed to `"a chained calendar computation matches across every
  lane-group tail length"` and its comment rewritten to describe the bug it
  used to guard against, historically, rather than a live behaviour. The
  test body is unchanged and keeps real value as a general correctness check
  across non-lane-multiple lengths on a chained node.
* No other test in this file exercised the guard: `"the emitted calendar
  kernel matches LocalDate over its whole range"` sweeps exactly
  `NARROW_MIN_DAYS..NARROW_MAX_DAYS`, where the arithmetic is unchanged and no
  guard would have fired either way, guard or no guard.

**`VarkaDifferentialSuite.scala`** (`sql/core`):

* `"a calendar node inside a fused predicate is computed and guarded like any
  other"` renamed (dropping "and guarded") with its final block deleted - the
  assertion that a `date_add`-pushed-out-of-range date under a filter declined
  the batch (`numFallbackBatchesDeclined > 0`). The rest of the test, which
  checks calendar-vs-calendar predicate fusion and has nothing to do with the
  guard, is unchanged.
* `"a date past the shipped lowering's range falls back rather than answering
  wrongly"` deleted outright. Its entire premise - that Varka and the row
  engine agree on `year(date_add(d, 20000000))` because Varka declines and the
  row engine answers - is false after this task; Varka now computes an answer
  of its own, and nothing here asserts it agrees. Rewriting it to assert the
  new, weaker behaviour (the two engines may now disagree) was rejected
  deliberately: a test that documents "this can now silently return the wrong
  SQL answer" as permanently green is worse than no test, since it invites
  the exact bit-rot the differential suite exists to prevent. The gap is
  tracked in section 4 below and in `VARKA-52.md`, not encoded as a green
  assertion.

Neither `VarkaChronoSuite.scala` nor `ChronoVectorOpsTest.java` (the
hand-written engine-module kernel, `sql/varka/engine`) needed changes.
`VarkaChronoSuite` sweeps `VarkaChrono`'s scalar model, which this task did
not touch. `ChronoVectorOps` is deliberately out of this task's scope - see
section 5.

## 4. The regression, stated plainly

Before this task, a day outside `VarkaChrono.NARROW_MIN_DAYS..NARROW_MAX_DAYS`
reaching any calendar extraction declined the whole batch to the row engine,
per the ghost-fallback contract in `sql/varka/AGENTS.md` ("a Varka failure
degrades to the row engine and never returns a wrong answer"). After this
task, the same day is computed anyway, using arithmetic that is undefined
outside that range, and can silently produce a wrong year, month, day or
quarter. Nothing above debug logging says so.

**Closed by VARKA-52** (`VARKA-52.md` section 10): the compiler now bounds
the day shift under every calendar node and declines an entry whose interval
can leave the range, and a column-offset `date_add`/`date_sub` under a calendar
node carries the guard on its own result. The paragraph below describes the
window as it stood between the two tasks.

This was a real, temporary contract violation, accepted deliberately rather
than found and fixed. It was reachable through `date_add`/`date_sub` with a **literal**
offset past the narrowed range's slack around the column contract (more than
8449747 days forward or 4675410 back - nothing bounds a literal day offset;
the first version of `VARKA-52.md` wrongly assumed the compiler did), and,
once PR #62 lands, with a **column** offset of any size - either pushing a
date's day count past roughly 33134 CE or before roughly -12800 CE - `year`,
`month`, `dayofmonth`, `quarter`, `dayofyear`, `last_day` and `add_months`
directly on a column, or on each other, cannot produce such a day on their
own, since `add_months`'s own literal-month-count bound (VARKA-40) keeps its
output inside the range whenever its input was (VARKA-52 found one refinement
to that: `last_day` and `add_months` can carry a date up to 30 days, or 31 per
month, past an input that itself passed the check, which its analysis charges
for). It closed when VARKA-52 landed the compile-time interval analysis and the
guard on the column-offset arithmetic nodes themselves.

### 4.1 A side effect found while merging past PR #72: two compile-cliff numbers moved

PR #72 (VARKA-32 step B1) landed on master while this task was still open, and merging
its result into this branch broke a test with a message that named the exact problem:
`VarkaLoopEmitterSuite`'s `"sharing the prefix moves the epilogue's HugeMethodLimit
crossing from 17 outputs to 40"` failed because 40 shared outputs, which used to cross
the 8000-byte limit, now fit in 7537 bytes.

The reason is structural, not a merge artifact: the guard this task removes lived in
`emitEra`, which both the unshared and the shared (`emitChronoPrefixOnce`) paths call.
Deleting it shrinks every calendar node's emitted bytecode regardless of which path
reaches it, so both of VARKA-32 step B1's own committed compile-cliff numbers - 17 outputs
unshared, 40 shared - moved again, independently of anything VARKA-32 did. Re-measured with
the same harness (`VarkaEmitterTestSupport.codeSize` against `epilogueMasked`):

| outputs | dates | unshared | shared |
|---|---|---|---|
| 16 | 4 | 6793 | 3061 |
| 17 | 5 | 7297 | 3565 |
| 18 | 5 | 7680 | 3637 |
| 19 | 5 | **8073** | 3719 |
| 40 | 10 | 20485 | 7537 |
| 44 | 11 | 22875 | **8630** |

The crossing moves from 17/40 to **19/44**. The full ladder, alongside the pre-task-51
one it replaces, is in `VARKA-32.md` section 7.1's update; the test now asserts the
new numbers and its title carries them. `m4/PLAN.md`'s debt register (the
`GROUP_BUDGET` entry) and VARKA-32's own status-table row both carry a pointer to this.

This is exactly the "numbers moving under the task's own feet" risk the milestone
document already names for tasks that touch emitted bytecode (see `VARKA-32.md`
section 10, risk 6, and the milestone's own section 3 narrative) - it is being recorded
here rather than silently absorbed because VARKA-44, not yet built, will otherwise plan
against a baseline two tasks have now moved out from under it for two unrelated reasons.

## 5. Explicitly out of this task

* **`ChronoVectorOps`** (`sql/varka/engine`), the hand-written reference
  kernel VARKA-32 built to cross-check the emitter's own fragment-sharing
  technique, carries its own independent copy of the same range guard. It is
  reference code (see `SKILLS.md`), not the production path - the emitter's
  generated bytecode is - and the owner's directive was scoped to the guard
  this session was actively discussing, which is the emitter's. Left
  untouched; revisit only if the owner asks for it explicitly.
* **VARKA-52 itself.** This task removes; it does not replace. `VARKA-52.md`
  is a plan, not an implementation.
* **Updating VARKA-26, VARKA-32, VARKA-34, VARKA-36, VARKA-38, VARKA-39, VARKA-40's own plan files.** They are
  historical records of what was true when written and are left as such.
  `m4/PLAN.md`'s status table rows for 34 and 36 got a one-line
  pointer back to this task, since their "Proof" columns asserted decline
  behaviour that is no longer true; the milestone table is a living document
  in a way the individual task files are not.

## 6. Verification

```
build/sbt catalyst/Test/compile sql/Test/compile
build/sbt 'catalyst/testOnly *Varka*' 'sql/testOnly *Varka*'
JAVA_OPTS="-XX:MaxVectorSize=16" build/sbt 'catalyst/testOnly *Varka*'
JAVA_OPTS="-XX:MaxVectorSize=16" build/sbt 'sql/testOnly *Varka*'
build/sbt catalyst/doc
dev/lint-java && dev/scalastyle
```

No parity number is expected to move: the guard's removal deletes code, it
does not change the arithmetic any in-range calendar node runs, so neither
pinned fixture (`VarkaLoopEmitterSuite`'s line map, `VarkaShapeCacheSuite`'s
shape hash) should move, and this task does not regenerate the benchmark
file.
