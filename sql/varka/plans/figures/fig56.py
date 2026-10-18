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


"""Figure 56: two compilers, two queues - a method's road from the interpreter to C2, top to
bottom, with the two queues on it and the side roads: tier 2 while C2's queue is long, tier 1
for trivial methods, and deoptimisation back to the top. Drawn from JDK 25's
`compilationPolicy.cpp` (`transition_from_none`, `transition_from_limited_profile`,
`select_task`, `initialize`)."""

from rough import Rough, finish

GREY = "#5c5f66"

r = Rough(1000, 900, seed=5601)
r.text(40, 44, "Two compilers, two queues", size=32)
r.text(
    40,
    80,
    "a method's road from the interpreter to C2, and what each form of it records",
    size=18,
    color=GREY,
)


def box(x, y, w, h, fill, label, size=18):
    r.rect(x, y, w, h, fill=fill)
    r.text(x + w / 2, y + h / 2, label, size=size, anchor="middle")


# The main road, top to bottom.
box(320, 120, 360, 90, "grey", "tier 0: the interpreter\ncounts calls and loop back-edges")
r.arrow(500, 210, 500, 272)
r.text(515, 241, "200 calls, scaled up\nby C1's queue", size=15, color=GREY)
box(380, 272, 240, 56, "white", "C1 queue", size=20)
r.text(366, 300, "a third of the\ncompiler threads", size=15, anchor="end", color=GREY)
r.arrow(500, 328, 500, 390)
box(
    320,
    390,
    360,
    100,
    "green",
    "tier 3: C1 with full profiling\nreceiver types, branch counts and\n"
    "type checks go into the profile",
)
r.arrow(500, 490, 500, 552)
r.text(515, 521, "5,000 calls in its profile,\nscaled up by C2's queue", size=15, color=GREY)
box(380, 552, 240, 56, "white", "C2 queue", size=20)
r.text(366, 580, "two thirds of the\ncompiler threads", size=15, anchor="end", color=GREY)
r.arrow(500, 608, 500, 670)
box(
    320,
    670,
    360,
    100,
    "blue",
    "tier 4: C2\noptimised on the profile:\ninlining, devirtualised calls",
)

# The side road while C2 is behind, and the way back.
box(740, 390, 230, 100, "yellow", "tier 2: C1 with\ncounters only\nno profile", size=17)
r.arrow(680, 170, 855, 390, dash="6 6")
r.text(
    975, 250, "while C2's queue is longer\nthan 5 x its threads", size=15, anchor="end", color=GREY
)
r.arrow(740, 465, 680, 465, dash="6 6")
r.text(
    855,
    520,
    "back to tier 3 once the queue\nis 2 x the threads or shorter",
    size=15,
    anchor="middle",
    color=GREY,
)

# Trivial methods.
box(
    40,
    390,
    230,
    100,
    "white",
    "tier 1: C1 without profiling\nfor trivial methods:\naccessors, constant getters",
    size=16,
)
r.arrow(350, 210, 155, 390)
r.text(190, 300, "nothing to profile", size=15, anchor="middle", color=GREY)

# The one arrow that goes backwards.
r.line(320, 720, 20, 720)
r.line(20, 720, 20, 150)
r.arrow(20, 150, 320, 150)
r.text(
    500,
    815,
    "deoptimisation: a guard failed; the method is interpreted again,\n"
    "and its profile keeps the trap count and a decompile count",
    size=15,
    anchor="middle",
    color=GREY,
)
r.note(
    40,
    872,
    "both queues: the method with the highest event rate first; an entry idle 50 ms is dropped",
)

finish(r, "fig56-two-compilers-two-queues")
