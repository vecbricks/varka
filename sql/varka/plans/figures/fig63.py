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

"""Figure 63: what changes the answer - five things that could change the bits of Spark's sin,
cos, tan, tanh and cbrt, each with its verdict and the count behind it: the JIT tier and Spark's
code path (parts A and E of results/VARKA-587-math-host-laptop.txt), the x86 CPU model (the
control passes of dev/varka_canary/mathlane-x86-*.txt), the JDK version (part D) and the
architecture (mathlane-aarch64-neoverse-n2.txt, and the Arm runner's file once it lands)."""

import os
import re

from mathhost import ARM, LAPTOP, children, mathlane_control, part, spark
from rough import Rough, finish

GREY = "#5c5f66"
RED = "#e03131"
FIVE = ["sin", "cos", "tan", "tanh", "cbrt"]

kids = children(part(LAPTOP, "A"))
tiers = [k for k in kids if k[0] in ("interpreter only", "C1 only", "C2 only")]
assert len(tiers) == 3, kids
tier_same = all(eq == total for _, eq, total in tiers)

modes, _ = spark(part(LAPTOP, "E"))
paths_same = all(len({modes[m][col][2] for m in modes}) == 1 for col in modes["whole-stage"])

epyc, n_lane = mathlane_control("mathlane-x86-avx2-epyc7763.txt")
zen5, _ = mathlane_control("mathlane-x86-avx512-zen5.txt")
n2, _ = mathlane_control("mathlane-aarch64-neoverse-n2.txt")
cpu_same = all(epyc[f.upper()] == zen5[f.upper()] for f in FIVE)

jdk = {}
for ln in part(LAPTOP, "D"):
    m = re.match(r"^(\w+)\s+differ=(\d+)", ln)
    if m:
        jdk[m.group(1)] = int(m.group(2))
jdk_moved = [f for f in FIVE if jdk[f] > 0]

arm_cmp = None
if os.path.exists(ARM):
    text = open(ARM).read()
    if "6. This host's Math against" in text:
        tail = text[text.index("6. This host's Math against") :].splitlines()
        arm_cmp = {
            m.group(1): int(m.group(2))
            for m in (re.match(r"^(\w+)\s+differ=(\d+)", ln) for ln in tail)
            if m
        }


def fmt(v):
    return format(v, ",")


rows = [
    (
        "the JIT tier",
        "interpreter, C1, C2",
        not tier_same,
        "each tier gives the same bits for all\n18 functions (laptop, JDK 25)",
    ),
    (
        "Spark's code path",
        "whole-stage, generated,\ninterpreted",
        not paths_same,
        "one answer per column in all three",
    ),
    (
        "the x86 CPU",
        "Zen 5 laptop against\nEPYC 7763 runner",
        not cpu_same,
        "the same count on both, of %s inputs:\nsin %s, cos %s, tan %s, tanh %s, cbrt %s"
        % (fmt(n_lane), *(fmt(epyc[f.upper()][0]) for f in FIVE)),
    ),
    (
        "the JDK",
        "21 to 25, same laptop",
        bool(jdk_moved),
        "%s change: %s and %s of %s inputs"
        % (" and ".join(jdk_moved), *(fmt(jdk[f]) for f in jdk_moved), fmt(1 << 20)),
    ),
    (
        "the architecture",
        "x86 against Arm\n(Neoverse N2)",
        True,
        "Arm replaces only sin and cos (%s, %s of %s\ndiffer from fdlibm); x86 all five"
        % (fmt(n2["SIN"][0]), fmt(n2["COS"][0]), fmt(n_lane)),
    ),
]

r = Rough(1000, 690, seed=6301)
r.text(40, 44, "What changes the answer", size=32)
r.text(40, 80, "of Spark's sin, cos, tan, tanh and cbrt", size=18, color=GREY)


def box(x, y, w, h, color, label, size=20):
    r.rect(x, y, w, h, color=color)
    r.text(x + w / 2, y + h / 2, label, size=size, anchor="middle", color=color)


y = 120
for cause, sub, changes, why in rows:
    r.text(40, y + 22, cause, size=22)
    r.text(40, y + 58, sub, size=15, color=GREY)
    box(300, y + 14, 80, 40, RED if changes else GREY, "yes" if changes else "no")
    r.text(410, y + 34, why, size=16, color=RED if changes else GREY)
    y += 96
if arm_cmp is None:
    for x1, y1, x2, y2 in (
        (410, y - 30, 950, y - 30),
        (950, y - 30, 950, y - 4),
        (950, y - 4, 410, y - 4),
        (410, y - 4, 410, y - 30),
    ):
        r.line(x1, y1, x2, y2, color=GREY, width=1.2, dash="6 6")
    r.text(
        680,
        y - 17,
        "owed: the laptop against the Arm runner, input by input",
        size=15,
        anchor="middle",
        color=GREY,
    )
else:
    r.text(
        410,
        y - 17,
        "laptop against Arm, of 1,048,576 inputs: "
        + ", ".join("%s %s" % (f, fmt(arm_cmp[f])) for f in FIVE),
        size=15,
        color=RED,
    )
r.note(
    40,
    y + 26,
    "not the CPU model, not the tier, not the code path:\nthe instruction set"
    " and the JDK build decide which routine computes Math.sin",
)
finish(r, "fig63-what-changes-the-answer")
