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

"""Figure 69: Spark's own query - of SparkMathHostBenchmark's 262,144 rows, how many differ from
the same functions over StrictMath, per SQL function, with the largest difference in ulps: on the
Ryzen laptop (part E of results/VARKA-587-math-host-laptop.txt) and on the EPYC 7763 and Neoverse
N2 runners (results/VARKA-587-spark-epyc7763.txt and -spark-arm.txt, owed until they land). The
script checks that whole-stage, generated and interpreted Spark give one digest per column."""

import re

from mathhost import GREY, LAPTOP, SPARK_ARM, SPARK_EPYC, cpu, lane_cpu, owed, part, spark
from rough import Rough, finish

RED = "#e03131"
hosts = [
    ("%s (laptop)" % cpu(part(LAPTOP, "A")), part(LAPTOP, "E")),
    ("%s (GitHub runner)" % lane_cpu("mathlane-x86-avx2-epyc7763.txt"), part(SPARK_EPYC, "E")),
    ("%s (GitHub Arm runner)" % lane_cpu("mathlane-aarch64-neoverse-n2.txt"), part(SPARK_ARM, "E")),
]
data = [(name, spark(lines)[0].get("whole-stage")) for name, lines in hosts]
lines = part(LAPTOP, "E")
modes = spark(lines)[0]
assert all(len({modes[m][c][2] for m in modes}) == 1 for c in modes["whole-stage"]), modes
n = int(re.search(r"^rows: (\d+)", "\n".join(lines), re.M).group(1))
ours = data[0][1]
landed = [(name, wsc) for name, wsc in data if wsc]
waiting = [name for name, wsc in data if not wsc]
cols = [c for c, v in ours.items() if v[0]]
zero = [c.split("(")[0] for c, v in ours.items() if not v[0]]

r = Rough(1000, 880, seed=6901)
r.text(40, 44, "Spark's own query", size=32)
r.text(
    40,
    80,
    "rows of %s where a function's answer differs from fdlibm's (StrictMath)%s"
    % (format(n, ","), "" if len(landed) > 1 else ", on the " + landed[0][0]),
    size=18,
    color=GREY,
)

X0, X1, TOP = (330 if len(landed) > 1 else 170), 830, 35000.0


def px(v):
    return X0 + (X1 - X0) * v / TOP


y = 124
for col in cols:
    r.text(40, y + 11 * len(landed), col, size=24)
    for name, wsc in landed:
        if len(landed) > 1:
            r.text(X0 - 12, y + 11, name.split(" (")[0], size=14, anchor="end", color=GREY)
        k, ulp, _ = wsc[col]
        if k:
            r.rect(X0, y + 2, max(px(k) - X0, 3), 18, color=RED)
        label = "%s, up to %d ulp%s" % (format(k, ","), ulp, "s" * (ulp > 1)) if k else "0"
        r.text(px(k) + 10, y + 11, label, size=17, color=GREY)
        y += 26
    y += 18

r.arrow(X0, y, X1 + 30, y)
for v in range(0, int(TOP) + 1, 10000):
    r.line(px(v), y - 6, px(v), y + 6)
    r.text(px(v), y + 22, format(v, ","), size=16, anchor="middle", color=GREY)
r.text(
    40,
    y + 60,
    "%s: 0 rows on the %s" % (", ".join(zero), hosts[0][0].split(" (")[0]),
    size=17,
    color=GREY,
)
if waiting:
    owed(r, 40, y + 92, 920, 40, "owed: the same bars on the " + " and the ".join(waiting), size=17)
    y += 60
r.note(
    40,
    y + 96,
    "whole-stage codegen, generated projection, interpreted: one digest per column",
    size=17,
)
r.h = int(y + 140)
finish(r, "fig69-sparks-own-query")
