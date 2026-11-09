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

"""Figure 65: one ulp - Spark's `SELECT cos(0.1)` on the laptop and fdlibm's answer, two
neighbouring doubles, with the exact cosine of the double 0.1 between them. The two answers are
read from part E of results/VARKA-587-math-host-laptop.txt; the exact value is the Taylor series
summed in 60-digit decimal arithmetic here, so the figure checks its own claim of which is
nearer."""

import math
from decimal import Decimal, getcontext

from mathhost import LAPTOP, part, spark
from rough import Rough, finish

GREY = "#5c5f66"
RED, GREEN, INK = "#e03131", "#2f9e44", "#1e1e1e"

_, literals = spark(part(LAPTOP, "E"))
answer, strict = literals["cos(0.1)"]
a, s = float(answer), float(strict)
assert a != s and math.nextafter(s, 2.0) == a, (answer, strict)

getcontext().prec = 60
x = Decimal(0.1)
exact, term, k = Decimal(0), Decimal(1), 0
while abs(term) > Decimal(10) ** -58:
    exact += term
    term = -term * x * x / ((2 * k + 1) * (2 * k + 2))
    k += 1
ulp = Decimal(a) - Decimal(s)
from_s = float((exact - Decimal(s)) / ulp)
from_a = float((Decimal(a) - exact) / ulp)

r = Rough(1000, 470, seed=6501)
r.text(40, 44, "One ulp", size=32)
r.text(40, 80, "SELECT cos(0.1): the two answers are neighbouring doubles", size=18, color=GREY)

X0, X1, Y = 200, 800, 250
r.arrow(80, Y, 920, Y)
for xx, val, who, color, up in (
    (X0, strict, "fdlibm (StrictMath)", GREEN, True),
    (X1, answer, "Spark on this x86 laptop, JDK 25", RED, False),
):
    r.line(xx, Y - 14, xx, Y + 14, color=color, width=2.4)
    dy = -1 if up else 1
    r.text(xx, Y + dy * 46, val, size=22, anchor="middle", color=color)
    r.text(xx, Y + dy * 78, who, size=17, anchor="middle", color=color)
xe = X0 + (X1 - X0) * from_s
r.line(xe, Y - 8, xe, Y + 8, color=INK, width=1.6, dash="3 3")
r.text(xe, Y - 100, "the exact cosine", size=17, anchor="middle")
r.arrow(xe, Y - 86, xe, Y - 12)
r.text((X0 + xe) / 2, Y + 30, "%.3f ulp" % from_s, size=15, anchor="middle", color=GREY)
r.text((xe + X1) / 2, Y - 30, "%.3f ulp" % from_a, size=15, anchor="middle", color=GREY)
r.note(
    80,
    400,
    "1 ulp here is 2^-53, about 1.1e-16: the 16th significant digit. Both answers\n"
    "are within Java's bound for Math.cos, and here the x86 one is the nearer.",
)
finish(r, "fig65-one-ulp")
