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

  dev/varka_census.py            # rewrite the census, the cast matrix and the docs' support
                                 # table, print the summary
  dev/varka_census.py --check    # fail if any of the three differs from a fresh one
  dev/varka_census.py --summary  # print the counts only

The mechanical half reads `FunctionRegistry.scala`: every registered name, the class or builder
it registers and the registry section it sits in. The judgement half is m8/census_curated.txt:
for each name whose inputs and result can all lie in the milestone's type set, the types it
admits there, its result, the types Varka fuses today, and what is left to do. Every other name
is excluded with a reason, by section or by name, below. Operators the parser or the analyzer
builds without a registry entry (CheckOverflow, SubtractTimestamps, try_cast, ...) are listed in
the curated file's [implicit] section, and those excluded with a reason in its
[implicit excluded] section.

The script fails rather than guess when the two halves disagree: a registered name with neither
a curated row nor an exclusion, a curated name the registry no longer has, a class named in the
census that the source does not define, an entry line in a registry section the parser did not
read, a type claimed as fused for a class that takes no operand of that type in any row of
`sql/varka/coverage.json` (each row lists its classes' operand types, so `year(d) = 2021 AND
i > 0` supports a date claim for Year and no int one; VARKA-328), and a cast pair the compiler
fuses that no row casts, or one a row casts that it does not fuse. Which casts fuse is not
curated: VarkaCastMatrixSuite asks the compiler and writes m8/casts_fused.tsv, from which
casts.tsv's fused_today is derived (VARKA-486). A type marked `~` is fused only
over a derived operand, and its note must quote the coverage row that shows it. A type marked
`^` is fused in part: one band or one form of it fuses (DECIMAL at one scale, say, while two
scales wait for a rescale), which a coverage row must show, but it is not counted as fused. It
goes to census.tsv's fused_in_part column and never to fused_today, so the done-when counts and
the need rule read it as unfused; the conformance harness lets such a form fuse without calling
it drift (VARKA-359.md 10.5). The cast aliases'
source types are not curated: they are the sources `legal()` allows. Nor are the fused types of
the cast entries (the aliases, `cast` and `try_cast`, marked `matrix`): they are derived from
casts_fused.tsv by matrix_fused() (VARKA-606).
So the census is regenerated, not edited, and a registry change upstream shows up here.

The census reaches users as the support table of docs/sql-varka.md (VARKA-353), a block this
script writes between its markers and --check holds to what it renders: per type and per family,
the math functions and the casts per mode, every cell read from the census, the cast probe and
coverage.json, so a new lowering moves the table in the same commit as the census.
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

TYPES = "sql/varka/types.tsv"


def types_rows(root=ROOT):
    """The type registry's rows, a dict per id, from its committed table (`sql/varka/types.tsv`,
    written by `VarkaTypesSuite` from `VarkaTypes`, VARKA-494)."""
    with open(os.path.join(root, TYPES), encoding="utf-8") as f:
        lines = [line.rstrip("\n") for line in f if line.strip() and not line.startswith("#")]
    header = lines[0].split("\t")
    return [dict(zip(header, line.split("\t"))) for line in lines[1:]]


# The census's types and their order are the registry's: every type it names.
ORDER = [r["census"] for r in types_rows() if r["census"] != "-"]
# The milestone's split of them, owner's decision of 14 October 2026: EXISTING are the types a
# lane read before milestone 8, NEW the ones it adds. A judgement about the milestone, not a fact
# of a type, so it stays here; conformance_errors() holds the two to the registry's set.
EXISTING = ["int", "date", "ym", "bigint", "time", "dt"]
# "dec" is DECIMAL up to precision 38 (owner's decision, 14 October 2026): an unscaled long up to
# precision 18 and a 128-bit lane pair above it.
NEW = ["bool", "byte", "short", "float", "double", "dec", "ntz"]
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
# The modes each cast entry can run in. An alias and `cast` are a Cast under the session's mode,
# LEGACY or ANSI as spark.sql.ansi.enabled says, never TRY; `try_cast` is TRY alone.
CAST_ENTRY_MODES = {name: ("legacy", "ansi") for name in [*CAST_ALIASES, "cast"]}
CAST_ENTRY_MODES["try_cast"] = ("try",)


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
    "ceil": "Ceil",
    "ceiling": "Ceil",
    "floor": "Floor",
    "ifnull": "Coalesce",
    "nvl": "Coalesce",
    "zeroifnull": "Coalesce",
    "nvl2": "If",
    "nullif": "If",
    "nullifzero": "If",
    "between": "GreaterThanOrEqual",
    "when": "CaseWhen",
    "date_diff": "DateDiff",
    "dateadd": "DateAdd",
    "day": "DayOfMonth",
    "dayofmonth": "DayOfMonth",
    "hour": ("HoursOfTime", "Hour"),
    "minute": ("MinutesOfTime", "Minute"),
    "second": ("SecondsOfTime", "Second"),
    "==": "EqualTo",
    "extract": "Extract",
    "date_part": "Extract",
    "datepart": "Extract",
    "time_from_millis": "TimeFromMillis",
    "time_from_micros": "TimeFromMicros",
    "make_timestamp_ntz": "MakeTimestampNTZ",
}


def check_cls_of(name, cls):
    """The classes an entry's claim is checked against: VIA's, a tuple, or the entry's own.

    A name may be several classes: `hour` is `HoursOfTime` over a TIME and `Hour` over a
    TIMESTAMP_NTZ (VARKA-340), so its value in VIA is a tuple of both.
    """
    via = VIA.get(name, cls)
    return via if isinstance(via, tuple) else (via,)


def build():
    entries = registry()
    sections = curated_sections()
    curated = {r[0]: r for r in parse_table(sections["registered"], 7)}
    implicit = parse_table(sections["implicit"], 9)
    implicit_out = parse_table(sections.get("implicit excluded", ""), 4)
    cov = coverage_index()
    probe = casts_fused()
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
            r = row(section, name, cls, types, result, fused, need, rows, note, cov, errors, probe)
            out.append(r)
        elif reason:
            out.append([name, cls, section, "out", reason, "", "", "", "", "", "", "", ""])
        else:
            errors.append(f"{name} ({section}, {cls}): neither curated nor excluded")
    for name in curated:
        if name not in seen:
            errors.append(f"{name}: curated but no longer registered")
    for name, cls, family, types, result, fused, need, rows, note in implicit:
        if cls not in classes:
            errors.append(f"{name}: class {cls} is not defined in the sources")
        r = row("implicit", name, cls, types, result, fused, need, rows, note, cov, errors, probe)
        r[2] = "implicit: " + family
        out.append(r)
    for name, cls, family, reason in implicit_out:
        if cls not in classes:
            errors.append(f"{name}: class {cls} is not defined in the sources")
        if name in {r[0] for r in implicit}:
            errors.append(f"{name}: both an implicit entry and excluded ({reason})")
        out.append(
            [name, cls, "implicit: " + family, "out", reason, "", "", "", "", "", "", "", ""]
        )
    return out, errors


def row(section, name, cls, types, result, fused, need, rows, note, cov, errors, probe):
    """One census entry's row, each claim rule it breaks appended to `errors`.

    Each rule is shown failing on a planted claim (VARKA-504), against a coverage index whose one
    row is `year(d) = 2021`, a Year node over a date operand. The first claim is the true one, so
    a rule that reported everything would show here too.

    >>> cov = {"Year": {"date"}, "#sql": {"year(d) = 2021": {"Year"}}}
    >>> def claim(types, fused, need, note=""):
    ...     errors = []
    ...     row("datetimeExpressions", "year", "Year", types, "int", fused, need, "", note, cov,
    ...         errors, {})
    ...     return errors
    >>> claim("date", "date", "done")
    []
    >>> claim("date", "date,int", "plumbing")  # a fused type the entry does not admit
    ['year: fused type int is not among its types', \
'year: no coverage row has a Year node over a int operand']
    >>> claim("date,int", "date,int", "done")  # a claim no coverage row shows
    ['year: no coverage row has a Year node over a int operand']
    >>> claim("date,int", "date,int~", "done")  # a derived claim that quotes no row
    ['year: a ~ claim must quote a coverage row naming its class']
    >>> claim("date,int", "date,int~", "done", "as in `year(d) = 2021`")
    []
    >>> claim("date", "date,date^", "done")  # fused and fused in part at once
    ['year: type date is both fused and fused in part']
    >>> claim("date", "int^", "plumbing")  # fused in part outside the entry's types
    ['year: type int fused in part is not among its types', \
'year: no coverage row has a Year node over a int operand']
    >>> claim("date", "date", "plumbing")  # a need that the fused types contradict
    ["year: need plumbing disagrees with fused ['date'] of ['date']"]
    >>> claim("date", "", "unplanned")  # a need the census does not know
    ["year: unknown need 'unplanned'"]
    """
    if (name in CAST_ALIASES) != (types == "legal") and section != "implicit":
        errors.append(f"{name}: a cast alias, and only one, takes its sources from legal()")
    if types == "legal":
        target = CAST_ALIASES[name]
        t = [src for src in ORDER if any(legal(src, target))]
        types = ",".join(t)
    if (name in CAST_ENTRY_MODES) != (fused == "matrix"):
        errors.append(f"{name}: a cast entry, and only one, takes its fused types from the matrix")
    # A type marked `!` is declined by the owner's decision: it leaves the types the entry admits
    # (and so the done-when denominator) and is recorded in the reason column instead.
    declined = expand(",".join(p[:-1] for p in types.split(",") if p.endswith("!")))
    t = expand(",".join(p for p in types.split(",") if p and not p.endswith("!")))
    res = t if result == "same" else expand(result)
    fused_by_matrix = fused == "matrix" and name in CAST_ENTRY_MODES
    if fused_by_matrix:
        fused = matrix_fused(name, t, probe, cov)
    # A type marked `^` is fused in part, and kept apart from the counted ones.
    partial = expand(",".join(p[:-1] for p in fused.split(",") if p.endswith("^")))
    f = expand(",".join(p for p in fused.split(",") if p and not p.endswith("^")))
    # A type marked `~` is fused in a coverage row whose operand of that type is derived (a
    # literal, or another expression's result) rather than a column; the note must quote that
    # row's SQL, and the row must name the class.
    derived = {p.rstrip("~") for p in fused.split(",") if p.endswith("~")}
    if derived:
        quoted = re.findall(r"`([^`]+)`", note)
        if not any(
            q in cov.get("#sql", {}) and c in cov["#sql"][q]
            for q in quoted
            for c in check_cls_of(name, cls)
        ):
            errors.append(f"{name}: a ~ claim must quote a coverage row naming its class")
    check_classes = check_cls_of(name, cls)
    for x in f:
        if x not in t:
            errors.append(f"{name}: fused type {x} is not among its types")
        # An identity needs no coverage row (pair_credit); TIME and DECIMAL to themselves are not.
        target = CAST_ALIASES.get(name)
        if x in derived or (fused_by_matrix and target == x and cast_kind(x, x)[0] == "identity"):
            continue
        if not any(x in cov.get(c, set()) for c in check_classes):
            classes = " or ".join(check_classes)
            errors.append(f"{name}: no coverage row has a {classes} node over a {x} operand")
    for x in partial:
        if x not in t:
            errors.append(f"{name}: type {x} fused in part is not among its types")
        if x in f:
            errors.append(f"{name}: type {x} is both fused and fused in part")
        if not any(x in cov.get(c, set()) for c in check_classes):
            classes = " or ".join(check_classes)
            errors.append(f"{name}: no coverage row has a {classes} node over a {x} operand")
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
        ",".join(partial),
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
    "fused_in_part",
]


def render_rows(header, rows):
    return "\n".join("\t".join(r) for r in [header] + rows) + "\n"


def render(out):
    return render_rows(HEADER, out)


def decimal_representations():
    """The representations of `dec` each Catalyst class has a coverage row for: "long" where a row
    reads a column of precision 18 or less, "pair" where it reads one above (the lane pair).
    The census holds one `dec` type, so this is read off the columns a row names: a row that reads
    both counts for both, which over-credits the pair by the rows that mix them."""
    data = json.loads(read(COVERAGE))
    precision = {}
    for name, sql_type in data["columns"].items():
        m = re.match(r"decimal\((\d+)", sql_type)
        if m:
            precision[name] = int(m.group(1))
    reps = collections.defaultdict(set)
    for row in data["expressions"]:
        read_cols = {
            p
            for n, p in precision.items()
            if re.search(
                r"(?<![A-Za-z0-9_])" + re.escape(n) + r"(?![A-Za-z0-9_])", row["executable"]
            )
        }
        if not read_cols:
            continue
        found = {"long" if p <= 18 else "pair" for p in read_cols}
        for operand in row["operands"]:
            cls, t = operand.split(":")
            if t == "dec":
                reps[cls] |= found
    return reps


def decimal_representation_lines(scoped):
    """The summary lines that split the `dec` entries by the band of the coverage rows that
    exercise them: an entry counts for a band when a coverage row of one of the classes its claim
    is checked against (`check_cls_of`) reads a decimal column of that band. This is evidence of
    coverage, not of fusion: the census holds one `dec` type, so a fused claim does not say which
    representation it covers, and a row that reads both bands credits both."""
    reps = decimal_representations()
    admitted = [r for r in scoped if "dec" in r[5].split(",")]
    lines = ["decimal entries with a coverage row that reads a decimal column of the band:"]
    for rep, label in (("long", "up to 18, an unscaled long"), ("pair", "above 18, a lane pair")):
        n = sum(
            1 for r in admitted if any(rep in reps.get(c, set()) for c in check_cls_of(r[0], r[1]))
        )
        lines.append(f"  {label:28s} {n:4d} / {len(admitted)}")
    return lines


def pair_counts(scoped):
    """The (entry, type) pairs of the in-scope rows, per type: (admitted, fused today), each a
    Counter. The summary's "pairs by type" and the support table's by-type columns read this."""
    pairs = [(r, t) for r in scoped for t in r[5].split(",") if t]
    admitted = collections.Counter(t for _, t in pairs)
    fused = collections.Counter(t for r, t in pairs if t in r[7].split(","))
    return admitted, fused


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
    implicit_in = [r for r in implicit if r[3] == "in"]
    lines.append(f"  implicit: {len(implicit)} / {len(implicit_in)}")
    lines.append("")
    reasons = collections.Counter(r[4] for r in out if r[3] == "out")
    lines.append("excluded, by reason:")
    for reason, n in reasons.most_common():
        lines.append(f"  {n:4d}  {reason}")
    lines.append("")
    need = collections.Counter(r[9] for r in scoped)
    lines.append("in scope, by need: " + ", ".join(f"{k} {need.get(k, 0)}" for k in NEEDS))
    per_type, per_type_fused = pair_counts(scoped)
    total, total_fused = sum(per_type.values()), sum(per_type_fused.values())
    lines.append(f"(entry, type) pairs: {total}, fused today {total_fused}")
    lines.append("pairs by type (fused today / admitted):")
    for t in ORDER:
        tag = "new" if t in NEW else "existing"
        lines.append(f"  {t:7s} {tag:8s} {per_type_fused.get(t, 0):4d} / {per_type.get(t, 0)}")
    lines.extend(decimal_representation_lines(scoped))
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
            " exact rule up to precision 18 (Decimal.roundToNumeric over a long-backed value, as"
            " every SQL read of the Arrow cache and Varka's row views hand it; row 365) and by"
            " the double rule above it (a BigDecimal-backed value)",
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
    and the ANSI and TRY range check up to precision 18 are integer rules, the double rule above
    it is not (the owner's decision of 1 November 2026 that Varka follows vanilla Spark, whose
    every SQL read of the Arrow cache gives the exact rule below precision 19; VARKA-365.md 11)."""
    if src in ("bool", "byte", "short", "int", "date", "ym", "float"):
        return "exhaustion"
    if src == "dec" and dst in ("byte", "short", "int"):
        return (
            "mixed: SMT (dev/varka_prove.sh) for LEGACY's low bits and the exact ANSI and TRY"
            " range check up to precision 18, sampled regions and the fuzzer for the double rule"
            " above it"
        )
    if src in ("bigint", "dt", "time", "ntz", "dec") and dst not in ("float", "double"):
        return "SMT (dev/varka_prove.sh) for the integer rule, sampled regions and the fuzzer"
    return "sampled regions and the fuzzer"


def cast_rows(src, dst):
    lanes = {src, dst}
    # The decimal and interval casts both ways, and the coarser day-time interval casts, are row
    # 103's casts step (VARKA-103.md 8, settlement 5).
    if lanes in ({"dec", "ym"}, {"dec", "dt"}) or (src, dst) == ("dt", "dt"):
        return "103"
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
    # An identity is the optimizer's, and the register leaves it to this row (VARKA-332.md 0).
    if src == dst and src != "time":
        return "332"
    # What is left - TIME to an integral type and to a coarser TIME, and DATE to a number or
    # BOOLEAN under LEGACY - no row claims: VARKA-332.md section 7 suggests each one's home.
    return "unclaimed"


# What the compiler fuses, per form pair and mode: written by VarkaCastMatrixSuite, which compiles
# every legal cast between two forms of the thirteen types (each interval field range, three time
# precisions, two decimals) and fails when the compiler's answers move from the file (VARKA-486).
# casts.tsv's fused_today is derived from it, so a claim cannot drift from the compiler.
CASTS_FUSED = "sql/varka/plans/m8/casts_fused.tsv"
FUSED_MODES = ["legacy", "ansi", "try"]

# The pairs that fuse only inside another operator, never as a bare cast over a column, each with
# the coverage row that shows it. The probe must say the pair declines; one that starts to fuse
# bare leaves this list.
CONTEXT_CASTS = {
    ("int", "dt"): "only as date_add's offset: d + CAST(i AS INTERVAL DAY)",
    ("dec", "dec"): "only a comparison's operand widened at one scale: d9 < d18, d9 = 0.42",
}

# The pairs that fuse only from a form the coverage table has no column of, so that no row over
# its columns can cast them as a plan does: the probe holds every form, and a row writing the cast
# over a literal shows nothing, since the optimizer folds it. Each must still fuse in the probe;
# one that a coverage row comes to cast leaves this list.
UNSHOWN_CASTS: dict[tuple[str, str], str] = {}


def casts_fused(root=ROOT):
    """Per (from, to) and per mode: how many of its form pairs are legal, and how many fuse.

    >>> import tempfile
    >>> with tempfile.TemporaryDirectory() as d:
    ...     path = os.path.join(d, CASTS_FUSED)
    ...     os.makedirs(os.path.dirname(path))
    ...     with open(path, "w") as f:
    ...         _ = f.write("from\\tto\\tfrom_type\\tto_type\\tlegacy\\tansi\\ttry\\n")
    ...         _ = f.write("ym\\tint\\tY\\tINT\\tfused\\tfused\\tfused\\n")
    ...         _ = f.write("ym\\tint\\tYM\\tINT\\tdeclined\\tdeclined\\tdeclined\\n")
    ...     casts_fused(d)
    {('ym', 'int'): ({'legacy': 2, 'ansi': 2, 'try': 2}, {'legacy': 1, 'ansi': 1, 'try': 1})}
    """
    out = {}
    with open(os.path.join(root, CASTS_FUSED), encoding="utf-8") as f:
        lines = f.read().splitlines()
    for line in lines[1:]:
        src, dst, _, _, *answers = line.split("\t")
        legal_forms, fused = out.get((src, dst), ({m: 0 for m in FUSED_MODES}, {}))
        fused = fused or dict.fromkeys(FUSED_MODES, 0)
        for mode, answer in zip(FUSED_MODES, answers):
            legal_forms[mode] += answer != "illegal"
            fused[mode] += answer == "fused"
        out[(src, dst)] = (legal_forms, fused)
    return out


def fused_cells(probe, src, dst):
    """casts.tsv's fused_today and fused_forms for one pair: the modes in which some form fuses,
    and "all" when every form legal in each of them fuses there, else how many do per mode
    ("2 of 3", or "LEGACY 2 of 3, ANSI 1 of 3" where the modes differ)."""
    if (src, dst) in CONTEXT_CASTS:
        return "", "context: " + CONTEXT_CASTS[(src, dst)]
    legal_forms, fused = probe.get((src, dst), ({}, {}))
    modes = [m for m in FUSED_MODES if fused.get(m)]
    if not modes:
        return "", ""
    if all(fused[m] == legal_forms[m] for m in modes):
        return ",".join(m.upper() for m in modes), "all"
    counts = {m: f"{fused[m]} of {legal_forms[m]}" for m in modes}
    forms = (
        next(iter(counts.values()))
        if len(set(counts.values())) == 1
        else ", ".join(f"{m.upper()} {c}" for m, c in counts.items())
    )
    return ",".join(m.upper() for m in modes), forms


# The mode a coverage row's cast runs in, to the legal() answer that admits it.
MODE_LEGAL = {"legacy": 0, "ansi": 1, "try": 1}


def pair_credit(probe, cov, src, dst, modes):
    """How far the cast src -> dst fuses under the given modes: None where it is legal in none of
    them, "all" where every form legal in each mode that admits it fuses there and a coverage row
    casts it, "part" where some form fuses in some such mode and a coverage row casts it, else "".
    An identity (cast_kind's) is "all" with no fused form and no coverage row: the analyzer removes
    it before a cast exists, so it never reaches Varka (the owner's decision, 11 November 2026). A
    pair that fuses only inside another operator (CONTEXT_CASTS) is "".

    >>> probe = {("ym", "int"): ({"legacy": 3, "ansi": 3, "try": 3},
    ...                          {"legacy": 2, "ansi": 2, "try": 2}),
    ...          ("bool", "byte"): ({"legacy": 1, "ansi": 1, "try": 1},
    ...                             {"legacy": 1, "ansi": 1, "try": 1}),
    ...          ("byte", "int"): ({"legacy": 1, "ansi": 1, "try": 1},
    ...                            {"legacy": 1, "ansi": 0, "try": 0})}
    >>> cov = {"#casts": {("ym", "int", "ansi"), ("bool", "byte", "ansi"), ("byte", "int", "ansi")}}
    >>> [pair_credit(probe, cov, s, d, ("legacy", "ansi"))
    ...  for s, d in [("bool", "byte"), ("ym", "int"), ("byte", "int"), ("bool", "bool")]]
    ['all', 'part', 'part', 'all']
    >>> [pair_credit(probe, {"#casts": set()}, s, d, ("legacy", "ansi"))
    ...  for s, d in [("bool", "byte"), ("int", "int")]]
    ['', 'all']
    >>> pair_credit(probe, cov, "date", "int", ("try",)) is None
    True
    """
    admitted = [m for m in modes if legal(src, dst)[MODE_LEGAL[m]]]
    if not admitted:
        return None
    shown = any((src, dst) == (s, d) for s, d, _ in cov["#casts"])
    if cast_kind(src, dst)[0] == "identity":
        return "all"
    if (src, dst) in CONTEXT_CASTS or not shown:
        return ""
    legal_forms, fused = probe.get((src, dst), ({}, {}))
    if all(0 < fused.get(m, 0) == legal_forms.get(m) for m in admitted):
        return "all"
    return "part" if any(fused.get(m) for m in admitted) else ""


def matrix_fused(name, sources, probe, cov):
    """A cast entry's fused types, in the curated file's notation, derived from the compiler's
    cast matrix (casts_fused.tsv) rather than curated, so the credit cannot drift from it
    (VARKA-606). The entry counts by source type (m8/PLAN.md 2.1): a source counts fused when
    every pair from it the entry can run is "all" under the modes the entry runs in
    (CAST_ENTRY_MODES), and fused in part (`^`) when it is not but some such pair fuses at least
    in part. An alias runs one pair from each source, to its target; `cast` and `try_cast` run
    every legal pair from it. An identity counts fused (pair_credit), so an alias credits its own
    type, and the identity from a source neither blocks it for `cast` and `try_cast` nor, alone,
    marks it fused in part.

    >>> matrix_fused("tinyint", ["bool", "short", "int", "bigint", "float"], {
    ...     ("bool", "byte"): ({"legacy": 1, "ansi": 1}, {"legacy": 1, "ansi": 1}),
    ...     ("short", "byte"): ({"legacy": 1, "ansi": 1}, {"legacy": 1, "ansi": 0})},
    ...     {"#casts": {("bool", "byte", "ansi"), ("short", "byte", "ansi")}})
    'bool,short^'
    >>> matrix_fused("tinyint", ["byte"], {}, {"#casts": set()})  # its identity
    'byte'
    >>> matrix_fused("try_cast", ["bool"], {}, {"#casts": set()})
    ''
    """
    modes = CAST_ENTRY_MODES[name]
    dsts = [CAST_ALIASES[name]] if name in CAST_ALIASES else ORDER
    out = []
    for src in sources:
        credits = {dst: pair_credit(probe, cov, src, dst, modes) for dst in dsts}
        credits = {dst: c for dst, c in credits.items() if c is not None}
        if credits and all(c == "all" for c in credits.values()):
            out.append(src)
        elif any(c for dst, c in credits.items() if cast_kind(src, dst)[0] != "identity"):
            out.append(src + "^")
    return ",".join(out)


def cast_errors(cov, probe):
    """Comet's rule over the probe and the coverage table, per mode: a pair the compiler fuses has
    a coverage row casting it, unless it is on UNSHOWN_CASTS, a pair no fixture column can show,
    which stays on that list only while that holds; a row's cast is legal in its mode and not an
    identity, which the analyzer removes before a cast exists; and a pair a row casts fuses in
    that mode, or fuses only inside another operator (CONTEXT_CASTS), where the probe must find it
    declined."""
    errors = []
    cast = cov["#casts"]
    for src, dst, mode in sorted(cast):
        if mode not in MODE_LEGAL or not legal(src, dst)[MODE_LEGAL[mode]]:
            errors.append(f"a coverage row casts {src} to {dst} under {mode}, where it is illegal")
        elif cast_kind(src, dst)[0] == "identity":
            errors.append(f"a coverage row casts {src} to {dst}, an identity")
        elif (src, dst) not in CONTEXT_CASTS and not probe.get((src, dst), ({}, {}))[1].get(mode):
            errors.append(f"a coverage row casts {src} to {dst} under {mode}, which never fuses")
    for (src, dst), (_, fused) in sorted(probe.items()):
        shown = any((src, dst) == (s, d) for s, d, _ in cast)
        if any(fused.values()) and not shown and (src, dst) not in UNSHOWN_CASTS:
            errors.append(f"the compiler fuses cast {src} to {dst} and no coverage row casts it")
        if (src, dst) in UNSHOWN_CASTS and (shown or not any(fused.values())):
            errors.append(f"cast {src} to {dst}: take it off UNSHOWN_CASTS")
        if (src, dst) in CONTEXT_CASTS and any(fused.values()):
            errors.append(f"cast {src} to {dst} fuses bare: take it off CONTEXT_CASTS")
    for src, dst in CONTEXT_CASTS:
        if not any((src, dst) == (s, d) for s, d, _ in cast):
            errors.append(f"cast {src} to {dst} is in CONTEXT_CASTS and no coverage row casts it")
    for src, dst in UNSHOWN_CASTS:
        if (src, dst) not in probe:
            errors.append(f"cast {src} to {dst} is in UNSHOWN_CASTS and the probe never emits it")
    return errors


# The verdicts of Spark's cast suites run under Varka, per suite, form pair and mode, written by
# the VarkaCastWithAnsiOffSuite, VarkaCastWithAnsiOnSuite and VarkaTryCastSuite subclasses
# (VARKA-368): a second witness beside the matrix, over the forms Spark's own tests reach.
CASTS_SUITES = "sql/varka/plans/m8/casts_suites.tsv"


def casts_suites(root=ROOT):
    """The record's lines as (suite, from, to, from_type, to_type, mode, verdict, reason), or
    none when the file is absent."""
    path = os.path.join(root, CASTS_SUITES)
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as f:
        return [tuple(line.split("\t")) for line in f.read().splitlines()[1:] if line]


def fused_forms(root=ROOT):
    """casts_fused.tsv's answer per (from_type, to_type, mode): fused, declined or illegal."""
    out = {}
    with open(os.path.join(root, CASTS_FUSED), encoding="utf-8") as f:
        for line in f.read().splitlines()[1:]:
            _, _, src, dst, *answers = line.split("\t")
            for mode, answer in zip(FUSED_MODES, answers):
                out[(src, dst, mode)] = answer
    return out


def suites_errors(lines, forms, probe):
    """The suites' record against the matrix (VARKA-368 2.7). A form casts_fused.tsv calls fused
    in a mode, which the suites exercise bare in that mode, is fused there in the record; and a
    pair whose every form fuses in a mode ("all" in casts.tsv's fused_forms) has no form, inside
    the matrix or outside it, that the suites find declined in that mode. A cast of a form to
    itself is not held to the pair's claim: the analyzer removes it before a cast exists, so the
    matrix never asks about it. Returns (errors, warnings); a warning is a form the suites find
    fused that the matrix calls declined, which can only mean the two compiled different
    expressions.

    >>> forms = {("INT", "BIGINT", "ansi"): "fused", ("INT", "SMALLINT", "ansi"): "declined"}
    >>> probe = {("int", "bigint"): ({"ansi": 1}, {"ansi": 1})}
    >>> def line(f, t, v):
    ...     return ("S", "int", "bigint", f, t, "ansi", v, "" if v == "fused" else "why")
    >>> e, w = suites_errors([line("INT", "BIGINT", "declined")], forms, probe)
    >>> e[0].startswith("S: CAST(INT AS BIGINT) in ansi is fused in casts_fused.tsv"), w
    (True, [])
    >>> e, w = suites_errors([line("INT", "INTx", "declined")], forms, probe)
    >>> "every form of int to bigint fuses in ansi: why" in e[0]
    True
    >>> suites_errors([line("INT", "INT", "declined")], forms, probe)
    ([], [])
    >>> suites_errors([line("INT", "SMALLINT", "fused")], forms, probe)[1][0].endswith("tsv")
    True
    """
    errors, warnings = [], []
    for suite, src, dst, src_type, dst_type, mode, verdict, reason in lines:
        cast = f"{suite}: CAST({src_type} AS {dst_type}) in {mode}"
        matrix = forms.get((src_type, dst_type, mode))
        if verdict != "fused" and matrix == "fused":
            errors.append(
                f"{cast} is fused in casts_fused.tsv, and the suites find it {verdict}: " + reason
            )
        elif verdict == "fused" and matrix == "declined":
            warnings.append(f"{cast} is fused in the suites and declined in casts_fused.tsv")
        elif (
            verdict != "fused"
            and matrix is None
            and src_type != dst_type
            and "-"
            not in (
                src,
                dst,
            )
        ):
            modes, claim = fused_cells(probe, src, dst)
            if claim == "all" and mode.upper() in modes.split(","):
                errors.append(
                    f"{cast} is {verdict} in the suites, and every form of {src} to "
                    f"{dst} fuses in {mode}: {reason}"
                )
    return errors, warnings


CAST_HEADER = ["from", "to", "legacy", "ansi", "try", "kind", "semantics", "proof"]
CAST_HEADER += ["fused_today", "rows", "fused_forms"]


def cast_matrix():
    probe = casts_fused()
    out = []
    for src in ORDER:
        for dst in ORDER:
            lg, an = legal(src, dst)
            if not (lg or an):
                continue
            kind, note = cast_kind(src, dst)
            if kind == "?":
                raise SystemExit(f"cast {src} -> {dst} is legal but has no kind")
            fused, forms = fused_cells(probe, src, dst)
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
                    forms,
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


# ---- the support table --------------------------------------------------------------------

# The users' table of the census (VARKA-353): a block of docs/sql-varka.md this script writes and
# --check holds to what it renders, beside the blocks VarkaCoverageSuite writes there.
DOCS = "docs/sql-varka.md"
SUPPORT = "support table"
SUPPORT_MARKS = (f"<!-- BEGIN generated {SUPPORT} -->\n", f"<!-- END generated {SUPPORT} -->\n")
REGEN = "python3 dev/varka_census.py"
# The two representations of a decimal (m8/PLAN.md 2.8): an unscaled long up to precision 18,
# a 128-bit lane pair above it.
BANDS = {"p18": "to precision 18", "p38": "precision 19 to 38"}
SIMPLE_COLUMN_TYPES = {
    "boolean": "bool",
    "tinyint": "byte",
    "smallint": "short",
    "int": "int",
    "bigint": "bigint",
    "float": "float",
    "double": "double",
    "date": "date",
    "timestamp_ntz": "ntz",
}


def column_type(declared):
    """The census type of a coverage column's declared type, and its band for a decimal, read
    from the type rather than the column's name.

    >>> [column_type(t) for t in ["decimal(18,2)", "decimal(19,2)", "interval day to second"]]
    [('dec', 'p18'), ('dec', 'p38'), ('dt', None)]
    >>> column_type("time(6)"), column_type("interval year")
    (('time', None), ('ym', None))
    >>> column_type("decimal(40,2)")
    Traceback (most recent call last):
    SystemExit: census: coverage column type 'decimal(40,2)' has no census type
    """
    d = declared.strip().lower()
    m = re.fullmatch(r"decimal\((\d+),\s*\d+\)", d)
    if m and 1 <= int(m.group(1)) <= 38:
        return "dec", "p18" if int(m.group(1)) <= 18 else "p38"
    if d in SIMPLE_COLUMN_TYPES:
        return SIMPLE_COLUMN_TYPES[d], None
    if re.fullmatch(r"time(\(\d\))?", d):
        return "time", None
    m = re.fullmatch(r"interval (year|month)( to month)?", d)
    if m:
        return "ym", None
    if re.fullmatch(r"interval (day|hour|minute|second)( to (hour|minute|second))?", d):
        return "dt", None
    raise SystemExit(f"census: coverage column type {declared!r} has no census type")


def columns_read(sql, columns):
    """The columns a coverage row's SQL names: identifiers outside its quoted literals that are
    column names, so `d` in `trunc(d, 'MM')` is read and `1L` names no `L`.

    >>> sorted(columns_read("d + INTERVAL '1 d' DAY < x2 AND 1L > l", {"d", "x2", "l", "L"}))
    ['d', 'l', 'x2']
    """
    bare = re.sub(r"'[^']*'|\"[^\"]*\"", " ", sql)
    return {w for w in re.findall(r"(?<![\w.])[A-Za-z_]\w*", bare) if w in columns}


def coverage_by_type(data):
    """Per census type, the coverage rows that read a column of it; `dec` is split by band under
    the keys "p18" and "p38", and a row reading both bands counts under "p38", the band whose
    lane pair it needs.

    >>> data = {"columns": {"d18": "decimal(18,2)", "d18b": "decimal(18,2)",
    ...                     "d38": "decimal(38,2)", "i": "int"},
    ...         "expressions": [{"executable": "d18 > 0"}, {"executable": "d38 IS NULL"},
    ...                         {"executable": "d18b < d38 AND i > 0"}]}
    >>> c = coverage_by_type(data)
    >>> c["dec"], c["p18"], c["p38"], c["int"]
    (3, 1, 2, 1)
    """
    columns = {name: column_type(t) for name, t in data["columns"].items()}
    counts = collections.Counter()
    for row in data["expressions"]:
        read = columns_read(row["executable"], columns)
        counts.update({columns[c][0] for c in read})
        bands = {columns[c][1] for c in read if columns[c][1]}
        if bands:
            counts["p38" if "p38" in bands else "p18"] += 1
    return counts


def owner_declined(r):
    """The types an in-scope entry's reason declines by the owner's decision."""
    m = re.match(r"declined ([\w,]+): ", r[4])
    return m.group(1).split(",") if m else []


def left_reason(r, t):
    """Why an in-scope entry leaves type `t` unfused, the class derived from its census row: fused
    in part (the `^` column), or owed to the rows it names, with the need beside them. None where
    `t` is fused. A type the owner declined is not among the entry's types at all (owner_declined).

    >>> row = ["x", "X", "s", "in", "", "int,dec", "", "int", "", "semantics", "359", "", ""]
    >>> left_reason(row, "dec"), left_reason(row, "int")
    ('owed to row 359 (semantics)', None)
    >>> left_reason(row[:12] + ["dec"], "dec")
    'fused in part'
    """
    if t in r[7].split(","):
        return None
    if t in r[12].split(","):
        return "fused in part"
    rows = [x for x in r[10].split(",") if x]
    if not rows:
        return f"owed, no row named ({r[9]})"
    return f"owed to row{'s' if len(rows) > 1 else ''} {', '.join(rows)} ({r[9]})"


def row_key(x):
    m = re.match(r"(\d+)(.*)", x)
    return (int(m.group(1)), m.group(2)) if m else (1 << 30, x)


def cell(text):
    return str(text).replace("|", "\\|")


def table(header, rows, align):
    out = ["| " + " | ".join(header) + " |", "| " + " | ".join(align) + " |"]
    out += ["| " + " | ".join(cell(c) for c in r) + " |" for r in rows]
    return "\n".join(out) + "\n"


def lanes_of(t):
    """A census type's lanes as the type registry states them, its words per value by band."""
    r = next(r for r in types_rows() if r["census"] == t)
    if r["lanes"] == "-":
        return r["storage"]
    if ":" in r["words"]:
        words = [w.split(":") for w in r["words"].split(",")]
        by = ", ".join(f"{n} to precision {p[1:]}" for p, n in words)
        return f"{r['lanes'].replace(',', ', ')}, words per value: {by}"
    return r["lanes"].replace(",", ", ")


def by_type(scoped, cov, order=None):
    """The by-type table's rows: one per census type, in registry order.

    >>> scoped = [
    ...     ["=", "EqualTo", "s", "in", "", "int,dec", "", "int", "", "plumbing", "348", "", "dec"],
    ...     ["/", "Divide", "s", "in", "declined dec: x", "int", "", "", "", "semantics", "359",
    ...      "", ""]]
    >>> for r in by_type(scoped, {"int": 4, "dec": 3, "p18": 1, "p38": 2}, ["int", "dec"]):
    ...     print(r[2:])
    [2, 1, 0, 'semantics 1', 0, 4]
    [1, 0, 1, '', 1, '3: 1 to precision 18, 2 at precision 19 to 38']
    """
    spark = {r["census"]: r["spark"] for r in types_rows()}
    admitted, fused = pair_counts(scoped)
    out = []
    for t in order or ORDER:
        reasons = [(r, left_reason(r, t)) for r in scoped if t in r[5].split(",")]
        part = sum(why == "fused in part" for _, why in reasons)
        left = collections.Counter(r[9] for r, why in reasons if why and why != "fused in part")
        rows = cov.get(t, 0)
        if t == "dec":
            p18, p38 = cov.get("p18", 0), cov.get("p38", 0)
            rows = f"{rows}: {p18} {BANDS['p18']}, {p38} at {BANDS['p38']}"
        out.append(
            [
                f"`{spark.get(t, t)}` (`{t}`)",
                lanes_of(t),
                admitted.get(t, 0),
                fused.get(t, 0),
                part,
                ", ".join(f"{k} {n}" for k, n in sorted(left.items())),
                sum(t in owner_declined(r) for r in scoped),
                rows,
            ]
        )
    return out


def family_name(section):
    if section.startswith("implicit: "):
        return section[len("implicit: ") :] + ", no registry entry"
    return section.removesuffix("Expressions")


def by_family(scoped):
    """The by-family table's rows: one per census section, in census order.

    >>> scoped = [
    ...     ["a", "A", "mathExpressions", "in", "", "int,double", "", "int,double", "", "done",
    ...      "", "", ""],
    ...     ["b", "B", "mathExpressions", "in", "", "int,dec", "", "int", "", "semantics",
    ...      "359,348", "", ""]]
    >>> by_family(scoped)
    [['math', 2, 1, 2, 0, 'dec', '348, 359']]
    """
    out = []
    for section in dict.fromkeys(r[2] for r in scoped):
        rows = [r for r in scoped if r[2] == section]
        owed = {t for r in rows for t in r[5].split(",") if left_reason(r, t)}
        named = {x for r in rows if r[9] != "done" for x in r[10].split(",") if x}
        out.append(
            [
                family_name(section),
                len(rows),
                sum(r[9] == "done" for r in rows),
                sum(bool(r[7]) for r in rows),
                sum(bool(r[12]) for r in rows),
                ", ".join(t for t in ORDER if t in owed),
                ", ".join(sorted(named, key=row_key)),
            ]
        )
    return out


# The bound a math function computes to under spark.sql.codegen.varka.math.ulpContract.enabled,
# per host library, is row 367's contract; until it commits one, every function reads this.
NO_CONTRACT = "exact; no contract"
CONTRACT_ROW = "367"


def math_rows(scoped):
    """The math functions whose departure from Spark's answer would need a ulp contract (need
    `ulp`), grouped by the rows that fuse them: their names, the rows, the types fused today, the
    answer by default and under the opt-in mode.

    >>> scoped = [["sin", "Sin", "m", "in", "", "double", "", "", "", "ulp", "367,356", "", ""],
    ...           ["cos", "Cos", "m", "in", "", "double", "", "", "", "ulp", "367,356", "", ""]]
    >>> math_rows(scoped)
    [['`sin`, `cos`', '356', 'none', "Spark's, bit for bit", 'exact; no contract']]
    """
    groups = {}
    for r in scoped:
        if r[9] == "ulp":
            rows_of = (x for x in r[10].split(",") if x and x != CONTRACT_ROW)
            key = ", ".join(sorted(rows_of, key=row_key))
            groups.setdefault(key, []).append(r)
    out = []
    for key, rows in sorted(groups.items(), key=lambda kv: row_key(kv[0])):
        fused = {t for r in rows for t in r[7].split(",") if t}
        out.append(
            [
                ", ".join(f"`{r[0]}`" for r in rows),
                key,
                ", ".join(t for t in ORDER if t in fused) or "none",
                "Spark's, bit for bit",
                NO_CONTRACT,
            ]
        )
    return out


def form_label(form):
    """A cast form for a footnote, a decimal by its band rather than its probe precision."""

    def band(m):
        return "DECIMAL " + BANDS["p18" if int(m.group(1)) <= 18 else "p38"]

    return re.sub(r"DECIMAL\((\d+),\s*\d+\)", band, form)


def cast_forms(root=ROOT):
    """Per (from, to) and mode, the declined forms of casts_fused.tsv as "FROM to TO"."""
    out = collections.defaultdict(list)
    with open(os.path.join(root, CASTS_FUSED), encoding="utf-8") as f:
        for line in f.read().splitlines()[1:]:
            src, dst, from_type, to_type, *answers = line.split("\t")
            for mode, answer in zip(FUSED_MODES, answers):
                if answer == "declined":
                    label = f"{form_label(from_type)} to {form_label(to_type)}"
                    if label not in out[(src, dst, mode)]:
                        out[(src, dst, mode)].append(label)
    return out


def cast_cell(probe, shown, src, dst, mode):
    """One cell of a mode's cast matrix, by that mode's own legality: blank where the mode rejects
    the pair, `=` for an identity, `context` for a pair that fuses only inside another operator
    where a coverage row casts it in this mode (`shown`, coverage_index's "#casts"), else
    `fused`, `declined` or `k of n` forms.

    >>> probe = {("dt", "dt"): ({"ansi": 2}, {"ansi": 1}), ("date", "int"): ({"legacy": 1}, {}),
    ...          ("dec", "dec"): ({"ansi": 2, "try": 2}, {"ansi": 0, "try": 0})}
    >>> shown = {("dec", "dec", "ansi")}
    >>> [cast_cell(probe, shown, *p) for p in [("dt", "dt", "ansi"), ("date", "int", "legacy")]]
    ['1 of 2', 'declined']
    >>> [cast_cell(probe, shown, "date", "int", m) for m in ("ansi", "try")]
    ['', '']
    >>> [cast_cell(probe, shown, *p) for p in [("int", "int", "try"), ("dec", "dec", "ansi"),
    ...                                        ("dec", "dec", "try")]]
    ['=', 'context', 'declined']
    >>> cast_cell({}, shown, "int", "bigint", "ansi")
    Traceback (most recent call last):
    SystemExit: census: cast int to bigint is legal under ansi and the probe has no form of it
    """
    if not legal(src, dst)[MODE_LEGAL[mode]]:
        return ""
    if cast_kind(src, dst)[0] == "identity":
        return "="
    if (src, dst) in CONTEXT_CASTS and (src, dst, mode) in shown:
        return "context"
    legal_forms, fused = probe.get((src, dst), ({}, {}))
    n, k = legal_forms.get(mode, 0), fused.get(mode, 0)
    if not n:
        raise SystemExit(
            f"census: cast {src} to {dst} is legal under {mode} and the probe has no form of it"
        )
    return "fused" if k == n else "declined" if not k else f"{k} of {n}"


def cast_tables(probe, shown, forms):
    """The three cast matrices, each under its own `<details>`, with a footnote per cell fused
    in part naming the cast's kind and its declined forms."""
    out = []
    for mode in FUSED_MODES:
        rows, notes = [], []
        for src in ORDER:
            cells = [cast_cell(probe, shown, src, dst, mode) for dst in ORDER]
            rows.append([f"`{src}`"] + cells)
            for dst, c in zip(ORDER, cells):
                if " of " in c:
                    declined = "; ".join(forms[(src, dst, mode)])
                    kind = cast_kind(src, dst)[0]
                    notes.append(f"* `{src}` to `{dst}` ({kind}), {c} forms; declined: {declined}")
        out.append(f'<details markdown="1">\n<summary>{mode.upper()}</summary>\n')
        out.append(table(["from \\ to"] + [f"`{t}`" for t in ORDER], rows, [":---"] * 14))
        if notes:
            out.append("\n" + "\n".join(notes) + "\n")
        out.append("\n</details>\n")
    return "\n".join(out)


def support_block(out, cov, probe, forms):
    """The text between the support table's markers."""
    scoped = [r for r in out if r[3] == "in"]
    parts = [
        f"Written by `dev/varka_census.py` from the census (`{OUT}`, `{CASTS_OUT}`), the"
        f" compiler's cast probe (`{CASTS_FUSED}`) and `{COVERAGE}`; regenerate it with"
        f" `{REGEN}`, and `{REGEN} --check` fails while it is stale.\n",
        "#### By type\n",
        "An entry is a function or operator of the census; a type counts *fused* for it only"
        " when every form of the entry over that type fuses, and *in part* when some form does."
        " *Left, by need* is the rest by what fusing it takes (`plumbing`, a compiler arm and IR on"
        " lanes that exist; `semantics`, Spark's overflow, NaN, rounding or scale rules; `ulp`, a"
        " math function exact to Spark's answer); the family table names the plan rows that"
        " owe them. *Declined* are the entries the owner's decision keeps off the type."
        " *Coverage rows* are the rows of the coverage table below that read a column of the"
        " type; a row reading decimals of both bands counts at 19 to 38, whose lane pair it"
        " needs.\n",
        table(
            ["Type", "Lanes", "Entries", "Fused", "In part", "Left, by need", "Declined"]
            + ["Coverage rows"],
            by_type(scoped, cov),
            [":---", ":---"] + ["---:"] * 3 + [":---", "---:", ":---"],
        ),
        "#### By family\n",
        "One row per section of Spark's function registry, and per family of the operators the"
        " parser builds with no registry entry: its entries in scope, those fused for every type"
        " they admit, those fused for at least one, those fused in part for some type, the types"
        " still owed and the plan rows the unfinished entries name.\n",
        table(
            ["Family", "Entries", "Every type", "Some type", "In part", "Types owed", "Rows"],
            by_family(scoped),
            [":---"] + ["---:"] * 4 + [":---", ":---"],
        ),
        "#### Math functions\n",
        "The math functions that do not fuse yet, by the plan row that fuses them (the ones"
        " that fuse today are named above and compute Spark's answer exactly): what each will"
        " return by default once fused, and the bound it will compute to with"
        " `spark.sql.codegen.varka.math.ulpContract.enabled` true, which the host library's"
        f" contract (row {CONTRACT_ROW}) states.\n",
        table(
            ["Functions", "Row", "Fused for", "By default", "Under the ulp contract"],
            math_rows(scoped),
            [":---"] * 5,
        ),
        "#### Casts by mode\n",
        "One matrix per evaluation mode, rows the source type and columns the target, each cell"
        " by that mode's own legality: blank where the mode rejects the cast, `=` for an identity"
        " the analyzer removes, `context` where the cast fuses only inside another operator, as a"
        " coverage row (compiled under ANSI) shows, and"
        " otherwise how many of the probe's forms (interval unit pairs, decimal precisions and"
        " scales) fuse. LEGACY is the mode with `spark.sql.ansi.enabled` false, ANSI with it true,"
        " and TRY is `try_cast`.\n",
        cast_tables(probe, coverage_index()["#casts"], forms),
    ]
    return "\n".join(parts)


def splice(text, block):
    """`text` with the support table's block replaced by `block`.

    >>> b, e = SUPPORT_MARKS
    >>> splice("a\\n" + b + "old\\n" + e + "z\\n", "new\\n")
    'a\\n<!-- BEGIN generated support table -->\\nnew\\n<!-- END generated support table -->\\nz\\n'
    >>> splice("a\\n", "new\\n")
    Traceback (most recent call last):
    SystemExit: census: docs/sql-varka.md has no support table block
    """
    begin, end = SUPPORT_MARKS
    i, j = text.find(begin), text.find(end)
    if i < 0 or j < i or text.count(begin) != 1:
        raise SystemExit(f"census: {DOCS} has no {SUPPORT} block")
    return text[: i + len(begin)] + block + text[j:]


# Every landed type is held to the conformance contract (VARKA-484, VARKA-475.md 3.1): a suite
# Varka<Name>ConformanceSuite over VarkaTypeConformance, or the row that owes it, both named in
# the type registry (VarkaTypes' conformance). A landed type is one Varka reads today. A type the
# committed census marks fused anywhere has landed too, so a new type's registry entry must name
# its suite or owner when its first claim lands.
CONFORMANCE_DIR = (
    "sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka"
)
# Each landed type's conformance suite, `Varka<name>ConformanceSuite`, and the row that owes a
# suite not yet written: the registry's `conformance` and `conformance_row` columns.
CONFORMANCE_SUITE = {r["census"]: r["conformance"] for r in types_rows() if r["conformance"] != "-"}
CONFORMANCE_OWED = {
    r["census"]: r["conformance_row"] for r in types_rows() if r["conformance_row"] != "-"
}


def conformance_errors(root=ROOT):
    """A landed type with neither a conformance suite nor an owner, or an owner and a suite.

    >>> import tempfile
    >>> with tempfile.TemporaryDirectory() as d:
    ...     os.makedirs(os.path.join(d, CONFORMANCE_DIR))
    ...     for t in sorted(CONFORMANCE_SUITE.keys() - CONFORMANCE_OWED.keys() - {"short"}):
    ...         name = f"Varka{CONFORMANCE_SUITE[t]}ConformanceSuite.java"
    ...         open(os.path.join(d, CONFORMANCE_DIR, name), "w").close()
    ...     conformance_errors(d)
    ['short (SmallInt) is a landed type with no VarkaSmallIntConformanceSuite and no owing row']
    """
    errors = []
    if sorted(EXISTING + NEW) != sorted(ORDER):
        errors.append(f"EXISTING and NEW are not the registry's census types {ORDER}")
    census = os.path.join(root, OUT)
    if os.path.exists(census):
        with open(census) as f:
            rows = [line.rstrip("\n").split("\t") for line in f][1:]
        fused = {t for r in rows if len(r) > 7 for t in r[7].split(",") if t}
        for t in sorted(fused - CONFORMANCE_SUITE.keys()):
            errors.append(
                f"{t} is fused in the census and its VarkaTypes entry names no conformance"
            )
    for t, name in CONFORMANCE_SUITE.items():
        suite = f"Varka{name}ConformanceSuite"
        present = os.path.exists(os.path.join(root, CONFORMANCE_DIR, suite + ".java"))
        if not present and t not in CONFORMANCE_OWED:
            errors.append(f"{t} ({name}) is a landed type with no {suite} and no owing row")
        if present and t in CONFORMANCE_OWED:
            owner = CONFORMANCE_OWED[t]
            errors.append(f"{t} has {suite}: its VarkaTypes entry still says row {owner} owes it")
    return errors


def stale(files, committed):
    """For each rendered file whose committed text (`committed(path)`) differs, the message that
    names it and the command that rewrites it: a hand edit of one cell of the support table is a
    stale docs file.

    >>> b, e = SUPPORT_MARKS
    >>> fresh = "x\\n" + b + "| a | 1 |\\n" + e
    >>> stale({DOCS: fresh}, {DOCS: fresh}.get)
    []
    >>> stale({DOCS: fresh}, {DOCS: fresh.replace("| 1 |", "| 2 |")}.get)
    ['census: docs/sql-varka.md is stale; run dev/varka_census.py']
    """
    return [
        f"census: {path} is stale; run dev/varka_census.py"
        for path, text in files.items()
        if committed(path) != text
    ]


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true", help="fail if the committed file is stale")
    ap.add_argument("--summary", action="store_true", help="print the counts only")
    ap.add_argument("--self-test", action="store_true", help="run the doctests and stop")
    args = ap.parse_args()
    if args.self_test or args.check:
        import doctest

        failed, _ = doctest.testmod()
        if failed or args.self_test:
            return 1 if failed else 0
    out, errors = build()
    errors += cast_errors(coverage_index(), casts_fused())
    suite_errors, suite_warnings = suites_errors(casts_suites(), fused_forms(), casts_fused())
    errors += suite_errors
    for w in suite_warnings:
        print("census: warning: " + w, file=sys.stderr)
    errors += conformance_errors()
    if errors:
        for e in errors:
            print("census: " + e, file=sys.stderr)
        return 1
    casts = cast_matrix()
    probe = casts_fused()
    cov = coverage_by_type(json.loads(read(COVERAGE)))
    block = support_block(out, cov, probe, cast_forms())
    files = {OUT: render(out), CASTS_OUT: render_rows(CAST_HEADER, casts)}
    files[DOCS] = splice(read(DOCS), block)
    if args.check:
        found = stale(files, read)
        for message in found:
            print(message, file=sys.stderr)
        return 1 if found else 0
    if not args.summary:
        for path, text in files.items():
            with open(os.path.join(ROOT, path), "w", encoding="utf-8") as f:
                f.write(text)
    sys.stdout.write(summary(out))
    sys.stdout.write("\n" + cast_summary(casts))
    return 0


if __name__ == "__main__":
    sys.exit(main())
