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

"""Figure 11: the size ladder - time per row against the number of expressions in one
projection: Spark under its defaults, Spark with hugeMethodLimit=8000, and Varka. Under the
defaults Spark steps where its generated method passes HotSpot's 8000-byte limit and is never
compiled again; tuned, the step is a slope; Varka is a line through the same rungs.

Every value is read from the committed results files of the published machine when the script
runs, so the figure cannot drift from them (VARKA-171.md 9.3). Spark's defaults and Varka
come from one run; the tuned line from a run on the same CPU model, and the note under the
legend gives how far that run's defaults are from this one's (VARKA-181.md 14)."""

import math
import os
import re

from rough import Rough, finish

HERE = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.join(HERE, "..", "..", "..", "core", "benchmarks")
RESULTS = os.path.join(BENCH, "VarkaSizeLadderBenchmark-jdk25-runner-results.txt")
PROVENANCE = os.path.join(BENCH, "VarkaSizeLadderBenchmark-jdk25-runner-provenance.txt")
TUNING = os.path.join(BENCH, "VarkaSizeLadderTuningBenchmark-jdk25-runner-results.txt")

VANILLA = "vanilla Spark (whole-stage codegen)"
VARKA = "Varka"
DEFAULTS = "vanilla Spark, defaults"
TUNED = "vanilla Spark, hugeMethodLimit=8000"


def read_ladder(path):
    """{entries: {case: (per_row_ns_text, relative_text)}} and {entries: vanilla method bytes}."""
    rows, method = {}, {}
    rung = None
    head = re.compile(r"^(\d+) entries over ")
    row = re.compile(r"^(.*?)\s+\d+\s+\d+\s+\d+\s+[\d.]+\s+([\d.]+)\s+([\d.]+)X\s*$")
    note = re.compile(r"^rung (\d+): vanilla's largest generated method is (\d+) bytes")
    with open(path) as f:
        for line in f:
            line = line.rstrip()
            if m := head.match(line):
                rung = int(m.group(1))
                rows[rung] = {}
            elif (m := row.match(line)) and rung is not None:
                rows[rung][m.group(1).strip()] = (m.group(2), m.group(3))
            elif m := note.match(line):
                method[int(m.group(1))] = int(m.group(2))
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
tuning, _ = read_ladder(TUNING)
prov = read_provenance(PROVENANCE)
cpu = prov["cpu"].split(",")[0]
jdk = prov["jdk"].replace("OpenJDK 64-Bit Server VM ", "JDK ")
rungs = sorted(n for n in rows if n in tuning)
# How far the tuned run's defaults are from this run's, at the rung where they differ most.
drift = max(abs(float(tuning[n][DEFAULTS][0]) / float(rows[n][VANILLA][0]) - 1) for n in rungs)
# The step: the last rung whose method is under the limit and the first past it.
below = max(n for n in rungs if method[n] <= 8000)
above = min(n for n in rungs if method[n] > 8000)

r = Rough(900, 700, seed=113)
r.text(40, 40, "n date expressions in one projection, 2 million Arrow-cached rows", size=24)
r.text(40, 74, "time per row, log scale; %s, %s, one core" % (cpu, jdk), size=17, color="#5c5f66")

# The axes: entries on x, nanoseconds a row on a log scale on y.
X0, X1, Y0, Y1 = 120, 830, 560, 120
LO, HI = 10.0, 20000.0


def px(n):
    return X0 + (X1 - X0) * n / 104.0


def py(ns):
    return Y0 - (Y0 - Y1) * (math.log10(ns) - math.log10(LO)) / (math.log10(HI) - math.log10(LO))


r.line(X0, Y0, X1 + 20, Y0)
r.line(X0, Y0, X0, Y1 - 20)
for ns, label in [(10, "10 ns"), (100, "100 ns"), (1000, "1,000 ns"), (10000, "10,000 ns")]:
    r.line(X0 - 8, py(ns), X0, py(ns))
    r.text(X0 - 14, py(ns), label, size=16, anchor="end", color="#5c5f66")
for n in (16, 32, 48, 64, 80, 100):
    r.line(px(n), Y0, px(n), Y0 + 8)
    r.text(px(n), Y0 + 24, str(n), size=16, anchor="middle", color="#5c5f66")
r.text((X0 + X1) / 2, Y0 + 56, "expressions in the projection", size=18, anchor="middle")

# Where the method passes HotSpot's limit.
xs = (px(below) + px(above)) / 2
r.line(xs, Y0, xs, Y1 - 10, color="#868e96", dash="6 6")
r.text(
    xs - 10,
    Y1 - 8,
    "Spark's method passes 8000 bytes:\n%d at %d, %d at %d"
    % (method[below], below, method[above], above),
    size=16,
    anchor="end",
    color="#5c5f66",
)


def series(case, color):
    pts = [(px(n), py(float(rows[n][case][0]))) for n in rungs]
    for (x1, y1), (x2, y2) in zip(pts, pts[1:]):
        r.line(x1, y1, x2, y2, color=color, width=2.4)
    for x, y in pts:
        r.ellipse(x, y, 4, 4, color=color, width=2.0)
    return pts


RED, BLUE, GREEN = "#e03131", "#1971c2", "#2f9e44"
series(VANILLA, RED)
pts = [(px(n), py(float(tuning[n][TUNED][0]))) for n in rungs]
for (x1, y1), (x2, y2) in zip(pts, pts[1:]):
    r.line(x1, y1, x2, y2, color=BLUE, width=2.4, dash="7 5")
for x, y in pts:
    r.ellipse(x, y, 4, 4, color=BLUE, width=2.0)
series(VARKA, GREEN)
last = rungs[-1]
r.text(px(last) + 12, py(float(rows[last][VANILLA][0])), "Spark,\ndefaults", size=19, color=RED)
r.text(px(last) + 12, py(float(tuning[last][TUNED][0])), "Spark,\ntuned", size=19, color=BLUE)
r.text(px(last) + 12, py(float(rows[last][VARKA][0])), "Varka", size=20, color=GREEN)
r.text(
    X0,
    Y0 + 96,
    "tuned: hugeMethodLimit=8000, a run on the same CPU model whose defaults\n"
    "are within %.0f%% of this run's at every rung" % math.ceil(100 * drift),
    size=15,
    color="#5c5f66",
)

# The two things the figure shows, kept apart: the step is Spark's alone, the gap is Varka's
# at every width.
r.note(
    X0 + 20,
    py(4000),
    "the step: %s to %s ns a row,\nand never compiled again"
    % (rows[below][VANILLA][0], rows[above][VANILLA][0]),
    size=18,
)
r.note(
    px(above) + 14,
    py(300),
    "Varka: %.0fx faster than the defaults at %d,\n"
    "%.0fx at %d; %.0fx faster than tuned\nat %d, %.0fx at %d"
    % (
        float(rows[below][VARKA][1]),
        below,
        float(rows[last][VARKA][1]),
        last,
        float(tuning[above][TUNED][0]) / float(rows[above][VARKA][0]),
        above,
        float(tuning[last][TUNED][0]) / float(rows[last][VARKA][0]),
        last,
    ),
    size=18,
)

finish(r, "fig11-the-size-ladder")
