# VARKA-396: wrong answers from C2-compiled kernels on AVX2

## 0. Why this row exists

`m8/PLAN.md` row 396: `VarkaIrFuzzSuite`'s compiled-history test (row 274a) gives wrong answers on
GitHub-hosted runners - three of four JVMs on 19 October 2026, and every night from 20 to 24
October, one to three JVMs a night. Every failure is in a C2-compiled history (`released`,
`warm-same`, `recompiled`, `quiet-guard`, `quiet-remainder`); none in the interpreter. The row
asks for the cause, a fix or a guard, and a regression test per case.

## 1. Findings (24 October 2026)

**The runner is AVX2-only.** The fuzz workflow now logs the CPU: AMD EPYC 7763 (Zen 3), `avx2`,
no AVX-512, `UseAVX=2`, `MaxVectorSize=32`, Zulu 25.0.4+7 and 25.0.5+8. The laptop (Zen 5,
AVX-512) had replayed every case without failing: its JVM always runs `UseAVX=3`.

**Reproduced on the laptop by the instruction set.** Replaying the 24 October long-lane case
(seed 202610241, iteration 10, `warm-same`) under OpenJDK 25.0.4.1:

| JVM flags | Result |
| :--- | :--- |
| none (AVX-512 instructions, 512-bit vectors) | passes |
| `-XX:MaxVectorSize=32` (AVX-512 instructions, 256-bit vectors) | passes |
| `-XX:UseAVX=2` | fails |
| `-XX:UseAVX=2 -XX:MaxVectorSize=32` (the runner's) | fails |

So the defect follows the AVX2 instruction set, not the vector width. Under the runner's flags
five of six recent failures reproduce: 202610240/7 (int lane, `released`), 202610241/10 (long,
`warm-same`), 202610243/4 (int, `warm-same`, a selection mismatch), 202610231/1 (long,
`recompiled`), 202610221/8 (long, `quiet-guard`), 202610222/1 (long, `warm-same`); 202610190/17
(19 October, `quiet-remainder`) does not, on today's master. A fastdebug build of JDK 25 GA
(`jdk-25-ga`) fails the same case with its warm-up given two minutes to settle, and no HotSpot
assertion fires: the defect is in JDK 25 since GA, not a regression of an update.

**The shrunk case.** `if(c0 > c1, c0, try_add(c1, c2))` over longs, the masked body, seven rows
(one lane group of four and a three-row masked epilogue), with eleven options off; unstable (it
does not fail the same way three times). Its masked loop, read with `javap`: three loads, `compare
(GT)` to `toLong`, the known-true word `m & v0 & v1`, the add and its overflow mask
`((s ^ c1) & (s ^ c2)) < 0` to `toLong`, `VectorMask.fromLong(kT)` and `blend`, the store and the
validity word.

**What it depends on.** Each switch below, alone, makes the replay pass (one run each):

| Switch | Replay |
| :--- | :--- |
| `-XX:DisableIntrinsic=_VectorCompare`, `_VectorMaskOp` (`toLong`), `_VectorFromBitsCoerced` (`fromLong`), `_VectorBlend`, `_VectorLoadOp` | passes |
| `_VectorLoadMaskedOp`, `_VectorStoreMaskedOp`, `_VectorBinaryOp` | fails |
| `-XX:-DoEscapeAnalysis`, `-XX:-EliminateAllocations`, `-XX:-IncrementalInline` | passes |
| `-XX:-UseLoopPredicate`, `-XX:-UseProfiledLoopPredicate`, `-XX:LoopUnrollLimit=0`, `-XX:-UseSuperWord`, `-XX:-PartialPeelLoop`, `-XX:-SplitIfBlocks` | fails |
| `-XX:PerMethodTrapLimit=0 -XX:PerBytecodeTrapLimit=0` | passes |

The intrinsics are the chain of every predicated select Varka emits: compare, mask to long, long
to mask, blend. The replay deoptimizes kernels' `loopMasked0` hundreds of times, mostly at
`profile_predicate` traps. Each passing switch changes the graph C2 builds, so the table does not
yet say whether a runtime deoptimization produces the wrong value or the compiled code is wrong
without one; the harness's per-case trap verdicts decide that (2.1).

**Not reproduced outside Varka yet.** Three standalone programs (`target/repro/standalone/` on the
investigation branch) do not fail under AVX2: the masked loop transcribed over native memory with
its epilogue; the same after the chain run over other species and element types; 48 generated
copies of the body, each deoptimized in its middle with vectors and masks live. A JBS search
(24 October) found no report that matches: JDK-8386154 is a wrong mask result on AVX-512 only.

## 2. The cause (24 October 2026)

**A C2 transform, wrong on targets without mask registers.** `VectorMask.fromLong(s, bits)` masks
its argument to the lane count in Java (`bits & (-1L >>> (64 - laneCount))`) before its intrinsic,
so `fromLong(s, m.toLong())` reaches C2 as `VectorLongToMask (AndL (VectorMaskToLong
(VectorStoreMask m)) 0xF)`. `VectorLongToMaskNode::Ideal` (`opto/vectornode.cpp`, JDK 25 GA and
`openjdk/jdk25u` master) folds that pattern into `VectorMaskCast`, and on a target whose masks are
vectors (AVX2) it first peels the `VectorStoreMask` off and casts the mask under it:

```cpp
if (dst_type->isa_vectmask() == nullptr) {
  if (src->Opcode() != Op_VectorStoreMask) return nullptr;
  src = src->in(1);                       // the compare's mask: 64-bit lanes of 0 / -1
}
...
return new VectorMaskCastNode(src, dst_type);   // dst_type: a vector of BOOLEANs
```

There `VectorLongToMask` yields a vector of canonical booleans (0 or 1 a byte), which the
`VectorLoadMask` after it widens with `0 - b`. The cast keeps the mask encoding instead, so a true
lane arrives as `0xFF`, the load mask makes it `1`, and `blendvpd`, which reads the sign bit,
takes it as false: every true lane of the select picks the ELSE value. A mask written to a
`boolean[]` shows the non-canonical byte: it prints `true` and is not equal to `true`.

**Why Varka meets it.** A masked body reads each column's validity word as `-1` when the column
has no nulls (`ldc -1` on the branch the warm-up takes; the other branch becomes an uncommon
trap), so the known-true word `kT = toLong(compare) & v0 & v1` folds to `toLong(compare)`, and
`blend(..., fromLong(kT))` is the round trip. Every predicated select over all-valid columns has
it. That is why traps forbidden, escape analysis or incremental inlining off (each changes what
folds) hid it, and why batches with nulls did not fail.

**Reproduced without Varka.** `Fold396.java` (a loop of `blend(fromLong(toLong(a > b)))` over
`LongVector.SPECIES_256`) and `Split396.java` (`toLong` alone and `fromLong` alone correct, the
round trip wrong) fail under `-XX:UseAVX=2` once C2 compiles them, on OpenJDK 25.0.4.1 and on a
fastdebug JDK 25 GA build, and pass under AVX-512. The emitted class driven in a bare loop over
the shrunk batch (`Bare396.java`) fails from call 22,407 of 300,000 under AVX2 and never under
AVX-512.

**The fix, tested.** In the fastdebug build, an `Identity` for `VectorLongToMaskNode` that returns
the `VectorStoreMask` itself where the target has no mask registers (both are boolean vectors of
the same length), with `Ideal`'s peeling branch disabled: the three programs pass under AVX2, the
shrunk Varka case passes three rounds of three where it failed three of three, and no assertion
fires. Of the six recent fuzz cases replayed in the fixed fastdebug build, 202610221/8
(`quiet-guard`) and 202610231/1 (`recompiled`) pass with every history reached; 202610241/10,
202610240/7 and 202610243/4 pass with the history each failed in unreached under fastdebug's
slower compiles, which proves nothing; 202610222/1 stopped at an unrelated debug-only assertion
in the AVX2 long `max` (`c2_MacroAssembler_x86.cpp:1096`, `greatest(x, x)` with both operands in
one register, whose result is right). OpenJDK mainline (`openjdk/jdk` master, read 24 October 2026) has rewritten the rule: it
casts the `VectorMaskToLong`'s input itself, which on AVX2 is the `VectorStoreMask`, so the cast
is boolean to boolean and correct. Which change rewrote it, and whether as a fix, is not yet
established; `jdk25u` still has the bug.

## 3. Next (decided 24 October 2026)

1. The six recent fuzz cases replayed with the fix on in the fastdebug build: done, section 2.
2. **Reported to OpenJDK** by the owner on 24 October 2026 through bugreport.java.com, reference
   `efdbbe29-dfaf-419b-a3c2-084dfb421bc5` (the text is `results/VARKA-396/bugreport.md`); the JBS id follows
   when triage posts it.
3. **Row 491**, the owner's choice of guard: the emitter stops writing the round trip on targets
   without mask registers, so Varka is correct on JDK 25 whatever the host. It ships an AND of
   every word `fromLong` reads with an all-ones C2 cannot fold, which folds away on AVX-512 hosts
   (`VARKA-491.md` 2 and 6; 25 October 2026), on the same branch as this row.
4. **`m10/SCOPE.md` item 114**: verify JDK 27 under AVX2, support it at run time and lift the guard
   there if it passes, ask for a 25u backport (the owner's to file), and move the base at JDK 29.
5. The investigation's report: `results/VARKA-396-report.md`, with the reproducers and the
   HotSpot fix in `results/VARKA-396/`.

## 4. Done when

As row 396 says: the cases reproduced and their cause named; each fixed, or kept from the
emitter with the JDK bug recorded; each shrunk case a regression test under the history that
failed; a runner fuzz night passing the compiled test.
