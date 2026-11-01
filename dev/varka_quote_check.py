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
"""Does every performance number quoted in the Varka documents trace to a committed
benchmark results file? (sql/varka/AGENTS.md, "Measurements, not adjectives".)

  dev/varka_quote_check.py              # report new orphans, exit with their count
  dev/varka_quote_check.py --all        # also list the numbers that were found
  dev/varka_quote_check.py FILE...      # only these documents
  dev/varka_quote_check.py --update-allowlist   # accept today's orphans, with a reason
  dev/varka_quote_check.py --measure-widening   # count what more number shapes would add
  dev/varka_quote_check.py --self-test          # the citation rule on fixtures

A quote is a number with two or more integer digits and one to three decimals
(3452.2, 481.7, 4785.665) that is not a percentage, a ratio suffixed with x,
part of a version, or the seconds of a clock time after a colon. That shape is
what benchmark rates and JMH scores look like and what section numbers,
versions and op counts do not. Each one must appear
in a results file under sql/*/benchmarks/ or sql/varka/engine/benchmarks/ (or a
demo's committed output under sql/varka/demo/, or a spike's under sql/varka/spikes/*/results/),
or among a plan's own measurements under sql/varka/plans/*/results/, either as
committed now or in any committed version of those files (plans legitimately
quote the number a change moved away from), and the report says which. A number
found nowhere is an orphan: a scratch-log figure, a typo, or a result that was
never regenerated into the tree.

A quote is held to the results files its passage cites: a table row (one line
starting with `|`) or a prose paragraph cites a file by naming it in backticks,
whole or by a trailing part of its path (`m8/results/VARKA-509-step4.txt`,
`VarkaThroughputBenchmark-jdk25-results.txt`). Only a passage that cites no
results file has its quotes searched for in all of them; the summary counts
those quotes. Holding a quote to its citation is what keeps a number from
passing because its digits happen to appear in an unrelated file. A quote found
only outside its citation is reported as UNCITED, with the file it was found in.

Orphans that predate this tool are listed in dev/varka_quote_allowlist.txt with
the reason each was accepted, one `<document>\t<number>\t<reason>` per line, and
are reported as "allowed" rather than counted. The list is a ratchet: it should
only shrink. --update-allowlist appends the current orphans to it with a
placeholder reason for you to edit, which is the one time a run may add to it.
"""

import argparse
import glob
import os
import re
import subprocess
import sys

DOCS = [
    "SKILLS.md",
    "README.md",
    "docs/sql-varka.md",
    "sql/varka/AGENTS.md",
    "sql/varka/VISION.md",
    "sql/varka/ADDING_AN_EXPRESSION.md",
    "sql/varka/ISSUES.md",
    "sql/varka/Varka_MVP.md",
]
DOC_GLOBS = [
    "sql/varka/plans/*.md",
    "sql/varka/plans/m*/*.md",
    "sql/varka/skills/*.md",
    "sql/varka/skills/calibration/*.md",
]
RESULT_GLOBS = [
    "sql/*/benchmarks/*.txt",
    "sql/varka/engine/benchmarks/*.txt",
    "sql/varka/bench/benchmarks/*.txt",
    # The reader's demos print their own tables; their committed outputs are results files too.
    "sql/varka/demo/*-output.txt",
    "sql/varka/demo/*/*-output.txt",
    # A spike keeps its outputs beside its harness (VARKA-291).
    "sql/varka/spikes/*/results/*.txt",
    # A plan's own measurements, cited from its rows as `m8/results/<file>`.
    "sql/varka/plans/*/results/*.txt",
]
ALLOWLIST = "dev/varka_quote_allowlist.txt"
# A number right after a colon is the seconds of a clock time (`TIME'12:34:56.789'`), not a rate.
QUOTE = re.compile(r"(?<![\w.\-:])(\d{2,}\.\d{1,3})(?![\w.%]|\s*x\b|\s*x\)|\s*x,|\s*x\.)")
# The shapes --measure-widening counts, which the check does not yet read: a percentage, and a
# decimal with one integer digit. A results file is searched for them as any number at all.
PERCENT = re.compile(r"(?<![\w.\-:])(\d+(?:\.\d+)?)%")
ONE_DIGIT = re.compile(r"(?<![\w.\-:])(\d\.\d{1,3})(?![\w.%]|\s*x\b|\s*x\)|\s*x,|\s*x\.)")
ANY_NUMBER = re.compile(r"(?<![\w.])(\d+(?:\.\d+)?)")


def root():
    return subprocess.run(
        ["git", "rev-parse", "--show-toplevel"], check=True, capture_output=True, text=True
    ).stdout.strip()


def numbers_in(text, shape=QUOTE):
    return set(shape.findall(text))


def committed_numbers(top, shape=QUOTE):
    """Every number of the given shape in every results file, per file: the numbers a file
    holds now, and those any committed version of it held."""
    files = sorted({p for g in RESULT_GLOBS for p in glob.glob(os.path.join(top, g))})
    rel = [os.path.relpath(p, top) for p in files]
    current = {}
    for p, r in zip(files, rel):
        with open(p, encoding="utf-8", errors="replace") as f:
            current[r] = numbers_in(f.read(), shape)
    # `git log -p` over the results directories: additions and removals both count,
    # since a plan may quote the number a regeneration replaced.
    #
    # --full-history is load-bearing, not defensive. Results files are regenerated rather
    # than merged textually, so merging master into a task branch resolves them by taking
    # one side whole - and that leaves the merge TREESAME to that side for these paths, so
    # git's default history simplification prunes the other parent's entire line. Every
    # version the other branch wrote then becomes invisible here, and a plan quoting one of
    # its numbers is reported as an orphan although the number is committed and correct.
    # VARKA-60 hit exactly this: 16 orphans on the branch against 0 on master, for numbers
    # master had measured. --full-history keeps the promise this tool's docstring makes -
    # any committed version of these files - and costs nothing measurable, since the
    # directories are small (both forms ran in 1.1s over ~50 MB of patch text).
    dirs = sorted({os.path.dirname(r) for r in rel})
    # While a merge is being committed, the incoming side's history is not yet reachable
    # from HEAD, so a number only that side ever committed would be reported as an orphan
    # and the pre-commit hook would block a merge that is clean once committed. Walking
    # MERGE_HEAD too gives the hook the same view the committed merge will have.
    revs = ["HEAD"]
    merging = subprocess.run(
        ["git", "-C", top, "rev-parse", "-q", "--verify", "MERGE_HEAD"], capture_output=True
    )
    if merging.returncode == 0:
        revs.append("MERGE_HEAD")
    log = subprocess.run(
        [
            "git",
            "-C",
            top,
            "log",
            "-p",
            "--full-history",
            "--no-renames",
            "--format=",
            *revs,
            "--",
            *dirs,
        ],
        check=True,
        capture_output=True,
        text=True,
        errors="replace",
    ).stdout
    # Each patch starts with `diff --git a/<path> b/<path>`; its lines belong to that path, so
    # a row citing one file is held to that file's history only. --no-renames shows a rename
    # as a deletion and an addition, which gives the new path the whole content.
    historical = {}
    path = None
    for line in log.splitlines():
        if line.startswith("diff --git "):
            path = line.rsplit(" b/", 1)[-1]
        elif path is not None and line[:1] in "+-" and not line.startswith(("+++ ", "--- ")):
            historical.setdefault(path, set()).update(numbers_in(line, shape))
    return current, historical, rel


def by_name(result_files):
    """The results files keyed by file name, which is what a citation is matched on first."""
    names = {}
    for r in result_files:
        names.setdefault(r.rsplit("/", 1)[-1], []).append(r)
    return names


def cited_files(text, names):
    """The results files a piece of text cites: each backticked token that is a results file's
    path or a trailing part of it (`m8/results/X.txt`, `results/X.txt`, a bare file name).
    names is by_name of the results files."""
    cited = set()
    for token in re.findall(r"`([^`\s]+)`", text):
        for r in names.get(token.rsplit("/", 1)[-1], ()):
            if r == token or r.endswith("/" + token):
                cited.add(r)
    return cited


def units(lines):
    """For each line, the text of the unit its quotes are held to: a table row (a line starting
    with `|`) is its own unit, and any other line belongs to its blank-line-separated paragraph."""
    out = [""] * len(lines)
    para = []
    for i, line in enumerate(lines + [""]):
        if line.lstrip().startswith("|") or not line.strip():
            text = "\n".join(lines[j] for j in para)
            for j in para:
                out[j] = text
            para = []
            if i < len(lines) and line.strip():
                out[i] = line
        else:
            para.append(i)
    return out


def where_found(current, historical):
    """Invert the per-file numbers: for each number, the files that hold it now and the files
    any committed version of which held it."""
    now, ever = {}, {}
    for files, index in ((current, now), (historical, ever)):
        for f, numbers in files.items():
            for n in numbers:
                index.setdefault(n, set()).add(f)
    return now, ever


def classify(n, cited, where):
    """Where number n is found: in the files the unit cites, or in any results file when the
    unit cites none. Returns (verdict, fallback, uncited): the verdict is "committed",
    "historical" or None; uncited names a file outside the citation that holds n, as a hint."""
    now, ever = (index.get(n, set()) for index in where)
    if now and (not cited or now & cited):
        return "committed", not cited, None
    if ever and (not cited or ever & cited):
        return "historical", not cited, None
    return None, not cited, min(now | ever, default=None)


def read_allowlist(top):
    allowed = {}
    path = os.path.join(top, ALLOWLIST)
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                if not line.strip() or line.startswith("#"):
                    continue
                doc, number, *reason = line.rstrip("\n").split("\t")
                allowed[(doc, number)] = reason[0] if reason else ""
    return allowed


def check_doc(lines, where, result_files, shape=QUOTE):
    """Each quote in a document's lines, as (line number, number, verdict, fallback, uncited);
    see classify for the last three."""
    out = []
    cited = {}
    names = by_name(result_files)
    for i, (line, unit) in enumerate(zip(lines, units(lines)), 1):
        if unit not in cited:
            cited[unit] = cited_files(unit, names)
        for n in shape.findall(line):
            out.append((i, n, *classify(n, cited[unit], where)))
    return out


def measure_widening(top, docs):
    """How many quotes the check would gain if it read percentages and one-digit decimals,
    and how many of those it would report as orphans. A results file's side is read as any
    number, since a percentage there may be printed without its sign."""
    current, historical, result_files = committed_numbers(top, ANY_NUMBER)
    where = where_found(current, historical)
    for name, shape in (("percentages", PERCENT), ("one-digit decimals", ONE_DIGIT)):
        quotes = orphans = 0
        for doc in docs:
            with open(os.path.join(top, doc), encoding="utf-8") as f:
                rows = check_doc(f.read().splitlines(), where, result_files, shape)
            quotes += len(rows)
            orphans += sum(1 for r in rows if r[2] is None)
        print(f"widening to {name}: {quotes} more quote(s), {orphans} of them orphans")


def self_test():
    """The scoping rule on fixtures, no git needed."""
    step4 = "sql/varka/plans/m8/results/VARKA-509-step4.txt"
    parquet = "sql/core/benchmarks/VectorizedRleValuesReaderBenchmark-jdk21-results.txt"
    files = [step4, parquet]
    current = {step4: {"11.96", "35.30"}, parquet: {"472.2", "135.8", "399.3", "989.1"}}
    historical = {}
    failures = []

    def verdicts(text):
        where = where_found(current, historical)
        return [r[1:4] for r in check_doc(text.splitlines(), where, files)]

    def expect(name, got, want):
        if got != want:
            failures.append(f"{name}: got {got}, want {want}")

    row = "| 509 | rates 472.2, 135.8, 399.3 and 989.1 (`m8/results/VARKA-509-step4.txt`) | x |"
    expect(
        "digits only in an uncited file fail",
        verdicts(row),
        [(n, None, False) for n in ("472.2", "135.8", "399.3", "989.1")],
    )
    expect(
        "the same quote passes when its row cites the file",
        verdicts(row.replace("m8/results/VARKA-509-step4.txt", parquet.split("/")[-1])),
        [(n, "committed", False) for n in ("472.2", "135.8", "399.3", "989.1")],
    )
    expect(
        "a row citing nothing falls back to every file",
        verdicts("| 1 | at 472.2 | x |"),
        [("472.2", "committed", True)],
    )
    expect(
        "prose is held to its paragraph's citation",
        verdicts(f"See `{step4}`.\nIt read 11.96 ms\n\nThen 472.2 and 35.30 ms"),
        [("11.96", "committed", False), ("472.2", "committed", True), ("35.30", "committed", True)],
    )
    historical[step4] = {"12.25"}
    expect(
        "a cited file's history counts",
        verdicts(f"| 2 | 12.25 (`{step4}`) |"),
        [("12.25", "historical", False)],
    )
    expect(
        "a fragment of a name cites nothing",
        cited_files("`-jdk21-results.txt`", by_name(files)),
        set(),
    )
    for f in failures:
        print("FAIL", f)
    print(f"self-test: {6 - len(failures)} of 6 passed")
    return len(failures)


def main():
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    p.add_argument("docs", nargs="*", help="documents to check (default: the Varka set)")
    p.add_argument("--all", action="store_true", help="also list the numbers that were found")
    p.add_argument(
        "--update-allowlist",
        action="store_true",
        help="append the current orphans to the allowlist, reason left to edit",
    )
    p.add_argument(
        "--measure-widening",
        action="store_true",
        help="count the quotes percentages and one-digit decimals would add; check nothing",
    )
    p.add_argument("--self-test", action="store_true", help="run the scoping rule's tests")
    args = p.parse_args()
    if args.self_test:
        return self_test()
    # The scoping rule's own tests run first, in a few milliseconds, so every run of the check -
    # the Build's docs job and the pre-commit hook among them - proves the rule it applies.
    if self_test() != 0:
        return 1
    top = root()
    allowed = read_allowlist(top)
    docs = args.docs or (
        DOCS
        + [
            os.path.relpath(x, top)
            for g in DOC_GLOBS
            for x in sorted(glob.glob(os.path.join(top, g)))
        ]
    )
    docs = [d for d in docs if os.path.isfile(os.path.join(top, d))]
    if args.measure_widening:
        measure_widening(top, docs)
        return 0
    current, historical, result_files = committed_numbers(top)
    found = where_found(current, historical)

    orphans = fallbacks = 0
    new_orphans = []
    for doc in docs:
        with open(os.path.join(top, doc), encoding="utf-8") as f:
            lines = f.read().splitlines()
        rows = []
        for i, n, where, fallback, uncited in check_doc(lines, found, result_files):
            fallbacks += fallback
            if where is None and (doc, n) in allowed:
                where = "allowed"
            elif where is None:
                where = "ORPHAN"
                orphans += 1
                new_orphans.append((doc, n))
                if uncited:
                    where = "UNCITED"
            ctx = lines[i - 1].strip()
            if where == "UNCITED":
                ctx = f"found only in uncited {uncited}"
            rows.append((i, n, where, ctx))
        shown = [r for r in rows if args.all or r[2] in ("ORPHAN", "UNCITED")]
        if shown:
            print(f"== {doc}")
            for i, n, where, ctx in shown:
                print(f"  {i:5d}  {n:>10}  {where:10}  {ctx[:90]}")
    print(
        f"\n{orphans} orphan(s) not in the allowlist ({len(allowed)} allowed); "
        f"results files searched: {len(result_files)} current, plus their git history; "
        f"{fallbacks} quote(s) cite no results file and were searched for in all of them"
    )
    if args.update_allowlist and new_orphans:
        # The allowlist is keyed by document and number, so a number quoted twice is one line.
        lines = dict.fromkeys(new_orphans)
        with open(os.path.join(top, ALLOWLIST), "a", encoding="utf-8") as f:
            for doc, n in lines:
                f.write(f"{doc}\t{n}\tREASON NEEDED\n")
        print(f"appended {len(lines)} line(s) to {ALLOWLIST}; edit the reasons")
    return orphans


if __name__ == "__main__":
    sys.exit(min(main(), 255))
