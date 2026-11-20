# VARKA-550: Narrow kernels' epilogues never fall back on hosts without AVX-512BW

Row 550 of `PLAN.md`. Added on 2 November 2026 from the AVX2 failure of #1291 (row 332); planned on
7 November 2026 beside `VARKA-551.md`, whose record of the host's C2 this row does not need.

HotSpot facts are from the jdk-25-ga source at `/home/max/proj/openjdk-build/jdk25`
(`src/hotspot/cpu/x86/x86.ad`), read on 7 November 2026; no JVM was run and no benchmark. What was not
read is **[I]**, an assumption **[A]**.

## 0. What exists now (admission check)

Against master at `d932baa1931`.

1. **The rule is as the row says.** `Op_LoadVectorMasked` and `Op_StoreVectorMasked` are refused in
   `match_rule_supported_vector` for a subword type without `avx512bw`, and for every type with
   `UseAVX < 1` (`x86.ad:1660-1665`); the `_masked` list (`:2011-2012`) is the AVX-512 side. A refused
   node leaves the Vector API's Java loop, which is correct and allocates.
2. **#1291 merged** (`8363562c827`, row 332). Its form is `VarkaVectorWalk.narrowRowsInEpilogue`
   (`:825`): `s.epilogue && analysis.lane.bits == Long.SIZE && analysis.lanes != 0`, with the load
   `emitNarrowRowsLoad` (`:853`, `byteAt`/`shortAt` and `withLane` at constant indices, zero past the
   remainder) and the store `VarkaBodyEmitter.emitNarrowRowsStore` (`putByteAt`/`putShortAt`,
   `VarkaBodyEmitter.java:1292`). `narrowRowsInBody` (`:835`) is row 575's loop-body use.
3. **The int-driven and the narrow-driven epilogues are still masked.** `emitNarrowLoad` falls to
   `fromMemorySegmentMasked` under `indexInRange` when `s.epilogue` and the predicate is false
   (`VarkaVectorWalk.java:803-805`); `emitNarrowTypeStore` falls to `intoMemorySegmentMasked`
   (`VarkaBodyEmitter.java:1256-1260`); a narrow-driven kernel's own lane loads at
   `VarkaVectorWalk.java:205`, `:574` and `:632` take the same masked form in the epilogue. It
   runs once for every batch whose length the lane count does not divide. **[I]** the exact count of
   shapes of the width audit's list that reach each of these sites; the first step produces it.
4. **The audit cannot see it.** `VarkaWidthAuditProbe.scala` fixes `rows = 256` (line 77), a number
   every int and narrow lane count divides (lane counts are powers of two, 2 to 64), so the epilogue
   never runs in the probe. #1291's six refusals were found by the PR Build's runner only because
   its long kernels stop a lane group early and so run the epilogue on every batch
   (`VARKA-332.md` 10.1).
5. **The two loop-body cases are as #1291's review left them** (`VARKA-332.md` 10.1, the medium
   review): at two long lanes or with no baked count a long kernel's loop body stores a narrow root
   under the masked store, and an int kernel's `TINYINT` root at 128 bits stores an eight-lane byte species
   wider than its four-lane group. Both need a width no AVX2 host prefers: 128-bit x86 is no preferred
   host (the 2 November 2026 review of row 332), and an Arm host has the masked forms natively (row
   575's measurement ran the one-row form there).
6. **No other row claims it.** Row 434 is the long masks at two long lanes; row 575 is the
   loop-body byte load at two long lanes (done). No branch or pull request names VARKA-550.

### What differs from the row text

- **The row's description of master is exact**: kernels driven at the int lane and at a narrow lane
  "load and store their epilogue under the masked forms". Nothing differs in kind.
- **"Most of them on CI's AVX2 runners" needs a fact the repo does not hold**: the batch
  length distribution. A batch whose length the lane count divides skips the epilogue. **[I]**
  whether Varka's batches are Arrow's, 4096 rows or a multiple the lane count divides, in which case
  the Java loop is the exception and the cost, not the correctness, is what the row removes. The
  correctness is not in doubt (the Java loop answers); the cost per batch is. The plan's first step
  measures neither; it makes the audit see the sites.
- **The done-when under `-XX:UseAVX=2` also holds on an AVX-512 laptop only if the audit's flags
  reach the probe JVM**: `JDK_JAVA_OPTIONS=-XX:UseAVX=2`, not `--jvm-arg` (`AGENTS.md`).
- **The row says "unrolled to the baked lane count"; for a narrow-driven kernel that count is
  large.** At 256 bits a byte kernel has 32 lanes and at 512 bits 64: 32 to 64 `withLane` inserts for
  each narrow load. Section 2.2.

## 1. Goal and non-goal

**Goal.** On a host without AVX-512BW, no kernel's epilogue calls a masked `BYTE` or `SHORT` load or
store, for any kernel driver lane, and the width audit proves it on a row count no lane count
divides.

**Non-goal.** A host with AVX-512BW (the masked form lowers there; whether the epilogue should
choose it is row 551's pair, which this row's form is the baseline for); the loop bodies except as
section 2.3 says; any change to what a kernel computes; a row count dependence of a result.

## 2. Design

### 2.1 The audit sees the epilogue

`VarkaWidthAuditProbe` runs each shape at a second row count, odd, 255 (not divisible by any lane
count 2 to 64), beside the existing 256 (both for `masked = false` and `true`), so the dense body and the
epilogue are each called. The audit's refusal list is read first on master; its growth is the work
list of 2.2 and is committed as the step's result (`width_audit.json` gains the shapes whose
epilogues the old count hid, with their refusal text). **[A]** the probe's source and sink
arithmetic are independent of the row count (they allocate `rows * stride`); nulls at `r % 7 == 3`
keep their pattern.

### 2.2 The form, driver by driver

`narrowRowsInEpilogue` widens from "long-driven" to "any kernel that moves a narrow value in its
epilogue with a baked lane count":

- **Driven at the int lane**: the load builds the int vector with `withLane` at the baked int lane
  count (at 256 bits 8, at 512 bits 16); the store reads each lane with `lane(k)` and writes it with
  `putByteAt`/`putShortAt`. Same helpers as the long-driven form, only the count differs.
- **Driven at a narrow lane**: the kernel's own lane is the narrow one, so the load builds a narrow
  vector from 16 to 64 rows (a `SHORT` kernel: 16 at 256 bits and 32 at 512; a `BYTE` kernel: 32 and 64),
  and the store writes as many. This is the large unroll; two guards: the emitted-size budget
  (`VarkaEmitBudget`) must still admit the shape, and a method past it declines the shape with the
  budget's reason, as every shape does. **[I]** whether any shape of the audit's list crosses it.
- The remainder test stays a scalar test on the remainder count per row, as in `emitNarrowRowsLoad`,
  so no vector value meets another at a join.

The alternative of overlapping the last full group is not taken: a filter's compaction and a guard's
accumulator would count the overlapped rows twice, so it holds only for pure maps. **[I]** whether
the fuzz would catch a wrong use; it is not built.

### 2.3 The two loop-body cases, served or stated out

- **A long kernel at two long lanes, or with no baked lane count.** Served for two lanes by the form row
  575 already uses (`narrowRowsInBody`), extended to the narrow store with `putByteAt`; the check is
  that no x86 host prefers 128 bits, so the audit's preferred width never meets it and the 128-bit
  census does. With no baked lane count there is nothing to unroll to: **stated out**, with the
  reason that the kernel runs on the masked store on every host, and AVX2 hosts take the Java loop
  there; the lane count is unbaked only in the evaluator's width sweeps, which are answer tests,
  not hosts. **[A]**
- **An int kernel's `TINYINT` root at 128 bits.** Served by the same store as the epilogue (section 2.2),
  at four int lanes; the shape's bytes move at 128 bits only.

### 2.4 Host independence

The form is not read from the host: it is the emitted form on every host, as #1291's is, and the
masked form on an AVX-512 host is row 551's pair. So `DEFAULTS` and the committed keys are unchanged in
kind; the bytes move in the epilogue methods, in `emitted_bytes.json`.

## 3. Tests and proofs

1. **The audit catches it first**: before the form, the audit with 255 rows under
   `JDK_JAVA_OPTIONS=-XX:UseAVX=2` on the laptop lists the refusals of 2.1; after, it lists none
   at the preferred width.
2. **Answers**: `VarkaEmitterNarrowSuite`, the cast matrices and `VarkaIrFuzzSuite` already hold
   every narrow shape's answer at lengths the lane count does not divide **[A]**; a new case runs each
   changed shape at lengths 1 to 2 * lanes + 1, nulls on and off, against `VarkaReferenceEvaluator`.
3. **The remainder bound**: a shape of each driver, length equal to the remainder minus one, plus the
   mapped-at-exactly segment of #1291 (an overread of one byte past the batch fails).
4. **Bytes**: `emitted_bytes.json` changes only in epilogue methods; a test lists the methods that
   moved and fails on a loop-body method that did, the oracle of `VARKA-332.md` 10.1.
5. **Both widths**: the audit at 256 and 128 bits; the option matrix with `isa=avx2`, `isa=w128`,
   `isa=arm`.
6. **No proofs**: no value computation changes. The SMT files are untouched.

## 4. Predictions, registered before any build

- **P1.** With 255 rows under `-XX:UseAVX=2` master's audit refuses at least one epilogue shape of the
  int-driven and of the narrow-driven kind, and after the form it refuses none at the preferred width. If
  master's audit refuses none, the row's premise (item 3) is wrong for that driver and the plan stops there.
- **P2.** `emitted_bytes.json` moves in epilogue methods only (test 3.4).
- **P3.** No shape crosses the emitted-size budget at 512 bits. **[A]** 64 inserts a narrow load is
  of the order of a few KiB of bytecode a shape, against a 64 KiB method cap.
- **P4.** Size: PR 1 under 300 hand-written lines, PR 2 under 700.

## 5. Order, files and size

1. **PR 1, the audit** (small): `VarkaWidthAuditProbe.scala` (second row count), `VarkaWidthAuditSuite.scala`
   (the refusal list is expected nonempty for the named shapes), `width_audit.json` regenerated with
   its refusals. The PR's result is the work list. Verified by the PR Build's runner, which is AVX2.
2. **PR 2, the form** (medium): `VarkaVectorWalk` (`narrowRowsInEpilogue`, `emitNarrowRowsLoad` for a
   driver lane count), `VarkaBodyEmitter` (`emitNarrowRowsStore`), the two loop-body cases of 2.3, the
   tests of section 3, regenerated `emitted_bytes.json`, `width_audit.json` and `coverage.json`
   (`dev/varka_regen_generated.sh --all`).

Medium: two pull requests.

## 6. What it unlocks

- **A narrow cast or comparison in an int- or narrow-driven kernel stops allocating on AVX2** in its
  epilogue; the runners are AVX2 half the time (row 505's admission check).
- **The decimal pair and its 128-bit width rule**: no dependence. The pair reads two long words in a
  long-driven kernel, whose epilogue #1291 already serves, so row 359's width rule (`VARKA-359.md` 11.4)
  needs nothing from this row; its host facts come from row 551's record.
- **Row 551's pair**: the row-at-a-time epilogue everywhere is the baseline the pair measures the
  masked form against.

## 7. Out of this row

- **Row 551's choice** of the masked form where `avx512bw` holds: out; it needs the record and a
  measurement.
- **Row 538's flip, row 548's lint**: unrelated. The probe's row count is a literal, not an
  architecture list; nothing here names an architecture.
- **Row 505's platform axis**: the axis runs the audit on its hosts; this row's second row count
  runs there unchanged, and the AVX2 axis is where the refusals are visible beside the PR Build.
- **Row 434**: the long masks at two long lanes, a different instruction.

## 8. Questions for the owner

1. **Is a narrow-driven kernel's unroll of 32 to 64 inserts acceptable, or should those shapes
   decline at 256 and 512 bits on a host without `avx512bw` until a measurement says otherwise?**
   (a) Unroll as #1291 does. (b) Decline them on AVX2 hosts. **Recommended (a)**: the row says
   unrolled, a decline loses fusion for a cost nobody measured, and PR 1's audit shows the number of
   shapes at stake before PR 2 is written.

   *8 November 2026, by the owner's word:* (a). Narrow-driven kernels unroll, under the emitted-size
   budget.
2. **Is 550 before 551's PR 3 still the order, with 551's PR 1 allowed ahead?** **Recommended yes**: the
   record needs nothing from 550; the pair needs 550.

   *8 November 2026, by the owner's word:* yes. Row 550 lands before 551's PR 3; 551's PR 1 and PR 2
   may land before it.

3. **(Added 20 November 2026, unanswered.) How should a narrow-driven kernel's epilogue be served
   where its lane count is not baked** (11, item 5): (a) bake 32 and 64 narrow lanes, (b) unroll to
   the emitting JVM's preferred count, or (c) an epilogue-only scratch form? **Recommended (c)**:
   it moves only the epilogue, reads nothing from the host and costs no budget per row; (a) is the
   larger change and needs its own pair.

## 9. Done when

The width audit at the preferred width, run under `-XX:UseAVX=2` on 255 rows, reports no masked subword
call; the two loop-body cases are served or stated out here with their reasons; the emitted bytes move
only in the epilogues (and at 128 bits in the loop-body cases); the PR Build and a dispatched option
matrix pass.

## 10. Outcome

### 10.1 Part 1, the audit (20 November 2026, built locally, not run)

Built on `varka-550-narrow-epilogue-avx2` from master at `6ac76f86cb3`, in the window the owner
set on 20 November 2026 (implementation first, tests and CI later).

- **The probe runs each shape in two phases.** `VarkaWidthAuditProbe` keeps its 20,000 dense and
  masked calls at 256 rows, then prints `VARKA_AUDIT_EPILOGUE_PHASE` and makes as many again at
  255 rows (`raggedRows`), with the null count of the rows the shorter batch holds (row 255 is one
  of the `r % 7 == 3` nulls, so it is one fewer). Section 2.1 had the two row counts interleaved;
  they are not, so that what C2 prints after the marker and did not print before it is the code
  only a ragged batch reaches. Its **[A]** holds: the inputs and outputs are sized for 256 rows,
  which a 255-row batch reads inside of.
- **The suite tells the epilogue's refusals from the body's.** `VarkaWidthAuditSuite` keeps, per
  shape, the refusal lines first printed after the marker (`epilogueOnly`). The preferred-width
  invariant admits a masked `BYTE` or `SHORT` load or store (`op=loadMasked` or `op=storeMasked`,
  `etype=byte` or `etype=short`, `vectorIntrinsics.cpp:1065`) only where it is one of those
  (`knownEpilogueRefusal`), and logs every such shape and line: that log, from an AVX2 run, is the
  work list of 2.2. The allowance is keyed on the probe's feature list, as the converts' is: it
  holds only on an x86 JVM whose list was read and lacks `avx512bw` (HotSpot clears that flag
  under `-XX:UseAVX=2`, `vm_version_x86.cpp:996-1000`, so the laptop's `UseAVX=2` run is keyed
  as an AVX2 host), from the high review of 23 November 2026. A unit test holds the allowance in
  both directions. The allowance stays
  until the narrow-driven epilogues at unbaked counts are served too (11, item 5), since part 2
  leaves those masked.
- **Where the plan was likely wrong.** Section 5 has `width_audit.json` "regenerated with its
  refusals". The committed census is the laptop's (AMD Ryzen AI 9 HX PRO 370, `use_avx` 3), and
  AVX-512BW lowers the masked subword forms, so its regeneration is expected to gain no masked
  subword line at 256 and 512 bits **[A]**; the work list is visible on an AVX2 runner and on
  the laptop under `JDK_JAVA_OPTIONS=-XX:UseAVX=2`, neither of which writes the committed file,
  so the list is the log line rather than the file, and test 3.1's "lists the refusals" is read
  from the `UseAVX=2` run's log. The regeneration is still owed, and its 128-bit column may move:
  the second phase runs epilogues the probe never ran, and the census already records that two
  long lanes lower no long mask, so the epilogues of long kernels there may add lines, which
  `VarkaCoverageSuite` renders into the coverage table's 128-bit column. Unread until the
  regeneration runs.
- **Prediction P5, registered before any run** (the calibration table's ratios run from 0.03 to
  5, median about 1): each probe takes 20 to 60% longer, since the second phase recompiles each
  shape's epilogue methods after their cold remainder branch traps, and the probes are mostly
  compile time **[A]**. The slowest runner probe was 252 s against the 600 s child budget, so the budget
  holds even at the top of the range. Unscored: no probe ran in this window.

Not run in this window (owed): the suite itself, on the laptop and under `UseAVX=2`; P1.

### 10.2 Part 2, the row form at every baked count (20 November 2026, built locally)

Stacked on part 1 as `varka-550b-narrow-rows-every-drive`.

- **Every epilogue with a baked lane count moves its narrow rows one at a time.**
  `narrowRowsInEpilogue` drops its long-lane condition: `s.epilogue && analysis.lanes != 0`.
  `emitNarrowRowsLoad` takes the lane it builds (`into`): the int lane for a column widened in a
  long or an int kernel, as before, and the column's own lane in a kernel driven at it, where the
  `ColumnRef` load now takes the form in the epilogue; `withLane` and `zero` are named from that
  lane (`ByteVector.withLane(int, byte)` takes the `int` the row read returns as its `byte`).
  `emitNarrowRowsStore` takes the lane it reads (`from`) and whether the remainder bounds it: an
  int kernel's narrow root and a narrow-driven kernel's own root (the drive-lane store branch of
  `emitLaneGroup`) take it in the epilogue. An epilogue's group holds at most the drive's lane count
  of rows (`emitMixedEpilogue`), so the unroll is at most 16 rows at a baked count.
- **The long kernel's loop body at two long lanes stores its narrow root row by row** (11, item 4),
  through `narrowRowsInBody`, whose doc now covers the store as well as row 575's load.
- **Stated out, with their reasons (11):** an int kernel's `TINYINT` root at four int lanes in its
  loop body under `MASKED` (item 3), a long kernel with no baked count (2.3), and every
  narrow-driven kernel at an unbaked count, which is a `BYTE` kernel at 256 and 512 bits and a
  `SHORT` kernel at 512 (item 5). The last is the gap between this part and the done-when.
- **The test of 3.2 and 3.3**: `VarkaEmitterNarrowSuite` "an epilogue moves its narrow rows one
  at a time at every remainder, at the int lane and at a narrow lane": six shapes (a widened byte
  choice stored byte, a widened short beside an int, an int stored byte and short, a byte and a
  short choice at their own lane), at 2, 4, 8 and 16 lanes, both arms, every length from 1 to
  `2 * lanes + 1`, with no nulls, every seventh row null and the masked body forced. A kernel
  maps each segment at exactly `length * stride` bytes (`VarkaSegments.map`), so a row read or
  written past the batch throws: the remainder bound of 3.3 is held by every length. The long
  kernel's store at two lanes is held by `VarkaEmitterIntegralCastSuite`'s casts at 2, 4 and 8
  long lanes, which already run there.
- **Test 3.4, the bytes oracle, is a comparison against the merge base** rather than a suite: after
  the regeneration, every method key of `emitted_bytes.json` whose hash moved must be an
  `epilogue*` method or the `<class>` entry (the constant pool), and a `loop*` or `run*` key that
  moved fails it. A suite comparing the file with itself after a regeneration would pass on any
  change, so the check is a one-off read of the two files, given in the pull request.
- **P3 scored, wrong in its premise**: the method cap is `HUGE_METHOD_LIMIT`, 8,000 bytes, not 64
  KiB. At the baked counts the unroll is at most 16 rows, about 450 bytes a narrow operation,
  so no shape is expected to cross the budget **[A]**; the unbaked 32 and 64 rows are where it
  would bind (11, item 5).
- **The allowance of 10.1 stays**: the narrow-driven epilogues at unbaked counts still refuse.

## 11. Decisions taken while the owner was away (20 November 2026)

The owner set an implementation-first window on 20 November 2026 with no one to ask; each
decision below is the option the plan, `AGENTS.md` and the plan's own recommendations favour.

1. **The probe's two row counts run one after the other, not side by side** (2.1 had them
   beside each other). Interleaved, the epilogue's refusals would print among the loop body's and
   could not be told apart; in sequence, what C2 prints after the marker and not before is the
   code only a ragged batch reaches.
2. **Part 1's allowance is a kind, not a list of shapes.** The plan has the suite expect the
   refusals "for the named shapes", which needs an AVX2 run to name them, and none can run in this
   window. The allowance admits a masked `BYTE` or `SHORT` load or store only where the shape's
   epilogue phase alone printed it, so a loop body's refusal of that kind still fails, and every
   admitted line is logged as the work list.
3. **An int kernel's `TINYINT` root at four int lanes keeps the masked store in its loop body:
   stated out, not served** (2.3 said served by the epilogue's row form). Since the plan was
   written, `NarrowGroupStore.COMBINED` (VARKA-603) serves that store whole with no mask, and
   `MASKED` is the default and the losing arm VARKA-603's pair measures against; giving `MASKED`
   the row form would remove the reference that pair reads. No x86 host prefers 128 bits (2.3),
   so the audit's preferred width never meets it. Its epilogue does take the row form.
4. **A long kernel at two long lanes stores a narrow root one row at a time in its loop body**,
   under the same predicate that reads a narrow column so there (`narrowRowsInBody`, row 575), as
   2.3 says; with no baked count the masked form stays, as 2.3 states out.
5. **The narrow-driven epilogues at an unbaked count are not served in part 2, and need the
   owner.** Section 2.2 assumed a `BYTE` kernel bakes 32 lanes at 256 bits and 64 at 512; it does
   not. `Lane.emitLanes` bakes a count only where `VarkaVectorSupport` has width-specialised
   validity helpers, 2 to 16 lanes, so a `BYTE` kernel at 256 or 512 bits and a `SHORT` kernel at
   512 run on `SPECIES_PREFERRED` with `analysis.lanes == 0`, and the row form has no count to
   unroll to. 2.3's **[A]** that an unbaked count occurs only in the evaluator's width sweeps is
   wrong for these lanes: it is the default emission of every all-narrow tree on x86 (the native
   arm is the default). So on an AVX2 runner a `BYTE`-driven kernel's epilogue still loads and
   stores under the masked forms after part 2, and the done-when does not hold. The ways open,
   none taken here:
   - (a) bake 32 and 64 narrow lanes by adding the helpers: every such kernel's loop body moves on
     every host (species constant, validity calls), against 2.4 and P2, and it is a hot-path change
     no measurement in this window could price;
   - (b) unroll the epilogue to the emitting JVM's preferred count: the emitted bytes would then
     depend on the host, against 2.4;
   - (c) an epilogue-only form that needs no count, such as copying the remainder rows to a zeroed
     scratch buffer and loading it whole.
   The budget bounds (a) and (b): `methodByteBudget` defaults to `VarkaEmitBudget.HUGE_METHOD_LIMIT`,
   8,000 bytecode bytes, not the 64 KiB P3 assumed; the row form costs about 25 to 30 bytes a row
   (read off `emitNarrowRowsLoad` and `emitNarrowRowsStore`), so about 1.8 KB a narrow load or
   store at 64 rows, and a shape with four narrow operations in one epilogue method is near the
   budget before its other work. Question 8.3.
