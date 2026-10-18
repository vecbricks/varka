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


"""Figure 59: one template, two runs - the tiered policy's reports for `IntVector.max` in a
passing and a failing run of the end-to-end warm-up suite on four cores, read from the table in
`VARKA-389.md` 9.5 (`-XX:+PrintTieredEvents`). Each report is a tick on a log scale of calls
with the threshold scale `k` above it; the first compile is a dot coloured by its tier, with
C2's queue at that instant; the profile's growth is the bar that follows, against C2's
maturity line at 1,000 calls; the tier-4 compile closes each track."""

import math
import os

from rough import PALETTE, Rough, finish

HERE = os.path.dirname(os.path.abspath(__file__))
RECORD = os.path.join(HERE, "..", "m7", "VARKA-389.md")
GREY = "#5c5f66"
PASS = PALETTE["green"]
FAIL = PALETTE["red"]
C2 = PALETTE["blue"]
MATURITY = 1000


def table(path, header):
    with open(path) as f:
        lines = f.read().splitlines()
    start = lines.index(header)
    out = []
    for line in lines[start + 2 :]:
        if not line.startswith("|"):
            break
        cells = [c.strip() for c in line.strip("|").split("|")]
        out.append(cells)
    return out


rows = table(RECORD, "| run | calls | `k` | C1 queue | C2 queue | decision |")
runs = {}
for run, calls, k, c1, c2, decision in rows:
    runs.setdefault(run, []).append((int(calls.replace(",", "")), float(k), int(c2), decision))
assert len(runs) == 2, runs.keys()

r = Rough(1000, 670, seed=5901)
r.text(40, 44, "One template, two runs", size=32)
r.text(
    40,
    80,
    "the policy's reports for IntVector.max on four cores; "
    "where its first compile fell, and at what tier",
    size=18,
    color=GREY,
)

X0, X1 = 110, 940
LO, HI = 100, 40000


def px(calls):
    return X0 + (X1 - X0) * (math.log10(calls) - math.log10(LO)) / (math.log10(HI) - math.log10(LO))


# The shared axis: calls, log scale.
Y_AXIS = 540
r.arrow(X0, Y_AXIS, X1 + 10, Y_AXIS)
for v in (100, 200, 500, 1000, 2000, 5000, 10000, 20000):
    r.line(px(v), Y_AXIS - 6, px(v), Y_AXIS + 6)
    r.text(px(v), Y_AXIS + 26, f"{v:,}", size=15, anchor="middle", color=GREY)
r.text(
    (X0 + X1) / 2,
    Y_AXIS + 48,
    "calls of the template, log scale",
    size=16,
    anchor="middle",
    color=GREY,
)

# C2's maturity line.
r.line(px(MATURITY), 130, px(MATURITY), Y_AXIS - 10, color=C2, width=1.4, dash="8 6")
r.text(px(MATURITY) + 8, 128, "C2 trusts a profile\nfrom 1,000 calls", size=15, color=C2)

tracks = [("G13, passed", 230, PASS), ("G14a, failed", 420, FAIL)]
for run, y, colour in tracks:
    events = runs[run]
    label = "passing run" if "passed" in run else "failing run"
    r.text(X0, y - 58, label, size=20)
    r.line(X0, y, X1, y, color=GREY, width=1.0)
    first = next(e for e in events if e[3].startswith("compile"))
    last = events[-1]
    # The reports before the first compile, with k above each.
    for calls, k, c2, decision in events:
        if calls >= first[0]:
            break
        r.line(px(calls), y - 10, px(calls), y + 10, color=GREY, width=1.4)
        r.text(px(calls), y - 20, "k %.1f" % k, size=13, anchor="middle", color=GREY)
    # The first compile.
    tier = "3" if "tier 3" in first[3] else "2"
    r.ellipse(px(first[0]), y, 9, 9, fill="green" if tier == "3" else "red", color=colour)
    r.text(
        px(first[0]),
        y + 40,
        "C2 queue %d: tier %s\nat %s calls" % (first[2], tier, f"{first[0]:,}"),
        size=15,
        anchor="middle",
        color=colour,
    )
    # The profile: fed at tier 3, frozen at tier 2.
    profile = int(last[3].rsplit(" ", 1)[1].replace(",", ""))
    if tier == "3":
        r.line(px(first[0]), y - 32, px(profile), y - 32, color=colour, width=6)
        r.text(
            px(profile) - 6,
            y - 44,
            "profile fed to %s calls" % f"{profile:,}",
            size=14,
            anchor="end",
            color=colour,
        )
    else:
        r.line(px(first[0]), y - 32, px(first[0]) + 6, y - 32, color=colour, width=6)
        r.text(
            px(first[0]) + 14,
            y - 32,
            "profile frozen at %s calls, under the line" % f"{profile:,}",
            size=14,
            color=colour,
        )
    # The tier-4 compile.
    r.ellipse(px(last[0]), y, 9, 9, fill="blue", color=C2)
    r.text(
        px(last[0]),
        y + 40,
        "tier 4 at\n%s calls" % f"{last[0]:,}",
        size=15,
        anchor="middle",
        color=C2,
    )

r.note(
    40,
    642,
    "the same test, the same four cores, a few hundred milliseconds apart in each JVM's life",
)

finish(r, "fig59-one-template-two-runs")
