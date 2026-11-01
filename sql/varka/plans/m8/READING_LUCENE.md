# Reading for milestone 8: what Lucene's use of the Vector API teaches Varka

Read on 27 October 2026 at the owner's request: Lucene has shipped Vector API kernels since Java
20, so what does it do per host, around C2, in its tests and across JDK versions that Varka does
not? Paths are relative to `/home/max/proj/lucene` at commit `20ced71` (27 October 2026, "Support
SIMD Faiss library variants with runtime fallback"). The checkout is shallow (one commit), so no
history or blame was read; the reasons behind most choices live in GitHub pull requests outside
the tree. **[V]** means the author read the code; **[I]** means inferred. Nothing was built or
run; no number below was measured here. Not repeated from `READING_JDK.md`, `READING_HIGHWAY.md`,
`m7/READING.md` section 8 or `skills/vector-api-and-width.md`; this note says where Lucene agrees
or differs.

Directories, cited by file name below:
`core25/` is `lucene/core/src/java25/org/apache/lucene/internal/vectorization/`,
`corevec/` is `lucene/core/src/java/org/apache/lucene/internal/vectorization/`,
`util/` is `lucene/core/src/java/org/apache/lucene/util/`,
`tests/` is `lucene/core/src/test/org/apache/lucene/internal/vectorization/`,
`plugins/` is `build-tools/build-infra/src/main/java/org/apache/lucene/gradle/plugins/`.
JDK lines are from `/home/max/proj/openjdk-build/jdk25` (`jdk-25-ga`).

## 1. How Lucene decides per host

1. **One provider per JVM, chosen once [V].** `corevec/VectorizationProvider.java` lines 137 to 180
   pick the scalar `DefaultVectorizationProvider` or the Panama one, held in a static holder (lines
   242 to 246). Lucene declines first on a JDK newer than the upper bound, the outer test (140, 173
   to 178), then: not HotSpot (141 to 146); the `jdk.incubator.vector` module not readable (149 to
   154); outside test mode, a forced test width (158 to 162) or a client VM (163 to 166). The Panama
   constructor can still refuse with an `UnsupportedOperationException`, which the lookup turns into
   the scalar provider and one log line (191 to 194).
2. **The host facts are HotSpot flags, read through the MXBean [V].** `util/HotspotVMOptions.java`
   lines 44 to 87 read any flag through `HotSpotDiagnosticMXBean`. `util/Constants.java` reads
   `UseAVX` (`HAS_AVX2` at 2, `HAS_AVX512` at 3, lines 86 to 91), `UseSVE` (94 to 95), `UseFMA`
   (74 to 75), `MaxVectorSize` (78 to 79) and `UseXmmI2F` as a proxy for "an AMD core" (81 to
   83). The proxy holds: HotSpot sets `UseXmmI2F` only where the CPU has SSE4a
   (`vm_version_x86.cpp` lines 1520 to 1525), which only AMD parts have [I]. Varka agrees on the
   source: `VarkaEmitOptions.java` lines 604 to 638 read `UseAVX` the same way, and an unknown
   level means "no evidence", as Lucene's `orElse(0)` does for a missing flag.
3. **The width is the preferred shape, not a feature list [V].** `PanamaVectorConstants.java`
   lines 37 to 39 take `VectorShape.preferredShape().vectorBitSize()`, so `-XX:MaxVectorSize`
   caps it. Below 128 bits the provider refuses (`PanamaVectorizationProvider.java` lines 44 to
   47).
4. **On x86 below 256 bits Lucene refuses the whole provider [V].** `PanamaVectorConstants.java`
   lines 41 to 46: `isAMD64withoutAVX2 = os.arch is amd64 && preferred < 256`, comment "hotspot
   misses some SSE intrinsics ... they do document this thing only works well with AVX2/AVX3 and
   Neon". So an SSE-only machine, or an AVX2 machine under `-XX:MaxVectorSize=16`, runs scalar.
   The flag is named `ENABLE_INTEGER_VECTORS` but it gates the float kernels too (lines 49 to
   52 of the provider). Aarch64 at 128 bits (NEON) is accepted.
   *Against Varka:* row 503, on its branch `varka-503-two-lane-decline` and not yet merged, gates on
   the same predicate ("`HOST_USE_AVX` reads a level (x86) and `LongVector.SPECIES_PREFERRED` has
   two lanes"), and measured that only a filter's long condition loses while projections win, so
   Varka declines one root kind, not the engine. Lucene's broader rule rests on a comment, not a
   measurement in the tree; Varka's evidence is the stronger.
5. **"Fast" features are separate gates, decided per CPU family [V].** `Constants.java` lines 118
   to 142: vector FMA on aarch64 except macOS ("the problem is just apple silicon"), on AMD only
   with `MaxVectorSize >= 32`, on Intel always. Scalar FMA (144 to 168) on AMD only at
   `MaxVectorSize >= 64` ("latency becomes 4 for the Zen3 ... still a wash until the Zen4").
   Compress and mask cast (170 to 179) only on aarch64 with SVE or amd64 with AVX2. Each has a
   system property that overrides the guess (`lucene.useVectorFMA`, `lucene.useScalarFMA`).
   *Against Varka:* row 347 found "`compress` needs SVE, not 'not NEON'"; Lucene's gate says the
   same on Arm. On x86 Lucene accepts AVX2 for compress, where Varka's census has the long
   `compress` cell `F` at 128 bits on the laptop; the gate is consistent with that, since
   Lucene never runs at 128 bits on x86 (item 4).
6. **Per-kernel lane thresholds [V].** `findNextGEQ` vectorizes on x86 only at 8 or more int
   lanes, "so the overhead of the vector approach plus counting trues on vector masks pays off",
   and on aarch64 at 4 (`PanamaVectorUtilSupport.java` lines 1305 to 1309). This agrees with
   `vector-api-and-width.md`'s AVX2 section (a mask made into or out of a word costs more than a
   cheap kernel's work) but not with row 503's cause, which is C2's `x86.ad` refusing masks at two
   lanes so the Java fallback runs; Lucene's threshold is a cost, row 503's a missing rule **[I]**.
7. **Without C2, no vectors [V].** `Constants.IS_CLIENT_VM` is `java.vm.info` containing
   `emulated-client` (`Constants.java` lines 63 to 65). HotSpot prints that exactly when the
   compilation mode is quick-only (`abstract_vm_version.cpp` lines 151 to 162), which
   `-XX:TieredStopAtLevel=1` sets (`compilerDefinitions.cpp` lines 84 to 86). The lookup then
   returns the scalar provider, and FMA is off too (`Constants.java` line 75).
   *Against Varka:* `VarkaKernelCompileDirective.java` lines 131 to 199 read more flags
   (`UseCompiler`, `UseJVMCICompiler`, `NeverActAsServerClassMachine`, `CompilationMode`,
   `TieredStopAtLevel`), but on `NO_C2` Varka "emits its kernels unwarmed" (lines 102 to 107);
   the only caller is the warm-up's gate (`VarkaKernelWarmup.java` line 270, by grep over
   `sql/catalyst` and `sql/core` main sources), so Varka still fuses [V].
   Every kernel then runs the Vector API's per-lane Java fallback, which allocates every vector.
   Lucene misses `-Xint` (its info string is "interpreted mode", with no `emulated-client`);
   Varka's `UseCompiler` check catches it as `NO_C2`. Neither project reads
   `EnableVectorSupport` [I, from the same source lines].
8. **What Lucene does not key on [V].** No AVX-512 sub-feature (`AVX512DQ`, `VL`, `BW`), no
   SVE vector length beyond `UseSVE >= 1`, no CPU model. `HAS_AVX512` is read but used only by
   the sandbox's Faiss wrapper (`FaissNativeWrapper.java` line 71), not by any kernel [V].
   Varka's keys are finer (row 491's guard and m9 item 115, by mask registers, VL and BW).

## 2. Workarounds for C2, and the JDK bugs cited

The tree names only two JDK bugs beside a workaround. The other workarounds are comments.

1. **JDK-8309727 [V for the code, I for the cause].** `PanamaVectorizationProvider.java` lines 40
   to 42 call `FloatVector.fromArray(SPECIES_PREFERRED, ...)` once in the constructor, "hack to
   work around for JDK-8309727", before any check. The JDK's regression test for that id,
   `test/jdk/jdk/incubator/vector/VectorRuns.java` lines 28 to 33, is a loop of `fromArray`,
   `compare`, `anyTrue` and `toLong` checked against a scalar loop. The bug's title was not read.
   A plausible mechanism is C2's refusal to intrinsify while the vector class is not yet
   initialized (`vectorIntrinsics.cpp` lines 336 to 337, "klass argument not initialized"), which
   an eager first call removes. Lines 33 to 35 of the provider also keep every static that
   touches the API out of the class, in `PanamaVectorConstants`, so a broken module fails inside
   the guarded constructor. Varka agrees on both: `VarkaVectorTemplateWarmup.java` lines 354 to
   366 run before the first vector code, name no API class in their initializer, and absorb a
   missing module. Row 476 (the narrow lanes cold in that warm-up) is the same concern.
2. **JDK-8336000 [V].** `util/VectorUtil.java` lines 371 to 377: on aarch64 `Long.bitCount` is
   not vectorized by C2's auto-vectorizer, so the scalar XOR bit count strides by `int` there and
   by `long` on x86. This is an auto-vectorization fact, not a Vector API one; Varka's
   `BIT_COUNT` is a Vector API lane operator, so it does not apply directly [I].
3. **A conversion operator must be a constant in the compiled method [V].**
   `PanamaVectorUtilSupport.java` lines 411 to 414: the signed and unsigned byte kernels "share
   no method", because "a conversion operator that is not a constant in the compiled method is
   not intrinsified, and C2 makes inlining decisions from per-method profiles: once both kernels
   had run, a shared method was compiled out of line, so whichever kernel ran second stayed much
   slower". Varka's emitted kernels carry their operators as constants [I], so the first half
   does not bite; the second half is the profile pollution behind rows 383 and 404 and the
   two-species section of `vector-api-and-width.md`.
4. **Avoid the 32-bit multiply on 512-bit registers [V].** Lines 479 to 482 widen bytes to
   shorts, multiply as 16 bits and widen the product to ints ("avoid AVX-512 heavy multiply on
   zmm"); the 256-bit body multiplies as 32 bits (lines 513 to 526). Lines 1027 to 1028 leave
   the question open for square distance, in a to-do comment: it "uses AVX-512 heavy multiply on
   zmm, should we just use 256-bit vectors on AVX-512?". No number is given.
5. **APIs that are fast only on some hosts are banned unless gated [V].**
   `gradle/validation/forbidden-apis/non-standard/incubator-vector.txt` lines 1 to 21 forbid
   `fma` on every vector type ("FMA may fallback to BigDecimal"), `compress`, `expand`,
   `COMPRESS_BITS`, `EXPAND_BITS` and `VectorMask.cast`; each use carries a
   `@SuppressForbidden` and a `Constants` gate (`PanamaVectorUtilSupport.java` lines 96 to 104,
   1587 to 1602). The BigDecimal claim holds for scalar `Math.fma`: its Java body is BigDecimal
   arithmetic (`Math.java` lines 2380 to 2500), which is what runs where `UseFMA` is off [V].
6. **Tails without masks [V].** Lucene never uses a masked load or store. Tails are scalar
   loops (for example `PanamaVectorUtilSupport.java` lines 126 to 129), or one extra full vector
   read aligned to the right end of the data, overlapping the last one
   (`MemorySegmentPostingDecodingUtil.java` lines 75 to 81). At 128 bits, where no 32-bit byte
   species exists, the byte kernels load 64 bits and use the first half only, advancing by four
   bytes ("overlapping read", lines 431 to 433 and 545 to 563). *Against Varka:* the masked
   epilogue is Varka's design, and `vector-api-and-width.md` already found that a mask costs the
   loop, not the instruction; the right-aligned read is a candidate only where a kernel is
   idempotent per row, which a fused store into the output buffer is [I].
7. **Overflow bounded by chunking [V].** The int4 kernels accumulate in 16-bit lanes and restart
   every `CHUNK` bytes, 8192, 4096 or 2048 by width, so the short accumulator cannot overflow
   (lines 586 to 605 and 637 to 641). Varka's narrow lanes (row 330) prove each operation's range
   instead; the chunk is the same reasoning applied to a reduction.

## 3. Allocation, boxing and shapes avoided

1. **Several species of one lane type in one class [V].** `PanamaVectorUtilSupport.java` holds
   `ByteVector.SPECIES_128` and `SPECIES_256` (lines 73 to 74), a full-width byte species (76 to
   78), a quarter-width byte species and a half-width short species (86 to 89), `SPECIES_64` in
   the 256-bit body (515 to 517), and per-width `Int4Constants` (586 to 605).
   `int4BitDotProduct256WithOffset` runs a 256-bit loop and then a 128-bit loop of `LongVector`
   in one method (lines 1392 to 1440), whatever the host's width. On an AVX-512 host several of
   these byte species are hot in one JVM [I]. This **contradicts** the rule in
   `vector-api-and-width.md` ("never introduce a second species of a lane type"), and nothing in
   the tree reports a box. Varka's measurement is the stronger evidence: the skill shows boxing
   depends on the operator and on the order profiles fill (a `selectFrom` boxes only when the
   second species ran hot first), and Lucene's byte kernels are loads, conversions, multiplies
   and adds, the operators the skill found unaffected [I]. Lucene's guard is at the method level
   instead: one method per kernel and width (item 2.3), and a benchmark that measures each kernel
   after all the others have compiled (section 4, item 6).
2. **A bimorphic loader inside the loop [V].** The byte kernels take a `ByteVectorLoader`
   interface with two record implementations, an array and a `MemorySegment` (lines 339 to
   383), and call `load` inside the vector loop. Once both are used, that call site is
   bimorphic [I]; Lucene accepts this, and no comment says it was measured.
3. **Per-call allocations Lucene accepts [V].** `cosineBody` returns a `float[3]` (lines 236 to
   240); `PanamaDocValuesRangeSupport.java` allocates `new long[vectorLen]` per call (line 47) and
   fills it one value at a time through a virtual `values.get` before `fromArray` (lines 51 to
   55). These are per call, not per vector, the distinction VARKA-55 drew.
4. **Thresholds before vectorizing [V].** Float kernels vectorize only when the array is longer
   than twice the lane count (lines 120 to 123, 1633); byte kernels at 16 bytes (422) and int4 at
   32 (624); bulk decode needs a 32-byte byte species (`PanamaDocValuesBulkDecodeSupport.java`
   lines 35 to 41). Varka's batches are long, so this matters only to its small-batch paths [I].
5. **Fixed unrolling with independent accumulators [V].** Dot product and square distance keep
   four accumulators, "we don't know how many the cpu can do at once, some can do 2, some 4"
   (lines 136 to 163); cosine keeps two because "each iteration has 3 FMAs" (204 to 205). This is
   the loop-carried chain that `vector-api-and-width.md`'s AVX2 section names, broken by hand.
6. **Masks become words only after the compare [V].** `PanamaDocValuesRangeSupport.java` lines
   56 to 61 and 104 to 110: two compares, an `and`, one `toLong`, and an OR into the bit set
   only when the word is non-zero. Lucene never builds a mask from a word (`fromLong`), so it
   never meets the JDK-8378968 shape row 491 works around [V, by grep over `core25/`].

## 4. Tests: vector against scalar, widths, and CI

1. **The duel [V].** `tests/BaseVectorizationTestCase.java` lines 24 to 41 hold the scalar
   provider and `VectorizationProvider.lookup(true)`, and skip the class when they are the same
   class. `tests/TestVectorUtilSupport.java` runs every kernel on both and compares: integers
   exactly (lines 386 to 396), floats within `1e-5 * size`, scaled by the larger result (lines
   41 to 42 and 377 to 384). Sizes are a fixed list of 22, odd ones and ones just past a power
   of two among them (lines 31 to 34), and inputs are random plus boundary fills with
   `Byte.MIN_VALUE` and `MAX_VALUE` in all four pairings (lines 72 to 99).
2. **Width forced by a property [V].** `tests.vectorsize` replaces the preferred width
   (`PanamaVectorConstants.java` lines 37 to 39) and lifts the x86 refusal (line 46). The build
   picks it at random from the seed among `default`, 128, 256 and 512
   (`plugins/java/TestsAndRandomizationPlugin.java` lines 629 to 642), so one run in four keeps
   the host's width. A species wider than the host's is not intrinsic and runs the Java fallback,
   which the class doc calls "EXTREMELY slow" (`PanamaVectorUtilSupport.java` lines 46 to 56);
   so a forced width tests the kernel's logic, not C2's code for that width [I]. Two classes do
   not follow the property at all: `PanamaDocValuesRangeSupport.java` line 31 and
   `PanamaDocValuesBulkDecodeSupport.java` line 28 use `SPECIES_PREFERRED` directly [V].
3. **Production refuses a forced width [V].** Outside test mode a set `tests.vectorsize` gives
   the scalar provider (`VectorizationProvider.java` lines 158 to 162). So every caller that
   goes through `getInstance()` - `VectorUtil` (`util/VectorUtil.java` line 53) and the codecs -
   is scalar in a forced-width run, and only the tests that call `lookup(true)` see the Panama
   code [V]. `TestVectorUtil`'s `findNextGEQ` and `filterByScore` checks call `VectorUtil`
   (`lucene/core/src/test/org/apache/lucene/util/TestVectorUtil.java` lines 467 and 498).
4. **GitHub CI runs without C2 [V].** `.github/workflows/run-checks-all.yml` lines 147 to 152 set
   `TEST_JVM_ARGS` to `-XX:TieredStopAtLevel=1 -XX:+UseParallelGC -XX:ActiveProcessorCount=1`,
   with the comment "we can't afford C2 on github runners"; the same flags are the local default
   (`TestsAndRandomizationPlugin.java` lines 139 to 152) unless a `CI`, `JENKINS` or `HUDSON`
   variable is set (`plugins/globals/RegisterBuildGlobalsPlugin.java` lines 58 to 60). Under
   those flags `IS_CLIENT_VM` is true, so production lookups are scalar (item 1.7), and the
   duel's `lookup(true)` runs the Panama kernels under C1, that is, the Vector API's Java
   fallback [I, from section 1 item 7 and `m7/READING.md` section 8]. The one workflow that
   runs `lucene:core`'s tests forces C1; the module workflows keep C2 (GitHub sets `CI`) but run
   other modules (`run-checks-mod-distribution.tests.yml` line 49,
   `run-special-checks-sandbox.yml` line 72), and the Jenkins jobs are not in the tree.
   A developer gets C2 and the host's width with one switch, `-Ptests.defaultvectorization=true`
   (`TestsAndRandomizationPlugin.java` lines 149 and 634 to 642). GitHub's matrix is
   `ubuntu-latest`, `ubuntu-26.04-arm`, `windows-latest` and `macos-latest` on JDK 25
   (`run-checks-all.yml` lines 106 to 115), and nothing forces an instruction set.
   *Against Varka:* row 505's axis (`-XX:UseAVX=2`, `MaxVectorSize=16`, Arm, all with C2) is
   stronger than anything Lucene runs in the tree. Lucene's local-and-GitHub configuration is
   the trap `vector-api-and-width.md` names: the fallback checked against scalar, C2 never.
5. **Over-read caught by an exact-size slice [V].** `tests/TestPostingDecodingUtil.java` lines 63
   to 66 read from a slice "that has just the right number of bytes to make the test fail with
   an index-out-of-bounds in case the implementation reads more than the allowed number of
   padding bytes". Varka meets the same with Arrow buffers sliced to their exact length
   (`skills/testing-and-debugging.md`, "Arrow promises no padding").
6. **A pollution benchmark [V].** `lucene/benchmark-jmh/src/java/org/apache/lucene/benchmark/jmh/
   VectorUtilMixingBenchmark.java` lines 36 to 39 and 55 to 79: `pollute=none` measures each
   kernel in a fresh fork, `pollute=all` first runs all four kernels 100,000 calls each, twice,
   "long enough for C2 to compile it ... a few calls only reach the interpreter". Lines 126 to
   153 call each kernel from its own `DONT_INLINE` method, "so the result does not depend on how
   many kernels C2 can inline into one benchmark method". This is the shape row 404 asks for.

## 5. The incubator module across JDK versions

1. **Compiled against an extracted stub jar [V].** `plugins/mrjar/ExtractJdkApis.java` lines 68
   to 74 copy `jdk.incubator.vector` and `jdk.internal.vm.vector.VectorSupport` class files for
   JDK 25 into `lucene/core/src/generated/jdk/jdk25-api.jar`. `LuceneJavaCoreMrjarPlugin.java`
   lines 102 to 104 drop `--release` and lines 119 to 126 compile with `--add-exports
   java.base/jdk.incubator.vector=ALL-UNNAMED`, "we patch the incubator packages into java.base,
   this has no effect on resulting class files". The `java25` source set is the base version
   (`gradle/libs.versions.toml` line 41, `minJava = "25"`), so its classes go to the jar's root,
   not under `META-INF/versions` (plugin lines 145 to 160) [V]. Varka compiles against the
   running JDK's module, so it needs no stub [I].
2. **A runtime upper bound [V].** The Panama provider is used only up to feature version 27
   (`VectorizationProvider.java` line 68); later JDKs get the scalar provider and a warning
   (lines 173 to 178) unless `org.apache.lucene.vectorization.upperJavaFeatureVersion` raises the
   bound (lines 66 to 85). The reason is that an incubator API may change between releases [I].
3. **Module presence is checked, then read [V].** Lines 149 to 155 find the module in Lucene's
   own module layer, else the boot layer, and `addReads` it. The build adds `--add-modules
   jdk.incubator.vector` to tests on JDK 21 to 27 (`TestsAndRandomizationPlugin.java` lines 357
   to 359 and 453 to 464). A module found in a non-boot layer is readable but, per m9 item 26's
   source reading, `EnableVectorSupport` is set only when the module is defined to the boot
   loader at startup, so such a JVM would run the fallback with no warning [I].

## 6. Numbers the tree cites

Few, and none for the kernels' own speed.

* `findNextGEQ` on aarch64 at 4 int lanes: "+75% on Graviton4 and +35% on Graviton2
  (AdvanceBenchmark)" (`lucene/CHANGES.txt` lines 249 to 251) [V].
* Postings moved from `long[]` back to `int[]` "to take advantage of 2x more lanes with the
  vector API" (`lucene/CHANGES.txt` lines 2102 to 2106) [V].
* The vectorizing thresholds of section 3 item 4 and the CHUNK sizes of section 2 item 7 are
  stated without a measurement [V].

## For Varka

Each takeaway with the row or item it serves, or the reason it is not worth taking. Nothing in
this note edits a row.

1. **x86 below 256 bits declined as a whole (1.4).** Serves row 503 (on its branch) and m9 items 101
   and 120. Lucene agrees on the predicate; keep Varka's narrower rule, which is measured.
2. **No vectors without C2 (1.7).** Serves m9 item 120 and the startup-decline candidate in
   `skills/the-jit.md` ("With the intrinsics off"). Candidate m9 item: decline fusion at session
   start when the JVM has no C2 or no intrinsics (`EnableVectorSupport` false), with the reason
   in `EXPLAIN`, instead of only skipping the warm-up.
3. **The mask-to-word step needs 8 int lanes on x86, 4 on Arm (1.6).** Serves m9 item 101 as
   supporting evidence (not row 503, whose cause differs); the Arm half says the 128-bit Arm runner
   needs no decline.
4. **Feature gates per CPU family behind overridable properties (1.5).** Row 505. Not worth
   taking as heuristics: Varka measures per host class, which is stronger.
5. **An eager first call and no API statics outside the guard (2.1).** Row 476. Agrees; the
   narrow lanes join the warm-up as 476 says.
6. **Constant operators, one method per kernel, and a pollution benchmark (2.3, 4.6).** Rows
   383 and 404. Take the benchmark shape for 404: a fresh-fork arm and an all-compiled arm,
   each kernel behind `DONT_INLINE`, so the reference arm's rate is read in both states.
7. **A 16-bit multiply instead of a 32-bit one at 512 bits (2.4).** Row 330. Supports NATIVE
   short multiply over the widened int one [I]; worth one cell in 330's measurement.
8. **A forbidden-API list for host-dependent operators (2.5).** m9 item 123. Take:
   a lint that fails an emitted `fma`, `compress`, `expand` or `VectorMask.cast` that is not
   behind a named gate.
9. **The right-aligned overlapping tail (2.6).** No row. Not worth taking now: Varka's masked
   epilogue is measured, and the read suits only idempotent shapes.
10. **Several byte species in one JVM with no box reported (3.1).** Row 329 and the skill
    `vector-api-and-width.md`. No change: the skill's measured cases stand, and Lucene's
    operators are the ones the skill found unaffected [I].
11. **`fromLong` never used (3.6).** Row 491 and m9 item 114: why Lucene never met
    JDK-8378968. It also serves m9 item 101's first option: compare, `and`, `toLong`, then
    word arithmetic, with no masked load or store and no word made into a mask, is "validity
    carried as a long" by another name.
12. **An exact-size slice, MIN and MAX fills, an odd-size list (4.1, 4.5).** Rows 499 and 504.
    Take the odd-size list for batch lengths just around each lane count; Varka has the rest.
13. **A relative float delta of `1e-5 * size` (4.1).** Row 345. Not worth taking: a foil like
    Highway's; Varka's bound is a step count.
14. **A forced width runs the fallback, and GitHub CI runs C1 only (4.2 to 4.4).** Row 505 and
    `m7/READING.md` section 8. Varka's axis is already stronger; the lesson is to keep C2 on
    every CI arm and never trust a forced species wider than the host. Lucene does run its
    whole suite on `ubuntu-26.04-arm` (`run-checks-all.yml` lines 113 to 115), which agrees
    with 505's Arm axis.
15. **A stub jar and an upper JDK bound raisable by a property (5.1, 5.2).** m9 item 114. Take
    the bound: refuse fusion on a feature version Varka has not tested, raisable by a conf.
16. **JDK bug ids beside each workaround (2.1, 2.2).** m9 item 123. Agrees; Lucene keeps them as
    comments only, which is what 123 improves on.
17. **FMA chosen per host (1.5).** The floating rows. Not worth taking: it changes the bits by
    host, and Varka's answers follow Spark's.
18. **The native FFM provider (`NativeVectorUtilSupport.java` lines 272 to 283).** No row. Not
    worth taking: a C library behind `invokeWithArguments`, outside the emitted-kernel model.

Not read: the scorer classes beyond their comments, `NativeVectorUtilSupport`'s descriptors, the
Jenkins job definitions (not in the tree), the GitHub pull requests behind the comments, and
`lucene/test-framework`, which has no vectorization code (grep for `vectorsize` and
`VectorizationProvider` finds nothing).
