# VARKA-618: the forked warm-up tests run under a lane count whatever host the unit draws

## 1. Where this came from

Row 618 of `PLAN.md`, added on 15 November 2026 when closing row 523. In run 43642146943 the
catalyst unit under `lanesOverride=4` (b053) drew an Intel Xeon 8573C, whose preferred int
species at `MaxVectorSize=64` is 16. The unit under `=16` (b033) drew an AMD EPYC 7763, whose
preferred is 8. So `assumeOneSpecies` skipped the six forked tests of `VarkaKernelWarmupSuite` in
both: 6 of 14 skipped, against 0 at the defaults (`results/VARKA-513-ci-wall-20261115.txt`,
523). Row 523's done-when asks for the tagged tests to run at the defaults and under a lane
count, and its only lane-count evidence is a draw in which each configuration was a second
species for its host. Nothing pins a configuration's catalyst unit to a host class.

## 2. The admission check, done

* **The skip holds on master.** `VarkaKernelWarmupSuite.assumeOneSpecies` cancels when
  `VarkaMatrix.base.lanesOverride()` is neither 0 nor `IntVector.SPECIES_PREFERRED.length()`,
  read in the test JVM. The forked tests' probe JVM (`warmUpInFork`) gets only the test JVM's own
  `-XX:MaxVectorSize`, `-XX:UseAVX` and `-XX:UseSVE` flags, so its preferred species is the test
  JVM's. `VarkaSanitizedWarmupSuite` has the same assumption over its forked probe.
* **The probe's own flag can give it the species.** The JDK 25 on the laptop (an AMD Ryzen AI 9
  HX PRO 370, AVX-512), with a one-line program printing `IntVector.SPECIES_PREFERRED.length()`
  (`results/VARKA-618-species.txt`): 16 lanes with no flag, 4 under `-XX:MaxVectorSize=16`, and
  16 under `=64`. With `-XX:UseAVX=2`, the runners' EPYC 7763 level: 8 with no flag, 4 under
  `=16`, and 8 under `=64`, with HotSpot's warning "MaxVectorSize must be at most 32 on this
  platform". So a probe started with `-XX:MaxVectorSize=<4 x lanes>` has the lane count as its
  preferred species wherever the host can make it: 4 lanes on every x86 runner class, and 16 only
  on a 512-bit host.
* **What that settles.** The template warm-up compiles the Vector API's templates for the
  preferred species (`vector-api-and-width.md`, "two species in one JVM is a box per
  iteration"). A probe whose preferred species is the kernel's has only one species, and the
  condition the assumption guards against is gone.
* **What the check would have rejected.** Pinning a configuration's unit to a runner class. A
  hosted `ubuntu-latest` job cannot ask for a CPU model (VARKA-591's retry exists because a job
  lands where it lands), so the matrix cannot pin one.

## 3. The design

### 3.1 The probe runs at the lane count's width

`warmUpInFork` and the sanitized suite's `runProbe` take their vector flags from one helper,
`VarkaChildProcess.vectorFlags(lanes)`. With `lanes == 0` it returns the test JVM's flags, as
today. Otherwise it returns them with `-XX:MaxVectorSize` replaced by `4 * lanes`, the int
species' bytes. The probe prints its preferred int lanes on its support line
(`VARKA_WARMUP_SUPPORT=... preferredLanes=<n>`).

The assumption moves from the test JVM to the probe's answer. A forked test cancels only when the
probe's preferred lanes are not the configuration's, and the message names the host and the
width: "this host cannot make 16 int lanes (preferred 8 at MaxVectorSize=64)". Under
`lanesOverride=4` that never happens on the runners. Under `=16` it happens on a 256-bit host
and nowhere else, which is a skip by the host, not by the draw.

Alternatives considered: run the probe at its own default width and let the kernel be a second
species there (rejected: that is the boxing the assumption exists for, and the tests would fail
rather than skip); and a lane-count configuration fixed to 4 lanes in the matrix (rejected: the
matrix's `lanesOverride` values come from `VarkaEmitOption.TABLE`, and `=4` already runs).

### 3.2 What is deliberately unchanged

The eight tests that do not fork, which run under every configuration. The `C2Code` tag and
`VarkaMatrixRuns.C2_OPTIONS`. The `lanesOverride` values the matrix runs (4 and 16). The test
JVM's own flags.

## 4. Files

| file | what |
|---|---|
| `VarkaChildProcess.java` | `vectorFlags(lanes)` |
| `VarkaKernelWarmupProbe.java` | `preferredLanes` on the support line |
| `VarkaKernelWarmupSuite.scala`, `VarkaSanitizedWarmupSuite.scala` | helper, assumption |
| `results/VARKA-618-species.txt` | the admission check's readings |

## 5. Tests, and what each is for

* `VarkaKernelWarmupSuite` run on the laptop under `lanesOverride=4` and `=16`, and under
  `JDK_JAVA_OPTIONS=-XX:UseAVX=2` with `=4` (the runners' AVX2 level, which `warmUpInFork` forwards
  to the probe's JVM with the lanes' own flag): all 14 tests run in the first, second and third.
  Under `UseAVX=2` with `=16`, the six cancel with the host message. This catches a flag that does
  not reach the probe.
* `VarkaKernelWarmupSuite`, a test that `vectorFlags(4)` replaces an existing
  `-XX:MaxVectorSize=32` and keeps `-XX:UseAVX=2`. It catches two width flags reaching one JVM,
  where the last one would win silently.
* `VarkaSanitizedWarmupSuite` under `lanesOverride=4` with the sanitizer on.

## 6. The measurement

### 6.1 Predictions, registered before the run

1. In the matrix's JUnit after the change, `VarkaKernelWarmupSuite` under `lanesOverride=4` runs
   14 of 14 on whichever class it draws, an EPYC 7763 or a Xeon 8573C. Under `=16` it runs 14 of
   14 on a Xeon 8573C and 8 of 14 on an EPYC 7763, with the six named as a host skip.
2. The six forked tests add about 30 s to a lane-count configuration's catalyst unit: the suite
   took 31 s at the defaults, nearly all of it the forked probes' warm-ups (VARKA-398's
   calibration row, ratio about 1.0), and the probe's width does not change its warm-up's length.

## 7. Risks

1. A probe at a narrower width than the test JVM's warms the templates for that width only,
   which is what is wanted. But a probe kernel whose emitted species is not the int species (a
   long lane) needs the long species' bytes. `vectorFlags` takes the int lanes, as
   `lanesOverride` is defined, and the probe's support line shows the species it measured.
2. On an Arm host (`isa=arm`, the Neoverse N2 at 128 bits), `-XX:MaxVectorSize=16` is the
   host's width already, so `=4` runs and `=16` cancels with the host message. Platform bins run
   the defaults only, so neither is drawn there today.

## 8. Sequencing

One pull request of about 120 counted lines.

1. The helper and the probe's line. Done when `build/sbt 'catalyst/testOnly
   *VarkaKernelWarmupSuite'` passes at the defaults on the laptop.
2. The assumption, and the laptop runs of 5 (four configurations, one suite each, under
   `SPARK_LOCAL_IP=127.0.0.1` and the heavy lock). Done when the counts match 5.
3. On the owner's word, the pull request's matrix dispatch. Done when its JUnit shows prediction
   1 for whichever classes the two units draw. A draw that lands both units on one class leaves
   the other class owed, which the next weekly run or a rerun of that bin supplies.

## 9. Done when

A lane-count configuration runs all 14 tests of `VarkaKernelWarmupSuite` on each runner class the
matrix uses without depending on the draw, and the report shows them.
