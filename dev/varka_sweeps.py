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
"""Every exhaustive sweep is listed once, and listed the way a job can select it (VARKA-573).

  dev/varka_sweeps.py --check      # fail on a sweep the manifest misses or lists wrongly
  dev/varka_sweeps.py --list       # print the manifest
  dev/varka_sweeps.py --self-test  # the check's cases, over fixture trees

An opt-in sweep is a test that does nothing unless a system property switches it on
(`-Dvarka.sweep=true`, or `varka.sweep.long` for the ones over about ten minutes). A PR's Build
runs none of them, so which job runs a sweep is a fact of its own, kept in
`sql/varka/ci/sweeps.tsv`, one row a suite:

  suite     the test class's simple name
  kind      `java` (JUnit) or `scala` (ScalaTest), the file's extension
  property  the system property that switches the suite's sweeps on
  how       `class` runs the whole suite with the property set; `filter` selects its opt-in tests
            by name (`-z opt-in`)
  job       the scheduled job that runs it, or `unscheduled`
  note      what it sweeps, and how long

The check reads every test source that reads a sweep property and fails when
  * a suite that reads one has no row, so a new sweep cannot go unscheduled unseen;
  * a row names a suite that no longer reads one, or whose kind or property is not the file's;
  * a JUnit suite is listed as `filter`: a name filter selects no test of a JUnit suite, which is
    how the sweeps of five JUnit suites ran in no job at all while the nightly's step reported a
    pass;
  * a suite is listed twice, or a row lacks a field.
"""

import argparse
import os
import re
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFEST = "sql/varka/ci/sweeps.tsv"
COLUMNS = ["suite", "kind", "property", "how", "job", "note"]
# A test reads a sweep property by naming it in a string literal.
READS = re.compile(r"""["'](varka\.sweep(?:\.long)?)["']""")


def discover(root):
    """The test sources that read a sweep property: {suite: (kind, set of properties)}."""
    found = {}
    top = os.path.join(root, "sql")
    for dirpath, _, names in os.walk(top):
        if os.sep + "src" + os.sep + "test" + os.sep not in dirpath + os.sep:
            continue
        for name in names:
            stem, ext = os.path.splitext(name)
            if ext not in (".java", ".scala"):
                continue
            with open(os.path.join(dirpath, name), encoding="utf-8", errors="replace") as f:
                props = set(READS.findall(f.read()))
            if props:
                found[stem] = (ext[1:], props)
    return found


def manifest(root):
    """The manifest's rows as (line number, dict), and the findings reading it raised."""
    path = os.path.join(root, MANIFEST)
    rows, findings = [], []
    try:
        with open(path, encoding="utf-8") as f:
            lines = f.read().splitlines()
    except OSError:
        return rows, [f"{MANIFEST}: missing"]
    if not lines or lines[0].split("\t") != COLUMNS:
        return rows, [f"{MANIFEST}:1: the header is not {chr(9).join(COLUMNS)}"]
    for n, line in enumerate(lines[1:], 2):
        if not line.strip():
            continue
        cells = line.split("\t")
        if len(cells) != len(COLUMNS) or not all(c.strip() for c in cells):
            findings.append(f"{MANIFEST}:{n}: a row has {len(COLUMNS)} non-empty fields")
            continue
        rows.append((n, dict(zip(COLUMNS, cells))))
    return rows, findings


def check(root):
    """The findings, one string each."""
    rows, findings = manifest(root)
    found = discover(root)
    seen = {}
    for n, r in rows:
        suite = r["suite"]
        if suite in seen:
            findings.append(f"{MANIFEST}:{n}: {suite} is listed twice (line {seen[suite]})")
            continue
        seen[suite] = n
        if suite not in found:
            findings.append(
                f"{MANIFEST}:{n}: {suite} reads no sweep property in any test source; "
                "delete the row, or the suite's sweep went"
            )
            continue
        kind, props = found[suite]
        if r["kind"] != kind:
            findings.append(f"{MANIFEST}:{n}: {suite} is a {kind} suite, not {r['kind']}")
        if r["property"] not in props:
            findings.append(
                f"{MANIFEST}:{n}: {suite} reads {', '.join(sorted(props))}, not {r['property']}"
            )
        if r["how"] not in ("class", "filter"):
            findings.append(f"{MANIFEST}:{n}: how is `class` or `filter`, not {r['how']}")
        elif r["how"] == "filter" and kind == "java":
            findings.append(
                f"{MANIFEST}:{n}: {suite} is a JUnit suite, which a name filter selects no test "
                "of; list it as `class`"
            )
    for suite in sorted(found):
        if suite not in seen:
            props = ", ".join(sorted(found[suite][1]))
            findings.append(
                f"{MANIFEST}: {suite} reads a sweep property ({props}) and has no row; "
                "add one, naming the job that runs it or `unscheduled`"
            )
    return findings


def self_test():
    failures = []

    def tree(files):
        d = tempfile.mkdtemp()
        for rel, text in files.items():
            p = os.path.join(d, rel)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "w", encoding="utf-8") as f:
                f.write(text)
        return d

    base = "sql/catalyst/src/test/java/p/"
    sc = "sql/catalyst/src/test/scala/p/"
    head = "\t".join(COLUMNS) + "\n"
    java = 'class A { boolean on = "true".equals(System.getProperty("varka.sweep")); }\n'
    scala = 'class B { val on = sys.props.get("varka.sweep.long").contains("true") }\n'

    def expect(name, files, want):
        got = check(tree(files))
        ok = (not got) if want is None else any(want in g for g in got)
        if not ok:
            failures.append(f"{name}: wanted {want or 'no finding'}, got {got}")

    good = (
        head
        + "A\tjava\tvarka.sweep\tclass\tunscheduled\tx\nB\tscala\tvarka.sweep.long\tfilter\tn\tx\n"
    )
    both = {base + "A.java": java, sc + "B.scala": scala}
    expect("a complete manifest", {**both, MANIFEST: good}, None)
    expect(
        "a sweep with no row",
        {**both, MANIFEST: head + good.split("\n")[1] + "\n"},
        "B reads a sweep property",
    )
    expect(
        "a row for a suite with no sweep",
        {base + "A.java": java, MANIFEST: good},
        "B reads no sweep property",
    )
    expect(
        "a JUnit suite listed as filter",
        {
            **both,
            MANIFEST: good.replace("A\tjava\tvarka.sweep\tclass", "A\tjava\tvarka.sweep\tfilter"),
        },
        "JUnit suite",
    )
    expect(
        "a wrong kind", {**both, MANIFEST: good.replace("A\tjava", "A\tscala")}, "is a java suite"
    )
    expect(
        "a wrong property",
        {**both, MANIFEST: good.replace("B\tscala\tvarka.sweep.long", "B\tscala\tvarka.sweep")},
        "reads varka.sweep.long, not varka.sweep",
    )
    expect(
        "a bad how",
        {**both, MANIFEST: good.replace("\tclass\t", "\tsome\t")},
        "`class` or `filter`",
    )
    expect("a duplicate", {**both, MANIFEST: good + good.split("\n")[1] + "\n"}, "listed twice")
    expect(
        "an empty field", {**both, MANIFEST: good.replace("unscheduled", "")}, "non-empty fields"
    )
    expect("a missing manifest", both, "missing")
    expect("a wrong header", {**both, MANIFEST: "suite\tkind\n"}, "header")
    expect(
        "a mention outside a string literal reads nothing",
        {sc + "C.scala": "// -Dvarka.sweep=true\n", MANIFEST: head},
        None,
    )
    expect(
        "a file outside the test tree reads nothing",
        {"sql/catalyst/src/main/java/p/D.java": java, MANIFEST: head},
        None,
    )
    for f in failures:
        print("FAIL", f)
    if not failures:
        print("varka_sweeps selftest: ok")
    return len(failures)


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--check", action="store_true", help="the default action")
    ap.add_argument("--list", action="store_true", help="print the manifest")
    ap.add_argument("--self-test", action="store_true", help="run the check's cases")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    if args.list:
        rows, findings = manifest(ROOT)
        for _, r in rows:
            print("\t".join(r[c] for c in COLUMNS[:5]))
        return len(findings)
    findings = check(ROOT)
    for f in findings:
        print(f, file=sys.stderr)
    if not findings:
        rows, _ = manifest(ROOT)
        print(f"varka_sweeps: {len(rows)} suites listed, none missing")
    return min(len(findings), 100)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
