# VARKA-491: selects without the long-to-mask round trip on AVX2

*Moved to milestone 9 on 23 November 2026 with row 491 (`SCOPE.md` item 138). A bare
`PLAN.md`, `results/` path or other `VARKA-<n>.md` below names milestone 8's, in `m8/`,
unless that plan moved too (`SCOPE.md` item 138).*

## 0. Why this row exists

Row 396 (`VARKA-396.md`, `results/VARKA-396-report.md`) found that JDK 25's C2 miscompiles
`VectorMask.fromLong(s, m.toLong())` on x86 without AVX-512 mask registers: `VectorLongToMaskNode
::Ideal` replaces the round trip with a cast that keeps the mask encoding where canonical booleans
are needed, and a select by the result takes its ELSE branch on every true lane. Varka's masked
body meets it wherever it turns a condition word back into a mask: the known-true word
`kT = toLong(compare) & v0 & v1` folds to `toLong(compare)` when the columns have no nulls (their
validity words are the constant `-1`). The owner chose to keep the emitter off the pattern
(24 October 2026) rather than decline the hosts; JDK 27's rewritten rule (`m9/SCOPE.md` item 114)
is the longer road.

## 1. The sites (reviewed 24 October 2026)

Every `VectorMask.fromLong` the emitter writes, and whether its argument can fold to a mask's
`toLong` of the same lane count:

| Site | Argument | Can match |
| :--- | :--- | :--- |
| `VarkaVectorWalk` `IfElse`, masked body | `kT` of the condition | yes: `toLong(compare) & -1 & -1` |
| `VarkaVectorWalk` `CondValue`, masked body | `kT` | yes |
| `VarkaVectorWalk` arm masks (`emitArmContext`), masked body | `kT`, or `kT ^ -1` for the ELSE arm | the THEN arm yes; the XOR is not the pattern but is not proven safe either |
| `VarkaVectorWalk` `emitGuardCollect` | a node's validity word | not by construction, but a word can be a condition's `kT` |
| `VarkaVectorWalk` `emitPick` | `~word` of an operand | as the ELSE arm |
| `VarkaVectorWalk` `emitToDrivingLane` | `toLong` of an int mask, to a long mask | no: the rule requires equal lane counts (8 against 4) |
| dense body: `BoolColumn`, `CondConst`, `IsNotNull` | a bitmap read from memory, or a constant | no |
| `VarkaBodyEmitter` guard accumulators | the constant 0 | no |
| hand-written kernels (`ChronoVectorOps`, `DateVectorOps`, `IntRangeOps`, `SelectionVectorOps`) | validity bits read from memory, or constants | no |

The dense body keeps each condition as a `VectorMask` (`condMask`) and blends by it, so it never
makes the round trip.

**Two cheaper forms were tried and fail.** Passing `kT | ~laneBits` (the bits `fromLong` drops
anyway) does not stop the fold under `-XX:UseAVX=2` - C2 removes the OR - and an addition would be
removed by `AndIL_sum_and_mask` by design. Both mix the word with constants, which C2 folds. What
it cannot fold is a value it does not know: a static field that is not final.

## 2. Design

**Every word the masked body hands `fromLong` is ANDed with an all-ones C2 cannot fold, behind a
guard that folds away where the miscompile cannot happen.** `VarkaVectorWalk.emitMaskOfWord` emits
`fromLong(species, word & (VarkaMaskGuard.ones | VarkaMaskGuard.OPEN))` at the first five sites of
the table (the picks' two conversions counted as one site); the rest keep `fromLong` of their
constant or bitmap. `VarkaMaskGuard.ones` is a public, non-final static `long`, all ones and never
written: HotSpot folds only final fields (and `@Stable` ones) into constants. `OPEN` is a
`static final long`, zero on x86 below AVX-512 (`UseAVX` under 3) and all ones elsewhere, which C2
does fold. Where it is zero, `ones | 0` is `ones`, and the input of `VectorLongToMask` is an
`AndL` with a value C2 does not know; the rule matches only an `AndL` of a `VectorMaskToLong` with
the all-lanes constant, so it cannot apply. C2 still bounds the word through the AND, so it needs
no zero-extension: on AVX2 the guard costs one `and` a site. Where `OPEN` is all ones, the guard
folds to `word` and the kernel compiles as base's. Elsewhere than x86 the rule cannot apply
either: C2 has `fromLong` on aarch64 only with SVE2 bitperm and on RISC-V only with RVV, both with
mask registers. An AVX-512 host without AVX512VL or AVX512BW (Knights Landing) types some masks
as vectors yet reports `UseAVX` 3; `m9/SCOPE.md` item 115 tests that case.

The guard goes on every word a `fromLong` reads, not only on those that can carry a `toLong`: the
bytes of a site then do not depend on its operands, which the emit cost model needs, and the
emitter needs no analysis of where a word came from. A word made with `toLong` that never becomes
a mask, as in a filter predicate, pays nothing.

The guard is a constant of the running JVM, not an emit option, so the bytes are the same on every
host: the committed byte and cost tables, and the shape hash, do not depend on the machine, which
matters because the fork's runners are a mix of AVX2 and AVX-512 hosts. `fromLongMasks` (reason
`MISCOMPILED`, never drawn by the fuzzer or the option matrix) emits the bare `fromLong` for the
A/B.

**Four forms were built and measured before this one, and lost** (section 6): the broadcast form
`broadcast(word).and(laneBits).compare(NE, 0)`, which never reaches `VectorLongToMask`; and, for
conditions, the comparison's own mask ANDed with `fromLong` of the operands' validity words, with
the broadcast form left for the words that carry a `toLong` anyway. Both cost through the emit cost
model as much as at their sites. The third XORed every word at its `fromLong` with an opaque zero,
with no guard, which cost AVX-512 hosts the XOR for nothing and AVX2 hosts a zero-extension as
well. The fourth XORed the guard into each word at its `toLong`, which a filter's comparisons pay
too.

## 3. Tests

1. **The shrunk cases of row 396 as regression tests**, in `VarkaAvx2SelectSuite`: each case's
   kernel through `VarkaCompiledCheck`, which must reach the history the case failed in (a case
   that does not reach it in three tries is cancelled, not passed); the suite runs in its own JVM
   under `-XX:UseAVX=2` (`@VarkaSuite(jvmFlags)`, which `dev/varka_matrix.sh` honours too). Under
   `fromLongMasks` the long cases of 24 and 19 October fail, as row 396's report records.
2. **No emitted kernel hands `fromLong` a word a `toLong` reaches**: `VarkaMaskOfWordSuite` runs an
   ASM data-flow pass over every coverage row's kernel, at the defaults and under each option
   matrix configuration, marking each long a `toLong` reaches and clearing the mark at an AND with
   `VarkaMaskGuard.ones | VarkaMaskGuard.OPEN`. `emitToDrivingLane`'s lane change, whose lane
   counts differ, is the one exception. Under `fromLongMasks` it finds the round trip, so it is
   not vacuous. The pass stays within one method. A test pins `OPEN` to zero below `UseAVX` 3 on
   x86 and all ones elsewhere, and `VarkaAvx2SelectSuite` checks it is zero in its AVX2 JVM.
3. The catalyst defaults gate, and a runner fuzz night (the nightly at seeds 202610190 to
   202610243) passing the compiled test, which row 396's done-when asks for.

`emitted_bytes.json`, the emit cost table and its audit, and the width audit are regenerated.

## 4. Predictions and costs

The emitter change is one helper at five sites. The regression suite and the byte check: half a
day. No change to what any kernel answers on AVX-512.

## 5. Done when

`m8/PLAN.md` row 491's done-when: the round trip's shape is not emitted on targets without mask
registers; each shrunk case of row 396 is a regression test passing under `-XX:UseAVX=2`; a runner
fuzz night passes the compiled test; the cost is measured on both instruction sets.

## 6. Outcome

**The broadcast form cost 15-46% on 16 laptop parity rows** (`VarkaEmitterParityBenchmark`, 5
rounds a side against the merge base `be2ec226e7d`, 24 October 2026), for two reasons the emitted
bytes showed. The `IfElse` blend kernels behind the "validity writes per body" rows grew by about
500 bytes. And the null-free calendar rows with `shareChronoPrefix` off, which hold no conversion at
all, went from two loop methods to four: about seven calls a site made the emit cost model's
call-site fit worse, its regenerated `SITES_MARGIN` rose from 0.206 to 0.286, and the planner
closed groups earlier. The base emitter with the regenerated table and base's margins emits base's
classes, byte for byte.

**The comparison-mask form brought the blend kernels to 2% over base but `SITES_MARGIN` to
0.439**: a site cost 0 or 2 calls by whether its operands are nullable, and 7 where it fell back to
the broadcast form, which the per-kind prices cannot see. A cost feature for the fallback brought
it to 0.347; the rest needed the emitter's analysis in the cost model.

**The opaque XOR restores the cost model**: `SITES_MARGIN` 0.206 and `BYTES_MARGIN` 0.156 against
base's 0.206 and 0.155, base's list of wide shapes that gain a loop method, and the calendar
kernels' classes the same size as base's, two loop methods each. The blend kernels are 114 bytes
over base. The shipped AND guard keeps that: `SITES_MARGIN` 0.206 and `BYTES_MARGIN` 0.157, and
every kernel of `emitted_bytes.json` has base's methods, so the planner splits nothing differently.

**On AVX-512 the opaque form cost 1-8% on the mixed-null blend rows** (laptop pair, 5 rounds a
side against `be2ec226e7d`, 25 October 2026). Of 239 rows none moved by 10% by both the median and
the best, and the 16 rows the broadcast form slowed are back within noise; but all twelve "validity
write(s) per body, mixed nulls" rows were slower by the median, from 1.3% to 7.6%, and the rounds
of the two widest, "1 validity write(s) per body, width-named helpers" and "4 validity write(s) per
body, one write per word", do not overlap with base's. Rows the change does not touch moved by up
to 3%, which is this pair's noise.

**The cost on AVX-512 is the XOR executing, which the guard removes.** The opaque form's twelve
"validity write(s) per body, mixed nulls" rows were all slower; in the same JVM the bare `fromLong`
ran 1-13% faster on the eight rows tried, in each of three rounds (25 October 2026). A twin XORing
with a literal `0L`, which C2 folds, ran as fast as the bare form, so the extra bytes cost nothing;
and the XOR timed again after the bare form was as slow, so the order did not either. The compiled
1-write loop showed the same inlining, the same two-vector unrolling and the load of `zero` lifted
out of the loop; what the XOR added on every vector was two instructions on the way from the
comparison to the blend, the `xor` and a `movzx`: the word's upper bits, provably zero after the
AND with a 16-bit validity load, are not once XORed with an unknown. In a body of about 25
instructions a vector, that is the 8% measured. Hence the guard: on AVX-512 it folds the XOR away.

**On AVX2 the AND costs about half what the XOR did, and the guard leaves filters alone.** Under
`-XX:UseAVX=2` on the laptop, three rounds with each form timed twice in one JVM (25 October 2026),
the 1- to 4-write blend kernels with mixed nulls ran 2.9-9.0% faster with the AND guard than with
the XOR at `fromLong`; the bare `fromLong`, wrong on AVX2 but a floor for the cost, was 0-8% faster
than the AND. Null-free, the forms were within noise. An earlier try XORed the guard into each word
at its `toLong` instead, which also dropped the zero-extension (4.1-5.6% over the XOR at
`fromLong` in its own rounds) but put the guard on every comparison, a filter's too: TPC-DS q3's
48 ranges, 96 comparisons, grew by about 770 bytes and no longer fitted one method, which
`VarkaSplitConditionFusionSuite` caught.

**The AND guard costs AVX-512 nothing measurable.** The laptop pair on it (5 rounds a side
against `be2ec226e7d`, 25 October 2026) has none of 239 rows slower by 10% by both the median and
the best, and 2 faster; the twelve mixed-null blend rows the opaque form slowed are from 1.3%
slower to 10.7% faster by the median, and the slowest rows, calendar and `dayofweek` kernels at 4
to 6%, hold no select. The runner pair (run 40175762601, AMD EPYC 9V45, AVX-512) agrees: none of
239 slower by 10% by both, 3 faster, the blend rows within 4.4% either way.

**A runner fuzz night passes the compiled test on AVX2.** The six nights that failed from 19 to 24
October, seed bases 20261019 to 20261024 with four JVMs each, ran on the branch on fork runners
(run 40176688855, 25 October 2026): every job on an AMD EPYC 7763 with `UseAVX=2` and
`MaxVectorSize=32`, and all 24 JVMs passed, the compiled-history test among them (seed 202610190,
for one, reached 18 int-lane and 17 long-lane cases over the compiled histories).
