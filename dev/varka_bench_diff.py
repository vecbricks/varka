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
"""Compare Spark benchmark results files row by row, the way the Varka plans do.

Two modes:

  dev/varka_bench_diff.py OLD NEW              # before/after: same case, two files
  dev/varka_bench_diff.py --git REV FILE       # before = FILE at git revision REV
  dev/varka_bench_diff.py --within FILE --ab "Julian map" "century-then-year"
                                               # A/B: pairs of rows in one file whose
                                               # names differ only by those two labels
  dev/varka_bench_diff.py --git REV FILE --requote
                                               # under each moved row, every document
                                               # line that quotes its old number

Rows are matched by (table, case name), where the table is the name on each
results table's header line ("date_add over 1000000 rows:  Best Time(ms) ...") -
present both in the generated files and in a plain run's stdout, which the
128-bit companion files are made from. The rate column (M rows/s,
computed by Spark's Benchmark from the best time) is what is compared, since
that is what every plan quotes. Rows moving by at least --threshold percent
are marked; rows matching --control (the scalar anchors) are listed first,
because if they moved the machine moved and nothing else in the file can be
read. Exit status 0 always; this is a reading aid, not a gate.

--table LABEL=FILE ... prints the date-surface table (task 62): one row per
entry and shape, every distribution's wall rate and the last one's ratio
against each of the others, then the executor-time tables the same way.

--requote turns a regeneration's diff into the requoting list: for every moved
row it searches the documents the quote checker covers (the plans, SKILLS.md,
the docs, the README) for the old number and prints each line that quotes it.
A regeneration is not finished until that list is empty or every remaining
line says on purpose that it quotes the number a change moved away from.
"""

import argparse
import glob
import os
import re
import subprocess

DOCS = ["SKILLS.md", "README.md", "docs/sql-varka.md", "sql/varka/AGENTS.md"]
DOC_GLOBS = ["sql/varka/plans/*.md"]

ROW = re.compile(r"^(.*?)\s+(\d+)\s+(\d+)\s+(\d+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)X\s*$")
HEADER = re.compile(r"^(.*?):\s+Best Time\(ms\)")


def parse(text):
    """{(table, case): rate} plus the ordered list of keys, in file order."""
    rates, order, table = {}, [], ""
    for line in text.splitlines():
        line = re.sub(r"^\[info\] ?", "", line)
        h = HEADER.match(line)
        if h:
            table = h.group(1).strip()
            continue
        m = ROW.match(line)
        if m:
            key = (table, m.group(1).strip())
            rates[key] = float(m.group(5))
            order.append(key)
    return rates, order


def read(path, rev=None):
    if rev is None:
        with open(path, encoding="utf-8") as f:
            return f.read()
    return subprocess.run(
        ["git", "show", f"{rev}:{path}"], check=True, capture_output=True, text=True
    ).stdout


def pct(before, after):
    return (after / before - 1.0) * 100.0 if before else float("nan")


def repo_root():
    return subprocess.run(
        ["git", "rev-parse", "--show-toplevel"], check=True, capture_output=True, text=True
    ).stdout.strip()


def quotes_of(number):
    """Every document line quoting `number` (as a whole token), as path:line: text."""
    top = repo_root()
    docs = DOCS + [
        os.path.relpath(x, top) for g in DOC_GLOBS for x in sorted(glob.glob(os.path.join(top, g)))
    ]
    token = re.compile(r"(?<![\d.])" + re.escape(number) + r"(?![\d])")
    hits = []
    for doc in docs:
        path = os.path.join(top, doc)
        if not os.path.isfile(path):
            continue
        with open(path, encoding="utf-8") as f:
            for i, line in enumerate(f, 1):
                if token.search(line):
                    hits.append(f"{doc}:{i}: {line.strip()[:100]}")
    return hits


def print_requotes(rows):
    total = 0
    for table, case, before, _ in rows:
        hits = quotes_of(f"{before:.1f}")
        if hits:
            total += len(hits)
            print(f"  {case} [{table}]: {before:.1f} is quoted in")
            for h in hits:
                print(f"    {h}")
    print(f"  {total} document line(s) quote a moved row's old number")


def print_rows(rows, threshold):
    # A case name repeats across tables ("hand-written kernel, null-free" is in date_add's
    # table and in datediff's), so name the table wherever the case alone is ambiguous.
    counts = {}
    for _, case, _, _ in rows:
        counts[case] = counts.get(case, 0) + 1
    labels = [
        (f"[{table}] {case}" if counts[case] > 1 else case, b, a) for table, case, b, a in rows
    ]
    width = max((len(label) for label, _, _ in labels), default=10)
    print(f"{'case':{width}}  {'before':>9}  {'after':>9}  {'change':>8}")
    for label, b, a in labels:
        change = pct(b, a)
        mark = " <--" if abs(change) >= threshold else ""
        print(f"{label:{width}}  {b:9.1f}  {a:9.1f}  {change:+7.1f}%{mark}")


def before_after(old_text, new_text, args):
    old, _ = parse(old_text)
    new, order = parse(new_text)
    control = re.compile(args.control)
    controls, moved, same, missing = [], [], [], []
    for key in order:
        section, case = key
        if key not in old:
            missing.append(key)
            continue
        row = (section, case, old[key], new[key])
        if control.search(case):
            controls.append(row)
        elif abs(pct(old[key], new[key])) >= args.threshold:
            moved.append(row)
        else:
            same.append(row)
    if controls:
        print(f"-- controls ({args.control}); if these moved, the machine moved --")
        print_rows(controls, args.threshold)
        print()
    print(f"-- moved by at least {args.threshold:g}% --")
    print_rows(moved, args.threshold) if moved else print("(none)")
    if args.all and same:
        print()
        print("-- within the threshold --")
        print_rows(same, args.threshold)
    if args.requote:
        print()
        print("-- requote: document lines quoting the old numbers of the moved rows --")
        print_requotes(moved)
    gone = [k for k in old if k not in new]
    if missing or gone:
        print()
        for k in missing:
            print(f"new only: [{k[0]}] {k[1]}")
        for k in gone:
            print(f"old only: [{k[0]}] {k[1]}")


def within(text, label_a, label_b, threshold):
    rates, order = parse(text)
    rows = []
    for section, case in order:
        if label_a in case:
            twin = (section, case.replace(label_a, label_b))
            if twin in rates:
                rows.append((section, case, rates[twin], rates[(section, case)]))
    print(f"-- {label_a} (after) against {label_b} (before), same run --")
    print_rows(rows, threshold) if rows else print("(no pairs found)")


SURFACE_NAME = re.compile(r"^(.*) over (\d+) rows(, executor time)?$")
SELECTIVITY = re.compile(r"^# selectivity: \d+ of \d+ rows, ([\d.]+%)")


def selectivities(text):
    """{entry: selectivity} from the `# selectivity` lines the driver prints under a filter
    entry's tables; the entry is the table name the line follows."""
    out, entry = {}, None
    for line in text.splitlines():
        h = HEADER.match(line)
        if h:
            m = SURFACE_NAME.match(h.group(1).strip())
            entry = m.group(1) if m else None
            continue
        m = SELECTIVITY.match(line)
        if m and entry:
            out[entry] = m.group(1)
    return out


def surface_table(specs):
    """The README's table from the date-surface files: one row per entry and shape, the wall
    rate of every distribution in M rows/s, and the last distribution's ratio against each of
    the others; then the same for the executor-time tables. `specs` are LABEL=FILE, the Varka
    file last."""
    labels, files, selected = [], [], {}
    for spec in specs:
        label, _, path = spec.partition("=")
        if not path:
            raise SystemExit(f"--table wants LABEL=FILE, got {spec}")
        labels.append(label)
        text = read(path)
        files.append(parse(text))
        selected.update(selectivities(text))
    last_rates, order = files[-1]
    for executor in (False, True):
        title = "executor time" if executor else "wall time"
        head = ["expression", "shape", "selects"] + [f"{l} (M rows/s)" for l in labels]
        head += [f"{labels[-1]} / {l}" for l in labels[:-1]]
        print(f"**{title}**")
        print()
        print("| " + " | ".join(head) + " |")
        print("|" + "---|" * len(head))
        for table, case in order:
            m = SURFACE_NAME.match(table)
            if not m or bool(m.group(3)) != executor:
                continue
            entry = m.group(1)
            rates = [f.get((table, case)) for f, _ in files]
            cells = [f"`{entry}`", case, selected.get(entry, "-") if "filter" in case else "-"]
            cells += [f"{r:.1f}" if r is not None else "-" for r in rates]
            for r in rates[:-1]:
                cells.append(f"**{rates[-1] / r:.2f}x**" if r and rates[-1] else "-")
            print("| " + " | ".join(cells) + " |")
        print()


def main():
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    p.add_argument("old", nargs="?", help="the older results file")
    p.add_argument("new", nargs="?", help="the newer results file")
    p.add_argument("--git", metavar="REV", help="read the old side of FILE from this revision")
    p.add_argument("--within", metavar="FILE", help="A/B pairs inside one file")
    p.add_argument(
        "--ab",
        nargs=2,
        metavar=("A", "B"),
        help="the two labels that distinguish an A/B pair's rows",
    )
    p.add_argument(
        "--threshold",
        type=float,
        default=3.0,
        help="percent change that counts as moved (default 3)",
    )
    p.add_argument(
        "--control",
        default=r"per-row|scalar|LocalDate|row engine",
        help="regex naming the control rows (default: the scalar anchors)",
    )
    p.add_argument("--all", action="store_true", help="also list rows within the threshold")
    p.add_argument(
        "--table",
        nargs="+",
        metavar="LABEL=FILE",
        help="the date-surface table (task 62) from these files, the Varka file last",
    )
    p.add_argument(
        "--requote",
        action="store_true",
        help="list every document line quoting a moved row's old number",
    )
    args = p.parse_args()

    if args.table:
        surface_table(args.table)
        return
    if args.within:
        if not args.ab:
            p.error("--within needs --ab A B")
        within(read(args.within), args.ab[0], args.ab[1], args.threshold)
        return
    if args.git:
        if not args.old or args.new:
            p.error("--git REV takes exactly one FILE")
        before_after(read(args.old, args.git), read(args.old), args)
        return
    if not (args.old and args.new):
        p.error("give OLD NEW, or --git REV FILE, or --within FILE --ab A B")
    before_after(read(args.old), read(args.new), args)


if __name__ == "__main__":
    main()
