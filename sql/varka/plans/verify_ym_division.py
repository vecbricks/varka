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

"""The admission check behind VARKA-89, section 2.20 of m5/PLAN.md.

A year-month interval is a count of months in an int32, so both expressions this
task is about divide by a constant over the *whole* int32 range - which is
exactly what the range-narrowed magic multiply cannot serve and what VARKA-88's
double-lane division can. This script is what says so, against the Java the row
engine runs:

    extract(YEAR FROM ym)   IntervalUtils.getYears(months) = months / 12
    ym / num                IntMath.divide(months, num, RoundingMode.HALF_UP)

**Truncation, and why nothing corrects it.** `getYears` is Java's `/`, which
truncates toward zero, and `D2I` truncates toward zero as well - it is defined as
the `(int)` cast. So the double route reproduces `getYears` with no correction at
all. Section 2.20 anticipated "a truncation correction on the negative side";
that correction is owed by a *floor*-producing magic multiply, which is what the
emitter has today, and it is not owed here. Row 1 is what turns that from a claim
into a verdict, over all 4 294 967 296 month counts.

**HALF_UP is a correction, and it is the one this task owes.** Guava rounds half
away from zero, which is neither `rint` (half to even) nor any lanewise rounding
the Vector API offers. From the truncating quotient `q` and the remainder
`r = m - d*q` the rule is "increment `q` away from zero iff `2*|r| >= |d|`", and
`halfup_from_trunc` below writes it in the integer lane ops the emitter would
actually run, so what is checked is the lowering rather than a restatement of the
specification. The comparison is Guava's own `|r| - (|d| - |r|) >= 0` rather than
`2*|r| >= |d|`, which would overflow for a divisor near the type's extremes.

**Why the rows are split the way they are.** Two independent things have to hold:
the double quotient must equal Java's `/`, and the correction must turn that into
Guava's HALF_UP. They fail for different reasons and are checked separately.

For the *quotient*, DIV needs no sweep to be believed - an int32 dividend is
under 2^52, and `verify_double_division.py` establishes that a correctly rounded
divide is exact for every dividend under that bound, multiples included - so the
exhaustive rows here confirm a proof rather than replace one. RECIP is the form
that can fail, and only at exact multiples, by the closed form that script
self-tests; it is evaluated per divisor in O(1) and reported as a table, because
the emitter's deny-list for `ym / num` is transcribed from it.

For the *correction*, the full int32 sweep runs at d = +/-12, the divisor
`extract(YEAR)` uses and the one a query most often writes, in both signs so a
sign slip cannot hide. Every other divisor is swept over a window at zero and at
both ends of the range, which is where the correction's own arithmetic would
overflow if it were going to.

**What is deliberately absent.** `extract(MONTH FROM ym)` is
`(months % 12).toByte` - a remainder, and a `ByteType` output Varka has no lane
and no Arrow vector for. No division makes it emittable, so it is not a row here;
it declines, and section 2.20 already says the byte output is its own question.
`ym / col` is absent for a different reason: the divisor is then not a constant,
there is no lanewise integer divide, and the shape declines.

Run: python3 sql/varka/plans/verify_ym_division.py     (about six minutes)
Needs numpy: float64 there is IEEE binary64 with one rounding per operation, the
same arithmetic the JVM's DoubleVector does.

Exit status is 0 only when every row's verdict equals EXPECTED below. A verdict
that moves in either direction fails the run, because what the emitter may emit
is transcribed from this output.
"""

import sys

try:
    import numpy as np
except ImportError:  # pragma: no cover - the message is the point
    sys.stderr.write("numpy is required: pip install numpy\n")
    raise SystemExit(2)

INT32_MIN = -(1 << 31)
INT32_MAX = (1 << 31) - 1
CHUNK = 1 << 24
# How far either side of zero and of each end of the range the windowed rows
# sweep. Large enough to cross many full remainder cycles for every divisor below
# 100000, and to reach any overflow the correction could suffer at the ends.
WINDOW = 1 << 22

# The divisors `ym / num` is checked over. 12 and 1 are what a query most often
# writes; 2, 4 and 1024 are the powers of two the emitter may lower as a shift;
# 7 and 49 are odd non-powers, 49 because it is the textbook reciprocal failure;
# and the two extremes are where the correction's arithmetic is most likely to
# overflow. Negative divisors are present because HALF_UP rounds away from zero
# on both sides and a sign slip would show up nowhere else.
DIVISORS = [1, -1, 2, -2, 4, 7, -7, 12, -12, 49, 1024, 100000, INT32_MAX, INT32_MIN]

EXPECTED = {
    "getYears, all int32, DIV": True,
    "getYears, all int32, RECIP": True,
    "halfup d=12, all int32, DIV": True,
    "halfup d=12, all int32, RECIP": True,
    "halfup d=-12, all int32, DIV": True,
    "halfup d=-12, all int32, RECIP": True,
    "halfup, windows, DIV": True,
    "halfup, windows, RECIP": True,
}

# The reciprocal form's verdict per divisor, which is what the emitter's deny-list
# for `ym / num` is transcribed from. Every divisor here admits it except 49 - the
# textbook case, where fl(1/49) rounds down far enough that 49 * fl(1/49) is
# 0.9999999999999999 and the first multiple truncates to 0. It is in the list for
# exactly that reason: it is the proof that this deny-list is not empty, and that a
# `ym / num` lowering may not pick the faster form without consulting the divisor.
EXPECTED_RECIP_ADMITS = {d: d != 49 for d in DIVISORS}


def java_trunc_div(m, d):
    """Java's `/`: truncation toward zero, in exact int64."""
    q = np.abs(m) // abs(int(d))
    return np.where((m < 0) != (d < 0), -q, q)


def java_halfup(m, d):
    """`IntMath.divide(m, d, RoundingMode.HALF_UP)`, the reference, in exact ints."""
    q = java_trunc_div(m, d)
    r = m - int(d) * q
    absr = np.abs(r)
    absd = abs(int(d))
    signum = np.where((m < 0) != (d < 0), -1, 1)
    return np.where(absr - (absd - absr) >= 0, q + signum, q)


def double_quotient(m, d, recip):
    """The truncating quotient the emitted kernel computes, in float64."""
    md = m.astype(np.float64)
    q = md * (1.0 / float(d)) if recip else md / float(d)
    return np.trunc(q).astype(np.int64)


def halfup_from_trunc(m, d, q):
    """The HALF_UP step as the emitter would run it, over a truncating `q`.

    Every operation here is a lane op the emitter already has: a multiply, a
    subtract, two absolute values, a compare and a masked add.
    """
    r = m - int(d) * q
    absr = np.abs(r)
    absd = abs(int(d))
    signum = np.where(m < 0, -1, 1) * (-1 if d < 0 else 1)
    return np.where(absr - (absd - absr) >= 0, q + signum, q)


def recip_admits(d):
    """The closed form of `verify_double_division.py`, per divisor.

    A rounded-up reciprocal is exact at every multiple; a rounded-down one is
    exact iff one divisor-times-reciprocal still truncates to 1.
    """
    inv = 1.0 / float(d)
    from fractions import Fraction

    if abs(Fraction(inv)) >= abs(Fraction(1, int(d))):
        return True
    return int(abs(int(d)) * abs(inv)) == 1


def all_int32():
    """Every int32, in blocks, as int64 so the reference arithmetic is exact."""
    lo = INT32_MIN
    while lo <= INT32_MAX:
        hi = min(lo + CHUNK - 1, INT32_MAX)
        yield np.arange(lo, hi + 1, dtype=np.int64)
        lo = hi + 1


def windows():
    """Zero and both ends of the range, where a correction would overflow."""
    yield np.arange(-WINDOW, WINDOW, dtype=np.int64)
    yield np.arange(INT32_MIN, INT32_MIN + WINDOW, dtype=np.int64)
    yield np.arange(INT32_MAX - WINDOW, INT32_MAX + 1, dtype=np.int64)


def sweep(blocks, d, recip, halfup):
    """Compare the lowering to its reference over `blocks`; return (wrong, first)."""
    wrong, first = 0, None
    for m in blocks:
        # Guava throws on Int.MinValue / -1 rather than returning a quotient, and the
        # lowering declines that shape for the same reason; excluding the one input
        # keeps the row about the rounding. See the emitter's overflow guard.
        live = m[m != INT32_MIN] if d == -1 else m
        if live.size == 0:
            continue
        q = double_quotient(live, d, recip)
        got = halfup_from_trunc(live, d, q) if halfup else q
        want = java_halfup(live, d) if halfup else java_trunc_div(live, d)
        bad = got != want
        n = int(bad.sum())
        if n and first is None:
            first = int(live[np.argmax(bad)])
        wrong += n
    return wrong, first


def multiples_of(d):
    """Every exact multiple of `d` inside int32 - where the reciprocal form fails."""
    lo = -(1 << 31) // abs(int(d))
    hi = ((1 << 31) - 1) // abs(int(d))
    k = np.arange(lo, hi + 1, dtype=np.int64)
    return k * abs(int(d))


def check_multiples(d):
    """The closed form's verdict, measured: how many multiples RECIP gets wrong."""
    m = multiples_of(d)
    got = double_quotient(m, d, True)
    return int((got != java_trunc_div(m, d)).sum())


def criterion_selftest():
    """The reference must differ from plain truncation, or these rows check nothing."""
    m = np.arange(-1000, 1001, dtype=np.int64)
    for d in (12, -12, 7):
        t = java_trunc_div(m, d)
        h = java_halfup(m, d)
        if int((t != h).sum()) == 0:
            return f"HALF_UP never differs from truncation at d={d}"
        r = m - d * t
        near = np.abs(r) * 2 < abs(d)
        if int((h[near] != t[near]).sum()) != 0:
            return f"HALF_UP moved a quotient whose remainder is under half, d={d}"
    # The tie itself, where half-up and half-even part company: 6/12 is 1 and
    # -6/12 is -1, where half-to-even would answer 0 for both.
    if int(java_halfup(np.array([6], dtype=np.int64), 12)[0]) != 1:
        return "6/12 did not round up"
    if int(java_halfup(np.array([-6], dtype=np.int64), 12)[0]) != -1:
        return "-6/12 did not round away from zero"
    return None


def main():
    fail = criterion_selftest()
    if fail is not None:
        print(f"FAIL: the criterion self-test is wrong: {fail}")
        return 1

    print("The reciprocal form's verdict per divisor, by the closed form:")
    recip_bad = []
    for d in DIVISORS:
        ok = recip_admits(d)
        if ok != EXPECTED_RECIP_ADMITS[d]:
            recip_bad.append(d)
        print(f"  d={d:<12} RECIP {'admitted' if ok else 'REFUSED'}")

    print("\nThat verdict measured, at every exact multiple inside int32, for the")
    print("divisor it refuses and the one extract(YEAR) uses:")
    multiples_bad = []
    for d in (49, 12):
        wrong = check_multiples(d)
        admits = EXPECTED_RECIP_ADMITS[d]
        if (wrong == 0) != admits:
            multiples_bad.append(d)
        print(
            f"  d={d:<12} {wrong} of {multiples_of(d).size} multiples wrong "
            f"({'admitted' if admits else 'REFUSED'})"
        )

    print("\nThe lowerings against the Java the row engine runs. `wrong` counts the")
    print("month counts whose result differs.\n")
    rows = [
        ("getYears, all int32, DIV", all_int32, 12, False, False),
        ("getYears, all int32, RECIP", all_int32, 12, True, False),
        ("halfup d=12, all int32, DIV", all_int32, 12, False, True),
        ("halfup d=12, all int32, RECIP", all_int32, 12, True, True),
        ("halfup d=-12, all int32, DIV", all_int32, -12, False, True),
        ("halfup d=-12, all int32, RECIP", all_int32, -12, True, True),
    ]
    results = {}
    for label, blocks, d, recip, halfup in rows:
        wrong, first = sweep(blocks(), d, recip, halfup)
        results[label] = wrong == 0
        print(f"  {label:<32} {'exact' if wrong == 0 else f'{wrong} wrong, first at {first}'}")

    for recip in (False, True):
        label = f"halfup, windows, {'RECIP' if recip else 'DIV'}"
        wrong, first = 0, None
        for d in DIVISORS:
            w, f = sweep(windows(), d, recip, True)
            if w and first is None:
                first = (d, f)
            wrong += w
        results[label] = wrong == 0
        print(f"  {label:<32} {'exact' if wrong == 0 else f'{wrong} wrong, first at {first}'}")

    print()
    bad = [k for k, v in results.items() if v != EXPECTED[k]]
    for k in bad:
        print(f"FAIL: {k} is {results[k]}, EXPECTED {EXPECTED[k]}")
    for d in recip_bad:
        print(f"FAIL: d={d} RECIP verdict moved from {EXPECTED_RECIP_ADMITS[d]}")
    for d in multiples_bad:
        print(f"FAIL: d={d} measured multiples disagree with the closed form")
    if bad or recip_bad or multiples_bad:
        return 1
    print("PASS: the double quotient reproduces Java's `/` over the whole int32 month")
    print("      range, so extract(YEAR FROM ym) needs no range guard and no")
    print("      truncation correction; and the HALF_UP step over it reproduces")
    print("      IntMath.divide for every divisor checked.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
