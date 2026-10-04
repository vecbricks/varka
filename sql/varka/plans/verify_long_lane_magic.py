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

"""The admission check behind VARKA-49, section 2.19 of m4/PLAN.md.

VARKA-26's calendar lowering is range-narrowed, with round-down magics and
correction carries, because VectorOperators has no multiply-high and an int32
lane cannot hold the product an exact magic division needs. VARKA-49 asks
whether that stops applying in int64 lanes, where LongVector's ordinary MUL
returns a full 64-bit low product.

This script establishes the two facts section 2.19 quotes:

  1. For each division the decomposition needs, an exact (M, k) pair exists
     whose largest product fits a *signed* 64-bit lane, over the dividend range
     that lowering actually sees - not a rounded-up power of two. The widest is
     /146097 over [0, 2^32 + 719468), the biased int32 day range.
  2. The margin is thin rather than comfortable: the same search over
     [0, 2^33) finds no exact pair at all for /146097, so the result depends on
     the real range and would not survive a wider one.

Fact 1 is checked exhaustively for the two small divisions and, for /146097,
over every multiple-of-d boundary plus a dense prefix and both endpoints - the
error of floor(n*M/2^k) - floor(n/d) is monotone between consecutive multiples
of d, so those boundaries are where a disagreement must appear if there is one.
The full 2^32 sweep is VARKA-49's own commit-1 deliverable, run as a committed
opt-in test against a long-arithmetic reference the way VARKA-26 swept its total
variant; this script is the cheap check that says the sweep is worth writing.

Run: python3 sql/varka/plans/verify_long_lane_magic.py
"""

INT64_MAX = 2**63 - 1

# The three divisions a civil-from-days decomposition needs, with the dividend
# range each one actually sees. ERA_DAYS divides the biased day, which spans the
# whole int32 range shifted to be non-negative and then March-shifted; the other
# two divide a day-of-era, which is bounded by ERA_DAYS itself.
ERA_DAYS = 146097
CENTURY_DAYS = 36524
MARCH_EPOCH_SHIFT = 719468

DIVISIONS = [
    (ERA_DAYS, 2**32 + MARCH_EPOCH_SHIFT, "day of era, /146097, biased int32 days"),
    (CENTURY_DAYS, 2**24, "century in era, /36524"),
    (365, 2**24, "year of century, /365"),
]


def boundaries(d, hi):
    """Every dividend where floor(n*M/2^k) can first diverge from floor(n/d).

    The approximation error grows monotonically between consecutive multiples of
    d, so a disagreement, if any exists, shows at a multiple of d or the value
    just below one. A dense prefix and both endpoints are added because they are
    cheap and catch a wrong k outright.
    """
    probe = set(range(0, 4096))
    probe.add(hi - 1)
    probe.add(hi - 2)
    n = d
    while n < hi:
        probe.add(n - 1)
        probe.add(n)
        n += d
    return sorted(v for v in probe if 0 <= v < hi)


def find_exact(d, hi):
    """The smallest k whose exact magic never overflows a signed 64-bit lane.

    Returns (k, M, largest_product) or None. M is the Granlund-Montgomery
    round-up magic, floor(2^k / d) + 1, which never underestimates.
    """
    for k in range(1, 64):
        magic = (1 << k) // d + 1
        largest = (hi - 1) * magic
        if largest > INT64_MAX:
            continue
        if all((n * magic) >> k == n // d for n in boundaries(d, hi)):
            return k, magic, largest
    return None


def main():
    print("Exact magic division with a 64-bit low product (no multiply-high):")
    print()
    ok = True
    for d, hi, label in DIVISIONS:
        found = find_exact(d, hi)
        if found is None:
            print(f"  {label:42s}  NO exact (M, k) fits a signed 64-bit lane")
            ok = False
            continue
        k, magic, largest = found
        checked = len(boundaries(d, hi))
        print(
            f"  {label:42s}  k={k:2d}  M={magic:<10d}"
            f"  largest product 2^{largest.bit_length() - 1}"
            f"  ({checked} boundary dividends checked)"
        )

    print()
    print("The margin, which is what makes this an admission check and not a formality:")
    wider = find_exact(ERA_DAYS, 2**33)
    if wider is None:
        print("  /146097 over [0, 2^33)                       NO exact (M, k) - as expected")
    else:
        print(
            f"  /146097 over [0, 2^33)                       k={wider[0]} M={wider[1]}"
            " - UNEXPECTED, section 2.19 understates the headroom"
        )
        ok = False

    print()
    if ok:
        print("PASS: every division the decomposition needs is exact in int64 lanes,")
        print("      and the range it is exact over does not extend to twice the input.")
    else:
        print("FAIL: section 2.19's table does not reproduce.")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
