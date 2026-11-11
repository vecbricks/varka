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

"""Figure 64: how often, how far - for each of the five Math methods behind Spark's host-dependent
functions, the share of MathHostProbe's 2^20 inputs where Math differs from StrictMath (fdlibm),
by 1 ulp and by 2, on the laptop at JDK 25 and 21 (parts A and B of
results/VARKA-587-math-host-laptop.txt) and on the Arm runner (results/VARKA-587-math-host-arm.txt,
drawn as owed until it lands)."""

from mathhost import ARM, LAPTOP, cpu, inputs, lane_cpu, part, probe
from rough import Rough, finish

GREY = "#5c5f66"
ONE, TWO = "#e03131", "#7048e8"
FIVE = ["sin", "cos", "tan", "tanh", "cbrt"]

hosts = [
    ("%s, JDK 25" % " ".join(cpu(part(LAPTOP, "A")).split()[:3]), part(LAPTOP, "A")),
    ("%s, JDK 21" % " ".join(cpu(part(LAPTOP, "B")).split()[:3]), part(LAPTOP, "B")),
    ("%s, JDK 25" % lane_cpu("mathlane-aarch64-neoverse-n2.txt"), part(ARM, "A")),
]
data = [(name, probe(lines), inputs(lines) if lines else None) for name, lines in hosts]

r = Rough(1000, 720, seed=6401)
r.text(40, 44, "How often, how far", size=32)
r.text(
    40,
    80,
    "share of 1,048,576 inputs where Math differs from fdlibm (StrictMath)",
    size=18,
    color=GREY,
)

X0, X1, TOP = 300, 900, 14.0


def px(pct):
    return X0 + (X1 - X0) * pct / TOP


y = 120
for fn in FIVE:
    r.text(40, y + 40, fn, size=26)
    for name, fns, n in data:
        r.text(X0 - 14, y + 13, name, size=15, anchor="end", color=GREY)
        if not fns:
            for x1, x2 in ((X0, X0 + 160),):
                r.line(x1, y + 4, x2, y + 4, color=GREY, width=1.2, dash="6 6")
                r.line(x1, y + 22, x2, y + 22, color=GREY, width=1.2, dash="6 6")
            r.text(X0 + 80, y + 13, "owed: Arm runner", size=15, anchor="middle", color=GREY)
        else:
            f = fns[fn]
            one, two = 100.0 * f["ulp1"] / n, 100.0 * f["ulp2"] / n
            if f["values"]:
                r.rect(X0, y + 4, max(px(one) - X0, 3), 18, color=ONE)
                if two:
                    r.rect(px(one), y + 4, max(px(one + two) - px(one), 3), 18, color=TWO)
            label = "%.1f%%" % (one + two) if f["values"] else "0: the same as fdlibm"
            if two:
                label += "  (%s by 2 ulps)" % format(f["ulp2"], ",")
            r.text(px(one + two) + 10, y + 13, label, size=15, color=GREY)
        y += 26
    y += 24

r.arrow(X0, y, X1 + 20, y)
for v in range(0, int(TOP) + 1, 2):
    r.line(px(v), y - 6, px(v), y + 6)
    r.text(px(v), y + 22, "%d%%" % v, size=15, anchor="middle", color=GREY)
r.rect(X0, y + 46, 22, 16, color=ONE)
r.text(X0 + 30, y + 54, "1 ulp", size=16)
r.rect(X0 + 120, y + 46, 22, 16, color=TWO)
r.text(X0 + 150, y + 54, "2 ulps", size=16)
finish(r, "fig64-how-often-how-far")
