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

"""Figure 24: the first query - what a new shape costs, query by query: Spark's first and
second run against Varka's first and second run with its warm-up on, which is the default,
and Varka once its kernel is compiled, in milliseconds a query on a log scale, at each rung of
the size ladder over a hundred thousand cached rows.

Every value is read from the committed runner file when the script runs
(sql/core/benchmarks/VarkaColdStartBenchmark-jdk25-runner-xeon8370c-results.txt,
PLAN_TASK_181.md 9.1), and the line where Spark's method passes 8000 bytes from the size
ladder's file, which runs the same expressions; so the figure cannot drift from either."""

import math
import os
import re

from rough import Rough, finish

HERE = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.join(HERE, "..", "..", "..", "core", "benchmarks")
RESULTS = os.path.join(BENCH, "VarkaColdStartBenchmark-jdk25-runner-xeon8370c-results.txt")
LADDER = os.path.join(BENCH, "VarkaSizeLadderBenchmark-jdk25-runner-results.txt")
GREY = "#5c5f66"
RED, ORANGE, GREEN = "#e03131", "#e8590c", "#2f9e44"
# (case in the file, label, colour, dash)
CASES = [
    ("vanilla Spark, first run", "Spark, first run", RED, "7 5"),
    ("vanilla Spark, second run", "Spark, second run", RED, None),
    ("Varka with warm-up, first run", "Varka, first run", ORANGE, "7 5"),
    ("Varka with warm-up, second run", "Varka, second run, still warming", ORANGE, None),
    ("Varka with warm-up, once compiled", "Varka, once compiled", GREEN, None),
]


def read_cold_start(path):
    """{entries: {case: best ms}}, {entries: [warm-up verdict ms]}, and the CPU."""
    rows, verdicts, cpu, rung = {}, {}, "", None
    head = re.compile(r"^(\d+) entries over \d+ Arrow-cached rows:")
    row = re.compile(r"^(.*?)\s+(\d+)\s+\d+\s+\d+\s+[\d.]+\s+[\d.]+\s+[\d.]+X\s*$")
    verdict = re.compile(r"^rung (\d+): the warm-up's verdicts")
    with open(path) as f:
        for line in f:
            line = line.rstrip()
            if " CPU " in line or line.endswith("Processor"):
                cpu = cpu or line.strip()
            elif line.startswith("====") or line.startswith("steady state"):
                rung = None
            elif m := head.match(line):
                rung = int(m.group(1))
                rows.setdefault(rung, {})
            elif (m := row.match(line)) and rung is not None:
                rows[rung][m.group(1).strip()] = int(m.group(2))
            elif m := verdict.match(line):
                verdicts[int(m.group(1))] = [int(v) for v in re.findall(r"after (\d+) ms", line)]
    return rows, verdicts, cpu


def ladder_methods(path):
    note = re.compile(r"^rung (\d+): vanilla's largest generated method is (\d+) bytes")
    with open(path) as f:
        return {int(m.group(1)): int(m.group(2)) for m in map(note.match, f) if m}


rows, verdicts, cpu = read_cold_start(RESULTS)
method = ladder_methods(LADDER)
rungs = sorted(n for n in rows if all(c in rows[n] for c, _, _, _ in CASES))
below = max(n for n in rungs if method.get(n, 0) <= 8000)
above = min(n for n in rungs if method.get(n, 0) > 8000)
first, last = rungs[0], rungs[-1]

r = Rough(960, 700, seed=2401)
r.text(40, 44, "The first queries of a new shape", size=32)
r.text(
    40,
    84,
    "milliseconds a query, best of five, log scale; 100,000 Arrow-cached rows; %s, four cores"
    % cpu.replace("(R)", "").split(" CPU")[0],
    size=17,
    color=GREY,
)

X0, X1, Y0, Y1 = 120, 860, 580, 150
LO, HI = 20.0, 3000.0


def px(n):
    return X0 + (X1 - X0) * (n - 10) / 94.0


def py(ms):
    return Y0 - (Y0 - Y1) * (math.log10(ms) - math.log10(LO)) / (math.log10(HI) - math.log10(LO))


r.line(X0, Y0, X1 + 20, Y0)
r.line(X0, Y0, X0, Y1 - 20)
for ms in (20, 50, 100, 200, 500, 1000, 2000):
    r.line(X0 - 8, py(ms), X0, py(ms))
    r.text(X0 - 14, py(ms), "{:,} ms".format(ms), size=17, anchor="end", color=GREY)
for n in (16, 32, 48, 64, 80, 100):
    r.line(px(n), Y0, px(n), Y0 + 8)
    r.text(px(n), Y0 + 24, str(n), size=17, anchor="middle", color=GREY)
r.text((X0 + X1) / 2, Y0 + 58, "expressions in the projection", size=20, anchor="middle")

xs = (px(below) + px(above)) / 2
r.line(xs, Y0, xs, Y1 - 10, color="#868e96", width=1.8, dash="7 6")
r.text(xs + 10, Y1 - 6, "Spark's method passes 8000 bytes", size=16, color=GREY)

for case, label, color, dash in CASES:
    pts = [(px(n), py(rows[n][case])) for n in rungs]
    for (x1, y1), (x2, y2) in zip(pts, pts[1:]):
        r.line(x1, y1, x2, y2, color=color, width=2.4, dash=dash)
    for x, y in pts:
        r.ellipse(x, y, 3.5, 3.5, color=color, width=1.8)

# A legend rather than end labels: three lines end within a fifth of each other at 100.
lx, ly = X0 + 30, Y1 + 10
for i, (case, label, color, dash) in enumerate(CASES):
    y = ly + 26 * i
    r.line(lx, y, lx + 36, y, color=color, width=2.6, dash=dash)
    r.text(lx + 46, y, label, size=17, color=color)

lo16, hi16 = min(verdicts[first]) / 1000.0, max(verdicts[first]) / 1000.0
lo100, hi100 = min(verdicts[last]) / 1000.0, max(verdicts[last]) / 1000.0
r.note(
    px(56),
    py(32),
    "the warm-up compiles the kernel in %.1f to %.1f s\nat %d expressions, %d to %d s at %d"
    % (lo16, hi16, first, int(lo100), int(hi100), last),
    size=18,
)

finish(r, "fig24-the-first-query")
