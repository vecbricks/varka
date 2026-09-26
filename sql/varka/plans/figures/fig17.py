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
"""Figure 17: what whole-stage codegen does with a query - the query becomes Java source, one
class per stage whose one method runs the row loop, Janino compiles it to bytecode, and the
JIT compiles that to machine code only while the method is at most 8000 bytes. Below, the
demo's projection at the two sizes either side of the limit, as bars against it.

The sizes are read from the demo's committed JDK 25 output when the script runs
(sql/varka/demo/method_size_cliff-jdk25-output.txt), so the figure cannot drift from it."""

import os
import re

from rough import Rough, finish

HERE = os.path.dirname(os.path.abspath(__file__))
DEMO = os.path.join(HERE, "..", "..", "demo", "method_size_cliff-jdk25-output.txt")
GREY = "#5c5f66"
RED = "#e03131"
LIMIT = 8000


def read_sizes(path):
    sizes = {}
    row = re.compile(r"^(\d+)\s+(\d+) bytes")
    with open(path) as f:
        for line in f:
            if m := row.match(line):
                sizes[int(m.group(1))] = int(m.group(2))
    return sizes


sizes = read_sizes(DEMO)
below = max(n for n in sizes if sizes[n] <= LIMIT)
above = min(n for n in sizes if sizes[n] > LIMIT)

r = Rough(960, 560, seed=171)
r.text(40, 44, "What whole-stage codegen does with your query", size=32)

# The pipeline: four stations, left to right.
BOXES = [
    (30, "your query", "a projection of\n%d date columns" % above),
    (265, "Java source", "one class per stage;\none method runs\nthe row loop"),
    (500, "bytecode", "Janino compiles\nthe source in\nmemory"),
    (735, "machine code", "the JIT compiles\nmethods of at most\n8000 bytes"),
]
W, TOP, H = 195, 110, 160
for i, (x, head, body) in enumerate(BOXES):
    r.rect(x, TOP, W, H, fill="blue" if i < 3 else "green")
    r.text(x + W / 2, TOP + 30, head, size=25, anchor="middle")
    r.text(x + W / 2, TOP + 100, body, size=19, anchor="middle")
    if i:
        r.arrow(x - 36, TOP + H / 2, x - 6, TOP + H / 2, width=2.0)

# The size of the row loop's method, against the limit.
GX, GW, SCALE = 265, 430, 10000.0
xl = GX + GW * LIMIT / SCALE
r.text(GX, 330, "the size of the row loop's method", size=21)
for i, n in enumerate((below, above)):
    y = 360 + i * 56
    w = GW * sizes[n] / SCALE
    r.rect(GX, y, w, 36, fill="green" if sizes[n] <= LIMIT else "red")
    r.text(GX - 14, y + 18, "%d columns" % n, size=20, anchor="end")
    r.text(GX + w + 12, y + 18, "%d bytes" % sizes[n], size=20)
r.line(xl, 346, xl, 470, color="#868e96", width=2.0, dash="7 6")
r.text(xl, 490, "8000", size=20, anchor="middle", color=GREY)

r.text(
    832,
    330,
    "%d columns: never\ncompiled; every row\nruns interpreted" % above,
    size=20,
    anchor="middle",
    color=RED,
)

finish(r, "fig17-what-spark-writes")
