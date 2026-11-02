# VARKA-434: A long column's epilogue load at two long lanes without a long mask (rows 434 and 435)

*Rows 434 and 435 of `m8/PLAN.md` share this file. Both come from the 21 October 2026 review of
row 403, and both are about the epilogue's bounds mask and `Slots.epilogueReadsMask`. Row 434
changes which epilogue reads need the long mask. Row 435 changes how the tests count the masks
that remain. Row 435's count is built first, because row 434's change is the first thing it has
to pin. Section A is row 434 and section B is row 435.*

## 0. What exists now (both rows)

Read on 1 November 2026 from the worktree at `130280562f3` (code as of master `5c660201b35`); the
line numbers are that tree's. Master has since moved to `1bb9c97b5d0`, whose diff to the files these
rows touch (`Analysis.java`, `Slots.java`, `VarkaIrGrammar.java`, row 157's decimal store) shifts
some lines and changes no finding. Nothing was run.

1. **Who reads the epilogue's mask.** `Slots.epilogueReadsMask` (`Slots.java`, about `:1321`)
   counts four readers: a guard, a column loaded at the driving lane, a value root stored at
   the driving lane that is neither a bitmap writer, a narrowing nor (since row 157) a decimal
   store, and a materialized prefix. Row 403 made an int column or root of a long-driven kernel
   not a reader, since it is loaded and stored under an int mask of its own
   (`VarkaVectorWalk.emitLowHalfLoad`, `:518`; `VarkaBodyEmitter.emitLowHalfStore`, `:1129`).
2. **How the long column is loaded.** In an epilogue, a column at the driving lane is
   `fromMemorySegment(species, seg, offset, LITTLE_ENDIAN, epilogueMask)`
   (`VarkaVectorWalk.java:193-203`), and a value root at the driving lane is stored by the masked
   `intoMemorySegment` (`VarkaBodyEmitter.java:1276-1286`). The mask itself is
   `species.indexInRange(i, length)` (`:891-896`, `:925-931`).
3. **What C2 refuses at two long lanes on x86.** `width_audit.json`'s 128-bit census lists, for
   `shiftright(l, i ^ 5)`: `op=broadcast vlen=2 etype=long ismask=1`, `op=loadMasked vlen=2
   etype=long` and `op=test/2 vlen=2 etype=long ismask=1`. A long compare to a mask is refused
   too: `op=comp/... vlen=2 etype=long ismask=usestore`, as in `CAST(d AS TIMESTAMP_NTZ)`'s
   entry. No int-lane mask construction is refused at 128 bits (`VARKA-153.md`, row 153's cell).
4. **`cast(l AS int) + i` is not a coverage row.** It is in neither `sql/varka/coverage.json` nor
   `docs/sql-varka.md`. It is the narrowing arm of `VarkaLaneConversionBenchmark`, built in the
   IR as `IntArith(ADD, WRAP, i, NarrowLane(l))` (`VarkaLaneConversionBenchmark.java:143-144`),
   which is the legacy form with no guard. Its committed 128-bit figures are in
   `sql/catalyst/benchmarks/VarkaLaneConversionBenchmark-jdk25-128bit-results.txt` (7790.8 M
   rows/s at 16384 rows, laptop, commit `512bb292da3`, from before row 403).
5. **Row 503 declines conditions, not projections.** Its pull request #1262 (open) leaves a
   long-lane filter condition to the row path at two long lanes on x86 and keeps projections
   fused. So this projection stays fused at 128 bits and stays in scope. The branch's plan says
   the masked epilogue's tail mask alone cost nothing measurable in its benchmark. That text is
   on the unmerged branch, and this plan quotes no figure from it.
6. **A materialized prefix never exists in a kernel of both lanes.** `Analysis.planMaterialized`
   returns before planning when `mixed()` (`Analysis.java:1165-1171`): "the scratch is laid out
   by the driving lane's stride, and the prefix's vectors are the int species'". The prefix
   transfer (`VarkaChronoLowering.emitPrefixTransfer`, `:524-556`) passes `s.epilogueMask()` to
   `IntVector`'s masked calls. In a long-driven kernel that mask would be the long species'. That
   path is unreachable only because of the early return.

## A. Row 434

### A.1 What differs from the row text

- **The row's candidate form is refused too.** "A lane-index compare in place of the mask" is a
  long compare at `vlen=2`, which item 3 lists as refused. It is not a vector form at 128 bits.
- **A form that the census suggests is lowered.** It follows `emitLowHalfLoad`'s precedent: the
  long column's partial group is loaded as `2 * lanes` ints under an int mask from the int
  species of the same width, then reinterpreted as longs. The root is stored the same way: the
  long vector is reinterpreted as ints and stored under the same int mask. Only int masks are
  built, and item 3 says none of those is refused. The reinterpret has no census entry, so
  whether it is lowered at 128 bits is a prediction (P1), checked by a width-audit construction.
- **"The docs column says which" has no cell to say it in** (item 4). The default session runs
  coverage rows under ANSI, where `CAST(l AS INT) + i` carries the narrowing's range guard. That
  guard is a long compare, refused at `vlen=2` whatever the load does. So a coverage row of the
  example would read `per-lane` before and after, naming the guard's constructions. The legacy
  form, which is the benchmark's arm, is the one this change can make `vector`, and the width
  audit's hand-built constructions are where a non-default form is recorded (question A1).
- **The size.** An option-gated emitter form, a width-audit construction, a coverage row and a
  benchmark rerun on the runner are more than the row's "small" (A.6).

### A.2 Goal and non-goal

Goal: at two long lanes, an epilogue's long column load and long root store build no long mask.
The width audit then records the legacy `cast(l AS int) + i` as `vector` at 128 bits, or records
what is still refused. The narrowing arm's 128-bit rerun is committed.

Non-goal:
- a guard's condemnation, which keeps the long mask, since its compare is refused anyway;
- the filters' long conditions (row 503, PR #1262);
- the floating lanes at two lanes (row 543);
- `m9/SCOPE.md` item 101's two-lane mask design.

### A.3 The design

1. A `VarkaEmitOptions` switch, `longEpilogueByInts`, default off, as `TEMPLATE_TASK.md` 3.1
   asks: under it, in a body with `s.epilogue` and the driving lane `LONG` at two lanes
   (`analysis.lanesOf(Lane.LONG) == 2`):
   - a long `ColumnRef` loads with `IntVector.fromMemorySegment(intSpecies, seg, offset, LE,
     intSpecies.indexInRange(0, 2 * lanes))`, followed by `reinterpretAsLongs()`;
   - a long value root stores with `reinterpretAsInts()`, followed by `intoMemorySegment(seg,
     offset, LE, intMask)`;
   - the int mask is built once per epilogue group into a slot of its own (`epilogueIntMask`),
     planned where `epilogueMask` is planned now.
2. `epilogueReadsMask`, under the switch at two long lanes, no longer counts a long column or a
   long root as a reader of the long mask. A guard and a prefix still count.
3. At wider species the switch changes nothing. Long masks are lowered there, and the plan keeps
   the existing form as the live reference variant.
4. The default flips to on in the last commit if P2 and P3 hold, per the template's rule.

### A.4 Tests

- `VarkaEmitterMixedLaneSuite`: `cast(l AS int) + i` (legacy) and `l + l2`-shaped long roots
  over every batch length 0 to 9 at two long lanes, under the switch on and off, against the
  reference. The epilogue then covers 1 and 2 rows and also runs on an empty batch.
- `VarkaWidthAuditProbe`: a construction "long load and store by ints, two lanes", the legacy
  `cast(l AS int) + i`. The census at 128 bits records its refusals, expected empty (P1).
- Row 435's count (section B) pins no long mask in the switched epilogue and one int mask a
  group.
- The option matrix carries the switch as an arm, so every suite runs both forms.
- A coverage row `CAST(l AS INT) + i`, domain `l BETWEEN -2147483648 AND 2147483647`, so the
  docs table has the example, under ANSI (question A1).

### A.5 Predictions and costs, registered before the build

- P1: the width audit's 128-bit list for the legacy construction is empty under the switch,
  that is, `reinterpretAsLongs` and `reinterpretAsInts` at 128 bits are lowered. If not, the
  list names the reinterpret, and the row closes by its done-when's second arm ("no vector
  form"), with the switch left off.
- P2: the narrowing arm at 128 bits on the EPYC 7763 runner moves by less than its round-to-round
  spread at 16384 rows and above. The epilogue is at most two rows of a batch.
- P3: the probe's per-call allocation in the epilogue at two long lanes falls from the
  fallback's figure to the 64-bit warm-up allowance's floor, measured with the
  `VarkaAllocationSampler` the warm-up uses. This plan quotes no before-figure: `VARKA-28.md` 9.3
  gives about 90 to 120 bytes a call from a probe that left no committed file, so the PR
  measures both sides.
- P4: `emitted_bytes.json` moves in its option-arm hashes and its new coverage row only, while
  the switch is off. When the default flips, the long-driven kernels' 128-bit hashes move.

### A.6 Order, files and size

| File | Change |
|---|---|
| `VarkaEmitOptions.java`, the option table | the switch |
| `Slots.java` | `epilogueIntMask`, `epilogueReadsMask` under the switch |
| `VarkaVectorWalk.java`, `VarkaBodyEmitter.java` | the load and store by ints |
| `VarkaEmitterMixedLaneSuite.java`, `VarkaWidthAuditProbe.java` | A.4 |
| `coverage.json`, `docs/sql-varka.md`, `width_audit.json`, `emitted_bytes.json` | regenerated |
| `sql/catalyst/benchmarks/VarkaLaneConversionBenchmark-*` | the runner's files |

Order:
1. row 435's count (section B), merged first;
2. the switch, default off, with its tests and the width-audit construction;
3. the benchmark at 128 bits on the runner, both forms, through `benchmark.yml` with
   `-XX:MaxVectorSize=16`, pinned to the EPYC 7763, results committed from the artifact;
4. the default flip and the docs, if P2 and P3 hold.

The width-audit census is regenerated on the laptop, the census host (`width_audit.json`'s
`host`), as row 413 did. Size: medium, about two days, against the row's "small".

### A.7 Done when

Row 434's done-when: the long column load at two long lanes is a vector form, the load by ints,
with the width audit recording the legacy example's 128-bit list (empty, or naming what is still
refused, in which case the form is shown to have no vector form). The docs carry the coverage
row with its column, and the narrowing arm's 128-bit rerun is committed from the runner.

## B. Row 435

### B.1 What differs from the row text

- **No mixed-lane prefix kernel exists** (item 6). The case the row asks
  `VarkaEmitterMixedLaneSuite` to pin cannot be built. A prefix kernel is single-lane, has two or
  more groups and a byte budget. Its prefix transfers build no mask of their own: they reuse the
  bounds mask (`emitPrefixTransfer` loads `s.epilogueMask()`). So the row's worry, that the
  transfers' masked calls would have to be told apart from the bounds mask's, does not arise.
  Other masks do arise in single-lane epilogues, from a narrowing store and from a narrow
  column's load, each of which builds its own. That is why the bytecode count needs subtracting,
  and why it should go.
- So the row reduces to two things: pin the premise that a mixed kernel has no prefix, so the
  case is demanded if the premise is lifted, and replace the instruction-pattern count with
  one the emitter reports.

### B.2 Goal and non-goal

Goal: the epilogue mask tests count masks by what the emitter reports, per body, with no
instruction patterns and no method-name prefix; a single-lane prefix kernel is pinned by an
exact count; the mixed kernels' no-prefix premise is asserted.

Non-goal: lifting the `mixed()` restriction on materialized prefixes.

### B.3 The design

1. `VarkaEmitTrace` gains, per body it emits, a record of the body's role (`BodyMode`), whether
   it built the bounds mask, how many reads of it were emitted (each call of `Slots.epilogueMask()`
   that emits an `aload`), and, after row 434, the same pair for the int mask. `Slots` notes the
   build where it allocates `epilogueMask` (`Slots.java:832-834`), and the read where the accessor
   is called. The record reaches a suite through `onEmission`, as the mechanisms do. No byte of
   any class changes.
2. `VarkaEmitterMixedLaneSuite.epilogueMasks` reads the trace instead of parsing the class.
3. **What the trace cannot catch.** It reports what `Slots` decided and what the emitter asked for.
   A mask local built and then never loaded would show as "built, zero reads", which the test
   rejects. A load that bypasses the accessor would be missed, but the accessor is the only way
   to name the slot. A cross-check against the bytecode stays in one place: one test asserts, for
   the four shapes of today's test, that the trace's counts equal the bytecode count the test uses
   today. It is kept so that the trace's own wiring is checked once.

### B.4 Tests

- `epilogueMaskOnlyWhereRead` on the trace: the four shapes of today with the same counts, and
  "built implies read" for every epilogue body.
- A single-lane prefix case in `VarkaEmitterValiditySuite`, where the prefix kernels are pinned
  today. It is a two-group date kernel under a byte budget with `year(d)` and `month(d)` in
  different groups, and it pins one bounds mask in each epilogue body that transfers the prefix.
- `VarkaEmitterMixedLaneSuite`: a mixed kernel with the same date outputs and a long column, under
  the same budget, has `materialized` empty. It is the premise test, with a message that names
  this plan.
- The bytecode cross-check of B.3 item 3.

### B.5 Predictions and costs

- PB1: no emitted byte moves (`VarkaEmittedBytesSuite` unchanged).
- PB2: the added test time is under 5 s, emission-only tests at the compile-only suites'
  ratio in the calibration table (VARKA-318, 0.37 to 0.46).

### B.6 Order, files and size

| File | Change |
|---|---|
| `VarkaEmitTrace.java`, `Slots.java` | the per-body mask record |
| `VarkaEmitterMixedLaneSuite.java`, `VarkaEmitterValiditySuite.scala` | B.4 |

One pull request, before row 434's emitter change. Size: small (about a day).

### B.7 Done when

Row 435's done-when, adjusted to the premise: a single-lane prefix kernel is pinned by an exact
count, the mixed kernels' lack of a prefix is asserted, and the counts come from the emitter's
trace, not from instruction patterns or method names.

## 1. Questions for the owner

- **A1. Where the example's 128-bit status is recorded.** (a) A coverage row `CAST(l AS INT) +
  i` under ANSI, whose docs column will read `per-lane` because of its guard, plus the width
  audit's construction for the legacy form; (b) the construction alone, with no coverage row.
  *Recommended: (a).* The docs then show the example, the guard's cost is named where a user
  reads it, and the row's note can point at the legacy form's `vector` entry.
- **A2. The switch's reach.** (a) Two long lanes only; (b) every width. *Recommended: (a).* Long
  masks are lowered at 256 and 512 bits, so the int form buys nothing there, and keeping it to
  one width keeps the other widths' bytes unmoved.
- **B1. Row 435's re-scope.** Accept that the prefix case is single-lane, with the mixed-lane
  premise asserted. *Recommended: yes.* The case the row names cannot be built while
  `planMaterialized` excludes mixed kernels.

## 2. Open points for the lead

1. Row 435's cell should say that the prefix case is single-lane (B.1), and row 434's cell should
   say that the size is medium.
2. Row 503's plan, on its branch, measures the tail mask as free in its own benchmark. When #1262
   merges, A.5's P2 can cite its committed results.
3. `emitPrefixTransfer` would pass a long mask to `IntVector` calls if `mixed()` were lifted from
   `planMaterialized`. B.4's premise test is the guard. Whoever lifts it owns that path.

## 3. Outcome

(Filled in when the rows are built.)
