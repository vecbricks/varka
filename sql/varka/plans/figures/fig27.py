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

"""Figure 27: one kernel class, and one batch's path through it - the entry point picks the
dense or the masked driver, the driver prepares every output's validity from a table and calls
the groups in turn, and each group is a loop method over full vectors and an epilogue over the
tail under a mask. The sizes on the boxes are the class the size ladder's hundred entries emit,
read from the committed dump (data/size-ladder-class-100.txt) when the script runs."""

import os
import re

from rough import Rough, finish

HERE = os.path.dirname(os.path.abspath(__file__))
CLASS = os.path.join(HERE, "data", "size-ladder-class-100.txt")
GREY = "#5c5f66"
GREEN, BLUE, VIOLET = "#2f9e44", "#1971c2", "#7048e8"


def methods(path):
    sizes, table = {}, False
    with open(path) as f:
        for line in f:
            if line.startswith("method "):
                table = True
            elif table and re.match(r"^[A-Za-z]\w*\s+\d+\s+\d+", line):
                name, size = line.split()[:2]
                sizes[name] = int(size)
            elif table:
                break
    return sizes


sizes = methods(CLASS)
groups = sorted(int(n[len("loopDense") :]) for n in sizes if n.startswith("loopDense"))
largest = max(sizes.values())


def kb(name):
    return "{:,} bytes".format(sizes[name])


r = Rough(1000, 720, seed=2701, roughness=1.3, sketchy=True, fill_style="sparse")
r.text(40, 44, "One kernel class, and one batch's path through it", size=32)
r.text(
    40,
    82,
    "the class the size ladder's hundred entries emit; every size read off the class",
    size=18,
    color=GREY,
)

# The entry point above the two drivers, side by side.
r.rect(400, 110, 200, 60, fill="grey")
r.text(500, 130, "run(batch)", size=20, anchor="middle")
r.text(500, 155, "any nulls in the inputs?", size=15, anchor="middle")
for i, (name, label, x0) in enumerate((("runDense", "no", 250), ("runMasked", "yes", 610))):
    r.rect(x0, 200, 280, 86, fill="violet")
    r.text(x0 + 140, 222, "%s: %s" % (name, kb(name)), size=19, anchor="middle")
    r.text(
        x0 + 140,
        258,
        "maps the inputs, prepares each\noutput's validity from a table,\ncalls the groups in turn",
        size=14,
        anchor="middle",
    )
    r.arrow(500 + (-30 if i == 0 else 30), 174, x0 + 140, 196, width=2.0)
    r.text(500 + (-70 if i == 0 else 60), 190, label, size=15, color=GREY)
r.note(
    40,
    222,
    "past about 180 groups\nthe driver's calls move\ninto stage methods;\npast 64 input columns a\n"
    "second kernel takes the\nentries the first set aside",
    size=14,
)

# The groups the dense driver calls, in order.
GY, GW, GH, GAP = 350, 150, 150, 18
shown = [groups[0], groups[1], groups[2], None, groups[-1]]
x = 40
for g in shown:
    if g is None:
        r.text(x + 30, GY + GH / 2, "...", size=34, anchor="middle")
        r.text(
            x + 30,
            GY + GH / 2 + 34,
            "%d groups" % len(groups),
            size=15,
            anchor="middle",
            color=GREY,
        )
        x += 78
        continue
    r.rect(x, GY, GW, GH, width=1.4)
    r.text(x + GW / 2, GY + 18, "group %d: four entries" % g, size=15, anchor="middle")
    r.rect(x + 8, GY + 34, GW - 16, 48, fill="green")
    r.text(x + GW / 2, GY + 48, "loopDense%d" % g, size=15, anchor="middle")
    r.text(x + GW / 2, GY + 70, kb("loopDense%d" % g), size=14, anchor="middle")
    r.rect(x + 8, GY + 92, GW - 16, 48, fill="blue")
    r.text(x + GW / 2, GY + 106, "epilogueDense%d" % g, size=15, anchor="middle")
    r.text(x + GW / 2, GY + 128, kb("epilogueDense%d" % g), size=14, anchor="middle")
    x += GW + GAP
# Fan the dense driver's calls down to the groups.
for gx in (
    40 + GW / 2,
    40 + GW + GAP + GW / 2,
    40 + 2 * (GW + GAP) + GW / 2,
    40 + 3 * (GW + GAP) + 78 + GW / 2,
):
    r.arrow(390, 290, gx, GY - 4, width=1.4)
r.arrow(750, 290, 750, GY + GH + 76, width=1.4, dash="6 5", color=VIOLET)
r.text(
    40 + 3 * (GW + GAP) + 78 + GW + 22, GY + 20, "in turn,\nonce per\nbatch", size=15, color=GREY
)

# What the two methods of a group are.
r.text(
    40,
    GY + GH + 40,
    "loop method: the group over every full vector of rows, 16 at a time",
    size=16,
    color=GREEN,
)
r.text(
    40,
    GY + GH + 64,
    "epilogue: the same group over the rows left at the end, under a mask",
    size=16,
    color=BLUE,
)
r.text(
    40,
    GY + GH + 88,
    "the masked driver calls the same groups' masked twins, loopMasked and epilogueMasked, which "
    "carry the validity",
    size=16,
    color=VIOLET,
)
r.note(
    40,
    GY + GH + 136,
    "the class's largest method: {:,} bytes, measured, under the 8000 the JIT compiles".format(
        largest
    ),
    size=18,
)

finish(r, "fig27-one-kernel-class")
