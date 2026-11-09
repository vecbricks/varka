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

"""Figure 68: where it bites - the same two-row CSV export written twice, shown as a diff: on the
Ryzen laptop before and after an upgrade from JDK 21 to 25, and on the laptop against the Arm
runner. The JDK 25 answers and fdlibm's are read from part E of
results/VARKA-587-math-host-laptop.txt; JDK 21's from part B's literal lines (cos(0.1) as printed,
cbrt(0.22) as fdlibm's because part B finds no literal where cbrt differs); Arm's from
results/VARKA-587-spark-arm.txt, drawn as owed until it lands. The changed digits are circled,
measured with the font's own advance widths."""

import os
import re

from fontTools.ttLib import TTFont
from mathhost import GREY, LAPTOP, SPARK_ARM, owed, part, spark
from rough import FONT_FILE, Rough, finish

RED, GREEN = "#e03131", "#2f9e44"
SIZE = 24

lit = spark(part(LAPTOP, "E"))[1]
b = "\n".join(part(LAPTOP, "B"))
assert re.search(r"^cbrt\s+0 of 1000$", b, re.M), "part B's cbrt literals changed"
cos21 = re.search(r"^cos\s+\d+ of 1000; first: cos\(0\.1\) = (\S+)", b, re.M).group(1)
arm = spark(part(SPARK_ARM, "E"))[1]
ROWS = [("cos", "0.1"), ("cbrt", "0.22")]
jdk21 = {"cos": cos21, "cbrt": lit["cbrt(0.22)"][1]}
jdk25 = {fn: lit["%s(%s)" % (fn, x)][0] for fn, x in ROWS}
on_arm = {fn: arm["%s(%s)" % (fn, x)][0] for fn, x in ROWS} if arm else None

font = TTFont(FONT_FILE)
cmap, hmtx, upm = font.getBestCmap(), font["hmtx"], font["head"].unitsPerEm


def width(s):
    return sum(hmtx[cmap[ord(c)]][0] for c in s) * SIZE / upm


r = Rough(1000, 420, seed=6801)
r.text(40, 44, "Where it bites", size=32)
r.text(40, 80, "the same query exported to CSV twice, then diffed", size=18, color=GREY)


Y, W = 140, 445


def panel(x, title, old_name, new_name, old, new):
    y = Y
    r.text(x, y, title, size=20)
    r.rect(x, y + 22, W, 210, color=GREY)
    x += 24
    lines = [(" ", "fn,x,value", None)]
    for fn, xv in ROWS:
        a = "%s,%s,%s" % (fn, xv, old[fn])
        if new is None:
            lines.append((" ", a, None))
            continue
        c = "%s,%s,%s" % (fn, xv, new[fn])
        lines += [(" ", a, None)] if a == c else [("-", a, c), ("+", c, a)]
    yy = y + 46
    r.text(x, yy, "--- %s   +++ %s" % (old_name, new_name), size=16, color=GREY)
    yy += 8
    for sign, s, other in lines:
        yy += 34
        color = {"-": RED, "+": GREEN}.get(sign, "#1e1e1e")
        r.text(x, yy, sign, size=SIZE, color=color)
        r.text(x + 22, yy, s, size=SIZE, color=color)
        if other:
            k = len(os.path.commonprefix([s, other]))
            x0, x1 = x + 22 + width(s[:k]), x + 22 + width(s)
            r.ellipse((x0 + x1) / 2 + 1, yy - 2, (x1 - x0) / 2 + 7, 14, color=color, width=1.4)
    return yy


panel(40, "JDK 21 to 25, on the Ryzen laptop", "jdk21.csv", "jdk25.csv", jdk21, jdk25)
if on_arm:
    panel(515, "the Ryzen laptop to Arm, JDK 25", "ryzen.csv", "arm.csv", jdk25, on_arm)
else:
    r.text(515, Y, "the Ryzen laptop to Arm, JDK 25", size=20)
    owed(
        r,
        515,
        Y + 22,
        W,
        210,
        "owed: the Arm runner's answers\nto cos(0.1) and cbrt(0.22)",
        size=18,
    )
finish(r, "fig68-where-it-bites")
