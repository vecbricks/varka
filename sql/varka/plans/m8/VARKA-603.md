# VARKA-603: The checked TINYINT multiply's store at 128 bits on AVX2

Row 603 of `PLAN.md`, added on 11 November 2026 on the owner's word from row 329's close
(`VARKA-329.md` 9.11, "Left open"). On the EPYC 7763 at 128 bits, the checked byte multiply's
stores read 131 to 139 M rows/s on both arms. The default arm's other `TINYINT` stores read 19 to
40 G rows/s on the same host.

## 0. What exists now (admission check)

From master at `d241c12372d`. Paths are under
`sql/catalyst/src/main/java/.../codegen/varka/` unless they say otherwise. The diagnosis was taken
on the laptop under `-XX:UseAVX=2 -XX:MaxVectorSize=16`. Its commands, HotSpot's lines and the
readings are in `results/VARKA-603-diagnosis.txt`. The two JDK-only models it ran are in
`results/VARKA-603/`. Every laptop rate here is diagnostic only. The row's evidence is the
EPYC 7763 runner (section 4). Nothing was dispatched for this plan.

1. **The cause, from HotSpot's own output.** The standard level-4 compiles of shape h's loop
   (`NarrowEmit6N::loopDense0`, 442 bytes) and shape i's (`NarrowEmit7N::loopMasked0`, 706
   bytes), and their WIDENED twins, are each followed by
   `** not supported: op=storeMasked vlen=8 etype=byte`. Each inline tree has exactly two
   declined calls, `VectorSupport::storeMasked ... failed to inline (intrinsic)`, one per int
   group of the iteration. Every other Vector API call in those trees is `(intrinsic)`: the loads,
   the `B2I` parts, the multiply, the range compares, the `I2B`, and the store's mask
   (`indexPartiallyInUpperRange`, from `ByteVector.SPECIES_64.indexInRange(0, lanes)`). So the
   multiply, the overflow check and the mask all compile. The predicated byte store is refused,
   and it runs as the Vector API's out-of-line Java loop.
2. **Why C2 refuses it.** `src/hotspot/share/opto/vectorIntrinsics.cpp:1041-1050` asks for
   `Op_StoreVectorMasked` at 8 byte lanes, and a masked store always needs the predicate.
   `src/hotspot/cpu/x86/x86.ad:1660-1665` answers no for every subword type (byte, short)
   without AVX-512BW, at every vector size. `vectorIntrinsics.cpp:1065-1070` prints the line in
   item 1 and leaves the call (jdk-25-ga source; the JVM that ran was 25.0.5). The JDK-only model
   `MaskedStores603` reads the same thing per element type at 128 bits: byte (8 lanes) and short
   (4 lanes) fall back, int (4 lanes) is intrinsic.
3. **It allocates.** Under `-Xlog:gc`, h and i took 235 and 218 young pauses over three rungs,
   against 3 to 19 for every other shape, while running about 1/200 of f's rows. The JDK-only
   model (`Repro603 masked`) allocates 20 bytes a row at `UseAVX=2` and none at the laptop's own
   AVX-512. The emitted kernels' bytes a row were not measured; the pause counts are their only
   allocation record. If they match the model, that is twenty times the allocation sampler's
   per-row allowance (`VarkaAllocationSampler.BYTES_PER_ROW_ALLOWANCE`, 1 byte), and an AVX2 host
   at 128 bits would warn on these kernels (**[I]**: inferred from the model and the constant; no
   evaluator was run).
4. **Where the emitter writes it.** `VarkaBodyEmitter.emitNarrowTypeStore` (`:1211`) stores a
   narrow root at `analysis.widenLanes(stored)` lanes (`:1229`). At four int lanes a byte has no
   32-bit species, so that is `ByteVector.SPECIES_64`, eight lanes. The condition at `:1249` masks
   every store whose species is wider than the int group, in the loop as well as the epilogue,
   because the iteration's last group has no next group to write over its upper lanes. This is
   the same branch row 479 names for WIDENED's shapes e, f and g (`VARKA-479.md` 0.4). Shapes h
   and i reach it on the default arm because the checked multiply is driven at the int lane on
   either arm. The benchmark prints "the arms' class bytes are identical" for h and i.
5. **Why the `SMALLINT` twins are clean.** At four int lanes a short species of four lanes exists
   (`ShortVector.SPECIES_64`), so `speciesLanes` equals the int group and the store is whole. The
   shorts would fall back too at `lanesOverride=2` (two groups, a masked `SPECIES_64` short
   store), where `MaskedStores603` reads `short64` as a fallback.
6. **Two whole-store forms compile and do not allocate, in a model.** `Repro603` models the
   kernel at 128 bits under `UseAVX=2`:
   - *combined*: the two groups' `I2B` parts 0 and -1 are OR-ed into one `Byte64Vector`, which is
     stored whole once an iteration (row 479's 2.2);
   - *overlap*: each group stores its eight-lane vector whole, and the loop stops one int group
     early so that the last upper half stays inside the segment (the long lane's form,
     `wholeNarrowStoreAtLong`).

   Both read 0 bytes a row and run at 0.96 to 1.8 G rows/s, against 54.5 to 54.6 M rows/s for the
   masked form in the same two quiet runs (`results/VARKA-603-diagnosis.txt`: 17.5 to 33.9 times).
   The two runs order them differently, so they are unranked. Four limits apply. The model is the
   laptop's, a JDK-only class, and runs in a JVM with no other vector code. It loads the byte
   inputs once an iteration, where the emitter reloads them in every group (`emitNarrowLoad`, row
   479's load-once), so the kernel 603b builds has more loads and live vectors per group than the
   model had. An earlier looped variant of *combined* allocated 13 bytes a row from a loop-carried
   vector phi; the straight-line form above, the emitter's own shape, allocated nothing.
   `VARKA-577.md`'s outcome for two long lanes records forms that were clean in an isolated JVM
   and boxed beside other vector code. So the model admits both forms for building, and the
   emitted kernels decide.
7. **The epilogue is masked too, and the two forms treat it differently.** `emitEpilogue` returns
   at once when the batch divides evenly, as 4096 does. Otherwise the epilogue runs one group a
   pass (`emitMixedEpilogue`, up to `narrowGroups()` passes) with a masked narrow load
   (`VarkaVectorWalk.emitNarrowLoad`) and a masked store. *Combined* keeps today's loop bound, so
   the epilogue stays cold on even batches. *Overlap* stops a group early, so the epilogue runs on
   every batch, and its masked byte store would then fall back on every batch. The long lane
   solved that once with a per-row epilogue (`narrowRowsInEpilogue`, `emitNarrowRowsLoad`,
   `emitNarrowRowsStore`, `VarkaVectorWalk.java:929`). *Overlap* needs the same at the int lane.
   The masked load is a weaker case than the store. `vectorIntrinsics.cpp:1052-1063` takes a
   masked load whose offset is known to be in range without the predicate, but whether the
   epilogue's load reaches that path was not read (**[I]**).
8. **The census does not see it.** No cell of `operator_census.json` or
   `results/VARKA-329-census-epyc-7763.json` reads a masked byte store; the only `storeMasked`
   anchor is `long.dec_zip_tail`. A cell is expressible with the existing machinery (section
   2.1). `VarkaOperatorCensusSuite` accepts an `F` verdict (`Verdict.FALLBACK`), as
   `long.dec_zip_tail` reads on both hosts.
9. **The width audit could see it but has not.** `width_audit.json` has `y1 * y2` and
   `try_multiply(y1, y2)` rows, all empty. It is the laptop's file at `use_avx` 3. `AGENTS.md`
   asks for a rerun under `JDK_JAVA_OPTIONS=-XX:UseAVX=2` before an emitter change is pushed,
   and that rerun at 128 bits should list the `storeMasked` refusal for `try_multiply(y1, y2)`
   (**[I]**: not run for this plan).
10. **The same masked store without a narrow input.** `narrowGroups()` counts only the narrow
    values a kernel reads (`VarkaKernelPlan.narrowGroups`, through `VarkaVectorIR.narrowValuesIn`),
    but the mask at `:1249` follows the species stored. A narrowing cast of an int value,
    `CAST(i AS TINYINT)`, `CAST(i + 1 AS TINYINT)` or `try_cast(i AS TINYINT)` (all in
    `emitted_bytes.json`), has one group at four int lanes and still stores `SPECIES_64` under a
    mask in every group of its loop. By the same `x86.ad` rule it falls back on AVX2 at 128 bits
    (**[I]**: not run; found by this plan's review). So the fix must be sized by the stored
    species, not by `narrowGroups()` (2.2).
11. **Who else is in this code.** #1484 (row 104, open) changes `VarkaNodeCompiler`, `DeclineSink`,
    `VarkaEmitOptions`, `VarkaVectorWalk` (at `emitIntNeg`), `emitted_bytes.json`,
    `width_audit.json` and `PLAN.md`, but not `VarkaBodyEmitter` or `VarkaKernelPlan`. A store fix
    does not touch the code #1484 changes, but it shares files with it: `VarkaEmitOptions` (603b),
    `VarkaVectorWalk` (603c's per-row epilogue, at `narrowRowsInEpilogue`), the regenerated files
    and `PLAN.md`. A decline by name would go through `VarkaNodeCompiler` and `DeclineSink`, which
    #1484 changes (its `TWO_LANE_CHECK_REASON` is the precedent). Row 481 (Started) restructures
    the checked multiply through `GuardedRange` and still drives it at the int lane, so it moves
    h's and i's bytes but not their store.

### What differs from the row text

- The row asks whether the store "and its mask" are intrinsified. The mask is; only the store is
  refused (item 1). The cause in row 479's account is now read from HotSpot's output, not
  inferred.
- `VARKA-479.md` 0.1 says that since row 478 the part form is the default only for mixed
  narrow-and-int kernels, and its 1 says "No change to the NATIVE arm". Both predate this
  reading. An all-`TINYINT` kernel whose checked multiply runs at the int lane (h and i) has the
  part form on the default arm. A store fix built as 479 plans it would change NATIVE's h and i.
  This is recorded here; `VARKA-479.md` is not rewritten (question 7.1).
- Item 6 partly answers `VARKA-479.md` 9.2, whether the combine lowers on AVX2: OR-ing the parts
  does, in the model. The emitted kernel's answer is still owed.

## 1. Goal and non-goal

**Goal.** At 128 bits on a host with no predicated subword store (x86 without AVX-512BW, and
NEON, **[I]** for NEON), the main loop of a kernel driven at the int lane whose narrow store
species spans more than one int group stores each narrow root whole and unmasked. Shapes h and i
of `VarkaNarrowEmitBenchmark` then stop running 31 to 36 times slower than at 256 bits.

Kernels with a narrowing cast of an int value (0, item 10) are in the goal too, since they take
the same branch.

**Non-goal.** No change at eight or more int lanes, so no default byte moves on a 256-bit or
512-bit host. No change to the multiply, its range check or `narrowArith` (a short-lane drive of
the byte multiply is question 7.4). No load-once and no
group pricing, which stay row 479's. No decline: the shapes keep fusing.

## 2. Design

### 2.1 The census cell first

A catalog entry, a probe loop and its switch case, in the files the census already uses
(`VarkaOperatorCensusCatalog`, `VarkaOperatorCensusProbe`):

    between("int", "I2B_store64_masked", WIDENING, "store.i2b64.masked", "storeMasked",
            "329, 479, 603", 0, 128)

`op_store_i2b64_masked` converts an `IntVector` at the census's int species with
`convertShape(I2B, ByteVector.SPECIES_64, 0)` and stores it with `intoMemorySegment` under
`SPECIES_64.indexInRange(0, lanes)`, with `lanes` not a constant. Its control is the fix's own
operation, `int.I2B_store64_combined`, bounded the same way (`0, 128`): parts 0 and -1 of
`I2B` into `ByteVector.SPECIES_64`, OR-ed and stored whole. It must read `I`. Expected verdicts:
`I` on the laptop at its own flags, `F` on the EPYC 7763 at 128 bits. The laptop's census file is
one per host and is regenerated at its own flags only. The cell's reading under `-XX:UseAVX=2`
is taken by a one-off fork of the probe, as `hostFactUnderAvx2` does for its cells, and is
recorded in a results text, not in `operator_census.json`.
The cell records what the operation costs on a host, not what the emitter emits. It stays `F` on
the EPYC after the fix, and the fix's evidence is that the kernels stop calling it (3.1).

### 2.2 The store form, behind an option

A new `VarkaEmitOptions` component, `narrowGroupStore`, with three values. It applies to every
narrow root of a kernel driven at the int lane whose store species is wider than the int group:
`parts = widenLanes(stored) / lanes` for that root. Since that count comes from the stored type, it
can differ from `narrowGroups()`, which comes from the narrow inputs (0, item 10). The plan
therefore sizes the iteration by the larger of the two: under `COMBINED` and `OVERLAP`,
`narrowGroups` counts stored narrow roots too (under `MASKED` it stays as now), so a cast like
`CAST(i AS TINYINT)` gets two groups at four int lanes. Each root then stores once every `parts`
groups, at the first row of those groups. Where the store species is no wider than the group the
store is already whole, and nothing changes.

- `MASKED`: today's form, kept as the live reference.
- `COMBINED`: each group's `I2B` (or `I2S`) result goes to a per-root local, converted at part
  `-(g mod parts)` for group `g`, OR-ed into the accumulator. After every `parts`-th group the
  accumulator is stored whole at the first row of those groups and cleared. The loop bound stays
  a whole iteration, so the epilogue keeps its masked one-group passes and stays cold on even
  batches. The parts run 0 to -(parts - 1): for bytes two at four int lanes and four at two, for
  shorts one at four (no change) and two at two. A byte input with a short root at two lanes has
  four groups and a two-part store, so that root stores twice an iteration.
- `OVERLAP`: each group stores its whole `widenLanes` vector, and the next group writes over the
  upper lanes. The loop stops `speciesLanes - lanes` rows early, rounded down to whole iterations,
  so the last whole store stays inside the segment. That is the `wholeNarrowStoreAtLong`
  pattern, with the plan answering "the bound leaves room" for the int lane too. Because that
  epilogue runs on every batch, `OVERLAP` brings a per-row epilogue at the int lane:
  `narrowRowsInEpilogue` and the row-at-a-time load and store generalised from the long lane to
  a kernel at the int lane with a baked lane count.

Both are built, and the EPYC 7763 reading picks the default. That follows the house rule (the
`FloorMod7` precedent) and the owner's standing preference for measuring rival forms rather than
choosing by argument. `COMBINED` comes first, since it needs no epilogue change.

### 2.3 What is deliberately unchanged

The epilogue under `MASKED` and `COMBINED`, cold on even batches and at most `narrowGroups()`
masked passes on a ragged one. The narrow load issued once per group (row 479). The grouping
weights (row 479). The byte-driven arm's own epilogue (10.1, item 3).

## 3. Tests and proofs

1. **Op counts.** In `VarkaEmitterNarrowArithSuite`, h's and i's shapes at `lanesOverride=4` and
   `2`, on both `narrowLanes` arms: under `COMBINED` and `OVERLAP` the loop methods (`loopDense0`,
   `loopMasked0`) hold no masked `intoMemorySegment`, and one whole store per narrow root per
   iteration (`COMBINED`) or per group (`OVERLAP`). Under `MASKED` they hold today's count. The
   numbers come from `dev/varka_emit.sh --table --variant narrowGroupStore=...`, not from reading
   the emitter. The same test for a WIDENED store shape (`-y1`) and for `CAST(i AS TINYINT)` on the
   default arm (0, item 10), since they take the same branch.
2. **Answers.** `VarkaEmitterNarrowSuite` and `VarkaEmitterNarrowArithSuite` under every
   `narrowGroupStore` value at `lanesOverride=4` and `2`, against the reference, with batch lengths
   leaving 0, 1, `lanes - 1`, `lanes` and `groups * lanes - 1` rows past the loop (and, for
   `OVERLAP`, a batch shorter than the early stop). The exhaustive `TINYINT` pairs in `LEGACY` and
   `TRY` run under each value at `lanesOverride=4`.
3. **The fuzzer and the matrix.** The new component gets its `VarkaEmitOption.TABLE` entry, so
   `VarkaIrFuzzSuite` draws it and the option matrix gains its configurations. The matrix's
   `isa=avx2` and `isa=w128` axis (row 505) runs it where the store matters.
4. **The width audit under AVX2.** On the laptop, under `JDK_JAVA_OPTIONS=-XX:UseAVX=2` at 128
   bits, before and after. The `try_multiply(y1, y2)` row (and `y1 * y2` under ANSI if the audit
   draws it) lists `storeMasked` under `MASKED` and nothing under the default chosen in step 4.
5. **The warm-up.** `COMBINED` adds `convertShape` at a negative part and a byte `or` that the
   template warm-up may not make. `VarkaWarmupReachSuite` must pass, and it gains the calls if
   it fails (as `VARKA-479.md` 6 foresaw).
6. **`emitted_bytes.json`.** Steps 2 and 3 move only the option-arm digests. Step 4's flip moves the
   128-bit and `lanesOverride` arms of shapes with a masked narrow store today (a part-form kernel
   or a narrowing cast), read shape by shape. The 256-bit and 512-bit default columns do not move.

## 4. Predictions, registered before the build

The evidence is `VarkaNarrowEmitBenchmark` on the EPYC 7763 runner (AVX2, no predicated byte
store) at 128 bits (`-XX:MaxVectorSize=16`) and 256 bits. It is regenerated beside the committed
`sql/catalyst/benchmarks/VarkaNarrowEmitBenchmark-jdk25-runner-epyc7763-128bit-results.txt` and
its 256-bit twin, and compared by `dev/varka_bench_diff.py`. The benchmark gains the
`narrowGroupStore` values as arms, named in every case label so that a default flip does not
change what a label means.

- P1: at 128 bits, h and i under `COMBINED` and under `OVERLAP` are at least 10 times faster than
  under `MASKED` (131 to 139 M rows/s now), on both `narrowLanes` arms. The model's ratio is
  17.5 to 33.9 (`results/VARKA-603-diagnosis.txt`; **[I]**: a laptop model, not the kernel).
- P2: WIDENED's e, f and g at 128 bits move the same way (150 to 156 M rows/s now), and so does
  a `CAST(i AS TINYINT)` store case, which 603b adds to the benchmark (no committed number yet;
  its `MASKED` arm is the baseline in the same run).
- P3: at 256 bits nothing moves beyond the band, since no kernel there has more than one group.
  This is the control.
- P4: the SMALLINT shapes j and k do not move at either width (one group at four int lanes).
- P5: no forecast between `COMBINED` and `OVERLAP`; the model's two runs disagree (0, item 6).
  The default becomes the faster form at 128 bits if it beats the other by more than the band,
  and `COMBINED` on a tie, since it leaves the epilogue cold.

## 5. Order, files and size

Four pull requests, each green on its own and under the 1000-line cap. Every push and dispatch
is on the owner's word.

| step | what | counted lines (estimate) | depends on | owes |
|---|---|--:|---|---|
| 603a | census cell and its control (2.1); laptop `operator_census.json` regenerated at its own flags (`VARKA_CENSUS_REGEN=true`), the cell's `-XX:UseAVX=2` reading by a one-off fork into a results text; the EPYC column from `varka-width-audit.yml`'s x86 artifact, which a push touching `VarkaOperatorCensus*.java` triggers | ~100 | nothing | one width-audit run on an EPYC 7763 runner |
| 603b | `narrowGroupStore` with `MASKED` and `COMBINED` (2.2), `narrowGroups` counting stored narrow roots under the new forms, its table entry, the benchmark arms and a `CAST(i AS TINYINT)` case, tests 3.1 to 3.5 for `COMBINED`, the width audit under AVX2 | ~450 | #1484 merged (shared `VarkaEmitOptions`, regenerated files, `PLAN.md`); question 7.1 answered | the laptop width audit at 128 bits under `UseAVX=2` |
| 603c | `OVERLAP`: the int lane's early loop stop in the plan, the per-row int-lane epilogue, its tests | ~350 | 603b; #1484 merged (`VarkaVectorWalk`) | none of its own |
| 603d | the EPYC 7763 runs at 128 and 256 bits with every arm, the default chosen by P5, the runner files regenerated, the bytes oracle and width audit regenerated, section 10 | ~80 plus generated files | 603b, and 603c unless the owner drops `OVERLAP` (7.2) | one dispatch at each width (two runs) |

Files: `VarkaOperatorCensusCatalog.java`, `VarkaOperatorCensusProbe.java` and the census files
(603a); `VarkaEmitOptions.java`, `VarkaEmitOption.java`, `VarkaBodyEmitter.java`, `Slots.java`,
`VarkaEmitterNarrowSuite.java`, `VarkaEmitterNarrowArithSuite.java` and
`VarkaNarrowEmitBenchmark.java` (603b); `VarkaKernelPlan.java`, `Analysis.java` and
`VarkaVectorWalk.java` at `narrowRowsInEpilogue` (603c); the two runner result files,
`emitted_bytes.json` and `width_audit.json` (603d). Row 481 can land before or after. If it lands
first, 603d's bytes and rates are read against its merge, not `23c26374efe`'s.

Size: small, as the row says, for 603a, 603b and 603d. 603c is the owner's choice (7.2).

## 6. Out of this row

- Load-once and the pricing of `narrowGroups`: row 479 (7.1).
- A decline by name at 128 bits: not planned unless the owner picks 7.1 (e).
- The side readings in 10.1.

## 7. Questions for the owner

*Answered by the owner on 11 November 2026: 1, (c), this row builds the whole store for every int-lane kernel whose narrow store is masked today, and row 479 keeps load-once and the pricing; 2, both forms are built; 3, the EPYC 7763 at 128 bits decides the default, with the 256-bit run as the control; 4, (c), a follow-up row after row 481 and 603d.*

1. **How row 603 relates to row 479.** They share one branch of `emitNarrowTypeStore`, so no store
   fix can cover h and i without also covering WIDENED's e, f and g and the mixed kernels.
   "Separate for h and i" can only mean separate ownership and evidence. The options:
   - (a) *603 waits and closes on 479's fix* (the row text's own rule). For: one change, no
     re-planning. Against: 479 is medium and waits behind the type rows (the owner's 23 October
     word). Its premises (0.1, the NATIVE non-goal) do not cover h and i, and its predictions do
     not name them, so it needs amending anyway. The default arm's 138- to 308-fold loss waits on
     load-once and pricing that it does not need.
   - (b) *603 folds into 479 as its x86 128-bit step.* 603 closes as superseded, and 479 gains h and
     i, the census cell and the AVX2 evidence. For: one row and one plan. Against: the same wait as
     (a), a medium row grown larger, and 603's done-when lives on inside another row's.
   - (c) *603 builds the whole store (2.2) for every int-lane kernel whose narrow store is masked
     today (the part-form kernels and the narrowing casts of 0, item 10) and closes on h's and i's
     EPYC rerun; 479 keeps load-once and pricing, and its store half (2.2, step 3) points here.*
     For: the default arm of a new type is fixed first and on its own, small, which serves
     milestone 8's goal. The code is written once. The census cell and the evidence stay with the
     row that found them. 479's open point 9.2 gets its answer from a built kernel. Against: two
     rows move one loop, so 479's plan needs a dated note and its row a re-plan line. 479's load
     once must then build on 603's combined store, not its own.
   - (d) *603 fixes only h and i.* Against: the store does not know its producer. It would need
     a test of what the root is, to keep a fix away from shapes that suffer the same refusal.
     Rejected.
   - (e) *Decline by name at 128 bits on hosts with no predicated subword store*, as row 503 does
     for two-lane long conditions (the row text allows it). For: no new emitter form. Against: it
     goes through `VarkaNodeCompiler` and `DeclineSink`, which #1484 changes. It must also cover
     e, f, g and the mixed kernels, and needs a host test. It gives up fusion where both whole
     forms compile, and the row path's rate for these shapes is unmeasured.

   **Recommended: (c).** On a yes, 603b's pull request adds a dated note to `VARKA-479.md` (2.2 and
   9.2) and a re-plan line to row 479, without rewriting either.
2. **Build both forms, or `COMBINED` only?** Building `OVERLAP` costs the int lane's per-row
   epilogue (603c, about 350 lines), which only `OVERLAP` needs. The model does not rank the two.
   **Recommended: both**, by the house rule and the standing preference for measuring rivals.
   `OVERLAP`'s per-row epilogue would also serve a later fix of the ragged-batch epilogue under
   the other forms.
3. **The decisive host.** **Recommended:** the EPYC 7763 at 128 bits decides the default, the
   256-bit run is the control, and the laptop numbers stay diagnostic. If row 505's Arm runner
   bin is cheap, an `ubuntu-24.04-arm` reading at 128 bits is recorded but does not decide. That
   runner is Neoverse N2 with SVE2, whose predicated store may make `MASKED` cheap there
   (**[I]**).
4. **A short-lane drive for the checked byte multiply** (raised by this plan's review). A byte
   product fits a short exactly (128 * 128 = 16384), so the checked `TINYINT` multiply could run
   at the short lane. That is eight lanes at 128 bits, with a whole `SPECIES_64` byte store and
   no parts. The `SMALLINT` twin `s1 * s2` already reads about 4.5 G rows/s at 128 bits on whole
   stores. The options:
   - (a) *Not in this row.* The whole store (2.2) fixes h and i, WIDENED's e, f and g and the
     casts. A short drive fixes h and i only, and it changes the multiply, which row 481
     (Started) is restructuring through `GuardedRange`.
   - (b) *Here, as a fourth arm of h and i.* For: it may be faster than any whole store at the int
     lane, since it has no parts and no conversions back to bytes per group. Against: it moves
     `narrowArith` and the range check, which this row's non-goal keeps out. It overlaps row 481,
     and it leaves the other shapes to 2.2 anyway.
   - (c) *A follow-up row after row 481 and this row's 603d.* It would be measured against
     whichever store form 603d picks.

   **Recommended: (c).** 603d's numbers show how much is left. If h and i stay well below the
   `SMALLINT` twin after the fix, the follow-up is admitted with that gap as its reason.

## 8. Done when

- The operator-census cell `int.I2B_store64_masked` and its control `int.I2B_store64_combined` are
  in `operator_census.json` (laptop, at its own flags) and in an EPYC 7763 census file, each with
  the verdict its host reads (expected, 2.1: the masked cell `F` on the EPYC at 128 bits, the
  control `I`). The masked cell's verdict is the host's and is not changed by the fix.
- The cause is recorded from HotSpot's output under `-XX:UseAVX=2 -XX:MaxVectorSize=16`, with the
  commands: section 0 and `results/VARKA-603-diagnosis.txt` (done by this plan).
- At 128 bits under the default `narrowGroupStore`, the main loops of h and i hold no masked
  `intoMemorySegment` on either `narrowLanes` arm, at `lanesOverride=4` and `2`, pinned by 3.1.
  The laptop width audit under `UseAVX=2` at 128 bits lists no `storeMasked` for them.
- The narrow suites pass under every `narrowGroupStore` value at four and two int lanes, and the
  option is in the fuzzer and the matrix.
- `VarkaNarrowEmitBenchmark` is rerun on the EPYC 7763 at 128 and 256 bits with every arm. The
  runner files are regenerated beside the committed numbers, and P1 to P5 are scored in section
  10.

## 9. Open points for the lead

1. If 7.1 is (a) or (b), sections 2 to 5 move into `VARKA-479.md`, and this file keeps section 0
   and the census step.
2. The epilogue's masked load at the int lane (0, item 7) was not read under `PrintIntrinsics`.
   603c's per-row epilogue makes it moot for `OVERLAP`. For the other forms it is a ragged-batch
   cost only.

## 10. Outcome

603a and 603b are built; 603c and 603d are the row's remaining steps.

### 603b: `narrowGroupStore` with MASKED and COMBINED (14 November 2026)

Built as 2.2 says, on master `8c2b8a4493e`. `VarkaEmitOptions.narrowGroupStore` selects the
store's form. `MASKED` is the default: it renders empty in `canonical()` and emits today's bytes,
so no committed shape hash or digest moves. `COMBINED` renders `|narrowGroupStore=COMBINED`.
Under `COMBINED`, `VarkaKernelPlan.narrowGroups` also counts each stored narrow root's parts (two
for a byte at four int lanes, four at two), so `CAST(i AS TINYINT)` - which reads no narrow value
at all - runs two groups an iteration where the masked form runs one; a selection root writes a
bitmap and no store, so it is not counted. `VarkaBodyEmitter.emitCombinedNarrowStore` converts
each group at part `-(g mod parts)`, ORs it into the root's accumulator (`Slots.narrowStoreAcc`,
with one shared conversion temporary) and stores the accumulator whole at the run's first row,
then clears it; the loop's accumulators are zeroed before it, and the epilogue keeps the masked
form under either value.

The parts' numbering was verified before the build in a JDK-only probe: a contracting `I2B`/`I2S`
takes part `0, -1, -2, -3` for its successive blocks in row order (a positive part is refused),
which is the plan's formula. One divergence from section 5's file table: the `narrowGroups` half
of 2.2 is in `VarkaKernelPlan.java`, which the table lists for 603c; 2.2 requires it in 603b.

The tests, 3.1 to 3.5, and 3.6:

- **3.1** passes. At four int lanes a byte store of an int-driven kernel holds two masked stores
  and no whole one under `MASKED`, and no masked store and one whole one per run under
  `COMBINED`; at two int lanes it is four and one. `CAST(i AS TINYINT)` holds one masked store in
  its one-group loop under `MASKED` and one whole store in its two-group loop under `COMBINED`.
  The byte-driven arm under `narrowLanes=true` is unchanged.
  `dev/varka_emit.sh --table --variant narrowGroupStore=...,lanesOverride=4` reads, as
  `loopDense0`'s lane ops, `y1 * y2` 6 at the defaults and 12 at four int lanes under either form,
  `try_multiply(y1, y2)` 0 under all three columns (it has only a masked loop, which the table
  does not count), and `CAST(i AS TINYINT)` 4 at the defaults, 4 at four int lanes under `MASKED`
  and 8 under `COMBINED`. So the table sees the cast's second group and cannot tell the two store
  forms apart - the store, its mask and the species are not lane ops on the owners it counts -
  and 3.1's class-file counts are the form's evidence.
- **3.2** passes. `VarkaEmitterNarrowArithSuite` ran 37 tests (19 opt-in sweep tests canceled) and
  `VarkaEmitterNarrowSuite` 16, under both forms at four and two int lanes, with batch lengths
  leaving 0, 1, lanes - 1, lanes and groups * lanes - 1 rows past the loop and one batch shorter
  than a whole iteration, against the reference and Spark; the exhaustive `TINYINT` pairs run in
  LEGACY and TRY under each form at four int lanes. A short column read through a byte store - the
  case whose load needs one part while the store needs two - answers too.
- **3.3** is the table entry: the choice is in `VarkaEmitOption.TABLE`, so the IR fuzzer draws it
  and the option matrix gains its configurations. `VarkaEmitOptionSuite` passes (8 tests).
- **3.4** is `results/VARKA-603b-width-audit-avx2.txt`: at 128 bits under `-XX:UseAVX=2`, a
  one-off fork of the benchmark's emission path for `try_multiply(y1, y2)` reads seven
  `not supported: op=storeMasked` lines under `MASKED`, six of them the eight-lane byte store,
  and no `storeMasked` line at all under `COMBINED`, whose store is intrinsic. The committed
  `width_audit.json` stays the laptop's own census (AVX-512BW, where the masked store is
  intrinsic and the shape's 128-bit row is empty), so this reading is a results text, as 603a's
  AVX2 cell reading is.
- **3.5** passes: `VarkaWarmupReachSuite` is green (3 tests), whose corpus is the coverage rows
  and fuzz shapes at the defaults and the matrix base, plus every other matrix configuration
  that overrides no lane count (a lane count's kernels name another species, which the warm-up
  does not feed). `COMBINED`'s `ByteVector.or`/`ShortVector.or` and their `zero(VectorSpecies)`
  are now called in the warm-up's byte and short blocks, so a four-int-lane JVM has both past
  the old mark; the review found the corpus naming them at 0 calls.
- **3.6**: `emitted_bytes.json` is the one generated file that moved. Its `option_arms` gained
  `narrowGroupStore=COMBINED` (17 arms to 18), the new arm pinned in `VarkaEmittedBytesSuite`
  beside the lane arms, and every default hash, every existing arm and the per-lane `lanes`
  section are unchanged. `width_audit.json`, `coverage.json`, `docs/sql-varka.md`, `types.tsv`
  and the census files are unchanged, as the unchanged defaults require. (This revision's
  review adds a width-2 `lanes` column, moves the pinned `COMBINED` arm's digest, and adds the
  option's two arms over its subject; see below.)

### Review fixes (15 November 2026)

The `/code-review high` of 603b's fixes: the template warm-up's byte and short blocks call the
whole-vector `or` and `zero` the combined store uses; `narrowGroupStore` runs over
`lanesOverride=4` and joins `C2_OPTIONS`; the benchmark keeps `WIDENED` and `NATIVE` (with
`WIDENED_COMBINED` and `NATIVE_COMBINED` beside them), so the committed rows still join; the
store's predicate is `VarkaKernelPlan.narrowStoreParts`, whose closing store asserts
`narrowGroups % parts == 0` by the named error and tests its accumulator array for null;
`CAST(i AS SMALLINT)` covers the `I2S` branch and `ShortVector` at two int lanes; the run's
first group assigns the accumulator, so no zero precedes it and the loop carries none; the
ragged tests add lengths 24 and 40 and a forced-mask body, and compile each (lanes, arm, form)
once; `skips.tsv` records the two store-form tests under `checkIntOverflow=false`.

The pin: the `COMBINED` arm's width-4 digest lost the loop's `or` and both `zero` calls, width
16 did not move (inert there), and a new width-2 column pins the four-part store.

**Recorded, no code (for 603c/603d).** `narrowGroups` sizes every narrow load too: a one-group
narrow load beside a two-part store reads `groups * intLanes` lanes once per group, twice the
read bytes at four int lanes. `CAST(s1 AS TINYINT)` at four int lanes is the shape that prices
it - one-group `SMALLINT` load, two-part byte store - timed under `MASKED` and `COMBINED`.

Left to 603c: `OVERLAP` and the int lane's per-row epilogue. Left to 603d: the default choice on
the EPYC 7763 at 128 bits (with the 256-bit control), the runner result files, and the bytes,
width-audit and census regenerations that follow a flip.

### 10.1 Side readings, candidates for rows (no row added)

1. **An OSR-only shuffle refusal.** The OSR compiles of i print `** unbox failed v2=Phi` and
   `** not supported: arity=1 op=cast#513/4 vlen1=2 etype2=byte` for a
   `Long128Vector::bitsToShuffle` convert. The standard compile that runs has neither. A row
   would be owed only if a kernel is ever seen running its OSR body for long.
2. **A two-lane long masked store falls back at `UseAVX=2`.** `MaskedStores603 long128` reads a
   fallback with 110424 bytes a call. The reason is unverified. One guess is a two-lane mask's
   boolean load below x86's four-byte minimum (`x86.ad:2212-2221`). This bears on rows 503, 577
   and 604's two-long-lane forms. A candidate row would read the reason and add a census cell.
3. **A 16-lane masked byte store is refused.** `not supported: op=storeMasked vlen=16 etype=byte`
   follows standalone compiles of `ByteVector::intoMemorySegment`. That is the byte-driven
   arm's epilogue form at 128 bits (**[I]**: the caller was not identified). It costs only on
   ragged batches. The long lane has a per-row epilogue and the byte lane has none. 603c's
   int-lane form would be the precedent for a candidate row.
