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
"""Figure 28: where the operators of Spark's own query suites run - per suite, the share in a
whole-stage codegen stage, outside one by design (a columnar scan, read through a conversion),
outside one for a reason (every such reason is silent), and outside because a setting switched
whole-stage codegen off.

Every value is read from the census's committed file when the script runs
(sql/core/benchmarks/VarkaCodegenGiveUps-jdk25-results.txt, PLAN_TASK_233.md 9.1)."""

import os
import re

from rough import Rough, finish

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.path.join(
    HERE, "..", "..", "..", "core", "benchmarks", "VarkaCodegenGiveUps-jdk25-results.txt"
)
SUITES = [
    ("TPC-DS", "TPC-DS"),
    ("TPC-H", "TPC-H"),
    ("SSB", "SSB"),
    ("SQL golden files", "the SQL golden files"),
]
SETTING = "whole-stage codegen switched off by a setting"
COLUMNAR = "a columnar scan, read inside the stage"


def read_census(path):
    """{suite: (operators, in a stage, {reason: operators}, plans)} for each suite's block."""
    suites, cur = {}, None
    head = re.compile(r"^(.+?) \(\w+\): (\d+) plans, (\d+) operators")
    inside = re.compile(r"^in a stage: (\d+) ")
    reason = re.compile(r"^(.+?)\s{2,}(\d+)\s+[\d.]+%\s+\d+\s")
    with open(path) as f:
        for line in f:
            line = line.rstrip()
            if m := head.match(line):
                cur = m.group(1)
                suites[cur] = [int(m.group(3)), 0, {}, int(m.group(2))]
            elif line.startswith("all:"):
                cur = None
            elif cur and (m := inside.match(line)):
                suites[cur][1] = int(m.group(1))
            elif cur and (m := reason.match(line)) and not line.startswith("reason"):
                suites[cur][2][m.group(1).strip()] = int(m.group(2))
    return suites


census = read_census(RESULTS)

r = Rough(980, 590, seed=2801, fill_style="tint-hatch")
r.text(40, 40, "Where the operators of Spark's own query suites run", size=30)
r.text(40, 72, "share of every operator of every final plan, per suite", size=20, color="#5c5f66")

X0, X1 = 250, 900
BAR_H, GAP, Y_TOP = 46, 30, 140
PARTS = [
    ("in a stage", "blue"),
    ("a columnar scan, by design", "grey"),
    ("left out for a reason, silently", "red"),
    ("whole-stage codegen switched off", "white"),
]

for i, (key, label) in enumerate(SUITES):
    ops, inside, reasons, _ = census[key]
    columnar = reasons.get(COLUMNAR, 0)
    setting = reasons.get(SETTING, 0)
    silent = ops - inside - columnar - setting
    shares = [inside / ops, columnar / ops, silent / ops, setting / ops]
    y = Y_TOP + i * (BAR_H + GAP)
    r.text(X0 - 16, y + BAR_H / 2, label, size=21, anchor="end")
    x = X0
    for share, (_, fill) in zip(shares, PARTS):
        w = (X1 - X0) * share
        if w > 0.5:
            r.rect(x, y, w, BAR_H, fill=fill)
        x += w
    # The silent share is the figure's point; it is written beside its bar.
    share_text = "%.1f%%" % (100 * silent / ops) if silent else "none"
    r.text(X1 + 12, y + BAR_H / 2, share_text, size=21, color="#c92a2a")

r.text(X1 + 12, Y_TOP - 22, "silent", size=18, color="#c92a2a")
for pct in (0, 25, 50, 75, 100):
    x = X0 + (X1 - X0) * pct / 100.0
    yb = Y_TOP + len(SUITES) * (BAR_H + GAP) - GAP + 8
    r.line(x, yb, x, yb + 8)
    r.text(x, yb + 24, "%d%%" % pct, size=18, anchor="middle", color="#5c5f66")

# The legend, one swatch a part, under the axis.
ly = Y_TOP + len(SUITES) * (BAR_H + GAP) + 44
for k, (label, fill) in enumerate(PARTS):
    lx, y = X0 + (k % 2) * 330, ly + (k // 2) * 30
    r.rect(lx, y, 26, 20, fill=fill)
    r.text(lx + 36, y + 10, label, size=19)

plans = sum(census[k][3] for k, _ in SUITES)
operators = sum(census[k][0] for k, _ in SUITES)
r.text(
    40,
    572,
    "{:,} plans and {:,} operators in all; the golden files run each query under several"
    " settings, some with whole-stage codegen off".format(plans, operators),
    size=17,
    color="#868e96",
)

finish(r, "fig28-where-operators-run")
