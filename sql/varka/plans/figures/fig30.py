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
"""Figure 30: the first minute of a new wide stage - each query's time, back to back, for a
projection of 150 cheap columns let into one stage by maxFields=200, and the same projection
outside a stage at the default, on stock Spark 4.2.0 on a GitHub-hosted runner and on a laptop.
The stage runs C1's code until C2's arrives, and the point where each settles is marked.

Every value is read from committed files when the script runs: the runner's from
sql/varka/demo/silent-giveups/compile_wait-jdk17-output.txt, the laptop's from the first-minute
section of sql/core/benchmarks/CodegenFallbackCostBenchmark-jdk25-laptop-results.txt
(PLAN_TASK_233.md 11.3, 13.6)."""

import os

from rough import Rough, finish

HERE = os.path.dirname(os.path.abspath(__file__))
TOP = os.path.join(HERE, "..", "..", "..")
RUNNER = os.path.join(TOP, "varka", "demo", "silent-giveups", "compile_wait-jdk17-output.txt")
LAPTOP = os.path.join(
    TOP, "core", "benchmarks", "CodegenFallbackCostBenchmark-jdk25-laptop-results.txt"
)


def read_series(path, in_label, out_label):
    """(ms per query in a stage, ms per query outside one) and the CPU, from a file's series."""
    lines = open(path).read().split("\n")
    found, cpu = {}, ""
    for i, line in enumerate(lines):
        if line.startswith("cpu:"):
            cpu = line.split(":", 1)[1].strip()
        elif line.startswith(("AMD", "Intel")) and not cpu:
            cpu = line.split(" w/ ")[0]
        if line.startswith("ms per query:"):
            label, times = lines[i - 1], line.split(":", 1)[1]
        elif ", ms per query:" in line:
            label, times = line.split(", ms per query:")
        else:
            continue
        for key, want in (("in", in_label), ("out", out_label)):
            if want in label:
                found[key] = [int(t) for t in times.split()]
    return found["in"], found["out"], cpu


def arrival(times):
    """Seconds before the first query within 10% of the last ten's median that starts five whose
    median is too: where the drop is on the plot. The plan's time to steady state starts the
    five-query window instead, so it can read up to two queries earlier."""

    def med(xs):
        return sorted(xs)[len(xs) // 2]

    final = med(times[-10:])
    for i in range(len(times) - 4):
        if times[i] <= final * 1.1 and med(times[i : i + 5]) <= final * 1.1:
            return sum(times[:i]) / 1000.0, final
    return sum(times) / 1000.0, final


runs = [
    ("runner, JDK 17", read_series(RUNNER, "maxFields=200, in a stage", "outside a stage")),
    (
        "laptop, JDK 25",
        read_series(LAPTOP, "maxFields=200, in a stage", "maxFields=100, the default, outside"),
    ),
]

r = Rough(960, 660, seed=3001)
r.text(40, 40, "The first minute of a new wide stage", size=30)
r.text(
    40,
    72,
    "each query's time, back to back: 150 cheap columns, in a stage and out of it",
    size=20,
    color="#5c5f66",
)

X0, X1, Y0, Y1 = 120, 880, 540, 130
T1, MS = 60.0, 2200.0


def px(t):
    return X0 + (X1 - X0) * t / T1


def py(ms):
    return Y0 - (Y0 - Y1) * ms / MS


r.line(X0, Y0, X1 + 20, Y0)
r.line(X0, Y0, X0, Y1 - 20)
for ms in (500, 1000, 1500, 2000):
    r.line(X0 - 8, py(ms), X0, py(ms))
    r.text(X0 - 14, py(ms), "%d ms" % ms, size=19, anchor="end", color="#5c5f66")
for t in (0, 10, 20, 30, 40, 50, 60):
    r.line(px(t), Y0, px(t), Y0 + 8)
    r.text(px(t), Y0 + 24, "%d s" % t, size=18, anchor="middle", color="#5c5f66")
r.text((X0 + X1) / 2, Y0 + 56, "seconds since the stage's first query", size=21, anchor="middle")

COLOURS = {
    ("runner, JDK 17", "in"): "#e03131",
    ("runner, JDK 17", "out"): "#1971c2",
    ("laptop, JDK 25", "in"): "#f08c00",
    ("laptop, JDK 25", "out"): "#74c0fc",
}
for name, (inside, outside, _) in runs:
    for kind, times in (("in", inside), ("out", outside)):
        pts, t = [], 0.0
        for ms in times:
            t += ms / 1000.0
            if t > T1:
                break
            pts.append((px(t), py(ms)))
        r.curve(pts, color=COLOURS[(name, kind)], width=2.6)
    wait, final = arrival(inside)
    r.line(px(wait), py(final) - 40, px(wait), py(final) + 40, color="#868e96", dash="5 5")

# The legend, top right, one line a series.
for k, ((name, kind), colour) in enumerate(COLOURS.items()):
    y = 150 + k * 28
    r.line(640, y, 680, y, color=colour, width=3.0)
    r.text(690, y, "%s, %s" % (name, "in a stage" if kind == "in" else "outside"), size=19)

rw, _ = arrival(runs[0][1][0])
lw, _ = arrival(runs[1][1][0])
r.note(
    px(1.5),
    py(1440),
    "C2's code arrives %.0f s in on the runner\nand %.0f s in on the laptop; until\n"
    "then the stage runs C1's code" % (rw, lw),
    size=19,
)
r.text(
    40,
    640,
    "runner: %s, stock Spark 4.2.0; laptop: %s, a build of Spark master"
    % (runs[0][1][2], runs[1][1][2]),
    size=17,
    color="#868e96",
)

finish(r, "fig30-first-minute")
