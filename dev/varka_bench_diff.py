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

--table LABEL=FILE ... prints the date-surface table (VARKA-62): one row per
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
DOC_GLOBS = ["sql/varka/plans/*.md", "sql/varka/plans/m*/*.md"]

ROW = re.compile(r"^(.*?)\s+(\d+)\s+(\d+)\s+(\d+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)X\s*$")
HEADER = re.compile(r"^(.*?):\s+Best Time\(ms\)")


def parse(text):
    """{(table, case, occurrence): rate} plus the ordered list of keys, in file order.

    The occurrence index is not decoration. Four sections of the parity file share
    the table header `20000000 rows in 4096-row chunks`, and two rows inside one
    of them share the case name `weekofyear (VARKA-37), null-free`, so a
    (table, case) pair is not unique. Keyed without the index, the second row
    overwrites the first: the file's own diff then silently reports one row where
    there are two, and `--requote` treats the survivor's shifted key as absent
    rather than moved, which is a false all-clear on the closing step
    `sql/varka/AGENTS.md` makes mandatory.
    """
    rates, order, seen, table = {}, [], {}, ""
    for line in text.splitlines():
        line = re.sub(r"^\[info\] ?", "", line)
        h = HEADER.match(line)
        if h:
            table = h.group(1).strip()
            continue
        m = ROW.match(line)
        if m:
            base = (table, m.group(1).strip())
            seen[base] = seen.get(base, 0) + 1
            key = base + (seen[base],)
            rates[key] = float(m.group(5))
            order.append(key)
    return rates, order


def label_of(table, case, occ, ambiguous):
    """The case as printed: qualified by table only where the name alone is not
    enough, and by `#n` only where the same name repeats inside one table."""
    label = f"[{table}] {case}" if ambiguous else case
    return f"{label} #{occ}" if occ > 1 else label


def read(path, rev=None):
    if rev is None:
        with open(path, encoding="utf-8") as f:
            return f.read()
    return subprocess.run(
        ["git", "show", f"{rev}:{path}"], check=True, capture_output=True, text=True
    ).stdout


def pct(before, after):
    return (after / before - 1.0) * 100.0 if before else float("nan")


def per_row(text):
    """{(table, case, occurrence): nanoseconds a row}, keyed as parse() keys rates."""
    out, seen, table = {}, {}, ""
    for line in text.splitlines():
        line = re.sub(r"^\[info\] ?", "", line)
        h = HEADER.match(line)
        if h:
            table = h.group(1).strip()
            continue
        m = ROW.match(line)
        if m:
            base = (table, m.group(1).strip())
            seen[base] = seen.get(base, 0) + 1
            out[base + (seen[base],)] = float(m.group(6))
    return out


def rate_change(before, after, before_ns=None, after_ns=None):
    """The rate's change in percent, read from whichever printed figure has more digits.

    The rate and the time a row are reciprocals, both printed with one decimal. A cold
    case's rate moves in steps of 12% at 0.8 M/s and prints 0.0 over a few rows, which
    would make every change unreadable; its time a row resolves such a case to 0.01%.
    dev/varka_bench_band.py measures the band from the same figure.
    """
    if before_ns and after_ns and min(before, after) < min(before_ns, after_ns):
        return (before_ns / after_ns - 1.0) * 100.0
    return pct(before, after)


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
    for table, case, occ, before, _ in rows:
        hits = quotes_of(f"{before:.1f}")
        if hits:
            total += len(hits)
            print(f"  {label_of(table, case, occ, False)} [{table}]: {before:.1f} is quoted in")
            for h in hits:
                print(f"    {h}")
    print(f"  {total} document line(s) quote a moved row's old number")


BAND_ROW = re.compile(r"^(\d) +([\d.]+) +(\d+)  (.*)$")
BAND_DELIM = " | "
BAND_LABEL = {0: "quiet", 1: "moderate", 2: "noisy", 3: "unreadable"}
BAND_THRESHOLD = {0: 3.0, 1: 10.0, 2: 25.0}


def read_band(path):
    """{(table, case, occurrence): tier} from a band file written by
    dev/varka_bench_band.py. A tier is what an unchanged file does to that case:
    0 within 3%, 1 within 10%, 2 within 25%, 3 not readable from a diff at all."""
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            m = BAND_ROW.match(line.rstrip())
            if m:
                table, _, case = m.group(4).partition(BAND_DELIM)
                out[(table, case, int(m.group(3)))] = int(m.group(1))
    if not out:
        raise SystemExit(f"{path}: no band rows parsed")
    return out


def band_verdict(band, key, change):
    """(marker, meaningful). Without a band this is the flat threshold as before."""
    if band is None:
        return (" <--" if abs(change) >= 3.0 else ""), abs(change) >= 3.0
    tier = band.get(key)
    if tier is None:
        return " <-- (no band)", True
    if tier >= 3:
        return "  (unreadable)", False
    over = abs(change) >= BAND_THRESHOLD[tier]
    return (f" <-- ({BAND_LABEL[tier]})" if over else ""), over


def print_rows(rows, threshold, band=None, changes=None):
    # A case name repeats across tables ("hand-written kernel, null-free" is in date_add's
    # table and in datediff's), so name the table wherever the case alone is ambiguous.
    counts = {}
    for _, case, _, _, _ in rows:
        counts[case] = counts.get(case, 0) + 1
    labels = [
        (label_of(table, case, occ, counts[case] > 1), (table, case, occ), b, a)
        for table, case, occ, b, a in rows
    ]
    width = max((len(label) for label, _, _, _ in labels), default=10)
    print(f"{'case':{width}}  {'before':>9}  {'after':>9}  {'change':>8}")
    for label, key, b, a in labels:
        change = changes[key] if changes and key in changes else pct(b, a)
        if band is None:
            mark = " <--" if abs(change) >= threshold else ""
        else:
            mark, _ = band_verdict(band, key, change)
        print(f"{label:{width}}  {b:9.1f}  {a:9.1f}  {change:+7.1f}%{mark}")


def before_after(old_text, new_text, args):
    old, _ = parse(old_text)
    new, order = parse(new_text)
    old_ns, new_ns = per_row(old_text), per_row(new_text)
    band = read_band(args.band) if args.band else None
    control = re.compile(args.control)
    controls, moved, same, missing, unreadable = [], [], [], [], []
    changes = {}
    for key in order:
        section, case, occ = key
        if key not in old:
            missing.append(key)
            continue
        row = (section, case, occ, old[key], new[key])
        change = rate_change(old[key], new[key], old_ns.get(key), new_ns.get(key))
        changes[key] = change
        if control.search(case):
            controls.append(row)
            continue
        if band is None:
            (moved if abs(change) >= args.threshold else same).append(row)
            continue
        # A case the band calls unreadable is set aside rather than reported as
        # moved: its swing between two runs of an unchanged file is larger than
        # anything a regeneration could tell you, so listing it wastes the reader.
        if band.get(key, 0) >= 3:
            unreadable.append(row)
            continue
        _, over = band_verdict(band, key, change)
        (moved if over else same).append(row)
    if controls:
        print(f"-- controls ({args.control}); if these moved, the machine moved --")
        print_rows(controls, args.threshold, band, changes)
        print()
    if band is None:
        print(f"-- moved by at least {args.threshold:g}% --")
    else:
        print(f"-- moved past the band in {args.band} --")
    print_rows(moved, args.threshold, band, changes) if moved else print("(none)")
    if unreadable:
        print()
        print(f"-- {len(unreadable)} case(s) the band calls unreadable, not classified --")
        print_rows(unreadable, args.threshold, band, changes)
    if args.all and same:
        print()
        print("-- within the band --" if band else "-- within the threshold --")
        print_rows(same, args.threshold, band, changes)
    if args.requote:
        print()
        print("-- requote: document lines quoting the old numbers of the moved rows --")
        print_requotes(moved)
    gone = [k for k in old if k not in new]
    if missing or gone:
        print()
        for k in missing:
            print(f"new only: [{k[0]}] {label_of(k[0], k[1], k[2], False)}")
        for k in gone:
            print(f"old only: [{k[0]}] {label_of(k[0], k[1], k[2], False)}")


def within(text, label_a, label_b, threshold):
    rates, order = parse(text)
    rows = []
    for section, case, occ in order:
        if label_a in case:
            twin = (section, case.replace(label_a, label_b), occ)
            if twin in rates:
                rows.append((section, case, occ, rates[twin], rates[(section, case, occ)]))
    print(f"-- {label_a} (after) against {label_b} (before), same run --")
    print_rows(rows, threshold) if rows else print("(no pairs found)")


SURFACE_NAME = re.compile(r"^(.*) over (\d+) rows(, executor time)?$")
SELECTIVITY = re.compile(r"^# selectivity: \d+ of \d+ rows, ([\d.]+%)")
CHECKSUM = re.compile(r"^# checksum: (.*)$")


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


def per_entry(text, pattern):
    """{entry: captured text} for a `# ...` line keyed by the table it follows.

    The same walk `selectivities` does, for any of the driver's per-entry comment lines."""
    out, entry = {}, None
    for line in text.splitlines():
        h = HEADER.match(line)
        if h:
            m = SURFACE_NAME.match(h.group(1).strip())
            entry = m.group(1) if m else None
            continue
        m = pattern.match(line)
        if m and entry:
            out[entry] = m.group(1)
    return out


def compare_answers(labels, per_label, what):
    """Every arm must agree about `what` on every entry both of them ran.

    This is the check the surface driver did not have (VARKA-125). It asserted that a row fused
    and that no batch fell back, and it compared nothing the arms computed, so a kernel that was
    fast and wrong published a rate like any other. Entries missing from an arm are skipped
    rather than reported: a `--only` or `--shard` run is a legitimate subset, and the arms are
    compared on what they have in common.

    A disagreement is the run's answer, not a warning, so it raises.
    """
    disagreed = []
    common = set(per_label[0])
    for d in per_label[1:]:
        common &= set(d)
    if not common:
        # Said out loud rather than passed over. A results file written before this check
        # existed carries no checksum line, so an old arm beside a new one compares nothing -
        # and a check that silently compares nothing is worse than no check, because the run
        # looks the same either way.
        print(f"note: no arm pair shares an entry carrying {what}; nothing was compared.")
        print("      Regenerate the older arm's file to get the comparison.")
        print()
        return 0
    for entry in sorted(common):
        values = [d[entry] for d in per_label]
        if len(set(values)) > 1:
            disagreed.append((entry, values))
    if not disagreed:
        return len(common)
    lines = [f"the arms disagree about {what} - one of them computed a different answer:"]
    for entry, values in disagreed:
        lines.append(f"  {entry}")
        for label, value in zip(labels, values):
            lines.append(f"    {label:<24} {value}")
    lines.append("")
    lines.append("A kernel that is fast and wrong looks exactly like a kernel that is fast, so")
    lines.append("this fails the run rather than printing a table. If the arms are different")
    lines.append("Spark versions and only the fold differs, check that `xxhash64` still hashes")
    lines.append("the same way in both before looking at the kernel.")
    raise SystemExit("\n".join(lines))


def surface_table(specs):
    """The README's table from the date-surface files: one row per entry and shape, the wall
    rate of every distribution in M rows/s, and the last distribution's ratio against each of
    the others; then the same for the executor-time tables. `specs` are LABEL=FILE, the Varka
    file last."""
    labels, files, selected = [], [], {}
    sums, sels = [], []
    for spec in specs:
        label, _, path = spec.partition("=")
        if not path:
            raise SystemExit(f"--table wants LABEL=FILE, got {spec}")
        labels.append(label)
        text = read(path)
        files.append(parse(text))
        sums.append(per_entry(text, CHECKSUM))
        sels.append(per_entry(text, SELECTIVITY))
        selected.update(selectivities(text))
    # Before the table, not after: a table printed above a disagreement reads as the run's
    # result, and the numbers in it are the ones that must not be quoted.
    if len(labels) > 1:
        compare_answers(labels, sums, "what an entry computed")
        compare_answers(labels, sels, "how many rows a filter selected")
    last_rates, order = files[-1]
    for executor in (False, True):
        title = "executor time" if executor else "wall time"
        head = ["expression", "shape", "selects"] + [f"{l} (M rows/s)" for l in labels]
        head += [f"{labels[-1]} / {l}" for l in labels[:-1]]
        print(f"**{title}**")
        print()
        print("| " + " | ".join(head) + " |")
        print("|" + "---|" * len(head))
        for table, case, occ in order:
            m = SURFACE_NAME.match(table)
            if not m or bool(m.group(3)) != executor:
                continue
            entry = m.group(1)
            rates = [f.get((table, case, occ)) for f, _ in files]
            cells = [f"`{entry}`", case, selected.get(entry, "-") if "filter" in case else "-"]
            cells += [f"{r:.1f}" if r is not None else "-" for r in rates]
            for r in rates[:-1]:
                cells.append(f"**{rates[-1] / r:.2f}x**" if r and rates[-1] else "-")
            print("| " + " | ".join(cells) + " |")
        print()


# A results file holding the collision the parity file really has: two rows in one
# table sharing a case name. Columns are squeezed only to fit the source line
# limit; the parser reads them by whitespace, exactly as it reads a real file.
SELFTEST_OLD = """\
OpenJDK 64-Bit Server VM
AMD Ryzen
20000000 rows in 4096-row chunks:  Best Time(ms)   Avg Time(ms)
------------------------------------------------------------------
weekofyear (VARKA-37), null-free  14 14 0 1430.0 0.7 1.0X
some other case                  20 20 0  700.0 1.4 0.5X
weekofyear (VARKA-37), null-free  14 14 0 1430.3 0.7 1.0X
"""

SELFTEST_NEW = SELFTEST_OLD.replace("1430.3", "1000.0")

# One entry's tables and its checksum line, as the surface driver writes them: the comment is
# keyed by the table header above it, so the parser has to have read that header first.
SELFTEST_SUMS_A = """\
date_add(d, 3) over 1000 rows:  Best Time(ms)   Avg Time(ms)
------------------------------------------------------------------
projection, columnar consumer   14 14 0 1430.0 0.7 1.0X
# plan: projection, columnar consumer Varka (kernel 2 batches, fallback 0)
# checksum: projection rows=1000 nonnull=967 fold=42
"""

SELFTEST_SUMS_B = SELFTEST_SUMS_A.replace("fold=42", "fold=43")


def selftest_rate_change():
    """A cold case is read from its time a row: 0.0 M/s to 0.0 is still a 25% change."""
    assert abs(rate_change(0.0, 0.0, 1250000.0, 1000000.0) - 25.0) < 1e-9
    assert abs(rate_change(0.8, 1.0, 1250.0, 1000.0) - 25.0) < 1e-9
    assert abs(rate_change(3452.2, 3452.2, 0.3, 0.3)) < 1e-9


def selftest():
    """The keying, on a file holding the collision the parity file really has.

    Two rows share a table and a case name and differ only in their rate. Keyed as
    (table, case) the second overwrites the first, the parse reports one row where
    there are two, and a change to the second is invisible - which is what
    `--requote` was silently missing. Keyed with the occurrence index both survive.
    """
    rates, order = parse(SELFTEST_OLD)
    assert len(order) == 3, f"expected 3 rows, parsed {len(order)}"
    assert len(rates) == 3, f"expected 3 distinct keys, got {len(rates)}"
    dup = [k for k in order if k[1].startswith("weekofyear")]
    assert len(dup) == 2 and dup[0][2] == 1 and dup[1][2] == 2, dup
    assert rates[dup[0]] == 1430.0 and rates[dup[1]] == 1430.3, [rates[k] for k in dup]

    # And a change to the second one is reported, at the right magnitude.
    new_rates, _ = parse(SELFTEST_NEW)
    moved = [k for k in order if abs(pct(rates[k], new_rates[k])) >= 3.0]
    assert moved == [dup[1]], f"expected only the second duplicate to move, got {moved}"
    assert abs(pct(rates[dup[1]], new_rates[dup[1]]) + 30.08) < 0.1

    # Labels stay readable: the repeat is marked, the singleton is not.
    assert label_of("t", "c", 1, False) == "c"
    assert label_of("t", "c", 2, False) == "c #2"
    assert label_of("t", "c", 1, True) == "[t] c"

    # VARKA-125: the per-entry comment lines are keyed by the table above them, and two arms
    # that disagree about one stop the run.
    one = per_entry(SELFTEST_SUMS_A, CHECKSUM)
    assert one == {"date_add(d, 3)": "projection rows=1000 nonnull=967 fold=42"}, one
    compare_answers(["a", "b"], [one, per_entry(SELFTEST_SUMS_A, CHECKSUM)], "x")
    try:
        compare_answers(["a", "b"], [one, per_entry(SELFTEST_SUMS_B, CHECKSUM)], "x")
    except SystemExit as e:
        assert "date_add(d, 3)" in str(e) and "fold=43" in str(e), str(e)
    else:
        raise AssertionError("a differing checksum did not stop the run")

    # An arm that ran a subset is compared on what it shares, not reported as a disagreement:
    # `--only` and `--shard` are legitimate, and failing on them would make the check a nuisance
    # that gets switched off.
    assert compare_answers(["a", "b"], [one, {}], "x") == 0
    assert compare_answers(["a", "b"], [one, one], "x") == 1
    print("varka_bench_diff selftest: ok")


def main():
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    p.add_argument("old", nargs="?", help="the older results file")
    p.add_argument("new", nargs="?", help="the newer results file")
    p.add_argument("--git", metavar="REV", help="read the old side of FILE from this revision")
    p.add_argument("--selftest", action="store_true", help="check the row keying and exit")
    p.add_argument(
        "--band",
        metavar="FILE",
        help="classify each move against the band file for this benchmark, instead of "
        "one flat threshold; written by dev/varka_bench_band.py",
    )
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
        help="the date-surface table (VARKA-62) from these files, the Varka file last",
    )
    p.add_argument(
        "--requote",
        action="store_true",
        help="list every document line quoting a moved row's old number",
    )
    args = p.parse_args()

    if args.selftest:
        selftest()
        selftest_rate_change()
        return
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
