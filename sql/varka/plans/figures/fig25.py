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

"""Figure 25: two generators, one measurement - Spark writes Java source, compiles it with
Janino, reads every method's size off the class file, logs the ones past 8000 bytes and runs
them anyway; Varka emits bytecode, reads the same sizes off the class it built, and splits a
group or declines an output before anything runs. The one station both pipelines share is the
measurement; what differs is what happens after it. No measured value is drawn, so the figure
reads nothing from the results files."""

from rough import Rough, finish

GREY = "#5c5f66"
RED = "#e03131"
GREEN = "#2f9e44"

r = Rough(1000, 640, seed=2501, roughness=1.3, sketchy=True, fill_style="drop")
r.text(40, 44, "Two generators, one measurement", size=32)
r.text(
    40,
    82,
    "both read every method's size off the class file; what differs is what happens next",
    size=18,
    color=GREY,
)

W, H = 150, 120
XS = (30, 210, 390, 570)


def lane(top, name, color, boxes):
    r.text(30, top - 22, name, size=22, color=color)
    for i, (head, body, fill) in enumerate(boxes):
        x = XS[i]
        r.rect(x, top, W, H, fill=fill)
        r.text(x + W / 2, top + 26, head, size=21, anchor="middle")
        r.text(x + W / 2, top + 78, body, size=16, anchor="middle")
        if i:
            r.arrow(x - 30, top + H / 2, x - 4, top + H / 2, width=2.0)


# Spark's lane.
TOP_A = 130
lane(
    TOP_A,
    "Spark",
    RED,
    [
        ("your query", "a projection of\na hundred date\nexpressions", "grey"),
        ("Java source", "one method does\neach row's work;\nno split in a stage", "blue"),
        ("Janino", "compiles the\nsource in memory", "blue"),
        ("measured", "every method's\nbytes, read off\nthe class file", "yellow"),
    ],
)
r.arrow(XS[3] + W + 4, TOP_A + H / 2, 766, TOP_A + H / 2, width=2.0)
r.rect(770, TOP_A, 200, H, fill="red")
r.text(870, TOP_A + 26, "logged, run anyway", size=20, anchor="middle")
r.text(
    870,
    TOP_A + 78,
    "INFO: method too long\nto be JIT compiled;\nevery row interpreted",
    size=16,
    anchor="middle",
)
r.note(
    XS[2] + 40,
    TOP_A + H + 34,
    "the one check on the number, hugeMethodLimit, defaults\n"
    "to 65535, a size no compiled method reaches, so nothing acts",
    size=17,
)

# Varka's lane.
TOP_B = 380
lane(
    TOP_B,
    "Varka",
    GREEN,
    [
        ("your query", "the same hundred\nexpressions", "grey"),
        ("vector IR", "each entry a tree\nover lanes; literals\nare slots", "violet"),
        ("bytecode", "Class-File API,\nno source, no\ncompiler", "green"),
        ("measured", "every method's\nbytes and calls,\noff the class it built", "yellow"),
    ],
)
# Three outcomes, fanned out from the measurement.
OUT_X, OUT_W, OUT_H = 770, 200, 62
outcomes = [
    (TOP_B - 40, "fits", "one class per shape,\nkept for every task, run", "green"),
    (TOP_B + 40, "a group is over", "split at its middle\nand built again", "orange"),
    (
        TOP_B + 120,
        "one output is over by itself",
        "declined, the reason in\nEXPLAIN; Spark computes it",
        "grey",
    ),
]
for y, head, body, fill in outcomes:
    r.arrow(XS[3] + W + 4, TOP_B + H / 2, OUT_X - 6, y + OUT_H / 2, width=1.8)
    r.rect(OUT_X, y, OUT_W, OUT_H, fill=fill)
    r.text(OUT_X + OUT_W / 2, y + 18, head, size=17, anchor="middle")
    r.text(OUT_X + OUT_W / 2, y + 44, body, size=14, anchor="middle")
# The regroup goes back to the emitter.
r.curve(
    [
        (OUT_X + 6, TOP_B + 40 + OUT_H),
        (720, TOP_B + H + 92),
        (XS[2] + W / 2, TOP_B + H + 92),
        (XS[2] + W / 2, TOP_B + H + 4),
    ],
    color="#e8590c",
    width=1.8,
    arrow=True,
)
r.text(560, TOP_B + H + 114, "built again, with smaller groups", size=16, color="#e8590c")
r.note(
    30,
    TOP_B + H + 60,
    "no method Varka runs is one the JIT\nnever compiles, and the decision is\n"
    "taken before the class runs anywhere",
    size=17,
)

finish(r, "fig25-two-generators-one-measurement")
