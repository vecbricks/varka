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
"""Figure 12: the method-size step on stock Spark 4.2.0, on three JDKs - the demo's projection
of n date expressions, time per row against n, with the 8000-byte crossing marked. The step is
in the same place on every JDK because Spark writes the bytecode, not the JDK.

Every value is read from the demo's committed runner outputs when the script runs
(sql/varka/demo/method_size_cliff-jdk{17,21,25}-output.txt), so the figure cannot drift from
them (PLAN_TASK_203.md 9.2)."""

import math
import os
import re

from rough import Rough, finish

HERE = os.path.dirname(os.path.abspath(__file__))
DEMO = os.path.join(HERE, "..", "..", "demo")
JDKS = (17, 21, 25)
COLORS = {17: "#e03131", 21: "#f08c00", 25: "#1971c2"}


def read_output(path):
    """{entries: (method bytes, past the limit, ns per row at the defaults)} and the CPU."""
    rows, cpu = {}, ""
    row = re.compile(r"^(\d+)\s+(\d+) bytes( \*)?\s+([\d.]+)\s+([\d.]+)\s*$")
    with open(path) as f:
        for line in f:
            if line.startswith("cpu:"):
                cpu = line.split(":", 1)[1].strip()
            elif m := row.match(line.rstrip()):
                rows[int(m.group(1))] = (int(m.group(2)), m.group(3) is not None, float(m.group(4)))
    return rows, cpu


data = {}
cpus = {}
for jdk in JDKS:
    data[jdk], cpus[jdk] = read_output(
        os.path.join(DEMO, "method_size_cliff-jdk%d-output.txt" % jdk)
    )
entries = sorted(data[JDKS[0]])
below = max(n for n in entries if not data[JDKS[0]][n][1])
above = min(n for n in entries if data[JDKS[0]][n][1])

r = Rough(900, 660, seed=127)
r.text(40, 40, "n date expressions in one projection, stock Spark 4.2.0", size=24)
r.text(
    40,
    72,
    "nanoseconds a row, log scale; GitHub-hosted runners, one core",
    size=17,
    color="#5c5f66",
)

X0, X1, Y0, Y1 = 130, 800, 520, 130
LO, HI = 400.0, 20000.0
N0, N1 = 42, 58


def px(n):
    return X0 + (X1 - X0) * (n - N0) / float(N1 - N0)


def py(ns):
    return Y0 - (Y0 - Y1) * (math.log10(ns) - math.log10(LO)) / (math.log10(HI) - math.log10(LO))


r.line(X0, Y0, X1 + 20, Y0)
r.line(X0, Y0, X0, Y1 - 20)
for ns, label in [
    (500, "500 ns"),
    (1000, "1 000 ns"),
    (2000, "2 000 ns"),
    (5000, "5 000 ns"),
    (10000, "10 000 ns"),
]:
    r.line(X0 - 8, py(ns), X0, py(ns))
    r.text(X0 - 14, py(ns), label, size=16, anchor="end", color="#5c5f66")
for n in entries:
    r.line(px(n), Y0, px(n), Y0 + 8)
    r.text(
        px(n),
        Y0 + 24,
        "%d\n%d bytes" % (n, data[JDKS[0]][n][0]),
        size=15,
        anchor="middle",
        color="#5c5f66",
    )
r.text(
    (X0 + X1) / 2,
    Y0 + 80,
    "expressions in the projection, and the largest method's size",
    size=18,
    anchor="middle",
)

# Where the method passes HotSpot's limit: between the last size under it and the first past.
xs = (px(below) + px(above)) / 2
r.line(xs, Y0, xs, Y1 - 10, color="#868e96", dash="6 6")
r.text(
    xs - 10,
    Y1 - 6,
    "past 8000 bytes: HotSpot\nnever compiles the method",
    size=16,
    anchor="end",
    color="#5c5f66",
)

for jdk in JDKS:
    pts = [(px(n), py(data[jdk][n][2])) for n in entries]
    # One smooth stroke through the rungs rather than a chain of segments.
    r.curve(pts, color=COLORS[jdk], width=2.4)
    for x, y in pts:
        r.ellipse(x, y, 4, 4, color=COLORS[jdk], width=2.0)
    last = entries[-1]
    r.text(px(last) + 12, py(data[jdk][last][2]), "JDK %d" % jdk, size=19, color=COLORS[jdk])

steps = ", ".join("%.1fx on JDK %d" % (data[j][above][2] / data[j][below][2], j) for j in JDKS)
r.note(
    px(N0) + 20,
    py(6000),
    "from %d to %d expressions the time per row steps\n%s" % (below, above, steps),
    size=18,
)
r.text(
    40,
    640,
    "JDK 17 and 25 ran on an %s, JDK 21 on an %s" % (cpus[17], cpus[21]),
    size=15,
    color="#868e96",
)

finish(r, "fig12-the-step-on-three-jdks")
