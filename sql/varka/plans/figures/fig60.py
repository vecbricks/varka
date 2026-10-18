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


"""Figure 60: a compile queue is a priority queue - a sketch of C1's queue on a four-core host
(one compiler thread) as `CompilationPolicy::select_task` sees it: every waiting method carries
an event rate, the thread takes the highest rate next whatever the order of arrival, an entry
whose method has made no calls for 50 ms is dropped as stale, and the queue's length scales the
thresholds of every method still interpreted (`threshold_scale`)."""

from rough import PALETTE, Rough, finish

GREY = "#5c5f66"
HOT = PALETTE["red"]
COOL = PALETTE["green"]

r = Rough(1000, 560, seed=6001)
r.text(40, 44, "A compile queue is a priority queue", size=32)
r.text(
    40,
    80,
    "C1's queue on a four-core host, one compiler thread; a sketch of what select_task sees",
    size=18,
    color=GREY,
)

# The queue: entries in arrival order, left to right, each with its rate.
entries = [
    ("arrived 1st", "rate 0.4", "white"),
    ("2nd", "rate 23", "green"),
    ("3rd", "rate 0", "red"),
    ("4th", "rate 2.1", "white"),
    ("5th", "rate 7.5", "white"),
    ("6th", "rate 0.9", "white"),
]
X, Y, W, H, GAP = 80, 190, 120, 90, 16
for i, (when, rate, fill) in enumerate(entries):
    x = X + i * (W + GAP)
    r.rect(x, Y, W, H, fill=fill)
    r.text(x + W / 2, Y + 28, when, size=15, anchor="middle", color=GREY)
    r.text(x + W / 2, Y + 60, rate, size=19, anchor="middle")
r.text(X, Y - 18, "in arrival order, left to right", size=15, color=GREY)
r.text(
    X + 5 * (W + GAP) + W,
    Y - 18,
    "rate: calls and back-edges per ms",
    size=15,
    anchor="end",
    color=GREY,
)

# The thread takes the highest rate, not the oldest.
taken = X + 1 * (W + GAP) + W / 2
r.rect(X + 2 * (W + GAP) + 20, 400, 380, 70, fill="white")
r.text(
    X + 2 * (W + GAP) + 210, 435, "the C1 thread\ncompiles the 2nd first", size=17, anchor="middle"
)
r.arrow(taken, Y + H, X + 2 * (W + GAP) + 60, 400, color=COOL, width=2.0)
r.text(taken + 12, Y + H + 60, "highest rate", size=15, color=COOL)

# The stale entry is dropped.
stale = X + 2 * (W + GAP) + W / 2
r.arrow(stale, Y, stale, Y - 60, color=HOT, width=2.0)
r.text(stale + 12, Y - 70, "no calls for 50 ms:\ndropped as stale", size=15, color=HOT)

# The queue's length feeds back on everyone still interpreted.
r.note(
    40,
    520,
    "six waiting, one thread: every interpreted method now needs 200 x (6 / 5 + 1) = 440 calls",
)

finish(r, "fig60-a-compile-queue-is-a-priority-queue")
