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

"""Figure 54: compiles by tier - every nmethod of VarkaWarmupEndToEndSuite's JVM by compile tier,
on four cores at 256-bit lanes against twenty-four cores, as the two compilation logs count them.
Tier 2 is C1 with counters only, the tier the tiered policy falls back to while C2's queue is
longer than Tier3DelayOn times C2's threads; a template that passes through it is never
profiled. The counts are read from the tiers table of VARKA-389.md section 9.5."""

import os

from rough import Rough, finish

HERE = os.path.dirname(os.path.abspath(__file__))
RECORD = os.path.join(HERE, "..", "m7", "VARKA-389.md")
GREY = "#5c5f66"
FOUR, MANY = "#ff8787", "#74c0fc"
HIGHLIGHT = "#e03131"


def table(path, header):
    with open(path) as f:
        lines = f.read().splitlines()
    start = lines.index(header)
    out = []
    for line in lines[start + 2 :]:
        if not line.startswith("|"):
            break
        cells = [c.strip() for c in line.strip("|").split("|")]
        out.append((cells[0], int(cells[1].replace(",", "")), int(cells[2].replace(",", ""))))
    return out


rows = table(RECORD, "| tier | four cores, 256-bit lanes | twenty-four cores |")
assert len(rows) == 5, rows

r = Rough(1000, 700, seed=5401)
r.text(40, 44, "Compiles by tier", size=32)
r.text(
    40,
    78,
    "Every compile of the suite's JVM, four cores at 256-bit lanes against twenty-four",
    size=18,
    color=GREY,
)

X0, X1 = 330, 940
TOP = 20000
BAR_H = 26


def px(v):
    return X0 + (X1 - X0) * v / TOP


y = 140
for label, four, many in rows:
    tier2 = label.startswith("2")
    r.text(
        X0 - 14,
        y + BAR_H + 4,
        label,
        size=17 if not tier2 else 19,
        anchor="end",
        color=HIGHLIGHT if tier2 else "#1e1e1e",
    )
    r.rect(X0, y, max(px(four) - X0, 2), BAR_H, fill=FOUR, solid=True)
    r.text(px(four) + 10, y + BAR_H - 6, f"{four:,}", size=16, color=GREY)
    r.rect(X0, y + BAR_H + 6, max(px(many) - X0, 2), BAR_H, fill=MANY, solid=True)
    r.text(px(many) + 10, y + 2 * BAR_H, f"{many:,}", size=16, color=GREY)
    y += 2 * BAR_H + 40

Y0 = y + 6
r.arrow(X0, Y0, X1 + 20, Y0)
for v in range(0, TOP + 1, 5000):
    r.line(px(v), Y0 - 6, px(v), Y0 + 6)
    r.text(px(v), Y0 + 24, f"{v:,}", size=15, anchor="middle", color=GREY)

y = Y0 + 56
r.rect(X0, y, 22, 18, fill=FOUR, solid=True)
r.text(X0 + 30, y + 10, "four cores, 256-bit lanes", size=17)
r.rect(X0 + 300, y, 22, 18, fill=MANY, solid=True)
r.text(X0 + 330, y + 10, "twenty-four cores", size=17)
r.note(
    60,
    y + 50,
    "Tier 2 profiles nothing. IntVector.max went from tier 2 to tier 4 on four\n"
    "cores, and the kernel compile that inlined it found no profile at its inner call.",
)
finish(r, "fig54-compiles-by-tier")
