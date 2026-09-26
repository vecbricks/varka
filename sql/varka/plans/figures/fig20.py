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
"""Figure 20: the second cliff - outside a whole-stage codegen stage a large CASE WHEN is split
into many small methods, all compiled, but the calls to them stay in one method, and with
enough branches that method passes 8000 bytes itself and runs interpreted. Two panels, at the
last two rungs of the ladder, each with the calling method's size and the time per row.

The sizes and times are read from the committed results file when the script runs
(sql/core/benchmarks/CaseWhenCodegenBenchmark-jdk25-results.txt), so the figure cannot drift
from it (PLAN_TASK_210.md 9.3)."""

import os
import re

from rough import Rough, finish

HERE = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.join(HERE, "..", "..", "..", "core", "benchmarks")
RESULTS = os.path.join(BENCH, "CaseWhenCodegenBenchmark-jdk25-results.txt")
OFF = "whole-stage codegen off"
GREY = "#5c5f66"
RED = "#e03131"


def read_ladder(path):
    """{branches: (outside-a-stage method bytes, ns per row outside a stage)}."""
    out, rung, size = {}, None, None
    head = re.compile(r"^CASE WHEN of (\d+) branches over ")
    outside = re.compile(r"^largest method of the projection outside a stage: (\d+) bytes")
    row = re.compile(r"^(.*?)\s+\d+\s+\d+\s+\d+\s+[\d.]+\s+([\d.]+)\s+[\d.]+X\s*$")
    with open(path) as f:
        for line in f:
            line = line.rstrip()
            if m := head.match(line):
                rung, size = int(m.group(1)), None
            elif m := outside.match(line):
                size = int(m.group(1))
            elif (m := row.match(line)) and rung is not None and m.group(1).strip() == OFF:
                out[rung] = (size, float(m.group(2)))
    return out


ladder = read_ladder(RESULTS)
small, big = sorted(ladder)[-2:]

r = Rough(960, 620, seed=201)
r.text(40, 44, "The second cliff: the branches are split, the calls are not", size=30)
r.text(
    40,
    84,
    "a CASE WHEN outside a whole-stage codegen stage, as Spark compiles it",
    size=20,
    color=GREY,
)


def panel(x0, branches):
    size, ns = ladder[branches]
    past = size > 8000
    r.text(x0 + 200, 140, "%d branches" % branches, size=26, anchor="middle")
    r.rect(x0 + 60, 172, 280, 96, fill="red" if past else "green")
    r.text(
        x0 + 200,
        220,
        "the method that calls\nthem: %d bytes" % size,
        size=22,
        anchor="middle",
    )
    # The split methods: a row of small boxes, all compiled.
    for i in range(6):
        bx = x0 + 12 + i * 64
        r.rect(bx, 350, 50, 44, fill="green")
        r.line(x0 + 200, 268, bx + 25, 350, color="#868e96", width=1.4)
    r.text(x0 + 400, 372, "...", size=26)
    r.text(
        x0 + 200,
        436,
        "the branches, split into\nsmall methods: all compiled",
        size=20,
        anchor="middle",
    )
    r.text(
        x0 + 200,
        510,
        (
            "past 8000 bytes: every call comes\nfrom the interpreter"
            if past
            else "under 8000 bytes: compiled"
        ),
        size=20,
        anchor="middle",
        color=RED if past else GREY,
    )
    r.text(x0 + 200, 566, "%s ns a row" % "{:,}".format(round(ns)), size=22, anchor="middle")


panel(20, small)
panel(500, big)
r.line(480, 130, 480, 590, color="#ced4da", width=1.4, dash="5 6")
r.note(
    40,
    604,
    "%.1fx the branches, %.0fx the time per row"
    % (big / float(small), ladder[big][1] / ladder[small][1]),
    size=20,
)

finish(r, "fig20-the-second-cliff")
