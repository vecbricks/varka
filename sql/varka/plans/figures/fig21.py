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

"""Figure 21: one method or a class - the size ladder's hundred entries as the one method
Spark's whole-stage code puts them in, and as the class Varka emits for them, every method
drawn to the same scale of bytecode bytes against HotSpot's 8000-byte limit.

Spark's size is read from the ladder's committed runner file, Varka's from the class dump
committed beside this script (data/size-ladder-class-100.txt), so the figure cannot drift
from either."""

import os
import re

from rough import Rough, finish

HERE = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.join(HERE, "..", "..", "..", "core", "benchmarks")
LADDER = os.path.join(BENCH, "VarkaSizeLadderBenchmark-jdk25-runner-results.txt")
CLASS = os.path.join(HERE, "data", "size-ladder-class-100.txt")
ENTRIES = 100
LIMIT = 8000
GREY = "#5c5f66"
RED = "#e03131"
LOOP, EPILOGUE, DRIVER = "#2f9e44", "#1971c2", "#7048e8"


def spark_method(path, entries):
    note = re.compile(r"^rung (\d+): vanilla's largest generated method is (\d+) bytes")
    with open(path) as f:
        for line in f:
            m = note.match(line)
            if m and int(m.group(1)) == entries:
                return int(m.group(2))
    raise ValueError("no rung %d in %s" % (entries, path))


def varka_methods(path):
    """{method name: bytes} from the dump's method table."""
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


spark = spark_method(LADDER, ENTRIES)
sizes = varka_methods(CLASS)
groups = sorted(int(n[len("loopDense") :]) for n in sizes if n.startswith("loopDense"))
largest = max(sizes.values())
drivers = [sizes["runDense"], sizes["runMasked"]]

r = Rough(960, 640, seed=2101)
r.text(40, 44, "The same hundred expressions", size=32)
r.text(
    40,
    84,
    "bytecode bytes per method: Spark's generated stage, and the class Varka emits",
    size=19,
    color=GREY,
)

X0, X1, Y0, Y1 = 120, 920, 540, 150
TOP = 18000


def py(b):
    return Y0 - (Y0 - Y1) * b / float(TOP)


r.line(X0, Y0, X1, Y0)
r.line(X0, Y0, X0, Y1 - 20)
for b in range(0, TOP + 1, 4000):
    r.line(X0 - 8, py(b), X0, py(b))
    r.text(X0 - 14, py(b), "{:,}".format(b), size=18, anchor="end", color=GREY)
r.text(X0 - 14, Y1 - 34, "bytes", size=18, anchor="end", color=GREY)

r.line(X0, py(LIMIT), X1, py(LIMIT), color=RED, width=1.8, dash="8 6")
r.text(
    X1,
    py(LIMIT) - 18,
    "8000 bytes: HotSpot never compiles a longer method",
    size=18,
    anchor="end",
    color=RED,
)

# Spark: one bar.
sx, sw = 160, 80
r.rect(sx, py(spark), sw, Y0 - py(spark), fill="red")
r.text(
    sx + sw + 16, py(spark) + 30, "Spark: one method,\n{:,} bytes".format(spark), size=20, color=RED
)

# Varka: four methods a group, side by side, then the two drivers.
vx = 285
bar, gap, group_gap = 4.0, 1.0, 4.0
x = vx
for g in groups:
    for kind, color in (
        ("loopDense", LOOP),
        ("loopMasked", LOOP),
        ("epilogueDense", EPILOGUE),
        ("epilogueMasked", EPILOGUE),
    ):
        b = sizes["%s%d" % (kind, g)]
        r.line(x, Y0, x, py(b), color=color, width=bar)
        x += bar + gap
    x += group_gap
x += 10
for b in drivers:
    r.line(x, Y0, x, py(b), color=DRIVER, width=bar * 1.6)
    x += bar * 1.6 + 4
r.text(
    (vx + x) / 2,
    py(LIMIT) + 40,
    "Varka: one class, {} groups of four methods\nand two drivers; the largest {:,} bytes".format(
        len(groups), largest
    ),
    size=20,
    anchor="middle",
    color=LOOP,
)

# What each colour is, under the bars.
ly = Y0 + 34
for i, (label, color) in enumerate(
    (
        ("a group's loop methods", LOOP),
        ("its epilogues", EPILOGUE),
        ("the drivers that call them", DRIVER),
    )
):
    lx = vx + (0, 250, 420)[i]
    r.line(lx, ly, lx + 22, ly, color=color, width=5)
    r.text(lx + 30, ly, label, size=17, color=GREY)

finish(r, "fig21-one-method-or-a-class")
