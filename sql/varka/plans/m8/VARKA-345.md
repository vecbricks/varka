# VARKA-345: The math family's exact default, and the ULP contract as an opt-in mode

## 0. What exists now (admission check)

From master at `5cdb032b9ef` (30 October 2026; Varka paths under
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/` unless named, Spark
paths under `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/`, `mathExpressions.scala`
meaning `expressions/mathExpressions.scala`), and from the plans of the rows it builds on:
`VARKA-341.md` (started, its 5.1 the code as built), `VARKA-342.md`, `VARKA-343.md`,
`VARKA-344.md` and `VARKA-346.md` (planned). The JDK was read in
`/home/max/proj/openjdk-build/jdk25` (`jdk-25-ga`, `6c48f4ed7`; `src/` below). The row's text is
the milestone plan's 2.7.1 and items 35, 36 and 98 of `m9/SCOPE.md`. Two admission programs were
run on the laptop under the heavy slot, at 512 and at 256 bits:
`results/VARKA-345-admission.txt` (the binding, the tiers, the step count, a lane the kernel does
not use) and `results/VARKA-345-exact-admission.txt` (a lane-wise copy of fdlibm's `exp` against
`StrictMath.exp`, and what a per-lane `Math.sin` call costs inside a vector loop).

1. **The owner's decision of 30 October 2026 supersedes the one of 14 October.** Every math
   function is bit-exact to vanilla Spark by default; the ULP contract survives only as an
   optional faster mode, off by default. The row text, 2.7.1's "The default is on" and
   `SCOPE_STANDARD_MODE.md` 3.4 were written under the earlier decision. This plan defines the
   exact default the function rows 356 to 358 cite (2.1 to 2.4) and the opt-in mode (2.5 to 2.9).
2. **What Spark computes, per function.** A `UnaryMathExpression` generates
   `java.lang.Math.<fn>(c)` (`mathExpressions.scala:84-86`) and evaluates `f` interpreted
   (`:77-79`); a `UnaryLogExpression` is null at `d <= 0` and generates
   `java.lang.StrictMath.<fn>` (`:89-115`); a `BinaryMathExpression` generates
   `java.lang.Math.<fn>(c1, c2)` (`:139-145`). By function:

| function | class | Spark calls | where |
|---|---|---|---|
| `sin`, `cos`, `tan` | `Sin`, `Cos`, `Tan` | `Math` | `:916`, `:401`, `:1024` |
| `asin`, `acos`, `atan` | `Asin`, `Acos`, `Atan` | `Math` | `:232`, `:209`, `:253` |
| `sec`, `csc`, `cot` | `Sec`, `Csc`, `Cot` | `1 / Math.cos`, `sin`, `tan` | `:421-424`, `:936-939`, `:1044-1047` |
| `sinh`, `cosh`, `tanh` | `Sinh`, `Cosh`, `Tanh` | `Math` | `:960`, `:446`, `:1069` |
| `asinh`, `acosh` | `Asinh`, `Acosh` | `ExpressionImplUtils`: fdlibm's branches over `StrictMath.log`, `log1p` and `Math.sqrt` | `:980-986`, `:468-474` |
| `atanh` | `Atanh` | `0.5 * (StrictMath.log1p(x) - StrictMath.log1p(-x))` | `:1091-1097` |
| `exp`, `expm1` | `Exp`, `Expm1` | `StrictMath` | `:573-575`, `:594-596` |
| `log`, `ln`, `log10`, `log1p` | `Log`, `Log10`, `Log1p` | `StrictMath`, null at `<= 0` | `:783`, `:832`, `:850` |
| `log2` | `Log2` | `StrictMath.log(x) / StrictMath.log(2)`, null at `<= 0` | `:802-813` |
| `log(b, x)` | `Logarithm` | `StrictMath.log(x) / StrictMath.log(b)`, null at `b <= 0` or `x <= 0`; `log(x)` alone is `StrictMath.log` | `:1634-1673` |
| `cbrt` | `Cbrt` | `Math` | `:271` |
| `atan2` | `Atan2` | `Math.atan2(y + 0.0, x + 0.0)` | `:1402-1411` |
| `pow` | `Pow` | `StrictMath.pow` | `:1434-1437` |
| `hypot` | `Hypot` | `Math` | `:1605-1606` |

   The registry names `log` for `Logarithm` and `ln` for `Log`
   (`analysis/FunctionRegistry.scala:481`, `:485`). That is the 27 functions of 2.7.1.
3. **What `Math` is on each host, at every tier.** `Math.asin`, `acos`, `atan`, `atan2`,
   `sinh`, `cosh`, `tanh`, `cbrt`, `hypot`, `expm1` and `log1p` delegate to `StrictMath`
   (`src/java.base/share/classes/java/lang/Math.java:242-244`, `:260-262`, `:281-283`,
   `:616-618`, `:2675-2677`, `:2703-2705`, `:2744-2746`, `:449-451`, `:2774-2776`,
   `:2812-2814`, `:2849-2851`), and `StrictMath` is `FdLibm` (`StrictMath.java:150-152`,
   `:193-195`, `:273-275`, `:294-296`, `:366-368`, `:580-582`, `:2116-2118`, `:2201-2203`).
   HotSpot replaces some of them by stubs, and the interpreter, C1 and C2 call the same stub:
   on x86 the template interpreter calls `StubRoutines::dexp`, `dlog`, `dlog10`, `dsin`, `dcos`,
   `dpow`, `dtan`, `dtanh` and `dcbrt` (`src/hotspot/cpu/x86/templateInterpreterGenerator_x86_64.
   cpp:419-474`), C1 the same nine (`cpu/x86/c1_LIRGenerator_x86.cpp:790-847`); on aarch64
   only `dsin` and `dcos` exist (`cpu/aarch64/stubGenerator_aarch64.cpp:11688-11693`), called
   by C1 the same way (`cpu/aarch64/c1_LIRGenerator_aarch64.cpp:850-853`). So `Math.<fn>`,
   called anywhere in one JVM, is the row engine's own routine on that host **[V]**, and:
   - **fdlibm everywhere**: `exp`, `expm1`, `log`, `log10`, `log1p`, `pow` (Spark calls
     `StrictMath`), and `asin`, `acos`, `atan`, `atan2`, `sinh`, `cosh`, `hypot` (Spark calls
     `Math`, which has no stub for them on either architecture, `READING_JDK.md` 1, "The
     fdlibm-everywhere list"). Their row-engine bits are `FdLibm.java`'s on every host.
   - **host-dependent**: `sin`, `cos`, `tan`, `tanh`, `cbrt` - Intel LIBM stubs on x86;
     `sin` and `cos` HotSpot's own stub on aarch64, `tan`, `tanh` and `cbrt` fdlibm there.
4. **`StrictMath` is fdlibm's operation sequence, and the JDK's file is the oracle only.** Each
   function is a class with a `compute` in `src/java.base/share/classes/java/lang/FdLibm.java`
   (`Asin` `:1061`, `Acos` `:1149`, `Atan` `:1232`, `Atan2` `:1350`, `Cbrt` `:1878`, `Hypot`
   `:1968`, `Pow` `:2111`, `Exp` `:2430`, `Log` `:2566`, `Log10` `:2686`, `Log1p` `:2795`,
   `Expm1` `:2987`, `Sinh` `:3117`, `Cosh` `:3193`, `Tanh` `:3271`), a Java rendering of Sun's
   fdlibm 5.3 built from IEEE `+ - * /`, compares, the high and low words of a double and
   constants, with no FMA (`READING_JDK.md` 3.4); C2 contracts none in a vector body
   (`VARKA-342.md` 0, item 6). So a lane-wise copy of fdlibm's sequence is the same arithmetic
   and its bits are `StrictMath`'s by construction, every branch reproduced exactly. **The
   licence decides the source.** `FdLibm.java` is GPLv2 with the Classpath exception (its
   header, lines 1 to 24), which covers linking and not a translation, so a port of that file
   cannot enter this Apache-2.0 repository; HotSpot's Intel LIBM stubs
   (`src/hotspot/cpu/x86/stubGenerator_x86_64_*.cpp`) are GPLv2 too. The port is written from
   Sun's fdlibm 5.3 C sources as netlib distributes them (`e_exp.c`, `e_log.c` and the rest),
   whose notice permits use, copying, modification and distribution provided the notice is
   preserved; `FdLibm.java` and `StrictMath` are read for understanding and run as the oracle a
   port is tested against, and no code or comment is copied from them. The C sources are not in
   this repository or on the laptop; fetching them is a step that needs the owner's approval
   (5, step 0).
5. **A lane-wise copy of fdlibm's `exp` holds bit for bit; its speed is not known.** A
   feasibility prototype, every branch computed and blended and not kept for licence reasons,
   differs from `StrictMath.exp` on 0 of 3640 fixed vectors (every threshold constant and 64
   neighbours each way, zeros, infinities, NaN payloads) and 0 of 16777216 sampled inputs, at
   both widths (`results/VARKA-345-exact-admission.txt` part 1, which says what the program did).
   Those counts stand. Its timing does not: 3.470 ns an element against the scalar `StrictMath`
   loop's 4.080 at 512 bits, 4.748 against 4.100 at 256, with SVML's `EXP` at 0.447 and 0.623
   (part 3), are provisional, laptop-only numbers of a hand-written Java loop, and they are not a
   finding about fdlibm in lanes. Why the port was not faster is unexplained: the timed runs
   forced inlining by `CompileCommand`; a run without it (the lead's `-XX:+PrintInlining` run at
   512 bits, 30 October 2026, recorded in that file) showed the 601-bytecode method refused
   ("hot method too big") at three call sites, so whether boxing (item 0.5a) explains the timing
   is not established; the IR-emitted port's run from the fdlibm 5.3 C sources (2.2) decides.
5a. **C2 inlines a hot callee only up to 325 bytecodes, and a refused call boxes its vectors.**
   `FreqInlineSize` is 325 on x86 and aarch64 (`src/hotspot/cpu/x86/c2_globals_x86.hpp:45`,
   `cpu/aarch64/c2_globals_aarch64.hpp:47`); a larger hot callee is refused as "hot method too
   big" (`share/opto/bytecodeInfo.cpp:170-190`) unless a `CompileCommand` or `@ForceInline`
   forces it (`:115-129`), and `@ForceInline` is honoured only in classes of the boot or platform
   loader (`share/classfile/classFileParser.cpp:1811-1832`; other code "silently drops some"), so
   a library on the class path cannot use it. A Vector API value passed to a Java call C2 did not
   inline stays a box: only a safepoint's debug uses are scalarised, a call that takes the value
   as an argument excluded (`share/opto/vector.cpp:238-252`), and a box with a use left is
   expanded (`:298-308`) into an allocation of its payload array and its vector object
   (`:374-445`): an allocation a call (the
   VARKA-55 boxing). The prototype's `exp` was 601 bytecodes and was refused (item 0.5); fdlibm's
   `pow` is about 300 lines of C.
6. **A per-lane `Math.sin` inside a vector loop is exact and costs the scalar call.** The loop
   that spills a vector to a lane buffer, calls `Math.sin` per lane and reloads differs from
   `Math.sin` on 0 of 65536 lanes, at 5.046 ns an element against the scalar loop's 4.884 at
   512 bits and 5.162 against 4.915 at 256 (`results/VARKA-345-exact-admission.txt` part 3).
   The call is the cost; what such a kernel gains is everything around it.
7. **The Vector API's binding (the opt-in mode's library).** `VectorMathLibrary` picks SVML on
   x64 and SLEEF on aarch64 and riscv64, or the property `jdk.incubator.vector.VectorMathLibrary`
   (`src/jdk.incubator.vector/share/classes/jdk/incubator/vector/VectorMathLibrary.java:58`,
   `:74-75`). SVML's symbol is `__jsvml_<op><N>_ha_<z0|l9|e9|ex>`, `z0` only for a 512-bit
   species (`:124-137`); `LOG`, `LOG10` and `POW` at 512 bits need AVX512DQ and `POW` is
   unsupported below 512 (`:162-166`); a 64-bit double species is never bound (`:158`, `:235`).
   SLEEF's tier is `u10`, `u05` for `HYPOT` (`:209-211`), and `TANH` is never bound (`:238-240`).
   `constructEntry` prints the symbol and address under `-Djdk.incubator.vector.DEBUG=true`
   (`:273`; the flag at `src/java.base/share/classes/jdk/internal/vm/vector/Utils.java:34`) and
   gives an unsupported operator a null entry (`:280`), whose call is the Java lambda per lane:
   `Math.<fn>` (`DoubleVector.java:737-877`). On the laptop every one of the 18 operators prints
   a `z0` symbol at 512 bits and every one but `POW` an `l9` symbol at 256 and 128
   (`results/VARKA-345-admission.txt` part 1).
8. **DEBUG proves the symbol, C2 proves the call.** `VectorSupport.libraryUnaryOp` is an
   intrinsic whose Java body is the lambda (`src/java.base/share/classes/jdk/internal/vm/vector/
   VectorSupport.java:339-344`); C2's `inline_vector_call` takes it only with a constant entry,
   a non-zero address and a supported vector size, and otherwise logs "missing constant",
   "missing entry point" or "vector size ... is not supported" under `-XX:+PrintIntrinsics`
   (`src/hotspot/share/opto/vectorIntrinsics.cpp:489-577`). Measured: the same `SIN` loop
   excluded from compilation differs from `Math.sin` on 0 lanes of 65536, compiled hot on 130;
   `EXP` excluded differs from `Math.exp` on 0 and from Spark's `StrictMath.exp` on 6443
   (`results/VARKA-345-admission.txt` part 2). Outside C2 a Vector API math operator is
   `Math.<fn>` per lane, which is not even Spark's answer for the `StrictMath` functions.
9. **A masked math operator computes every lane, and a lane the kernel does not use can be the
   slow path.** `lanewiseTemplate` blends after `unaryMathOp` over the whole vector
   (`DoubleVector.java:707-710`, the binary form `:833-835`). One lane in eight at `1e300` or NaN
   makes SVML's `SIN` at 512 bits take 4.521 and 4.644 ns an element against 0.763; blending
   that lane to `0.5` first restores 0.806; a mask with no lane set costs the same 4.590; at 256
   bits a subnormal lane makes `LOG` 5.036 against 0.931 (`results/VARKA-345-admission.txt`
   part 4, laptop). So a null lane's slot, whatever it holds, must not reach a library call.
10. **The probe's distance is not the step count.** `MathLaneProbe` takes
    `Math.abs(bits(a) - bits(b))` (`dev/varka_canary/MathLaneProbe.java:202-203`): right for two
    doubles of one sign, and `Long.MIN_VALUE` for `-0.0` against `+0.0` or for
    `-Double.MIN_VALUE` against `+Double.MIN_VALUE` (`results/VARKA-345-admission.txt` part 3),
    which `Math.max` then drops. And a step count alone passes `+inf` against `Double.MAX_VALUE`
    (one step, part 3). A multiple of `Math.ulp` fails as 2.7.1 says: at `r = 1.0` it admits the
    double two steps below, and at `r = +inf` it admits 1.0 (part 3). The JDK's own test
    `isWithin1Ulp` never compares `actual` with its neighbours
    (`test/jdk/jdk/incubator/vector/Double256VectorTests.java:759-777`). The overflow is in
    Varka's own probe, and row 367's measured lines depend on it, so 367 takes 2.5's comparator
    (open point 7).
11. **The configuration precedents.** Varka's settings are `SQLConf` entries, internal,
    version `5.0.0`, each with `.withBindingPolicy` (`sql/catalyst/src/main/scala/org/apache/spark/
    sql/internal/SQLConf.scala:3046-3182`); `dev/varka_lint.py`'s `binding-policy` rule refuses
    one without (`:405-416`). A setting read only by the Varka rule, which runs after analysis,
    is `NOT_APPLICABLE` (`SQLConf.scala:3056-3058`), as `ConfigBindingPolicy`'s rule 1 has it
    (`common/utils/src/main/scala/org/apache/spark/internal/config/ConfigBindingPolicy.scala:
    29-35`). The family switches decline at planning through `DeclineSink.off`
    (`VarkaNodeCompiler.java:285-310`) and travel beside the options, not in them, because they
    change admission and never bytes (`sql/core/src/main/java/org/apache/spark/sql/execution/
    VarkaSessionEmit.java:51-56`); `useAVX` is an emit option laid over the options there
    (`:93-105`) and reaches the shape hash. `DeclineSink` already takes an option,
    `rangeSets` (`VarkaExpressionCompiler.java:595`). `VarkaEmitOption.Reason` has no member for
    a sanctioned departure from the row engine's answers (`varka/VarkaEmitOption.java:47-98`).
12. **Every math function declines today.** `floatingDecline` admits no math class and answers
    "an operation over floating values that no floating lowering serves"
    (`VarkaNodeCompiler.java:331-356`); no family names one; `VarkaFamily` has six members, none
    math (`varka/VarkaFamily.java:48-72`). 342's planned `MathUnary(MathFn fn, child)` with
    `MathFn.SQRT` (`VARKA-342.md` 2.1) is the node 356 to 358 "add their functions to".
13. **The dated corrections the row's done-when names exist, under the earlier decision.**
    `SCOPE_FUNCTIONS.md` 3 and `m9/SCOPE.md` item 36 each carry a correction of 14 October 2026;
    both end on the contract being chosen. Each needs a second dated correction recording the
    exact default (2.10).
14. **No baseline times a math function.** `VarkaFloatingBaselineBenchmark` times a comparison, a
    chain, a cast and a choice (`VARKA-342.md` 0, item 8).

### What differs from the row text and the milestone plan

- **Exact by default, the contract opt-in and off by default** (owner, 30 October 2026). The
  row's "`...ulpContract.enabled`, on by default" becomes off by default; its "differential
  suites' config-off arm" becomes a config-on arm; 2.7.1's "The default is on" is superseded
  (its edit, section 5).
- **The row's first done-when item turns over.** "The configuration declines the family when
  off" was written for a family that fuses only under the contract. With an exact default the
  family fuses with the configuration off, in its exact forms; what the configuration switches
  is the form, not the admission (2.6). The check becomes: with the configuration off, no kernel
  holds a library call (an `--asm` and an IR check, 3.6), and with it on the opt-in forms appear
  only on a binding that has a contracted bound.
- **2.7.1's "a composition containing one compares its outputs that way" is unsound** in the
  opt-in mode: `exp(x) - 1.0` at `x = 1e-10` turns one step of `exp`'s answer, `2^-52`, into
  about `2^-52 / ulp(1e-10)`, of the order of `10^10` steps of the difference. 2.8 compares a
  contracted node within its bound and a composition by another route (open point 2).
- **The bound's key carries the species width**, since the symbol does (item 0.7); the
  production key is the binding and the full JDK build, as the decision of 14 October already
  said, and the measured key adds the CPU (2.5).
- **Question 2 applies to the opt-in mode only** and no longer holds 356 to 358 (open point 1).
- **The fdlibm group fuses first as a per-lane `StrictMath` call** (the lead, 30 October 2026):
  exact, and the work around it vectorised; the port is an option arm per function, the default
  only where it measures faster on the EPYC runners (2.1, open point 3).
- **The port is emitted as IR, not called** (the owner, 30 October 2026): Varka's emitter lowers
  fdlibm's operation sequence as IR nodes in its own kernels; the vector fdlibm library is the
  reference and the test oracle, not a runtime dependency (2.2, open point 10).

## 1. Goal and non-goal

The math family's two modes, as one mechanism the function rows fill in. **By default** every one of
the 27 functions, once its row fuses it, is bit-identical to vanilla Spark on every host, by the
exact forms defined here per function (2.1): first a per-lane call inside the kernel, of
`StrictMath.<fn>` for the thirteen fdlibm functions (the seven Spark calls through `Math` delegate
to `StrictMath` on every host, item 0.3) and of `Math.<fn>` for the five host-dependent ones; and,
for the thirteen, a lane-wise port of fdlibm 5.3 that Varka's emitter lowers as IR
nodes, built as an option arm per function and made the default only where it measures faster.
**Opt-in**, under `spark.sql.codegen.varka.math.ulpContract.enabled`, off by default, a function may
instead take the Vector API's library operator on a binding whose bound is contracted, within that
bound. This row builds the configuration, the family and its declines, the IR forms and their shared
lowerings, the IR helpers a port is written in, the bit-identity harness, the step-count comparator,
the bound's keying and storage, the binding check, the dead-lane rule, the docs and register
entries, the baseline class, and, once the owner approves fetching its source, the vector fdlibm
library's skeleton with `exp` as its template, the reference the emitted ports are held to. It fuses
no function in production: each function is its row's (section 7).

## 2. Design

### 2.1 The exact default, per function (what 356 to 358 cite)

| function | first exact form | measured arm | why it is exact |
|---|---|---|---|
| `exp`, `expm1`, `log`, `ln`, `log10`, `log1p`, `pow` | `LANE_CALL` of `StrictMath.exp`, `expm1`, `log`, `log10`, `log1p`, `pow` | `PORT` of fdlibm 5.3's `e_exp.c`, `s_expm1.c`, `e_log.c`, `e_log10.c`, `s_log1p.c`, `e_pow.c` | Spark calls `StrictMath`, which is that sequence (item 0.4) |
| `asin`, `acos`, `atan`, `atan2`, `sinh`, `cosh`, `hypot` | `LANE_CALL` of `StrictMath.asin`, `acos`, `atan`, `atan2`, `sinh`, `cosh`, `hypot` | `PORT` of `e_asin.c`, `e_acos.c`, `s_atan.c`, `e_atan2.c`, `e_sinh.c`, `e_cosh.c`, `e_hypot.c` | Spark calls `Math`, which delegates to `StrictMath`, fdlibm on every host (item 0.3) |
| `sin`, `cos`, `tan`, `tanh`, `cbrt` | `LANE_CALL` of `Math.sin`, `cos`, `tan`, `tanh`, `cbrt` | none (open point 11) | the row engine's own routine on that host at every tier (item 0.3) |
| `sec`, `csc`, `cot` | `1.0 / LANE_CALL(Math.cos, sin, tan)`, the divide a lane `DIV` (342) | none | Spark's expression, its divide IEEE's |
| `log2`, `log(b, x)` | `LOG(x) / c` with `c = StrictMath.log(2.0)` folded at compile time; `LOG(x) / LOG(b)` | by `log`'s form | Spark's expression; a folded constant is the same double |
| `atanh` | `0.5 * (LOG1P(x) - LOG1P(-x))` | by `log1p`'s form | Spark's expression |
| `asinh`, `acosh` | `ExpressionImplUtils`' branches as blends over `LOG`, `LOG1P` and the lane `SQRT` (342) | by `log`'s and `log1p`'s forms | Spark's expression; `sqrt` is IEEE's |

`LOG` and `LOG1P` in the compositions are the node for that function in whichever exact form it
takes on the host, so a composition follows its operand's form and needs no arm of its own.

**The rule for the fdlibm group** (the lead, 30 October 2026, open point 3): each of the thirteen
fuses first as `LANE_CALL` of `StrictMath.<fn>`, which is exact and leaves the loads, the null
handling and the arithmetic around the call vectorised. The `PORT` is then built per function as a
measured option arm, and both forms are kept as arms whichever wins, since which is faster can
depend on the JDK, the CPU and the vector width. `PORT` becomes
a function's default on a host only where it measures faster than `LANE_CALL` on the EPYC runners
by minimums; elsewhere `LANE_CALL` stays the default and the port an arm the option matrix draws.

Three rules hold for every row of the table:

- **Spark's expression, operation for operation.** Operand order, `atan2`'s `+ 0.0` on both
  operands, the log family's null at `<= 0` (a value-dependent null, as 342's zero divisor is),
  `log(x)` with no base as `StrictMath.log` alone: the kernel computes what the generated code
  computes, never a rewritten form (`log2` is not `log(x) * (1 / ln 2)`).
- **`PORT` is a port, not an approximation of one.** Written from fdlibm 5.3's C, each constant
  as the C source writes it, each branch reproduced (2.4), each high- or low-word operation on
  the long view of the lane (343's reinterpretation), no FMA and no reassociation; held to
  `StrictMath` bit for bit (2.3), never copied from `FdLibm.java` (item 0.4).
- **`LANE_CALL` of the host-dependent group calls `Math`, never `StrictMath`**, because the row
  engine does; a port of a host's stub is not planned: Intel's x86 stubs in HotSpot are GPLv2
  (item 0.4), so where one would come from, and whether it may, is open point 11, and any such
  arm would be built only where it beats the per-lane call and only with a bit-identity check
  against that host's `Math` (section 7).

### 2.2 Two parts: a vector fdlibm library as the reference, and Varka's forms

The exact default has two parts with a line between them. The first is a library that stands
alone in its own repository, `vfdlibm` (https://github.com/vecbricks/vfdlibm, group
`io.github.vecbricks`, Apache-2.0; the owner, 30 October 2026), filled one function at a time as
Varka needs it, as its `PLAN.md` section 2 states; the second is Varka's own emitter, which writes
the same sequence into its kernels. **The library is the reference and the test oracle of the
emitted ports, never a runtime dependency of a kernel** (the owner, 30 October 2026, open point 10).

**The library: fdlibm in lanes, with no Spark or Varka dependency.** A Java 25 module on
`jdk.incubator.vector` alone, Apache-2.0 with Sun's fdlibm notice preserved, whose API is one
static method per function and species-generic:

```java
public final class VectorFdlibm {
  public static DoubleVector exp(DoubleVector x);
  public static DoubleVector exp(DoubleVector x, VectorMask<Double> live);
  // the same pair for expm1, log, log10, log1p, asin, acos, atan, sinh, cosh;
  // pow, atan2 and hypot as (DoubleVector x, DoubleVector y[, VectorMask<Double> live])
}
```

Each method returns, in every lane, `StrictMath.<fn>` of that lane's argument, bit for bit, the
contract its tests hold it to (2.3). The masked form computes only for `live` lanes and returns
`0.0` in the others. Inside, each function is fdlibm 5.3's sequence over the long view of the lanes
(the high word `hi(x)` and low word `lo(x)` as `LSHR` and `AND`, `withHi`, `withLo`, `fromHiLo`, a
compare of `|x|`'s high word against a constant done as a compare of `|x|` against the double whose
bits are that word shifted left 32, exact for every non-NaN `|x|`, and the exponent add
`y + (k << 52)`), with each range branch blended, or its rare lanes **finished by a per-lane
`StrictMath.<fn>` call**, which is exact (2.4). The library's own tests are 2.3's tests 1 and 2
against `StrictMath` on the JVM that runs them. Its use to Varka is as a second, independently
written rendering of each sequence: the emitted port is held to it lane for lane on the same
vectors, so a slip in either shows as a difference before `StrictMath` is even consulted, and a
port's branch decisions (2.4) are tried and timed in it before they are emitted.

**Varka's side: three forms of one node.** 342's `MathUnary(MathFn fn, VarkaVectorIR child)`
gains a `MathForm form`, and a sibling `MathBinary(MathFn fn, MathForm form, VarkaVectorIR left,
VarkaVectorIR right)` serves `ATAN2`, `POW` and `HYPOT`. `MathFn` gains every function of 2.1's
first column that is one call, each member naming its Spark method (`Math` or `StrictMath`), the
method its `LANE_CALL` calls (`StrictMath` for the thirteen fdlibm functions, `Math` for the five
host-dependent ones), and its `VectorOperators` constant. `MathForm` is sealed
and exhaustive:

| form | lowering | exact | built by |
|---|---|---|---|
| `LANE_CALL` | the lanes stored to the kernel's scratch, the method called per live lane, the lanes loaded back | yes | 345; each function's first form, by its row |
| `PORT` | the function's fdlibm sequence expanded by the compiler into IR nodes inline in the kernel's method, over the helpers below | yes | the helpers and the expansion's frame by 345; each function's sequence, as a measured arm, by its row |
| `LIBRARY` | `lanewise(op)` after the dead-lane blend (2.9) | within the bound | 345; enabled per function by its row |

**How `PORT` reaches the kernel: as IR, never as a call.** The compiler expands a `PORT` node into
the function's operation sequence as ordinary IR nodes - lane arithmetic, compares, blends, the long
reinterpretations (343) and long-lane shifts - inside the kernel's one method, and the emitter
lowers them as it lowers any other node. The helpers 345 builds are IR builders, not methods
called at run time: `hi`, `lo`, `withHi`, `withLo`, `fromHiLo`, the high-word compare and the
exponent add, each a few nodes. A kernel's method budget (`VarkaEmitBudget`) bounds how much of a
port one method holds, and the function row checks its port against it.

Calling the library's static methods from the kernel (open point 10's option (a)) is not built, for
three reasons in the JDK (item 0.5a). C2 inlines a hot callee only up to `FreqInlineSize`, 325
bytecodes on x86 and aarch64 (`src/hotspot/cpu/x86/c2_globals_x86.hpp:45`,
`cpu/aarch64/c2_globals_aarch64.hpp:47`), and refuses a larger one as "hot method too big"
(`share/opto/bytecodeInfo.cpp:170-190`); the prototype's `exp` was 601 bytecodes and was refused
(item 0.5), and `pow` has about three times `exp`'s code lines (section 5). `@ForceInline` is
dropped outside classes of the boot and platform loaders
(`share/classfile/classFileParser.cpp:1811-1832`), so a library on the class path cannot ask for the
inline, and a `-XX:CompileCommand=inline` is the deployment's flag, not Varka's. And a vector passed
to a call that is refused stays a box, allocated a call (`share/opto/vector.cpp:238-252`,
`:298-308`, `:374-445`), which the warm-up's allocation-free test would catch and hold the kernel on
the row engine. Splitting each function under 325 bytecodes would make every split point a boxing
point unless every piece inlined, which nothing guarantees. Under the IR route no call boundary
exists to box, and the IR a port is lowered from is the IR row 506's printer renders: that row
renders the decimal kernels' proofs from the lowered IR graph, and a port emitted as IR nodes is
open to the same rendering, while a call into a library would be opaque to it (no proof of a math
kernel is planned here).

`SQRT` keeps 342's lowering, the lanewise operator, which is IEEE's and needs no form. The form
is part of the node, so it is in the shape key by construction, and the compiler chooses it
(2.6); the emitter never does. Why a field and not three nodes: the three compute one function,
the reference evaluates them alike (2.8) and the census entry is one (`VARKA-475.md` 4 rule 1).

**`LANE_CALL`'s lowering** is one loop over the lane count around a static call, the pattern of
item 0.6, on the scratch buffers the kernel already owns (`varka/VarkaScratch.java`): no
allocation a batch, and a null or masked-off lane skipped rather than called (2.9). Its cost is
the call (item 0.6); its value is that the loads, the null handling and the arithmetic around it
stay vectorised.

### 2.3 The bit-identity tests (what 356 to 358 name)

Every exact form is held by three tests, which each function row instantiates with its cells:

1. **The sampled differential, `VarkaMathExactSuite`**: the kernel's lanes against
   `StrictMath.<fn>` or `Math.<fn>` - whichever Spark calls - on the same JVM, by
   `doubleToRawLongBits`, over at least 2^24 inputs a function: uniform bit patterns, the
   function's finite-result domain, `[-2, 2]`, and magnitudes down to `2^-60` (the prototype's
   four draws), at each width the host runs. NaN is compared by raw bits too: every exact form
   computes NaN by the sequence Spark runs (fdlibm returns `x + x` for a NaN argument), so a
   payload passes through as it does there. A `PORT` form is held, on the same inputs, to the
   library's lanes as well as to `StrictMath`. The library runs the same test on its own,
   against `StrictMath`, with no kernel.
2. **The fixed vectors**: each threshold constant of the function's fdlibm source and its 64
   neighbours each way (the prototype's 3640 for `exp`), SLEEF's special-value list - NaN, both
   infinities, `+-DBL_MAX`, `+-DBL_MIN`, `+-denorm_min`, both zeros, `+-1` and their neighbours
   (`READING_SLEEF.md` 6.3) - and the libraries' worst cases: the handbook's and Gladman's
   (`READING_LIBM.md`), glibc's and Intel's switching regions (`READING_LIBM.md` 6.3,
   `READING_JDK.md` 2.8) and the SLEEF overflow band of section 10.1 item 1. The list is a
   committed resource per function, `sql/varka/math/<fn>.vectors`, which 367's probe reads too.
3. **Through the engine**: the coverage rows and the differential suites compare the fused
   answers with Spark's bit for bit (the existing rule for every exact expression), and the IR
   fuzzer's reference evaluates the node by the Spark method (2.8), so a port is checked against
   `StrictMath` wherever the fuzzers draw it.

Test 1 runs on each host CI runs: the row engine's `Math` is per host (item 0.3), so a
`LANE_CALL` form is checked against the host it runs on, and a `PORT` against `StrictMath`, which
is the same everywhere. These are sampled checks, not proofs; a port is exact by construction and
the tests check the construction was copied right.

### 2.4 Range branches: compute and blend, or finish the rare lanes per lane

A port meets fdlibm's branches - `exp`'s filter, two reduction arms, the tiny argument and the
subnormal result (read in `FdLibm.java:2459-2513`), `log`'s subnormal rescale, `pow`'s many cases.
Each branch is either computed in every lane and blended, or tested and its live lanes finished by a
per-lane `StrictMath.<fn>` call after the vector body, which is exact and keeps the batch in the
kernel. The rule, per branch: **blend unless the branch is rare on ordinary data and its lane cost
is large; finish its lanes per lane then.** The library does this inside its method, so its API has
no decline; Varka keeps a decline to the row engine only as a choice of its own for a branch whose
per-lane finish it measures slower than the row path (a `GuardedRange`-shaped refusal naming the
branch). Rare and large are measured, not judged: the function row counts each branch's share over
the same four draws as 2.3 test 1 (the prototype's counts for `exp`: over `[-750, 750]` the far
reduction takes 990595 inputs of 1048576 and the filter 56522; over `[-2, 2]` the near reduction
364176, the far 502697, the primary range 181703, `results/VARKA-345-exact-admission.txt` part 2)
and times the kernel with the branch blended against the kernel with it finished per lane, by
minimums. A branch finished per lane or declined states its rate beside its time, as
`SCOPE_STANDARD_MODE.md` 4's decline-rate assertion asks.

### 2.5 The opt-in mode: the bound, its keys and where it lives

**The bound is a count of representable doubles.** A kernel lane `a` passes against the row
engine's `r` within `k` steps when:

- either is NaN: both are, by NaN-ness (a NaN never passes against a number);
- either is infinite: `a == r`, the same infinity (so `+inf` never passes against
  `Double.MAX_VALUE`, one step away, item 0.10);
- otherwise the sign bits are equal and `|bits(a) - bits(r)| <= k` (one sign, so the raw-bit
  difference is the step count and cannot overflow; `-0.0` never passes against `+0.0`).

Two static methods in catalyst's main scope, since the docs generator and the cross-check read
the same class: `VarkaMathBound.steps(double a, double r)`, the step count of the last rule, `0`
for two NaNs or one infinity against itself, and `Long.MAX_VALUE` for every pair the rules refuse
at any `k` (a NaN against a number, an infinity against anything else, opposite signs); and
`within(double a, double r, int k)`, which is `steps(a, r) <= k`. Every oracle calls `within`.
**367's probe measures with `steps`**, never with `MathLaneProbe`'s `Math.abs` of the raw-bit
difference, which is `Long.MIN_VALUE` for opposite signs and is then dropped by `Math.max` (item
0.10): a lane `steps` puts at `Long.MAX_VALUE` is recorded as a line of its own, with its witness,
that no contracted value can cover.

**Two bounds, kept apart.**

- The **measured** bound is 367's: per function, JDK build (`java.runtime.version`), symbol and
  CPU model, the largest `steps` its probe found, "a lower bound of the true maximum".
  345 fixes the line format 367 writes, one TSV per host under `sql/varka/plans/m8/results/`
  (`VARKA-367-<host>.tsv`): function, library, symbol, species bits, JDK build, CPU, inputs,
  lanes differing, largest steps, and the witness (input bits, the library's bits, the row
  engine's bits). The true-value column from MPFR is 367's own addition to the line.
- The **contracted** bound is what the oracle compares within and the docs state: a record table
  in code, `varka/VarkaMathContract.java`, one entry per function and binding - library, symbol,
  and the full JDK build, `java.runtime.version`, as the decision of 14 October already keyed it
  (open point 4) - holding the contracted steps, SLEEF's stated tier beside it where SLEEF is the
  library (`u10`, `u05` for `hypot`; SVML states none, `READING_JDK.md` 1), and the measured
  lines it rests on. A JDK build with no entry runs the exact form until it is measured.
- **The rule between them** (open point 5): the contracted value is at least every measured
  line it cites. Since 367's probe gives a lower bound of the true maximum, a measured line can
  later exceed the contracted value, and then the contract is wrong, not the line: the suite
  (3.4) fails whenever any committed line for an entry's key exceeds its contracted value, and
  refuses an entry that cites no line. Raising the value is a dated entry with the new line's
  witness.

**The binding is resolved, then checked.** `varka/VarkaMathBinding.java` reproduces
`VectorMathLibrary`'s rule (item 0.7) from `os.arch`, the property, the species' bit size and
the AVX level the emitter already reads, giving the symbol or `UNBOUND`. AVX512DQ is not visible
outside the JDK; the resolver takes it as present wherever `UseAVX=3`, and the binding check is
what catches a host where it is not. In the opt-in mode a function takes `LIBRARY` only where its
binding is bound and has a contract entry; elsewhere it keeps its exact form, with the reason in
the verbose `EXPLAIN` ("no contracted bound for `SIN` on `__jsvml_sin4_ha_l9`, JDK 25").

**The binding check.** `VarkaMathBindingSuite` forks one JVM with
`-Djdk.incubator.vector.DEBUG=true`, touches each operator at each species the host runs, and holds
the DEBUG lines to the resolver: a symbol wherever the resolver says bound, none where `UNBOUND`. It
runs in the PR build, which is x86 (`ubuntu-latest`), and in the census workflow on the Arm runner
(`varka-canary.yml`'s host), so each JDK build CI uses is checked on both architectures. The call
itself, which DEBUG does not prove (item 0.8), is checked by `dev/varka_emit.sh --asm` over a
`LIBRARY` form run with the DEBUG flag: the `call` in `loopDense0`'s assembly targets the address
the DEBUG line printed. `varka_emit.sh` gains `--jvm-arg` for the flag; the check runs by hand once
per JDK build in the gate and its output is committed (`results/VARKA-345-binding-<host>.txt`).

### 2.6 The configuration

```scala
val VARKA_MATH_ULP_CONTRACT = buildConf("spark.sql.codegen.varka.math.ulpContract.enabled")
  .internal()
  .doc("When true, Varka may compute a math function (sin, exp, log, pow and the rest of" +
    " the family) with the JDK's vector math library where the function's documented bound" +
    " is contracted for this host's library: faster, and within that many representable" +
    " doubles of Spark's answer rather than equal to it. When false, the default, every" +
    " math function Varka fuses returns Spark's answer bit for bit.")
  .version("5.0.0")
  // Read by the Varka rule after analysis, choosing how a kernel computes a value; nothing a
  // view body resolves to.
  .withBindingPolicy(ConfigBindingPolicy.NOT_APPLICABLE)
  .booleanConf
  .createWithDefault(false)
```

- **The emit option**: `VarkaSessionEmit` gains `mathUlpContract`, laid over the base options as
  `useAVX` is (item 0.11), into a `VarkaEmitOptions` flag `mathUlpContract`, default false, a
  `FlagTag("ulpContract", true)` so the defaults hash as they do today, and a new
  `VarkaEmitOption.Reason.CONTRACT` - a sanctioned departure from the row engine's answers that
  a session opts into; the fuzzer and the option matrix draw it. It is an emit option rather
  than a family switch because it changes the form a node takes, so bytes, which is what
  `VarkaSessionEmit`'s own rule puts in the options.
- **The port arm** (2.1's rule): a second emit option, `mathPort`, the set of `MathFn` members
  that take `PORT` rather than `LANE_CALL` in the exact default, with `Reason.MACHINE` (which
  form wins depends on the machine). Its default is the functions whose port measured faster on
  the EPYC runners, empty until a function row enters one; a function with no port built is never
  in it. It is not a SQL configuration; the option matrix draws the empty set and every built
  port, and the differential suites run both, each bit for bit.
- **Read at compile time, never per batch.** The math arm reads `options.mathUlpContract()`
  through `DeclineSink`, as it reads `rangeSets`, when it builds the node, and writes the form
  into it; no kernel holds a read of the setting. A plan keeps the options it was planned with
  (`VarkaSessionEmit`'s rule), so a query planned before the setting changed computes as planned.
- **The audit**: `dev/varka_lint.py`'s binding-policy rule passes it; the configuration audit
  suite (`SparkConfigBindingPolicySuite`) runs after it is added; `docs/sql-varka.md`'s
  configuration list states it, its default and what turning it on changes.

### 2.7 The family, its declines and question 2

`VarkaFamily.MATH` ("math", switch `spark.sql.codegen.varka.family.math.enabled`), its arm
`VarkaMathCompiler`, claims the 27 functions by class and operand type; `floatingDecline` admits
them by name, as it admits the comparisons. This row's arm admits nothing in production: each
class declines with the row that fuses it ("`sin` is row 356's"), and a test hook,
`VarkaMathCompiler.admitForTesting(MathFn, MathForm)`, admits one so 345's suites exercise each
form. A function row replaces its classes' reasons with lowerings.

**Question 2 lives in the opt-in mode only.** An exact form has no inexact value to feed a
comparison, a cast or a hash, so nothing below applies by default. In the opt-in mode the arm
classifies each Catalyst expression over a `LIBRARY` node, with the node itself as the root of a
contracted value: a **contracted value** (the node, under negation, `abs`, the choices and their
null handling only, which keep the step count), a **composition** (under any other value-
producing node) or a **discrete consumer** (a comparison, `IN`, a cast to an integral or decimal
type, a rounding function of 344 or 346, `hash`). The classification is built here because the
oracle needs it whatever the answer (2.8); what the compiler does with a discrete consumer is
the answer to question 2 (open point 1), and only this section and 2.8's last paragraph change
with it. Until it is answered a discrete consumer over a `LIBRARY` node keeps the exact form,
which is answer (a)'s behaviour and the conservative one. The question does not block 356 to
358, whose default forms are exact.

### 2.8 Oracles

- **The exact default**: every form is compared bit for bit, against Spark through the coverage
  differential and the Spark oracle, against the reference evaluator in the IR fuzzer.
  `VarkaReference` evaluates `MathUnary` and `MathBinary` in every form by the Spark method,
  never by the Vector API, whose operator outside C2 is `Math.<fn>` (item 0.8), and never by the
  port it checks.
- **The opt-in mode, a contracted value**: compared within the contracted bound for the host's
  binding by `VarkaMathBound.within`, in the coverage differential, the Spark oracle, the IR and
  Spark-level fuzzers.
- **The opt-in mode, a composition** (open point 2, settled as (A)): the kernel-level oracles
  evaluate the `LIBRARY` node by a library reference - `VarkaMathLibraryReference`, a per-
  operator loop at the kernel's species warmed until C2 compiles it, and checked compiled by a
  witness from 367's line where the library and `Math` differ - and compare the composition bit
  for bit; the library reference itself is compared within the bound against the row engine,
  lane by lane. The Spark-level fuzzer compares contracted values within the bound and counts a
  composition or a discrete consumer as fused and not compared, leaving it to the kernel level.
- **The differential suites' configuration-on arm**: each coverage row of the math family runs
  under the default, bit for bit, and again with the configuration on, within the bound where
  the host's binding has an entry and bit for bit elsewhere. The option matrix carries the
  configuration as `mathUlpContract=true`.

### 2.9 Null and masked-off lanes

- **`LANE_CALL`** calls only the live lanes; a dead lane's slot is written `0.0`, which no
  consumer reads.
- **`LIBRARY`** blends every dead lane - null, masked off by an arm, past the batch's end, or
  null by the log family's `<= 0` - to a harmless argument before the call: `0.5` for the unary
  operators, `(0.5, 0.5)` for the binary ones, inside every operator's fast path, on the
  measurement of item 0.9. The blend is emitted only in a body with a mask (the masked body, an
  arm, the tail); the dense body of a batch with no nulls and no arm has none.
- **`PORT`** computes every branch on every lane, so a dead lane costs no slow path; but a
  declining branch (2.4) tests live lanes only, so a null lane never declines a batch.

The coverage rows say which form served them, and `docs/sql-varka.md` says it per function
(2.10).

### 2.10 Docs, coverage, register

- **The coverage table's bound column**: each math row carries its default form - "exact:
  per-lane `StrictMath` call", "exact: fdlibm port emitted as IR", "exact: per-lane `Math`
  call" - with the port arm named where one is built, and, where an opt-in entry exists, "opt-in:
  within k steps on <binding>" per binding, with SLEEF's tier beside a SLEEF entry;
  `VarkaCoverageSuite` fails a math row without a form and an exact row with a bound. 353's user
  support table renders the same data.
- **`docs/sql-varka.md`**: the configuration in its list (2.6), the default (exact) and what
  turning the mode on changes. The head of the math section is 353's.
- **`SCOPE_STANDARD_MODE.md` 3.4** becomes an opt-in entry: a dated correction of 30 October
  2026 under its heading saying the departure is no longer the default, the family is exact by
  default and the contract is a mode a session turns on; its "The default is on" paragraph kept
  as the record, marked superseded.
- **`SCOPE_FUNCTIONS.md` 3 and `m9/SCOPE.md` item 36** each gain a correction of 30 October 2026:
  the exact default, the two exact forms, the contract as an opt-in mode. Item 36's
  "emitted fdlibm" option becomes the `PORT` arm, emitted as IR and still costed without FMA; item
  98 stays the route to one bit pattern for the opt-in library.

### 2.11 Compiler, reference, cost, census, warm-up

- `VarkaMathCompiler` and `VarkaFamily.MATH` (2.7); `floatingDecline` admits the classes.
- `VarkaReference` evaluates both nodes in every form by the Spark method (2.8).
- Cost: `LANE_CALL` is a feature of `VarkaEmitCostRegister` per function, weighed until its fit
  as a scalar call times the lane count; a `PORT` is weighed by the IR nodes it expands to, and a
  port of `pow`'s size (about 300 lines of source, `READING_JDK.md` 3.4) is checked against
  `VarkaEmitBudget`'s method limits by its row, since the expansion is inline in the kernel's
  method.
- The census cells: each `VectorOperators` math operator (`VARKA-376.md` 9 already has them) and
  the reinterpretations and long-lane shifts the helpers use (343's and 341's cells).
- The warm-up gains the calls the three lowerings make (`VarkaWarmupReachSuite`): the scratch
  store and load around a `LANE_CALL`, the long-view operations a `PORT` expands to, and each
  operator a `LIBRARY` form calls.

## 3. Tests and proofs

1. **The configuration**: with it off (the default) a kernel admitted through the test hook in
   `LIBRARY`'s place holds no library call - the IR has no `LIBRARY` node and `--asm` shows no
   `call` to a `__jsvml_` or SLEEF address; with it on, the form appears only where the binding
   has a contract entry; the decline reasons name the configuration and the binding. The option's
   tag reaches `canonical()` (`VarkaShapeCacheSuite`'s component test), and so does `mathPort`'s;
   `option_arms` gains both pinned arms.
2. **The port helpers**, the IR builders and the library's lane helpers alike: each against its
   definition on the raw bits
   (`doubleToRawLongBits` shifted and masked, `longBitsToDouble` of the assembled word) over
   2^24 bit patterns; the high-word compare against its double form over every exponent and both
   signs, NaN excluded by construction.
3. **`LANE_CALL` through the hook**: `sin`, `tan` and `cbrt` against `Math`, and `exp` and `asin`
   against `StrictMath`, over 2.3's draws in both bodies and at each width, by raw bits, nulls and
   arms skipped, no allocation a batch (`VarkaAllocationSampler`).
4. **The contract table**: `VarkaMathContractSuite` reads every committed `VARKA-367-<host>.tsv`
   line, checks its format, and fails when any line for an entry's key (function, binding, full
   JDK build) exceeds the entry's contracted value or when an entry cites no line; empty until
   367 commits its first host, when it holds whatever the function rows enter.
5. **The comparator**: `VarkaMathBoundSuite` - at `k`, a lane `k` steps out passes, `k + 1` and
   `k + 2` fail (the row's done-when); NaN against a number, `+inf` against `Double.MAX_VALUE`,
   `-0.0` against `+0.0` and `-Double.MIN_VALUE` against `+Double.MIN_VALUE` fail; two NaN
   payloads pass; `steps` is `Long.MAX_VALUE` for each refused pair and the exact count for
   pairs of one sign either side of a power of two; through an oracle, a planted kernel lane two
   steps beyond the bound fails the coverage differential's math row.
6. **The binding check** (2.5): `VarkaMathBindingSuite` in the PR build (x86) and the census
   workflow (Arm); the `--asm` check by hand in the gate.
7. **The dead-lane blend**: a `LIBRARY` kernel over a batch with nulls holding `1e300` and NaN in
   their slots computes the live lanes as the dense kernel does, and its time is within the
   dense time's band (laptop, from item 0.9's measurement; the gate's run only).
8. **Question 2's classification**: each of the three classes over `sin` built through the hook,
   the classes asserted and the oracle's path per class.
9. **The library reference**: compiled before it is trusted (its witness), and a planted
   composition error caught bit for bit.
10. **The generated files** in `ADDING_AN_EXPRESSION.md`'s order: `coverage.json` and
    `docs/sql-varka.md` gain the column; no existing shape's bytes move.

## 4. The baseline, and predictions

**The baseline first, its own pull request on the unchanged code**:
`VarkaMathBaselineBenchmark`, a class for the family, the row path today: `sin(x)`, `tan(x)`,
`cbrt(x)`, `exp(x)`, `log(x)`, `log10(x)`, `pow(x, y)`, `atan2(y, x)`, `sinh(x)`, `hypot(x, y)`,
`sec(x)` and `exp(x) * 2 + 1`, over ordinary values, with and without nulls, on the EPYC 7763
at 256 and 128 bits through `benchmark.yml`; its fuse check behind a property, as 342's is. The
function rows measure against it.

Predictions, registered before the build:

1. **No existing shape's bytes move**; `option_arms` gains the `mathUlpContract` and `mathPort`
   arms.
2. **`LANE_CALL` alone gains nothing on the call**: a kernel of `sin(x)` or `exp(x)` alone within
   0.8 to 1.3 times the row path's time a row, from item 0.6's per-lane loop at the scalar
   call's cost; a gain appears only with work around the call, such as `exp(x) * 2 + 1`, by the
   arithmetic's share.
3. **The emitted `exp` port against `LANE_CALL`**: no direction is registered. The only timing
   of a lane-wise `exp` is the prototype's, whose cause is unexplained (item 0.5), so it predicts
   nothing about an inline port; section 5 step 4 measures it first.
4. **The opt-in mode at 256 bits** is several times faster than the row path for a bound
   operator, as `SCOPE_FUNCTIONS.md` 3's table has it for the library against the fallback
   (2.5x to 9x at four lanes); this row registers the direction only, each function row the
   number.
5. **The bit-identity tests' time**: 2^24 inputs a function at about 5 ns a scalar reference
   call (item 0.6) is about 0.1 s of reference calls a function on one core, under 30 s for the
   27 with the kernel runs and the sanitizer, at the calibration table's new-suite ratio of 2
   (VARKA-243). The fixed vectors add milliseconds.

## 5. Order, files and size

0. **The fdlibm 5.3 C sources, with the owner's approval.** Netlib's `fdlibm` (Sun's 5.3, the
   `e_*.c`, `s_*.c`, `k_*.c` files and `fdlibm.h`) fetched by a named source and digest, and
   placed in `vfdlibm` (2.2), each file's Sun notice preserved in the port's source header. Nothing
   of them is fetched before that yes; until then no `PORT` sequence is written, and the prototype
   of item 0.5 is not reused.
1. **The baseline** (4), its own pull request.
2. **345a, needing nothing unbuilt**: the configuration and its option, `Reason.CONTRACT`,
   `VarkaFamily.MATH` with every class declining by its row, `VarkaMathBound`, `VarkaMathBinding`,
   the binding suite and `--jvm-arg`, the contract table (empty) and its suite, the TSV format,
   the coverage column, the docs entry, the dated corrections (2.10), and this row's edit of
   2.7.1. Tests 3.1 (the IR half), 3.4 to 3.6.
3. **345b, after 342's same-lane part (its `MathUnary`) and 343's reinterpretations**: the form
   field and `MathBinary`, the `LANE_CALL` and `LIBRARY` lowerings, the dead-lane rule, `PORT`'s
   expansion frame and its IR helpers (2.2), the `mathPort` option, the test hook,
   `VarkaMathExactSuite`'s harness and the vector files' format, the classification and the library
   reference; tests 3.1 (the `--asm` half), 3.2, 3.3 and 3.7 to 3.10.
4. **The library's skeleton and the `exp` template, after step 0**: `vfdlibm`'s first
   function, in its own pull request paired with Varka's: the library's API (2.2), the lane helpers
   with their test 3.2, the masked-form contract, the per-lane finish, and its own runs of 2.3's
   tests 1 and 2 against `StrictMath`; then one function as the template, `exp` from `e_exp.c`,
   written once in the library and once as Varka's `PORT` expansion, the two held to each other and
   to `StrictMath`, its branch measurement (2.4), and the emitted port's speed against `LANE_CALL`
   of `StrictMath.exp` on the laptop first and then on the EPYC runners, which re-establishes item
   0.5's timing on a port with no call. Whether `exp`'s port becomes its default is 358's, by 2.1's
   rule. The other ports are their rows' (section 7).

| file | what |
|---|---|
| `SQLConf.scala`, `VarkaSessionEmit.java` | the configuration and its reading |
| `varka/VarkaEmitOptions.java`, `VarkaEmitOption.java` | `mathUlpContract`, `Reason.CONTRACT` |
| `varka/VarkaFamily.java`, `VarkaMathCompiler.java`, `VarkaNodeCompiler.java` | the family, its arm, `floatingDecline` |
| `varka/VarkaVectorIR.java` | `MathForm`, `MathBinary`, the `MathFn` members |
| `varka/VarkaVectorWalk.java`, `Slots.java`, `Analysis.java` | the three lowerings, the blend |
| `varka/VarkaMathPort.java` (new) | `PORT`'s expansion frame, the IR helpers, `exp`'s expansion |
| `vfdlibm`, its own repository (2.2) | `VectorFdlibm`, its helpers, `exp` as the template, its tests; the reference only |
| `varka/VarkaMathBound.java`, `VarkaMathBinding.java`, `VarkaMathContract.java` | 2.5 |
| `dev/varka_emit.sh` | `--jvm-arg` |
| tests | 3.1 to 3.9, `VarkaReference`, the library reference |
| `sql/varka/math/` | the vector file format and its reader |
| benchmark | the baseline class and its results files |
| generated | `coverage.json`, `docs/sql-varka.md`, `emitted_bytes.json` |
| plans | `PLAN.md` 2.7.1 (owner's decision recorded), `SCOPE_STANDARD_MODE.md` 3.4, `SCOPE_FUNCTIONS.md` 3, `m9/SCOPE.md` item 36 |

**The port's size, as counts.** Thirteen functions are in scope for the port: `exp`, `expm1`,
`log`, `log10`, `log1p`, `pow`, `asin`, `acos`, `atan`, `atan2`, `sinh`, `cosh` and `hypot` (2.1;
`cbrt` and the other host-dependent four take `LANE_CALL` of `Math`). fdlibm 5.3's C sources are not
on the laptop, so their own line count is not taken; by a line count of the JDK's translation, read
only to count, the thirteen are about 1,000 code lines, `pow` about a fifth of them and `log10` the
smallest (`results/VARKA-345-exact-admission.txt`). Each is written twice under 2.2's route, once in
the library and once as Varka's IR expansion.

**Size: large, against the row's medium.** The baseline half a day; 345a about three days (the
configuration and option one, the bound, binding and contract with their suites one and a half,
docs and records half); 345b about four (the forms and lowerings one and a half, the port's frame,
helpers and harness one, the classification and library reference one and a half); the library's
skeleton and the `exp` template about four (the helpers and API one, the library's `exp` with its
branch measurement one and a half, the IR expansion of `exp` and its speed against `LANE_CALL`
one and a half), after the owner's yes on step 0. About eleven and a half days in three pull
requests after the baseline, one of them in `vfdlibm`.

## 6. What it unlocks in the census

No entry moves: every math function stays declined, by its row's name, until 356 to 358 fuse it.
What moves is that each of those rows starts from a defined exact form, its tests and its
lowering, and the opt-in mode's mechanism, so each is its function's sequence, cells and
coverage rows.

## 7. Out of this row, and declines that stay

| piece | owner |
|---|---|
| the configuration, the two options, the family and its declines, the IR forms, `LANE_CALL` and `LIBRARY` lowerings, the dead-lane rule, `PORT`'s expansion frame and IR helpers, the library's skeleton, helpers and API, `exp` as the template in the library and as an IR expansion, the bit-identity harness and vector format, the step comparator, the binding resolver and check, the contract table and its cross-check, the measured-line format, the classification, the library reference, the docs column and records, the baseline class | 345 |
| each function's claim and first `LANE_CALL` form, its port from its fdlibm source in the library and as an IR expansion (the `PORT` arm), the measurement that decides its default form (2.1's rule), branch decisions (2.4), fixed vectors and exact cells, coverage rows, opt-in contract entries, and measurement against the baseline | 356 (trigonometric), 357 (hyperbolic), 358 (exp, log, pow, root) |
| the probe, its runs per host, the measured lines, MPFR's true-value column, the lane-independence arm, `pow`'s guarded domain | 367 |
| the user support table and the head of the math section | 353 |
| `MathUnary` with `SQRT`, the lane `DIV` | 342 |
| the reinterpretations and conversions the helpers use | 343 |
| a port of a host's `Math` stub (Intel's on x86, HotSpot's `dsin` on aarch64), only from a source whose licence allows it and only where it beats `LANE_CALL`, with a bit-identity check against that host's `Math` | not planned; open point 11 |
| the emitted SLEEF-shaped `exp` and `log` | `m9/SCOPE.md` item 98 |

Declines that stay after this row: every math function, by its row's name; in the opt-in mode a
binding with no contract entry keeps the exact form, which is not a decline.

## 8. Open points for the lead

Points 2, 3, 4, 5, 7 and 10 were settled on 30 October 2026, 10 by the owner's word and the rest by
the lead's; each keeps its number, since the sections above cite them, and states what was
settled. Point 9, where the library lives, was settled by the owner the same day and is dropped
from the list (2.2). Points 1, 6, 8 and 11 stay open.

1. **Question 2, now the opt-in mode's only, and the owner's** (milestone plan 7, question 2;
   10.3 item 1). *Answered, the owner, 30 October 2026: (a) with a documented caveat, recorded in
   the milestone plan's question 2.* **It does not block 356 to 358**: their default is exact, and a discrete
   consumer over an exact value is exact. In the opt-in mode the options are:
   (a) a discrete consumer over a `LIBRARY` node keeps the exact form (2.7 builds this now as the
   interim); evidence: it keeps every filter and integral result Spark's, and it is the kernel's
   to enforce only for consumers inside the kernel - a `GROUP BY`, a join key, a sort or a
   partitioned write above the projection is invisible to the kernel, so the Varka rule would
   have to inspect the operators that read the projection's outputs, which no row plans;
   consequence for 356 to 358: their opt-in coverage rows over a filter or a cast are rows that
   take the exact form. (b) admitted, the oracle threshold-aware: the kernel level compares
   bit for bit against the library reference (2.8), so only the Spark-level fuzzer's partition
   check learns a tolerance (a row may differ only where the contracted value is within its
   bound of the threshold), and users are told a filter over a math function may select
   different rows in the mode; consequence: each function row adds the threshold cells. (c) the
   documentation alone; consequence: the Spark fuzzer's skip of 2.8 covers it, the rows add
   notes. *Recommended: (a), with a documented caveat for the opt-in mode*: the docs' entry for
   the configuration (2.6, 2.10) says that a value computed under the mode and consumed above the
   kernel - grouped, joined on, sorted or written as a partition key - may fall on the other side
   of a threshold than Spark's answer. What changes with the answer: 2.7's last paragraph and
   2.8's third bullet.
2. **The opt-in mode's composition oracle.** *Settled, the lead, 30 October 2026: (A).* The
   opt-in mode checks compositions against the C2-warmed library reference of 2.8, checked
   compiled by a witness, with compositions compared bit for bit; outside C2 the operator is
   `Math.<fn>` (item 0.8), so only a compiled loop reproduces the kernel's lanes. (B), admitting
   a `LIBRARY` node only as a contracted value, and (C), 2.7.1's comparison at the output, which
   is unsound (the `exp(x) - 1.0` example of "What differs"), are not built.
3. **The default form of the fdlibm group.** *Settled, the lead, 30 October 2026: option (ii).*
   Every fdlibm function fuses first as the per-lane `StrictMath` call, which is exact and leaves
   the surrounding operations vectorised. The IR-emitted port is a measured option arm per
   function (`mathPort`, 2.6), kept as an arm whichever way the measurement goes, so both forms
   are built; it becomes a function's default only where it measures faster on the EPYC runners
   (2.1's rule). Option (i), `PORT` as the default from a function's first pull request, is not
   taken; the prototype's timing (item 0.5) does not bear on the choice either way.
4. **The opt-in mode's production key.** *Settled, the lead, 30 October 2026: the full JDK
   build.* A contract entry is keyed by function, binding and `java.runtime.version`, as the
   decision of 14 October already said, so an update release with a different library behind the
   same symbol runs exact until it is measured (2.5).
5. **The contracted value.** *Settled, the lead, 30 October 2026.* The contracted value is at
   least every measured line for its key. 367's probe gives a lower bound of the true maximum, so
   `VarkaMathContractSuite` fails whenever a committed measured line exceeds the contracted value
   (2.5, 3.4); raising the value is a dated entry with that line's witness.
6. **Row wording to change under the 30 October decisions** (the lead's edits). *Applied, the
   lead, 30 October 2026, in the milestone plan's rows and sections listed below.*
   - **356**: "The trigonometric functions under the contract" becomes "exact by default, the
     opt-in contract as a second form": `sin`, `cos`, `tan` by `LANE_CALL` of `Math` and `sec`,
     `csc`, `cot` as `1 / LANE_CALL`; `asin`, `acos`, `atan`, `atan2` first by `LANE_CALL` of
     `StrictMath`, each with its `PORT` arm, the default where it measures faster on the EPYC
     runners (`VARKA-345.md` 2.1); "each only after 367 has measured it" applies to the opt-in
     form only; the done-when's "within its stated bound" becomes "bit for bit by 2.3's tests, and
     within its bound in the opt-in mode".
   - **357**: `sinh`, `cosh` first by `LANE_CALL` of `StrictMath` with their `PORT` arms, `tanh`
     by `LANE_CALL` of `Math.tanh` on every host (the SLEEF clause about `tanh` concerns the
     opt-in form only), `asinh`, `acosh`, `atanh` as Spark's compositions over the `log` and
     `log1p` nodes and `SQRT`; "under the contract" and "each only after 367 has measured it" as
     for 356.
   - **358**: `exp`, `expm1`, `log`, `ln`, `log10`, `log1p`, `pow`, `log2`, two-argument `log` and
     `hypot` first by `LANE_CALL` of `StrictMath`, each one-call function with its `PORT` arm
     (`exp`'s template built by 345), `cbrt` by `LANE_CALL` of `Math.cbrt`; owner's decision A3's
     guard, the SLEEF output guard and "enabled on a host only after 367 has measured the guarded
     domain" belong to the opt-in `LIBRARY` form, while the exact `pow` is `StrictMath`'s,
     reproducing fdlibm `e_pow.c`'s weak branch as it is in either exact form (so A3's 636-ulp
     concern does not arise by default); the exact nodes (`y == 2` and the rest) and the census
     reading ("`pow` has no vector stub at 256 and 128") concern the opt-in form; `log10` "at its
     two steps" becomes bit for bit.
   - **367**: still needed, for the opt-in mode only; its lines in the format of 2.5; its fixed
     vectors shared with 2.3's resource; "every bound of 356 to 358 cites its line" becomes
     "every opt-in contract entry"; its distance is `VarkaMathBound.steps` (point 7), and its
     lines are read by the rule of point 5.
   - **PLAN.md elsewhere** (outside row 345's line, so not edited here): 1.1's done-when item on
     the contract (line 140), the opening paragraph (lines 22 and 32), section 4.1's F2 and G4
     cells and its "math chain stays held by question 2" bullet, risk 13, question 2's text in
     section 7 and 10.3 item 1 all describe the contract as the default or question 2 as holding
     the chain. This row edits 2.7.1 only, as the brief allows.
7. **The probe's step distance.** *Settled, the lead, 30 October 2026: Varka's own, and row 367
   depends on it.* `MathLaneProbe`'s `Math.abs(bits(a) - bits(b))` is `Long.MIN_VALUE` for a
   pair of opposite signs, which `Math.max` then drops (item 0.10), so such a pair is counted as
   differing and its distance lost; none of the committed `mathlane-*.txt` maxima is affected
   unless such a pair occurred. The lead adds the dated note to row 367. The comparator 367 must
   use is 2.5's: `VarkaMathBound.steps(a, r)`, the raw-bit difference only when both are finite
   and of one sign, `0` for two NaNs or one infinity against itself, `Long.MAX_VALUE` for a NaN
   against a number, an infinity against anything else and any pair of opposite signs (so `-0.0`
   against `+0.0`); a lane at `Long.MAX_VALUE` is reported as a line with its witness, never
   folded into a maximum.
8. **The size**: large, about eleven and a half days in three pull requests after the baseline,
   against the row's medium; the port written twice (library and IR expansion) and both exact
   forms built per function are what grew it (section 5).
10. **How a `PORT` reaches the kernel.** *Settled, the owner's word, 30 October 2026: option (b).*
    Varka emits the fdlibm operation sequence as IR nodes in its own kernels; the standalone
    vector fdlibm library is the reference and the test oracle, not a runtime dependency (2.2).
    Option (a), the kernel calling the library's static methods, is not built: C2 inlines a hot
    callee only up to `FreqInlineSize`, 325 bytecodes on x86 and aarch64
    (`src/hotspot/cpu/x86/c2_globals_x86.hpp:45`, `cpu/aarch64/c2_globals_aarch64.hpp:47`;
    "hot method too big", `share/opto/bytecodeInfo.cpp:170-190`); `@ForceInline` is honoured
    only in boot and platform classes (`share/classfile/classFileParser.cpp:1811-1832`), so not
    outside the JDK; and a vector passed to a refused call is boxed, an allocation a call
    (`share/opto/vector.cpp:238-252`, `:298-308`, `:374-445`). The lead's `-XX:+PrintInlining`
    run of the prototype showed the first in practice: its 601-bytecode `exp` refused at three
    call sites (item 0.5). The route also suits row 506, whose printer renders proofs from the IR
    the compiler lowers: a port emitted as IR nodes is in that IR, a library call is not.
11. **A port of the host-dependent group.** `sin`, `cos`, `tan`, `tanh` and `cbrt` stay
    `LANE_CALL` of `Math`. A faster exact form would have to reproduce Intel's stubs on x86 and
    HotSpot's `dsin` and `dcos` on aarch64, which are GPLv2 in HotSpot (item 0.4), so a port of
    them is not a plan step; whether any permissively licensed source reproduces them bit for bit
    is unknown. The question is the owner's, with the licence first.

Settled here, not questions: the configuration's name stays the row's, its default false by the
owner's word; `NOT_APPLICABLE` by `ConfigBindingPolicy`'s rule 1 and the Varka precedent; the
setting is an emit option because it changes a node's form (2.6).

## 9. Done when

With the configuration off, the default, no kernel holds a library call and every math form admitted
through the hook - `LANE_CALL` of `StrictMath` and of `Math`, and `PORT` once its template exists -
is bit-identical to Spark by 2.3's three tests; with it on, `LIBRARY` appears only on a binding with
a contract entry keyed by the full JDK build; `VarkaMathBound` refuses a lane two steps further out
than the bound and every pair of opposite signs (3.5); the contract suite fails a measured line
above its entry (3.4); the binding check passes on the x86 and Arm runners and the `--asm` check is
committed for the laptop; the dated corrections of `SCOPE_FUNCTIONS.md` 3, item 36 and
`SCOPE_STANDARD_MODE.md` 3.4 record the exact default; no existing shape's bytes move; the PR build
and the option matrix with `mathUlpContract=true` and with `mathPort` drawn pass; the baseline is
committed with this row's predictions scored where they can be; and, after the owner's yes on step
0, the library's `exp` and its IR expansion match `StrictMath.exp` and each other by 2.3's tests 1
and 2, with the emitted port's speed against `LANE_CALL` of `StrictMath.exp` recorded on the EPYC
runners.

## 10. Outcome

(Written when the work lands.)
