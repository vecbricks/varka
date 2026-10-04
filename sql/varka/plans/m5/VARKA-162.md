# VARKA-162: ship the half-species narrowed store

*Scoped 21 September 2026 (milestone 5 section 2.98, row 162), from VARKA-156's measurement; planned the same day.*

## 1. The question

VARKA-156 found what the narrowed store costs at two 64-bit lanes and why: the
masked store's in-loop bounds branch keeps C2 from unrolling the loop, and a
half-width species - the int species with the long lane's own count, so the
converted vector is exactly the group's values - stores plainly and gets the
wide store's loop shape back. Measured (`VARKA-156.md` section 6), the
half-species arm is never worse than the masked form and is the fastest of the
three stores past L3 at the wide widths:

| width | rows | shape | wide store | masked (shipped) | half species |
|---|---|---|---|---|---|
| 128 | 262144 | `second` | 763.5 | 691.6 | 738.7 |
| 128 | 262144 | three fields | 762.0 | 623.1 | 689.3 |
| 512 | 8388608 | `hour` | 2529.8 | 3112.1 | 3635.7 |
| 512 | 8388608 | three fields | 1019.9 | 1078.4 | 1247.1 |

The masked form is still what ships, for one structural reason: the
half-species form exists only where the lane count is baked
(`VarkaEmitOptions.lanesOverride` non-zero), because `Lane.speciesField`
names `SPECIES_64` and its siblings for a count and `SPECIES_PREFERRED` for
none, and the half of the preferred species has no named constant. Production
emits with the defaults, count zero, so production has never run the form
that measured best. The question is how the general case is built without
paying the cost the masked form was chosen to avoid, and what the 128-bit
residue on the three-field shape is.

## 2. The change

1. **The general case: a half-of-preferred species constant.** The engine's
   `VarkaVectorSupport` (a plain Java class, loaded once per JVM) gets a
   `public static final VectorSpecies<Integer> INT_HALF_PREFERRED`, defined as
   the int species whose bit size is half of `LongVector.SPECIES_PREFERRED`'s:
   `SPECIES_256` on a 512-bit JVM, `SPECIES_128` at 256 bits, `SPECIES_64` at
   128. A static final on an initialised class is a constant to C2, exactly as
   `IntVector.SPECIES_PREFERRED` is, so the intrinsics fold it the same way.
   `emitNarrowStore` takes the half-species path at count zero through that
   field, and at a baked count through the named constant as it does now. The
   emitted class itself carries no static state, as today.
2. **The default flips.** `narrowHalfSpecies` becomes `true` in
   `VarkaEmitOptions.DEFAULTS`; the masked form stays as the reference
   variant on `FloorMod7`'s precedent, selectable for the A/B and pinned by
   the differential. The canonical rendering treats the new default as empty,
   so production hashes for shapes without a `NarrowLane` root do not move,
   and the narrowed shapes get the class names they would have had.
3. **The second-species risk, measured rather than assumed.** A second
   `IntVector` species in one JVM can make the Vector API's shared templates
   inline bimorphically and box the int kernels that share them
   (`VARKA-28.md` 2.2). The half species is a second int species by
   construction. VARKA-156's regeneration ran the int32 arms in the same JVM as
   the half-species arm and they did not box (`VARKA-156.md` section 6,
   prediction 3), which is one JVM and one benchmark. The canary here is
   wider: `VarkaEmitterParityBenchmark`'s and `VarkaThroughputBenchmark`'s
   int-lane rows regenerated after the flip, against their committed files,
   and the assembly gate's allocation self-test, which is what a boxed lane
   group shows up in.
4. **The 128-bit residue, read.** With the half species the three-field shape
   still reads 10% under the wide store at two lanes, and `second` 3%.
   `dev/varka_emit.sh "hour(t)" "minute(t)" "second(t)" --columns "t:time(6)"
   --asm --width=16` against the wide two-divide shape, as VARKA-156 did for
   `second`: per-group instruction counts, the unrolling factor, and whether
   the three half-vector stores per two-row group are the difference. Named
   or closed; a close is its own step and not assumed.
5. **The oracles.** `emitted_bytes.json` moves for every shape with a
   `NarrowLane` root at both baked widths and for no other shape; the census
   and the coverage docs regenerate; `VarkaTimeBenchmark` regenerates at all
   three widths so its "narrowed store (shipped)" rows read the new form and
   the "half species" arm becomes the reference masked one, renamed.

## 3. Predictions, registered before the run

1. **The shipped rows move to the half-species rows' rates**, within 3% at
   every width and row count in `VarkaTimeBenchmark`: the store is the same
   store the arm measured.
2. **Production sees it.** The `TIME` surface's `hour(t)`, `minute(t)` and
   `second(t)` at 512 bits past L3 - which is where the surface's 500000000
   rows sit - read above their committed four-arm rates (`hour(t)` 1041.4 M
   rows/s, `VARKA-105.md` section 6) by at least a tenth, since the
   benchmark's past-L3 rows read the half species 17% to 44% over the masked
   form at 512 bits.
3. **No int kernel boxes.** The parity and throughput benchmarks' int rows
   stay within their committed bands and the gate's allocation self-test
   reads zero bytes per row for the int shapes, in a JVM where both species
   have been used.
4. **The residue is the stores.** At 128 bits the three-field shape's loop
   has three half-vector stores per two-row group where the wide shape has
   three full ones, and the per-group cost is in address arithmetic and store
   issue rather than in the divides; the count of instructions per group
   between the two shapes differs by the stores' own instructions and not by
   an unrolling factor. If instead the half-species loop is not unrolled on
   this shape, the residue is loop shape again and prediction 4 has failed.
5. **`emitted_bytes.json` changes for exactly the narrowed shapes** - the
   three extracts, the three-field construction and VARKA-158's conversions
   with an int result - and for nothing else, at both baked widths.

## 4. Verification

The emitter suite's narrowed-store cases at count zero and at baked counts,
in both null modes and in the epilogue; the differential against the masked
reference variant; `VarkaLaneTypeSuite` and the fuzzer's reach set unchanged.
The oracles regenerated in producer order - census, coverage and docs, bytes -
and their readers run after. `VarkaTimeBenchmark` at three widths;
`VarkaEmitterParityBenchmark` and `VarkaThroughputBenchmark` for the canary;
the assembly gate. The `TIME` surface's fork arm alone at 105's setting for
prediction 2, not the four arms, since stock does not move.

## 5. Outcome

*Written after the runs.*

## 6. Explicitly out of this task

A widening store (VARKA-157) and VARKA-28's narrowing inside a tree use the
same species question and inherit the constant, but neither is built here. If
prediction 3 fails, the flip is reverted and the masked form stays, with the
boxing recorded as the price of one species per type, which is the outcome
VARKA-156's plan named for that case.
