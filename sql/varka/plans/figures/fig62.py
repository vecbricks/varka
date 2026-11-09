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

"""Figure 62: two libraries behind one function list - Spark's math functions by the Java class
they call, java.lang.Math or StrictMath, each marked by whether Math differs from fdlibm for it on
the laptop at JDK 25. Read from part A of results/VARKA-587-math-host-laptop.txt, whose lines name
Spark's class and SQL functions beside each JDK method's count."""

from mathhost import LAPTOP, cpu, inputs, part, probe
from rough import Rough, finish

GREY = "#5c5f66"
RED, GREEN = "#e03131", "#2f9e44"

lines = part(LAPTOP, "A")
fns = probe(lines)
n = inputs(lines)
assert fns["sin"]["class"] == "Math" and fns["exp"]["class"] == "StrictMath", fns

r = Rough(1000, 560, seed=6201)
r.text(40, 44, "Two libraries behind one function list", size=32)
r.text(
    40,
    80,
    "which Java class each Spark math function calls, and whether HotSpot",
    size=18,
    color=GREY,
)
r.text(40, 104, "replaces it on the %s laptop (x86, JDK 25)" % cpu(lines), size=18, color=GREY)


def box(x, y, w, h, color, label, size=19):
    r.rect(x, y, w, h, color=color)
    r.text(x + w / 2, y + h / 2, label, size=size, anchor="middle", color=color)


def column(x, title, sub, cls):
    r.text(x + 210, 150, title, size=24, anchor="middle")
    r.text(x + 210, 178, sub, size=16, anchor="middle", color=GREY)
    y = 210
    same = []
    for fn, f in fns.items():
        if f["class"] != cls:
            continue
        if cls == "Math" and f["differ"] == 0:
            same += f["names"]
            continue
        differs = f["differ"] > 0
        color = (RED if differs else GREY) if cls == "Math" else GREEN
        box(x, y, 150, 34, color, ", ".join(f["names"]), size=17)
        if cls == "Math":
            note = "%s of %s inputs differ" % (format(f["differ"], ","), format(n, ","))
        elif differs:
            note = "Math.%s would differ on %s" % (fn, format(f["differ"], ","))
        else:
            note = "the same either way"
        r.text(x + 162, y + 17, note, size=15, color=RED if cls == "Math" else GREY)
        y += 40
    if same:
        half = (len(same) + 1) // 2
        label = ", ".join(same[:half]) + ",\n" + ", ".join(same[half:])
        box(x, y, 150, 56, GREY, label, size=15)
        r.text(
            x + 162, y + 28, "Math delegates these\nto StrictMath: 0 differ", size=15, color=GREY
        )
        y += 62
    return y


y1 = column(40, "java.lang.Math", "what Spark calls for these", "Math")
y2 = column(530, "StrictMath", "fdlibm in Java, on every host", "StrictMath")
r.line(500, 140, 500, max(y1, y2))
r.note(
    40,
    max(y1, y2) + 24,
    "red: answers that can change with the host. Spark chose StrictMath for exp, ln and pow,"
    "\nso their Math stubs, which differ too, never reach a query.",
)
finish(r, "fig62-two-libraries-behind-one-function-list")
