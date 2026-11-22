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

`WINDOWED` beats today's default by about 1.9 times at 256 bits and 2.2 times at 128 in L2, and
still wins past L3. But it loses to it when every value is past `2^51`, where each group takes
the slow branch: by about a quarter at 256 bits and a fifth at 128. Today's default is slower
than the scalar loop in cache at both widths. At 128 bits no vector form reaches the scalar
loop in L2, and `SCALAR` beats both vector forms when every value is past `2^51`.

The owner approved this row on 23 November 2026 ("add a row"). His rule that a default may
differ by width, read from the emitted lane count, applies (row 600's plan, `VARKA-600.md` 3.1).

## 2. The admission check, done

The two runs stand as the only readings of the three forms at both widths. Each width has one
run, where `VARKA-600.md` 6 asks for two compared by minimums, and both are on one CPU model.
So nothing here is decided yet. The check rejects a default chosen from these two runs alone.

## 3. What this row decides, beside row 600

Row 600's step 4 (`VARKA-600.md` 8) sets the 7763's default per width by its own rule: the
fastest form in L2 and L3 among those at least as fast as the scalar loop, else `SCALAR`. This
row owns what that rule does not weigh:

1. **The data.** The rule reads only rungs inside the window. A `WINDOWED` default is slower than
   today's on a column whose values are all past `2^51`. Decide whether that is acceptable, or
   whether the form needs a cheaper slow branch (the `SCALAR` lanes in place of round to odd),
   and measure a mixed rung, which `VARKA-600.md` 7 item 3 leaves unplanned.
2. **The 128-bit case.** No vector form reaches the scalar loop there, and `SCALAR` reads 73% of
   it, outside the 15% of `VARKA-600.md` 6.1 prediction 3. Decide between `SCALAR`, `WINDOWED`
   with a scalar slow branch, and a decline to the row path, which row 600's done-when allows
   only where a baseline shows the fused cast slower than Spark's.
3. **The hosts.** The forms run where `useAVX=2` resolves. Repeat both widths on the 7763 and
   run them on the EPYC 9V74 (`sql/varka/HARDWARE.md`), which runs the same code, and on the
   laptop under `-XX:UseAVX=2`, so that the default does not rest on one CPU model.

## 4. Done when

The default form at each width is chosen from at least two runs a width on the 7763 and one on
each other AVX2 host above, its past-window and mixed rungs included. The result is recorded in
section 5 with the runs' ids. A default that loses on a rung says by how much and why that is
accepted. The default lands in row 600's 600b or after it, and the losing forms stay as options.

## 5. Outcome

Not started.
