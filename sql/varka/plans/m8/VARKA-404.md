# VARKA-404: The lane-conversion benchmark's int-lane arm measures its kernel, not the JIT's mood

## 0. What exists now (admission check)

From master at `5c660201b35`, read on 1 November 2026. Nothing was run: the cause has to be read
from a JVM's own output, which is this row's first step (section 2.1), not its admission check.

1. **The row's numbers are committed.** The spike's `results/int-arm-tiering.txt`
   (`sql/varka/spikes/lane-conversion/`) holds the arm run alone, at four rungs, under four JVM
   settings (19 October 2026):

   | setting (`int-arm-tiering.txt`) | 16384 rows | 262144 rows |
   | :-- | --: | --: |
   | tiered, at `512bb292da3` | 2894.7 | 10657.6 |
   | `-XX:-TieredCompilation` | 20202.2 | 10571.6 |
   | `-XX:-UseOnStackReplacement` | 2642.2 | 11105.9 |

   and the same kernel in a plain loop at 17213 M rows/s. With OSR off, the two largest rungs fall
   to 184.0 and 208.7 (`int-arm-tiering.txt`), so they reach compiled code only through OSR.
2. **The committed results still carry it, at both widths.** `VarkaLaneConversionBenchmark` was
   last regenerated at `512bb292da3` (`VarkaLaneConversionBenchmark-jdk25-provenance.txt`). "i2 +
   i, the int lane" reads 1160.7 at 16384 rows and 12927.5 at 262144
   (`VarkaLaneConversionBenchmark-jdk25-results.txt`), and 835.2 and 11026.5 at 128 bits
   (`VarkaLaneConversionBenchmark-jdk25-128bit-results.txt`). Every other arm of the 16384-row
   rung reads 5160.3 or more at 512 bits (`VarkaLaneConversionBenchmark-jdk25-results.txt`).
3. **It is this arm alone.** A scan of every committed `Varka*results.txt` whose sections are
   titled by a row count found no other case whose rate at one rung is less than a third of its
   rate at the next. Only the two cells above match.
4. **What sets the arm apart, read from the source** (`VarkaLaneConversionBenchmark.java`):
   - it is the only arm whose kernel plan drives the int lane, so it is the only arm through the
     seven-argument `run` and through the `Run` lambda that calls it;
   - it is the sixth of seven cases at its rung. Five kernel classes were emitted and timed in the
     same JVM before it, each one asking C2 for its loop methods;
   - each rung emits a new class, so the arm's kernel at 16384 rows is the first int-lane class
     the JVM sees at all.
5. **The two readers a harness can call.** `VarkaKernelWarmup.jitState(Class)` is package-private
   in main code and unreachable from `org.apache.spark.sql`. `VarkaEmitterTestSupport.loopLevels`
   and `allLoopsC2` are public test helpers, which `VarkaSharedPrefixBenchmark` and
   `VarkaNarrowEmitBenchmark` use to settle an arm before timing it. `VarkaTierWitness` tells an
   OSR version from a standard one, which the code list cannot (row 442).
6. **Production does not run this way.** A warmed kernel has C1 excluded by
   `VarkaKernelCompileDirective`, whose pattern matches only the warmed name
   (`VarkaFusedProjection_w...`). The benchmark's classes, `org.apache.spark.sql.varka.bench.
   LaneConversion<N>`, do not match it, so their methods take the tiered path with C1.

### What differs from the row text

- The row speaks of "the harness's call site". The source shows two lambdas and one case lambda,
  and the int arm is alone on one of them (item 4). Which call site matters is part of what the
  JIT output must say.
- The row offers two outcomes: isolate the arm, or state the rung unreliable. Item 6 adds a third
  that the cause may select: run the benchmark's kernels as production runs warmed ones, with C1
  excluded. That changes every arm, so it is the owner's (question 1).

## 1. Goal and non-goal

**Goal.** The cause of the 16384-row int arm's rate is read from the JIT's own output and written
down. The benchmark then either measures the arm on the code the other arms run on, or its
results file says, per case, when a case was timed on code that is not C2's.

**Non-goal.** No emitter change: the arm's kernel bytes are not in question (the spike's bytes
oracle, item 1). No change to the other kernel benchmarks beyond the shared helper of 2.3.

## 2. Design

### 2.1 Read the cause (a laptop debugging run)

Debugging is the laptop's work by the owner's rule (CI for verification, the laptop for
debugging). The runs use `SPARK_LOCAL_IP=127.0.0.1` and take one of the shared flock slots.

The benchmark is run with `-Dvarka.bench.only` empty, so all arms run in order, and is run again
with only the int arm. Each run uses:

- `-XX:+PrintCompilation` and `-XX:+PrintTieredEvents`, filtered afterwards to the
  `LaneConversion<N>` classes and the two lambdas;
- `-XX:CompileCommand=PrintInlining,org.apache.spark.sql.VarkaLaneConversionBenchmark*::*`, for
  what C2 inlined at the case lambda and the `Run` lambdas.

Hypotheses, registered before the run, each with the output that would confirm it:

- **H1, stranded on C1 (`skills/the-jit.md`, "A method C1 compiles at tier 2 but not at tier 3 is
  stranded on C1 code").** The int kernel's loop crosses its first threshold while C2's queue
  holds the five earlier arms' compiles, so it is compiled at tier 2 and never climbs. The
  evidence would be a `compile level=2` event for the class's `loopDense` with a non-empty C2
  queue beside it, and no later level-4 version. It fits item 1: with tiering off it is fixed,
  and with OSR off the small rung is unchanged.
- **H2, the case lambda's call site.** C2 compiles the shared case lambda with a profile from the
  five eight-argument arms, then deoptimizes at the int arm's `Run` class and recompiles it
  without inlining the kernel. The evidence would be a `made not entrant` of the lambda's
  compile at the int arm's start and a `PrintInlining` line refusing the kernel's `run`.
- **H3, OSR only.** The loop's only C2 version is an OSR one, which a 16384-row call (1024 lane
  groups at 512 bits) never enters, while the 262144-row rung runs long enough to enter it. The
  evidence would be a `%` compile of `loopDense` and no standard level-4 compile.

The outcome goes in section 10 with the log lines that decided it, and the lesson goes in
`skills/the-jit.md` if it is not one of the sections named above.

### 2.2 The harness change, by the cause

- **H1:** the benchmark installs the C1 exclusion for its own kernel classes, as production does
  for warmed ones. That needs `VarkaKernelCompileDirective` to accept a second name pattern from
  a test, or the benchmark to name its classes with the warmed prefix (question 1).
- **H2:** each arm gets a call site of its own, a `Run` instance whose class is distinct per arm
  (a small hidden class per arm, or one lambda per arm written out). Then no arm's profile reaches
  another's.
- **H3, or a cause none of the three names:** the arm is settled before it is timed, as
  `VarkaSharedPrefixBenchmark.driveToCompiledCode` does, using row 442's shared helper.

### 2.3 The record in the results file, in every case

Whatever 2.2 does, the benchmark checks each case once its warm-up ends: the loop methods of the
case's kernel must be C2 code, read by `VarkaEmitterTestSupport.loopLevels`, or by row 442's
witness-based helper once it lands. A case that is not writes one `note(...)` line beside its
table, naming the case and the levels. A results file therefore says when a cell was timed on
lower-tier code, which today only the spike's investigation found. The scalar loop is exempt: it
has no kernel.

## 3. Tests and proofs

1. The JIT logs of 2.1, kept in `sql/varka/spikes/lane-conversion/results/int-arm-jit-<date>.txt`
   (filtered to the classes named there), and cited from section 10.
2. A regeneration with `dev/varka_bench_regen.sh catalyst VarkaLaneConversionBenchmark` after the
   change, at both widths. Then a `benchmark.yml` pair-mode run of the class against the merge
   base on the runner, which is the verification.
3. A planted case for 2.3: a run with `-XX:TieredStopAtLevel=1` must write the note for every
   kernel case (`VARKA-504.md` on branch `varka-m8-triage`: a check proves it can fail).

## 4. Predictions, registered before the build

- P1: H1 is the cause (**[I]**: from the three settings of item 1 alone).
- P2: after the change the int arm at 16384 rows reads within the plain loop's order, 10000 M
  rows/s or more at 512 bits, on the laptop.
- P3: under H1's fix every other arm moves less than the class's band, or the class has none and
  the flat threshold applies, and the move is read with that said (`sql/varka/AGENTS.md`).
- P4: the note of 2.3 is written for no case in the regenerated files.

## 5. Order, files and size

| File | Change |
|---|---|
| `VarkaLaneConversionBenchmark.java` | the fix 2.2 selects; the check of 2.3 |
| `VarkaBenchmarkBase.java` | the per-case tier check as a helper, beside `note` |
| `VarkaKernelCompileDirective.java` | a test pattern, only under H1 and question 1's (a) |
| `sql/catalyst/benchmarks/VarkaLaneConversionBenchmark-jdk25-*` | regenerated |
| `sql/varka/spikes/lane-conversion/results/` | the JIT log of 2.1 |
| `sql/varka/skills/the-jit.md` | the lesson, if new |

Order: the JIT run and its log; the fix; the check; the regeneration and the pair.

Size: small, as the row says, unless question 1's answer is (a). Then the regeneration moves every
arm, and the requote of `VARKA-28.md` 9.3's table is part of the row.

## 6. Out of this row

- The other kernel benchmarks' tiers. Row 442 shares the helper, and a scan for their bad cells
  found none (item 3).
- `VarkaKernelWarmup.jitState`'s visibility.

## 7. Questions for the owner

1. **If the cause is C1 (H1): what should the benchmark measure?** (a) The kernels as a warmed
   session runs them, with C1 excluded through the directive. Every arm moves and the file is
   regenerated whole. (b) The tiered path as today, with the arm settled before timing (2.2's
   third case), so only the int arm moves. (c) The arm left as it is and its cell noted unreliable.
   Recommended: (a). Production's warmed kernels never run C1 code, so a benchmark that does is
   pricing a path users do not take.
2. **The tier check of 2.3 in the base class.** It costs one code-list read per case, whose cost
   `skills/the-jit.md` gives ("Which tier served a call"). Should every Java kernel benchmark run
   it through `VarkaBenchmarkBase`, or only this one? Recommended: this one, with the helper in
   the base so that the next benchmark that needs it calls it.

## 8. Done when

- the cause is stated in section 10 with the JIT output lines that show it;
- the int arm is either measured on C2 code or its cell is noted, by the check of 2.3, in the
  committed results file;
- the regeneration and the pair are committed.

## 9. Open points for the lead

1. Row 442's witness-based helper would serve 2.3 better than the code list, since it tells an
   OSR version apart (H3). If row 442 lands first, this row uses it.

## 10. Outcome
