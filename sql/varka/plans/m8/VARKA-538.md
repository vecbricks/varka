# VARKA-538: The conversion form chosen by the JVM's own instruction set

## 0. What exists now (admission check)

From the code at `18f6565d073` (31 October 2026; Varka paths under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named) and
the local `jdk-25-ga` source at `/home/max/proj/openjdk-build/jdk25/src/hotspot/` (HotSpot paths
below are relative to it). The row comes from row 30's plan, `VARKA-30.md` 0 item 14.

**What Varka has.**

1. **The predicate reads a requested level, not the host.** `convertsFallBack()` is `useAVX !=
   USE_AVX_UNKNOWN && useAVX < 3` over the record's own `useAVX` component
   (`varka/VarkaEmitOptions.java:609-621`). Its comment says the level and the feature are not the
   same question and leaves `dev/varka_canary/L2DProbe.java` to settle a machine (`:594-602`).
2. **Production never sets the level.** `DEFAULTS` holds `USE_AVX_UNKNOWN` (`:644-652`), on
   purpose: a default read from the machine would make the shape hashes and the committed bytes
   describe one host (`:251-264`, `VARKA-88.md` 9.3). The only other writer is the session:
   `VarkaSessionEmit.apply` lays `spark.sql.codegen.varka.emit.useAVX` over the base options only
   when it is set (`sql/core/.../execution/VarkaSessionEmit.java:96-107`), and the conf defaults
   to -1 (`SQLConf.scala:3110-3126`). So **in production `convertsFallBack()` is false on every
   host**, the EPYC 7763 included, and a 64-bit constant division takes the conversion form
   there. `VarkaShapeCacheSuite.scala:346-349` pins that the default selects nothing from the
   machine.
3. **What that costs on AVX2.** Row 121 measured it with the session level unset under
   `-XX:UseAVX=2`: `hour(t)` at 132.4 M rows/s, under a seventh of its rate at the laptop's own
   level (`VARKA-121.md` 5.1, `TimeSurface-varka-jdk25-avx2-results.txt`). Its second arm, the magic
   form on the same host, never ran (row 121 is "Switch landed, first arm measured"), and its
   section 6 left the production default on AVX2 "ships separately if at all".
4. **One lowering reads the predicate.** `takesMagicDivide` (`varka/VarkaDivisionLowering.java:
   344-362`): the long lane's constant division takes the magic form where it holds. The other
   readers are tests: `VarkaMatrixRuns.emissionKey` folds every level below 3 into
   `FALLBACK_USE_AVX = 2` and the rest into the defaults' (`VarkaMatrixRuns.java:229-243`),
   `VarkaEmittedBytesSuite.scala:690-711` holds the fold to the bytes, and the shape-cache test.
5. **One place reads the level as a host fact.** `VarkaWidthAuditSuite.knownBelowAvx512` expects
   the `L2D`/`D2L` refusal where the probe's `UseAVX` is below 3 (`VarkaWidthAuditSuite.scala:
   226-245`); the probe prints `HOST_USE_AVX` (`VarkaWidthAuditProbe.scala:52`, `:77`).
6. **A second level reader answers another question.** `VarkaMaskGuard.OPEN` reads
   `HOST_USE_AVX` (`varka/VarkaMaskGuard.java:55`, `:65-68`) for C2's mask representation and
   JDK-8378968, not for conversions. It is not this row's.
7. **A stale comment.** `VarkaTimeBenchmark.java:158` calls the magic form the one "an AVX2-only
   host takes without asking"; per item 2 no host takes it without asking.

**What HotSpot does.**

8. **The path.** `inline_vector_convert` asks `arch_supports_vector` for the cast opcode at the
   target's lane count and type (`share/opto/vectorIntrinsics.cpp:2337-2343`), which asks
   `Matcher::match_rule_supported_vector` (`:183-204`); a refusal leaves the Vector API's Java
   fallback, which is correct and allocates.
9. **x86, per family** (`cpu/x86/x86.ad`):

   | conversion | refused when | source |
   |---|---|---|
   | every `VectorCast*` | `UseAVX < 1` | `:1414-1428` |
   | `I2L`, `I2F` (and `B2X`, `S2X`) at 256 bits | `UseAVX < 2`; `I2D` exempt | `:1752-1756` |
   | `L2I` (and `L2B`, `L2S`) at 256 bits | `UseAVX < 2` | `:1757-1759` |
   | `L2F`, `L2D` | `!supports_avx512dq()` | `:1760-1762` |
   | `F2I` from a 256-bit source | `UseAVX < 2` | `:1764-1771` |
   | `F2L`, `D2L` | `!supports_avx512dq()` (`F2X` falls through) | `:1773-1777` |
   | `F2D`, `D2F`, `D2I` | `UseAVX < 1` only | as above |

   So the four 64-bit floating conversions are keyed on a feature, AVX512DQ, and every other one
   on a level that every AVX2 host passes. The DQ instructions are encoded at the source's width
   (`evcvtqq2pd`, `:7607-7611`), which below 512 bits wants AVX512VL; the match rule does not
   ask for VL, and no CPU HotSpot gives DQ lacks VL **[I]**.
10. **Where DQ comes from.** `UseAVX < 3` clears every AVX-512 feature, DQ and VL included
    (`cpu/x86/vm_version_x86.cpp:996-1013`). On Intel, the Knights family (Xeon Phi) clears DQ,
    VL and BW at level 3 (`:1046-1051`); `is_knights_family()` is true also under the diagnostic
    `-XX:+UseKNLSetting` (`vm_version_x86.hpp:801`, `globals_x86.hpp:111-112`), on Intel only.
    So **below level 3 the level is exact**, and the level misses only a level-3 JVM without DQ:
    Xeon Phi, or a virtual CPU that reports AVX-512F without DQ **[I]**. Neither is a Varka host.
11. **The JVM tells Java its features.** The feature string is built after the clearing
    (`vm_version_x86.cpp:1107-1111`) and returned by `VectorSupport.getCPUFeatures()`
    (`share/prims/vectorSupport.cpp:618-621`), a public method of a class in `java.base`'s
    unexported `jdk.internal.vm.vector`. The Vector API reads `avx512dq` from it itself
    (`jdk.incubator.vector/.../CPUFeatures.java:39`, `:69`, package-private). Spark passes no
    `--add-exports` (`launcher/.../JavaModuleOptions.java:28-50`), so Varka cannot call it today.
12. **aarch64 has a rule for every conversion.** `match_rule_supported_vector` has no conversion
    case (`cpu/aarch64/aarch64_vector.ad:152-160`); NEON caps vectors at 16 bytes, and NEON's
    `L2F` and `D2I` are per-lane instructions inside the vector code (`:4468-4482`), still
    matched. The exclusion at `:129-147` is for auto-vectorization only. So "unknown converts"
    is right on aarch64.

**What the census holds.**

13. **The cells agree with HotSpot on every host.** The laptop (Zen 5, `UseAVX=3`, DQ) reads
    `L2D`, `D2L`, `L2F` and the int conversions `I` at 512, 256 and 128 bits
    (`operator_census.json`); the EPYC 7763 reads `L2D` and `D2L` `F` (56 and 80 bytes a row at
    256 and 128) and `L2F` `F` (62 and 88), the rest `I`; the Neoverse N2 reads all `I` at 128
    (`results/VARKA-376-census-epyc-7763.json`, `-neoverse-n2.json`). There is no `F2L` cell; row
    343's step 2 adds it.
14. **The census records the CPU, not the features.** Its `host` block holds `cpu`, `arch` and
    `jdk` (`VarkaOperatorCensusSuite.java:524-527`), and the committed file is compared only on
    the CPU that wrote it (`:555-570`). Its probe forks take JVM flags (`:416`).

**Who relies on it.**

15. **Rows 30, 343 and 344 read the predicate as "the host".** `VARKA-30.md` 2.3: "The host is
    `convertsFallBack()`", its `BIGINT` column forms on AVX2 read only where it holds.
    `VARKA-343.md` 0 item 5 and 2.3 the same, and 343a declines `L2D` and `L2F` "where
    `convertsFallBack()` holds" (its 4). `VARKA-344.md` 0 item 13 the same for its `FLOOR` and
    `CEIL` forms. Their facts agree with item 9 (343's AVX512DQ rule, its `F2L` fallback on the
    EPYC); their premise does not hold, by item 2.

### What differs from the row text

- **The defect the row names cannot happen in production.** No host is read (item 2), so no
  AVX-512 host "reads level 3"; a level 3 arrives only from a session or a test, and an explicit
  request is not wrong about the host. The defect that does happen is on AVX2: every AVX2 host
  takes the conversion form and its Java fallback (items 2, 3, 13).
- **The level is exact below 3** (item 10). The gap the row names is level 3 without DQ, a Xeon
  Phi class Varka does not run on.
- **The row is the production default or it is a comment.** Its step 4 (bytes unchanged, empty
  rendering kept) holds with a host-blind default, which leaves users where they are; the per-host
  tables of rows 30, 343 and 344 need the default to read the host. Question 1.
- **The host fact cannot be read today without a module export** (item 11). Question 2.
- **The fact cannot enter `DEFAULTS`** or the committed bytes become per host (item 2). It enters
  where the session's options are resolved, and the tests pin it off (2.2).
- **`VarkaMaskGuard` stays** (item 6), and the int conversions need nothing (item 9).
- **The census cells stand in for `L2DProbe`.** The row's step 3 holds the predicate to
  `L2DProbe`'s verdict; the census reads the same refusal per cell and width, and is already
  run on each host class (2.4).
- **Size: medium**, not small, with the default; small without it.

## 1. Goal and non-goal

**Goal.** Where the JVM running the driver lacks AVX512DQ, a session that sets no level emits the
form its conversions need: the magic form of the 64-bit constant division today, and the AVX2 forms
of rows 30, 343 and 344 when they land. The fact is HotSpot's own feature set, unknown answers keep
today's conversion form, and every committed file and test stays host-independent.

**Non-goal.** Reading the host inside `DEFAULTS` or the emitter; the mask guard; the int
conversions; a host below AVX2; per-executor forms on a mixed cluster (2.2).

## 2. Design

### 2.1 The host fact

A small final class, `varka/VarkaHostIsa.java`, reads the fact once, lazily, in a holder so that
nothing pays for it unless asked:

- `conversionsFallBack()`: true only when `os.arch` is `amd64` or `x86_64`, the feature string was
  read, and it lacks `avx512dq`. The string comes from `VectorSupport.getCPUFeatures()` called
  reflectively, so no javac flag is needed, with `--add-exports=java.base/jdk.internal.vm.vector=
  ALL-UNNAMED` at run time (question 2). Any failure - no export, no such method, a JVM that is not
  HotSpot - answers false, as the predicate does today for an unknown level.
- `features()`: the string itself, or empty, for the census and the width audit to print.

The export goes where Spark's runtime options live: `JavaModuleOptions.DEFAULT_MODULE_OPTIONS`,
the sbt test `javaOptions` (`project/SparkBuild.scala:347-350`), Maven's `extraJavaTestArgs`
(`pom.xml:331`) and the census and width-audit probe forks. The benchmark and option-matrix
workflows run through sbt and take the sbt options. A miss degrades silently to today's form, so
the resolver logs once, on the driver, when the feature query is not accessible.

### 2.2 Where the fact enters

`convertsFallBack()` stays a pure function of the record; a record that varied by host under one
rendering would hand one class name two bodies (`VARKA-88.md` 9.3). The fact enters once, on the
driver, in `VarkaSessionEmit.of(conf)`: when the session sets no level and the host's conversions
fall back, the record's `useAVX` is `2`, the level `emissionKey` already uses for every fallback
host (shape (a), question 3). The plan carries the record to the executors, so the compile at
planning and the evaluator's compile agree, which 343a's decline needs (it changes admission, not
only bytes).

No new conf: `spark.sql.codegen.varka.emit.useAVX` carries the choice. Its default becomes `-2`,
"resolve from the host"; `-1` keeps today's meaning, the emitter's own default with no resolution,
so one setting reproduces today's record on any host; `2` and up stay explicit levels. The
resolution happens where the session's conf becomes `VarkaEmitOptions`, never in the emitter, so
the catalyst tests, which have no session, and 502's reproduction files, which carry an explicit
level, are untouched.

Every Varka SQL test session pins `-1`, so the sql suites, the reach record and the generated files
(`coverage.json`, `docs/sql-varka.md`) stay the same on every runner CPU: the PR Build's runners
mix the EPYC 7763 with AVX-512 Xeons **[I]**, and a record written on one and read on another must
not change meaning. The pin is explicit, not a `spark.testing` check, which would leave the
production default unexercised outside test 3.6. It goes in `TestSQLContext.overrideConfs`, which
reaches every `SharedSparkSession` suite, and in each test helper that builds its own session:
`VarkaSharedSessions`, `VarkaArrowSessions`, `VarkaAutoRegistrationSuite`,
`VarkaFallbackReasonSuite`, `VarkaSizeLadderJitProbe` and `CaseWhenInliningProbe` (from a grep of
`SparkSession.builder` and `newSession()` over the sql/core and sql/hive test trees; sql/hive
builds no Varka session). The benchmarks keep the default, since they measure what a user gets,
and an A/B sets the level explicitly as it does today. One suite leaves the default on (3, test 2).

Consequences, stated in the conf's doc: on a mixed cluster the driver's host decides, and both
forms are correct; an explicit `useAVX` wins over the host; on the laptop and the Neoverse N2
nothing changes, and on an AVX2 host the shape key renders `|2|`, as a session at level 2 does
today.

### 2.3 The code that reads the level for the same question

- `VarkaWidthAuditSuite.knownBelowAvx512` keys on the probe's printed `conversionsFallBack()`
  instead of `UseAVX < 3`; the probe prints the feature string beside `UseAVX`.
- `VarkaMatrixRuns.emissionKey` and `FALLBACK_USE_AVX` stay: the record has no new component.
- The javadocs of `convertsFallBack`, `useAVX`, `takesMagicDivide` and the `useAVX` conf say that
  the level is a target, below 3 exact by HotSpot's clearing, and that the host enters through the
  session; `VarkaTimeBenchmark.java:158` is corrected.

### 2.4 The census holds the answer

The census suite gains a test that holds `VarkaHostIsa.conversionsFallBack()` in the probe's JVM to
the live census's `long.L2D`, `double.D2L` and `long.L2F` cells (and `F2L` once row 343 adds it): a
fallback answer exactly where the cells read `F`, at every width the host runs. It runs at the
host's own flags and in one fork under `-XX:UseAVX=2`, where HotSpot clears DQ (item 10), so the
laptop checks both answers against measured cells. The census `host` block gains the JVM's
`use_avx` and its feature string, so each committed file carries the reason beside its verdicts.

### 2.5 What the consumers need

| row | needs from this row | before |
|---|---|---|
| 343 | 343a declines `L2D` and `L2F` where `convertsFallBack()` holds; without 2.2 that never fires in production and AVX2 users get the Java fallback; its coverage rows run at the pinned (host-blind) session | 343a's code |
| 30 | its `longColumnDivide` arms read only where `convertsFallBack()` holds; nothing else | its AVX2 arm, after 343b |
| 344 | its AVX2 `FLOOR` and `CEIL` forms, the same | its AVX2 forms, after 343b |

None of them needs a new predicate: each needs the existing one fed the host. Each plan's sentence
"the host is `convertsFallBack()`" becomes true when this row lands, and the build adds "once row
538 feeds it the host" to their 2.3 and 0 items in the same pull request. So this row lands before
343a's code (question 4).

## 3. Tests and proofs

1. **The resolver.** `VarkaHostIsa` over planted feature strings: with `avx512dq` false, without it
   true, empty false, on a non-x86 `os.arch` false; and the reflective read failing (a JVM without
   the export) answers false. On x86 the real read returns a non-empty string, as the shape-cache
   test asks of `HOST_USE_AVX`.
2. **The session.** `VarkaSessionEmit.of` with the default level and a fallback host gives
   `useAVX = 2`; off, or with an explicit level, gives what it does today; a fallback host is
   planted through a test seam, not the JVM. A `VarkaEvaluatorComponentsSuite` case: a constant
   `BIGINT` division planned and run under the planted host emits the magic form on driver and
   executor alike, against the reference.
3. **The simulated AVX-512 host without DQ.** The planted string `avx512f` without `avx512dq` at
   `UseAVX=3` answers fallback and the session takes the magic form. No AMD host can make the JVM
   itself report this (`UseKNLSetting` is Intel-only, item 10), so it is a planted fact.
4. **The census test** of 2.4, at the host's flags and under `-XX:UseAVX=2`.
5. **The width audit** passes on the laptop and the EPYC 7763 with the new key, and fails when the
   key is planted wrong (a fallback host reported as converting).
6. **The real path, nothing planted.** One sql test at the default level: the session's
   options answer `convertsFallBack()` as `VarkaHostIsa.conversionsFallBack()` does on the running
   JVM, and a constant `BIGINT` division planned and run through the session matches the reference.
   It passes on every CPU and runs the real export, the reflective read and `of(conf)` on whatever
   runner the Build draws: the magic form on an EPYC 7763, the conversion form on an AVX-512 host.
7. **Unchanged.** `VarkaEmittedBytesSuite`, `width_audit.json`, `operator_census.json`'s verdicts,
   `coverage.json` and the shape-cache test, with the config audit suite for the conf's new default.

## 4. Predictions and costs

1. **The default is worth having.** On the EPYC 7763 at 256 bits, every int64 conversion-form row
   of `VarkaTimeBenchmark` is at least twice as slow as its magic-form row, by minimums (row 121's
   prediction 2, never scored). If it is not, the default does not ship (question 1).
2. **Nothing committed moves**, on the laptop or on either runner class, since the tests pin the
   resolution off and `DEFAULTS` is untouched.
3. **The host answers**: the laptop converts; the laptop under `-XX:UseAVX=2` and the EPYC fall
   back; the Neoverse N2 converts; each agrees with its census cells.
4. **Cost.** The read is one native call per JVM; the census test adds one probe fork, a few
   seconds. The work: the resolver, the export in four places and the session, a day; the census
   and width-audit changes, half a day; the measurement and the doc corrections, half a day. About
   two days: medium.

## 5. Order, files and size

1. **The measurement first, its own pull request on unchanged code**: `VarkaTimeBenchmark`, which
   already has the conversion and the magic arms, on the EPYC 7763 through `benchmark.yml` with
   `expected-cpu` set (the workflow runs a catalyst class, `.github/workflows/benchmark.yml:
   488-491`); the results file committed and prediction 1 scored. This is the number question 1
   waits on.
2. **The row**, one pull request: 2.1 to 2.4, the consumers' sentences, tests 1 to 7, verified by
   the PR Build and a dispatched option matrix.

| file | what |
|---|---|
| `varka/VarkaHostIsa.java` | the fact, 2.1 |
| `sql/core/.../VarkaSessionEmit.java`, `SQLConf.scala` | the resolution and the `-2` default of `emit.useAVX`, 2.2 |
| `TestSQLContext.scala` and the six session helpers of 2.2 | the test pin at `-1` |
| `launcher/.../JavaModuleOptions.java`, `project/SparkBuild.scala`, `pom.xml` | the export |
| `varka/VarkaEmitOptions.java`, `varka/VarkaDivisionLowering.java` | javadocs only |
| `VarkaWidthAuditSuite.scala`, `VarkaWidthAuditProbe.scala` | 2.3 |
| `VarkaOperatorCensusSuite.java`, `VarkaOperatorCensusProbe.java` | 2.4 |
| `VarkaTimeBenchmark.java` | the comment |
| tests | 3.1 to 3.3 and 3.6; the Varka SQL test sessions' conf |
| plans | `VARKA-30.md`, `VARKA-343.md`, `VARKA-344.md`: one sentence each |
| generated | `operator_census.json`'s `host` block |

Size: medium, two pull requests.

## 6. Out of this row

The mask guard's level (item 6, `m9/SCOPE.md` item 114); per-executor forms on a mixed cluster;
hosts below AVX2, where the int conversions fall back too (item 9); a level-keyed lowering added
later that would need AVX-512F apart from DQ, which shape (a) would misread on a Xeon Phi; the
runner files of the census, regenerated by their own workflow when 343 adds its cells.

## 7. Questions for the owner

1. **Does this row ship the production default?** (a) Yes: a session that sets no level takes the
   host's conversion form, gated on prediction 1. (b) No: the predicate's meaning is documented,
   and rows 30, 343 and 344 say their AVX2 forms run only under `emit.useAVX=2`. **Recommended:
   (a).** It is what the three plans assume, the EPYC 7763 is the runners' most common CPU and an
   AVX2 machine is a common user's, and row 121's section 6 reserved it for a number, which step 1
   produces.
2. **How is DQ read?** (a) `VectorSupport.getCPUFeatures()` behind a new `--add-exports` in
   Spark's module options: HotSpot's own answer after its clearing. (b) No new flag: `UseAVX >= 3`
   from the bean, plus `avx512dq` in `/proc/cpuinfo`: Linux only, and it shows hardware HotSpot may
   have cleared. **Recommended: (a)**; unknown falls back to today either way.
3. **What the record holds.** (a) `useAVX` alone, the resolver writing 2 for a fallback host. (b)
   A new component naming the conversion verdict, which moves `canonical()`, `emissionKey`, the
   bytes suite's folded set and the shape-cache test. **Recommended: (a)**; (b) when a lowering
   first keys on AVX-512F apart from DQ.
4. **This row before 343a's code.** **Recommended: yes**: 343a's decline is dead in production
   without it, and this row is two days against 343a's five.

*The owner approved the plan on 31 October 2026 and had the advisor settle its questions:* all four
recommendations stand. Question 1's gate is literal: the default flips only if step 1's EPYC pair
shows the magic form beating the fallback at both widths. Question 2 (a) was checked against the
JDK 25 source: `VectorSupport.getCPUFeatures()` returns `VM_Version::features_string()`
(`vectorSupport.cpp:617-623`), which is built (`vm_version_x86.cpp:1104-1111`) after `UseAVX < 3`
clears the AVX-512 bits (`:996-1000`); its names are separated by `", "` and DQ is `avx512dq`.

## 8. Done when

On a host without AVX512DQ, a session that sets no level takes the magic form of the 64-bit
constant division, read from the JVM's features (or, under question 1 (b), the predicate and the
three plans say what it means); the planted AVX-512 host without DQ takes it too; test 3.6 passes
on the PR Build's runners with nothing planted; the census test passes on the EPYC 7763 and on
the Neoverse N2 on CI, and on the laptop at both flag sets, because the laptop's Ryzen AI 9 HX 370
is the project's only host with AVX512DQ (the EPYC 7763 is Zen 3, the N2 is Arm); prediction 1 is
scored from a committed file; `emitted_bytes.json`, `width_audit.json` and `coverage.json` are
unchanged; the PR Build and the option matrix pass.

## 9. Outcome

## 10. Open points for the lead

1. **One place for the Varka SQL test sessions' conf.** Settled in 2.2: `TestSQLContext` plus the
   six helpers that build their own session.
2. **The runners' CPU mix** (2.2) is taken from row 516's draws, not re-read here.
3. **The conf's name.** Settled in 2.2: no new conf, a `-2` default of `emit.useAVX`.
4. **Step 1's vehicle.** `benchmark.yml` measures `VarkaTimeBenchmark`'s two kernel arms. Row 121's
   unrun second arm, the `TIME` surface with `emit.useAVX=2` through `varka-surface-benchmark.yml`
   (its `extra-confs` input, line 109), would score the same prediction end to end against
   `VARKA-121.md` 5.1's file. The kernel pair is cheaper; the surface arm is the user's view.
   Settled: the kernel pair in `benchmark.yml`, which scores prediction 1 directly; the surface arm
   stays row 121's.
