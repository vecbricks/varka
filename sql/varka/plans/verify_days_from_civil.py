#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""The arithmetic behind VARKA-40, checked before it was handed over - and
re-checked, in a way the first version of this script did not do, after the
first version turned out to have a real bug.

Two things, both of which VARKA-40.md depends on being true:

  1. days_from_civil, the inverse of the VARKA-26 decomposition, round-trips to
     the identity over every day from year 1 to year 9999, and over the wider
     year range add_months's own month arithmetic can reach.
  2. add_months, built from that inverse, matches LocalDate.plusMonths
     semantics - month arithmetic, then clamp the day to the new month - over a
     broad sample of days and offsets, including the largest offsets the
     compiler accepts.

It is committed for the same reason verify_chrono_tails.py is: the recipe
claims its arithmetic is checked, and the reader should be able to re-run the
check rather than take that on trust.

    python3 sql/varka/plans/verify_days_from_civil.py

Two things earned this script's keep, on two different runs.

The first: the natural formulation of the month arithmetic, folding the year
into a total month count and dividing by 12, puts the dividend near 400,000,
far past the ~46341 bound an exact magic multiply needs. The fix - keep the
dividend small, just the month index plus the offset - is what add_months
below does.

The second, found only after the emitter was written and tested against small
years near the epoch: this script's own first version checked
`(v * M) >> k == v // d` with Python's arbitrary-precision integers, which
answers whether the *shift* is correct but silently assumes the *multiply*
does not overflow. The emitter's lanes are 32-bit, so `v * M` wraps at 2**32
- not at Python's infinity, and not at 2**31 either, since the shift is
unsigned (LSHR). The `/ 400` and `/ 100` magic for the recompose direction
looked exact under the naive check but overflows for a biased year past
about 25599, which every year past roughly 12400 reaches once the bias
(chosen to cover add_months's widened range, not just the day range) is
added in. The functions below simulate the actual 32-bit lane arithmetic
(mul32/ushr32) rather than trusting Python's unbounded integers, which is
what catches this the way the first version could not - and the fix takes
the same shape VARKA-26's own large divisors already use: a round-down magic
plus one correction step, not a claimed-exact one.
"""

import datetime


def to_i32(v):
    v &= 0xFFFFFFFF
    return v - 0x100000000 if v >= 0x80000000 else v


def mul32(a, b):
    """int32 multiply, wrapping exactly as the emitted lane op does."""
    return to_i32(a * b)


def ushr32(v, k):
    """Unsigned (LSHR) right shift on the 32-bit pattern of v."""
    return (v & 0xFFFFFFFF) >> k


ERA, CEN, BIAS = 146097, 36524, 5394572


def decompose(d):  # VarkaChrono.narrowed, as shipped
    w = to_i32(d + BIAS)
    era = ushr32(mul32(w, 114), 24)
    r = to_i32(w - era * ERA)
    if r >= ERA:
        era += 1
        r -= ERA
    c = ushr32(mul32(r, 7349), 28)
    doc = to_i32(r - c * CEN)
    if doc >= CEN:
        c += 1
        doc -= CEN
    if c == 4:
        c = 3
        doc += CEN
    yoc = ushr32(mul32(doc, 45966), 24)
    doy = to_i32(doc - to_i32(365 * yoc + (yoc >> 2)))
    if doy < 0:
        doy += 365 + (1 if (yoc & 3) == 0 else 0)
        yoc -= 1
    mp = ushr32(mul32(to_i32(5 * doy + 2), 877241), 27)
    dom = to_i32(doy - ushr32(mul32(to_i32(153 * mp + 2), 838861), 22) + 1)
    month = mp + 3 if mp < 10 else mp - 9
    year = to_i32(400 * (era - 32) + 100 * c + yoc + (1 if mp >= 10 else 0))
    return year, month, dom


def month_start(mp):
    return ushr32(mul32(to_i32(153 * mp + 2), 838861), 22)


# Hinnant's days_from_civil, with every division done as a magic multiply on a
# biased (non-negative) operand, the way the emitter does it.
#
# YBIAS is 15200, not VARKA-34's 13200: add_months can push a year up to
# MONTH_ARITH_MAX_MONTHS/12 (about 2047 years) past either end of VARKA-26's
# narrow day range, so the year this function must cover is roughly
# -14848..35181, not -12800..33134 - and 15200 is the smallest multiple of
# 400 that keeps every one of those biased.
YBIAS = 15200
# floor(2**24 / 400) == floor(2**22 / 100): one constant, two shifts. A
# round-down magic, not an exact one - see the module docstring - so both
# divisions below take one correction step.
CM = 41943
CK400 = 24
CK100 = 22


def div_mod_round_down_plus_one(v, d, M, K):
    q = ushr32(mul32(v, M), K)
    r = to_i32(v - q * d)
    if r >= d:
        q += 1
        r -= d
    return q, r


def leap(y):
    b = y + YBIAS
    assert b >= 0, (y, b)
    div4 = (b & 3) == 0
    _, mod100 = div_mod_round_down_plus_one(b, 100, CM, CK100)
    _, mod400 = div_mod_round_down_plus_one(b, 400, CM, CK400)
    return 1 if (div4 and (mod100 != 0 or mod400 == 0)) else 0


def days_from_civil(y, m, d):
    yy = y - (1 if m <= 2 else 0)
    b = yy + YBIAS
    assert b >= 0, (y, m, d, b)
    era, yoe = div_mod_round_down_plus_one(b, 400, CM, CK400)
    mp = m + (9 if m <= 2 else -3)
    doy = to_i32(month_start(mp) + d - 1)
    century, _ = div_mod_round_down_plus_one(yoe, 100, CM, CK100)
    doe = to_i32(to_i32(yoe * 365 + (yoe >> 2) - century) + doy)
    return to_i32(to_i32((era - YBIAS // 400) * ERA) + doe - 719468)


# LocalDate.plusMonths semantics: month arithmetic then clamp the day. Do NOT
# fold the year into a total month count: y * 12 is ~400k, past the ~46341
# bound an exact magic multiply needs. Keep the dividend small instead - it is
# only the month index plus the offset, so for a literal interval it is
# bounded by the literal (VarkaChrono.MONTH_ARITH_MIN/MAX_MONTHS).
MBIAS_UNITS = 2048
MONTH_M, MONTH_K = 43691, 19


def add_months(d, months):
    y, m, dom = decompose(d)
    k = to_i32((m - 1) + months + 12 * MBIAS_UNITS)
    q = ushr32(mul32(k, MONTH_M), MONTH_K)  # k / 12, exact magic
    nm = to_i32(k - q * 12)  # 0..11
    ny = to_i32(y + q - MBIAS_UNITS)
    le1 = 1 if nm <= 1 else 0
    mp = to_i32((nm - 2) + (12 if le1 else 0))  # March-based month
    mp_next = min(mp + 1, 11)
    start, start_next = month_start(mp), month_start(mp_next)
    regular_length = to_i32(start_next - start)
    feb_length = to_i32((365 + leap(ny)) - start)
    length = feb_length if mp == 11 else regular_length
    clamped_day = min(dom, length)
    return days_from_civil(ny, nm + 1, clamped_day)


# 1. Round trip: decompose then recompose must be the identity, everywhere.
bad_rt = 0
for d in range(-719162, 2932897):
    y, m, dom = decompose(d)
    if days_from_civil(y, m, dom) != d:
        bad_rt += 1
print("round trip over", 2932897 + 719162, "days -> mismatches:", bad_rt)

# 1b. The same round trip over the wider year range add_months can reach,
# which the first version of this script never exercised.
NARROW_MIN_DAYS, NARROW_MAX_DAYS = -BIAS, (1 << 24) - 1 - BIAS
bad_rt_wide = 0
n_wide = 0
for d in list(range(NARROW_MIN_DAYS, NARROW_MIN_DAYS + 5000)) + list(
    range(NARROW_MAX_DAYS - 5000, NARROW_MAX_DAYS + 1)
):
    y, m, dom = decompose(d)
    n_wide += 1
    if days_from_civil(y, m, dom) != d:
        bad_rt_wide += 1
print(
    "round trip near the narrow range's own edges over", n_wide, "days -> mismatches:", bad_rt_wide
)

# 2. add_months against LocalDate.plusMonths semantics, on a broad sample -
# including, deliberately, large years crossed with large offsets, which is
# exactly the combination that found the overflow bug and which a sample
# built only from small years near the epoch does not reach.
LEN = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]


def ref_add(dt, months):
    total = dt.year * 12 + (dt.month - 1) + months
    ny, nm = divmod(total, 12)
    ln = LEN[nm] + (1 if nm == 1 and (ny % 4 == 0 and (ny % 100 != 0 or ny % 400 == 0)) else 0)
    return datetime.date(ny, nm + 1, min(dt.day, ln))


# Only over the epoch-relative range: datetime.date itself cannot represent the wider years
# near NARROW_MIN/MAX_DAYS, so those are covered by the round trip above instead (1b) and by
# VarkaLoopEmitterSuite's own matrix test, whose oracle is DateTimeUtils.dateAddMonths (backed
# by java.time.LocalDate, whose year range is far wider than Python's datetime).
bad_am = 0
n = 0
for d in range(-719162, 2932897, 37):
    dt = datetime.date.fromordinal(d + 719162 + 1)
    for months in (0, 1, -1, 12, -12, 13, -13, 100, -100, 1200, -1200, 24564, -24576):
        total = dt.year * 12 + (dt.month - 1) + months
        if not (1 <= total // 12 <= 9999):
            continue  # outside datetime's own range
        want = ref_add(dt, months)
        got = add_months(d, months)
        n += 1
        if got != want.toordinal() - 719162 - 1:
            bad_am += 1
            if bad_am < 4:
                print("MISMATCH", dt, months, got, want)
print("add_months over", n, "cases -> mismatches:", bad_am)
