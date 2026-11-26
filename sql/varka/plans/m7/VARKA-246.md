# VARKA-246: One vector species per lane type in the shared test JVM

## 1. Where this came from

Row 246 of `m7/PLAN.md`, from `m10/SCOPE.md` item 69 (VARKA-209 13.2). A second species of one lane
type in a JVM makes the Vector API's shared templates inline bimorphically, so that C2 keeps a
heap box per loop iteration in some shapes and every kernel compiled afterwards can run boxed
(`sql/varka/skills/vector-api-and-width.md`). The catalyst test JVM broke the rule: suites that run
kernels at a lanes override put a second species in the JVM every Varka suite shares, and any test
whose verdict is a JIT outcome failed by the suites' order. The warm-up suite's compile tests did,
one full run in three, until they were moved to a JVM of their own (a fix for that suite, not for
the JVM). Item 69's "done when": no Varka suite runs a second species in the shared JVM, a guard is
in place, and the full run's time is read before and after.

## 2. The admission check, done

The census is the check: the item's own words say a search for a lanes override finds four suites
"and not a proof there are no others". Made on 9 October 2026 on `master` (`4675b27160f`) by
the guard itself, in its `report` mode over `catalyst/testOnly *Varka*` on this machine (AVX-512,
preferred 512 bits): a kernel emitted at a lanes override names `<Lane>Vector.SPECIES_<bits>`
(`Lane.speciesField`), so the emitted bytes say whether a class defines a species other than
`SPECIES_PREFERRED`'s, and 237 classes did.

| suite | classes at another species | species named |
|---|---:|---|
| `VarkaEmitterDivisionSuite` | 95 | Int, Long and Double at 64, 128 and 256 bits |
| `VarkaCoverageCompositionFuzzSuite` | 47 | Int, Long, Double at 128 and 256 |
| `VarkaEmitterValiditySuite` | 37 | Int at 64, 128, 256 |
| `VarkaIrFuzzSuite` | 28 | Int, Long, Double at 64, 128, 256 |
| `VarkaEmitterLongLaneSuite` | 25 | Long, Double, Int at 64, 128, 256 |
| `VarkaEmitterBudgetSuite` | 4 | Int, Long, Double at 128 |
| `VarkaEmitCostSuite` | 1 | Long, Double at 128 |

Seven suites, three more than the item named. Nothing else in `catalyst` defines a second species;
`sql/core`'s suites run the production loader at the session's width and set no lanes override
(none of its test sources mentions one), and the benchmarks fork their JVMs. The same suites run
2 to 24 seconds each, which bounds the cost of a JVM of their own.

What the check would have rejected: moving only the four suites the item named (the census found
three more), and a guard in main code (`VarkaGeneratedClassLoader`), where a test-only rule would
live in the product.

## 3. The design

### 3.1 The guard: `VarkaSpeciesGuard` (test, Java)

*Superseded in part by 9.1: the check is against the sizes the JVM has already used (a registry), not against `SPECIES_PREFERRED`; read 9.1 first.*

Reads a class's constant pool with `java.lang.classfile` (Java because scalac rejects that API's
cyclic types) and reports every `SPECIES_<bits>` field of a vector class whose size is not that
class's `SPECIES_PREFERRED`. `check(className, bytes)` throws where a test is about to define
such a class, unless the JVM is a suite's own (`-Dvarka.ownJvm=true`) or `-Dvarka.speciesGuard=off`;
`report` records the violations to a file instead, which is how the census was made. It is called
where the test harness defines classes: `VarkaEmitterTestBase.load` and `VarkaKernelCheck`'s two
runners, the only in-process points that run a kernel at a lanes override. A kernel emitted for the
JVM's own width, or at a lane count that is the preferred one, is not a second species (the
named constant is the same object), and the guard says so.

### 3.2 The move: `VarkaOwnJvm` (test, Scala)

A suite mixes it in last. In the shared JVM its own tests are not registered; one test is, which
starts a child JVM with the parent's arguments, classpath and system properties (the option
matrix's configuration, the sanitizer, a fuzzer's budget and seed) running that suite alone with
`-Dvarka.ownJvm=true`, copies the child's output to the parent's so the matrix's markers are where
they were, and passes when the child exits 0, failing with the child's failing lines. In the
child the tests are registered as usual and the guard is off. `-Dvarka.ownJvm=true` by hand runs a
suite's tests directly, which is how to run one test of such a suite from sbt. The seven suites
mix it in; their tests are unchanged.

### 3.3 What is deliberately unchanged

The tests of the seven suites, the emitter, the benchmarks (forked), the suites that already fork
a probe, and the 128-bit gate's use of `-XX:MaxVectorSize=16`.

### 3.4 Registered op counts

None.

## 4. Files

| file | what |
|---|---|
| `VarkaSpeciesGuard.java`, `VarkaSpeciesGuardSuite.scala` (test) | the guard and its tests |
| `VarkaOwnJvm.scala` (test) | the trait |
| the seven suites | `with VarkaOwnJvm` |
| `VarkaEmitterTestBase.scala`, `VarkaKernelCheck.scala` | the guard where they define classes |
| `sql/varka/skills/vector-api-and-width.md`, `testing-and-debugging.md` | the notes |

## 5. Tests, and what each is for

* **`VarkaSpeciesGuardSuite`**: a kernel at the JVM's own width names no second species, at
  another lane count it does and the guard throws, and the long lane is seen too. It reads bytes
  and defines nothing.
* **The seven suites themselves**: each passes in its child, and the parent shows one test per
  suite. Run at 512, 256 and 128 bits (below).
* **The whole `catalyst/testOnly *Varka*` run in fail mode**: any suite that defines a second
  species in the shared JVM fails there, so the census is a standing test.

## 6. The measurement

The full `catalyst/testOnly *Varka*` run's wall time before (the census run, 443 s) and after,
and the guard's verdict at the three widths a runner may have (512, 256 and 128 bits).

### 6.1 Predictions, registered before the run

1. The guard finds no violation at 512, 256 or 128 bits in fail mode.
2. The full run's time rises by the seven JVM starts, 70 to 150 seconds, and does not fall: the
   item's "boxed kernels it no longer runs" are not a time that shows in a suite of correctness
   tests.
3. The seven suites' child runs pass with the counts they had.

## 7. Risks

1. **A suite run from sbt with `-z`** finds nothing in the parent; `-Dvarka.ownJvm=true` is the
   route and the trait's doc says so.
2. **The matrix's per-test bookkeeping** (a test that fused under the defaults must fuse under a
   configuration) reads the child's markers through the parent's output; a configuration run of
   one of the seven is the check.
3. **A new suite that needs a second width** fails the guard with a message that names the fix.

## 8. Sequencing

The guard and its census, the trait and the seven suites, the runs at three widths, the notes.

## 9. Outcome

Done on 9 October 2026.

**The census, at three widths.** At 512 bits the guard found seven suites (section 2). Run at 256
and 128 bits it found two more, `VarkaEmitterDriverTableSuite` and `VarkaExactGroupingSuite`, three
tests between them that fix a 16-lane override: at 512 bits that is the preferred species and
harmless, at 256 and 128 it is a second one. Both mix in `VarkaOwnJvm` now. With nine suites in
their own JVMs the full `catalyst/testOnly *Varka*` run (48 suites, 464 tests in the parent, 26
cancelled as before) has no violation at 512, 256 or 128 bits.

**Time, before and after** (the whole run, wall clock): 443 s before (the census run, 562 tests
including the nine suites' own), 538 s at 512 bits, 547 s at 256 and 548 s at 128 after, so 95 to
105 s more, the nine JVM starts. The row's expected gain, the boxed kernels the shared JVM no longer
runs, does not show in a suite of correctness tests and is not claimed.

**The option matrix still reads the forked suites.** `dev/varka_matrix.sh --config cse=false`
over three of them passes both the defaults and the configuration (the child inherits the
configuration and writes the markers through the parent's output).

**Predictions scored.**

1. **Refuted at 256 and 128 bits, held at 512, and held at all three once the two suites were
   forked.** The first runs at 256 and 128 bits failed three tests the 512-bit census had not
   found; the census of one width is a census of that width, which is why the guard runs at all
   of them.
2. **Held.** The run is 95 to 105 s longer, inside the 70 to 150 s predicted, and does not fall.
3. **Held.** Every child passes with the tests it had.

**What it does not do.** Suites already outside the shared JVM (the assembly, cliff, deopt and
width-audit suites, the benchmarks) are untouched. The guard sits where the emitter harness and
`VarkaKernelCheck` define classes; a new test that defines a class by another route is not seen,
and `VarkaSpeciesGuard.check` is the one line to add there. `sql/core` runs the production loader
at the session's width and sets no lanes override, which the census at 512 bits could not show for
a width it was not run at; no `sql/core` run was made at 128 bits here.

### 9.1 Correction after #685's CI, 9 October 2026

The first CI run of #685 failed `Varka suites: catalyst` on a runner whose preferred width is 256
bits, in the option matrix's `lanesOverride=4` configuration: that configuration pins the lane
count to 4 for every suite, so every kernel the JVM defines is at 128 bits, and a guard that
compared with `SPECIES_PREFERRED` called every one of them a second species. The guard was
wrong in kind: the thing to keep is one species per lane type in a JVM, however it is spelled.
`VarkaSpeciesGuard.Registry` now records the size each vector class has used (the preferred one
counts as its size), refuses a class that would add a second size, and registers nothing when it
refuses, so later kernels of the established width pass. The pure `secondSpecies` (against the
preferred) stays for the census. Running the configuration over all catalyst suites then found one
real mix: `VarkaMemorySanitizerSuite`'s harness probe emitted with the emitter's default options,
beside kernels at the configuration's width; it emits with `VarkaMatrix.base` now. The guard's own
tests are tagged `PinsDefaults`, since a configuration changes what "the JVM's own width" means.

All four lane-related configurations (`lanesOverride=4`, `lanesOverride=16`,
`narrowHalfSpecies=true`, `validityByWidth=false`) pass over every catalyst suite, as do the
defaults. The prediction in section 6.1 that the guard finds no violation "at 512, 256 or 128
bits" was about the defaults; it said nothing of the matrix, which is where it failed.

### 9.2 Review of #685 (`/code-review high`), 9 October 2026

Fixed in the PR: the child's timeout never fired (the read loop ends only when the child does; a
daemon thread ends it now, and the child is destroyed on any exit, with a budget of the shortest
per-test cap less a minute, so the parent reports a hang before a cap halts the JVM); the forked
suites became one synthetic test, so a `-z` filter matched nothing and reported green (each suite
keeps its tests by name and tags and takes each result from the child's JUnit report); a stray
assertion in the guard's suite failed it under `-Dvarka.ownJvm=true`; the guard's class doc and
plan 3.1 described the pre-correction rule; `testing-and-debugging.md` was in the files table and
not in the PR (written); `secondSpecies` repeated `sizesUsed`'s walk (derived from it) and the
property's name was written twice (one constant); the child classpath preferred
`SPARK_DIST_CLASSPATH` over the parent's own (the parent's, when it holds ScalaTest). Not changed:
the registry is first-come, so the class that adds the second size is the one blamed; the message
now names the size already established, and the class doc says so.

### 9.3 Correction after row 410, 21 October 2026

9.1's sentence that all four lane-related configurations pass "over every catalyst suite" held on
256-bit runners, where it was measured. On a 512-bit host `lanesOverride=4` failed three tests
with one JVM and 35 with `--split 2` (27 on the CI runner): two suites emitted at the JVM's own
width, `VarkaEmitterFramesSuite` through a lane count of 0 and `VarkaUnreadLocalsSuite` through a
gate on the preferred width, and the guard blames whichever suite runs second. Row 410 fixed
both (`m8/PLAN.md`); the guard is unchanged. Of 9.1's list, `lanesOverride=16`,
`narrowHalfSpecies=true` and `validityByWidth=false` passed at 512 bits before row 410 as well.
