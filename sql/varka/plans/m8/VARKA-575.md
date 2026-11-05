# VARKA-575: a BYTE column read at the long width at two long lanes

Row 575 of `PLAN.md`. Found on 5 November 2026 by the first dispatch of row 505's axis.

## 0. What exists now (admission check)

1. **The decline is the load, not the store.** The three catalyst suites that failed under
   `isa=w128` in run 42018067671 were run again on the laptop with `-XX:MaxVectorSize=16`
   (5 November 2026) and fail the same way: `VarkaCastMatrixSuite`, `VarkaCoverageSuite` (three
   tests) and `VarkaTinyIntConformanceSuite`. The only forms that moved are
   `byte -> bigint` and `byte -> double` in all three modes, and each says `a BYTE column has no
   load at 2 long lanes`, the refusal of `VarkaKernelPlan.longNarrowRefusal`. No test names the
   narrow store, so `VARKA-332.md`'s store gap at two lanes is a separate matter.
2. **Why the load has no form.** A kernel driven at the long lane holds its narrow values at the
   int lane, in the int species of the long species' width, low half. At two long lanes that
   species has four int lanes, so a byte column needs a four-lane byte load, a 32-bit species,
   which the Vector API does not have. `Lane.widenLanes` names the next width up, an eight-lane
   64-bit load, and an int-driven kernel computes the groups that load spans in one iteration
   (`narrowGroups`, row 479's part form). A long kernel computes one group an iteration, so the
   refusal says the part form "is not built at the long width". A `SMALLINT` column is not
   affected: the four-lane short species exists.
3. **A row-at-a-time load exists for the epilogue.** `VarkaVectorWalk.emitNarrowRowsLoad` builds
   the int vector lane by lane with `VarkaVectorSupport.byteAt` and `withLane`, under the
   remainder's bounds; a long kernel's epilogue already reads its narrow rows so.
4. **Only a 128-bit host meets it.** x86 at 128 bits is no host that prefers it (the 2 November 2026
   review of row 332), and row 503 already hands a two-lane x86 host's long conditions to the row
   path. Arm NEON is the host this serves.
5. **No other row claims it.** Row 479 is the part form for kernels driven at the int lane;
   `lanesOverride=4` has no skip line for these tests.

## 1. Goal and non-goal

Goal: `CAST(y1 AS BIGINT)`, `greatest(y1, l)`, `CAST(y1 AS DOUBLE)` and `CAST(l AS TINYINT)` fuse
at two long lanes, the 14 tests listed under `isa=w128` and `isa=arm` pass without their skip
lines, and the committed generated files agree on the 256-bit and the 128-bit host.

Not in this row: the part form at the long width in general, the narrow store at two lanes, or
any change to a shape that fuses today.

## 2. Design

1. **The loop body takes the row-at-a-time load** where the byte species of twice the long lanes
   does not exist: `lanes` calls of `byteAt` and `withLane`, with no remainder check, since the
   loop stops while a whole group fits. Only shapes that decline today change, so no committed
   byte of a kernel at 256 or 512 bits moves.
2. **The refusal narrows** to the widths where neither form exists, and says so.
3. **The measurement decides before the decline is lifted** (row 503's rule: no fused shape slower
   than the row path). The form is built on a branch and timed against the row path on an Arm
   runner, `VarkaIntegralCastBaselineBenchmark` over `CAST(y AS BIGINT)` with and without nulls, in
   pair mode. If it loses, the form is not admitted on that host, as row 503 does for filters.

## 3. Where it is measured

On GitHub's Arm runner, `ubuntu-24.04-arm`, a Neoverse N2 (`dev/varka_canary/` has its census).
`benchmark.yml` runs its benchmark jobs on `ubuntu-latest`; step 1 gives it a `runner` input, so
that a dispatch can name the Arm runner, with `expected-cpu` pinned to its CPU model (the same
mechanism that pins the EPYC 7763 for x86). The x86 pin and its rule are unchanged.

## 4. Order

1. `benchmark.yml`'s `runner` input and the Arm pin, proven by one dispatch on the Arm runner
   (needs the owner's word).
2. The form on a branch, and its measurement on Arm.
3. If it wins: the compiler's admission, the regenerated files, the 14 tests' skip lines deleted,
   one option-matrix dispatch with `isa=w128` and `isa=arm` green.

## 5. Predictions, registered before the build

- P1: `CAST(y AS BIGINT)` at two long lanes, no nulls, is at least as fast as the row path on the
  Arm runner. (**[I]**: no measurement of any byte kernel at two long lanes exists.)
- P2: no committed byte of `emitted_bytes.json` moves, since only declining shapes change.
- P3: the four forms and the 14 tests are the whole difference between the generated files at
  128 and at 256 bits. The local run of section 0 supports it for the cast matrix and the coverage
  files.

## 6. Done when

The four forms fuse at two long lanes where the measurement admits them, the 14 skip lines for
this row are deleted with their tests passing under `isa=w128` and `isa=arm`, and a dispatch of the
option matrix shows both green. If the measurement says no, the row records it and the lines stay,
with the reason now the measurement.
