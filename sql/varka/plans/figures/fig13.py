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
"""Figure 13: the Spark tracker's tickets carrying Janino's "grows beyond 64 KB" message, by
year of filing. They peak while Spark still failed loudly at 64 KB and fall away after the
split machinery of 2.3.0; the 8000-byte step leaves no such trail, because it does not fail.

The counts are the search of PLAN_TASK_210.md 7.1, read from the tracker on 25 September 2026
with `project = SPARK AND text ~ "grows beyond 64 KB"`; they are a record of that day and are
kept here with their date rather than re-queried, so the figure matches the post."""

from rough import Rough, finish

QUERY_DATE = "25 September 2026"
COUNTS = [
    (2015, 1),
    (2016, 18),
    (2017, 10),
    (2018, 6),
    (2019, 3),
    (2020, 2),
    (2021, 1),
    (2022, 1),
    (2023, 0),
    (2024, 0),
    (2025, 1),
    (2026, 1),
]
TOTAL = sum(c for _, c in COUNTS)

r = Rough(900, 560, seed=131)
r.text(40, 40, 'Spark tickets that quote "grows beyond 64 KB", by year filed', size=24)
r.text(
    40,
    72,
    "%d tickets; the tracker's own search, %s" % (TOTAL, QUERY_DATE),
    size=17,
    color="#5c5f66",
)

X0, Y0, W, GAP, H = 90, 440, 52, 14, 300
top = max(c for _, c in COUNTS)


def py(c):
    return Y0 - H * c / float(top)


r.line(X0 - 10, Y0, X0 + len(COUNTS) * (W + GAP), Y0)
for i, (year, c) in enumerate(COUNTS):
    x = X0 + i * (W + GAP)
    if c:
        r.rect(x, py(c), W, Y0 - py(c), fill="#ffd43b" if year < 2018 else "grey")
        r.text(x + W / 2, py(c) - 12, str(c), size=17, anchor="middle")
    r.text(x + W / 2, Y0 + 22, str(year), size=15, anchor="middle", color="#5c5f66")

# 2.3.0 shipped in February 2018 with the split machinery of the 2017 fix wave.
xr = X0 + 3 * (W + GAP) - GAP / 2
r.line(xr, Y0, xr, py(top) - 20, color="#868e96", dash="6 6")
r.text(xr + 8, py(top) - 14, "Spark 2.3.0 ships:\nthe 64 KB fix wave", size=16, color="#5c5f66")
r.note(
    X0 + 5 * (W + GAP),
    py(top) + 60,
    "the step at 8000 bytes leaves no such trail:\nit does not fail, it answers slowly",
    size=18,
)
r.text(
    40,
    520,
    "four of the %d are still open, among them a CASE WHEN report from 2020" % TOTAL,
    size=15,
    color="#868e96",
)

finish(r, "fig13-the-tracker-by-year")
