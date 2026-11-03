# Reading: what LLVM's loop vectorizer offers Varka's unroll factor and tail

Read on 3 November 2026 for VARKA-25 (the unroll factor, now `m9/SCOPE.md` item 15) and the
remainder of a loop that runs more than one lane group an iteration. Source: llvm-project at tag
`llvmorg-20.1.8` (commit `87f0227cb601`), Apache-2.0 WITH LLVM-exception, a sparse checkout kept
outside the repository. Read: `llvm/docs/Vectorizers.rst`, `llvm/docs/VectorizationPlan.rst`, the
cost-model parts of `llvm/lib/Transforms/Vectorize/LoopVectorize.cpp`, `VPlan.h` (the recipe
base), the head of `VPlanUnroll.cpp`, `llvm/lib/Target/X86/X86TargetTransformInfo.cpp` (register
count, interleave cap) and `llvm/include/llvm/Analysis/TargetTransformInfoImpl.h` (defaults).
LLVM line numbers below are that tag's; Varka's are `origin/master` `f72e4556750`. **[V]** means
read in the code, **[I]** inferred. Nothing was built or run, and no code is copied.

## 1. What LLVM does, in the parts that bear on Varka

* **A register-usage estimate before anything is built [V].** `calculateRegisterUsage`
  (`LoopVectorize.cpp` 5145-5359) numbers the loop's instructions in reverse post-order, opens an
  interval at each in-loop value's definition and closes it at its last in-loop use, and keeps the
  peak of the open set per register class (`MaxLocalUsers`). A vector value wider than a register
  counts as several. Loop invariants are counted apart, once, because interleaving does not
  multiply them (5159-5162, 5309-5327); constants are ignored. The comment calls it "a very rough
  estimation" (5147), and nothing checks it against the register allocator.
* **The interleave cap [V].** `selectInterleaveCount` (4861-5143): per class
  `IC = bit_floor((R - invariants - 1) / max(1, maxLocal - 1))`, the `- 1`s keeping the induction
  variable out (4949-4957), the minimum over classes, then clamped by the target's maximum (4 on
  any AVX x86, `X86TargetTransformInfo.cpp` 227-243) and by the trip count, so the vector loop runs
  at least twice (4980-5018). x86 has 32 vector registers with AVX-512 and 16 without (163-176).
* **The trigger is not the cap [V].** A loop without a reduction is interleaved only when its cost
  is under `SmallLoopCost` = 20 (302, 5059-5074), to amortise loop overhead, or to saturate load
  and store ports; otherwise IC is 1, since `enableAggressiveInterleaving` is false by default
  (`TargetTransformInfoImpl.h` 428) and x86 keeps it.
* **The tail [V].** The x86 default is a scalar epilogue; predication over the whole loop is off
  (`PreferPredicateOverEpilogue`, 218-221). Epilogue vectorization adds a second vector loop at
  IC = 1 for the remainder before the scalar one, only when VF x IC >= 16 (4649-4674), and its own
  source comment calls the heuristic crude (4651-4654).
* **Cost and code from one structure [V].** `VectorizationPlan.rst` "Design Guidelines": each
  VPlan must support "both estimating the cost and generating the output IR code". A recipe models
  "a sequence of one or more output IR instructions" (`VPlan.h` 704-708) and prices itself.
  `unrollByUF` clones each recipe beside itself unless it is uniform across parts, so invariants
  are not replicated (`VPlanUnroll.cpp`).

## 2. Sorted against Varka

### (a) Worth a plan note or a row

1. **The live count VARKA-25 lacks, by LLVM's scan [I].** `m9/SCOPE.md` item 15's 3 November note
   records that "the live-temporary count the emitter already computes" does not exist. LLVM's
   estimate is that count, cheap and computed from the IR. For Varka: intervals over the group's
   emission order (outputs in group order, each a post-order of `VarkaVectorWalk`), never over
   `max_stack` or local slots; three classes Varka must name itself, vectors, `VectorMask`s (one
   vector register on AVX2, a k-register on AVX-512) and validity longs (general registers); the
   dense and masked bodies counted apart, since only the masked one carries validity words and
   the `kT`/`kF` pairs; a calendar or division node priced at its internal peak, for which
   `VarkaEmitBudget`'s `*_TMP_COUNT` and `CHRONO_PREFIX_SLOTS` constants (lines 424-505) stand in.
   Invariants counted once is 2.24's broadcast confounder (`m5/PLAN.md` 2.24): a broadcast
   emitted per use is rematerialisable, a broadcast pinned in a local is an invariant, the pinned
   case the 7x collapse at 32 literals of `m2/VARKA-10.md`. Varka's calibration is stronger than
   LLVM's: item 15 compares the count with C2's spill moves per loop method, and VARKA-32's pair
   (equal op counts, 4 against 74 xmm spill moves) is the first case any count must separate.
2. **The cap, with Varka's measured ceiling [I].** `K = min(2, bit_floor((R - inv - 1) /
   max(1, live - 1)))`, R = 16 or 32 by host: 8 live values and one invariant give 2 at 16
   registers and 4 at 32 before the `min`, 15 live give 1 at 16 and 2 at 32. The 2 is Varka's (K = 4
   bought nothing, `VarkaUnrollFactorBenchmark-jdk25-results.txt`), which LLVM's x86 maximum of 4
   does not contradict. That file was measured on the laptop, its narrow run under
   `-XX:MaxVectorSize=16`, which keeps 32 registers (item 15's note), so every unroll number so
   far had 32. The admission check needs an AVX2 host (the EPYC 7763 runners) or `-XX:UseAVX=2`.
   The benchmark's chains are serial with about three live values, so the cap never binds there;
   it binds on wide fused bodies (four calendar fields, `dayofweek` with validity).
3. **LLVM's trigger disagrees with Varka's measurement, which names a hypothesis [I].** On x86
   LLVM would interleave the 8-op chain (cost under 20) and not the 20-op one; Varka measured the
   reverse, 8 flat and 20 better by 4.4% at K = 2 (same file). A likely reason is C2's own
   unrolling: `policy_unroll` unrolls a body under `LoopUnrollLimit`, sixty on x86 (`m9/SCOPE.md`
   item 26, "Unrolling, from the source"), which covers LLVM's small-loop case, and leaves a
   bigger body to an emitted K. The record is split: `skills/vector-api-and-width.md` says C2 does
   not unroll Vector API pipelines, `m4/VARKA-46.md` 9.3 saw it unroll a small `year` loop. So the
   JIT log of the two benchmark loops (`PrintCompilation` and the fastdebug loop-opts trace) is
   read first; if it confirms, "K > 1 only for a body C2 will not unroll" is the gate.
4. **The remainder of a K = 2 loop [I].** 2.24 notes the remainder grows to `K * lanes - 1` rows.
   LLVM's shape names the choice: one unmasked K = 1 iteration for a remaining full group, then
   today's one masked iteration, rather than up to K masked ones, which pay masked access
   (2.3-2.9x, `VARKA-10.md`) over more rows. On a 4096-row batch neither runs; LLVM's trip-count
   rule (interleave only when the loop runs at least twice) is the short-batch rule.

### (b) Not applicable to a bytecode-over-C2 target

Runtime alias checks (Arrow buffers never alias in a kernel call); a scalar epilogue (Varka's tail
is one masked vector iteration, VARKA-24, and its fallback is Spark's row path); per-instruction
costs and VF by cost per lane (C2 selects instructions, and a per-shape species meets the
species-keyed warm-up of `m7/VARKA-295.md`); scalable vectors, EVL tail folding and early-exit
loops; VPlan's regions and outer-loop work (Varka's IR is a DAG with no control flow in a lane
group).

### (c) Varka already does it as well or better

* **The masked one-iteration tail**: free on aligned batches, small per partial batch
  (`m4/VARKA-24.md` 2); both agree full predication does not pay on x86.
* **Loop versioning**: the per-batch choice of the dense or masked body is LLVM's runtime check
  and versioned loop, applied to validity instead of aliasing.
* **Narrow loads in parts**: `VarkaKernelPlan`'s narrow groups read a byte column as one wide
  species in parts, the default VF rule plus `MaximizeBandwidth`'s accounting.
* **Align cost and execute**: `VarkaEmitCost` prices the same walk the grouping takes and is
  audited against built classes (`emit_cost_audit.json`), a ground truth LLVM's debug
  cross-check of its two models lacks.
* **Lanes that must not trap**: inactive lanes read 0 and nothing traps on 0 (`VarkaLoopEmitter`
  class doc), and VARKA-30's division never issues a trapping vector divide, which covers LLVM's
  safe-divisor choice.

Section 2 (a) items 1 to 4 are the note on `m9/SCOPE.md` item 15 of 3 November 2026. Not read:
the SLP vectorizer, VPlan's recipe execution, and the targets other than x86.
