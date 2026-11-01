# VARKA-476: The first narrow kernel of a fresh JVM, measured against the warm-up

## 0. What exists now (admission check)

From master at `b9c587374b6`. Paths are under `sql/catalyst/src/main/java/.../codegen/varka/` or
`sql/catalyst/src/test/.../codegen/varka/` unless they say otherwise.

1. **The narrow arms are in the template warm-up.** The row was filed when
   `VarkaVectorTemplateWarmup` warmed only the Int, Long and Double species. Row 329's review fix
   (#993, `baf5e7e40bc`, `VARKA-329.md` 9.8 finding 989-11) added them, because the reach gate
   failed without them. `Rounds.run` (`VarkaVectorTemplateWarmup.java:599-611`) now takes the
   preferred byte and short species and the species a widened kernel loads a narrow column at
   (`widenedSpecies`). There is one block per narrow lane at the preferred species, `bytes` and
   `shorts` (`:834`, `:870`): dense and masked loads and stores, compare, blend, min, max,
   broadcast, the `B2I`/`S2I` widening, `I2B`/`I2S` and the selection's mask calls. There is
   also one block per narrow lane at the widened species, `widenedBytes` and `widenedShorts`
   (`:923`, `:952`), which converts every part a lane group can take
   (`parts = w.length() / is.length()`). Above 512 bits the byte blocks are skipped (finding
   987-13).
2. **The gate counts methods, not cost.** `VarkaWarmupReachSuite` ("every Vector API method an
   emitted kernel calls gets 2,000 calls from the warm-up", `VarkaWarmupReachSuite.java:66`)
   holds every receiver an emitted kernel calls, the widened narrow species included, to the
   warm-up's call count. It proves the narrow methods are *called* by the warm-up. It does not
   prove that a first narrow batch stops boxing, or what that batch costs with and without them.
3. **The measurement the row asks for has a harness, for one int-lane shape.**
   `VarkaKernelWarmupSuite` forks `VarkaKernelWarmupProbe` (`warmUpInFork`,
   `VarkaKernelWarmupSuite.scala:97-122`), passing on the vector flags and the matrix
   configuration. The probe's shapes are calendar kernels over one date column (`shape()`,
   `VarkaKernelWarmupProbe.java:88-94`, and the sixteen-entry `wide` shape). The suite asserts
   the verdict (`COMPILED`), the drop from first to last probe call (`COMPILED_DROP`), and a byte
   limit per call through the evaluator's entry (`:220-252`), using `VarkaAllocationSampler`.
   No probe shape reads a narrow column.
4. **The whole template warm-up can be switched off.** `varka.warmup.vectorTemplates=false`
   (`VarkaVectorTemplateWarmup.java:147`, read at `:296`) skips it. So "templates on" against
   "templates off" needs no new code. Templates on with only the narrow blocks removed has no
   switch.
5. **Which narrow kernels a fresh JVM meets first.** With `narrowLanes` true by default (row
   478), an all-narrow kernel drives at its own lane and calls the preferred byte or short
   species. A kernel that mixes a narrow column with an int one drives at the int lane and calls
   the widened species (row 480). The two shapes reach different blocks of item 1, so each needs
   its own measurement.
6. **No committed number exists.** No results file under `sql/varka/plans/m8/results/` or
   `sql/catalyst/benchmarks/` measures a first narrow call. `VarkaColdStartBenchmark`'s shapes
   are date shapes.

### What differs from the row text

- The row says the warm-up "covers the Int, Long and Double species ... only". That was true on
  23 October 2026. #993 added the narrow blocks the same day (item 1), so the first branch of the
  done-when ("those arms are added") is built already. What is still owed is the measurement that
  shows the arms pay, and a test that keeps them paying.
- The row's "about line 582" is now `Rounds.run` at line 599.

## 1. Goal and non-goal

**Goal.** For the first narrow kernel of a fresh JVM, a committed measurement of its first
batch, and a suite test that would fail if the narrow blocks stopped doing their job. The
measurement covers allocation per call, the warm-up verdict and the compiled state, for an
all-narrow shape and a mixed narrow-and-int shape. It compares three arms: templates on, narrow
blocks removed, and templates off.

**Non-goal.** No change to what the warm-up calls unless the measurement shows a gap (section
6). No new warm-up switch in production code (question 1). No change to emitted bytes.

## 2. Design

### 2.1 Two narrow probe shapes

`VarkaKernelWarmupProbe` gains a third shape argument, `narrow`, beside `wide` and `others`, with
two kernels:

- **all-narrow**: `y1 + y2` and `s1 < s2` as one projection over two `TINYINT` and two
  `SMALLINT` columns, wrapping (LEGACY). It drives at the byte lane under the defaults and calls
  the preferred byte and short species.
- **mixed**: `CAST(y1 AS INT) + i` and `y1` passed through as an output, over a `TINYINT` and an
  `INT` column. It drives at the int lane, loads at the widened byte species and stores the
  narrow output back. At 128 bits this is also row 479's part form.

Each is built with `VarkaMatrix.base`, as `shape()` is, so the matrix reaches it. The batch data
reuses the probe's null patterns (`Nulls`).

### 2.2 The three arms, one fork each

`warmUpInFork` gains a `jvmArgs` parameter. The three arms are:

1. **templates on**: the defaults;
2. **templates off**: `-Dvarka.warmup.vectorTemplates=false`;
3. **narrow blocks removed**: measured once on a scratch branch that returns early from the four
   narrow blocks, and recorded rather than committed (question 1).

For each arm and shape the probe prints the fields the suite already reads: the first probe
call's bytes, the last, the verdict and its state, and the entry state. It also prints the JIT
state that `jitState` reports, which names the compiled `ByteVector`/`ShortVector` methods.

### 2.3 What is committed

- `sql/varka/plans/m8/results/VARKA-476-narrow-first-batch.txt`: the three arms by two shapes by
  two widths (256 and 128 bits), with three forks per cell. Recorded per cell: first-call bytes,
  verdict, time to verdict and compiled state. It follows the provenance block of
  `results/VARKA-329-partload.txt`. The laptop has 512-bit registers, so the 256-bit and 128-bit
  rows are forced with `-XX:MaxVectorSize`, and one AVX2 run (`-XX:UseAVX=2`) is added.
- One suite test, `C2Code`-tagged as its neighbours are: "after the template warm-up, the first
  narrow kernel's calls run without boxing through the evaluator's entry", for both shapes, with
  the same byte limit as `:240-241`.

## 3. Tests and proofs

1. The new `VarkaKernelWarmupSuite` test of 2.3, at the JVM's width and under the matrix's
   configurations like its neighbours (`assumeOneSpecies`, `C2Code`).
2. **The test proves it can fail**: on the scratch branch of arm 3 it must fail for the shape
   whose blocks were removed. That run is recorded in the results file with its output line. If
   it does not fail, the test is not measuring the narrow blocks and is redesigned before merge.
3. `VarkaWarmupReachSuite` unchanged and green: the reach and the cost are separate claims.

## 4. Predictions, registered before the build

- P1: with templates on, both shapes reach `COMPILED`, and the last probe call's allocation is
  within the existing byte limit (**[I]**: item 1's blocks call every narrow method the gate
  names).
- P2: with templates off, the first narrow call allocates at least `COMPILED_DROP` times the
  templates-on figure, as the int-lane shape does in the existing test (**[I]**).
- P3: with the narrow blocks removed, the mixed shape's first calls box (the widened species is
  reached only by its blocks). The all-narrow shape may not box, because its preferred species
  shares templates with the int warm-up's (**[I]**, not checked: the plan did not trace which
  `jdk.incubator.vector` templates the byte and int species share).
- P4: the suite test adds under 30 s to `VarkaKernelWarmupSuite` per configuration, its fork
  costing about what the existing forked tests do. That figure is to be read against the
  calibration table (`dev/varka_calibration.py`) before the build, and the row adds its scored
  line after.

## 5. Order, files and size

One pull request, test code and a results file:

| File | Change |
|---|---|
| `VarkaKernelWarmupProbe.java` | the `narrow` shapes, the arm fields |
| `VarkaKernelWarmupSuite.scala` | `jvmArgs` on `warmUpInFork`; the narrow test |
| `sql/varka/plans/m8/results/VARKA-476-narrow-first-batch.txt` | the measurement |
| `sql/varka/skills/benchmarking.md` or `testing-and-debugging.md` | one lesson if P3 is refuted |

The order: the probe shapes first, then the laptop measurement under the heavy lock with
`SPARK_LOCAL_IP=127.0.0.1`, then the test, then the scratch-branch fail check. The PR's matrix
dispatch runs the test under every configuration.

Size: small. The row says small to medium; the warm-up change it expected is already built.

## 6. Out of this row

- Adding warm-up blocks for a narrow method the measurement shows is cold. If P1 fails, the
  missing method is named in the results file, and the fix is its own row unless it is one call.
- The narrow species above 512 bits (finding 987-13): no host runs them.
- Row 479's part form at 128 bits. This row measures the mixed shape as it is emitted today, and
  row 479 reruns the measurement if its loop changes the calls.
- A cold-start benchmark section for narrow shapes. `VarkaColdStartBenchmark` stays date-only
  unless the owner asks (question 2).

## 7. Questions for the owner

1. **Arm 3 without a production switch.** Recommended: measure it on a scratch branch and record
   it, and add no `varka.warmup.vectorTemplates.narrow` property to production code for a
   one-off measurement. The alternative is a test-only system property read in `Rounds.run`,
   which keeps the arm reproducible at the cost of a branch in the warm-up.
2. **A cold-start benchmark section for a narrow shape**, measured on the EPYC runners? It would
   give the post a figure. Recommended: not in this row; the suite test and the results file
   answer the row's question.

## 8. Done when

- the results file records, for both shapes at 256 and 128 bits, the first-batch allocation, the
  verdict and the compiled state under templates on, narrow blocks removed and templates off;
- `VarkaKernelWarmupSuite` holds both narrow shapes' first calls through the evaluator's entry to
  the byte limit, and the scratch-branch run shows that test failing without the narrow blocks;
- or, if a shape cannot be warmed this way, the results file says why, with the JIT state that
  shows it.

## 9. Open points for the lead

1. The row's warm-up change is already on master (#993). The row is only the measurement and a
   guard now, and this plan sizes it small. Should the row's text be trimmed to match?
2. P3 depends on which Vector API templates the byte and int species share. If the all-narrow
   shape does not box with its blocks removed, the test for that shape cannot fail on arm 3. The
   plan then keeps it as a regression guard and says so, rather than inventing a fault.
3. #993's description named one gap: at four int lanes, a short column loaded beside a byte
   column converted only its first part. The widened blocks now loop over every part
   (`widenedShorts`, `parts`), so that looks closed. The plan did not run a 128-bit warm-up to
   confirm it, so the 128-bit mixed cell of the measurement is where it shows.

## 10. Outcome
