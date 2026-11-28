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

"""Is every Varka tool under dev/ in the tool index, and does the index name only what exists?

  dev/varka_tools_lint.py              # check the index against the tree
  dev/varka_tools_lint.py --self-test  # the check's cases, over fixture names and texts

The index is `sql/varka/.agents/tools.md`: one bullet for each entry under `dev/` whose name
starts with `varka`, a file or a directory, saying what it is for, whether it starts a JVM or a
build, and its usual command line. An agent finds a tool there and not by listing `dev/`, so a
tool the index lacks is a tool nobody is sent to, and a bullet for a tool that is gone sends a
reader to a command that fails.

The entries are the first path components under `dev/` of the files git tracks and of the
untracked files it does not ignore, so a new tool counts before it is added and a build's
leftovers do not. A bullet is a line that opens with "- `dev/<name>`" (a directory may end with a
slash); a name in running text is not a bullet. The findings:

  * an entry with no bullet;
  * a bullet whose entry does not exist;
  * an entry with two bullets;
  * a bullet, read to the next bullet, heading or blank line, without exactly one of the tags the
    index defines: `[sbt]`, `[java]`, `[no JVM]`, `[data]`.

The exit status is the number of findings, 100 at most.
"""

import argparse
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INDEX = "sql/varka/.agents/tools.md"
PREFIX = "varka"
TAGS = ("[sbt]", "[java]", "[no JVM]", "[data]")
BULLET_RE = re.compile(r"^- `dev/(" + PREFIX + r"[^`/]*)/?`")


def tracked_entries(root):
    """The names directly under dev/ that start with the prefix: tracked, or not ignored."""
    pattern = "dev/" + PREFIX + "*"
    out = subprocess.run(
        [
            "git",
            "-C",
            root,
            "ls-files",
            "-z",
            "--cached",
            "--others",
            "--exclude-standard",
            pattern,
        ],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    names = {path.split("/")[1] for path in out.split("\0") if path}
    # A file deleted in the working tree and not yet staged is still in git's index.
    return sorted(n for n in names if os.path.lexists(os.path.join(root, "dev", n)))


def bullets(text):
    """Each bullet of the index as (name, line number, the bullet's text)."""
    found = []
    lines = text.split("\n")
    for i, line in enumerate(lines):
        m = BULLET_RE.match(line)
        if not m:
            continue
        body = [line]
        for rest in lines[i + 1 :]:
            if not rest.strip() or rest.startswith(("- ", "#")):
                break
            body.append(rest)
        found.append((m.group(1), i + 1, "\n".join(body)))
    return found


def check(entries, text, index=INDEX):
    """The findings for the entries under dev/ against the index's text."""
    findings = []
    seen = {}
    for name, lineno, body in bullets(text):
        if name in seen:
            findings.append(
                f"{index}:{lineno}: dev/{name} has a second bullet (the first is at line "
                f"{seen[name]}): keep one"
            )
            continue
        seen[name] = lineno
        if name not in entries:
            findings.append(
                f"{index}:{lineno}: the index names dev/{name}, which does not exist: remove "
                "the bullet, or rename it with the tool"
            )
        tags = [t for t in TAGS if t in body]
        if len(tags) != 1:
            findings.append(
                f"{index}:{lineno}: the bullet of dev/{name} carries {len(tags)} of the tags "
                f"{', '.join(TAGS)}: give it exactly one"
            )
    for name in entries:
        if name not in seen:
            findings.append(
                f'dev/{name} is not in {index}: add a bullet "- `dev/{name}`: ..." to the '
                "section it belongs to, with what it is for, a tag and its usual command line"
            )
    return findings


def self_test():
    failures = []

    def case(name, entries, text, *expected):
        got = check(entries, text)
        ok = len(got) == len(expected) and all(e in g for e, g in zip(expected, got))
        if not ok:
            failures.append(f"{name}: expected {list(expected)}, got {got}")

    a = "- `dev/varka_a.sh`: runs a. `[sbt]` `dev/varka_a.sh`\n"
    d = "- `dev/varka_d/`: the files a reads. `[data]`\n"
    both = ["varka_a.sh", "varka_d"]
    case("every entry indexed", both, "# Index\n\n" + a + d)
    case("an empty tree and an empty index", [], "# Index\n")
    case("a tool with no bullet", both, a, "dev/varka_d is not in")
    case("a bullet for a tool that is gone", ["varka_a.sh"], a + d, "names dev/varka_d, which")
    case("a tool with two bullets", both, a + d + a, "dev/varka_a.sh has a second bullet")
    case(
        "a name in running text is not a bullet",
        both,
        a + "Read `dev/varka_d/` first.\n",
        "dev/varka_d is not in",
    )
    case(
        "a bullet without a tag",
        both,
        a + "- `dev/varka_d/`: the files a reads.\n",
        "dev/varka_d carries 0 of the tags",
    )
    case(
        "a bullet with two tags",
        both,
        d + "- `dev/varka_a.sh`: runs a. `[sbt]` or `[java]`\n",
        "dev/varka_a.sh carries 2 of the tags",
    )
    case(
        "a tag on a continuation line counts",
        both,
        d + "- `dev/varka_a.sh`: runs a,\n  at length. `[no JVM]`\n  `dev/varka_a.sh`\n",
    )
    case(
        "a tag after a blank line belongs to no bullet",
        both,
        d + "- `dev/varka_a.sh`: runs a.\n\n`[sbt]` is a tag.\n",
        "dev/varka_a.sh carries 0 of the tags",
    )
    case(
        "the next bullet ends a bullet",
        both,
        "- `dev/varka_a.sh`: runs a.\n" + d,
        "dev/varka_a.sh carries 0 of the tags",
    )
    case(
        "another prefix is not this lint's",
        ["varka_a.sh"],
        a + "- `dev/lint-java`: Spark's. `[sbt]`\n",
    )
    case(
        "a path below an entry is not a bullet of it",
        both,
        a + "- `dev/varka_d/Probe.java`: one file. `[data]`\n",
        "dev/varka_d is not in",
    )

    # The tree's own lookup: this file is tracked, starts with the prefix and exists.
    here = os.path.basename(__file__)
    if here not in tracked_entries(ROOT):
        failures.append(f"tracked_entries: dev/{here} itself is not among the entries")

    for f in failures:
        print(f"FAIL {f}", file=sys.stderr)
    print(f"varka_tools_lint self-test: {len(failures)} failure(s)")
    return 1 if failures else 0


def main(argv):
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--self-test", action="store_true", help="run the check's cases")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    path = os.path.join(ROOT, INDEX)
    if not os.path.isfile(path):
        print(f"{INDEX} does not exist: the tool index is gone", file=sys.stderr)
        return 1
    with open(path, encoding="utf-8") as f:
        text = f.read()
    entries = tracked_entries(ROOT)
    findings = check(entries, text)
    for finding in findings:
        print(finding, file=sys.stderr)
    if not findings:
        print(f"varka_tools_lint: {len(entries)} entries under dev/, each with one bullet")
    return min(len(findings), 100)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
