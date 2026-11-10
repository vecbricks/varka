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

"""Figure 67: which routine answers - for Spark's five host-dependent Math methods, the routine
HotSpot runs on JDK 25 on two x86 chips and one Arm core, named by whether MathLaneProbe's control
pass (dev/varka_canary/mathlane-*.txt, 262,144 positive moderate inputs) found Math differing from
StrictMath, with the count. Below, the per-input comparison of the Ryzen laptop against the Arm
runner over the probe's 2^20 inputs, owed until results/VARKA-587-math-host-arm.txt lands."""

import os
import re

from mathhost import ARM, CANARY, GREY, box, lane_cpu, mathlane_control, owed
from rough import Rough, finish

RED, GREEN, VIOLET = "#e03131", "#2f9e44", "#7048e8"
FIVE = ["sin", "cos", "tan", "tanh", "cbrt"]
HOSTS = [
    ("mathlane-x86-avx512-zen5.txt", "x86, Zen 5, %s (laptop)"),
    ("mathlane-x86-avx2-epyc7763.txt", "x86, %s (GitHub runner)"),
    ("mathlane-aarch64-neoverse-n2.txt", "Arm (GitHub Arm runner)"),
]


def avx(name):
    m = re.search(r"UseAVX=(\d)", open(os.path.join(CANARY, name)).read())
    return {"3": "AVX-512", "2": "AVX2"}[m.group(1)] if m else None


cols = []
for name, sub in HOSTS:
    counts, n = mathlane_control(name)
    a = avx(name)
    cols.append((lane_cpu(name), sub % a if a else sub, counts, n, a is None))
assert len({c[3] for c in cols}) == 1, cols
x86_same = all(cols[0][2][f.upper()] == cols[1][2][f.upper()] for f in FIVE)

arm_cmp = {}
if os.path.exists(ARM):
    text = open(ARM).read()
    if "6. This host's Math against" in text:
        for ln in text[text.index("6. This host's Math against") :].splitlines():
            m = re.match(r"^(\w+)\s+differ=(\d+)", ln)
            if m:
                arm_cmp[m.group(1)] = int(m.group(2))

r = Rough(1000, 660, seed=6701)
r.text(40, 44, "Which routine answers", size=32)
r.text(
    40,
    80,
    "JDK 25: what HotSpot runs for Math's five, and on how many of %s inputs"
    % format(cols[0][3], ","),
    size=18,
    color=GREY,
)
r.text(40, 104, "it differs from fdlibm (StrictMath)", size=18, color=GREY)

X, W, Y0, H = [200, 465, 730], 240, 210, 58
for x, (cpu, sub, counts, _, is_arm) in zip(X, cols):
    r.text(x + W / 2, 146, cpu, size=21, anchor="middle")
    r.text(x + W / 2, 172, sub, size=16, anchor="middle", color=GREY)
    for i, fn in enumerate(FIVE):
        k = counts[fn.upper()][0]
        if not k:
            color, routine = GREEN, "fdlibm"
        else:
            color, routine = (VIOLET, "Arm's FMA port") if is_arm else (RED, "Intel's stub")
        label = "%s: %s differ" % (routine, format(k, ",")) if k else "fdlibm: the same"
        box(r, x, Y0 + i * H, W, H - 12, color, label, size=17)
for i, fn in enumerate(FIVE):
    r.text(40, Y0 + i * H + 23, "Math." + fn, size=22)
if x86_same:
    r.note(200, Y0 + 5 * H + 6, "the two x86 chips: the same count for every function", size=17)

y = Y0 + 5 * H + 70
r.text(
    40,
    y - 26,
    "the Ryzen laptop against the Arm runner, input by input (the probe's 1,048,576 inputs):",
    size=17,
    color=GREY,
)
for i, fn in enumerate(FIVE):
    x = 200 + i * 158
    r.text(x + 70, y + 2, fn, size=17, anchor="middle")
    if fn in arm_cmp:
        box(r, x, y + 16, 140, 40, RED if arm_cmp[fn] else GREEN, format(arm_cmp[fn], ","))
    else:
        owed(r, x, y + 16, 140, 40, "owed: Arm runner", size=16)
finish(r, "fig67-which-routine-answers")
