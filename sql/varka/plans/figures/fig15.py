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
"""Figure 15: a CASE WHEN of n branches under Spark's three evaluation paths - inside a
whole-stage codegen stage, outside one, and interpreted - time per row against n on log axes.
The stage steps where its one method passes 8000 bytes; the outside-a-stage path steps between
300 and 1000 branches, where the method holding the calls to the split methods passes it too;
the interpreted path grows with the square of the branches.

Every value is read from the committed results file when the script runs
(sql/core/benchmarks/CaseWhenCodegenBenchmark-jdk25-results.txt), so the figure cannot drift
from it (PLAN_TASK_210.md 9.2)."""

import math
import os
import re

from rough import Rough, finish

HERE = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.join(HERE, "..", "..", "..", "core", "benchmarks")
RESULTS = os.path.join(BENCH, "CaseWhenCodegenBenchmark-jdk25-results.txt")
PROVENANCE = os.path.join(BENCH, "CaseWhenCodegenBenchmark-jdk25-provenance.txt")

ON = "whole-stage codegen on"
OFF = "whole-stage codegen off"
INTERP = "interpreted, factoryMode=NO_CODEGEN"
LABELS = {ON: "inside a stage", OFF: "outside a stage", INTERP: "interpreted"}
COLORS = {ON: "#e03131", OFF: "#1971c2", INTERP: "#868e96"}


def read_ladder(path):
    """{branches: {case: ns per row}} and {branches: the stage's largest method, as printed}."""
    rows, method, rung = {}, {}, None
    head = re.compile(r"^CASE WHEN of (\d+) branches over ")
    size = re.compile(r"^largest method of the stage: (.*)$")
    row = re.compile(r"^(.*?)\s+\d+\s+\d+\s+\d+\s+[\d.]+\s+([\d.]+)\s+[\d.]+X\s*$")
    with open(path) as f:
        for line in f:
            line = line.rstrip()
            if m := head.match(line):
                rung = int(m.group(1))
                rows.setdefault(rung, {})
            elif (m := size.match(line)) and rung is not None:
                method[rung] = m.group(1)
            elif (m := row.match(line)) and rung is not None:
                rows[rung][m.group(1).strip()] = float(m.group(2))
    return rows, method


def read_provenance(path):
    fields = {}
    with open(path) as f:
        for line in f:
            if ":" in line and not line.startswith(" "):
                key, value = line.split(":", 1)
                fields[key.strip()] = value.strip()
    return fields


rows, method = read_ladder(RESULTS)
prov = read_provenance(PROVENANCE)
cpu = prov["cpu"].split(",")[0]
jdk = prov["jdk"].replace("OpenJDK 64-Bit Server VM ", "JDK ")
rungs = sorted(rows)

r = Rough(900, 700, seed=139)
r.text(40, 40, "a CASE WHEN of n branches, three ways to run it", size=24)
r.text(
    40,
    72,
    "nanoseconds a row, both axes log; 200 000 rows; %s, %s" % (cpu, jdk),
    size=17,
    color="#5c5f66",
)

X0, X1, Y0, Y1 = 130, 780, 560, 120
LO, HI = 50.0, 1000000.0
NLO, NHI = 25.0, 1400.0


def px(n):
    return X0 + (X1 - X0) * (math.log10(n) - math.log10(NLO)) / (math.log10(NHI) - math.log10(NLO))


def py(ns):
    return Y0 - (Y0 - Y1) * (math.log10(ns) - math.log10(LO)) / (math.log10(HI) - math.log10(LO))


r.line(X0, Y0, X1 + 20, Y0)
r.line(X0, Y0, X0, Y1 - 20)
for ns, label in [
    (100, "100 ns"),
    (1000, "1 000 ns"),
    (10000, "10 000 ns"),
    (100000, "100 000 ns"),
    (1000000, "1 ms"),
]:
    r.line(X0 - 8, py(ns), X0, py(ns))
    r.text(X0 - 14, py(ns), label, size=16, anchor="end", color="#5c5f66")
for n in rungs:
    r.line(px(n), Y0, px(n), Y0 + 8)
    r.text(px(n), Y0 + 24, str(n), size=16, anchor="middle", color="#5c5f66")
r.text((X0 + X1) / 2, Y0 + 56, "branches", size=18, anchor="middle")

for case in (INTERP, ON, OFF):
    pts = [(px(n), py(rows[n][case])) for n in rungs]
    # One smooth stroke through the rungs rather than a chain of segments.
    r.curve(pts, color=COLORS[case], width=2.4)
    for x, y in pts:
        r.ellipse(x, y, 4, 4, color=COLORS[case], width=2.0)
    last = rungs[-1]
    # The stage and the no-stage paths end within a fifth of each other at 1000 branches, so
    # their labels are pushed apart rather than drawn at the points.
    shift = {ON: -14, OFF: 16}.get(case, 0)
    r.text(px(last) + 12, py(rows[last][case]) + shift, LABELS[case], size=19, color=COLORS[case])

first_past = min(
    n for n in rungs if method[n].endswith("bytes") and int(method[n].split()[0]) > 8000
)
r.note(
    px(first_past) + 24,
    py(rows[first_past][ON]) + 40,
    "the stage's method is %s:\nnever compiled, %.0fx slower than no stage"
    % (method[first_past], rows[first_past][ON] / rows[first_past][OFF]),
    size=17,
)
# The outside-a-stage path's own step: the pair of adjacent rungs where it grows the most.
a, b = max(zip(rungs, rungs[1:]), key=lambda p: rows[p[1]][OFF] / rows[p[0]][OFF])
r.note(
    px(a) - 40,
    py(rows[a][OFF]) + 60,
    "outside a stage, %d to %d branches: %.0fx the cost\nfor %.1fx the branches - "
    "the method that calls\nthe split methods passes 8000 bytes itself"
    % (a, b, rows[b][OFF] / rows[a][OFF], b / float(a)),
    size=17,
)
r.text(
    40,
    660,
    "at 1000 branches the stage fails to compile past 64 KB and falls back to the same split"
    " code, plus the failed compile on every run",
    size=15,
    color="#868e96",
)

finish(r, "fig15-the-case-when-ladder")
