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

"""The arithmetic behind VARKA-34 to VARKA-37, checked before it was handed over.

Each of VARKA-34.md through VARKA-37.md gives a formula for a tail on
VARKA-26's civil-from-days decomposition. This script is where those formulas
were checked: it reimplements the decomposition exactly as VarkaChrono.narrowed
does, applies each candidate tail, and compares against Python's datetime over
every day of 0001-01-01..9999-12-31 - the range Spark's date literals span.

It is committed because the recipes claim the arithmetic is verified, and a
claim like that should be re-runnable by whoever is asked to trust it. It is
not a test: the real gates are the suites each task adds, whose oracles are
java.time rather than this file.

    python3 sql/varka/plans/verify_chrono_tails.py

It earned its keep on the first run: the day-of-year formula had 59 where the
answer is 60, and failed on 84% of days.
"""

import datetime

ERA, CEN, BIAS = 146097, 36524, 5394572


def decompose(d):
    w = d + BIAS
    era = (w * 114) >> 24
    r = w - era * ERA
    if r >= ERA:
        era += 1
        r -= ERA
    c = (r * 7349) >> 28
    doc = r - c * CEN
    if doc >= CEN:
        c += 1
        doc -= CEN
    if c == 4:
        c = 3
        doc += CEN
    yoc = (doc * 45966) >> 24
    doy = doc - (365 * yoc + (yoc >> 2))
    if doy < 0:
        doy += 365 + (1 if (yoc & 3) == 0 else 0)
        yoc -= 1
    mp = ((5 * doy + 2) * 877241) >> 27
    dom = doy - (((153 * mp + 2) * 838861) >> 22) + 1
    month = mp + 3 if mp < 10 else mp - 9
    year = 400 * (era - 32) + 100 * c + yoc + (1 if mp >= 10 else 0)
    return era - 32, c, yoc, doy, mp, dom, month, year


# magic modulo for a non-negative dividend under the exactness bound
def mod_magic(v, d, M, k):
    return v - ((v * M) >> k) * d


# A multiple of 400, so it preserves leapness, and big enough that year - 1 stays
# non-negative at the bottom of the range VarkaChrono covers (VARKA-37 needs that).
LEAP_BIAS = 13200


def leap(year):
    y = year + LEAP_BIAS  # 0 <= y <= 45934 for the covered range
    by4 = (y & 3) == 0
    by100 = mod_magic(y, 100, 167773, 24) == 0
    by400 = mod_magic(y, 400, 167773, 26) == 0
    return 1 if (by4 and ((not by100) or by400)) else 0


def dayofyear(doy, L):
    return doy - 305 if doy >= 306 else doy + 60 + L


def cum(m):
    return ((153 * m + 2) * 838861) >> 22


def lastday(d, mp, dom, L):
    length = (cum(mp + 1) - cum(mp)) if mp < 11 else 28 + L
    return d + length - dom


def truncq(d, jdoy, month, L):
    q = (month + 2) // 3
    starts = [1, 91 + L, 182 + L, 274 + L]
    return d - jdoy + starts[q - 1]


def pofy(year):
    y = year + LEAP_BIAS
    return (y + (y >> 2) - ((y * 167773) >> 24) + ((y * 167773) >> 26)) % 7


def weeks_in(year):
    return 52 + (1 if (pofy(year) == 4 or pofy(year - 1) == 3) else 0)


def weekofyear(d, jdoy, year):
    isodow = ((d + 3) % 7 + 7) % 7 + 1  # Varka's weekday + 1
    w = (jdoy - isodow + 10) // 7
    if w < 1:
        return weeks_in(year - 1)
    if w > weeks_in(year):
        return 1
    return w


bad = {"doy": 0, "last": 0, "ty": 0, "tm": 0, "tq": 0, "woy": 0, "leap": 0}
cur = datetime.date(1, 1, 1)
d0 = -719162
n = 0
for i in range(0, 2932897 - d0):
    d = d0 + i
    era, c, yoc, doy, mp, dom, month, year = decompose(d)
    L = leap(year)
    ref_leap = 1 if (cur.year % 4 == 0 and (cur.year % 100 != 0 or cur.year % 400 == 0)) else 0
    if L != ref_leap:
        bad["leap"] += 1
    jdoy = dayofyear(doy, L)
    if jdoy != cur.timetuple().tm_yday:
        bad["doy"] += 1
    ld = lastday(d, mp, dom, L)
    ref_ld = (
        d
        - cur.day
        + [31, 29 if ref_leap else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][cur.month - 1]
    )
    if ld != ref_ld:
        bad["last"] += 1
    if d - jdoy + 1 != d - cur.timetuple().tm_yday + 1:
        bad["ty"] += 1
    if d - dom + 1 != d - cur.day + 1:
        bad["tm"] += 1
    qs = datetime.date(cur.year, ((cur.month - 1) // 3) * 3 + 1, 1)
    if truncq(d, jdoy, month, L) != d - (cur - qs).days:
        bad["tq"] += 1
    if weekofyear(d, jdoy, year) != cur.isocalendar()[1]:
        bad["woy"] += 1
    n += 1
    try:
        cur += datetime.timedelta(days=1)
    except OverflowError:
        break
print("checked", n, "days (0001-01-01..9999-12-31)")
for k, v in bad.items():
    print(f"  {k:5s} mismatches: {v}")
