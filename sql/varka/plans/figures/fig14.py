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
"""Figure 14: a cached table at and past spark.sql.codegen.maxFields - one column summed, and
every column read, over a table of 100 and of 101 int columns. The 101-column table is read row
by row whatever the query touches, which costs nothing on the one-column sum and four and a
half times on the whole-table read.

Every value is read from the committed results file when the script runs
(sql/core/benchmarks/CachedTableWidthBenchmark-jdk25-results.txt), so the figure cannot drift
from it (PLAN_TASK_210.md 9.2)."""

import os
import re

from rough import Rough, finish

HERE = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.join(HERE, "..", "..", "..", "core", "benchmarks")
RESULTS = os.path.join(BENCH, "CachedTableWidthBenchmark-jdk25-results.txt")
PROVENANCE = os.path.join(BENCH, "CachedTableWidthBenchmark-jdk25-provenance.txt")


def read_results(path):
    """[(query, [(case, ns per row, scan)])] in file order."""
    groups, scans, current = [], [], None
    header = re.compile(r"^(.*?):\s+Best Time\(ms\)")
    row = re.compile(r"^(.*?)\s+\d+\s+\d+\s+\d+\s+[\d.]+\s+([\d.]+)\s+[\d.]+X\s*$")
    scan = re.compile(r"^(.*?): the scan is (columnar|row-based)$")
    with open(path) as f:
        for line in f:
            line = line.rstrip()
            if m := scan.match(line):
                scans.append(m.group(2))
            elif m := header.match(line):
                current = (m.group(1), [])
                groups.append(current)
            elif (m := row.match(line)) and current is not None:
                current[1].append((m.group(1).strip(), float(m.group(2)), scans.pop(0)))
    return groups


def read_provenance(path):
    fields = {}
    with open(path) as f:
        for line in f:
            if ":" in line and not line.startswith(" "):
                key, value = line.split(":", 1)
                fields[key.strip()] = value.strip()
    return fields


groups = read_results(RESULTS)
prov = read_provenance(PROVENANCE)
cpu = prov["cpu"].split(",")[0]
jdk = prov["jdk"].replace("OpenJDK 64-Bit Server VM ", "JDK ")

r = Rough(900, 620, seed=137)
r.text(40, 40, "a cached table of 100 and of 101 columns, nanoseconds a row", size=24)
r.text(40, 72, "two million rows; %s, %s" % (cpu, jdk), size=17, color="#5c5f66")

scale = 560.0 / max(ns for _, rows in groups for _, ns, _ in rows)
y = 110
for query, rows in groups:
    r.text(40, y, query, size=21)
    y += 24
    for case, ns, scan in rows:
        r.text(40, y + 17, case, size=16)
        w = max(6.0, ns * scale)
        r.rect(300, y, w, 34, fill="#e03131" if scan == "row-based" else "green")
        r.text(300 + w + 12, y + 17, "%.1f, %s" % (ns, scan), size=17)
        y += 46
    y += 30

r.note(
    300,
    560,
    "the count is of the whole cached schema, not of what the query reads;\n"
    "the batches are turned back into rows before an aggregate anyway",
    size=18,
)

finish(r, "fig14-the-cached-table-width")
