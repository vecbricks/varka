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

"""Figure 24: the first query - what a new shape costs, query by query, in two panels on one
log scale: the first query of a new shape, Spark against Varka with its warm-up on, which is the
default; and the second query, with Varka's kernel once compiled beside it. Milliseconds a query,
at each rung of the size ladder over a hundred thousand cached rows. Two panels rather than five
lines in one, because the runs of one engine differ only in a dash and the two engines' reds were
read as one colour.

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
RED, GREEN, BLUE = "#e03131", "#2f9e44", "#1971c2"
SPARK_FIRST = "vanilla Spark, first run"
SPARK_SECOND = "vanilla Spark, second run"
VARKA_FIRST = "Varka with warm-up, first run"
VARKA_SECOND = "Varka with warm-up, second run"
VARKA_COMPILED = "Varka with warm-up, once compiled"
CASES = (SPARK_FIRST, SPARK_SECOND, VARKA_FIRST, VARKA_SECOND, VARKA_COMPILED)


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
rungs = sorted(n for n in rows if all(c in rows[n] for c in CASES))
below = max(n for n in rungs if method.get(n, 0) <= 8000)
above = min(n for n in rungs if method.get(n, 0) > 8000)
first, last = rungs[0], rungs[-1]

r = Rough(1040, 690, seed=2401)
r.text(40, 44, "The first queries of a new shape", size=32)
r.text(
    40,
    84,
    "milliseconds a query, best of five, log scale; 100,000 Arrow-cached rows; %s, four cores"
    % cpu.replace("(R)", "").split(" CPU")[0],
    size=17,
    color=GREY,
)

Y0, Y1 = 560, 170
LO, HI = 20.0, 3000.0


def py(ms):
    return Y0 - (Y0 - Y1) * (math.log10(ms) - math.log10(LO)) / (math.log10(HI) - math.log10(LO))


def panel(x0, x1, title, series):
    """Axes, the cliff, the series and their end labels, pushed apart where they would collide."""

    def px(n):
        return x0 + (x1 - x0) * (n - 10) / 94.0

    r.text((x0 + x1) / 2, Y1 - 40, title, size=21, anchor="middle")
    r.line(x0, Y0, x1 + 16, Y0)
    r.line(x0, Y0, x0, Y1 - 16)
    for ms in (20, 50, 100, 200, 500, 1000, 2000):
        r.line(x0 - 8, py(ms), x0, py(ms))
        r.text(x0 - 14, py(ms), "{:,}".format(ms), size=16, anchor="end", color=GREY)
    for n in (16, 32, 48, 64, 80, 100):
        r.line(px(n), Y0, px(n), Y0 + 8)
        r.text(px(n), Y0 + 24, str(n), size=16, anchor="middle", color=GREY)
    r.text((x0 + x1) / 2, Y0 + 56, "expressions in the projection", size=18, anchor="middle")
    xs = (px(below) + px(above)) / 2
    r.line(xs, Y0, xs, Y1 - 6, color="#868e96", width=1.8, dash="7 6")
    r.text(xs + 8, Y1 + 4, "the method passes\n8000 bytes", size=15, color=GREY)
    ends = []
    for case, label, color in series:
        pts = [(px(n), py(rows[n][case])) for n in rungs]
        for (xa, ya), (xb, yb) in zip(pts, pts[1:]):
            r.line(xa, ya, xb, yb, color=color, width=2.6)
        for x, y in pts:
            r.ellipse(x, y, 3.5, 3.5, color=color, width=1.8)
        ends.append([pts[-1][1], label, color])
    # End labels in order of height, each at least 24 px from the one above it.
    ends.sort(key=lambda e: e[0])
    for i in range(1, len(ends)):
        ends[i][0] = max(ends[i][0], ends[i - 1][0] + 24)
    for y, label, color in ends:
        r.text(px(last) + 12, y, label, size=17, color=color)


r.text(60, py(HI) - 8, "ms", size=16, color=GREY)
panel(110, 420, "the first query", [(SPARK_FIRST, "Spark", RED), (VARKA_FIRST, "Varka", GREEN)])
panel(
    590,
    880,
    "the second query",
    [
        (SPARK_SECOND, "Spark", RED),
        (VARKA_SECOND, "Varka, still\nwarming", GREEN),
        (VARKA_COMPILED, "Varka, once\ncompiled", BLUE),
    ],
)

lo16, hi16 = min(verdicts[first]) / 1000.0, max(verdicts[first]) / 1000.0
lo100, hi100 = min(verdicts[last]) / 1000.0, max(verdicts[last]) / 1000.0
r.note(
    110,
    Y0 + 96,
    "the warm-up compiles the kernel in %.1f to %.1f s at %d expressions, %d to %d s at %d;"
    "\nuntil then a query runs on Spark's own projection outside a stage"
    % (lo16, hi16, first, int(lo100), int(hi100), last),
    size=17,
)

finish(r, "fig24-the-first-query")
