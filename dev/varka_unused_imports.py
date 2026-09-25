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
"""Remove the imports the build itself reports unused, from its own log.

    build/sbt catalyst/compile 2>&1 | tee compile.log; dev/varka_unused_imports.py compile.log
    dev/lint-java > lint.log; dev/varka_unused_imports.py lint.log --apply

A file moved out of another inherits every import of its source, and a file that lost members
keeps the imports they needed. Spark's build turns scalac's unused-import warning into an error
(`-Wunused:imports` under `-Werror`), and checkstyle's `UnusedImports` fails `dev/lint-java`, so
both already say exactly which imports are unused, one at a time and usually after several
minutes each. This reads those reports - scalac's "path:line:col: Unused import" and
checkstyle's "path:[line,col] ... Unused import - name." - and removes every named import in one
pass: the whole statement when it imports one name, the one selector from a brace import like
`import a.{B, C}`, which is then rewrapped at 100 columns. It prints what it would change and
writes only with `--apply`; run the build again afterwards, since a removal can reveal the next.
"""

import argparse
import glob
import os
import re
import sys

SCALAC = re.compile(r"(?m)^\[error\] (\S+\.scala):(\d+):(\d+): Unused import$")
CHECKSTYLE = re.compile(
    r"(?m)^\[ERROR\] (\S+\.java):\[(\d+),\d+\] \(imports\) UnusedImports: "
    r"Unused import - ([\w.]+)\.$"
)


def reports(log):
    """(path, line, column or None, name or None) for every unused import in a build log.

    >>> log = '''[error] /r/A.scala:31:3: Unused import
    ... [error]   B, C}
    ... [ERROR] src/main/java/p/X.java:[54,8] (imports) UnusedImports: Unused import - p.Y.Z.
    ... '''
    >>> reports(log)
    [('/r/A.scala', 31, 3, None), ('src/main/java/p/X.java', 54, None, 'p.Y.Z')]
    """
    found = [(p, int(line), int(col), None) for p, line, col in SCALAC.findall(log)]
    found += [(p, int(line), None, name) for p, line, name in CHECKSTYLE.findall(log)]
    return found


def resolve(path, root="."):
    """A reported path as a file under `root`: as given, or found by its tail, since checkstyle
    reports a path relative to the module it runs in."""
    if os.path.exists(path):
        return path
    hits = glob.glob(os.path.join(root, "**", path), recursive=True)
    return hits[0] if len(hits) == 1 else None


def statement_at(lines, index):
    """The first and last line index of the import statement that covers line `index`."""
    start = index
    while not lines[start].lstrip().startswith("import "):
        start -= 1
    end = start
    while lines[end].count("{") > lines[end].count("}") or (
        "{" in "".join(lines[start : end + 1]) and "}" not in "".join(lines[start : end + 1])
    ):
        end += 1
    return start, end


def drop_selector(statement, offset):
    """The Scala import `statement` without the selector at character `offset`, or None when
    it was the only name, so the whole statement goes.

    >>> drop_selector("import a.b.{C, D, E}", 15)
    'import a.b.{C, E}'
    >>> drop_selector("import a.b.{C, D}", 12)
    'import a.b.D'
    >>> drop_selector("import a.b.{C => X, D}", 20) is None
    False
    >>> drop_selector("import a.b.C", 11) is None
    True
    """
    if "{" not in statement:
        return None
    head, _, rest = statement.partition("{")
    body = rest[: rest.rindex("}")]
    cut = offset - len(head) - 1
    parts, pos = [], 0
    for chunk in body.split(","):
        parts.append((pos, pos + len(chunk), chunk.strip()))
        pos += len(chunk) + 1
    keep = [text for lo, hi, text in parts if not (lo <= cut < hi)]
    if not keep:
        return None
    if len(keep) == 1 and "=>" not in keep[0]:
        return head + keep[0]
    return head + "{" + ", ".join(keep) + "}"


def wrap(statement, width=100):
    """Spark's wrapping of a long brace import: continuation lines indented two spaces.

    >>> for line in wrap("import a.{" + ", ".join("Name%d" % i for i in range(14)) + "}", 50):
    ...     print(line)
    import a.{Name0, Name1, Name2, Name3, Name4,
      Name5, Name6, Name7, Name8, Name9, Name10,
      Name11, Name12, Name13}
    """
    if len(statement) <= width:
        return [statement]
    words = statement.split(" ")
    lines, current = [], ""
    for w in words:
        candidate = (current + " " + w) if current else w
        if len(candidate) > width and current:
            lines.append(current)
            current = "  " + w
        else:
            current = candidate
    lines.append(current)
    return lines


def plan_file(path, hits):
    """The file's new lines and a description of each change, applying hits bottom-up."""
    with open(path, encoding="utf-8") as handle:
        lines = handle.read().split("\n")
    changes = []
    done = set()
    for line, col, name in sorted(hits, key=lambda h: (h[0], h[1] or 0), reverse=True):
        idx = line - 1
        if name is not None:
            pattern = re.compile(r"^import (static )?" + re.escape(name) + r";$")
            if pattern.match(lines[idx].strip()):
                changes.append(f"{path}:{line}: removed {lines[idx].strip()}")
                del lines[idx]
            continue
        start, end = statement_at(lines, idx)
        if (start, col, line) in done:
            continue
        done.add((start, col, line))
        joined, offset = "", None
        for k in range(start, end + 1):
            text = lines[k].strip() if k > start else lines[k].rstrip()
            if k == idx:
                indent = len(lines[k]) - len(lines[k].lstrip())
                base = len(joined) + (1 if joined else 0)
                offset = base + (col - 1 - (indent if k > start else 0))
            joined = (joined + " " + text) if joined else text
        joined = joined.replace("{ ", "{").replace(" }", "}")
        new = drop_selector(joined, offset)
        old = lines[start : end + 1]
        lines[start : end + 1] = [] if new is None else wrap(new)
        changes.append(
            f"{path}:{line}: {' '.join(s.strip() for s in old)}  ->  {new or '(removed)'}"
        )
    return lines, changes


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[1])
    parser.add_argument("log", nargs="?", help="a build log; standard input when omitted")
    parser.add_argument("--apply", action="store_true", help="edit the files")
    parser.add_argument("--root", default=".", help="where to look for module-relative paths")
    args = parser.parse_args()
    log = open(args.log, encoding="utf-8").read() if args.log else sys.stdin.read()
    by_file = {}
    for path, line, col, name in reports(log):
        real = resolve(path, args.root)
        if real is None:
            print(f"cannot find {path}; skipped", file=sys.stderr)
            continue
        by_file.setdefault(real, []).append((line, col, name))
    if not by_file:
        print("no unused imports reported in the log")
        return
    for path, hits in sorted(by_file.items()):
        lines, changes = plan_file(path, hits)
        for change in changes:
            print(change)
        if args.apply:
            with open(path, "w", encoding="utf-8") as handle:
                handle.write("\n".join(lines))
    if not args.apply:
        print("\ndry run; pass --apply to edit the files, then build again")


if __name__ == "__main__":
    main()
