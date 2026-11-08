# VARKA-551: One record of what the host's C2 lowers

Row 551 of `PLAN.md`. Added on 2 November 2026 by the owner's word, from the reviews of #1262 and
#1291; planned on 7 November 2026, ahead of its place in the queue, because row 359's layout
verdict and its width rule (`VARKA-359.md` 11.3 rule 2 and 11.4) both read what the host's C2
lowers, and the decimal chain is the milestone's critical path.

Facts about HotSpot below come from the jdk-25-ga source at `/home/max/proj/openjdk-build/jdk25`
(`src/hotspot/cpu/x86/x86.ad`), read on 7 November 2026; no JVM was run. What was not read is marked
**[I]**, an assumption **[A]**.

## 0. What exists now (admission check)

Against master at `d932baa1931`.

1. **There are four signals today, not three, and one of them is a static.** (a) The emit level
   `useAVX`, a component of `VarkaEmitOptions`, read by `convertsFallBack()` and
   `convertsMayFallBack()` (`VarkaEmitOptions.java:659`, `:680`), by `VarkaDivisionLowering.java:363`
   and by `VarkaKernelPlan.java:602`. (b) The two-lane decline, a boolean of `VarkaSessionEmit`
   resolved on the driver from the host reading and the preferred long lane count
   (`VarkaKernelPlan.twoLaneMasksFallBack`, `:231` and `:254`; `VarkaSessionEmit.bind`). (c)
   `VarkaMaskGuard.OPEN`, a `static final` computed at class initialisation from
   `VarkaEmitOptions.HOST_USE_AVX` (`VarkaMaskGuard.java:53-66`), the host's level read on the
   executor's own JVM. (d) The epilogue's narrow form, which reads **nothing**: `narrowRowsInEpilogue`
   (`VarkaVectorWalk.java:825`) is true for every kernel driven at the long lane with a baked lane
   count, on every host, and `narrowRowsInBody` (`:835`) reads whether a species exists, not the
   CPU.
2. **`VarkaHostIsa` reads the whole feature list but names one question.** `Reading` holds `arch`,
   `features` (HotSpot's string, `", "`-separated), `failure` and `longBits`
   (`VarkaHostIsa.java:68`); the only feature it names is `avx512dq` (`DQ_FEATURE`), through
   `conversionsFallBack()`, and `hasFeature` matches whole names. It has no `avx512bw`, no
   `avx512vl`, and no component for either. Row 538's two readers merged (#1255 on 1 November
   2026, the default flip as #1345 on 4 November); its four branches (`varka-538-host-isa`,
   `-flip`, `-plan`, `-default-flip`) are squashed history, nothing live.
3. **The session resolves a level, not a set of answers.** `VarkaSessionEmit.resolve(host)`
   (`VarkaSessionEmit.java:214`) returns `2` where the conversions fall back and the long species
   is at least 256 bits, `3` on an x86 host whose list holds `avx512dq`, otherwise unknown. A level
   cannot say "AVX-512 with DQ but without BW"; the mask guard's own comment says so
   (`VarkaMaskGuard.java:58-64`, a Knights Landing host "which a level does not show").
4. **The CPU architecture is listed in more places than `VarkaHostIsa`.** Four lines name both
   `"amd64"` and `"x86_64"`: `VarkaTwoLaneConditionSuite.java:69` and `:84`,
   `VarkaAvx2SelectSuite.java:137`, `VarkaAssemblySuite.scala:133` and `VarkaShapeCacheSuite.scala:317`;
   main code has none (it calls `VarkaHostIsa.isX86`). These are row 548's `arch-list` lint
   (`dev/varka_lint.py` has no such rule yet).
5. **The matcher facts the row asks for, read from `x86.ad`** (all in `match_rule_supported_vector`,
   which starts at `:1588`):

   | question | rule, line | host condition |
   |---|---|---|
   | masked `BYTE` or `SHORT` load or store | `Op_LoadVectorMasked`, `Op_StoreVectorMasked`, `:1660-1665` | refused without `avx512bw` for a subword type, and without AVX (`UseAVX < 1`) for any type |
   | `L2D`, `L2F` (long to floating) | `Op_VectorCastL2X`, `:1757-1763` | refused without `avx512dq`; long to integral refused only at 256 bits below AVX2 |
   | `D2L`, `F2L` (floating to long) | `Op_VectorCastD2X`, `:1774-1778`, `F2X` falling through from `:1764` | refused without `avx512dq` |
   | integral casts | `Op_VectorCastB2X`, `S2X`, `I2X`, `:1750-1756` | refused at 256 bits below AVX2 |
   | shuffle and rearrange | `Op_VectorLoadShuffle`, `Op_VectorRearrange`, `:1726-1733` | refused at two lanes, and at 256 bits below AVX2 |
   | mask to vector and back | `Op_VectorLoadMask`, `Op_VectorMaskCast`, `Op_VectorStoreMask`, `:1734-1743` | refused at two lanes whatever the host |
   | two-vector select | `Op_SelectFromTwoVector`, `:1833-1846` | refused below 512 bits without `avx512vl`, a `SHORT` without `avx512bw`, a `BYTE` without `avx512_vbmi`, and an `INT`, `FLOAT` or `DOUBLE` without EVEX |
   | masks as mask registers | not a rule: the mask guard's constant | `avx512f` with `avx512vl` and `avx512bw` (`VarkaMaskGuard.java:58-64`) |

   The first row is the one row 550 and the epilogue pair rest on; the third and fourth are row
   538's; the fifth and sixth are row 503's and row 359's width rule (`VARKA-359.md` 11.4 cites
   `:1726-1730`); the seventh is new to the plans (item 7).
6. **Nothing of the record exists.** `git branch --list 'varka-55*'` shows no branch of this row
   or of 550; no pull request names VARKA-550 or VARKA-551 (`gh pr list`, read only); no
   worktree holds either.
7. **The decimal pair's de-interleave may reach a rule 359 does not cite.** 359a loads both words of
   a sixteen-byte slot and separates them with `a.rearrange(EVEN, b)` and the odd shuffle
   (`VARKA-359.md` 0 item 5), two shuffles built once a body. Whether the Vector API lowers that
   two-source `rearrange` to `Op_SelectFromTwoVector` (needing `avx512vl` below 512 bits, so
   refused on every AVX2 host) or to two single-source `Op_VectorRearrange` and a blend (refused only at
   two lanes) was **not read** in `jdk.incubator.vector`'s `Long256Vector` of the local source.
   **[I]** It decides whether 11.4's rule "two long lanes on x86 is the fallback" is the whole width
   rule on AVX2 at 256 bits (section 2.4, question 1).

   *Read on 8 November 2026, as the owner's answer to question 1 asked:* it does not reach it.
   `Long256Vector.rearrange(s, v)` (`Long256Vector.java:459-465`) calls
   `LongVector.rearrangeTemplate(shuffletype, shuffle, v)` (`LongVector.java:2333-2353`), which is
   two one-vector `VectorSupport.rearrangeOp` calls, one per source, and a `blend` under
   `shuffle.laneIsValid()`, itself a compare `GE 0` of the shuffle's bits and a mask cast
   (`Long256Vector.java:910-913`). Only `selectFrom(v1, v2)` calls `selectFromTwoVectorOp`
   (`LongVector.java:2468-2470`). So the load's de-interleave is `Op_VectorRearrange`, refused on
   x86 at two lanes and at 256 bits below AVX2 (`x86.ad:1726-1733`), `Op_VectorBlend`, refused only
   below SSE4.1 (`:1469-1473`), and a compare, and 11.4's rule is the whole width rule for it; the
   EPYC 7763 census agrees (`long.rearrange2` intrinsic at 256 bits, the fallback at 128,
   `VARKA-157.md` 0 item 9). The one form that does reach `Op_SelectFromTwoVector` is the store's
   option `decimalStore=SELECT_FROM` (row 157; the default is `REARRANGE`), native below 512 bits
   only with `avx512vl` (`x86.ad:1833-1836`), so the Java fallback on the EPYC 7763 **[I, derived;
   no EPYC cell of the store exists]**. `selectFromTwo` therefore serves that option's choice, not
   359a's load.

### What differs from the row text

- **The reading is not yet the record the row starts from.** The row says `VarkaHostIsa`'s reading
  holds "the architecture, `avx512bw`, `avx512dq`, `avx512vl` and the vector width"; master's holds
  the architecture, the whole string and the width, and names `avx512dq` alone. `avx512bw` and
  `avx512vl` are two more names, not two more reads.
- **The two-lane decline already travels with the plan**, as a boolean of `VarkaSessionEmit`
  (`bind`), not through `useAVX`; the row's "reads the long lane count" is right, but moving it
  into the record is a change of where the boolean lives, not of what is read.
- **There is a fourth reader the row does not list**, `VarkaMaskGuard.OPEN`, which the row keeps
  as a constant on the executor (right) and whose second half, reading the same list, is
  "scope item 115's step 2". The catalogue `m9/SCOPE.md` has no item 115 or 127 with those meanings
  (its item 127 is "The 64-bit operations table on AVX2", a different thing), so the row's two
  scope pointers cannot be followed. **[I]** which items the owner meant; question 4.
- **#1291's epilogue "moves narrow rows one at a time on every host" is exact** (`narrowRowsInEpilogue`
  has no host test), and the AVX-512 cost the row implies is real only for the epilogue: it runs
  once a batch, not once a lane group.
- **"Row 550 lands first" is not a dependency of the record.** Row 550 changes what the epilogue
  emits and reads no host; the record is read by the three signals above. Only the epilogue choice
  at the end of this row (PR 3) needs 550. So the record can land before 550, which is what row
  359 needs (section 6).
- **The emitted bytes do not move for the existing readers, and cannot for a host the CI
  matrix runs**, provided the record's rendering for the answers a level implies is the level's own
  (section 2.2). That is a design constraint, not a fact about master.

## 1. Goal and non-goal

**Goal.** One immutable record, `VarkaHostLowering`, holds one answer for each x86 matcher rule the
emitter's choices depend on (item 5's table, minus the rows no choice reads), each with its
`x86.ad` citation in the javadoc. It is read from `VarkaHostIsa`'s feature string and width on the
driver, carried with the plan like `useAVX`, and every CPU-dependent choice in the emitter and the
admission check reads it and nothing else.

**Non-goal.** Reading the host inside `VarkaEmitOptions.DEFAULTS` or in the emitter (a default that
varied by host would make the committed bytes describe one machine, `VARKA-538.md` 2.2); per-executor
readings on a mixed cluster; a rule for aarch64 beyond "no x86 rule applies"; a new
lowering for any host (the record says what exists, it does not make C2 lower more).

## 2. Design

### 2.1 The record

A final record in `varka/`, `VarkaHostLowering`, with one component per question, each a three-valued
`Answer` (`YES`, `NO`, `UNKNOWN`). `UNKNOWN` is the answer of `DEFAULTS` and of a feature list that
could not be read, and each consumer states in its javadoc what it does on `UNKNOWN`, which is
always what it does today. The questions, from item 5:

| component | YES when | consumers |
|---|---|---|
| `maskedSubword` | `avx512bw` | the epilogue (row 550, PR 3), the narrow store |
| `convertsLongFloating` | `avx512dq` | `VarkaDivisionLowering`, `convertsMayFallBack`, rows 30, 343, 344 |
| `rearrangeAtTwoLanes`, `masksAtTwoLanes` | never on x86 (`vlen == 2`); always off x86 | `twoLaneMasksFallBack`, row 359's width rule |
| `selectFromTwo` | `avx512vl` (and the element rules) | row 359 if item 7 says so |
| `maskRegisters` | `avx512f`, `avx512vl`, `avx512bw` | `VarkaMaskGuard` (PR 2) |

The record takes the `Reading` and the lane count, so a test constructs it from a planted
`Reading("amd64", "avx2, avx512bw", null, 256)`, as `VarkaHostIsaSuite` does today. Each component has a
method that quotes its rule's line in the jdk-25-ga source and a test that holds the quoted
condition, not the code's, to the answer (section 3). `Reading` gains `hasFeature(name)`; no
other change to the reading.

### 2.2 Where it enters, and why the bytes stay

`VarkaEmitOptions` gains one component, `lowering`, defaulting to the all-`UNKNOWN` record, set by
`VarkaSessionEmit` from the driver's reading and carried in the plan as `useAVX` is. The existing
`useAVX` setter stays the user-facing surface (`spark.sql.codegen.varka.emit.useAVX`) and writes the
record the level implies: level `2` is `convertsLongFloating = NO` and everything else `UNKNOWN`,
level `3` is `convertsLongFloating = YES`, unknown is the default. The two-lane boolean moves into the
record's `masksAtTwoLanes`. `convertsFallBack()` and `convertsMayFallBack()` become one-line reads of
the record.

The shape key and `emissionKey` render the record by the old rendering whenever the record equals one a
level implies, so `emitted_bytes.json`, `width_audit.json` and `coverage.json` do not move on any host
the matrix runs, and `VarkaMatrixRuns`' fold of every fallback host into level 2 is one equality.
A record a level cannot express (`maskedSubword = YES` beside `convertsLongFloating = NO`, a Knights
Landing host, none of ours) renders its own bits. **[A]** `VarkaEmitOption.TABLE` takes the
component with a list of records to draw, and the option matrix and the fuzz draw from it as they do
for every option (`VarkaEmitOptionSuite` fails until the entry exists).

### 2.3 The mask guard

`VarkaMaskGuard.OPEN` stays a `static final`, because C2 folds it only as a constant, and is computed on
the executor's own JVM from `VarkaHostIsa.host()`'s `maskRegisters` answer, falling back to the level
rule of `openFor` when the list was not read. This is PR 2, separate because it changes a reader that
the record does not carry (item 1c) and because a Knights Landing host is the only host whose answer
differs. It needs the module export on the executor, which Spark's module options already carry
(`VARKA-538.md` 2.1).

### 2.4 What row 359 gets

The width rule (`VARKA-359.md` 11.4) reads `masksAtTwoLanes` and `rearrangeAtTwoLanes` from the
session's record instead of `twoLaneMasksFallBack`; the same sessions, the same fusing. Item 7,
read on 8 November 2026, found that the pair's de-interleave never reaches
`Op_SelectFromTwoVector`, so 11.4's table gains no row; `selectFromTwo` is read by the store's
`decimalStore=SELECT_FROM` option instead, which an AVX2 host should not choose.

## 3. Tests and proofs

1. **Each field follows its host** (the Done-when's planting test): for each component, a table of
   planted readings (`avx2` only; with `avx512f, avx512vl`; with `bw`; with `dq`; aarch64; unread;
   two-lane width) against the expected answer, in `VarkaHostLoweringSuite`, no JVM flags.
2. **Each consumer follows its field**: the division form, the plan's conversion admission and the
   two-lane decline each tested with the record planted and no level set, reproducing
   `VarkaHostIsaSuite` and `VarkaTwoLaneConditionSuite`'s current cases through the record.
3. **A rule holds its citation**: a test that reads `x86.ad`'s quoted conditions is not possible in CI
   (the source is not in the build); instead the javadoc carries the line numbers and the
   jdk-25-ga tag, and the census (3.5) holds the answers to what the running JVM does.
4. **Bytes unchanged**: `VarkaEmittedBytesSuite`, `VarkaWidthAuditSuite`, `VarkaCoverageSuite` and
   the shape-cache test pass with no regeneration; the diff of the three generated files is empty.
5. **The census holds the record to the JVM**: `VarkaOperatorCensusSuite`'s existing cells for
   `L2D`, `D2L` and `L2F` become `convertsLongFloating`'s check, and a new cell set (a masked
   `BYTE` load and store, a two-lane compare) holds `maskedSubword` and `masksAtTwoLanes` to the
   Vector API's refusal on the laptop at its own flags and under `-XX:UseAVX=2`, and on the EPYC 7763 and
   Neoverse N2 runners.
6. **The option matrix**: a `lowering` configuration set per `TABLE` entry passes, and
   `isa=avx2`, `isa=w128`, `isa=arm` stay green.

No SMT proof is owed: nothing here computes a value.

## 4. Predictions, registered before any build

- **P1.** `emitted_bytes.json`, `width_audit.json` and `coverage.json` do not change in PR 1 or 2.
- **P2.** On the laptop (Zen 5, `avx512bw`, `avx512dq`, `avx512vl`) every component but the two-lane
  ones answers `YES`; under `-XX:UseAVX=2` the three feature components answer `NO` (the list
  `getCPUFeatures()` returns is cleared, `VARKA-538.md` 7); on the EPYC 7763 the same `NO`s at 256 bits.
  The two-lane ones answer `YES` under `-XX:MaxVectorSize=16` on x86 only.
- **P3.** The epilogue pair (PR 3), masked against row at a time, differs by less than 1.3x on every
  section whose batch holds 1024 rows or more on the AVX-512 host: the epilogue runs once a batch,
  so even a masked form several times faster saves a few nanoseconds against thousands of rows.
  Only a section of a few lanes' worth of rows can show it. **[A]** the Varka batch size; **[I]**
  where it is set (the kernels' batch is the Arrow batch). If P3 holds the epilogue stays
  row at a time on every host and the row's choice is closed with the pair committed.
- **P4.** Size: PR 1 under 800 hand-written lines, PR 2 under 300, PR 3 under 500.

## 5. Order, files and size

1. **PR 1, the record and its three readers** (medium, under 1000): `VarkaHostIsa` (`hasFeature` on
   `Reading`), `VarkaHostLowering`, `VarkaEmitOptions` (the component, the level mapping, the two
   predicates), `VarkaEmitOption.TABLE`, `VarkaSessionEmit` (resolve the record; the two-lane
   boolean moves), `VarkaKernelPlan`, `VarkaDivisionLowering`, `VarkaMatrixRuns`, tests 3.1, 3.2, 3.4;
   the census cells of 3.5 for `maskedSubword` and the two-lane facts.
2. **PR 2, the mask guard** (small): `VarkaMaskGuard`, its test, the census cell.
3. **PR 3, the epilogue pair, after row 550** (small to medium, gated): a benchmark class of its own
   (`AGENTS.md`: a new feature family gets its own benchmark file), the pair on the AVX-512 host
   and on the EPYC 7763 at 256 and 128 bits, results committed with the form chosen; the
   epilogue reads `maskedSubword` only if P3 fails.

Not before row 550: PR 1 and PR 2. PR 3 waits for 550's int- and narrow-driven form, since the pair
compares against it.

## 6. What it unlocks

- **The decimal pair and its width rule.** Row 359's 11.4 and its layout verdict (`VARKA-359.md`
  11.3 rule 2) rest on the host's C2 lowering; today they read the two-lane boolean and the level
  separately. The record gives 359a one input with the matcher citation beside each question and the
  item 7 question answerable, so the 128-bit pair (`Decimal128`, two long words) is planned against
  one named fact instead of three.
- **Rows 30, 343, 344**, which pick their per-host forms by `convertsFallBack()`, read one fact.
- **Row 548's `arch-list` lint** passes with fewer exceptions: the record is the one place that
  names an architecture, besides `VarkaHostIsa`.
- **Row 550's follow-up**: the epilogue form is chosen by `maskedSubword`, not by a comment.

## 7. Out of this row

- **Row 538's flip**: merged (#1345); this row keeps its `-2` and its width condition, and reads
  the same list. The condition (`MAGIC_MIN_LONG_BITS`) moves into the record only if question 2 says
  so.
- **Row 548's `arch-list` lint**: unaffected, and independent. The four test lines of section 0
  item 4 are row 548's to change; this row moves none of them, so the two rows do not conflict.
- **Row 505's platform axis**: the axis dispatches `isa=avx2`, `isa=w128`, `isa=arm`; this row's
  tests plant readings and so run on all three unchanged, and the census cells of 3.5 run on the
  axis' hosts. No change to the axis.
- **Per-executor readings** (a mixed cluster) and the mask guard on the driver: out, as row 538
  put them.
- **Which scope items 115 and 127 are**: none; the row now points at sections 2.3 and 7
  (question 4, answered on 8 November 2026).

## 8. Questions for the owner

1. **Does the record carry `selectFromTwo`, and is the item 7 reading owed before 359a?**
   (a) Yes: carry it now, read `rearrange(shuffle, v)` for 359a. (b) No: leave it to 359a.
   **Recommended (a).** 11.4's width rule is a verdict the decimal chain is built on; a host
   fact it may have missed on every AVX2 machine is worth one read before the pair is built.

   *8 November 2026, by the owner's word:* (a). The record carries `selectFromTwo`, and item 7 is
   read before 359a's pair is built on its de-interleave.
2. **Does the record replace `useAVX` as the stored component, or sit beside it as derived?**
   (a) Beside it, the level the user-facing surface (section 2.2). (b) Replace: the level becomes
   a setter only. **Recommended (a)**: the committed keys stay, and `useAVX` remains the one
   knob users have.

   *8 November 2026, by the owner's word:* (a). The record sits beside `useAVX`, which stays the
   user-facing setting.
3. **Where is the AVX-512 arm of the epilogue pair measured?** (a) The laptop, idle, its 256-bit datapath
   named in the results. (b) A Xeon 8573C runner, which `benchmark.yml` cannot pin today. **Recommended
   (a)**, with (b) only if the laptop shows the masked form 1.3x faster.

   *8 November 2026, by the owner's word:* (a), with (b) as the fallback: the laptop first, idle,
   its 256-bit datapath named in the results; a Xeon 8573C runner only if the laptop shows the
   masked form 1.3x faster.
4. **Which scope items are 115 and 127?** Master's catalogue has no item with the row's meaning.
   **Recommended:** correct the row's pointers to this plan's sections 2.3 and 7.

   *8 November 2026, by the owner's word:* (a). The row's pointers now name sections 2.3 and 7 of
   this plan.

## 9. Done when

Every CPU-dependent choice in the emitter and the admission check reads `VarkaHostLowering`; a test
plants each field and sees its choice follow; the three generated files are unchanged; the width audit
at the preferred width, and under `-XX:UseAVX=2`, reports what it did before; `VarkaMaskGuard.OPEN` reads
the same feature list; and the epilogue pair is committed with the form it chose.

## 10. Outcome

Not started.
