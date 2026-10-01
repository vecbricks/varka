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

"""Figure 23: the range filter - modified-q3's predicate, n date ranges joined by `or`, over a
cached integer date column: Spark, and Varka's two designs for a predicate too large for one
method, the range set and the split predicate, time per row on a log scale. Spark steps where
the scan's processNext passes 8000 bytes, since the filter is generated into the scan loop.

Every value is read from the committed runner file when the script runs
(sql/core/benchmarks/VarkaRangeFilterBenchmark-jdk25-runner-9v45-results.txt,
PLAN_TASK_172.md 9.10), so the figure cannot drift from it."""

import math
import os
import re

from rough import Rough, finish

HERE = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.join(HERE, "..", "..", "..", "core", "benchmarks")
RESULTS = os.path.join(BENCH, "VarkaRangeFilterBenchmark-jdk25-runner-9v45-results.txt")

VANILLA = "vanilla Spark (whole-stage codegen)"
RANGE_SET = "Varka"
SPLIT = "Varka, split conditions"
GREY = "#5c5f66"
RED, GREEN, TEAL = "#e03131", "#2f9e44", "#0c8599"


def read_filter(path):
    """{ranges: {case: (ns per row, relative)}}, {ranges: method bytes}, and the CPU."""
    rows, method, cpu, rung = {}, {}, "", None
    head = re.compile(r"^(\d+) ranges over ")
    row = re.compile(r"^(.*?)\s+\d+\s+\d+\s+\d+\s+[\d.]+\s+([\d.]+)\s+([\d.]+)X\s*$")
    note = re.compile(r"^rung (\d+): vanilla's largest generated method is (\d+) bytes")
    with open(path) as f:
        for line in f:
            line = line.rstrip()
            if line.endswith("Processor") or " CPU " in line:
                cpu = cpu or line.strip()
            elif m := head.match(line):
                rung = int(m.group(1))
                rows[rung] = {}
            elif (m := row.match(line)) and rung is not None:
                rows[rung][m.group(1).strip()] = (float(m.group(2)), float(m.group(3)))
            elif m := note.match(line):
                method[int(m.group(1))] = int(m.group(2))
    return rows, method, cpu


rows, method, cpu = read_filter(RESULTS)
rungs = sorted(rows)
below = max(n for n in rungs if method[n] <= 8000)
above = min(n for n in rungs if method[n] > 8000)
last = rungs[-1]

r = Rough(960, 680, seed=2301)
r.text(40, 44, "modified-q3's filter: n date ranges joined by or", size=30)
r.text(
    40,
    82,
    "time per row, log scale; 2 million Arrow-cached rows, one core, %s" % cpu,
    size=18,
    color=GREY,
)

X0, X1, Y0, Y1 = 120, 790, 560, 140
LO, HI = 3.0, 20000.0


def px(n):
    return X0 + (X1 - X0) * n / 210.0


def py(ns):
    return Y0 - (Y0 - Y1) * (math.log10(ns) - math.log10(LO)) / (math.log10(HI) - math.log10(LO))


r.line(X0, Y0, X1 + 20, Y0)
r.line(X0, Y0, X0, Y1 - 20)
for ns, label in [(10, "10 ns"), (100, "100 ns"), (1000, "1,000 ns"), (10000, "10,000 ns")]:
    r.line(X0 - 8, py(ns), X0, py(ns))
    r.text(X0 - 14, py(ns), label, size=17, anchor="end", color=GREY)
for n in (10, 50, 100, 150, 200):
    r.line(px(n), Y0, px(n), Y0 + 8)
    r.text(px(n), Y0 + 24, str(n), size=17, anchor="middle", color=GREY)
r.text((X0 + X1) / 2, Y0 + 58, "date ranges in the filter", size=20, anchor="middle")

xs = (px(below) + px(above)) / 2
r.line(xs, Y0, xs, Y1 - 10, color="#868e96", width=1.8, dash="7 6")
r.text(
    xs + 10,
    Y1 - 6,
    "the scan's method passes 8000 bytes:\n{:,} at {}, {:,} at {}".format(
        method[below], below, method[above], above
    ),
    size=16,
    color=GREY,
)


def series(case, color, dash=None):
    pts = [(px(n), py(rows[n][case][0])) for n in rungs]
    for (x1, y1), (x2, y2) in zip(pts, pts[1:]):
        r.line(x1, y1, x2, y2, color=color, width=2.4, dash=dash)
    for x, y in pts:
        r.ellipse(x, y, 4, 4, color=color, width=2.0)


series(VANILLA, RED)
series(RANGE_SET, GREEN)
series(SPLIT, TEAL, dash="7 5")
# The two designs end within a few percent of each other, so their labels are spaced apart.
r.text(px(last) + 14, py(rows[last][VANILLA][0]), "Spark", size=20, color=RED)
LABELS = {RANGE_SET: ("Varka, range set", GREEN), SPLIT: ("Varka, split\npredicate", TEAL)}
for i, case in enumerate(sorted(LABELS, key=lambda c: rows[last][c][0])):
    label, color = LABELS[case]
    r.text(
        px(last) + 14,
        py(rows[last][case][0]) + (30 if i == 0 else -26),
        label,
        size=18,
        color=color,
    )

r.note(
    px(104),
    py(1300),
    "the step: {} to {} ns a row,\n{:.0f}x, and the scan loop interpreted".format(
        rows[below][VANILLA][0],
        rows[above][VANILLA][0],
        rows[above][VANILLA][0] / rows[below][VANILLA][0],
    ),
    size=18,
)
r.note(
    px(108),
    py(220),
    "at {} ranges: {:.0f}x and {:.0f}x faster;\nat {}: {:.1f}x and {:.1f}x".format(
        last,
        rows[last][RANGE_SET][1],
        rows[last][SPLIT][1],
        below,
        rows[below][RANGE_SET][1],
        rows[below][SPLIT][1],
    ),
    size=18,
)

finish(r, "fig23-the-range-filter")
