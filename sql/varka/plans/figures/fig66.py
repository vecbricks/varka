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

"""Figure 66: one query, two answers - in a cluster with an x86 driver and Arm executors, Spark
folds the literal cos(0.1) on the driver while it plans the query and computes cos(x) on the
executors, so a filter comparing the two holds each host's answer. The driver's value and fdlibm's
are read from part E of results/VARKA-587-math-host-laptop.txt (the Ryzen laptop), the executors'
from results/VARKA-587-spark-arm.txt, drawn as owed until it lands."""

from mathhost import GREY, LAPTOP, SPARK_ARM, box, cpu, lane_cpu, owed, part, spark
from rough import NOTE, Rough, finish

RED, GREEN, VIOLET = "#e03131", "#2f9e44", "#7048e8"

x86, strict = spark(part(LAPTOP, "E"))[1]["cos(0.1)"]
arm = spark(part(SPARK_ARM, "E"))[1].get("cos(0.1)", (None,))[0]
ryzen, n2 = cpu(part(LAPTOP, "A")), lane_cpu("mathlane-aarch64-neoverse-n2.txt")

r = Rough(1000, 600, seed=6601)
r.text(40, 44, "One query, two answers", size=32)
r.text(40, 80, "an x86 driver in front of Arm executors, one query:", size=18, color=GREY)
box(r, 40, 106, 920, 46, "#1e1e1e", "SELECT x FROM t WHERE cos(x) = cos(0.1)", size=22)

r.arrow(250, 156, 250, 196)
r.arrow(750, 156, 750, 196)
r.text(262, 176, "plans it", size=16, color=GREY)
r.text(762, 176, "runs it", size=16, color=GREY)
box(
    r,
    40,
    200,
    420,
    84,
    RED,
    "the driver, x86 (here the %s)\nplans the query and folds the literal" % ryzen,
    size=19,
)
box(
    r,
    540,
    200,
    420,
    84,
    VIOLET,
    "the executors, Arm (%s)\ncompute cos(x) for every row" % n2,
    size=19,
)
r.arrow(250, 290, 250, 340, color=RED)
r.arrow(750, 290, 750, 340, color=VIOLET)
r.text(250, 366, "cos(0.1) =", size=18, anchor="middle", color=GREY)
r.text(750, 366, "cos(x) at the row x = 0.1 =", size=18, anchor="middle", color=GREY)
box(r, 110, 390, 280, 50, RED, x86, size=22)
if arm:
    box(r, 610, 390, 280, 50, VIOLET, arm, size=22)
    verdict = "the row is %s" % ("kept: the same bits here" if arm == x86 else "filtered out")
    r.text(500, 415, "=" if arm == x86 else "!=", size=30, anchor="middle")
    r.text(500, 482, verdict, size=20, anchor="middle", color=NOTE)
else:
    owed(r, 610, 390, 280, 50, "owed: Arm runner", size=18)
    r.text(500, 415, "?", size=30, anchor="middle")
    r.text(
        500,
        482,
        "the row x = 0.1 is kept only if the two answers agree",
        size=20,
        anchor="middle",
        color=NOTE,
    )
r.text(
    500,
    548,
    "fdlibm (StrictMath), the same on every host: %s" % strict,
    size=18,
    anchor="middle",
    color=GREEN,
)
finish(r, "fig66-one-query-two-answers")
