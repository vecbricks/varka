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

A quote is a number with two or more integer digits and one to three decimals
(3452.2, 481.7, 4785.665) that is not a percentage, a ratio suffixed with x, or
part of a version. That shape is what benchmark rates and JMH scores look like
and what section numbers, versions and op counts do not. Each one must appear
in a results file under sql/*/benchmarks/ or sql/varka/engine/benchmarks/,
either as committed now or in any committed version of those files (plans
legitimately quote the number a change moved away from), and the report says
which. A number found nowhere is an orphan: a scratch-log figure, a typo, or a
result that was never regenerated into the tree.

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

DOCS = ["SKILLS.md", "README.md", "docs/sql-varka.md", "sql/varka/AGENTS.md"]
DOC_GLOBS = ["sql/varka/plans/*.md"]
RESULT_GLOBS = [
    "sql/*/benchmarks/*.txt",
    "sql/varka/engine/benchmarks/*.txt",
    "sql/varka/bench/benchmarks/*.txt",
]
ALLOWLIST = "dev/varka_quote_allowlist.txt"
QUOTE = re.compile(r"(?<![\w.\-])(\d{2,}\.\d{1,3})(?![\w.%]|\s*x\b|\s*x\)|\s*x,|\s*x\.)")


def root():
    return subprocess.run(
        ["git", "rev-parse", "--show-toplevel"], check=True, capture_output=True, text=True
    ).stdout.strip()


def numbers_in(text):
    return set(QUOTE.findall(text))


def committed_numbers(top):
    """Every quote-shaped number in every committed version of every results file."""
    files = sorted({p for g in RESULT_GLOBS for p in glob.glob(os.path.join(top, g))})
    rel = [os.path.relpath(p, top) for p in files]
    current = set()
    for p in files:
        with open(p, encoding="utf-8", errors="replace") as f:
            current |= numbers_in(f.read())
    # `git log -p` over the results directories: additions and removals both count,
    # since a plan may quote the number a regeneration replaced.
    #
    # --full-history is load-bearing, not defensive. Results files are regenerated rather
    # than merged textually, so merging master into a task branch resolves them by taking
    # one side whole - and that leaves the merge TREESAME to that side for these paths, so
    # git's default history simplification prunes the other parent's entire line. Every
    # version the other branch wrote then becomes invisible here, and a plan quoting one of
    # its numbers is reported as an orphan although the number is committed and correct.
    # Task 60 hit exactly this: 16 orphans on the branch against 0 on master, for numbers
    # master had measured. --full-history keeps the promise this tool's docstring makes -
    # any committed version of these files - and costs nothing measurable, since the
    # directories are small (both forms ran in 1.1s over ~50 MB of patch text).
    dirs = sorted({os.path.dirname(r) for r in rel})
    log = subprocess.run(
        ["git", "-C", top, "log", "-p", "--full-history", "--format=", "--", *dirs],
        check=True,
        capture_output=True,
        text=True,
        errors="replace",
    ).stdout
    historical = numbers_in(log)
    return current, historical, rel


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
    args = p.parse_args()
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
    current, historical, result_files = committed_numbers(top)

    orphans = 0
    new_orphans = []
    for doc in docs:
        path = os.path.join(top, doc)
        if not os.path.isfile(path):
            continue
        with open(path, encoding="utf-8") as f:
            lines = f.read().splitlines()
        rows = []
        for i, line in enumerate(lines, 1):
            for n in QUOTE.findall(line):
                if n in current:
                    where = "committed"
                elif n in historical:
                    where = "historical"
                elif (doc, n) in allowed:
                    where = "allowed"
                else:
                    where = "ORPHAN"
                    orphans += 1
                    new_orphans.append((doc, n))
                rows.append((i, n, where, line.strip()))
        shown = [r for r in rows if args.all or r[2] == "ORPHAN"]
        if shown:
            print(f"== {doc}")
            for i, n, where, ctx in shown:
                print(f"  {i:5d}  {n:>10}  {where:10}  {ctx[:90]}")
    print(
        f"\n{orphans} orphan(s) not in the allowlist ({len(allowed)} allowed); "
        f"results files searched: {len(result_files)} current, plus their git history"
    )
    if args.update_allowlist and new_orphans:
        with open(os.path.join(top, ALLOWLIST), "a", encoding="utf-8") as f:
            for doc, n in new_orphans:
                f.write(f"{doc}\t{n}\tREASON NEEDED\n")
        print(f"appended {len(new_orphans)} line(s) to {ALLOWLIST}; edit the reasons")
    return orphans


if __name__ == "__main__":
    sys.exit(min(main(), 255))
