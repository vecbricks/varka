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
"""Figure 18: a step or a slope - the size ladder's projection of n date columns under Spark's
defaults, under spark.sql.codegen.hugeMethodLimit=8000, and with whole-stage codegen off, time
per row on a linear scale. The defaults step where the stage's method passes 8000 bytes and
stay up; the two settings turn the step into a slope.

Every value is read from the committed runner file when the script runs
(sql/core/benchmarks/VarkaSizeLadderTuningBenchmark-jdk25-runner-results.txt), so the figure
cannot drift from it (PLAN_TASK_192.md 9.5)."""

import os
import re

from rough import Rough, finish

HERE = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.join(HERE, "..", "..", "..", "core", "benchmarks")
RESULTS = os.path.join(BENCH, "VarkaSizeLadderTuningBenchmark-jdk25-runner-results.txt")

DEFAULTS = "vanilla Spark, defaults"
TUNED = "vanilla Spark, hugeMethodLimit=8000"
OFF = "vanilla Spark, wholeStage=false"
STYLE = {
    DEFAULTS: ("#e03131", "the defaults"),
    TUNED: ("#1971c2", "hugeMethodLimit=8000"),
    OFF: ("#868e96", "wholeStage=false"),
}
GREY = "#5c5f66"


def read_ladder(path):
    """{entries: {case: ns per row}}, {entries: method bytes}, and the CPU named in the file."""
    rows, method, cpu, rung = {}, {}, "", None
    head = re.compile(r"^(\d+) entries over ")
    row = re.compile(r"^(.*?)\s+\d+\s+\d+\s+\d+\s+[\d.]+\s+([\d.]+)\s+[\d.]+X\s*$")
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
                rows[rung][m.group(1).strip()] = float(m.group(2))
            elif m := note.match(line):
                method[int(m.group(1))] = int(m.group(2))
    return rows, method, cpu


rows, method, cpu = read_ladder(RESULTS)
rungs = sorted(n for n in rows if all(c in rows[n] for c in STYLE))
below = max(n for n in rungs if method[n] <= 8000)
above = min(n for n in rungs if method[n] > 8000)
top = max(rows[n][c] for n in rungs for c in STYLE)

r = Rough(960, 640, seed=181)
r.text(40, 44, "A step, or a slope", size=32)
r.text(
    40,
    84,
    "n date columns in one projection, 2 million cached rows, one core, %s" % cpu,
    size=19,
    color=GREY,
)

X0, X1, Y0, Y1 = 120, 760, 540, 140
N0, N1 = 10, 104
YMAX = 1000 * (int(top / 1000) + 1)


def px(n):
    return X0 + (X1 - X0) * (n - N0) / float(N1 - N0)


def py(ns):
    return Y0 - (Y0 - Y1) * ns / YMAX


r.line(X0, Y0, X1 + 20, Y0)
r.line(X0, Y0, X0, Y1 - 20)
for ns in range(0, int(YMAX) + 1, 2000):
    r.line(X0 - 8, py(ns), X0, py(ns))
    r.text(X0 - 14, py(ns), "{:,} ns".format(ns), size=18, anchor="end", color=GREY)
for n in (16, 32, 48, 64, 80, 100):
    r.line(px(n), Y0, px(n), Y0 + 8)
    r.text(px(n), Y0 + 26, str(n), size=19, anchor="middle", color=GREY)
r.text((X0 + X1) / 2, Y0 + 62, "date columns in the projection", size=21, anchor="middle")

xs = (px(below) + px(above)) / 2
r.line(xs, Y0, xs, Y1 - 10, color="#868e96", width=1.8, dash="7 6")
r.text(xs - 10, Y1 - 6, "the method passes\n8000 bytes", size=19, anchor="end", color=GREY)

for case, (color, label) in STYLE.items():
    pts = [(px(n), py(rows[n][case])) for n in rungs]
    r.curve(pts, color=color, width=3.0, monotone=True)
    for x, y in pts:
        r.ellipse(x, y, 4, 4, color=color, width=2.0)
last = rungs[-1]
# The two settings end within a few percent of each other, so their labels are spaced apart.
ends = sorted(STYLE, key=lambda c: rows[last][c])
for i, case in enumerate(ends):
    color, label = STYLE[case]
    y = py(rows[last][case]) + (24 * (i - 0.5) if case != DEFAULTS else 0)
    r.text(px(last) + 14, y, label, size=21, color=color)

r.arrow(
    300,
    py(6500) + 52,
    px(above) - 10,
    py(rows[above][DEFAULTS]) - 4,
    color="#6741d9",
    width=1.8,
)
r.note(
    px(12),
    py(6500),
    "the defaults: %.0fx at the step,\nand the method is never\ncompiled again"
    % (rows[above][DEFAULTS] / rows[below][DEFAULTS]),
    size=20,
)
r.note(
    px(60),
    py(3600),
    "either setting: a slope, %.0fx faster\nthan the defaults at %d columns"
    % (rows[last][DEFAULTS] / rows[last][TUNED], last),
    size=20,
)

finish(r, "fig18-a-step-or-a-slope")
