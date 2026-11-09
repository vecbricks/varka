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

"""The readers figures 62 to 65 share: VARKA-587's results files, as MathHostProbe,
SparkMathHostBenchmark and MathLaneProbe print them. A runner's file that has not landed yet
reads as None, and the figure draws its panel as owed."""

import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.path.join(HERE, "..", "m8", "results")
CANARY = os.path.join(HERE, "..", "..", "..", "..", "dev", "varka_canary")
LAPTOP = os.path.join(RESULTS, "VARKA-587-math-host-laptop.txt")
ARM = os.path.join(RESULTS, "VARKA-587-math-host-arm.txt")


def part(path, letter):
    """The lines of part `letter` of a results file ("==== A. ..." to the next "===="), or the
    whole file when it has no parts, as a runner's single run does."""
    if not os.path.exists(path):
        return None
    lines = open(path).read().splitlines()
    heads = [i for i, ln in enumerate(lines) if ln.startswith("==== ")]
    if not heads:
        return lines
    start = [i for i in heads if lines[i].startswith("==== %s." % letter)][0]
    end = min([i for i in heads if i > start] + [len(lines)])
    return lines[start + 1 : end]


def probe(lines):
    """Section 1 of a MathHostProbe run: {fn: {field: value}}, with "spark" the class and names."""
    out = {}
    for ln in lines or []:
        m = re.match(r"^(\w+)\s+spark=(\w+): (.*?)\s+differ=", ln)
        if m:
            fields = dict(re.findall(r"(\w+\+?)=(\d+)", ln[ln.index("differ=") :]))
            fields = {k: int(v) for k, v in fields.items()}
            fields["class"], fields["names"] = m.group(2), m.group(3).split(", ")
            out[m.group(1)] = fields
    return out


def inputs(lines):
    for ln in lines:
        m = re.match(r"^inputs: (\d+) per function", ln)
        if m:
            return int(m.group(1))
    raise ValueError("no inputs line")


def children(lines):
    """Section 4 of a MathHostProbe run: [(name, equal, total)]."""
    out = []
    for ln in lines:
        m = re.match(r"^(.+?)\s{2,}-.*equal to .* for (\d+) of (\d+)", ln)
        if m:
            out.append((m.group(1), int(m.group(2)), int(m.group(3))))
    return out


def spark(lines):
    """SparkMathHostBenchmark's comparison: {mode: {sql: (differ, maxUlp, digest)}} and the
    literal lines {sql: (answer, strict)}."""
    modes, literals, mode = {}, {}, None
    for ln in lines:
        m = re.match(r"^(whole-stage|generated|interpreted):$", ln)
        if m:
            mode = m.group(1)
            modes[mode] = {}
            continue
        m = re.match(
            r"^\s+(\S+(?:, y\))?)\s+rows differing from StrictMath=(\d+) maxUlp=(\d+)"
            r".*digest=(\w+)",
            ln,
        )
        if m and mode:
            modes[mode][m.group(1)] = (int(m.group(2)), int(m.group(3)), m.group(4))
            continue
        m = re.match(r"^(\w+\([\d.]+\))\s+(\S+)\s+StrictMath (\S+)", ln)
        if m:
            literals[m.group(1)] = (m.group(2), m.group(3))
    return modes, literals


def mathlane_control(name):
    """The control pass of a committed MathLaneProbe file: {OP: (vs StrictMath, max ulp)} and the
    input count."""
    lines = open(os.path.join(CANARY, name)).read().splitlines()
    start = [i for i, ln in enumerate(lines) if ln.startswith("### control")][0]
    out, n = {}, None
    for ln in lines[start:]:
        m = re.match(r"^arch=.*inputs=(\d+);", ln)
        if m:
            n = int(m.group(1))
        m = re.match(
            r"^(\w+)\s+[\d.]+ ns/elem.*vs StrictMath:\s+(\d+) lanes differ \(max (\d+)", ln
        )
        if m:
            out[m.group(1)] = (int(m.group(2)), int(m.group(3)))
        if ln.startswith("overflow band"):
            break
    return out, n
