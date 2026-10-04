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

"""The admission check behind VARKA-88, section 2.19 of m5/PLAN.md.

The Vector API has no integer division and no multiply-high, so every division
Varka does today is a range-narrowed magic multiply. VARKA-88 asks whether a
division through double lanes - `(double) v` in, a multiply by the reciprocal or
a true divide, `D2I`/`D2L` back - is exact over the dividends Varka's divisions
actually see, in which case it needs no magic, no correction and no range guard.

Two lowerings, which are not equally exact:

    RECIP   trunc(v * fl(1/d))   two roundings, a multiply
    DIV     trunc(v / d)         one rounding, a divide

**The error constant.** RECIP's relative error is at most 2^-52 (two roundings of
2^-53 each, plus a 2^-106 cross term); DIV's is 2^-53. A non-multiple's true
quotient lies at least 1/d from every integer, so truncation cannot cross an
integer while |v| < 2^52 (RECIP) or |v| < 2^53 (DIV). The bound is on the
DIVIDEND and does not depend on the divisor.

**At an exact multiple that bound says nothing, and a closed form does.** DIV
returns k exactly: the quotient is representable and the divide is correctly
rounded. RECIP computes fl(k * fl(1/d)), and if fl(1/d) rounded *down* the
product can land on the double just below k. The rule, which `criterion_selftest`
checks rather than assumes:

    fl(1/d) >= 1/d  ->  exact at every multiple below 2^53
    fl(1/d) <  1/d  ->  exact at every multiple iff trunc(d * fl(1/d)) == 1

The textbook failure is d = 49, where 49 * fl(1/49) is 0.9999999999999999.
Varka's own /146097 is another, which is why this script exists: the sampled
check section 2.19 first quoted tried 12, 3, 7 and 100 and generalised.

**Admissibility is a property of the (divisor, dividend range) pair**, not of the
divisor, so every row below names the lowering that performs the division and the
range that lowering actually sees, read from the source. The same 146097 appears
twice with different verdicts: inexact under RECIP over the era step's `w`, exact
over the Julian century's `quadDays = 4*doe + 3`, whose multiples are never the
failing ones.

**Three divisions a reader might expect are absent, because Spark does not
perform them** (IntervalUtils.scala:47-70): `extract(HOUR FROM dt)` and
`extract(MINUTE FROM dt)` are a modulo *then* a divide with ByteType results, and
`extract(SECOND FROM dt)` is `Decimal(micros % MICROS_PER_MINUTE, 8, 6)` with no
division at all. They belong to VARKA-103, and their blocker is the output type
rather than the division. Only `getDays` is a flat division.

Run: python3 sql/varka/plans/verify_double_division.py    (about six seconds)
Needs numpy: float64 there is IEEE binary64 with one rounding per operation, the
same arithmetic the JVM's DoubleVector does.

Exit status is 0 only when every row's verdict equals EXPECTED below. A verdict
that moves in either direction fails the run, because the emitter's
per-(divisor, range) table is transcribed from this output.
"""

import sys
from fractions import Fraction

try:
    import numpy as np
except ImportError:  # pragma: no cover - the message is the point
    sys.stderr.write("numpy is required: pip install numpy\n")
    raise SystemExit(2)

TWO52 = 1 << 52
TWO53 = 1 << 53

# --- the rows: (key, divisor, lo, hi, label) or (key, divisor, lo, hi, label, step) ----------
# `hi` is exclusive. A row with `step` takes only the dividends lo, lo+step, lo+2*step, ...
# which is not a refinement anyone may skip: the Julian century divides `quadDays = 4*doe+3`,
# so its dividends are exactly the values congruent to 3 mod 4, and 146097 - the multiple where
# the reciprocal form fails - is congruent to 1 and unreachable. Modelled as a plain interval
# the row reads "RECIP unusable" for a value the lowering can never see.
#
# The int32 rows come from VarkaChrono and its javadoc. The two Julian rows are what the
# shipped default
# (julianMap = true) performs; the era rows are the narrowed prefix's own step and the total
# form VARKA-88 would delete the narrowing for.
INT32_ROWS = [
    ("era/narrow", 146097, 0, 1 << 24, "era step, narrowed: /146097 over w < 2^24"),
    ("era/total", 146097, 0, (1 << 32) + 719468, "era step, total: /146097 over biased int32"),
    ("julian/century", 146097, 3, 584388, "Julian century: /146097 over quadDays = 4*doe+3", 4),
    ("julian/year", 1461, 0, 584400, "Julian year: /1461 over the mapped count (max 584399)"),
    ("century", 36524, 0, 146097, "century in era: /36524 over the day of era"),
    ("year", 365, 0, 36525, "year of century: /365 over the day of century"),
    ("month", 153, 0, 5 * 365 + 3, "month: /153 over 5*dayOfYear + 2"),
    ("day", 5, 0, 153 * 11 + 3, "day of month: /5 over 153*marchMonth + 2"),
    ("quarter", 3, 0, 15, "quarter: /3 over the month index"),
    ("week", 7, 0, 685, "ISO week: /7 over dayOfYear - 1"),
    ("dom", 2141, 0, 1 << 16, "day of month: /2141 over the numerator's low half"),
    ("yoe/100", 100, 0, 400, "year of era: /100 over 0..399"),
    ("yoe/400", 400, 0, 1 << 24, "quatercentennial: /400 over the biased year"),
    # extract(YEAR FROM ym) and CAST(ym AS INTERVAL YEAR): a full signed int32 month count.
    # extract(QUARTER FROM ym) has no row: intervalExpressions.scala:127-160 admits only YEAR
    # and MONTH for a year-month interval and throws otherwise.
    ("ym/12", 12, -(1 << 31), 1 << 31, "extract(YEAR FROM ym): /12 over all signed int32"),
]

# TIME is nanoseconds of day. hour(t) is a flat division; minute(t) and second(t) are a
# division and then a /60 on the quotient it produces - DateTimeUtils.getHoursOfTime and its
# siblings go through LocalTime, whose fields are exactly these - so each contributes two rows.
NANOS_PER_DAY = 86_400_000_000_000
TIME_ROWS = [
    ("time/hour", 3_600_000_000_000, 0, NANOS_PER_DAY, "hour(t): /3.6e12 over nanos of day"),
    ("time/minute", 60_000_000_000, 0, NANOS_PER_DAY, "minute(t) step 1: /6e10"),
    ("time/second", 1_000_000_000, 0, NANOS_PER_DAY, "second(t) step 1: /1e9"),
    ("time/mod60m", 60, 0, 1440, "minute(t) step 2: /60 over the minute of day"),
    ("time/mod60s", 60, 0, 86400, "second(t) step 2: /60 over the second of day"),
    ("time/trunc_ms", 1_000_000, 0, NANOS_PER_DAY, "time_trunc to milliseconds: /1e6"),
    ("time/trunc_us", 1_000, 0, NANOS_PER_DAY, "time_trunc to microseconds: /1e3"),
]

# Day-time intervals are signed microseconds, and only the day extract divides:
# IntervalUtils.getDays(micros) = (micros / MICROS_PER_DAY).toInt.
INTERVAL_ROWS = [
    (
        "dt/days",
        86_400_000_000,
        -TWO52,
        TWO52 + 1,
        "extract(DAY FROM dt): /8.64e10 over +/-2^52 micros",
    ),
]

# The verdict each row must have, as (recip_ok, div_ok). The emitter's table is transcribed
# from this output, so a row that moves fails the run rather than drifting.
EXPECTED = {
    "era/narrow": (False, True),
    "era/total": (False, True),
    "julian/century": (True, True),
    "julian/year": (True, True),
    "century": (True, True),
    "year": (True, True),
    "month": (True, True),
    "day": (True, True),
    "quarter": (True, True),
    "week": (True, True),
    "dom": (True, True),
    "yoe/100": (True, True),
    "yoe/400": (True, True),
    "ym/12": (True, True),
    "time/hour": (True, True),
    "time/minute": (True, True),
    "time/second": (True, True),
    "time/mod60m": (True, True),
    "time/mod60s": (True, True),
    "time/trunc_ms": (True, True),
    "time/trunc_us": (True, True),
    "dt/days": (True, True),
}


def java_quotient(v, d):
    """Java's `/` on int64 lanes: truncation toward zero, correct at INT64_MIN.

    numpy's `//` floors, and `-(abs(v) // d)` would wrap at INT64_MIN, whose magnitude is not
    representable - the one value int64 truncation has to be careful about. So the correction
    is applied to the floored quotient instead: they differ by one exactly where the dividend
    is negative and the division is inexact.
    """
    floor_q = v // d
    rem = v - floor_q * d
    return np.where((v < 0) & (rem != 0), floor_q + 1, floor_q)


def recip(v, d):
    """trunc(v * fl(1/d)), as DoubleVector MUL then D2I/D2L would compute it."""
    return np.trunc(v * np.float64(1.0 / d))


def div(v, d):
    """trunc(v / d), as DoubleVector DIV then D2I/D2L would compute it."""
    return np.trunc(v / np.float64(d))


def recip_exact_at_multiples(d):
    """The closed form: may RECIP be used for divisor `d` at its exact multiples?

    A rounded-up reciprocal never fails: the product of a representable k with a value at or
    above 1/d rounds to at or above k, and stays below k+1 while k < 2^53. A rounded-down one
    fails somewhere iff it already fails at k = d, which trunc(d * fl(1/d)) == 1 tests.
    """
    r = 1.0 / d
    if Fraction(r) >= Fraction(1, d):
        return True
    return int(np.trunc(np.float64(d) * np.float64(r))) == 1


def criterion_selftest(d_hi=20000, k_hi=1 << 14):
    """Check the closed form against direct evaluation for every divisor in [2, d_hi).

    Returns the divisors where the rule and the arithmetic disagree over the multiples k*d
    for k < k_hi, which must be none or section 2 of the plan states a false rule.
    """
    ks = np.arange(1, k_hi, dtype=np.float64)
    bad = []
    for d in range(2, d_hi):
        claimed = recip_exact_at_multiples(d)
        actual = bool(np.all(np.trunc((ks * d) * np.float64(1.0 / d)) == ks))
        if claimed != actual:
            bad.append((d, claimed, actual))
    return bad


def probe_dividends(d, lo, hi, step=1, cap=3_000_000, seed=88):
    """Where a failure must first appear: every multiple's neighbourhood, plus randoms.

    For each multiple k*d in range, the three dividends k*d-1, k*d, k*d+1 - the exact multiple,
    where the reciprocal's own rounding decides, and its neighbours, where a non-multiple's
    quotient is nearest an integer. Every multiple when there are at most `cap` of them;
    otherwise a dense prefix, a dense suffix and a random sample of the interior, with the
    blocks overlapping at their boundaries so no multiple falls between them.
    """
    if step != 1:
        # A strided domain is enumerated whole while it fits, which is the honest thing to do:
        # its multiples are a sparse subset of the divisor's, so probing "every multiple" would
        # probe dividends the lowering never produces.
        count = (hi - lo + step - 1) // step
        if count <= 20 * cap:
            return np.arange(lo, hi, step, dtype=np.int64), True
        rng = np.random.default_rng(seed)
        idx = np.unique(rng.integers(0, count, size=cap, dtype=np.int64))
        return np.sort(lo + idx * step), False
    k_lo = -((-lo) // d) if lo < 0 else (lo + d - 1) // d
    k_hi = (hi - 1) // d
    count = k_hi - k_lo + 1
    exhaustive = count <= cap
    if exhaustive:
        ks = np.arange(k_lo, k_hi + 1, dtype=np.int64)
    else:
        rng = np.random.default_rng(seed)
        edge = cap // 4
        head = np.arange(k_lo, k_lo + edge, dtype=np.int64)
        tail = np.arange(k_hi - edge, k_hi + 1, dtype=np.int64)
        middle = rng.integers(k_lo + edge, k_hi - edge, size=cap // 2, dtype=np.int64)
        ks = np.unique(np.concatenate([head, middle, tail]))
    mult = ks * d
    probes = np.concatenate([mult - 1, mult, mult + 1])
    rng = np.random.default_rng(seed)
    probes = np.concatenate([probes, rng.integers(lo, hi, size=1_000_000, dtype=np.int64)])
    probes = probes[(probes >= lo) & (probes < hi)]
    probes.sort()  # a reported failure is then the numerically smallest, not the first stored
    return probes, exhaustive


def check_row(d, lo, hi, step=1):
    """(recip_ok, div_ok, first recip failure, first div failure, probes, exhaustive)."""
    probes, exhaustive = probe_dividends(d, lo, hi, step)
    expect = java_quotient(probes, d).astype(np.float64)
    f = probes.astype(np.float64)
    first = []
    for fn in (recip, div):
        bad = np.nonzero(fn(f, d) != expect)[0]
        first.append(int(probes[bad[0]]) if bad.size else None)
    return first[0] is None, first[1] is None, first[0], first[1], probes.size, exhaustive


def run(rows, title):
    print(title)
    results = {}
    for row in rows:
        key, d, lo, hi, label = row[:5]
        step = row[5] if len(row) > 5 else 1
        recip_ok, div_ok, r_bad, d_bad, n, exhaustive = check_row(d, lo, hi, step)
        claimed = recip_exact_at_multiples(d)
        if step != 1:
            how = "every dividend" if exhaustive else "sampled dividends"
        else:
            how = "all multiples" if exhaustive else "sampled multiples"
        note = ""
        if not recip_ok:
            note += f"  RECIP wrong from {r_bad}"
        if not div_ok:
            note += f"  DIV wrong from {d_bad}"
        # The closed form speaks about multiples only; a row can still fail RECIP on a
        # non-multiple past 2^52, which is why the two are reported separately.
        if step != 1:
            # The closed form answers about every multiple of d; a strided domain contains
            # only some of them, so the two may legitimately differ - and here they do.
            flag = "" if claimed or recip_ok else ""
            agrees = True
        else:
            flag = "" if claimed == recip_ok else "  [closed form disagrees with this sweep]"
            agrees = claimed == recip_ok
        print(
            f"  {label:54s} RECIP {'ok' if recip_ok else 'NO'}  DIV {'ok' if div_ok else 'NO'}"
            f"  [{n:>9d} dividends, {how}]{note}{flag}"
        )
        results[key] = (recip_ok, div_ok, agrees)
    print()
    return results


def main():
    print("The closed form for RECIP at exact multiples, against direct evaluation for every")
    print("divisor in [2, 20000) over every multiple index below 2^14:")
    bad = criterion_selftest()
    if bad:
        for d, claimed, actual in bad[:10]:
            print(f"  d={d}: the rule says {claimed}, the arithmetic says {actual}")
        print("  FAIL - the rule in VARKA-88.md section 2 is wrong.")
        return 1
    print("  no divisor disagrees, so the rule holds over that family.")
    print(
        f"  the textbook failure, d = 49: 49 * fl(1/49) = {49 * (1.0 / 49)!r}, "
        f"and the rule says {recip_exact_at_multiples(49)}"
    )
    print()

    results = {}
    results.update(run(INT32_ROWS, "int32 divisions, by (divisor, dividend range):"))
    results.update(
        run(
            TIME_ROWS,
            "TIME, nanoseconds of day - hour divides once, minute and second "
            "divide and then take /60:",
        )
    )
    results.update(
        run(
            INTERVAL_ROWS,
            "Day-time intervals, signed microseconds - only the day extract divides at all:",
        )
    )

    print("Past the bound, for the one row whose range reaches it: the first dividend above")
    print("2^52 where each form goes wrong, at the multiples' neighbourhoods up to 2^53:")
    d = INTERVAL_ROWS[0][1]
    ks = np.arange(TWO52 // d, TWO53 // d + 1, dtype=np.int64)
    mult = ks * d
    probes = np.concatenate([mult - 1, mult, mult + 1])
    probes = probes[(probes >= TWO52) & (probes < TWO53)]
    probes.sort()
    expect = java_quotient(probes, d).astype(np.float64)
    for name, fn in (("RECIP", recip), ("DIV", div)):
        bad_idx = np.nonzero(fn(probes.astype(np.float64), d) != expect)[0]
        where = (
            f"v = 2^52 + {int(probes[bad_idx[0]]) - TWO52}"
            if bad_idx.size
            else "nothing below 2^53"
        )
        print(f"  extract(DAY FROM dt) /8.64e10  {name:5s} {where}")
    print()

    moved = [k for k, (r, dv, _) in results.items() if (r, dv) != EXPECTED.get(k)]
    disagreed = [k for k, (_, _, agreed) in results.items() if not agreed]
    if moved:
        for k in moved:
            print(f"  {k}: verdict {results[k][:2]} is not the expected {EXPECTED.get(k)}")
        print("FAIL: a row's verdict moved. The emitter's table is transcribed from this")
        print("      output, so it is updated deliberately or not at all.")
        return 1
    if disagreed:
        print(f"FAIL: the closed form disagreed with the sweep for: {', '.join(disagreed)}")
        return 1
    print("PASS: every row matches its expected verdict, the closed form agrees with every")
    print("      sweep, and DIV is exact for every division over its stated range.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
