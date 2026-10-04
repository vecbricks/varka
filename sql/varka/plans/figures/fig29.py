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
"""Figure 29: a wide projection in a whole-stage codegen stage against the same projection
outside one - for 50 and 99 cheap columns and 50 and 99 mixed columns, the time a row in a stage
over the time without one, on a GitHub-hosted runner and on a laptop. Above the line the stage
is slower; below it, faster.

Every value is read from the benchmark's committed files when the script runs
(sql/core/benchmarks/CodegenWideProjectionBenchmark-jdk25-results.txt and its -laptop-
counterpart, VARKA-233.md 13.5)."""

import os
import re

from rough import Rough, finish

HERE = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.join(HERE, "..", "..", "..", "core", "benchmarks")
FILES = [
    ("CodegenWideProjectionBenchmark-jdk25-results.txt", "runner", "#1971c2"),
    ("CodegenWideProjectionBenchmark-jdk25-laptop-results.txt", "laptop", "#f08c00"),
]
SHAPES = [(50, "cheap"), (99, "cheap"), (50, "mixed"), (99, "mixed")]


def read_bench(path):
    """{(entries, kind): (ns a row in a stage, ns a row with whole-stage codegen off)}, and CPU."""
    rows, cur, cpu = {}, None, ""
    head = re.compile(r"^(\d+) (\w+) entries over \d+ cached rows:")
    case = re.compile(r"^(the defaults|wholeStage=false): .*?\s([\d.]+)\s+[\d.]+X\s*$")
    with open(path) as f:
        for line in f:
            line = line.rstrip()
            if m := head.match(line):
                cur = (int(m.group(1)), m.group(2))
                rows[cur] = {}
            elif line.startswith(("AMD", "Intel")) and not cpu:
                cpu = line.split(" w/ ")[0]
            elif cur and (m := case.match(line)):
                rows[cur][m.group(1)] = float(m.group(2))
    return {k: (v["the defaults"], v["wholeStage=false"]) for k, v in rows.items()}, cpu


data = {}
cpus = {}
for name, who, _ in FILES:
    data[who], cpus[who] = read_bench(os.path.join(BENCH, name))

r = Rough(960, 640, seed=2901, fill_style="tint-hatch")
r.text(40, 40, "A wide projection, in a stage and out of it", size=30)
r.text(
    40,
    72,
    "time a row in a whole-stage codegen stage over the time without one, defaults, after C2",
    size=20,
    color="#5c5f66",
)

X0, X1, Y0, Y1 = 140, 900, 500, 130
LO, HI = 0.7, 1.7


def py(ratio):
    return Y0 - (Y0 - Y1) * (ratio - LO) / (HI - LO)


r.line(X0, Y0, X0, Y1 - 10)
for ratio in (0.8, 1.0, 1.2, 1.4, 1.6):
    r.line(X0 - 8, py(ratio), X0, py(ratio))
    r.text(X0 - 14, py(ratio), "%.1f" % ratio, size=19, anchor="end", color="#5c5f66")
base = py(1.0)
r.line(X0, base, X1, base, color="#868e96", dash="6 6")
r.text(X1, base - 14, "the same speed", size=18, anchor="end", color="#5c5f66")
r.text(X0 + 8, Y1 - 4, "slower in a stage", size=19, color="#c92a2a")
r.text(X0 + 8, Y0 + 2, "faster in a stage", size=19, color="#2b8a3e")

group_w = (X1 - X0) / len(SHAPES)
bar_w = 54
for g, shape in enumerate(SHAPES):
    cx = X0 + group_w * (g + 0.5)
    for k, (_, who, colour) in enumerate(FILES):
        stage, out = data[who][shape]
        ratio = stage / out
        x = cx + (k - 1) * (bar_w + 10) + 5
        top, bottom = (py(ratio), base) if ratio >= 1 else (base, py(ratio))
        r.rect(x, top, bar_w, bottom - top, fill="red" if ratio >= 1 else "green")
        label = "%.2fx" % ratio if ratio >= 1 else "-%d%%" % round(100 * (1 - ratio))
        ly = top - 14 if ratio >= 1 else bottom + 16
        r.text(x + bar_w / 2, ly, label, size=18, anchor="middle")
        r.text(x + bar_w / 2, Y0 + 30, who, size=17, anchor="middle", color=colour)
    r.text(cx, Y0 + 60, "%d %s columns" % shape, size=21, anchor="middle")

r.note(
    X0 + group_w * 2 + 20,
    py(1.55),
    "a column that does work of its own\ncosts more than the call C2\nleaves behind",
    size=19,
)
r.text(
    40,
    620,
    "runner: %s; laptop: %s; JDK 25" % (cpus["runner"], cpus["laptop"]),
    size=17,
    color="#868e96",
)

finish(r, "fig29-wide-projection-in-and-out")
