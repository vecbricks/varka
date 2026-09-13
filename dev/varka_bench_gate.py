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
# Assert the invariants a benchmark results file must satisfy, whatever its
# numbers are.
#
#   dev/varka_bench_gate.py sql/catalyst/benchmarks/VarkaEmitterParityBenchmark-jdk25-results.txt
#   dev/varka_bench_gate.py --history          # every committed revision of every gated file
#   dev/varka_bench_gate.py --selftest
#
# Why a gate and not a row. Task 77 was opened because a kernel collapsed from
# 273 to 8.8 M rows/s at 128-bit and the number was committed without anyone
# remarking on it. A band cannot catch that - the collapse is far outside any
# band, but so is ordinary noise in the file's loudest cases, and a reader who
# has learned to discount large moves discounts this one too. What separates it
# is not its size but that it broke something that must be true: the fused
# kernel became slower than the sixty-four separate passes it exists to beat.
#
# So each pair below says one arm must beat another, and every pair was DERIVED
# rather than asserted: it holds in every committed revision of both widths of
# its file, 43 to 47 observations each. The single exception is the pair that
# catches the collapse, which holds everywhere except `aef0b82260e` and the two
# regenerations committed while it was still broken.
#
# Tables with no must-beat direction are listed in UNGATED with the reason, and
# a table in neither list fails the run: a section added later must be a
# decision, not an omission.

import pathlib
import re
import subprocess
import sys


def _usage(code: int = 2) -> None:
    """Print this file's header comment, the way the shell tools answer --help."""
    lines = pathlib.Path(__file__).read_text().splitlines()
    for line in lines[16:]:
        if not line.startswith("#"):
            break
        print(line[2:] if line.startswith("# ") else line[1:])
    sys.exit(code)


if "-h" in sys.argv[1:] or "--help" in sys.argv[1:]:
    _usage(0)

ROW = re.compile(r"^(.*?)\s+(\d+)\s+(\d+)\s+(\d+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)X\s*$")
HEADER = re.compile(r"^(.*?):\s+Best Time\(ms\)")

# (table, arm, reference): the arm must read a higher rate than the reference.
PARITY_PAIRS = [
    # An emitted loop at or above the hand-written kernel is this project's
    # standing parity gate (PLAN_MILESTONE_4.md section 5).
    ("date_add over 1000000 rows", "emitted loop, null-free", "hand-written kernel, null-free"),
    ("date_add over 1000000 rows", "emitted loop, mixed nulls", "hand-written kernel, mixed nulls"),
    ("datediff over 1000000 rows", "emitted loop, null-free", "hand-written kernel, null-free"),
    ("datediff over 1000000 rows", "emitted loop, mixed nulls", "hand-written kernel, mixed nulls"),
    # A fused chain against the same work as separate kernel passes.
    ("chain over 1000000 rows, mixed nulls", "fused, depth 1", "sequential kernels, depth 1"),
    ("chain over 1000000 rows, mixed nulls", "fused, depth 2", "sequential kernels, depth 2"),
    ("chain over 1000000 rows, mixed nulls", "fused, depth 4", "sequential kernels, depth 4"),
    ("chain over 1000000 rows, mixed nulls", "fused, depth 8", "sequential kernels, depth 8"),
    ("chain over 1000000 rows, mixed nulls", "fused, depth 16", "sequential kernels, depth 16"),
    ("two outputs over 1000000 rows, mixed nulls", "fused, CSE", "sequential kernels (9 passes)"),
    # The pair task 77 exists for.
    ("4 outputs x depth 16 over 1000000 rows", "fused, 64 ops", "sequential kernels, 64 passes"),
    # The shipped lowering against the two it replaced.
    (
        "dayofweek over 1000000 rows",
        "magic multiply (shipped), null-free",
        "lanewise DIV, null-free",
    ),
    (
        "dayofweek over 1000000 rows",
        "magic multiply (shipped), null-free",
        "per-row LocalDate (the path Spark uses today)",
    ),
]

PARITY_UNGATED = {
    "depth-4 arms over 1000000 rows": "a blend against plain arithmetic; either may win",
    "20000000 rows in 4096-row chunks": "A/B arms and option variants; no must-beat direction",
    "two outputs over a shared chain, 1000000 rows, mixed nulls": "the task 17 budget pair",
    "20000000 rows in chunks": "chunk-length variants of one shape",
    "20000000 rows, 20 calendar outputs over 5 dates": "shared against unshared epilogue",
    "one output over 1000000 rows, null-free": "the width ladder; every rung is a datum",
}

# The fused session against the Janino session, for every shape where Varka wins
# in every committed revision. The row-consumer shapes are not here: they lose by
# construction, which is the debt register's own finding.
# Every shape's fused session against its Janino session, for the shapes where
# Varka wins in every committed revision of both widths. Names are written
# without the suffix every table in this file carries.
THRU = " over 2000000 Arrow-cached rows"

THROUGHPUT_WINS = [
    "date_add",
    "date_sub",
    "date_add, column offset (task 56 control)",
    "date + CAST(i AS INTERVAL DAY), bound checked (task 56)",
    "add_months, literal (task 60 control)",
    "add_months, column count (task 60)",
    "make_date",
    "datediff",
    "nested projection",
    "shared subchain (DAG-CSE)",
    "case when, predictable data",
    "case when, unpredictable data",
    "next_day, literal weekday (task 59 control)",
    "next_day, weekday column (task 59)",
    "next_day, weekday column reused by two outputs (task 59)",
    "trunc, literal format (task 61 control)",
    "trunc, format column (task 61)",
    "weekofyear",
    "yearofweek",
    "year * 100 + month (task 63)",
    "datediff + 1 (task 63)",
    "try_add over datediff (task 63)",
    "mixed projection, arithmetic entry fused (task 63)",
    "mixed projection (partial fusion)",
    "chain depth 1",
    "chain depth 2",
    "chain depth 4",
    "chain depth 8",
    "dayofweek, row consumer",
    "case when unpredictable, row consumer",
    # New in task 68; one revision each, no loss, and six to eight times the row
    # engine at both widths, so the direction is not in doubt even on thin history.
    "interval add, int count (task 68 control)",
    "interval add, interval columns (task 68)",
    "month composite, int form (task 68 control)",
    "make_ym_interval (task 68)",
    "add_months, int count (task 67 control)",
    "d + interval column (task 67)",
]

THROUGHPUT_PAIRS = [(t + THRU, "varka (SIMD)", "baseline (Janino)") for t in THROUGHPUT_WINS]

# The row-consumer shapes lose by construction and the debt register says why, so
# they are listed rather than gated.
READ_BACK_FLOOR = [
    "chain depth 1, row consumer",
    "chain depth 2, row consumer",
    "chain depth 4, row consumer",
    "chain depth 8, row consumer",
    "date_add, row consumer",
    "mixed projection, row consumer",
    "residual-heavy projection, row consumer",
    "datediff, row consumer",
    "nested projection, row consumer",
]

THROUGHPUT_UNGATED = {
    t + THRU: "the read-back floor; every selected row crosses it (task 19)"
    for t in READ_BACK_FLOOR
}
THROUGHPUT_UNGATED["dayofweek" + THRU] = "not an invariant: 1 loss in 27 revisions, at an early one"


# Tables that a committed revision holds and the current file does not. They are
# listed so that --history reads clean: a walk back through the record should
# report broken invariants, not the ordinary fact that sections get renamed.
RETIRED = {
    "mixed projection (fallback) over 2000000 Arrow-cached rows",
}


def parse(text):
    """[(table, case, occurrence, rate, relative)] in file order."""
    rows, table, seen = [], "", {}
    for line in text.splitlines():
        line = re.sub(r"^\[info\] ?", "", line)
        h = HEADER.match(line)
        if h:
            table = h.group(1).strip()
            continue
        m = ROW.match(line)
        if m:
            case = m.group(1).strip()
            seen[(table, case)] = seen.get((table, case), 0) + 1
            rows.append((table, case, seen[(table, case)], float(m.group(5)), float(m.group(7))))
    return rows


def rules_for(path):
    if "ParityBenchmark" in path:
        return PARITY_PAIRS, PARITY_UNGATED
    if "ThroughputBenchmark" in path:
        return THROUGHPUT_PAIRS, THROUGHPUT_UNGATED
    return None, None


def check(path, text):
    """[failure strings]; empty when the file satisfies its invariants."""
    pairs, ungated = rules_for(path)
    if pairs is None:
        return []
    rows = parse(text)
    rate = {(t, c, o): r for t, c, o, r, _ in rows}
    tables = list(dict.fromkeys(t for t, _, _, _, _ in rows))
    bad = []
    for table, arm, ref in pairs:
        ka, kr = (table, arm, 1), (table, ref, 1)
        if ka not in rate or kr not in rate:
            continue
        if rate[ka] <= rate[kr]:
            bad.append(
                f"{table}: {arm!r} at {rate[ka]:.1f} does not beat {ref!r} at {rate[kr]:.1f}"
            )
    # A table in neither list is an omission, not a pass.
    covered = {t for t, _, _ in pairs} | set(ungated) | RETIRED
    for table in tables:
        if table not in covered:
            bad.append(
                f"{table}: new table, neither gated by a pair nor listed as ungated; "
                f"decide which and add it to dev/varka_bench_gate.py"
            )
    return bad


GATED_FILES = [
    "sql/catalyst/benchmarks/VarkaEmitterParityBenchmark-jdk25-results.txt",
    "sql/catalyst/benchmarks/VarkaEmitterParityBenchmark-jdk25-128bit-results.txt",
    "sql/core/benchmarks/VarkaThroughputBenchmark-jdk25-results.txt",
    "sql/core/benchmarks/VarkaThroughputBenchmark-jdk25-128bit-results.txt",
]


def history():
    """Every committed revision of every gated file. Prints the ones that fail."""
    failures = 0
    for path in GATED_FILES:
        revs = [
            line
            for line in subprocess.run(
                ["git", "log", "--format=%h %ad", "--date=short", "--reverse", "--", path],
                check=True,
                capture_output=True,
                text=True,
            ).stdout.split("\n")
            if line.strip()
        ]
        print(f"== {path}: {len(revs)} revisions")
        for line in revs:
            rev, date = line.split(None, 1)
            try:
                text = subprocess.run(
                    ["git", "show", f"{rev}:{path}"],
                    check=True,
                    capture_output=True,
                    text=True,
                ).stdout
            except subprocess.CalledProcessError:
                continue
            bad = check(path, text)
            if bad:
                failures += 1
                print(f"   {rev} {date}")
                for b in bad:
                    print(f"       {b}")
    print(f"\n{failures} revision(s) fail the gate")
    return failures


SELFTEST = """\
OpenJDK 64-Bit Server VM
AMD Ryzen
4 outputs x depth 16 over 1000000 rows:  Best Time(ms)   Avg Time(ms)
------------------------------------------------------------------
fused, 64 ops                     0 1 0 2048.4 0.5 1.0X
sequential kernels, 64 passes    32 33 0   30.8 32.4 0.0X
"""


def selftest():
    path = "sql/catalyst/benchmarks/VarkaEmitterParityBenchmark-jdk25-results.txt"
    assert check(path, SELFTEST) == [], "a healthy pair must pass"
    # The collapse: the fused arm falls under the passes it exists to beat.
    broken = SELFTEST.replace("2048.4", "8.8").replace("1.0X", "1.0X", 1)
    bad = check(path, broken)
    assert len(bad) == 1 and "does not beat" in bad[0], bad
    # An unknown table is an omission, not a pass.
    unknown = SELFTEST.replace("4 outputs x depth 16 over 1000000 rows", "brand new section")
    bad = check(path, unknown)
    assert any("new table" in b for b in bad), bad
    print("varka_bench_gate selftest: ok")


def main():
    args = sys.argv[1:]
    if "--selftest" in args:
        selftest()
        return 0
    if "--history" in args:
        history()
        return 0
    if not args:
        args = GATED_FILES
    failures = 0
    for path in args:
        with open(path, encoding="utf-8") as f:
            bad = check(path, f.read())
        if bad:
            failures += len(bad)
            print(f"{path}:")
            for b in bad:
                print(f"   {b}")
        else:
            print(f"{path}: ok")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
