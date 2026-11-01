# VARKA-479: The part-form load, one load and whole stores an iteration

## 0. What exists now (admission check)

From master at `b9c587374b6`. Paths are under
`sql/catalyst/src/main/java/.../codegen/varka/` unless they say otherwise.

1. **When the part form happens.** `VarkaKernelPlan.narrowGroups` (`VarkaKernelPlan.java:
   389-401`) gives a kernel driven at the int lane the widest part count of any narrow value it
   reads: `Lane.widenLanes(lanes, narrow) / lanes`. `widenLanes` (`Lane.java:398-408`) takes the
   narrowest narrow species of at least `lanes` lanes. A byte species is at least 64 bits (8
   lanes) and a short species at least 64 bits (4 lanes). So:

   | int lanes | `TINYINT` groups | `SMALLINT` groups |
   | --: | --: | --: |
   | 16, 8 | 1 | 1 |
   | 4 (128-bit hosts, `MaxVectorSize=16`) | 2 | 1 |
   | 2 (`lanesOverride=2` only) | 4 | 2 |

   Since row 478, `narrowLanes` is true by default and an all-narrow kernel drives at its own
   lane, so it never has parts. The part form is the default only for a kernel that mixes a
   narrow value with an int one, on a 128-bit host (Apple M-series, Neoverse, or x86 under
   `MaxVectorSize=16`). Under `narrowLanes=false` (the WIDENED arm, run in the option matrix) it
   applies to every narrow kernel there.
2. **The loop already stops on a whole iteration.** `emitSpecies` rounds `loopBound` down to a
   multiple of `groups * lanes` (`VarkaBodyEmitter.java:568-577`). The epilogue runs the rest one
   group per pass (`emitMixedEpilogue`, entered at `:856`). That part of the row's fix, "the loop
   stops one load early ... the epilogue takes the rest", is built.
3. **Each group reloads the iteration's whole load.** The loop body (`:789-812`) emits
   `emitLaneGroup` once per group with `s.rowGroup = g` and advances `i` by `lanes` after each.
   `emitNarrowLoad` (`VarkaVectorWalk.java:684-718`) loads `groups * lanes` narrow lanes from
   `i - rowGroup * lanes`, the iteration's first row, and converts part `rowGroup`. So at four int
   lanes a byte column's eight-lane load is issued twice an iteration, once per group. Whether C2
   merges the two loads is not recorded.
4. **Each group's narrow store is masked.** `emitNarrowTypeStore` (`VarkaBodyEmitter.java:
   1058-1097`) stores a narrow root at `widenLanes(stored)` lanes. Where that species is wider
   than the int group, the store is always masked by `indexInRange(0, lanes)` (`:1084-1093`),
   because the last group has no next group to overwrite its tail. With no predicated store
   (no AVX-512BW, no SVE), C2 lowers `intoMemorySegment(..., mask)` out of line, per lane:
   `VARKA-329.md` 9.8 findings 987-1 and 987-4.
5. **Nothing prices the groups.** `VarkaEmitBudget.weightOf` (`VarkaEmitBudget.java:603`) weighs a
   node once. The budget and the emit cost model (`VarkaEmitCost`, `VarkaEmitCostTable`) never
   read `narrowGroups`, so a body that runs twice or four times an iteration is grouped as if it
   ran once (finding 987-5).
6. **The record to rerun exists.** `results/VARKA-329-partload.txt` measured the part-form load
   at 128 bits on the laptop (23 October 2026, WIDENED against NATIVE, four shapes,
   `VarkaNarrowEmitBenchmark`). `VarkaNarrowEmitBenchmark` has EPYC 7763 runner results at 256
   and 128 bits (`sql/catalyst/benchmarks/VarkaNarrowEmitBenchmark-jdk25-runner-epyc7763-*`).
7. **The suites.** `VarkaEmitterNarrowSuite` and `VarkaEmitterNarrowArithSuite` run both arms.
   `lanesOverride` (`VarkaEmitOptions.java:219`) selects four or two int lanes on any host.

### What differs from the row text

- The loop bound and the group-at-a-time epilogue are built (item 2). What is left is the load
  issued once per group and the masked store per group (items 3 and 4), and the pricing (item 5).
- Since row 478 the default emits the part form only for mixed narrow-and-int kernels on 128-bit
  hosts (item 1). All-narrow kernels reach it only under `narrowLanes=false`. That matters for
  which benchmark cells decide the change (section 4).

## 1. Goal and non-goal

**Goal.** In a kernel driven at the int lane whose narrow species spans more than one group, the
main loop issues each narrow column's load once an iteration and each narrow root's store once,
whole and unmasked. The grouping weights price the body's repetition.

**Non-goal.** No change at eight or more int lanes, so no emitted byte moves on a 256-bit or
512-bit host: `emitted_bytes.json`'s default columns stay identical, and only its 128-bit and
`lanesOverride` arms move. No change to the NATIVE arm. No change to the epilogue's one-group
passes.

## 2. Design

### 2.1 Load once, part per group

The iteration's loads move out of the group loop. Before the first group, each narrow column the
body reads is loaded into a local, once, at `i` (the iteration's first row). Each group converts
its own part from that local: `convertShape(B2I, intSpecies, rowGroup)`. `emitNarrowLoad` gains a
"from the iteration's local" path when `s.epilogue` is false and `groups > 1`. Its current path
stays for the epilogue and for one group. The slots come from `Slots`, one reference local per
narrow input, allocated when `narrowGroups() > 1`.

### 2.2 Combine, then store whole

Each group's narrow root value goes to a per-root local, not to memory. After the last group, the
parts are combined into one narrow vector of `groups * lanes` lanes and stored with the unmasked
`intoMemorySegment`. This is safe because the loop bound guarantees a whole iteration fits
(item 2). The combine is one `convertShape(I2B, narrowSpecies, part)` per group, followed by a
`blend` (or `unslice`) into an accumulator. Which of the two C2 lowers without a call on NEON and
on AVX2 is the build's first question, answered with `dev/varka_emit.sh --asm` under
`-XX:MaxVectorSize=16` (**[I]**: the plan did not run it).

### 2.3 Pricing the repetition

`VarkaEmitBudget` multiplies a body's weight by `narrowGroups` where it compares against
`GROUP_BUDGET`, and `VarkaEmitCost` carries `narrowGroups` as a feature with the cost model's next
refit (row 352's schedule, as rows 431 and 432 are). Until that refit, the budget's multiply is
the guard, and the model's prediction is not used for a kernel with `narrowGroups > 1`. Its
current behaviour is unchanged: the prediction is not consulted for these kernels today
(**[I]**: the plan did not trace `fitsBudgets`' call site for a widened kernel).

## 3. Tests and proofs

1. An op-count test in `VarkaEmitterNarrowSuite` at `lanesOverride=4`: for `CAST(y1 AS INT) + i`
   with `y1` also output, the dense loop method has exactly one `ByteVector.fromMemorySegment`
   per narrow column and no masked `intoMemorySegment`. At `lanesOverride=2` the same holds with
   four groups. The count comes from `dev/varka_emit.sh --table --variant lanesOverride=4`.
2. Both narrow suites pass at `lanesOverride=4` and `2`, under both `narrowLanes` arms, against
   the reference evaluator, with batch lengths that leave 0, 1 and `groups * lanes - 1` rows for
   the epilogue.
3. `VarkaIrFuzzSuite` at `lanesOverride=4`, by the option matrix's existing configuration.
4. `emitted_bytes.json`: the default columns unchanged; the 128-bit and `lanesOverride` arms move
   only for shapes with a narrow input or root, and the diff is read shape by shape.
5. A budget test: a mixed narrow kernel whose weight fits `GROUP_BUDGET` once but not twice is
   split at four int lanes and not at eight.

## 4. Predictions, registered before the build

- P1: at 128 bits on the EPYC 7763 runner (AVX2, no predicated byte store), the mixed-shape
  cells of `VarkaNarrowEmitBenchmark` get faster under the WIDENED arm. That is where the masked
  store was out of line. The direction is registered, not the size (**[I]**).
- P2: on the laptop (AVX-512BW, predicated stores), the same cells move less than on the runner,
  possibly within the band. The masked store there is one instruction.
- P3: the eight-lane and wider cells do not move (no byte change).
- P4: the part-form record (`results/VARKA-329-partload.txt`) rerun on the laptop beside its 23
  October numbers. `VarkaNarrowEmitBenchmark`'s 128-bit runner file regenerated on the EPYC 7763
  runners, compared by `dev/varka_bench_diff.py`.

## 5. Order, files and size

Three commits on one branch, each green:

1. the budget multiply and its test (2.3), which moves no byte at eight or more lanes;
2. load once (2.1), the op-count test's load half, the bytes oracle's 128-bit arms regenerated;
3. store whole (2.2), the op-count test's store half, the oracle regenerated, the benchmark pair.

| File | Change |
|---|---|
| `VarkaBodyEmitter.java` | the iteration's loads and combined stores around the group loop |
| `VarkaVectorWalk.java` | `emitNarrowLoad` from the iteration's local |
| `Slots.java` | the narrow input and root locals |
| `VarkaEmitBudget.java` | `narrowGroups` in the group weight |
| `VarkaEmitterNarrowSuite.java`, `VarkaEmitterNarrowArithSuite.java` | the op counts, the 2- and 4-lane batches |
| `sql/varka/emitted_bytes.json` | the 128-bit and `lanesOverride` arms |
| `results/VARKA-329-partload.txt` | the rerun, beside the old numbers |
| `sql/catalyst/benchmarks/VarkaNarrowEmitBenchmark-jdk25-runner-epyc7763-128bit-results.txt` | the runner pair |

Size: medium, as the row says.

## 6. Out of this row

- The cost model's refit with `narrowGroups` as a feature: row 352's refit.
- A short column at four int lanes, which has one group and no part form.
- The epilogue's one-group passes, which stay masked by design.
- Row 476's warm-up measurement of the mixed shape. If 2.2's combine adds Vector API calls the
  warm-up does not make, the reach gate fails and the warm-up gains them here.

## 7. Questions for the owner

1. **Ship as the default at 128 bits, or behind an option first?** The house rule puts a new form
   behind a `VarkaEmitOptions` switch, with the old one as a live arm, and flips the default on
   the measurement. Recommended: behind `narrowPartsOnce`, default flipped in commit 3 if P1
   holds on the runner. That leaves the old form in the matrix as the losing arm.
2. **The decisive host.** On the laptop the masked store is cheap (P2), so the decision rests on
   the EPYC 7763 runner at 128 bits and, if the owner wants it, an Arm runner. Recommended: the
   EPYC runner decides, and Arm is recorded if row 505's Arm bin exists by then.

## 8. Done when

- a 128-bit kernel issues one narrow load per narrow column and no masked store per iteration of
  its main loop, pinned by the op-count test at four and two int lanes;
- the grouping weights price `narrowGroups`;
- the narrow suites pass at four and two int lanes under both arms;
- the part-form record is rerun beside its old numbers, and the runner's 128-bit file is
  regenerated and read against its band.

## 9. Open points for the lead

1. The row text predates row 478's default flip, which narrows the part form to mixed kernels on
   128-bit hosts (0.1). Is that still worth a medium row in m8, whose goal is the types? It keeps
   NEON and AVX2-only hosts from paying a per-lane store, which is a correctness-of-speed concern
   rather than a type one.
2. 2.2's combine (`blend` or `unslice`) is unmeasured. If neither lowers to straight-line code on
   AVX2 at 128 bits, the store half is withdrawn and the row closes on the load half and the
   pricing, with the reason recorded.

## 10. Outcome
