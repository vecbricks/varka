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


"""Figure 58: C2's queue chooses the tier - a sketch of the queue's length over a JVM's first
seconds on a four-core host against the two lines of JDK 25's policy: above Tier3DelayOn (5)
times C2's threads a method due for tier 3 is built at tier 2 instead, and a tier-2 method is
sent back to tier 3 only once the queue is at or below Tier3DelayOff (2) times the threads.
The curve is a sketch; the lines, the thread counts and the thresholds are the JVM's."""

from rough import PALETTE, Rough, finish

GREY = "#5c5f66"
QUEUE = PALETTE["blue"]
HOT = PALETTE["red"]
COOL = PALETTE["green"]

r = Rough(1000, 620, seed=5801)
r.text(40, 44, "C2's queue chooses the tier", size=32)
r.text(
    40,
    80,
    "a sketch of a JVM's first seconds on four cores; the two lines are JDK 25's defaults",
    size=18,
    color=GREY,
)

X0, X1 = 110, 940
Y0 = 500
PER_ENTRY = 23


def py(entries):
    return Y0 - PER_ENTRY * entries


# Axes.
r.arrow(X0, Y0, X1 + 10, Y0)
r.arrow(X0, Y0, X0, py(16) - 20)
for n in (0, 4, 8, 12, 16):
    r.line(X0 - 6, py(n), X0 + 6, py(n))
    r.text(X0 - 14, py(n), str(n), size=15, anchor="end", color=GREY)
r.text(60, py(8), "methods waiting for C2", size=16, anchor="middle", color=GREY, rotate=-90)
r.text(
    (X0 + X1) / 2, Y0 + 36, "time: the first seconds of a JVM", size=16, anchor="middle", color=GREY
)

# The two lines of the policy, for 2 C2 threads.
ON, OFF = 10, 4
r.line(X0, py(ON), X1, py(ON), color=HOT, width=1.4, dash="8 6")
r.text(
    X1,
    py(ON) - 12,
    "10: above this, a method due for tier 3 is built at tier 2",
    size=15,
    anchor="end",
    color=GREY,
)
r.line(X0, py(OFF), X1, py(OFF), color=COOL, width=1.4, dash="8 6")
r.text(
    X0 + 90,
    py(OFF) + 24,
    "4: at or below this, a tier-2 method is sent back to tier 3",
    size=15,
    color=GREY,
)

# The queue: startup, a lull, a warm-up's burst, and the drain.
pts = [
    (X0, py(1)),
    (180, py(7)),
    (250, py(13)),
    (330, py(10)),
    (400, py(6)),
    (470, py(8)),
    (520, py(12)),
    (600, py(9)),
    (700, py(3)),
    (800, py(2.5)),
    (X1, py(2)),
]
r.curve(pts, color=QUEUE, width=2.4, monotone=True)

# Methods that cross their first threshold while the queue is on either side of the line.
for x, n in ((235, 12.5), (520, 12)):
    r.ellipse(x, py(n) - 22, 7, 7, fill="red", color=HOT)
    r.text(x, py(n) - 48, "tier 2", size=14, anchor="middle", color=HOT)
for x, n in ((400, 6), (760, 2.8)):
    r.ellipse(x, py(n) - 22, 7, 7, fill="green", color=COOL)
    r.text(x, py(n) - 48, "tier 3", size=14, anchor="middle", color=COOL)

r.note(560, 140, "a warm-up's burst")
r.arrow(600, 150, 530, py(12) - 30, color="#6741d9")
r.text(
    X1,
    py(14),
    "twenty-four cores: 8 C2 threads,\nthe lines at 40 and 16",
    size=15,
    anchor="end",
    color=GREY,
)

finish(r, "fig58-c2s-queue-chooses-the-tier")
