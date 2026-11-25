# VARKA-640: The default `BIGINT` to `FLOAT` form at each width, on every AVX2 host

## 1. Where this came from

Row 600's part 600a (#1625, head `ed6d4ace513`) added two forms of the AVX2 `L2F` cast behind the
option `narrowConversionForm`: `WINDOWED` and `SCALAR`, beside today's default `ROUND_TO_ODD`. Its
first runner runs, one a width on the EPYC 7763, are in `results/VARKA-640-wide-conversion-runs.txt`
(run 45071805478 at 256 bits, run 45071808355 at `-XX:MaxVectorSize=16`). Rates in M/s:

| rung | width | round to odd | windowed | scalar lanes | scalar loop |
| :--- | ---: | ---: | ---: | ---: | ---: |
| L2 | 256 | 1608.0 | 2995.2 | 1123.2 | 2277.8 |
| L2 | 128 | 846.5 | 1862.7 | 1663.7 | 2277.8 |
| past L3 | 256 | 1341.4 | 1652.1 | 1027.7 | 1573.4 |
| past L3 | 128 | 811.2 | 1665.1 | 1521.1 | 1864.2 |
| L2, every value past 2^51 | 256 | 1606.4 | 1214.1 | 1122.4 | 2280.6 |
| L2, every value past 2^51 | 128 | 845.6 | 682.8 | 1663.7 | 2268.0 |

These are single runs, one a width, and the house rule asks for a ratio under about 1.3 times
to be re-run and compared by minimums before it is relied on; so the readings below are
directions until section 4's runs. `WINDOWED` beats today's default by about 1.9 times at 256
bits and 2.2 times at 128 in L2. Past L3 it reads ahead at both widths, by 1.23 times at 256
bits, a margin the re-run has to confirm. When every value is past `2^51`, where each group
takes the slow branch, it reads behind today's default: 0.76 of it at 256 bits and 0.81 at 128,
outside the 10% of `VARKA-600.md` 6.1 prediction 4. Today's default is slower than the scalar
loop in cache at both widths. At 128 bits no vector form reaches the scalar loop in L2, and
there `SCALAR` beats both vector forms when every value is past `2^51`; at 256 bits it is the
slowest form on that rung.

The owner approved this row on 23 November 2026 ("add a row"). His rule that a default may
differ by width, read from the emitted lane count, applies (row 600's plan, `VARKA-600.md` 3.1).

## 2. The admission check, done

The two runs stand as the only readings of the three forms at both widths. Each width has one
run, where `VARKA-600.md` 6 asks for two compared by minimums, and both are on one CPU model.
So nothing here is decided yet. The check rejects a default chosen from these two runs alone.
The re-runs of 23 November 2026 are listed in section 5.

## 3. What this row decides, beside row 600

Row 600's step 4 (`VARKA-600.md` 8) sets the 7763's default per width by its own rule: the
fastest form in L2 and L3 among those at least as fast as the scalar loop, else `SCALAR`. This
row owns what that rule does not weigh:

1. **The data.** The rule reads only rungs inside the window. A `WINDOWED` default is slower than
   today's on a column whose values are all past `2^51`. Decide whether that is acceptable, or
   whether the form needs a cheaper slow branch (at 128 bits, the `SCALAR` lanes in place of
   round to odd; at 256 bits `SCALAR` is slower than round to odd there),
   and measure a mixed rung, which `VARKA-600.md` 7 item 3 leaves unplanned.
2. **The 128-bit case.** No vector form reaches the scalar loop there, and `SCALAR` reads 73% of
   it, outside the 15% of `VARKA-600.md` 6.1 prediction 3. Decide between `SCALAR`, `WINDOWED`
   with a scalar slow branch, and a decline to the row path, which row 600's done-when allows
   only where a baseline shows the fused cast slower than Spark's.
3. **The hosts.** The forms run where `useAVX=2` resolves. Repeat both widths on the 7763 and
   run them on the EPYC 9V74 (`sql/varka/HARDWARE.md`), the other AVX2 runner, so that the
   default does not rest on one CPU model. The laptop under `-XX:UseAVX=2` is a Zen 5 forced to
   AVX2, so its runs serve diagnosis only and choose nothing.

## 4. Done when

The default form at each width is chosen from at least two runs a width on the 7763 and one on
the 9V74, past-window and mixed rungs included. The result is recorded in section 5 with the
runs' ids. A default that loses on a rung says by how much and why that is accepted, and the
losing forms stay as options. Row 600's 600b ships the first per-width default by its own rule;
this row does not hold it back, and confirms or revises that default afterwards.

## 5. Outcome

Not decided. The re-runs of section 4 are recorded, the mixed rung is not.

On 23 November 2026 `VarkaWideConversionBenchmark` ran six times at master `241125b2f37`
(`benchmark.yml`, `create-commit=false`). Each run's provenance and results are copied into
`results/VARKA-640-wide-conversion-runs.txt` byte for byte, and each CPU below is the one its
provenance names:

| CPU | width | runs |
| :--- | :--- | :--- |
| AMD EPYC 7763 | 256 bits | 45125914714, 45125932383 |
| AMD EPYC 7763 | 128 bits (`-XX:MaxVectorSize=16`) | 45125949427, 45125966020 |
| AMD EPYC 9V74 | 256 bits | 45125982176 |
| AMD EPYC 9V74 | 128 bits (`-XX:MaxVectorSize=16`) | 45125998303 |

That is the run count section 4 asks for, with the past-window rung. The ladder on master has no
mixed rung, so section 4 is not met and no default is chosen here; the mixed rung needs code
first.
