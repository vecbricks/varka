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

"""Figure 22: the method sizes - every method Spark generates for its golden-file suite and its
TPC-DS, TPC-H and SSB queries, counted by bytecode size in bins that double, on log axes,
against HotSpot's 8000-byte limit and the class file's 65535.

Every count is read from the committed census when the script runs
(sql/core/benchmarks/VarkaCodegenMethodSizes-jdk25-results.txt, PLAN_TASK_181.md 11), so the
figure cannot drift from it."""

import math
import os
import re

from rough import Rough, finish

HERE = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.join(HERE, "..", "..", "..", "core", "benchmarks")
RESULTS = os.path.join(BENCH, "VarkaCodegenMethodSizes-jdk25-results.txt")
GREY = "#5c5f66"
RED = "#e03131"
BLUE = "#1971c2"


def read_census(path):
    """{size in bytes: methods}, and the summary line of the families together."""
    sizes, summary, listing, family = {}, {}, False, None
    with open(path) as f:
        for line in f:
            line = line.rstrip()
            if line.startswith("Every size with its count"):
                listing = True
            elif listing and re.match(r"^\d+ \d+$", line):
                b, n = line.split()
                sizes[int(b)] = int(n)
            elif m := re.match(r"^(\S.*?): (\d+) methods$", line):
                family = m.group(1)
            elif family == "all" and line.startswith("median"):
                summary = dict(
                    (k, int(v)) for k, v in re.findall(r"(median|p99|largest) (\d+)", line)
                )
    return sizes, summary


sizes, summary = read_census(RESULTS)
total = sum(sizes.values())
# Bins that double: [1, 2), [2, 4), ... [32768, 65536).
bins = [0] * 16
for b, n in sizes.items():
    bins[min(15, int(math.log2(b)))] += n
past = sum(n for b, n in sizes.items() if b > 8000)

r = Rough(960, 640, seed=2201)
r.text(40, 44, "%s methods Spark generates, by size" % "{:,}".format(total), size=32)
r.text(
    40,
    84,
    "its golden-file suite and its TPC-DS, TPC-H and SSB queries; bytecode bytes, log scales",
    size=19,
    color=GREY,
)

X0, X1, Y0, Y1 = 120, 900, 530, 150
C0, C1 = 0.5, 1000000.0


def px(b):
    return X0 + (X1 - X0) * math.log2(b) / 16.0


def py(c):
    return Y0 - (Y0 - Y1) * (math.log10(c) - math.log10(C0)) / (math.log10(C1) - math.log10(C0))


r.line(X0, Y0, X1 + 10, Y0)
r.line(X0, Y0, X0, Y1 - 20)
for c, label in [
    (1, "1"),
    (10, "10"),
    (100, "100"),
    (1000, "1,000"),
    (10000, "10,000"),
    (100000, "100,000"),
]:
    r.line(X0 - 8, py(c), X0, py(c))
    r.text(X0 - 14, py(c), label, size=17, anchor="end", color=GREY)
r.text(X0 - 14, Y1 - 34, "methods", size=17, anchor="end", color=GREY)
for b, label in [(1, "1"), (10, "10"), (100, "100"), (1000, "1,000"), (10000, "10,000")]:
    r.line(px(b), Y0, px(b), Y0 + 8)
    r.text(px(b), Y0 + 24, label, size=17, anchor="middle", color=GREY)
r.text((X0 + X1) / 2, Y0 + 58, "bytes of bytecode in the method", size=20, anchor="middle")

for i, n in enumerate(bins):
    if n:
        x0, x1 = px(2**i), px(2 ** (i + 1))
        r.rect(x0 + 2, py(n), x1 - x0 - 4, Y0 - py(n), fill="blue" if i < 13 else "red")

for b, label, color in [
    (8000, "8000: never JIT-compiled", RED),
    (65535, "65535: does not\ncompile at all", GREY),
]:
    r.line(px(b), Y0, px(b), Y1 - 10, color=color, width=1.8, dash="8 6")
    r.text(px(b) - 8, Y1 + 8, label, size=17, anchor="end", color=color)

r.note(
    px(2),
    py(400000),
    "half are under %d bytes,\n99 in 100 under %d" % (summary["median"], summary["p99"]),
    size=19,
)
# The file counts sizes and names no method; the one past 8000 is TPC-DS's (its family's
# summary line says so) and the census's log named it (PLAN_TASK_181.md 11).
r.arrow(px(3000), py(2000), px(12450) - 4, py(past) - 18, color="#6741d9", width=1.8)
r.note(
    px(1100),
    py(8000),
    "{} past 8000: modified-q3's\naggregate, {:,} bytes".format(past, summary["largest"]),
    size=18,
)

finish(r, "fig22-the-method-sizes")
