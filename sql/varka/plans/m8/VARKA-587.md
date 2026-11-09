# VARKA-587: A post for Spark users on math answers that depend on the host

## 0. What exists now (admission check)

From master at `d20b73fd45d` and this branch's tree, read on 9 November 2026. Spark's source was
read in this worktree; HotSpot in the local OpenJDK 25 tree (`jdk-25-ga`,
`/home/max/proj/openjdk-build/jdk25`, paths below under `src/hotspot/` and `src/java.base/`). Run:
the new probe on the laptop on JDK 25, 21 and 17, and the new Spark-level class on JDK 25, under the
session's heavy slot (`results/VARKA-587-math-host-laptop.txt`).

1. **Which class Spark calls, by function, in both evaluation paths.** Source
   `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/expressions/mathExpressions.scala`:
   - `UnaryMathExpression` generates `java.lang.Math.<fn>(c)` (`:85`) and evaluates its `f`
     (`:76-78`); `BinaryMathExpression` generates `java.lang.Math.<fn>(c1, c2)` (`:144`).
     `UnaryLogExpression` generates `java.lang.StrictMath.<fn>(c)` (`:110`).
   - `Sin` `:916`, `Cos` `:401`, `Tan` `:1024`, `Tanh` `:1069`, `Cbrt` `:271`, `Asin`, `Acos`,
     `Atan`, `Sinh`, `Cosh`, `Hypot` `:1605` take the base classes' `Math` code, and their `f` is
     `scala.math.<fn>`. `Sec`, `Csc`, `Cot` generate `1 / java.lang.Math.cos/sin/tan` (`:424`,
     `:939`, `:1047`) and evaluate the same. `Atan2` generates `java.lang.Math.atan2(c1 + 0.0,
     c2 + 0.0)` (`:1410-1412`).
   - `Exp` and `Expm1` override to `StrictMath` (`:573-575`, `:594-596`), `Log`, `Log10`,
     `Log1p` are `UnaryLogExpression`s over `StrictMath` (`:783`, `:832`, `:850`), `Log2` and
     `Logarithm` divide `StrictMath.log`s (`:802-810`, `:1634`), `Pow` is `StrictMath.pow` both
     ways (`:1434-1437`), `Atanh` is `StrictMath.log1p` (`:1091-1097`), and `asinh`/`acosh` are
     `ExpressionImplUtils` over `StrictMath.log`/`log1p` and `Math.sqrt`
     (`ExpressionImplUtils.java:417-454`).
   - The interpreted path agrees with the generated one class for class. Read from the bytecode,
     not the Scala: `javap -c` of each eval lambda (`Sin$$anonfun$$lessinit$greater$19` and its
     siblings) calls `scala/math/package$.sin` for `sin`, `cos`, `tan`, `sec`, `csc`, `cot`,
     `tanh`, `cbrt`, and `java/lang/StrictMath.<fn>` for `exp`, `ln`, `log10`, `log1p`, `pow`;
     and `scala.math.package$.sin`, `cbrt` and `tanh` are each one `invokestatic
     java/lang/Math.<fn>` (scala-library 2.13).
   - Spark documents it: `sin` "as if computed by `java.lang.Math.sin`" (`:903`), `sec` "as if
     computed by `1/java.lang.Math.cos`" (`:407`). And `Math`'s class comment says its methods
     "are not defined to return the bit-for-bit same results" as `StrictMath`'s
     (`java/lang/Math.java:43-46`); `sin`'s bound is 1 ulp (`:181`), `tanh`'s 2.5 (`:2732`).
2. **Which `Math` methods HotSpot replaces, on JDK 25.** `@IntrinsicCandidate` with a body that
   delegates to `StrictMath`: `sin` `Math.java:187-189`, `cos` `:205-207`, `tan` `:223-225`,
   `exp` `:333-335`, `log` `:358-360`, `log10` `:386-388`, `cbrt` `:448-450`, `atan2` `:615-617`,
   `pow` `:753-755`, `tanh` `:2743-2745`. Plain delegation, no intrinsic: `asin` `:242-243`,
   `acos`, `atan`, `sinh` `:2675-2676`, `cosh`, `hypot` `:2774-2775`, `expm1` `:2812-2813`,
   `log1p`.
   - **x86_64**: `generate_libm_stubs` installs Intel's LIBM stubs for `sin`, `cos`, `tan`,
     `tanh`, `cbrt`, `exp`, `pow`, `log`, `log10` when `UseLibmIntrinsic` (diagnostic, default
     true, `cpu/x86/globals_x86.hpp:163`) and the intrinsic is available
     (`cpu/x86/stubGenerator_x86_64.cpp:3681-3710`). No `atan2` stub on either architecture.
     The `tanh` stub is Intel's of 2024-2025 and `cbrt`'s of 2025 (their files' headers), so on
     JDK 21 and 17 those two are fdlibm on x86 too (measured, item 3).
   - **The stubs have no CPU-feature branch that changes a value**: the only feature tests in the
     nine are `supports_sse3()`, choosing `movddup` over `movlhps` to copy a lane
     (`stubGenerator_x86_64_sin.cpp:226`, `:240`, `_tan.cpp:813`, `:821`, `_log.cpp:242`,
     `:254`), and none uses FMA. So two x86 machines on one JDK build compute the same bits.
   - **aarch64**: stubs for `sin` and `cos` only (`cpu/aarch64/stubGenerator_aarch64.cpp:11688-
     11694`), "an optimized version of fdlibm sin/cos" (`macroAssembler_aarch64_trig.cpp:29-30`)
     that uses fused multiply-adds in its argument reduction and polynomial (`:292`, `:295`,
     `:322`, `:338`, `:794`), so its bits can differ from fdlibm's. `tan`, `exp`, `log`,
     `log10`, `pow` have no stub there and call `SharedRuntime::d<fn>` (`templateInterpreter
     Generator_aarch64.cpp:243-274`, `share/opto/library_call.cpp:1875-1900`), HotSpot's C copy
     of fdlibm, built with `-ffp-contract=off` (`make/hotspot/lib/JvmOverrideFiles.gmk:57-58`,
     `make/autoconf/flags-cflags.m4:916-922`); `tanh` and `cbrt` are not intrinsic there.
   - **Every tier calls the same routine**: the x86 interpreter's math entries call the stub
     (`cpu/x86/templateInterpreterGenerator_x86_64.cpp:419-475`, whose comment gives the reason,
     "identical implementation in order to avoid monotonicity bugs", `:388-392`), C1 calls it
     (`cpu/x86/c1_LIRGenerator_x86.cpp:797-821`), C2 calls it (`library_call.cpp:1875-1900`), and
     aarch64's do the same (`c1_LIRGenerator_aarch64.cpp:850-853`). An interpreter entry exists
     only when the intrinsic is available (`share/interpreter/templateInterpreterGenerator.cpp:
     446`), so `-XX:DisableIntrinsic=_dsin,...` sends all three tiers to the Java body, which is
     `StrictMath`'s.
3. **What the laptop measured** (`results/VARKA-587-math-host-laptop.txt`, Zen 5, JDK 25.0.4.1):
   - Part A, 2^20 inputs a function drawn four ways: `Math` differs from `StrictMath` for `sin`
     21203 inputs, `cos` 21016, `tan` 22721, `tanh` 130493, `cbrt` 86791 (64 of them NaN
     payloads), all by 1 ulp but 1563 of `tanh`'s by 2; and for `exp`, `log`, `log10`, `pow`,
     which Spark does not call through `Math`. `asin`, `acos`, `atan`, `sinh`, `cosh`, `atan2`,
     `hypot`, `expm1`, `log1p`: none. Cold and hot passes give the same digests; children under
     `-Xint`, C1 only and C2 only give this JVM's `Math` digests for all 18 functions, and the
     child with the intrinsics disabled gives `StrictMath`'s for all 18.
   - Parts B and C, JDK 21 and 17: the same counts but `tanh` and `cbrt` at 0, digests of `sin`,
     `cos`, `tan` unchanged. Part D: JDK 25's `Math` against JDK 21's on the same laptop differs
     for exactly `tanh` (130493) and `cbrt` (86791). So a JDK upgrade from 21 to 25 changes two
     functions' answers on x86. On JDK 17 the child with the intrinsics disabled gives `pow`
     bits that are not `StrictMath`'s; unread, and not Spark's (Spark calls `StrictMath.pow`).
     The first runs, with `_dtanh` and `_dcbrt` in the list on every JDK, saw JDK 21's and 17's
     child exit with status 1 at once: a JVM refuses an intrinsic name it does not know. The
     probe now names them from JDK 24 and 25 on; the committed parts are the second runs.
   - The literals 0.01 to 10.00: `Math` differs for 39 of 1000 under `sin`, 38 `cos`, 42
     `tan`, 51 `tanh`, 87 `cbrt`; the first, `cos(0.1)`.
   - Part E, Spark itself (`SparkMathHostBenchmark`, 2^18 rows from Parquet, Varka off): `sin`,
     `cos`, `tan`, `sec`, `csc`, `cot`, `tanh`, `cbrt` differ from the same composition over
     `StrictMath` in 5170 to 32396 rows, `sec`, `csc`, `cot` by up to 2 ulps (the reciprocal of a
     1-ulp error), and `asin`, `atan2`, `exp`, `ln`, `log10`, `pow` in none; whole-stage,
     generated and interpreted give one digest per column. `SELECT cos(0.1)` is folded to
     `0.9950041652780258`, fdlibm's being `0.9950041652780257`; `CAST(reflect(
     'java.lang.StrictMath', 'cos', x) AS DOUBLE)` gives fdlibm's bits in every row but NaNs.
4. **What other hosts already measured.** `dev/varka_canary/mathlane-*.txt`, the control pass of
   `MathLaneProbe` (per-lane scalar fallback, 262144 positive moderate inputs; a different input
   set from item 3's): on the EPYC 7763 runner and on the laptop `Math` differs from `StrictMath`
   for `SIN` 8707, `COS` 8726, `TAN` 9549, `TANH` 4772 (max 2 ulp), `CBRT` 21962, the same counts
   on both x86 CPUs; on the Neoverse N2 runner for `SIN` 19081 and `COS` 17697 (max 1 ulp), and
   `TAN`, `TANH`, `CBRT` 0. That is item 2's reading, measured: Arm's sin and cos differ from
   fdlibm and from x86's, its tan, tanh and cbrt equal fdlibm, and two x86 CPUs agree.
5. **What it means for Varka.** Varka's math is bit-exact to Spark's on the same host by default
   (the owner, 30 October 2026, `VARKA-345.md` 2.1): the `Math` group as per-lane calls of the
   method Spark calls (`VARKA-356.md` section 1). So Varka inherits the dependence and does not
   remove it; the post says so in one sentence.

### What differs from the brief

- **The claim is architecture and JDK, not CPU model.** Item 2 and item 4 show two x86 CPUs
  agreeing; the title's "your CPU" is true of the instruction set. Question 1.
- **`exp`, `log`, `log10` and `pow` differ by host in `Math`, not in Spark.** Part 5 of
  `results/VARKA-356-admission.txt` lists them beside `sin`; Spark calls `StrictMath` for them,
  so the post names them as the safe ones, and every figure draws the split.
- **Not all 1 ulp**: `tanh` reaches 2 in 1563 of 130493, and Spark's `sec`, `csc`, `cot` reach 2.
- **The probe is a canary, not only a benchmark**: the scalar question needs no Spark build and
  runs in seconds on any JDK, so `dev/varka_canary/MathHostProbe.java` answers it; the Spark-level
  check is the benchmark-style `SparkMathHostBenchmark`, for `benchmark.yml`'s pinned runners.

## 1. Audience and goal

Experienced Spark users who run and tune queries, compare results between clusters, move
workloads to Arm instances or upgrade JDKs. The goal is one post that tells them exactly which
SQL functions can return other bits on another machine, why, by how much, and what to do. Not
for Spark committers and not about Varka. Not in this row: a change to Spark (an upstream
proposal to call `StrictMath` would be its own row and the owner's decision), the cost of the
remedies (item 6.3), publishing.

## 2. The claim and its exact scope

On JDK 25, Spark's `sin`, `cos`, `tan`, `sec`, `csc`, `cot`, `tanh` and `cbrt` can return
different last bits on x86 and on Arm, and `tanh` and `cbrt` also change on x86 between JDK 21 and
25; the difference is 1 ulp (2 for `tanh`, `sec`, `csc`, `cot`), on 2 to 12% of inputs. Every
other math function, `exp`, `ln`, `log2`, `log10`, `log1p`, `pow`, `sqrt`, `asin`, `acos`,
`atan`, `atan2`, `sinh`, `cosh`, `asinh`, `acosh`, `atanh` and `hypot`, answers the same on every
HotSpot host, in value; the `StrictMath` digests of the five that make a NaN from a domain
error differ between x86 and Arm (6.4). On one host the answer does not change between the interpreter and the compilers,
nor between whole-stage, generated and interpreted Spark. Scope: HotSpot JDK 25 (17 and 21 for
the JDK step, on x86 only), x86_64 and aarch64 as GitHub's runners have them; not other JVMs
(OpenJ9, GraalVM native), not SVE hosts, not Windows or macOS.

## 3. The evidence

| fact | committed | owed |
| :--- | :--- | :--- |
| Spark's class per function, both paths | item 1 (source, bytecode) | |
| x86 stubs, aarch64 stubs, tiers share them | item 2 (source) | |
| x86 Zen 5, JDK 25, 21, 17: counts, ulps, tiers, literals | `results/VARKA-587-math-host-laptop.txt` A to D | |
| Spark query on x86 Zen 5, three paths | same file, E | |
| Arm and two x86 CPUs, 262144 inputs, scalar | `dev/varka_canary/mathlane-*.txt` control passes | |
| Arm, the probe's 2^20 inputs, and laptop against Arm per input | `results/VARKA-587-math-host-arm.txt` (6.1) | |
| a third x86 CPU (Xeon Platinum 8573C), and laptop against it per input | `results/VARKA-587-math-host-x86-runner.txt` (6.1) | |
| Spark query on Neoverse N2 | `results/VARKA-587-spark-arm.txt` (6.2) | |
| Spark query on EPYC 7763 (pinned) | | `benchmark.yml`, 6.2 |

## 4. The figures

Each script reads the committed results, and a panel that needs an owed file draws a dashed
"owed" placeholder until `results/VARKA-587-math-host-arm.txt` or `-epyc7763.txt` lands. The
Arm files landed on 9 November 2026 (6.4) and fill fig64, fig66, fig67, fig68 and fig69 (and
fig63, which the post no longer shows); fig68's panels grew to fit four diff lines. Only fig69's
EPYC 7763 bars stay owed.

1. `fig62.py`, *Two libraries behind one function list*: Spark's functions in two columns,
   `Math` and `StrictMath`, each box marked by whether the laptop measured a difference (part A).
2. *(Dropped from the post on 9 November 2026, which now states the eliminations in one paragraph; the script stays for the record.)* `fig63.py`, *What changes the answer*: the five candidate causes (tier, Spark's code path, x86
   CPU model, JDK version, architecture) against yes/no, each cell citing its count (parts A, B,
   D, E and the `mathlane` controls); the architecture cell from the Arm file when it lands.
3. `fig64.py`, *How often, how far*: per function, the share of the 2^20 inputs that differ on the
   laptop at JDK 25 and 21, split by ulps, with the Arm column owed.
4. `fig65.py`, *One ulp*: `cos(0.1)` on the double number line, the two neighbouring doubles and
   their decimal text, from part E.
5. `fig66.py`, *One query, two answers*: an x86 driver folding `cos(0.1)` and Arm executors
   computing `cos(x)` under one filter; the driver's value and fdlibm's from part E, the
   executors' from `results/VARKA-587-spark-arm.txt` (owed). A drawing of the mechanism of
   section 9's mixed cluster, which is not run.
6. `fig67.py`, *Which routine answers*: `sin`, `cos`, `tan`, `tanh`, `cbrt` against the Ryzen
   laptop, the EPYC 7763 and the Neoverse N2, each cell the routine (Intel's stub, Arm's FMA
   port, fdlibm) and its count from the `mathlane-*.txt` control passes (item 4); below, the
   laptop against Arm per input from the Arm file's section 6 (owed).
7. `fig68.py`, *Where it bites*: a two-row CSV export diffed, JDK 21 against 25 on the laptop
   (parts B and E) and the laptop against Arm (`-spark-arm.txt`, owed), the changed digit
   circled.
8. `fig69.py`, *Spark's own query*: part E's rows differing per function as bars, with the
   largest ulp; the script asserts one digest per column across the three paths. The EPYC 7763
   and Neoverse N2 bars from `-spark-epyc7763.txt` and `-spark-arm.txt` (one owed slot until
   they land).

Every figure names the machine from its results file (`mathhost.cpu`, `mathhost.lane_cpu`): the
laptop is the AMD Ryzen AI 9 HX PRO 370. In the post: fig65 is Figure 1, fig62 2, fig66 3, fig67
4, fig68 5, fig64 6, fig69 7.

## 5. The outline

Hook (`SELECT cos(0.1)`, and `cbrt(0.22)` across a JDK upgrade), In short (fig 65), "Two
libraries behind one function list" (fig 62, fig 66), "What does not change the answer" (fig 67),
"Where it bites" (fig 68, fig 64, fig 69), "What to do" (compare with a tolerance; `reflect`
into `StrictMath`; the intrinsic flag; pin architecture and JDK where bits matter), "How these
were measured". Draft: `m8/POST_MATH_HOST.md`.

## 6. The experiment owed

### 6.1 The probe on both runner architectures

`varka-canary.yml` runs a canary on `ubuntu-latest` and `ubuntu-24.04-arm` with JDK 25 and keeps
`$RUNNER_TEMP/canary-out` as each job's artifact. It is dispatchable only from the repository's
default branch, so the fork runs it on push, as row 576 did (run 42193042419): a scratch branch,
`varka-587-probe-run`, this branch with the workflow's default canary set to `MathHostProbe`.
The probe writes its dump into the artifact directory by itself on a runner. After the owner's
word:

    git switch -c varka-587-probe-run varka-587-post-math-host
    sed -i "s/inputs.canary || 'MathLaneProbe'/inputs.canary || 'MathHostProbe'/" \
      .github/workflows/varka-canary.yml
    git commit -am "Point the canary at MathHostProbe for one run"
    git push fork varka-587-probe-run
    # then, with the run id:
    gh run download <run> -R MaxGekk/spark -n canary-MathHostProbe-aarch64 -D <scratch>/arm
    gh run download <run> -R MaxGekk/spark -n canary-MathHostProbe-x86_64 -D <scratch>/x86
    java dev/varka_canary/MathHostProbe.java --compare <scratch>/arm/mathhost-dump.txt

The x86 job's CPU is whatever the pool assigns (an EPYC 9V45 in 576's run), which is a third x86
model. The laptop's `--compare` output against the Arm dump is the per-input count between the
hosts. Committed as `results/VARKA-587-math-host-arm.txt` (the Arm job's `library.txt` and
`machine.txt`, and the compare) and `-x86-runner.txt`.

*Ran 9 November 2026*: run 42612190576 on MaxGekk/spark, from `varka-587-probe-run` at
`f720069e66b` (`aa06cec20ca` and the workflow's default; the probe is this branch's file), both
legs green, Azul's Zulu 25.0.5.0.3 on each. The Arm leg drew a Neoverse N2 (CPU part 0xd49);
the x86 leg drew an Intel Xeon Platinum 8573C (AVX-512), a third x86 model and not the EPYC
7763. The compares ran on the laptop against the downloaded dumps with JDK 25.0.4.1, from the
laptop's own dump of section 0 item 3.

### 6.2 The Spark query on the pinned runners

`benchmark.yml` runs `SparkMathHostBenchmark` like any benchmark, pinned by `expected-cpu`; about
an hour of build each. After the owner's word and the branch on the fork:

    gh workflow run benchmark.yml -R MaxGekk/spark --ref varka-587-post-math-host \
      -f class=SparkMathHostBenchmark -f jdk=25 -f generate-tpcds=false \
      -f runner=ubuntu-24.04-arm -f expected-cpu=Neoverse-N2 \
      -f extra-java-options="--add-modules jdk.incubator.vector"
    gh workflow run benchmark.yml -R MaxGekk/spark --ref varka-587-post-math-host \
      -f class=SparkMathHostBenchmark -f jdk=25 -f generate-tpcds=false \
      -f expected-cpu="AMD EPYC 7763" \
      -f extra-java-options="--add-modules jdk.incubator.vector"

The artifact's `sql/core/benchmarks/SparkMathHostBenchmark-jdk25-results.txt` and its provenance
sidecar go to `results/VARKA-587-spark-arm.txt` and `-spark-epyc7763.txt`.

*Ran 9 November 2026*: the Neoverse N2 run is 42612241748 (from `varka-587-probe-run` at
`f720069e66b`, the same benchmark file; CPU check "Runner CPU: Neoverse-N2"; Zulu
25.38+109-CA), committed as `results/VARKA-587-spark-arm.txt`. The EPYC 7763 run, 42612245140,
drew another CPU and is retried hourly; `-spark-epyc7763.txt` stays owed, and with it the
post's two EPYC marks and fig69's EPYC bars.

### 6.3 Predictions, registered before the runs

1. Neoverse N2, JDK 25: `Math` differs from `StrictMath` for `sin` and `cos` only, by at most 1
   ulp; `tan`, `tanh`, `cbrt`, `exp`, `log`, `log10`, `pow` equal it (item 2; item 4's control).
2. The laptop against Arm: `tan`, `tanh`, `cbrt` differ on exactly the laptop's inputs (22721,
   130493, 86791); `sin` and `cos` on at least as many as the laptop's count, by at most 2 ulps.
3. The x86 runner, any CPU: every digest equal to the laptop's at JDK 25 (item 2's no-feature
   branch).
4. Spark on Arm: `sin`, `cos`, `sec`, `csc` differ from `StrictMath` and `tan`, `cot`, `tanh`,
   `cbrt` do not; the three paths agree. Spark on the EPYC 7763: every digest equal to part E's.

Not measured and not claimed: the cost of `reflect` or of disabling the intrinsics. The post says
both are slower without a number, or the row measures them if the owner asks (question 3).

### 6.4 What the runs measured, against 6.3

1. **Held, for values.** On the Neoverse N2 `Math` differs from `StrictMath` for `sin` in 54794
   values and `cos` in 29538, by 1 ulp, and for `tan`, `tanh`, `cbrt`, `exp`, `pow` in none
   (`results/VARKA-587-math-host-arm.txt` A). Not registered: `sin` and `cos` also differ in 125
   NaN payloads each, and `log`, `log10` in 61 NaN payloads and no value. On the literals,
   `sin` 70 and `cos` 70 of 1000, `cos(0.1)` not among them.
2. **Held.** The laptop against Arm (same file, B): `tan` 22721, `tanh` 130493, `cbrt` 86791,
   exactly the laptop's own counts; `sin` 66122 and `cos` 43725 (125 of each NaN payloads), above
   the laptop's 21203 and 21016, by at most 1 ulp, under the 2 allowed.
3. **Held for every function Spark calls through `Math`; `Math.pow` did not.** The Xeon Platinum
   8573C equals the laptop input by input for 17 of 18 functions, and `Math.pow` differs in 3
   inputs by 1 ulp (`results/VARKA-587-math-host-x86-runner.txt` B). The two hosts differ in
   CPU and in JDK build (Ubuntu's 25.0.4.1, Zulu's 25.0.5), so the 3 are not attributed; Spark
   calls `StrictMath.pow`. The EPYC 7763 itself stays owed (6.2).
4. **Held on Arm.** Spark on the Neoverse N2 (`results/VARKA-587-spark-arm.txt`): `sin` 13569
   rows, `cos` 7389, `sec` 4418, `csc` 7954 (both by up to 2 ulps), `tan`, `cot`, `tanh`,
   `cbrt` and the six `StrictMath` functions 0; one digest per column across the three paths.
   `SELECT cos(0.1)` folds to `0.9950041652780257`, fdlibm's, and `cbrt(0.22)` to
   `0.6036810736797685`; `sin(0.48)` gives x86's `0.4617791755414829`. The EPYC 7763 half is owed.

**Not registered: a NaN's bits differ by architecture even in `StrictMath`.** The probe's
`StrictMath` digests of `asin`, `acos`, `log`, `log10` and `pow` differ between the Arm runner
and the laptop (so its compare leaves those five out), and the other 13 agree; Spark's own
`asin` and `pow` columns digest differently on the two hosts although each equals `StrictMath`
on its host in every row, while `atan2`, `exp`, `ln` (null below zero) and `log10` agree. The
five are the ones that make a NaN from a domain error, and on the laptop each returns
`0xfff8000000000000` for one (`StrictMath.asin(2.0)` and the like, interpreted, JDK 25), x86's
default NaN; Arm's bits were not printed. Java does not specify a NaN's bit pattern, so the
post keeps to values and says so in one parenthesis; the row does not chase it further.

## 7. Questions for the owner

1. **The title.** *Settled by the owner, 9 November 2026:* "Your `sin()` depends on your JDK and
   your instruction set", which says what item 4 measured: two x86 CPUs agree.
2. **The dispatches of 6.2.** *Settled by the owner, 9 November 2026:* all three, the canary
   probe on both architectures and the Spark query on the Arm and the EPYC 7763 runners.
3. **The remedies' cost**: measure `reflect` and `-XX:DisableIntrinsic` against the stub in a
   benchmark (a row of its own), or leave the post saying "slower" with no number. Recommended:
   no number in this post.

## 8. Done when

The post is ready to publish with its figures, every number in it traced to a committed file by
the quote check, the Arm and EPYC 7763 measurements of 6.1 and 6.2 committed with the post's owed
marks replaced, and the owner has reviewed it.

## 9. Read and not taken

- **`approx_count_distinct`** calls `Math.log` in its estimate
  (`HyperLogLogPlusPlusHelper.scala:238`), which is the x86 stub and fdlibm on Arm, so its
  estimate could in principle differ by host after rounding. Spark's, not Varka's, and not
  measured; not a row.
- **A mixed-architecture cluster** folds a literal's `sin` on the driver and computes a column's on
  the executors, so the same expression can take each host's answer. True from item 2, not
  measured; a paragraph and a drawing of the mechanism in the post (fig 66), marked as not run.
