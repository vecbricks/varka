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
"""The calls that cross between named groups of members, for deciding where to cut a file.

    dev/varka_callgraph.py FILE... --group chrono='emitChrono.*|emitTrunc.*' --group div='.*Divide'
    dev/varka_callgraph.py A.java B.java --by-file     # each file is a group

Before a file is split, the question is which members of the part being moved call members
that stay, and the other way round: every such call becomes a cross-file reference, and the
seam with the fewest is the one to cut. This prints, for each ordered pair of groups, the
callees on the far side and the callers that reach them. A group is a regular expression
matched against a member's name (or its qualified `Owner.name`); a member no group matches is
in "rest". `--by-file` makes each file its own group, which answers the same question after the
split: what still crosses.

The map is `dev/varka_members.py`'s, so a callee is a name used as a word, which can over-report
an overloaded name and never misses a call; plain fields are left out unless `--fields` asks.
"""

import argparse
import collections
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from varka_members import load


def group_of(member, groups, by_file):
    """The first group whose pattern matches the member's name or qualified name.

    >>> from varka_members import Member
    >>> m = Member("A.java", "A", "emitTruncMonth", "method", 1, 1, 2, "")
    >>> group_of(m, [("chrono", re.compile("emitTrunc.*"))], False)
    'chrono'
    >>> group_of(m, [("div", re.compile(".*Divide"))], False)
    'rest'
    >>> group_of(m, [], True)
    'A.java'
    """
    if by_file:
        return os.path.basename(member.file)
    for name, pattern in groups:
        if pattern.fullmatch(member.name) or pattern.fullmatch(member.qualified):
            return name
    return "rest"


def crossings(members, groups, by_file):
    """{(from group, to group): {callee: {callers}}} over the calls that cross groups."""
    by_name = {m.qualified: m for m in members}
    where = {m.qualified: group_of(m, groups, by_file) for m in members}
    out = collections.defaultdict(lambda: collections.defaultdict(set))
    for m in members:
        for callee in m.callees:
            src, dst = where[m.qualified], where[callee]
            if src != dst and callee in by_name:
                out[(src, dst)][callee].add(m.qualified)
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[1])
    parser.add_argument("files", nargs="+")
    parser.add_argument(
        "--group", action="append", default=[], metavar="NAME=REGEX", help="a named group"
    )
    parser.add_argument("--by-file", action="store_true", help="each file is a group")
    parser.add_argument("--fields", action="store_true", help="count plain fields as callees")
    args = parser.parse_args()
    groups = []
    for spec in args.group:
        name, sep, regex = spec.partition("=")
        if not sep:
            sys.exit(f"--group wants NAME=REGEX, got {spec!r}")
        groups.append((name, re.compile(regex)))
    if not groups and not args.by_file:
        sys.exit("give at least one --group, or --by-file")
    members = load(args.files, args.fields)
    counts = collections.Counter(group_of(m, groups, args.by_file) for m in members)
    print("members per group: " + ", ".join(f"{g} {n}" for g, n in sorted(counts.items())))
    table = crossings(members, groups, args.by_file)
    for src, dst in sorted(table):
        callees = table[(src, dst)]
        print(f"\n== {src} -> {dst}: {len(callees)} callees")
        for callee in sorted(callees):
            callers = sorted(c.split(".", 1)[1] for c in callees[callee])
            print(f"  {callee.split('.', 1)[1]:32} <- {', '.join(callers)}")
    if not table:
        print("\nno call crosses between the groups")


if __name__ == "__main__":
    main()
