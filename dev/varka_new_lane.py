#!/usr/bin/env python3
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
"""Has every lane been wired at the sites no compiler checks? (VARKA-474 2.3.)

  dev/varka_new_lane.py --check            # every lane
  dev/varka_new_lane.py --check BYTE SHORT # these lanes

A new lane touches its `LaneType` member, one `Lane` entry, one `VarkaLaneArrow` entry, its IR
leaves and emitter arms, and its tests. The members, entries and arms are exhaustive switches, so
the compiler lists every one a new lane misses. The sites below are not: each compiles without the
lane and ignores it silently, so this script walks them. Per lane: the compiler's admission (the
registry puts a Spark type on it), the template warm-up's load of the lane's vector class, a fuzz
grammar leaf and a benchmark. Per Spark type on the lane: a coverage column, a width-audit column
and a census entry fused today, so a new type on an existing lane - `TIMESTAMP_NTZ` on the long lane
- is checked too, though the lane's own sites are already wired. It prints each site with no case,
with the file and what the site does. It cannot prove a case is right; it proves the site was looked
at. A type starts here at the registry: until it is on a lane, nothing knows of it.

The lanes are read from the code, not listed here: the `LaneType` members from `VarkaVectorIR`,
each lane's Vector API class from `Lane`, and the Spark types on each lane, with their census
names, from the type registry's committed table, `sql/varka/types.tsv` (`VarkaTypes`, the
compiler's admission).

Sites keyed by IR node kind rather than by lane - the cost model's feature names, the census
catalog of operators, the reference evaluator and the fuzz grammar's per-kind switches - need no
arm for a lane, or are exhaustive switches the compiler already checks, and are not walked. A
gap a later row owns is listed in OWED with that row and printed as owed, not as missing; the
exit status counts only omissions no row owns.
"""

import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CODEGEN = "sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen"
CODEGEN_TEST = "sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen"
CODEGEN_SCALA_TEST = "sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen"
IR = f"{CODEGEN}/varka/VarkaVectorIR.java"
LANE = f"{CODEGEN}/varka/Lane.java"
REGISTRY = f"{CODEGEN}/varka/VarkaTypes.java"
WARMUP = f"{CODEGEN}/varka/VarkaVectorTemplateWarmup.java"
GRAMMAR = f"{CODEGEN_TEST}/varka/VarkaIrGrammar.java"
COVERAGE_SUITE = f"{CODEGEN_SCALA_TEST}/varka/VarkaCoverageSuite.scala"
WIDTH_AUDIT = f"{CODEGEN_SCALA_TEST}/varka/VarkaWidthAuditShapes.scala"
CENSUS = "sql/varka/plans/m8/census.tsv"
TYPES = "sql/varka/types.tsv"
BENCHMARKS = [
    "sql/catalyst/src/test/java/org/apache/spark/sql",
    "sql/core/src/test/java/org/apache/spark/sql",
]

# (site, lane) -> the row that owns the gap.
OWED = {
    ("fuzz grammar", "BYTE"): "row 352 (the fuzzers across types)",
    ("fuzz grammar", "SHORT"): "row 352 (the fuzzers across types)",
    ("fuzz grammar", "FLOAT"): "row 341 (the floating lanes, after their split)",
    ("benchmark", "FLOAT"): "row 341 (the floating lanes, after their split)",
    ("fuzz grammar", "DOUBLE"): "row 341 (the floating lanes, after their split)",
    ("benchmark", "DOUBLE"): "row 341 (the floating lanes, after their split)",
    # A decimal pair counts fused only when it fuses at every precision up to 38 (m8/PLAN.md 2.8),
    # so the comparisons and null tests row 347 fuses up to precision 18 are a note, not a claim.
    ("census", "DecimalType"): "row 359 (a decimal pair fused at every precision, PLAN.md 2.8)",
}


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as f:
        return f.read()


def lane_types():
    """The LaneType members, in declaration order."""
    # The members up to the first `;`, each a bare name or a name with constructor arguments
    # (`INT(Kind.INTEGRAL, 32)`): the names are what stands at the start of each.
    m = re.search(r"enum LaneType\s*\{([^;]*);", read(IR))
    if not m:
        sys.exit(f"{IR}: no `enum LaneType` member list found")
    names = re.findall(r"(?:^|,)\s*([A-Z_][A-Z0-9_]*)\s*(?:\(|,|$)", m.group(1))
    if not names:
        sys.exit(f"{IR}: no `enum LaneType` member list found")
    return names


def vector_classes():
    """Each lane's Vector API class, from Lane's constants."""
    pairs = re.findall(
        r'\b[A-Z]+\(VarkaVectorIR\.LaneType\.([A-Z_]+),\s*"jdk\.incubator\.vector\.(\w+)"',
        read(LANE),
    )
    return dict(pairs)


def spark_types():
    """Each lane's Spark types: the type registry's entries read on a lane, from its committed
    table (`sql/varka/types.tsv`, written by `VarkaTypesSuite` from `VarkaTypes`). A decimal is
    read on its lane up to a precision (`up_to_precision`), and is checked there like the rest."""
    out = {}
    for row in types_rows():
        if row["admission"] in ("on_lane", "up_to_precision"):
            out.setdefault(row["lane"], []).extend(row["spark"].split(","))
    return out


def types_rows():
    """The registry's rows, a dict per id, from `sql/varka/types.tsv`."""
    lines = [line for line in read(TYPES).splitlines() if line and not line.startswith("#")]
    header = lines[0].split("\t")
    return [dict(zip(header, line.split("\t"))) for line in lines[1:]]


def call_args(src, start):
    """The text between the parenthesis at `start` and its match."""
    depth = 0
    for i in range(start, len(src)):
        if src[i] == "(":
            depth += 1
        elif src[i] == ")":
            depth -= 1
            if depth == 0:
                return src[start + 1 : i]
    return ""


def top_level_args(text):
    args, depth, cur = [], 0, ""
    for ch in text:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            args.append(cur.strip())
            cur = ""
        else:
            cur += ch
    if cur.strip():
        args.append(cur.strip())
    return args


def grammar_leaf_lanes():
    """The lanes the IR fuzz grammar draws a column leaf of; a one-argument ColumnRef is INT."""
    src = read(GRAMMAR)
    lanes = set()
    for m in re.finditer(r"new ColumnRef\(", src):
        args = top_level_args(call_args(src, m.end() - 1))
        if len(args) == 1:
            lanes.add("INT")
        elif len(args) == 2:
            lm = re.search(r"LaneType\.([A-Z_]+)", args[1])
            if lm:
                lanes.add(lm.group(1))
    return lanes


def column_types(path):
    """The Spark types a Scala suite declares a column of."""
    return set(re.findall(r'AttributeReference\("[^"]+",\s*(\w+)', read(path)))


def census_fused():
    names = set()
    lines = read(CENSUS).splitlines()
    col = lines[0].split("\t").index("fused_today")
    for line in lines[1:]:
        cells = line.split("\t")
        if len(cells) > col and cells[col]:
            names.update(cells[col].split(","))
    return names


def benchmark_sources():
    """Every Varka*Benchmark.java under a module's test sources, at any depth."""
    out = []
    for d in BENCHMARKS:
        for dirpath, _, files in os.walk(os.path.join(ROOT, d)):
            for f in sorted(files):
                if f.startswith("Varka") and f.endswith("Benchmark.java"):
                    path = os.path.relpath(os.path.join(dirpath, f), ROOT)
                    out.append((path, read(path)))
    return out


def report(label, gaps, checked, owed_keys):
    """Prints one lane's or type's gaps; returns how many no row owns, stale OWED lines too."""
    missing = 0
    names = {name for name, _, _ in gaps}
    for site, key in OWED:
        if key == owed_keys and site not in names:
            print(f"{label}: stale OWED entry: the {site} site is wired; remove its line")
            missing += 1
    if not gaps:
        print(f"{label}: no site is missing ({checked} checked)")
        return missing
    for name, path, what in gaps:
        owner = OWED.get((name, owed_keys))
        if owner:
            print(f"{label}: owed by {owner}: {name} ({path}): {what}")
        else:
            print(f"{label}: missing: {name} ({path}): {what}")
            missing += 1
    return missing


def check(lanes_wanted):
    members = lane_types()
    vectors = vector_classes()
    types = spark_types()
    missing = 0
    for lane in members:
        if lane not in vectors:
            print(f"{LANE}: no Lane constant names LaneType.{lane}")
            missing += 1
    census_name = {}
    for row in types_rows():
        for t in row["spark"].split(","):
            census_name[t] = row["census"]
    for lane, names in types.items():
        for t in names:
            if census_name.get(t, "-") == "-":
                print(f"{TYPES}: {t} is on {lane} and has no census name")
                missing += 1

    leaves = grammar_leaf_lanes()
    coverage = column_types(COVERAGE_SUITE)
    audit = column_types(WIDTH_AUDIT)
    fused = census_fused()
    warmup = read(WARMUP)
    benches = benchmark_sources()

    for lane in lanes_wanted:
        if lane not in members:
            print(f"{lane}: not a LaneType member ({', '.join(members)})")
            missing += 1
            continue
        lane_spark = types.get(lane, [])
        vector = vectors.get(lane, "")
        vector_call = re.compile(rf"\b{vector}\.") if vector else None
        # The lane's own sites: what is per species or per IR leaf, not per Spark type.
        lane_sites = [
            (
                "compiler admission",
                REGISTRY,
                "the type registry puts a Spark type on the lane",
                bool(lane_spark),
            ),
            (
                "template warm-up",
                WARMUP,
                f"a block loading the lane's {vector or 'vector class'}",
                bool(vector) and f"{vector}.fromMemorySegment" in warmup,
            ),
            ("fuzz grammar", GRAMMAR, "a column leaf of the lane", lane in leaves),
            (
                "benchmark",
                "Varka*Benchmark.java",
                "a benchmark class that reads the lane: its IR, a Spark type of it, or its "
                "Vector API class",
                any(
                    f"LaneType.{lane}" in src
                    or (vector_call is not None and vector_call.search(src))
                    or any(f"DataTypes.{t}" in src for t in lane_spark)
                    for _, src in benches
                ),
            ),
        ]
        gaps = [(name, path, what) for name, path, what, ok in lane_sites if not ok]
        missing += report(lane, gaps, len(lane_sites), lane)
        # Each Spark type's own sites: a new type on an existing lane is checked here, since
        # the lane's sites above are already wired by the types before it.
        for t in lane_spark:
            name = census_name.get(t)
            type_sites = [
                (
                    "coverage table",
                    COVERAGE_SUITE,
                    f"a {t} column, so a coverage row can read one",
                    t in coverage,
                ),
                (
                    "width audit",
                    WIDTH_AUDIT,
                    f"a {t} column among the per-width shapes",
                    t in audit,
                ),
                (
                    "census",
                    CENSUS,
                    f"an entry fused today for {name or t}",
                    name is not None and name in fused,
                ),
            ]
            gaps = [(n, path, what) for n, path, what, ok in type_sites if not ok]
            missing += report(f"{lane}/{t}", gaps, len(type_sites), t)
    return missing


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument(
        "--check",
        nargs="*",
        metavar="LANE",
        required=True,
        help="the lanes to check; every LaneType member when none is named",
    )
    args = ap.parse_args()
    lanes = args.check or lane_types()
    sys.exit(1 if check(lanes) else 0)


if __name__ == "__main__":
    main()
