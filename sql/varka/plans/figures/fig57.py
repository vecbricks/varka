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


"""Figure 57: how a method moves between the tiers - the transitions of JDK 25's tiered policy
with their conditions at the default thresholds (`CompilationPolicy::transition_from_none`,
`transition_from_limited_profile`, `transition_from_full_profile`, `is_trivial`, `is_old` and
the deoptimisation path in `compilationPolicy.cpp`). The interpreter's own route to C2, taken
when its profile already holds the tier-4 counts, is told in the text rather than drawn."""

from rough import Rough, finish

GREY = "#5c5f66"

r = Rough(1000, 740, seed=5701)
r.text(40, 44, "How a method moves between the tiers", size=32)
r.text(
    40,
    80,
    "JDK 25 defaults; the policy decides when a method's counters report in",
    size=18,
    color=GREY,
)

r.ellipse(150, 330, 95, 55, fill="grey", label="tier 0\ninterpreted", size=18)
r.ellipse(520, 190, 110, 55, fill="green", label="tier 3\nC1, full profiling", size=18)
r.ellipse(520, 470, 110, 55, fill="yellow", label="tier 2\nC1, counters only", size=18)
r.ellipse(870, 330, 95, 55, fill="blue", label="tier 4\nC2", size=18)
r.ellipse(870, 112, 95, 40, fill="white", label="tier 1\nC1, no profiling", size=17)
r.text(870, 172, "trivial methods go here and stay", size=14, anchor="middle", color=GREY)

# Up from the interpreter.
r.arrow(235, 300, 415, 215)
r.text(300, 300, "200 calls, or 100 and\n2,000 with back-edges", size=15, color=GREY)
r.arrow(235, 360, 415, 445)
r.text(
    300,
    360,
    "the same thresholds, while C2's\nqueue is longer than 5 x its threads",
    size=15,
    color=GREY,
)

# Between the C1 tiers, and up to C2.
r.arrow(520, 415, 520, 245)
r.text(535, 330, "queue back to 2 x the threads,\nor the method is old", size=15, color=GREY)
r.arrow(625, 215, 780, 300)
r.text(
    660, 200, "5,000 calls, or 600 and 15,000\nwith back-edges, in the profile", size=15, color=GREY
)
r.arrow(625, 445, 780, 360, dash="6 6")
r.text(640, 470, "nothing to profile, or the\ninterpreter already profiled it", size=15, color=GREY)

# The one arrow that goes backwards.
r.line(870, 385, 870, 600)
r.line(870, 600, 150, 600)
r.arrow(150, 600, 150, 385)
r.text(
    510,
    636,
    "deoptimisation: a guard failed; the method returns to the interpreter\n"
    "and its profile keeps the trap and a decompile count",
    size=15,
    anchor="middle",
    color=GREY,
)
r.note(40, 712, "reports: every 128 calls interpreted, 2,048 at tier 2, 1,024 at tier 3")

finish(r, "fig57-how-a-method-moves")
