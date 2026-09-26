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
"""Figure 16: one method per stage, and the two limits on its size - a ruler of the stage
method's bytecode with three zones: compiled by the JIT below 8000 bytes, run by the bytecode
interpreter between 8000 and 65535, and no compile at all past 65535. The demo's projection is
marked on it at the two sizes either side of the first limit.

The two sizes are read from the demo's committed JDK 25 output when the script runs
(sql/varka/demo/method_size_cliff-jdk25-output.txt), so the figure cannot drift from it."""

import os
import re

from rough import Rough, finish

HERE = os.path.dirname(os.path.abspath(__file__))
DEMO = os.path.join(HERE, "..", "..", "demo", "method_size_cliff-jdk25-output.txt")
GREY = "#5c5f66"
LIMIT = 8000


def read_sizes(path):
    """{columns: method bytes} from the demo's table."""
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

r = Rough(960, 600, seed=161)
r.text(40, 44, "One method per stage, and two limits on its size", size=32)
r.text(
    40,
    86,
    "the bytecode of the method that runs every row of a whole-stage codegen stage",
    size=20,
    color=GREY,
)

# The ruler: not to scale, since 0 to 8000 is where the story is and 8000 to 65535 is not.
X0, X8K, X64K, X1 = 50, 440, 790, 920
TOP, BOT = 190, 320
r.rect(X0, TOP, X8K - X0, BOT - TOP, fill="green")
r.rect(X8K, TOP, X64K - X8K, BOT - TOP, fill="yellow")
r.rect(X64K, TOP, X1 - X64K, BOT - TOP, fill="red")
r.text((X0 + X8K) / 2, (TOP + BOT) / 2, "compiled by the JIT:\nfast", size=26, anchor="middle")
r.text(
    (X8K + X64K) / 2,
    (TOP + BOT) / 2,
    "never compiled: runs in\nthe bytecode interpreter,\ncorrect but 5 to 6x slower",
    size=23,
    anchor="middle",
)
r.text((X64K + X1) / 2, (TOP + BOT) / 2, "does not\ncompile at\nall", size=22, anchor="middle")

r.arrow(X0, BOT + 14, X1 + 20, BOT + 14)
for x, label, why in [
    (X0, "0", ""),
    (X8K, "8000 bytes", "HotSpot's limit:\nthe JIT skips\nanything longer"),
    (X64K, "65535 bytes", "the JVM's limit\non one method"),
]:
    r.line(x, BOT + 6, x, BOT + 24)
    r.text(x, BOT + 44, label, size=21, anchor="middle")
    if why:
        r.text(x, BOT + 100, why, size=19, anchor="middle", color=GREY)
r.text(X1 + 18, BOT + 44, "not to scale", size=17, anchor="end", color=GREY)

# The demo's projection either side of the first limit.
for n, x, lx in [(below, X8K - 40, X8K - 130), (above, X8K + 40, X8K + 130)]:
    r.text(lx, 136, "%d columns:\n%d bytes" % (n, sizes[n]), size=21, anchor="middle")
    r.arrow(lx, 160, x, TOP - 4, width=1.8)

r.note(
    50,
    500,
    "past 8000 bytes Spark logs one INFO line and runs the method anyway",
    size=22,
)
r.note(
    50,
    556,
    "set spark.sql.codegen.hugeMethodLimit=8000 and Spark falls back instead",
    size=22,
)

finish(r, "fig16-one-method-two-limits")
