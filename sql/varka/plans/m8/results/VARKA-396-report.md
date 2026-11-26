# VARKA-396 investigation report: wrong answers from C2-compiled kernels on AVX2

*24 October 2026. Row 396 of `m8/PLAN.md`; the plan and its running record are `m8/VARKA-396.md`.*

## Summary

Varka's nightly fuzz has failed on GitHub-hosted runners every night from 20 to 24 October 2026,
and once on 19 October: the IR fuzzer's compiled-history test found fused kernels whose C2-compiled
code gives a different answer from the reference evaluator. The cause is a **bug in the HotSpot C2
compiler of JDK 25**, not in Varka's emitted code:

- On x86 CPUs without AVX-512 mask registers (AVX2), C2 miscompiles the Vector API round trip
  `VectorMask.fromLong(species, mask.toLong())`. A rule in `VectorLongToMaskNode::Ideal`
  (`src/hotspot/share/opto/vectornode.cpp`) replaces the round trip with a cast of the original
  mask, but on these targets the cast keeps the mask encoding (`0`/`-1` a lane) where canonical
  booleans (`0`/`1` a byte) are required. A true lane arrives as `0xFF`, becomes `1` when the mask
  is widened back, and `blendvpd`, which reads the sign bit, takes it as false.
- Varka meets it in every predicated select over columns with no nulls: the validity word of such
  a column is the constant `-1`, so the kernel's known-true word `toLong(compare) & -1 & -1`
  folds to the bare round trip once C2 has turned the null branch into an uncommon trap.
- The symptom is a select that takes its ELSE branch on every row whose condition is true.

A 40-line JDK-only program reproduces it on OpenJDK 25.0.4.1 and on a fastdebug build of JDK 25
GA under `-XX:UseAVX=2`, and never under AVX-512. A two-part HotSpot patch fixes the program and
the Varka case in a fastdebug build. `openjdk/jdk25u` still carries the bug; OpenJDK mainline has
rewritten the rule in a way that does not (JDK-8370863, in JDK 27, by its fix version; not yet run
here).

## 1. The failures

| Night | Runner JDK | Failing JVMs | Example |
| :--- | :--- | :--- | :--- |
| 19 Oct | Zulu 25.0.4+7 | 3 of 4 | long lane, `quiet-remainder` |
| 20 Oct | Zulu 25.0.4+7 | 2 of 4 | `released`, `quiet-guard` |
| 21 Oct | Zulu 25.0.4+7 | 2 of 4 | `released` x2 |
| 22 Oct | Zulu 25.0.5+8 | 2 of 4 | `quiet-guard`, `warm-same` |
| 23 Oct | Zulu 25.0.5+8 | 1 of 4 | `recompiled`, flagged unstable |
| 24 Oct | Zulu 25.0.5+8 | 3 of 4 | `released`, `warm-same` x2 |

Every failure was in a C2-compiled batch history; none in the interpreter. The laptop (AMD Zen 5,
AVX-512) had replayed every case without a failure since 19 October.

## 2. How it was narrowed

**The runner's CPU.** The fuzz workflow's log, added by row 396, names the runner: AMD EPYC 7763
(Zen 3), `avx avx2`, no AVX-512, `UseAVX=2`, `MaxVectorSize=32`. The laptop's JVM always runs
`UseAVX=3`.

**Reproduced by the instruction set.** The 24 October long-lane case, replayed on the laptop:

| JVM flags | Result |
| :--- | :--- |
| default (AVX-512, 512-bit vectors) | passes |
| `-XX:MaxVectorSize=32` (AVX-512 instructions, 256-bit vectors) | passes |
| `-XX:UseAVX=2` | fails |
| `-XX:UseAVX=2 -XX:MaxVectorSize=32` (the runner's) | fails |

Under the runner's flags five of the six recent failures reproduced, in both the int and the long
lane. A fastdebug build of JDK 25 GA failed the same case with no HotSpot assertion firing: the
defect is in JDK 25 since GA.

**Bisection.** Each switch below, alone, made the replay pass or not:

| Switch | Replay |
| :--- | :--- |
| `DisableIntrinsic` of `_VectorCompare`, `_VectorMaskOp` (`toLong`), `_VectorFromBitsCoerced` (`fromLong`), `_VectorBlend`, `_VectorLoadOp` | passes |
| `DisableIntrinsic` of the masked load and store, the binary ops | fails |
| `-XX:-DoEscapeAnalysis`, `-XX:-EliminateAllocations`, `-XX:-IncrementalInline` | passes |
| loop predication, unrolling, SuperWord, partial peeling, split-if off | fails |
| uncommon traps forbidden (`PerMethodTrapLimit=0`, `PerBytecodeTrapLimit=0`) | passes |

The intrinsics named the chain - compare, mask to long, long to mask, blend - and the C2 switches
suggested a deoptimization. That hypothesis did not survive: a fastdebug build instrumented to log
every vector and mask payload the deoptimizer rebuilt in a Varka kernel showed that the failing
kernels rebuilt none. Each passing switch changes what C2 *compiles* (what folds), not what happens
at a trap.

**The wrong value.** The shrunk case, `if(c0 > c1, c0, try_add(c1, c2))` over longs, run alone
through Varka's compiled check, failed six rounds of six, and the raw message gave the value:
row 0 wanted `c0` (`4460076517051`, since `c0 > c1`) and got `c1 + c2` (`-32177637661844`) - the
ELSE branch on a row whose condition is true. The emitted class driven in a bare loop over the
same batch was right for 22,406 calls and wrong on every call from 22,407 (C2's compile) under
AVX2, and never under AVX-512. The failing body was the masked one over a batch with no nulls,
where the validity words are the constant `-1`.

**The JDK-only reproducer.** With that, a plain Java program reproduced it:

```java
static final VectorSpecies<Long> S = LongVector.SPECIES_256;

static void roundTrip(long[] a, long[] b, long[] c, long[] out) {
  for (int i = 0; i < S.loopBound(a.length); i += S.length()) {
    LongVector va = LongVector.fromArray(S, a, i);
    LongVector vb = LongVector.fromArray(S, b, i);
    long m = va.compare(VectorOperators.GT, vb).toLong();
    LongVector s = vb.add(LongVector.fromArray(S, c, i));
    s.blend(va, VectorMask.fromLong(S, m)).intoArray(out, i);
  }
}
```

Under `-XX:UseAVX=2` it goes wrong from about call 18,000 (when C2 compiles it); under AVX-512 it
is right in 200,000 calls. Taken apart: `toLong` alone is right, `fromLong` alone is right, the
round trip is wrong, and the mask it produces, stored to a `boolean[]`, holds a value that prints
`true` and is not equal to `true` - a non-canonical byte.

## 3. The cause

`VectorMask.fromLong(s, bits)` masks its argument to the lane count in Java before its intrinsic,
so the round trip reaches C2 as

```
VectorLongToMask (AndL (VectorMaskToLong (VectorStoreMask m)) 0xF)
```

and `VectorLongToMaskNode::Ideal` (JDK 25 GA and `openjdk/jdk25u` master) folds it:

```cpp
Node* src = in(1)->in(1)->in(1);
if (dst_type->isa_vectmask() == nullptr) {     // no mask registers: AVX2
  if (src->Opcode() != Op_VectorStoreMask) {
    return nullptr;
  }
  src = src->in(1);                            // the mask under it: 64-bit lanes of 0 / -1
}
...
return new VectorMaskCastNode(src, dst_type);  // dst_type: a vector of BOOLEANs
```

On a target without mask registers `VectorLongToMask` yields a vector of canonical booleans, which
the `VectorLoadMask` after it widens with `0 - b`. The cast keeps the mask encoding instead, so a
true lane is `0xFF`, the load makes it `1`, and the blend reads it as false. On AVX-512 the rule
takes its mask-register branch, which is correct.

## 4. The fix, tested

In the local JDK 25 fastdebug build, behind an environment switch:

- `VectorLongToMaskNode::Identity` returns the `VectorStoreMask` itself where the target has no
  mask registers and the two are boolean vectors of the same length (an existing node, so an
  `Identity`, not an `Ideal`, which C2's verification requires);
- `Ideal`'s peeling branch is disabled.

| Program, fastdebug JDK 25, `-XX:UseAVX=2` | Without | With the fix |
| :--- | :--- | :--- |
| `Fold396` (Varka's shape, validity `-1` on the taken branch) | wrong from call 52,921 | right in 200,000 calls |
| `Fold396` (the bare round trip) | wrong from call 38,936 | right in 200,000 calls |
| `Split396` (the round trip's mask read directly) | wrong | right |
| The shrunk Varka case through the compiled check | 3 of 3 rounds fail | 0 of 3 fail |

No HotSpot assertion fires with the fix. Of the six recent fuzz cases replayed in the fixed build,
two pass with the history they failed in reached (202610221/8 `quiet-guard`, 202610231/1
`recompiled`); three pass with that history unreached under fastdebug, which proves nothing; one
stopped at an unrelated debug-only assertion in the AVX2 long `max` over `greatest(x, x)`. The
patch is
`target/repro/jdk25-long-to-mask-fix.diff` on the investigation branch.

**Upstream.** A search of the JDK bug tracker found no report of this rule. `openjdk/jdk25u` master
has the JDK 25 code. OpenJDK mainline has rewritten the rule: it casts the `VectorMaskToLong`'s
input itself, which on AVX2 is the `VectorStoreMask`, so the cast is boolean to boolean and
correct. Varka's scope catalogue (`m10/SCOPE.md` item 56) lists the mask-cast change, JDK-8370863,
with fix version 27. Whether JDK 27 passes the reproducer has not been run here.

## 5. What it means for Varka

- **Released behaviour is affected**, not only the nightly: on an AVX2-only x86 host, a fused
  predicated select over all-valid columns can return wrong answers once C2 compiles its kernel.
  AVX-512 hosts and the interpreter are not affected.
- The emitter cannot simply avoid the operation: `fromLong` of a known-true word is how every
  select, pick and guard of the masked body is lowered.
- A JVM flag is not a fix: every switch that hid the bug in the replay is JVM-wide and costly
  (escape analysis off undoes the Vector API's box elimination).

## 6. Decisions and next steps

Decided by the owner, 24 October 2026:

1. **A Varka-side guard (row 491):** emit the selects without the `toLong` to `fromLong` round trip
   on targets without mask registers - a mask built by broadcast, AND with the lane bits and a
   compare against zero, three vector operations, close to what `fromLong` lowers to on AVX2
   anyway - after a review of every `fromLong` the emitter writes. The emitter's `useAVX` option
   is not the host's level by default, on purpose (shape hashes and the committed emitted bytes
   describe no one machine), so the row decides between the safe form everywhere, measured on
   AVX-512, and a host-dependent choice for this one lowering. Row 491 measured that form and
   two others; it ships `fromLong` of the word XORed with a zero C2 cannot fold, on every host
   (`VARKA-491.md` 2 and 6).
2. **The JDK plan (next milestone):** run the reproducer and the Varka case on JDK 27 under AVX2;
   if 27 is fixed, support it at run time (Varka keeps building for 25; release-25 class files
   run on 27) and lift the guard there; on JDK 25 keep the guard and ask OpenJDK for a 25u
   backport, citing JDK-8370863 and the reproducer, if the owner files it; make JDK 29, the next
   long-term release, the migration target.

Reported to OpenJDK by the owner on 24 October 2026 through bugreport.java.com, reference
`efdbbe29-dfaf-419b-a3c2-084dfb421bc5` (`VARKA-396/bugreport.md` holds the text); the JBS id follows when triage
posts it. Open: the regression tests the row's
done-when asks for, one per shrunk case under the history that failed; a runner fuzz night passing
the compiled test once the guard lands.

## Appendix: the files

In `sql/varka/plans/m8/results/VARKA-396/`, beside this report:

- `Fold396.java`, `Split396.java` - the JDK-only reproducers
  (`java --add-modules jdk.incubator.vector -XX:UseAVX=2 Fold396`).
- `Bare396.java` - an emitted kernel class driven in a bare loop.
- `jdk25-long-to-mask-fix.diff` - the HotSpot fix tested in the fastdebug build (gated on
  `VARKA_FIX_LONG_TO_MASK` for the A/B; a real patch drops the gate).

On the investigation branch's `target/repro/` only: the driver that runs the shrunk case through
Varka's compiled check, the deoptimization payload logging used and ruled out, and the standalone
attempts that did not reproduce (the kernel transcribed with random validity, with polluted
profiles, and 48 copies deoptimized mid-body), kept as the record of what is not the cause.
