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
"""The census of Spark's scalar expressions over milestone 8's type set (m8/PLAN.md 2).

  dev/varka_census.py            # rewrite m8/census.tsv and m8/casts.tsv, print the summary
  dev/varka_census.py --check    # fail if the committed census differs from a fresh one
  dev/varka_census.py --summary  # print the counts only

The mechanical half reads `FunctionRegistry.scala`: every registered name, the class or builder
it registers and the registry section it sits in. The judgement half is m8/census_curated.txt:
for each name whose inputs and result can all lie in the milestone's type set, the types it
admits there, its result, the types Varka fuses today, and what is left to do. Every other name
is excluded with a reason, by section or by name, below. Operators the parser or the analyzer
builds without a registry entry (CheckOverflow, SubtractTimestamps, try_cast, ...) are listed in
the curated file's [implicit] section.

The script fails rather than guess when the two halves disagree: a registered name with neither
a curated row nor an exclusion, a curated name the registry no longer has, a class named in the
census that the source does not define, an entry line in a registry section the parser did not
read, a type claimed as fused for a class that takes no operand of that type in any row of
`sql/varka/coverage.json` (each row lists its classes' operand types, so `year(d) = 2021 AND
i > 0` supports a date claim for Year and no int one; VARKA-328), and a cast pair the matrix marks
fused that no row casts, or one a row casts that it does not mark. A type marked `~` is fused only
over a derived operand, and its note must quote the coverage row that shows it. The cast aliases'
source types are not curated: they are the sources `legal()` allows.
So the census is regenerated, not edited, and a registry change upstream shows up here.
"""

import argparse
import collections
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGISTRY = (
    "sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/analysis/FunctionRegistry.scala"
)
COVERAGE = "sql/varka/coverage.json"
OUT = "sql/varka/plans/m8/census.tsv"
CURATED = "sql/varka/plans/m8/census_curated.txt"
SOURCES = ["sql/catalyst/src/main/scala", "sql/catalyst/src/main/java", "sql/api/src/main/scala"]

# The type set, owner's decision of 14 October 2026. EXISTING are the types `laneOf` admits
# today (VarkaNodeCompiler.laneOf: int, date and year-month intervals on the int lane; bigint,
# TIME and day-time intervals on the long lane); NEW are the ones this milestone adds.
EXISTING = ["int", "date", "ym", "bigint", "time", "dt"]
# "dec" is DECIMAL up to precision 38 (owner's decision, 14 October 2026): an unscaled long up to
# precision 18 and a 128-bit lane pair above it.
NEW = ["bool", "byte", "short", "float", "double", "dec", "ntz"]
ORDER = ["bool", "byte", "short", "int", "bigint", "float", "double", "dec"]
ORDER += ["date", "ntz", "time", "ym", "dt"]
GROUPS = {
    "INTEGRAL": ["byte", "short", "int", "bigint"],
    "FLOAT": ["float", "double"],
    "NUM": ["byte", "short", "int", "bigint", "float", "double", "dec"],
    "IVL": ["ym", "dt"],
    "ALL": ORDER,
}
NEEDS = ["done", "plumbing", "semantics", "ulp", "hard"]  # hard: empty since 14 October 2026
DECLINED = "owner's decision, 14 October 2026"

STRING = "STRING input or output: owner's decision, 14 October 2026"
LTZ = "TIMESTAMP with local time zone: owner's decision, 14 October 2026"
FOLDED = "a constant per query: folded to a literal before Varka sees it"
RANDOM = "nondeterministic: seeded per partition, its answer depends on row order"

SECTION_EXCLUSIONS = {
    "stringExpressions": STRING,
    "collectionExpressions": "nested types (array, map, struct): no representation",
    "lambdaExpressions": "nested types (array, map, struct): no representation",
    "arrayExpressions": "nested types (array, map, struct): no representation",
    "structExpressions": "nested types (array, map, struct): no representation",
    "mapExpressions": "nested types (array, map, struct): no representation",
    "aggregateExpressions": "aggregate: Varka fuses scalar projections and filters",
    "windowExpressions": "window function: Varka fuses scalar projections and filters",
    "generatorExpressions": "generator: Varka fuses scalar projections and filters",
    "csvExpressions": "parses or produces STRING: owner's decision, 14 October 2026",
    "jsonExpressions": "parses or produces STRING: owner's decision, 14 October 2026",
    "routedSqlJsonFunctionEntries": "parses or produces STRING: owner's decision, 14 October 2026",
    "xmlExpressions": "parses or produces STRING: owner's decision, 14 October 2026",
    "urlExpressions": "parses or produces STRING: owner's decision, 14 October 2026",
    "variantExpressions": "VARIANT: outside the type set",
    "avroExpressions": "BINARY: outside the type set",
    "protobufExpressions": "BINARY: outside the type set",
    "dataSketchExpressions": "sketch state in BINARY: outside the type set",
    "spatialExpressions": "geospatial types: outside the type set",
    "vectorExpressions": "arrays of floats: nested types, no representation",
}

NAME_EXCLUSIONS = {
    # math
    "rand": RANDOM,
    "random": RANDOM,
    "randn": RANDOM,
    "uniform": RANDOM,
    "bin": STRING,
    "hex": STRING,
    "unhex": STRING,
    "conv": STRING,
    "e": FOLDED,
    "pi": FOLDED,
    # predicate
    "like": STRING,
    "ilike": STRING,
    "rlike": STRING,
    "regexp_like": STRING,
    "regexp": STRING,
    # datetime
    "try_to_timestamp": STRING,
    "try_to_date": STRING,
    "try_to_time": STRING,
    "to_timestamp": STRING,
    "to_date": STRING,
    "to_time": STRING,
    "to_timestamp_ntz": STRING,
    "to_timestamp_ltz": STRING,
    "to_unix_timestamp": STRING,
    "unix_timestamp": STRING,
    "from_unixtime": STRING,
    "date_format": STRING,
    "dayname": STRING,
    "monthname": STRING,
    "current_timezone": STRING,
    "try_make_interval": "CALENDAR INTERVAL result: outside the type set",
    "make_interval": "CALENDAR INTERVAL result: outside the type set",
    "current_date": FOLDED,
    "curdate": FOLDED,
    "current_time": FOLDED,
    "localtime": FOLDED,
    "localtimestamp": FOLDED,
    "current_timestamp": LTZ,
    "now": LTZ,
    "from_utc_timestamp": LTZ,
    "to_utc_timestamp": LTZ,
    "convert_timezone": LTZ,
    "date_trunc": LTZ,
    "months_between": LTZ,
    "timestamp_seconds": LTZ,
    "timestamp_millis": LTZ,
    "timestamp_micros": LTZ,
    "timestamp_nanos": LTZ,
    "unix_seconds": LTZ,
    "unix_millis": LTZ,
    "unix_micros": LTZ,
    "unix_nanos": LTZ,
    "make_timestamp": LTZ + " (its NTZ form is make_timestamp_ntz)",
    "try_make_timestamp": LTZ + " (its NTZ form is try_make_timestamp_ntz)",
    "make_timestamp_ltz": LTZ,
    "try_make_timestamp_ltz": LTZ,
    "window": "STRUCT result: nested types, no representation",
    "session_window": "STRUCT result: nested types, no representation",
    "window_time": "STRUCT input: nested types, no representation",
    # hash
    "crc32": "BINARY input: outside the type set",
    "md5": STRING,
    "sha": STRING,
    "sha1": STRING,
    "sha2": STRING,
    "xxh3_64": "BINARY input: outside the type set",
    "xxh3_128": "BINARY input or output: outside the type set",
    # conversion
    "timestamp": LTZ,
    "binary": "BINARY: outside the type set",
    "string": STRING,
    # misc
    "try_aes_decrypt": "BINARY: outside the type set",
    "aes_encrypt": "BINARY: outside the type set",
    "aes_decrypt": "BINARY: outside the type set",
    "hmac": "BINARY: outside the type set",
    "try_reflect": STRING,
    "reflect": STRING,
    "java_method": STRING,
    "assert_true": "NULL-typed result: raises or returns null, nothing to compute",
    "raise_error": "NULL-typed result: raises or returns null, nothing to compute",
    "uuid": RANDOM,
    "spark_partition_id": "a per-task fact, not a function of the row",
    "input_file_name": STRING,
    "input_file_block_start": "a per-file fact, not a function of the row",
    "input_file_block_length": "a per-file fact, not a function of the row",
    "monotonically_increasing_id": "a per-task counter, not a function of the row",
    "current_database": STRING,
    "current_schema": STRING,
    "current_catalog": STRING,
    "current_path": STRING,
    "current_user": STRING,
    "user": STRING,
    "session_user": STRING,
    "version": STRING,
    "typeof": STRING,
    "bitmap_count": "BINARY input: outside the type set",
    "bitmap_and": "BINARY: outside the type set",
    "bitmap_or": "BINARY: outside the type set",
    "bitmap_andnot": "BINARY: outside the type set",
    "bitmap_xor": "BINARY: outside the type set",
}


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as f:
        return f.read()


ENTRY_START = (
    r"\bexpression\[|\bexpressionBuilder\(|\bexpressionGeneratorBuilderOuter\(|"
    r"\bcastAlias\(|\.registryEntry\b"
)
# The cast aliases and the type each casts to: their admitted sources come from legal().
CAST_ALIASES = {
    "boolean": "bool",
    "tinyint": "byte",
    "smallint": "short",
    "int": "int",
    "bigint": "bigint",
    "float": "float",
    "double": "double",
    "decimal": "dec",
    "date": "date",
    "time": "time",
}


def registry():
    """(section, name, class) for every registered name, in registry order."""
    text = read(REGISTRY)
    # Only the section definitions: the guideline comment above them quotes example entries,
    # and the code below them builds other registries.
    start = text.index("private def conditionalExpressions")
    end = text.index("val expressions: Map[String, (ExpressionInfo, FunctionBuilder)]")
    text = text[start:end]
    entries = []
    section = None
    patterns = [
        (r'expression\[(\w+)\]\(\s*"([^"]+)"', lambda m: (m.group(2), m.group(1))),
        (r'expressionBuilder\(\s*"([^"]+)",\s*(\w+)', lambda m: (m.group(1), m.group(2))),
        (
            r'expressionGeneratorBuilderOuter\(\s*"([^"]+)",\s*(\w+)',
            lambda m: (m.group(1), m.group(2)),
        ),
        (r'castAlias\(\s*"([^"]+)",\s*(\w+)', lambda m: (m.group(1), "Cast")),
        (r"\b(CaseWhen|CreateStruct)\.registryEntry", None),
    ]
    combined = re.compile(
        r"private (?:def|val) (\w+)\s*:\s*Seq\[FunctionRegistryEntry\]|"
        + "|".join("(?:" + p + ")" for p, _ in patterns),
        re.S,
    )
    special = {"CaseWhen": "when", "CreateStruct": "struct"}
    for m in combined.finditer(text):
        if m.group(1):
            section = m.group(1)
            continue
        token = m.group(0)
        for p, fn in patterns:
            mm = re.match(p, token, re.S)
            if mm:
                if fn is None:
                    name, cls = special[mm.group(1)], mm.group(1)
                else:
                    name, cls = fn(mm)
                entries.append((section, name, cls))
                break
    # Every entry line the sections hold must have been read: a form the patterns above do not
    # know would otherwise drop out of the census without the "neither curated nor excluded"
    # check ever seeing it.
    expected = len(re.findall(ENTRY_START, text))
    if expected != len(entries):
        raise SystemExit(f"census: {expected} registry entries in the source, {len(entries)} read")
    return entries


def expand(spec):
    out = []
    for part in [p.rstrip("~") for p in spec.split(",") if p]:
        for t in GROUPS.get(part, [part]):
            if t not in ORDER:
                raise SystemExit(f"unknown type {t!r} in {spec!r}")
            if t not in out:
                out.append(t)
    return sorted(out, key=ORDER.index)


def curated_sections():
    """The curated file's sections, comments dropped."""
    sections = {}
    current = None
    for line in read(CURATED).splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        if line.startswith("[") and line.endswith("]"):
            current = line[1:-1]
            sections[current] = []
            continue
        sections[current].append(line)
    return {k: "\n".join(v) for k, v in sections.items()}


def parse_table(text, width):
    rows = []
    for line in text.strip().splitlines():
        # `||` is the bitwise-or operator's name; split on the first field by hand.
        if line.startswith("||"):
            parts = ["|"] + line[2:].split("|")
        else:
            parts = line.split("|")
        if len(parts) != width:
            raise SystemExit(f"malformed census line ({len(parts)} fields): {line}")
        rows.append([p.strip() for p in parts])
    return rows


def coverage_index():
    """The types each Catalyst class takes in some coverage row, from the row's operand list: a
    class is credited only with the types its own node takes (VARKA-328), not with every column
    its row reads, so `year(d) = 2021 AND i > 0` supports a date claim for Year and no int one.
    Also returned: each row's SQL to its classes, for the `~` claims, and the casts the rows
    perform, as (from, to, mode) in this file's type names."""
    data = json.loads(read(COVERAGE))
    index = collections.defaultdict(set)
    index["#sql"] = {}
    index["#casts"] = set()
    for row in data["expressions"]:
        for operand in row["operands"]:
            cls, t = operand.split(":")
            index[cls].add(t)
        index["#sql"][row["sql"]] = set(row["catalyst"])
        for cast in row["casts"]:
            pair, mode = cast.split(":")
            src, dst = pair.split(">")
            index["#casts"].add((src, dst, mode))
    return index


def defined_classes():
    names = set()
    pattern = re.compile(r"^\s*(?:case\s+)?(?:class|object)\s+(\w+)", re.M)
    for base in SOURCES:
        for dirpath, _, files in os.walk(os.path.join(ROOT, base)):
            for f in files:
                if f.endswith((".scala", ".java")):
                    with open(os.path.join(dirpath, f), encoding="utf-8") as fh:
                        names.update(pattern.findall(fh.read()))
    return names


# Classes a fused claim is checked against when the registered class is not the one the
# compiler sees: a RuntimeReplaceable rewritten before physical planning, or a builder.
VIA = {
    "ifnull": "Coalesce",
    "nvl": "Coalesce",
    "when": "CaseWhen",
    "date_diff": "DateDiff",
    "dateadd": "DateAdd",
    "day": "DayOfMonth",
    "dayofmonth": "DayOfMonth",
    "hour": "HoursOfTime",
    "minute": "MinutesOfTime",
    "second": "SecondsOfTime",
    "==": "EqualTo",
    "extract": "Extract",
    "date_part": "Extract",
    "datepart": "Extract",
    "time_from_millis": "TimeFromMillis",
    "time_from_micros": "TimeFromMicros",
}


def check_cls_of(name, cls):
    return VIA.get(name, cls)


def build():
    entries = registry()
    sections = curated_sections()
    curated = {r[0]: r for r in parse_table(sections["registered"], 7)}
    implicit = parse_table(sections["implicit"], 9)
    cov = coverage_index()
    classes = defined_classes()
    errors = []
    seen = set()
    out = []
    for section, name, cls in entries:
        seen.add(name)
        if cls not in classes:
            errors.append(f"{name}: class {cls} is not defined in the sources")
        reason = NAME_EXCLUSIONS.get(name) or SECTION_EXCLUSIONS.get(section)
        if name in curated:
            if reason:
                errors.append(f"{name}: both curated and excluded ({reason})")
            _, types, result, fused, need, rows, note = curated[name]
            out.append(row(section, name, cls, types, result, fused, need, rows, note, cov, errors))
        elif reason:
            out.append([name, cls, section, "out", reason, "", "", "", "", "", "", ""])
        else:
            errors.append(f"{name} ({section}, {cls}): neither curated nor excluded")
    for name in curated:
        if name not in seen:
            errors.append(f"{name}: curated but no longer registered")
    for name, cls, family, types, result, fused, need, rows, note in implicit:
        if cls not in classes:
            errors.append(f"{name}: class {cls} is not defined in the sources")
        r = row("implicit", name, cls, types, result, fused, need, rows, note, cov, errors)
        r[2] = "implicit: " + family
        out.append(r)
    return out, errors


def row(section, name, cls, types, result, fused, need, rows, note, cov, errors):
    if (name in CAST_ALIASES) != (types == "legal") and section != "implicit":
        errors.append(f"{name}: a cast alias, and only one, takes its sources from legal()")
    if types == "legal":
        target = CAST_ALIASES[name]
        t = [src for src in ORDER if any(legal(src, target))]
        types = ",".join(t)
    # A type marked `!` is declined by the owner's decision: it leaves the types the entry admits
    # (and so the done-when denominator) and is recorded in the reason column instead.
    declined = expand(",".join(p[:-1] for p in types.split(",") if p.endswith("!")))
    t = expand(",".join(p for p in types.split(",") if p and not p.endswith("!")))
    res = t if result == "same" else expand(result)
    f = expand(fused)
    # A type marked `~` is fused in a coverage row whose operand of that type is derived (a
    # literal, or another expression's result) rather than a column; the note must quote that
    # row's SQL, and the row must name the class.
    derived = {p.rstrip("~") for p in fused.split(",") if p.endswith("~")}
    if derived:
        quoted = re.findall(r"`([^`]+)`", note)
        if not any(
            q in cov.get("#sql", {}) and check_cls_of(name, cls) in cov["#sql"][q] for q in quoted
        ):
            errors.append(f"{name}: a ~ claim must quote a coverage row naming its class")
    check_cls = check_cls_of(name, cls)
    for x in f:
        if x not in t:
            errors.append(f"{name}: fused type {x} is not among its types")
        if x in derived:
            continue
        if x not in cov.get(check_cls, set()):
            errors.append(f"{name}: no coverage row has a {check_cls} node over a {x} operand")
    if need not in NEEDS:
        errors.append(f"{name}: unknown need {need!r}")
    if (need == "done") != (set(f) == set(t)):
        errors.append(f"{name}: need {need} disagrees with fused {f} of {t}")
    lanes = [x for x in ORDER if x in NEW and (x in t or x in res)]
    return [
        name,
        cls,
        section,
        "in",
        f"declined {','.join(declined)}: {DECLINED}" if declined else "",
        ",".join(t),
        ",".join(res),
        ",".join(f),
        ",".join(lanes),
        need,
        rows,
        note,
    ]


HEADER = [
    "name",
    "class",
    "section",
    "scope",
    "reason",
    "types",
    "result",
    "fused_today",
    "new_lanes",
    "need",
    "rows",
    "note",
]


def render_rows(header, rows):
    return "\n".join("\t".join(r) for r in [header] + rows) + "\n"


def render(out):
    return render_rows(HEADER, out)


def summary(out):
    registered = [r for r in out if not r[2].startswith("implicit")]
    lines = []
    sections = collections.Counter(r[2] for r in registered)
    ins = collections.Counter(r[2] for r in out if r[3] == "in")
    lines.append(f"registered names: {len(registered)}, in {len(sections)} sections")
    lines.append(f"distinct registered classes: {len({r[1] for r in registered})}")
    implicit = [r for r in out if r[2].startswith("implicit")]
    lines.append(f"implicit operators: {len(implicit)}")
    scoped = [r for r in out if r[3] == "in"]
    lines.append(f"in scope: {len(scoped)} entries ({len({r[1] for r in scoped})} classes)")
    lines.append("")
    lines.append("section: registered / in scope")
    for s, n in sections.items():
        lines.append(f"  {s}: {n} / {ins.get(s, 0)}")
    lines.append(f"  implicit: {len(implicit)} / {ins.get('implicit', 0) + len(implicit)}")
    lines.append("")
    reasons = collections.Counter(r[4] for r in out if r[3] == "out")
    lines.append("excluded, by reason:")
    for reason, n in reasons.most_common():
        lines.append(f"  {n:4d}  {reason}")
    lines.append("")
    need = collections.Counter(r[9] for r in scoped)
    lines.append("in scope, by need: " + ", ".join(f"{k} {need.get(k, 0)}" for k in NEEDS))
    pairs = [(r, t) for r in scoped for t in r[5].split(",")]
    fused = [(r, t) for r, t in pairs if t in r[7].split(",")]
    lines.append(f"(entry, type) pairs: {len(pairs)}, fused today {len(fused)}")
    per_type = collections.Counter(t for _, t in pairs)
    per_type_fused = collections.Counter(t for _, t in fused)
    lines.append("pairs by type (fused today / admitted):")
    for t in ORDER:
        tag = "new" if t in NEW else "existing"
        lines.append(f"  {t:7s} {tag:8s} {per_type_fused.get(t, 0):4d} / {per_type.get(t, 0)}")
    full = [r for r in scoped if r[9] == "done"]
    lines.append(f"entries fused for every admitted type: {len(full)}")
    some = [r for r in scoped if r[7]]
    lines.append(f"entries fused for at least one type: {len(some)}")
    return "\n".join(lines) + "\n"


# ---- the cast matrix ----------------------------------------------------------------------

CASTS_OUT = "sql/varka/plans/m8/casts.tsv"
INTEGRAL = ["byte", "short", "int", "bigint"]
NUMERIC = INTEGRAL + ["float", "double", "dec"]
WIDTH = {"byte": 8, "short": 16, "int": 32, "bigint": 64}
# Exactly representable integers: a float holds 24 bits, a double 53.
MANTISSA = {"float": 24, "double": 53}


def legal(src, dst):
    """(LEGACY, ANSI) legality, transcribed from Cast.canCast (Cast.scala 249 to 340) and
    Cast.canAnsiCast (92 to 205) for the type set; TRY follows canTryCast, which for atomic
    types is canAnsiCast (211 to 239). The census's own check is row 328: a suite holds this
    function's answers to Cast's over every pair."""
    if src == dst:
        return True, True
    num = NUMERIC
    legacy = (
        (dst == "bool" and (src in num or src == "date"))
        or (src == "bool" and dst in num)
        or (src in num and dst in num)
        or (src == "date" and dst in num)
        or (src == "date" and dst == "ntz")
        or (src == "ntz" and dst == "date")
        or (src == "time" and dst in INTEGRAL + ["dec", "ntz"])
        or (src == "ntz" and dst == "time")
        or (src in ("ym", "dt") and dst in INTEGRAL + ["dec"])
        or (src in INTEGRAL + ["dec"] and dst in ("ym", "dt"))
    )
    ansi = (
        (dst == "bool" and src in num)
        or (src == "bool" and dst in num)
        or (src in num and dst in num)
        or (src == "date" and dst == "ntz")
        or (src == "ntz" and dst == "date")
        or (src == "time" and dst in INTEGRAL + ["dec", "ntz"])
        or (src == "ntz" and dst == "time")
        or (src in ("ym", "dt") and dst in INTEGRAL + ["dec"])
        or (src in INTEGRAL + ["dec"] and dst in ("ym", "dt"))
    )
    return legacy, ansi


def cast_kind(src, dst):
    """What the cast does to a value: the class the matrix in m8/PLAN.md 2.2 sorts it into."""
    if src == dst:
        if src == "ym":
            return (
                "relabel or unit narrowing",
                "MONTH end field keeps the count; YEAR divides by 12",
            )
        if src == "dt":
            return "relabel or unit narrowing", "SECOND end field keeps the micros; coarser divides"
        if src == "time":
            return "relabel or truncation", "wider precision keeps the nanos; narrower floors"
        if src == "dec":
            return "rescale", "scale up multiplies by 10^k; scale down rounds HALF_UP; overflow"
        return "identity", ""
    if dst == "bool":
        if src == "date":
            return "always null", "LEGACY returns null for every date"
        return "test", "x != 0 (a NaN is true)"
    if src == "bool":
        return "select", "1 or 0; DECIMAL(p, s) with p = s overflows"
    if src == "date" and dst in NUMERIC:
        return "always null", "LEGACY returns null for every date"
    if src in INTEGRAL and dst in INTEGRAL:
        if WIDTH[dst] > WIDTH[src]:
            return "widening", "sign extension, exact"
        return "narrowing", "LEGACY wraps; ANSI raises CAST_OVERFLOW; TRY nulls"
    if src in INTEGRAL and dst in MANTISSA:
        if WIDTH[src] < MANTISSA[dst]:
            return "widening", "exact"
        return "rounding", "round to nearest even, as the JVM converts; no error"
    if src in MANTISSA and dst in MANTISSA:
        if src == "float":
            return "widening", "exact"
        return "rounding", "round to nearest even; overflow to infinity, no error"
    if src in MANTISSA and dst in INTEGRAL:
        if dst == "bigint":
            note = (
                "LEGACY saturates, NaN to 0; ANSI and TRY range-check on the double against"
                " Long.MaxValue.toDouble, which is 2^63, so 2^63 passes and saturates; NaN raises"
                " or nulls"
            )
        elif dst == "int":
            note = (
                "LEGACY saturates, NaN to 0; ANSI and TRY range-check on the double against"
                " 2^31 - 1 exactly; NaN raises or nulls"
            )
        else:
            note = (
                "LEGACY saturates to int then wraps; ANSI and TRY range-check, NaN raises or nulls"
            )
        return "truncation", note
    if src in INTEGRAL and dst == "dec":
        return (
            "scale up",
            "exact; overflow past p - s digits: LEGACY null, ANSI NUMERIC_VALUE_OUT_OF_RANGE",
        )
    if src == "dec" and dst == "bigint":
        return (
            "truncation",
            "toward zero, exact; LEGACY keeps the low bits, ANSI raises CAST_OVERFLOW",
        )
    if src == "dec" and dst in INTEGRAL:
        return (
            "truncation",
            "toward zero; LEGACY exact, keeping the low bits; ANSI and TRY range-check by the"
            " double rule (Decimal.roundToNumeric over a BigDecimal-backed value) at every"
            " precision, as Varka's fallback reads it through ArrowColumnVector (row 365, option"
            " iii); Spark's cached row path reads a long-backed value and the exact rule",
        )
    if src == "dec" and dst == "double":
        return (
            "rounding",
            "BigDecimal.doubleValue: one division under the JDK's guard (scale 0, or (long)(double)"
            " u == u with 0 < s <= 22, Long.MAX_VALUE excluded); the exact arm (366) for the rest",
        )
    if src == "dec" and dst == "float":
        return (
            "rounding",
            "BigDecimal.floatValue, rounded once: (long)(float) u == u and s <= 10 divided in"
            " float, never the double quotient narrowed; round-to-odd or the exact arm (366)"
            " past it",
        )
    if src in MANTISSA and dst == "dec":
        return (
            "shortest digits",
            "Decimal(double) is the double's shortest decimal: a lanewise guard from the rounding"
            " interval and an integer arm in lanes, the exact shortest-digits path for the rest"
            " (346)",
        )
    if src == "date" and dst == "ntz":
        return (
            "scale up",
            "days times micros per day; past 106,751,991 days multiplyExact raises under LEGACY"
            " and ANSI, TRY nulls",
        )
    if src == "ntz" and dst == "date":
        return "floor division", "floorDiv by micros per day, exact"
    if src == "ntz" and dst == "time":
        return "floor modulo", "floorMod by micros per day, to nanos, truncated to the precision"
    if src == "time" and dst == "ntz":
        return (
            "session date",
            "the date comes from CURRENT_DATE, a literal after ComputeCurrentTime",
        )
    if src == "time" and dst in INTEGRAL:
        return "floor division", "floorDiv by nanos per second; narrower targets range-check"
    if src == "time" and dst == "dec":
        return "rescale", "nanos as DECIMAL(14, 9), HALF_UP to the target scale"
    if src in ("ym", "dt") and dst in INTEGRAL:
        return (
            "unit division",
            "truncating division by the end field's unit; narrower targets check",
        )
    if src == "ym" and dst == "dec":
        return "unit division", "the month count divided to the end field's unit, truncating"
    if src == "dt" and dst == "dec":
        return "unit division", "to the end field's unit; a SECOND end keeps six decimals"
    if src in ("byte", "short") and dst in ("ym", "dt"):
        return "scale up", "times the unit, exact: no value of the source overflows"
    if src == "int" and dst == "ym":
        return (
            "scale up",
            "times the unit; a YEAR end overflows past 178,956,970 (multiplyExact in int): raises"
            " under LEGACY and ANSI, TRY nulls; a MONTH end never",
        )
    if src == "int" and dst == "dt":
        return (
            "scale up",
            "times the unit; a DAY end overflows past 106,751,991: raises under LEGACY and ANSI,"
            " TRY nulls; the other ends never",
        )
    if src == "bigint" and dst == "ym":
        return (
            "scale up",
            "times the unit and narrowed to int (toIntExact); overflow raises under LEGACY and"
            " ANSI, TRY nulls",
        )
    if src == "bigint" and dst == "dt":
        return (
            "scale up",
            "times the unit in a long; overflow raises under LEGACY and ANSI, TRY nulls",
        )
    if src == "dec" and dst in ("ym", "dt"):
        return "rescale", "to the unit, HALF_UP; overflow raises under LEGACY and ANSI, TRY nulls"
    return "?", ""


def cast_proof(src, dst):
    """How a pair is proven: exhaustion where the source has at most 32 bits; SMT for an integer
    rule over a 64-bit source; sampled regions and the fuzzer for a double source and for a
    64-bit source rounding to a floating type, which floating-point theory does not decide in
    useful time (VARKA-241.md). A decimal to INT, SMALLINT or TINYINT is mixed: LEGACY's low bits
    are an integer rule, the ANSI and TRY range check by the double rule is not, at every precision
    (the owner's answer to question 3 of m8/PLAN.md section 7, 16 October 2026, which superseded
    decision A1's exact rule below precision 19)."""
    if src in ("bool", "byte", "short", "int", "date", "ym", "float"):
        return "exhaustion"
    if src == "dec" and dst in ("byte", "short", "int"):
        return (
            "mixed: SMT (dev/varka_prove.sh) for LEGACY's low bits, sampled regions and the fuzzer"
            " for the ANSI and TRY range check by the double rule at every precision"
        )
    if src in ("bigint", "dt", "time", "ntz", "dec") and dst not in ("float", "double"):
        return "SMT (dev/varka_prove.sh) for the integer rule, sampled regions and the fuzzer"
    return "sampled regions and the fuzzer"


def cast_rows(src, dst):
    lanes = {src, dst}
    if "dec" in lanes:
        if dst in ("byte", "short", "int"):
            return "350,364,365"
        if dst in ("float", "double"):
            return "350,364,366"
        if src in ("float", "double"):
            return "346,350,364"
        return "350,364"
    if lanes & {"float", "double"}:
        return "343"
    if lanes & {"ntz"}:
        return "338" if lanes <= {"date", "ntz"} else "340"
    if lanes <= set(INTEGRAL + ["bool"]):
        return "332,28" if lanes & {"bigint"} and lanes & {"byte", "short", "int"} else "332"
    if lanes & {"ym", "dt"}:
        return "103,332"
    return "332"


# The pairs Varka fuses today, each a cast arm of the compiler (VarkaIntervalCompiler,
# VarkaTimeCompiler, VarkaNodeCompiler) and a coverage row casting that pair: in part, as the
# note says. `cast_errors` holds the two to each other (VARKA-328).
FUSED_CASTS = {
    ("ym", "ym"): "MONTH end field only (CAST(ymy AS INTERVAL MONTH), ym - ymm)",
    ("dt", "dt"): "SECOND end field only (dt < INTERVAL '0' SECOND)",
    ("time", "time"): "widening precision only",
    ("int", "ym"): "MONTH unit only (CAST(i AS INTERVAL MONTH))",
    ("ym", "int"): "MONTH-ended source only",
    ("int", "dt"): "DAY unit, only as date_add's offset (d + CAST(i AS INTERVAL DAY))",
}

# The mode a coverage row's cast runs in, to the legal() answer that admits it.
MODE_LEGAL = {"legacy": 0, "ansi": 1, "try": 1}


def cast_errors(cov):
    """Comet's four-way rule over the matrix and the coverage table, per mode: a pair marked fused
    has a coverage row casting it; a row's cast is legal in its mode and not an identity, which
    the analyzer removes before a cast exists; and a pair a row casts is marked fused. The rows
    resolve under ANSI, Spark's default, so a pair fused only in another mode cannot be claimed
    until a row runs in it."""
    errors = []
    cast = cov["#casts"]
    for src, dst, mode in sorted(cast):
        if mode not in MODE_LEGAL or not legal(src, dst)[MODE_LEGAL[mode]]:
            errors.append(f"a coverage row casts {src} to {dst} under {mode}, where it is illegal")
        elif cast_kind(src, dst)[0] == "identity":
            errors.append(f"a coverage row casts {src} to {dst}, an identity")
        elif (src, dst) not in FUSED_CASTS:
            errors.append(f"a coverage row casts {src} to {dst}; mark it in FUSED_CASTS")
    for src, dst in FUSED_CASTS:
        if not any((src, dst) == (s, d) for s, d, _ in cast):
            errors.append(f"cast {src} to {dst} is marked fused and no coverage row casts it")
    return errors


CAST_HEADER = ["from", "to", "legacy", "ansi", "try", "kind", "semantics", "proof"]
CAST_HEADER += ["fused_today", "rows"]


def cast_matrix():
    out = []
    for src in ORDER:
        for dst in ORDER:
            lg, an = legal(src, dst)
            if not (lg or an):
                continue
            kind, note = cast_kind(src, dst)
            if kind == "?":
                raise SystemExit(f"cast {src} -> {dst} is legal but has no kind")
            fused = FUSED_CASTS.get((src, dst), "")
            out.append(
                [
                    src,
                    dst,
                    "yes" if lg else "no",
                    "yes" if an else "no",
                    "yes" if an else "no",
                    kind,
                    note,
                    cast_proof(src, dst),
                    fused,
                    cast_rows(src, dst),
                ]
            )
    return out


def cast_summary(rows):
    lines = []
    lines.append(f"cast pairs over the 13 types: {len(ORDER) ** 2} ordered, {len(rows)} legal")
    lg = sum(r[2] == "yes" for r in rows)
    an = sum(r[3] == "yes" for r in rows)
    lines.append(f"legal under LEGACY: {lg}, under ANSI and TRY: {an}")
    lines.append(f"legal under LEGACY only: {sum(r[2] == 'yes' and r[3] == 'no' for r in rows)}")
    kinds = collections.Counter(r[5] for r in rows)
    lines.append("by kind: " + ", ".join(f"{k} {n}" for k, n in kinds.most_common()))
    proofs = collections.Counter(r[7].split(" ")[0].rstrip(":") for r in rows)
    lines.append("by proof: " + ", ".join(f"{k} {n}" for k, n in proofs.most_common()))
    lines.append(f"fused today, at least in part: {sum(bool(r[8]) for r in rows)}")
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true", help="fail if the committed file is stale")
    ap.add_argument("--summary", action="store_true", help="print the counts only")
    args = ap.parse_args()
    out, errors = build()
    errors += cast_errors(coverage_index())
    if errors:
        for e in errors:
            print("census: " + e, file=sys.stderr)
        return 1
    casts = cast_matrix()
    files = {OUT: render(out), CASTS_OUT: render_rows(CAST_HEADER, casts)}
    if args.check:
        stale = [path for path, text in files.items() if read(path) != text]
        for path in stale:
            print(f"census: {path} is stale; run dev/varka_census.py", file=sys.stderr)
        return 1 if stale else 0
    if not args.summary:
        for path, text in files.items():
            with open(os.path.join(ROOT, path), "w", encoding="utf-8") as f:
                f.write(text)
    sys.stdout.write(summary(out))
    sys.stdout.write("\n" + cast_summary(casts))
    return 0


if __name__ == "__main__":
    sys.exit(main())
