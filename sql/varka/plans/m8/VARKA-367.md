# VARKA-367: The extended math probe, the measured lines the opt-in ULP contract rests on

## 0. What exists now (admission check)

From master at `5c660201b35` and this branch's tree, read on 1 November 2026. The plans read are
`VARKA-345.md` (merged as a plan, nothing built) and `VARKA-356.md` (rows 356 to 358, merged as a
plan). The JDK was read in `/home/max/proj/openjdk-build/jdk25` only through those plans' citations.
The GitHub runner image readmes were read with `gh api` GETs. Nothing was dispatched, no JVM was
run, and no third-party source was fetched.

1. **The probe today.** `dev/varka_canary/MathLaneProbe.java` (408 lines) runs 14 Vector API
   operators: `SIN`, `COS`, `TAN`, `EXP`, `LOG`, `LOG10`, `EXPM1`, `LOG1P`, `ATAN`, `TANH`, `CBRT`,
   `POW`, `ATAN2` and `HYPOT` (`:104-132`). Each runs at `SPECIES_PREFERRED` over 2^18 inputs
   `|(u - 0.5) * 60|`, so the inputs are positive and below 30, with `y` in `[0.5, 4.5]` for the
   binary ones (`:328`, `:369`). Its own comment says why: "the point is agreement on
   ordinary arguments, not edge behaviour" (`:37-38`). Every lane is compared with `Math` and with
   `StrictMath`, and the largest distance is printed (`report`, `:196-210`). After the random
   inputs come the fixed inputs of the SLEEF overflow band: eight for `EXP` and six for `POW`,
   pushed through the compiled loops and printed lane by lane (`:211-243`, `:384-406`).
2. **The four operators it does not run** are `ASIN`, `ACOS`, `SINH` and `COSH`. The family binds
   18 (`VARKA-345.md` item 0.7). **No composition is probed**: `sec`, `csc`, `cot`, `asinh`,
   `acosh`, `atanh`, `log2` and the two-argument `log` are computed by Spark over these operators
   (`VARKA-345.md` item 0.2). Those 26 forms are the family's 27 names, since `ln` and `log(x)` are
   `log` and `power` is `pow`.
3. **Its distance overflows.** `Math.abs(bits(a) - bits(b))` (`:202-203`) is `Long.MIN_VALUE` for
   two results of opposite signs, and `Math.max` then drops it (`VARKA-345.md` item 0.10). With
   positive inputs no committed reading met such a pair, but the extended draws will. 345 fixes
   the comparator, `VarkaMathBound.steps` (its 2.5), and it is not built yet.
4. **Three readings are committed, one per host library**, all on JDK 25.0.4+7:
   - SVML `l9` at 256 bits, `mathlane-x86-avx2-epyc7763.txt` (GitHub's x86 runner, an AMD EPYC
     7763, Zulu build, 19 September 2026);
   - SLEEF `u10` at 128 bits, `mathlane-aarch64-neoverse-n2.txt` (GitHub's Arm runner, a Neoverse
     N2, Zulu build, 16 October 2026);
   - SVML `z0` at 512 bits, `mathlane-x86-avx512-zen5.txt` (the laptop, Ubuntu's build, taken by
     hand with the workflow's two passes, 19 September 2026).

   The largest distances against the class Spark calls are 1 step everywhere, except `LOG10` at 2
   steps on all three and `TANH` at 1 against `Math` but 2 against `StrictMath` on x86 (`LOG10`
   24199 lanes differing at `l9`, 24250 on SLEEF; `TANH` 455 lanes against `Math` at `l9` and
   1535 at `z0`). These files are canary outputs, not results files under
   `dev/varka_quote_check.py`, so this plan quotes only their counts.
5. **Two operators have no symbol on one host each.** At AVX2 SVML binds no `POW`, and the EPYC
   file's `POW` row is the per-lane fallback, 0 lanes against `Math`. SLEEF binds no `TANH`; the
   Arm row is 0 against both classes. So the `l9` `POW` key and the SLEEF `TANH` key can carry no
   contract entry (`READING_JDK.md` 2.4 and 2.5). `LOG`, `LOG10` and `POW` at 512 bits need
   AVX512DQ, which the laptop has.
6. **The machine line names the wrong build string.** `machine()` prints `java.vm.version`
   (`:191`). The production key is `java.runtime.version` (`VARKA-345.md` 2.5, settled in its open
   point 4). The laptop's Ubuntu build and the runners' Zulu build share `25.0.4+7` but are
   different builds **[I]**: the plan did not read either one's `java.runtime.version`, since
   that takes a JVM.
7. **The canary workflow needs no Spark build.** `varka-canary.yml` checks out only
   `dev/varka_canary`, installs Zulu 25, and runs a single-file program three times: the library
   pass, a control with the Java fallback forced, and the `fromlong` pass. It runs on every push
   that touches the directory and by dispatch with `canary` and `args` inputs. It uploads
   `$RUNNER_TEMP/canary-out` whole. So the probe cannot import catalyst's `VarkaMathBound`.
8. **MPFR is on the laptop and likely on the runners.** The laptop has `libmpfr.so.6` (package
   `libmpfr6`) and no header. The Foreign Function and Memory API needs none, and it is final in
   JDK 25, so no preview is involved. GitHub's `ubuntu-latest` x86 image lists `gcc` 13, and the
   Arm partner image lists GCC 12 to 14. GCC's compiler proper links MPFR, so `libmpfr6` is on both
   **[I]**: neither readme lists the library itself.
9. **The worst-case tables are not in the tree.** The row names "the handbook's and Gladman's
   worst cases". Neither source is in `sql/varka/papers/` or in any reading note. PLAN.md 2.7.1
   and section 9 cite Gladman's Table 3 for its maxima, not for its inputs.
   `READING_LIBM.md` 6.3 and `READING_JDK.md` 2.8 do give switching regions as constants:
   - glibc's `log`, `exp` and `pow` regions;
   - Intel's `pow` near-one region `[1 - 1/32, 1 + 1/16)`;
   - fdlibm's `log` near 1, `exp` below `2^-28`, at 0.5 ln2 and 1.5 ln2, and on its subnormal path.

   `READING_SLEEF.md` 1 gives SLEEF's special-value list and its lane-independence filler.
10. **What 345 and 356 to 358 expect of this row.** 345 fixes the line format: one TSV per host,
    `results/VARKA-367-<host>.tsv`, with function, library, symbol, species bits, JDK build, CPU,
    inputs, lanes differing, largest steps and a witness. MPFR's true value is this row's addition
    (`VARKA-345.md` 2.5). `VarkaMathContractSuite` reads every committed line and fails an entry
    that a line exceeds (its 3.4). The fixed vectors are shared with 345's resource,
    `sql/varka/math/<fn>.vectors` (its 2.3). The function rows enter a contract entry only after
    this row commits a host's lines (`VARKA-356.md` 2.7 and its section 5, step 6). The probe
    serves the opt-in mode only: the exact default needs no probe (`VARKA-345.md` 8, point 6).

### What differs from the row text

- **The opt-in mode only.** The row was written when the contract was the default. Since
  30 October its lines feed only the opt-in `LIBRARY` form; "every bound of 356 to 358 cites its
  line" reads "every opt-in contract entry cites its line" (the row's own dated note).
- **"Against both references"** stays in the log, but a measured line is against the class Spark
  calls (`VARKA-345.md` item 0.2's table). That is the only reference a contract entry compares
  with.
- **The compositions** are measured, but under 345's settled oracle (its open point 2, (A)) a
  composition over a `LIBRARY` node is compared bit for bit against the library reference, so its
  line feeds no contract entry (2.4, open point 2).
- **"A bisection search"** cannot bisect on the step distance, which is not monotone in the input.
  2.3 says what the search is.
- **The worst-case tables need the owner's yes** before they are fetched (question 1). Until then,
  the fixed vectors are the constants of item 0.9.

## 1. Goal and non-goal

**Goal.** For each of the 18 operators and the 8 compositions, on each host library and species
the opt-in mode can bind, a committed measured line of the largest step distance the probe
finds against the method Spark calls, with its witness. MPFR's correctly rounded value says
which side is wrong at the witness and how often each side misrounds. The draws reach the
whole domain, the switching regions, the SLEEF band and `pow`'s guarded domain, and a
lane-independence arm checks that no lane reads its neighbours. Every line is stated as a lower
bound of the true maximum.

**Non-goal.** No contract entry: entries are each function row's last step (`VARKA-356.md` 5,
step 6). No Varka kernel is run: the probe measures the library operators, and 345's library
reference holds the kernels to them (its 2.8). No change to the exact default, and no timing.
The binding check is 345's (`VarkaMathBindingSuite`).

## 2. Design

### 2.1 Where the probe lives, and its comparator

The probe stays a canary, so it answers on both runner architectures in minutes with no Spark
build. The new work is an `extended` argument to `MathLaneProbe`. The default run, the push
trigger's, keeps today's draws and output, so the September and October readings stay
reproducible. The default run gains only two changes: `java.runtime.version` on the machine line
(item 0.6), and `steps` in place of the raw-bit distance.

`steps` is 345's three-rule comparator (`VARKA-345.md` 2.5):

- two NaNs, or one infinity against itself, give 0;
- a NaN against a number, an infinity against anything else, or opposite signs give
  `Long.MAX_VALUE`;
- otherwise, the raw-bit difference.

The canary cannot reach catalyst (item 0.7), so the comparator is a second file beside it,
`dev/varka_canary/MathSteps.java`. JDK 25's source launcher puts the launched file's source
root on the source path, so a default-package sibling is compiled on demand (the
launcher's `MemoryContext.java:87-88` under `src/jdk.compiler`, JEP 458). `VarkaMathBoundSuite`
(345's test 3.5) holds this copy to `VarkaMathBound.steps` on its pairs and on 2^24 random
bit-pattern pairs, so the two cannot drift (open point 1).

### 2.2 The draws, per form and species

Each operator loop keeps today's shape: the operator a constant in its own loop, every operator
touched once before anything is hot, then warmed until C2 compiles it, which the control pass
and the witnesses check as today. The extended run measures each form at each species the host
binds:

- the laptop at 512 bits (`z0`) and at 256 bits (`l9`);
- the EPYC runner at 256 bits (`l9`);
- the Arm runner at 128 bits (`advsimd`).

128 bits on x86 is the same `l9` symbol as 256 bits and is run once on the laptop to check
that. On every x86 runner the workflow runs the 256-bit species explicitly, so an `l9` line
exists whatever CPU the dispatch lands on, and `SPECIES_PREFERRED` as well, which gives `z0`
lines when it lands on an AVX-512 part. The species comes from a system property read into a
`static final`, so it is a constant for C2. The draws, each `2^24` inputs:

1. **bits**: uniform 64-bit patterns, every class of double included;
2. **domain**: uniform over the function's finite-result domain (for `exp`, below the overflow
   point; for `asin` and `acos`, `[-1, 1]`; for the log family, the positive half);
3. **near**: `[-2, 2]` and magnitudes down to `2^-60`, 345's draws (its 2.3);
4. **regions**: 64 neighbours each way of every switching-region constant of item 0.9, and the
   function's fdlibm thresholds where its `.vectors` file has them;
5. **fixed**: the `.vectors` file, SLEEF's special-value list and Spark's edges (the log
   family's `0.0`, `-0.0` and `-1.0`; `asinh`'s and `acosh`'s `2^28`), and the SLEEF band,
   both ends, its middle and the doubles just outside;
6. **search**: 2.3.

The binary forms (`atan2`, `hypot`, `pow`, the two-argument `log`) draw `(x, y)` pairs the same
ways, with every pairing of zeros, infinities and NaN in **fixed**. Once a function row adds
its `.vectors` file, the workflow's sparse checkout adds `sql/varka/math`; the probe reads a
file where it exists and uses the constants of item 0.9 where it does not.

### 2.3 The search for the largest distance

The step distance is not monotone in the input, so there is nothing to bisect on. The search
is a sweep. From each of the 16 largest witnesses of draws 1 to 5, and from each region
boundary, it evaluates 64 neighbours each way at strides of `2^k` steps, for `k` from 40 down
to 0, and restarts from any new maximum. Bisection is used for one thing only: to find where a
run of differing lanes the sweep crossed begins and ends, so that the boundary is swept
densely too. The search is bounded at `2^20` evaluations a form. Its result is still a lower
bound of the true maximum, and every line says so.

### 2.4 The compositions

Each of the eight is computed as Spark writes it (`VARKA-356.md` item 0.1), but over the
library operator in lanes, which is its opt-in form:

- `sec`, `csc` and `cot` as `1.0 /` the lane `COS`, `SIN` or `TAN`;
- `log2(x)` as `LOG(x) / c`, `c` the bits of `StrictMath.log(2)`;
- the two-argument `log` as `LOG(x) / LOG(b)`;
- `atanh` as `0.5 * (LOG1P(x) - LOG1P(-x))`;
- `asinh` and `acosh` as `ExpressionImplUtils`' branches over the lane `LOG`, `LOG1P` and `SQRT`,
  blended.

Each is compared with Spark's scalar expression. Its line has the kind `composition`. A
contract entry never cites it: under 345's oracle (A) a composition over a `LIBRARY` node is
held bit for bit to the library reference, not to a bound. The line serves the docs' statement
of what a user of the mode sees (open point 2). That an emitted composition equals this
lane-wise one is 345's library-reference test, not this row's.

### 2.5 `pow`'s guarded domain and the SLEEF band

- **The guarded domain.** 358's opt-in `pow` declines a batch with `|y| > 2^31` and
  `||x| - 1| <= 2^-20` (row 358). Its line is measured on draws that exclude that domain,
  with three draws of their own: `x` within `2^-10` of 1 just outside the guard, Intel's near-one
  region `[1 - 1/32, 1 + 1/16)`, and negative `x` with integral `y`. The guarded domain itself is
  measured as an informational line of kind `declined`, so the reason for the guard is on
  record. A `pow` line exists only where `POW` is bound: `z0` and SLEEF; the `l9` key gets an
  `UNBOUND` line (item 0.5).
- **The SLEEF band.** On SLEEF, `EXP` and `POW` give infinity in the band where `StrictMath` is
  finite (PLAN.md 10.1 item 1). `steps` puts such a lane at `Long.MAX_VALUE`. It becomes a line
  of kind `band` with its witness and count, never folded into a maximum. 358's output guard is
  what the mode does about it.

### 2.6 The lane-independence arm

SLEEF's own tests put the argument in one random lane and random bits in the others
(`READING_SLEEF.md` 1 and 6.1). For each form, `2^16` arguments are each placed in a random lane
of a vector whose other lanes hold a filler: random bits, `1e300`, NaN, a subnormal or `-0.0`
(the slow-path triggers of `VARKA-345.md` item 0.9). The argument's lane must have the same bits
as in the draw where every lane holds an ordinary value. A difference is a line of kind
`lane-independence` with its witness, filler included. This is the answer half of 345's
dead-lane rule. 345 owns the timing half.

### 2.7 The true value, from MPFR

The probe binds `mpfr_init2`, `mpfr_set_d`, the 26 forms' functions (`mpfr_sin` to
`mpfr_hypot`; `mpfr_sec`, `mpfr_csc` and `mpfr_cot`; `mpfr_log2`; `mpfr_pow`), `mpfr_get_d`
and `mpfr_clear` through the Foreign Function and Memory API from `libmpfr.so.6`. An `mpfr_t`
is a 32-byte segment of a confined arena, allocated once per thread. Each value is computed at
256 bits and rounded with `MPFR_RNDN`. The two-argument `log` is `log(x) / log(b)` at 256 bits.
At 256 bits a double rounding is possible in principle, so the value is a correctly rounded
candidate, not a proof **[I]**. The probe states that, and it counts a witness where MPFR's
ternary result is exact.

Where it is called:

- on every lane where the library and Spark's method differ in draws 4 and 5 and in the search;
- on a seeded sample of `2^16` such lanes in each of draws 1 to 3;
- on a fixed seeded sample of `2^20` lanes a form, whether they differ or not, for the
  misrounding rates.

A missing `libmpfr.so.6` turns the column off, with a line saying so; the run still writes its
lines. On a runner without the library, the workflow installs `libmpfr6` from Ubuntu's archive
(question 3). Its `Describe the machine` step prints `ldconfig -p | grep mpfr` first.

### 2.8 The lines

One TSV per host, `results/VARKA-367-<host>.tsv` (345's format), and beside it a text summary
of the same name, `.txt`, which `dev/varka_quote_check.py` reads, unlike a TSV. The hosts are
named from the probe's own CPU line, not presumed: `x86-avx512-zen5` for the laptop, and for
the runners whatever CPU the dispatch lands on (`x86-avx2-epyc7763` and `aarch64-neoverse-n2`
for the committed readings' hosts). One line per form, binding, species and draw:

- 345's columns: function, library, symbol (or `UNBOUND`), species bits, JDK build
  (`java.runtime.version`), CPU, inputs, lanes differing, largest steps, and the witness's
  input, library and Spark bits;
- this row's: `kind` (`operator`, `composition`, `declined`, `band`, `lane-independence`), the
  draw, the witness's MPFR bits, and over the MPFR sample, counts of lanes where only the
  library misrounds, only Spark does, or both.

The text summary carries the counts against the other class (the row's "both references"), the
misrounding rates as percentages, the run id or the hand run's provenance, and the control
pass's per-element times. The columns this row adds are a change to 345's format (open point 3).

## 3. Tests and proofs

1. **The comparator copy**: `VarkaMathBoundSuite` holds `MathSteps.steps` to `VarkaMathBound.steps`
   on 345's pairs and on 2^24 random pairs (345a's suite, with one test added).
2. **The probe's self-check** (`MathLaneProbe selftest`, no library involved): `steps` on
   345's refused pairs; a planted lane two steps out shows as 2 with its witness; a planted
   neighbour-reading lane fails the lane-independence arm; an MPFR value of `sin(1.0)` and of
   `exp(709.0)` against `StrictMath` where it is correctly rounded. It is run by the workflow
   before the extended pass, and a failure ends the job.
3. **The C2 check, per loop**: the witnesses and the control pass as today. An extended line
   whose loop shows no witness difference on ordinary inputs, where the default run's reading
   had some, is marked `unconfirmed` and never cited.
4. **The default run unchanged**: the push run's output, apart from the machine line, matches
   the committed files' structure. Their counts move only by the run's own variance, since the
   inputs are the same seeded draws.

## 4. Predictions and costs, registered before the run

1. **The maxima stay small on the ordinary draws.** Against the class Spark calls, 1 step for
   every bound operator on draws 2 and 3, and 2 for `LOG10` everywhere, the committed readings'
   maxima (item 0.4). `TANH` is 1 against `Math`, which Spark calls; its 2 against `StrictMath`
   stays in the summary's other column. **[I]** on the whole domain: a few steps more near the
   switching regions is possible, and no number is registered.
2. **`Long.MAX_VALUE` lines appear on SLEEF only**: `band` lines for `EXP` and `POW`, and none on
   SVML, whose band lanes equal `StrictMath` (PLAN.md 10.1 item 1). Draw 1 adds opposite-sign
   or NaN-against-number lines only where a library mishandles a special value. None is
   expected **[I]**.
3. **Both sides misround.** fdlibm and the vector libraries are not correctly rounded, so the
   MPFR sample finds lanes where only Spark is wrong as well as lanes where only the library
   is; direction only, since no source states a rate (`READING_LIBM.md` 5).
4. **No lane-independence failure** on any host **[I]**.
5. **The cost.** About `2^24` inputs times six draws times 26 forms, each lane evaluated by the
   library and by two scalar classes at roughly the control pass's 10 to 20 ns, is a few minutes
   a species, and the MPFR calls are about `2^21` a form at a few microseconds each **[I]**. At
   the calibration table's ratio of 2 for a new check of its kind (VARKA-243), the extended pass
   takes under 30 minutes a runner. The push run is unchanged, since `extended` is a dispatch
   argument.

## 5. Order, files and size

1. **367a, the probe.** `MathSteps.java`; the extended draws, the four operators, the
   compositions, the guarded `pow` and band lines, the search, the lane-independence arm, the
   MPFR binding, the TSV and text writers, the self-check; the workflow's `extended` path (the
   self-check first, the sparse checkout of `sql/varka/math`, the MPFR check, the TSV in the
   artifact). The laptop run at 512, 256 and 128 bits under a heavy slot, with
   `SPARK_LOCAL_IP=127.0.0.1` as for every local JVM, committed as `x86-avx512-zen5` with its
   provenance (question 2).
2. **367b, the runners.** One dispatch of `varka-canary.yml` with `args: extended`. Its two
   TSVs and summaries are committed from the artifact, with the run id in each summary.
   `VarkaMathContractSuite` (345a) is then run on the three files.
3. **367c, the worst-case tables**, after the owner's yes (question 1): the inputs transcribed
   with their sources into the `.vectors` files or a file of this row's, and the three hosts run
   again.

| file | what |
|---|---|
| `dev/varka_canary/MathLaneProbe.java` | `extended`: draws, search, arms, MPFR, self-check |
| `dev/varka_canary/MathSteps.java` (new) | 345's comparator, for the canary |
| `.github/workflows/varka-canary.yml` | the `extended` path, the MPFR check, the sparse checkout |
| a test in `VarkaMathBoundSuite` | the copy against the catalyst comparator (345a's suite) |
| `sql/varka/plans/m8/results/` | `VARKA-367-<host>.tsv` and `.txt`, three hosts |
| `sql/varka/skills/` (the testing file) | how to run the extended probe on a new JDK build |

Placement. 367a needs only 345's text. Its test 1 needs 345a's `VarkaMathBoundSuite`, and it
reads the `.vectors` files once 345b and the function rows add them. Its lines are needed by
nothing before 356 to 358's step 6, the opt-in entries, each the last step of its row.
Recommended: open 367a after 345a merges, and 367b after the first function row's `LANE_CALL`
pull request (358a) has added its `.vectors` files. That puts the lines in place before any
step 6, and spends no effort on the opt-in mode ahead of the exact forms, which deliver every
function (open point 4).

**Size: medium, as the row says.** 367a about three days: the draws and search one, the MPFR
binding and its self-check one, the compositions, arms and writers one. 367b half a day. 367c
half a day after the yes. About four days in two pull requests and a follow-up.

## 6. Out of this row

| piece | owner |
|---|---|
| `VarkaMathBound`, the TSV reader, the contract and binding suites | 345 |
| the `.vectors` format and its reader; the library reference | 345 |
| each function's `.vectors` file, its opt-in entry citing these lines | 356 to 358 |
| `pow`'s guard and the SLEEF output guard in the kernel | 358 |
| re-measuring on each new JDK build CI adopts | a dispatch and a commit; no automation |

## 7. Questions for the owner

1. **The worst-case tables.** The row names "the handbook's and Gladman's worst cases", and
   neither is in the tree (item 0.9). The sources are: Gladman, Innocente, Mather and
   Zimmermann's "Accuracy of Mathematical Functions in Single, Double, Double Extended, and
   Quadruple Precision", whose tables give each library's worst inputs found; and the Handbook of
   Floating-Point Arithmetic's tables of hardest-to-round cases. The plan has read neither, so
   which edition, tables and pages is yours to name. Options: (a) fetch the paper with your yes,
   transcribe it into `sql/varka/papers/` by the house rule, and take its inputs as vectors with
   the citation; enter the handbook's cases by hand from your copy, with page numbers. (b) The
   paper only. (c) Neither: the switching regions and the search stand alone. Recommended: (a)
   if you have the book, else (b). The inputs are facts and the plan copies no code, but the
   licence of each source is yours to check first, as it was for `e_exp.c`.

   *1 November 2026, by the owner's word:* both: fetch Gladman et al.'s paper with the owner's yes,
   transcribed into `papers/`, and enter the Handbook's worst cases from the owner's copy.
2. **The laptop as the third host.** SVML's `z0` symbols bind only at 512 bits (item 0.4). The
   x86 runner pool mixes AVX2 and AVX-512 CPUs (`TestSQLContext.scala`'s comment on
   `VARKA_EMIT_USE_AVX`, VARKA-538), but a dispatch cannot choose its CPU, so a `z0` reading on a
   runner is luck, not a plan step. The laptop is the only host where `z0` is reproducible. The
   rule of 26 October puts verification on CI and keeps the laptop for debugging. Should the
   laptop's `z0` run count as the third host library, committed by hand with its provenance as
   the September file was? Recommended: yes, and a runner that happens to be AVX-512 adds its
   `z0` lines as well. A `z0` line is keyed to its own JDK build, so it serves that build only.
3. **MPFR.** GNU MPFR is LGPL. The probe calls the installed shared library through the Foreign
   Function and Memory API; it copies no code, ships nothing, and is a dev tool. Where a runner
   lacks it, the workflow would install `libmpfr6` from Ubuntu's own archive. Is that acceptable
   under the licence rule and the tools rule? Recommended: yes. The rules forbid translating GPL
   code and running tools of unknown provenance; this is neither.

   *1 November 2026, by the owner's word:* yes: the probe may call the installed MPFR library,
   installing `libmpfr6` from Ubuntu's archive where a runner lacks it; no MPFR code is copied.

## 8. Done when

- The extended probe has run on each of the three host libraries: SVML `z0` on the laptop, SVML
  `l9` on an x86 runner and the laptop, and SLEEF `advsimd` on the Arm runner. Each run's TSV
  and summary are committed under `m8/results/`, with every form, every species the host binds,
  `UNBOUND` lines where a symbol is missing, and the guarded `pow`, band and lane-independence
  lines.
- Each line has a witness, and an MPFR value wherever the library was found.
- `VarkaMathContractSuite` reads all three files.
- The copy of `steps` is held to 345's comparator.
- Every opt-in contract entry the function rows later enter cites one of these lines.

## 9. Open points for the lead

1. **The comparator's home.** A copy beside the canary, held to the catalyst class by test 1
   (2.1, recommended), or the probe moved into catalyst's test tree to call `VarkaMathBound`
   directly. Moving it loses the canary's no-build run on both architectures, and dispatching on
   Arm would then need a Spark build there.
2. **The composition lines.** Kept as informational lines of kind `composition` (2.4,
   recommended), which give the docs a measured statement of what a mode user sees for `sec` or
   `asinh`; or dropped, about half a day less. Under 345's oracle (A), neither choice gives a
   composition a contract entry. What 345's docs column says for a composition in the mode is
   345's to settle (its 2.10).
3. **The format's added columns.** `kind`, the draw and the MPFR columns (2.8) extend 345's line.
   345a builds the reader, so either 345a takes them now or this row's pull request extends the
   reader. Recommended: 345a, since the format is not built yet. The rates go in the `.txt`
   summary because `varka_quote_check.py` reads only `*.txt` results; adding `*.tsv` to its
   globs would be the other way.
4. **The order** (section 5). The plan recommends 367a after 345a and 367b after 358a. That
   keeps the opt-in mode behind the exact forms, in line with milestone 8's focus on the new
   types.
5. **The production key.** Keyed by the full JDK build (345's open point 4), a contract entry
   serves only the builds measured: the runners' Zulu build and the laptop's Ubuntu build. A
   user's JDK will have no entry, and the opt-in mode will run the exact form there. That
   follows from 345's settlement and is not reopened here. The skill's recipe (section 5) is
   how a deployment would measure its own build.

## 10. Outcome

(Written when the work lands.)
