# Reading for milestone 8: what SLEEF offers the math family

Read on 15 October 2026 at the owner's request: what do SLEEF's sources and tests give rows 345,
346, 357, 358, 367 and `m10/SCOPE.md` 98? Paths are relative to `/home/max/proj/sleef` at commit
`7623d6c` (25 December 2025). **The clone is not what the JDK binds**: its `CMakeLists.txt` says
`SLEEF_VERSION 4.0.0` (development, after 3.9); the JDK 25 binds 3.6.1; the clone is shallow, so
no diff against 3.6.1 was possible. `CHANGELOG.adoc` names 3.8 "Fix oflow bound in log1p(f), exp
and pow" (#604, #606), so those overflow thresholds in 3.6.1 may differ from the code below;
3.9 moved the tests to TLFloat (tester4). The rest is 3.6.1 behaviour only if unchanged [I].
Kernels: `src/libm/sleefsimddp.c`, `src/common/dd.h`, `src/arch/helperadvsimd.h`; tests:
`src/libm-tester/`. **[V]** means the author read the code; **[I]** means inferred. Nothing was
built or run. Lane-op counts are my hand counts of the non-AVX-512 path on an FMA target (one
vector instruction = one op), good to about 10 percent.

## 1. What the tiers promise and how they are checked

* **Names [V].** `rename.h`: `xexp` is `Sleef_exp_u10`, `xlog_u1` is `Sleef_log_u10`, `xlog` is
  `Sleef_log_u35`; the two `hypot` tiers are separate kernels; `xfmod` has no tier.
* **Bounds the tests enforce [V].** `u05`: 0.5 (`tester2simddp.c`: `hypot_u05` and `fmod` fail
  above 0.5), so correct rounding up to the reference's precision; `u10`: 1.0 (exp, log_u1,
  pow, tanh, expm1, log10, log1p; `exp10` is allowed 1.09); `u15`: 1.5; `u35`: 3.5. The 0.5001
  of SLEEF's reference pages is not in the clone.
* **What a ULP is [V].** `testerutil.h` `countULP`: `|result - exact| / 2^(ilogb(exact)+1-53)`,
  floored at the subnormal quantum; NaN against NaN is 0, NaN against a number 10001, zero
  against nonzero 10000, signed-zero mismatch 10002 only when asked. The reference is the exact
  value (MPFR at 64 or 256 bits; binary128 in tester4), not another library; Varka's step count
  is a distance between two libraries' doubles.
* **Consequence for 345 [I].** A result within 1.0 of the exact value is one of the two
  neighbours of it, so a `u10` kernel and a fdlibm kernel also within 1 ulp are 1 step apart
  (2 if the exact value is a double); `u35` allows about 4. This is why 367 must measure.
* **How the tests draw inputs [V].** None is exhaustive (`grep -i exhaust` over `src`, `docs`
  and the top-level files finds nothing). `tester2simddp.c` loops until 1000 failures on
  uniformly random 64-bit patterns (so most `exp` arguments saturate), with a few draws as
  neighbours of 0 and infinity. `tester.c` and `tester4simd.cpp` sweep fixed grids: `exp` over
  -10..10 at 0.002 and -1000..1000 at 1.1, and near `LOG_DBL_MAX` at 1e-5; `log` over 0.0001..
  10000, `2.1^i`, `DBL_MAX * 0.93^i`, `DBL_MIN * 0.996323^i`; `expm1` at `10^-d`; `pow` on a
  100 by 100 grid; `fmod` on two grids; and the list `ad` (NaN, +-inf, +-DBL_MAX, +-DBL_MIN,
  +-denorm_min, +-0, +-1, nextafter(+-1)). Signed zero is checked only on `ad` (tester4).
* **Lane independence [V].** Each test puts the argument in one random lane and random bits in
  the others (`memrand`), so a result that depends on a neighbour lane fails.
* **Special values follow C99 [V].** `tester.c` lines 841 and 844: "x is +1 and y is a NaN, the
  result is 1.0"; "y is 0 and x is a NaN, the result is 1.0".

## 2. Structure of the kernels (non-AVX-512 path, `sleefsimddp.c`)

* **`exp` u10, line 1404 [V].** `n = rint(x/ln2)`; `s = x - n*L2U - n*L2L` (two `vmla`; `L2U`
  has about 42 bits, so `n*L2U` is exact); Estrin `POLY10` for degrees 3 to 12, then Horner
  steps for 0.5, 1, 1; scale by `ldexp2` (multiplies by `2^(q/2)` and `2^(q-q/2)`); `x >
  LOG_DBL_MAX` gives +inf, `x < -1000` gives +0; NaN flows through. Count: 20 arithmetic ops,
  11 for the scaling, 4 for the clamps, about 35 in all. **Item 98's 25 matches the arithmetic
  without the scaling** [I].
* **`log` u35, line 1358, and u10, line 1510 [V].** Subnormal rescale by `2^64` (and `e - 64`);
  `e = ilogb(d/0.75)`, `m = d * 2^-e` in [0.75, 1.5); `x = (m-1)/(m+1)`;
  `log d = e ln2 + 2x + x^3 t(x^2)` with `POLY7` in `x^2` (degree 15 in x). u35 does it in plain
  doubles with one division: about 40 ops. **u10 does `m-1`, `m+1`, the division, `e ln2`, `2x`
  and the sum in double-double** (`dd.h`): about 70 ops. **The JDK binds u10 for `LOG`, so item
  98's 45 is the u35 shape, whose bound is 3.5** [I, JDK note plus these counts]. Specials are
  selects: `+inf`, NaN or negative to NaN, zero to `-inf`. `log10` (2047) is the u10 shape with
  a dd multiply by 2/ln10, about 8 ops more; `log1p` (2197) is the u10 shape on `m = fma(d,
  2^-e, 2^-e - 1)`, which recovers the bits lost by `d + 1`.
* **`pow`, line 1596 [V].** `logk` (a full double-double log, about 90 ops) times `y` in dd,
  then `expk` (a double-double reduction and polynomial with the same scaling, about 75 ops),
  plus `visint`/`visodd` (one rint each) and about 10 selects: roughly 200 ops [I].
* **`fmod`, line 2430 [V].** `|y| < DBL_MIN` rescaled by `2^54`; remainder `r` held as dd; a loop
  of at most 21 passes, each `q = trunc(toward0(r) * toward0(1/d))`, two corrections to 1 or 2,
  `r = normalize(r - q*d)` (exact: dd product by FMA); it leaves early when every lane has
  `r < d`; then `x`'s sign, `|x| < |y|` returns `x`, `y == 0` is NaN. About 30 ops and one
  division per pass; one pass while `|x/y|` is small [I]. Exact for `|x/y| < 1e300`, the tests'
  own limit.
* **`hypot` u05, line 2383 [V].** dd `min/max`, dd square, `+1`, dd square root, times `max`;
  subnormal rescale by `2^54`; inf overrides NaN, zero `min` returns `max`. u35 is `max *
  sqrt(fma(t, t, 1))`: about 8 ops, no dd.
* **`tanh` u10, 1693; `expm1`, 2038 [V].** Both sit on `expk2`, a dd `exp`: `tanh = (e - 1/e) /
  (e + 1/e)` in dd (1 above 18.714973875); `expm1 = expk2(a) - 1` in dd (fixed overflow and
  underflow cut-offs, `-0.0` kept). Not fdlibm's formulas, so no step-for-step match with Spark.
* **Specials of `pow` [V].** `y == +-inf` follows `(|x|-1) * sign(y)`: below 0 gives +0, equal
  gives 1, above gives +inf. A negative `x` with a non-integer `y` multiplies by NaN. The last
  select, `y == 0 || x == 1` gives **1**, comes after the NaN or-in, so `pow(1, NaN)`,
  `pow(NaN, 0)` and `pow(-1, +-inf)` are 1: C99, as 2.7.1 says, and unlike Java. `pow(+-0, y)`
  and `pow(+-inf, y)` take their sign from an odd integer `y` (`visodd`).

## 3. FMA, non-FMA, and bits

* **Two code paths exist [V].** `vmla` is fused only where the helper says so
  (`helperadvsimd.h` line 378: `CONFIG == 1`); `ENABLE_FMA_DP` selects one set of dd routines
  (`dd.h` lines 190 to 236), otherwise another that splits operands with `vupper` (Veltkamp).
  The file sets `FP_CONTRACT OFF`, so every fusion is explicit.
* **Do they give the same bits? Not stated, and not tested between the two [V].** Each variant
  is tested against the exact value. What is tested for bit identity is the FMA family:
  `tester3.c` writes a SHA-256 over 200001-point grids (`exp` -1000..1000, `log` 1e-300..1e14
  by bit pattern, `pow` over 451 by 451, also `hypot`, `fmod`, `tanh`, `expm1`) and compares it
  with **one file**, `hash_finz.txt`, for every ISA target. The SSE2 helper makes this possible
  by emulating `vfma` in software (`tlfloat_fma`, `helpersse2.h` lines 181 to 189), so a CPU without
  FMA still gets the fused bits. That is the intended contract: one bit pattern per function
  across ISAs for `finz`. A mul-plus-add `vmla` rounds twice, so the non-FMA build will differ
  somewhere [I]; there is no `cinz` hash file in the clone.
* **For Varka [I].** Item 98's "one bit pattern on every host" has a precedent and a test
  shape: pin the FMA semantics (`DoubleVector.fma` is exact, so it is the `vfma`) and compare a
  digest over fixed grids with the argument in a random lane. Whether the JDK's 3.6.1 build is
  `finz` advsimd (FMA) was not read.

## 4. What does not vectorise without a branch

* **`fmod`/`remainder` loops** with an all-lane exit (`vtestallones`) and up to 21 passes;
  one slow lane holds the others. Row 346's single-FMA remainder with a decline above `2^53`
  is the same loop cut to one pass.
* **`log1p` calls a whole second kernel**: `if (!LIKELY(vtestallones(d <= LOG1P_BOUND))) r =
  select(.., xlog_u1(d))`, for `d > 1e307` (line 2239 [V]). A fused version needs both
  paths computed, or a guard.
* **AVX-512 `log` uses `getexp`, `getmant`, `fixup`**, with no Vector API equivalent; an emitted
  kernel takes the integer path (exponent bits; `ldexp3` adds to the exponent field).
* **`rint` and `trunc` want hardware rounding** (`FULL_FP_ROUNDING`); otherwise `vrint2` is
  `(d + c) - c` with `c = +-2^52`. Branch-free and expressible in Java, which never reassociates.
* **`ldexp2` and `ilogb2k` use half-width `vint`** with 64-bit lanes; Java would shift in 64 bits.

## 5. gencoef

* **Two phases [V].** (1) A simplex linear program (`simplexfr.c`, 4096-bit MPFR) minimises the
  maximum relative error on `S` (40 to 60) samples. (2) A hill climb moves each coefficient by
  0, +1 or -1 ulp at random and scores the maximum ULP over `Q` points, the leading `L`
  coefficients in extra precision.
* **Not reproducible [V].** Phase 2 is `srandom(time(NULL))`, and the parameters are `#if 0/1`
  blocks in `dp.h` edited by hand (`gencoef.txt`: "many iterations of trial and error"). The
  enabled block is `exp`, `N 12`, range `+-0.347`, but the shipped `xexp` has degree 12 (13
  coefficients), so the committed state does not reproduce the shipped table [I]. The constants
  in `sleefsimddp.c` are the data; copying them, with the licence, is the only exact route.

## 6. Worth taking

1. **The ULP-against-exact definition and the random-lane filler** for 345's oracle and masks.
2. **The digest regression across ISAs with software FMA** for item 98 and 367's keys.
3. **The list `ad`** and the C99 `pow` cases as the seed of the exact-value table.
4. **`xfmod` as 346's second oracle is sound** to `|x/y| < 1e300`, beyond the 2^53 guard.
5. **Cost shape**: `exp` about 35 ops, `log` u35 about 40, u10 about 70, `pow` about 200; item 98
   should say which `log`.

## 7. Not worth taking

The dd towers for `tanh`, `expm1`, `pow` (cost; the JDK does not bind `TANH`), the AVX-512
`getexp` paths, `gencoef`, DFT, quad and single precision. Not read: the JDK's `libsleef` build
flags, the 3.6.1 sources, SVE and RVV helpers, `sinh` and `cosh`.
