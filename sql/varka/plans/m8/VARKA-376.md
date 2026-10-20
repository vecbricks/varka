# VARKA-376: which of this milestone's operators are intrinsic, and allocation-free, where

*Row 376 of `m8/PLAN.md`, wave 0, added by the review of 16 October 2026 (10.3 item 3). Planned on
16 October 2026 at `8a50f61a368`. The row is a census, so its admission check is short: whether
the census exists already in some form, and what it can rest on.*

## 1. Where this came from

Two designs of this milestone already rest on operators the HotSpot match rules refuse on a CI
host: row 30's long-to-double and double-to-long conversions, which `x86.ad` refuses without
AVX512DQ, so not on the EPYC 7763 runners (10.1 item 4), and 375's `VectorMask.fromLong`, refused
on aarch64 without SVE2 BitPerm (10.1 item 2). The record's operator table
(`skills/vector-api-and-width.md`, "Every operator the plans rely on is one instruction") was
taken on one host, and its two-species finding - a box per iteration, 6.4x to 12.8x slower - is a
case 329's two arms can create in one JVM. Row 376 asks, before the lane rows design around them:
for each family of operator the milestone's rows use, is it intrinsic at each width the milestone
measures, and does a loop of it allocate.

## 2. The admission check, done

1. **No operator-by-operator census exists.** The operator table was measured in September 2026
   with an uncommitted `Probe.java` on the laptop. `VarkaWidthAuditSuite` (catalyst, forking
   `VarkaWidthAuditProbe` once per width under `-XX:MaxVectorSize=<bytes> -Xbatch` and
   `PrintIntrinsics`, writing `sql/varka/width_audit.json`; run on both runner classes by
   `varka-width-audit.yml`, results as artifacts) reads intrinsic refusals per kernel *shape*, not
   per operator, and reads no allocation. `VarkaAssemblySuite` asserts allocation per row
   (`maxBytesPerRow = 1.0`, `VarkaAssemblyProbe`, `VARKA-55.md`) for a few loops. So the row
   builds the census; it does not re-run one.
2. **What a verdict is.** By the skill's entry "An intrinsic refusal line is not a verdict about
   this compile; the inlining decisions are", the verdict per operator is read from
   `-XX:+PrintInlining` filtered to the intrinsic's symbol (`late inline succeeded` against
   `failed to inline (intrinsic)`), never from `PrintIntrinsics`'s `**` lines alone; and by
   "Assert it as a rate, not as sites", the allocation half is
   `ThreadMXBean.getThreadAllocatedBytes` around a thousand steady-state calls, at most one byte
   per row, a box being at least five. Both instruments exist and are cheap.
3. **The widths, named by species and not by datapath.** The laptop's preferred species is 512
   bits on a 256-bit datapath (the skill's "This machine's AVX-512 is 256 bits wide"); the EPYC
   7763 runners are AVX2, a 256-bit species; the 128-bit species is the forced `MaxVectorSize=16`
   on x86 and the native one on the Neoverse N2 runner. The row's "512, 256 and 128" are species
   widths, and the table says so in its header.
4. **The two-species case must not run in a shared test JVM.** `VarkaSpeciesGuard` fails any suite
   that defines a second species of a lane type where a test is about to load a class, and
   `VarkaOwnJvm` runs such a suite in a child JVM (VARKA-246). The census's two-species probe
   therefore runs forked, in a JVM of its own, as the assembly and cliff suites fork theirs.
5. **`m7` row 374 is closed "Done, no fix"**: x86 C2 lowers no two-lane mask, so at a 128-bit x86
   species every masked long operator is the fallback. The census records that per operator as the
   expected refusal at that cell rather than a finding, and every 128-bit timing it takes carries
   the release rate beside it (`m8/PLAN.md` 10.3 item 4).

**What still needs a run.** The census itself: the probe of 3.1 on the laptop at its preferred
species and at `MaxVectorSize=32` and `16`, and on both runner classes through a dispatched
workflow. Nothing about the row's premise is in doubt, so the run is the work rather than an
admission gate; the first command, once the probe exists, is

    taskset -c 3-5 build/sbt -Dvarka.ownJvm=true 'catalyst/testOnly *VarkaOperatorCensusSuite'

and the row is done when its table is committed with a verdict in every cell.

## 3. The design

### 3.1 One forked probe, one row per operator family

`VarkaOperatorCensusProbe` (catalyst test tree, Java), a `main` that runs one tight loop per
operator at the JVM's preferred species, each loop the operator alone over an array, warmed until
C2's loop compile settles, then reports per operator: the inlining verdict of the intrinsic it
reaches, and the allocated bytes per row over a thousand calls. `VarkaOperatorCensusSuite`
(JUnit 5, `@VarkaSuite(ownJvm = true)`) forks it at each width the host supports and writes
`sql/varka/operator_census.json` under a regen flag, as the width audit writes its file. The
families, from the row:

* byte and short widening, narrowing - operators: `B2I`, `S2I`, `I2B`, `I2S` by `convertShape`, and
  the widening load; for rows: 329, 330
* byte and short lanewise - operators: `ADD`, `SUB`, `MUL`, `SADD`, `SSUB`, compares, shifts at byte
  and short; for rows: 329, 330
* floating compares - operators: `EQ`, `LT`, `LE`, `IS_NAN` on `DoubleVector`, `FloatVector`; for
  rows: 341
* conversions - operators: `L2D`, `D2L`, `F2D`, `D2F`, `I2D`, `L2F`; for rows: 30, 343, 344
* lane width - operators: `I2L` and `L2I` by `convertShape`, expanding parts 0 and 1, contracting 0
  and -1; for rows: 28
* fused multiply-add - operators: `FMA` on doubles; for rows: 30, 346, 366
* math - operators: each `VectorOperators` math operator the family binds; for rows: 345, 356 to 358
* unsigned long compares - operators: `ULT`, `UGT`, `ULE`, `UGE` on `LongVector`; for rows: 359, 360
* the de-interleave - operators: two-vector `rearrange`, `selectFrom`, `compress` on `LongVector`;
  for rows: 347, 359
* masks - operators: `VectorMask.fromLong`, `toLong` per species; for rows: 331, 375, 27
* two species - operators: 329's arms: a byte species and the int species in one JVM, both orders of
  first use; for rows: 329

### 3.2 What the census produces

The table, committed beside the record's operator table in `skills/vector-api-and-width.md` as a
new section, one row per operator, one column per host and species: `I` (intrinsic, no
allocation), `I+A` (intrinsic, allocating), `F` (fallback), with the allocation per row where it
is not zero. And for every row of `m8/PLAN.md` whose design uses an operator the table marks `F`
or `I+A` on a host, a line in that row's plan (or its row text, when it has no plan yet) naming the
form it takes on that host - the done-when's second half.

### 3.3 What is deliberately unchanged

* No emitter or kernel change, and no decision of another row's design: the census says where an
  operator is intrinsic; 30, 329, 343 and the rest choose their forms from it.
* The width audit and the assembly suite keep their scope; the census is per operator, they are
  per shape.
* `m7` row 374's two-lane x86 masks: recorded as a known `F`, not re-investigated.

### 3.4 Registered op counts

None: this row emits no kernel.

## 4. Files

* `sql/catalyst/src/test/java/.../codegen/varka/VarkaOperatorCensusProbe.java`: new: the
  per-operator loops
* `sql/catalyst/src/test/java/.../codegen/varka/VarkaOperatorCensusSuite.java`: new: forks the probe
  per width, writes the file
* `sql/varka/operator_census.json`: new: the census's output
* `.github/workflows/varka-width-audit.yml`: a step running the census on both runner classes
* `sql/varka/skills/vector-api-and-width.md`: the table, as a new section; `SKILLS.md` regenerated

## 5. Tests, and what each is for

1. **A planted fallback is read as `F`**: the probe's gather at a forced 128-bit species on x86,
   which VARKA-165 recorded as refused on part of the pool, or `fromLong` under `UseSVE=1` on the
   Arm runner (375); catches a census that reads every operator as intrinsic.
2. **A planted box is read as `I+A`**: the two-species loop in its boxing order, the case the
   skill measured; catches an allocation reading that cannot see a box.
3. **The suite's own JVM**: `VarkaSpeciesGuard` stays green in the shared JVM while the census
   runs; catches the census polluting the other suites.

## 6. The measurement

The census measures verdicts, not throughput; it times nothing it reports as a speed.

### 6.1 Predictions, registered before the run

1. **The conversions `L2D` and `D2L` read `F` at the 256-bit species on the EPYC 7763 runner**
   and `I` on the laptop, by `x86.ad`'s AVX512DQ rule (10.1 item 4).
2. **`fromLong` reads `I` on all three hosts at their defaults**, by 375's section 2.
3. **The two-species loop reads `I+A` in at least one order** of first use, as the skill's
   measurement found for `selectFrom`.
4. **The math operators read `I` wherever the library binds them** and `F` for `POW` at the AVX2
   runner's 256-bit species (the canary's finding).
5. **The suite takes under 10 minutes per host**, forking one JVM per width: about forty
   operators, each warmed for a few seconds, then doubled by the calibration table's observation
   that harness reads cost more than the kernel's arithmetic (VARKA-274a's tier witness, VARKA-243's
   suite); stated so the workflow step's timeout can be set.

## 7. Risks

1. **A loop of one operator is not a kernel.** C2 may lower an operator alone and refuse it in
   context; the census is a floor, and the rows' own assembly checks (`dev/varka_emit.sh --asm`)
   stay the verdict for a kernel.
2. **The runner pool varies** (`sql/varka/HARDWARE.md`): the x86 runner's column names its CPU
   from the run, and `expected-cpu` pins the EPYC 7763 where a cell's verdict depends on it.
3. **The Arm runner is one core class with BitPerm**; the `UseSVE=1` column is a simulation, as
   375 says.

## 8. Sequencing

1. This plan.
2. The probe and the suite, run on the laptop at its three widths, the planted cases of section 5
   green.
3. The workflow step, the two runner columns, the table committed with `SKILLS.md` regenerated.
4. The rows whose design meets an `F` or `I+A` cell get their line (section 3.2).

Explicitly out of this row: choosing any row's form, kernel-level assembly checks, and fixing any
refusal it finds.

## 9. Outcome

*20 October 2026, at `5e11eb91ba3` (after rows 28, 334, 335, 397 to 399; none of them touched what
the plan rests on).* The census is built and taken on the laptop; the two runner columns came from the
workflow's first dispatch (run 39400866095 on master after the merge, 20 October 2026, Zulu
25.0.4+7: EPYC 7763 at 256 and 128 bits, Neoverse N2 at 128) and are committed under `results/` as
`VARKA-376-census-epyc-7763.json` and `VARKA-376-census-neoverse-n2.json`, so the row's done-when is
met on all three hosts. Section 9.2 reads them.

**What was built.** `VarkaOperatorCensusCatalog` (87 entries: 85 cells and two planted cases, no
vector type in it), `VarkaOperatorCensusProbe` (49 loops, one `op_*` method each, the operator a
`static final` read from a system property so that C2 folds it) and `VarkaOperatorCensusSuite`
(JUnit 5, `@VarkaSuite`), which forks the probe once per cell at each width `MaxVectorSize` gives
the host and writes `sql/varka/operator_census.json` under `VARKA_CENSUS_REGEN=true`. The table is
in `skills/vector-api-and-width.md`, and `varka-width-audit.yml` runs the suite on both runner
classes. On the laptop: 85 of 85 cells intrinsic and allocation-free at 512 bits, 84 of 85 at 256
(`POW` has no vector stub), and at 128 sixty-five intrinsic, fourteen fallbacks (the 64-bit lane
at two lanes and `POW`) and six cells not meaningful. No cell allocates in a loop that is
intrinsic, and the five two-species arrangements read `I`.

**Departures from section 3, each for a reason the work found.**

1. *Not `ownJvm`.* The suite defines no species in its own JVM: every species lives in a fork, and
   the catalog it reads holds no vector type, so a child JVM around the forks would only add a
   process. Test 3 of section 5 (the species guard stays green) therefore has nothing to guard.
2. *One JVM per cell, not per family.* A second species of a lane type in a JVM is what the record
   says boxes, and the conversions and the narrow loads each use one; per family would have made
   the census depend on its own order. About 250 short forks run in 25 s on the laptop.
3. *The planted cases.* The fallback is `-XX:DisableIntrinsic=_VectorBinaryOp` on a long add, which
   is a fallback on every host and has a control without the flag read `I`; the gather at 128 bits
   and `fromLong` under `UseSVE=1` of section 5 are host-dependent, so they stay data. The planted
   box is a vector stored to a static field (6 bytes a row, read `I+A`), because the two-species
   loops did not box here.
4. *The verdict rule needed two additions.* A declined intrinsic is not always a `failed to inline
   (intrinsic)` line: with the intrinsic switched off, C2 inlines `VectorSupport::binaryOp` as the
   Java method (`inline (hot)`), so any `VectorSupport` decision other than `maybeRebox` that is not
   an `(intrinsic)` is a refusal. And the math operators look their vector stub up before the
   intrinsic is called, so a species with none (`POW` at 256 and 128 bits) never reaches
   `libraryBinaryOp`: for the `library*` anchors an absent decision beside other intrinsics reads `F`
   with the method named "(no stub)"; for any other anchor an absence fails the suite as unread.
   Trees of several compiles are told apart by their root call sites' bci, and a two-loop entry is
   read loop by loop (`VARKA_CENSUS_LOOP` markers), or the second loop's tree hides the first's.
5. *Three more two-species arrangements* than the plan's two orders: the widening load beside a full
   compare in either order, and both species in one loop, since 329's kernel is the last.

**Rows that name a form for a host.** Each row whose design meets an `F` cell has a "Census
reading" sentence in its row text in `PLAN.md` (27, 329, 331, 341, 345, 347, 358, 359, 360, 375):
at 128 bits on x86 the 64-bit lane's mask operators are the Java fallback, consistent with `m7` row
374, and `POW` is the fallback below 512 bits. The runner columns add cells (section 9.2): the AVX2 runner refuses `L2D`, `D2L` and `L2F`, which
rows 30, 343 and 344 take into their designs.

### 9.1 The predictions

| | prediction | measured | |
| :-- | :-- | :-- | :-- |
| 1 | `L2D` and `D2L` read `F` at 256 bits on the EPYC 7763 runner and `I` on the laptop | laptop `I` at all three widths; EPYC 7763 `F` at 256 and at 128 | held |
| 2 | `fromLong` reads `I` on all three hosts at their defaults | laptop `I` at its default 512 and at 256; on longs at 128 `F`, as `m7` row 374 has it; EPYC 7763 `I` at 256 and `F` at 128 on longs; Neoverse N2 `I` at its 128 | held |
| 3 | the two-species loop reads `I+A` in at least one order | `I` with no allocation in all five arrangements at 512 and 256 bits | missed |
| 4 | the math operators read `I` where bound and `F` for `POW` at the AVX2 runner's 256 | laptop: every operator `I` at 512, `POW` `F` at 256 and 128 (no stub); EPYC 7763 `F` for `POW` at 256 and 128, every other math operator `I`; Neoverse N2 `POW` `I` but `TANH` `F`, a cell the prediction did not name | held, with one new cell |
| 5 | the suite takes under 10 minutes per host | 25 s on the laptop (85 cells, three widths, 4 parallel forks by default on 24 cores) | held, at 4% of the bound |

The miss in 3 is a finding: the box the record measured needs more than a second byte species in the
JVM, and the arms of 329 read clean here in every arrangement tried, so 329 need not build around
it on this host; the Arm and AVX2 runner columns decide whether that holds on the fleet.

### 9.2 The runner columns

Run 39400866095 (workflow dispatch on master, 20 October 2026; the full cells are in
`results/VARKA-376-census-epyc-7763.json` and `results/VARKA-376-census-neoverse-n2.json`).

* **EPYC 7763 (AVX2), 256 bits:** 81 cells `I`, 4 `F`: `L2D`, `D2L`, `L2F` (the long-to-double family
  needs AVX512DQ) and `POW` (no stub). **128 bits:** 62 `I`, 17 `F`, 6 not meaningful; beside the
  four, the double compares and `IS_NAN`, the four unsigned long compares, `rearrange2`,
  `selectFrom`, `compress`, `fromLong` and `toLong` on longs, the same 64-bit-lane-at-two-lanes
  fallbacks the laptop has at 128 bits, plus the three conversions.
* **Neoverse N2 (128 bits, native):** 78 `I`, 1 `F`, 6 not meaningful. The one `F` is `TANH`, a cell
  neither the laptop nor the EPYC has; it is a double math function, so row 358's `tanh` design
  meets it on Arm.
* No intrinsic cell on either runner allocates beyond the laptop's bound; the census files carry the
  bytes a row.

**Self-review and out-of-scope findings** are in the pull request description.

