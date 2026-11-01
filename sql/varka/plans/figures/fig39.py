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

"""Figure 39: what a click costs - the mean milliseconds of a short query over a cached table
with the same value each time or a new one, each bar split into the part spent in Janino and
the rest, from the "mean of 40 queries" table of the stock Spark 4.2.0 run
(sql/varka/demo/compiles/times-jdk25-output.txt). Figure 61 draws the same bars for this
repository's benchmark of an integer passed to the class (`draw` below serves both), a separate
measurement on another build and CPU, so the two are separate figures."""

import os
import re

from rough import Rough, finish

HERE = os.path.dirname(os.path.abspath(__file__))
STOCK = os.path.join(HERE, "..", "..", "demo", "compiles", "times-jdk25-output.txt")
FORK = os.path.join(
    HERE, "..", "..", "..", "core", "benchmarks", "CodegenRecompileBenchmark-jdk25-results.txt"
)
GREY = "#5c5f66"
JANINO, REST = "#e8590c", "#74c0fc"


def means(path):
    """[[(label, classes, ms, janino ms)]] per "mean of 40 queries" block, in file order."""
    head = re.compile(r"^mean of \d+ queries")
    row = re.compile(r"^(.*?)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s*$")
    blocks, current = [], None
    with open(path) as f:
        for line in f:
            line = line.rstrip()
            if head.match(line):
                current = []
                blocks.append(current)
            elif current is not None and (m := row.match(line)):
                current.append(
                    (m.group(1).strip(), float(m.group(2)), float(m.group(3)), float(m.group(4)))
                )
            else:
                current = None
    return blocks


def cpu(path):
    """The CPU a results file names: run.sh's "cpu:" header or the JMH-style machine line."""
    with open(path) as f:
        lines = f.read().splitlines()
    for line in lines:
        if line.startswith("cpu:"):
            return line.split(":", 1)[1].strip()
    return next(line.strip() for line in lines if re.match(r"^(AMD|Intel|Apple)", line))


def short(name):
    """'AMD EPYC 9V45 96-Core Processor' -> 'AMD EPYC 9V45'."""
    return re.sub(r"\s+\d+-Core Processor$", "", name)


def shared_top():
    """The axis end both figures use, so their bars are drawn to one scale: the next ten ms past
    the slowest row of either run."""
    rows = means(STOCK)[0] + means(FORK)[1]
    return 10.0 * (int(max(ms for _, _, ms, _ in rows) / 10) + 1)


def draw(name, seed, title, subtitle, where, rows):
    """One figure of one block of bars on the shared axis, with the legend below."""
    r = Rough(1000, 160 + 40 * len(rows) + 110, seed=seed)
    r.text(40, 44, title, size=32)
    r.text(
        40,
        84,
        subtitle,
        size=17,
        color=GREY,
    )
    r.text(40, 120, where, size=16, color=GREY)
    x0, x1, label = 360, 900, 340
    top = shared_top()

    def px(ms):
        return x0 + (x1 - x0) * ms / top

    y = 140
    for name_, classes, ms, janino in rows:
        r.text(label, y + 16, name_, size=18, anchor="end")
        if janino > 0:
            r.rect(x0, y, px(janino) - x0, 30, fill=JANINO, solid=True)
        r.rect(px(janino), y, px(ms) - px(janino), 30, fill=REST, solid=True)
        r.text(px(ms) + 10, y + 16, "%s ms, %d classes" % (("%.2f" % ms), classes), size=17)
        y += 40
    r.arrow(x0, y + 4, x1 + 20, y + 4)
    for ms in range(0, int(top) + 1, 10):
        r.line(px(ms), y - 2, px(ms), y + 10)
        r.text(px(ms), y + 28, "%d" % ms, size=16, anchor="middle", color=GREY)
    y += 44
    r.rect(x0, y + 8, 22, 18, fill=JANINO, solid=True)
    r.text(x0 + 30, y + 17, "Janino compiling", size=17)
    r.rect(x0 + 200, y + 8, 22, 18, fill=REST, solid=True)
    r.text(x0 + 230, y + 17, "everything else", size=17)
    finish(r, name)


if __name__ == "__main__":
    stock = means(STOCK)
    assert len(stock) == 1, len(stock)
    draw(
        "fig39-what-a-click-costs",
        3901,
        "What a click costs",
        "SELECT count(*) over 100,000 cached rows, mean of 40 queries, ms; local mode, JDK 25, "
        "GitHub runners",
        "stock Spark 4.2.0, %s" % short(cpu(STOCK)),
        stock[0],
    )
