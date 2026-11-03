# Reading: what ISPC's masked control flow offers Varka's `IF` and `CASE`

Read on 3 November 2026 for `m9/SCOPE.md` item 23 (narrowing a `CASE` arm by its cost) and
VARKA-25's unroll factor. Sources: Pharr and Mark, "ispc: A SPMD Compiler for High-Performance CPU
Programming", InPar 2012 (pharr.org/matt/assets/ispc.pdf), sections 3.2-3.3, 5.1, 5.6, 6.1-6.6
and Table 1; and github.com/ispc/ispc at commit `ded7463f4926` (2 November 2026), BSD-3-Clause
(Intel), a sparse checkout of `docs/` and `src/` kept outside the repository. Read: `src/ctx.cpp`,
`src/stmt.cpp` (`IfStmt`), `src/expr.cpp` (logical operators, `SelectExpr`), `src/ispc.h` (the
cost enum), `src/ispc.cpp` (targets), `src/opt/ImproveMemoryOps.cpp`,
`src/opt/IntrinsicsOptPass.cpp`, `docs/perfguide.rst`. Varka's side is `origin/master`
`f72e4556750`. **[V]** means read in the code or paper, **[I]** inferred. Nothing was built or
run, and no code is copied.

## 1. What ISPC does

* **Control flow becomes data flow under an execution mask [V]** (paper 3.3). `if` sets the mask
  to `old & test`, then `old & ~test` (`ctx.cpp` 463-471); `break` and `continue` fold lanes into
  per-loop masks (705-717, 766-775). Only side effects are masked: "it is unnecessary for most
  temporaries computed when evaluating an expression".
* **Three ways to emit a varying `if` [V]** (`stmt.cpp` 666-735). Under a cost of 6
  (`PREDICATE_SAFE_IF_STATEMENT_COST`, `ispc.h` 1034) and safe with the mask all off: both arms
  straight-line, blended. Otherwise `emitMaskMixed` (808-855): each arm once, under
  `if (any(mask))`. The coherent `cif` (`emitMaskAllOn`, 737-805) branches on `all(test)` and
  `!any(test)` to arm-only blocks and emits each arm up to three times.
* **The same rule for expressions [V].** `?:`, `&&` and `||` evaluate an operand under
  `any(mask)` only when its cost is over 6 or it is unsafe all-off (`expr.cpp` 2023-2029,
  3874-3880); the table (`ispc.h` 1004-1031) prices a simple op 1, a select 4, a gather 8.
* **Static mask folding [V].** A blend under a known mask becomes an operand
  (`IntrinsicsOptPass.cpp` 94-99); a masked store under a known all-on mask becomes a plain store
  (`ImproveMemoryOps.cpp` 1862-1925). `foreach` is full gangs unmasked plus one masked partial
  iteration (paper 5.6).
* **Measured [V]** (paper Table 1, geometric mean over seven workloads, AVX, each optimisation
  disabled in turn): coherent control flow off 0.85x (aobench 0.33x, Black-Scholes 1.0x); all-on
  mask optimisations off 0.73x; gather coalescing off 0.79x.
* **Instrumentation [V]** (`perfguide.rst` "Instrumenting"): `--instrument` reports, per control
  point, its calls, the share with the mask all off and the mean share of active lanes.

## 2. Sorted against Varka

### (a) Worth a plan note or a row

1. **A per-lane-group skip of a heavy arm, as `emitMaskMixed`, not `cif` [I].** Varka's `IfElse`
   (`VarkaVectorWalk`, `case IfElse`) computes the condition, both arms and a blend, always. ISPC's
   rule would branch around the nodes only the arm uses, `if (kT.anyTrue())` for THEN; ELSE takes
   the unknown lanes too, so in the masked body its test is `(~kT & live) != 0`. The blend stays,
   and each arm is emitted once, so the method and call-site budgets see two branches more; `cif`'s
   three copies they would refuse. It differs from what is recorded: item 23 is per-batch
   compaction (ClickHouse), right for a rare arm or a mostly-null batch; the lane-group skip needs
   no compaction and pays on clustered data, a time-ordered batch where `d < cutoff` holds for long
   runs. `m4/VARKA-24.md` 9 declined a per-group branch to save a few long-word ops; here the
   skipped arm is a calendar decomposition, `AddMonths` or a bounded division, dozens of vector
   ops, and in milestone 8 exact math or the 128-bit decimal pair. Costs: the skip set is the nodes
   the arm alone uses (a node shared with the condition or another output is computed anyway),
   which `Analysis`'s arm contexts (`collectArmContexts`) already walk; a skipped arm's local is
   zeroed for the verifier. On random data, a condition over two uniform columns as the
   benchmarks draw them, the branch is unpredictable and the skip a strict loss.
2. **A coherence census first [I].** One counter per `IfElse` in a debug arm, ISPC's
   instrumentation: per lane group, is the known-true word all on, all off or mixed, on the CASE
   rows and on a sorted variant. It decides item 1, item 23's compaction threshold and Lang's
   refill thresholds (item 25) alike, and needs no emitter change beyond the counter. Paper
   6.4 gives the scale to expect: 0.85x geometric mean, aobench 0.33x, nothing on incoherent data.

Both are the note on `m9/SCOPE.md` item 23 of 3 November 2026, not a row: milestone 8 is type work.

### (b) Not applicable to a bytecode-over-C2 target

* **Doubled gangs** (`avx2-i32x16`, `ispc.cpp` 1524-1531): LLVM legalises a 16-wide value into two
  registers; a Vector API species wider than the hardware runs the Java fallback
  (`VarkaEmitOptions`, `lanesOverride`: "slowly if the hardware is narrower"). The analogue is the
  unroll factor, VARKA-25; the perf guide's "try both, register-pressure bound" agrees with
  `m2/VARKA-10.md`'s hoist finding.
* **Gather coalescing**: Varka's kernels have no gathers.
* **Masks through loops** (`breakLanes`, `continueLanes`): Varka's IR has no loops; the design is
  the reference if a data-dependent loop node arrives.
* **"Masking is free" on AVX-512**: the Vector API's masked ops measured 2.3-2.9x slower even
  all-true (`VarkaLoopEmitter` class doc, `VARKA-10.md`); unmasked compute with truth in validity
  words stays the choice.
* **`uniform` as a type qualifier**: Spark folds literal-only subtrees before Varka sees them.

### (c) Varka already does it as well or better

* **`foreach`** is Varka's unmasked loop plus one masked epilogue (VARKA-24), and the
  inactive-lanes-read-0 invariant (`VarkaLoopEmitter` class doc) is `SafeToRunWithMaskAllOff`.
* **All-on specialisation** (paper 6.6): the dense body is a static all-on version chosen per
  batch, with no validity work at all, and the all-null and null-free batch paths exist.
* **Arm-scoped side effects**: Varka's side effect is a guard's raise, and `guardUnderArm`
  (`VarkaEmitOptions`, on by default) scopes it to the arm's lanes with no branch.
* **Literals as uniform**: a `LiteralSlot` is broadcast once a call, its hoist gated by measured
  register pressure (`m2/VARKA-10.md`).
* **Mask width per data width**: validity is a long word per lane group whatever the lane width,
  a `VectorMask` made only at a blend, AVX-512's representation on every target.
* **"Avoid 8- and 16-bit types"**: Varka measured the opposite on the Vector API, native narrow
  lanes 2x to 16x faster than widening (`m8/VARKA-329.md` 9.5-9.6), since a narrow species holds
  more lanes; ISPC's gang is fixed by the 32-bit width.
* **Skipping only the blend**: one op, priced 4 against ISPC's threshold of 6; never worth a branch.

Not read: ISPC's task system, its gather and scatter passes beyond the store folding, and the
targets other than x86.
