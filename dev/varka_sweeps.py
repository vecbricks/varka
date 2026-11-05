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
  dev/varka_sweeps.py --matrix     # the scheduled jobs' suites and shards, as JSON
  dev/varka_sweeps.py --self-test  # the check's cases, over fixture trees

An opt-in sweep is a test that does nothing unless a system property switches it on
(`-Dvarka.sweep=true`, or `varka.sweep.long` for the ones over about ten minutes). A PR's Build
runs none of them, so which job runs a sweep is a fact of its own, kept in
`sql/varka/ci/sweeps.tsv`, one row a suite:

  suite     the test class's simple name
  kind      `java` (JUnit) or `scala` (ScalaTest), the file's extension
  property  the system property that switches the suite's sweeps on
  how       `class` runs the whole suite with the property set; `filter` selects a ScalaTest
            suite's opt-in tests by name (`-z opt-in`); `shard` runs the part of a JUnit suite
            whose test names contain one of `select`'s texts
  select    `-` for a whole suite, the filter's text for `filter`, and for `shard` the texts
            joined by `|`, each a `-z` of dev/varka_matrix.sh's runner, which matches a JUnit
            test's display name
  job       the scheduled job that runs it, or `unscheduled`
  note      what it sweeps, and how long

A suite too long for one job has one `shard` row a part; the shards of a suite must between them
select each of its opt-in tests (a display name saying `opt-in`) exactly once, so that a sweep added
later falls in a shard or fails the check.

The check reads every test source that reads a sweep property and fails when
  * a suite that reads one has no row, so a new sweep cannot go unscheduled unseen;
  * a row names a suite that no longer reads one, or whose kind or property is not the file's;
  * a JUnit suite is listed as `filter`: sbt's name filter selects no test of a JUnit suite, which
    is how the sweeps of five JUnit suites ran in no job at all while the nightly's step reported
    a pass, and not every sweep's display name says `opt-in`;
  * a suite is listed twice, mixes `class` or `filter` with `shard`, or a row lacks a field;
  * a shard's text selects no opt-in test, or an opt-in test is selected by no shard or by two.
"""

import argparse
import json
import os
import re
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFEST = "sql/varka/ci/sweeps.tsv"
COLUMNS = ["suite", "kind", "property", "how", "select", "job", "note"]
# A test reads a sweep property through a property accessor; a string that only mentions the name,
# as `contains("varka.sweep")` does in a check of the sources, reads nothing.
READS = re.compile(
    r"""(?:getProperty|getBoolean|getInteger|props\.get|props)\(\s*"""
    r"""["'](varka\.sweep(?:\.long)?)["']"""
)


# A JUnit test's display name, which may be a literal split over lines with `+`.
DISPLAY = re.compile(r"""@DisplayName\(\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)\)""", re.S)
SHARD_SEP = "|"


def display_names(path):
    """The display names of a JUnit source's tests."""
    with open(path, encoding="utf-8", errors="replace") as f:
        text = f.read()
    return ["".join(re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))) for m in DISPLAY.finditer(text)]


def class_name(path):
    """The fully qualified name of the test class a source declares, from its path."""
    tail = re.split(r"src/test/(?:java|scala)/", path.replace(os.sep, "/"), maxsplit=1)[-1]
    return os.path.splitext(tail)[0].replace("/", ".")


def discover(root):
    """The test sources that read a sweep property: {suite: (kind, properties, path)}."""
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
                found[stem] = (ext[1:], props, os.path.join(dirpath, name))
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
    by_suite = {}
    seen = set()
    for n, r in rows:
        key = (r["suite"], r["select"])
        if key in seen:
            findings.append(f"{MANIFEST}:{n}: {r['suite']} / {r['select']} is listed twice")
            continue
        seen.add(key)
        by_suite.setdefault(r["suite"], []).append((n, r))
    for suite, group in by_suite.items():
        n0 = group[0][0]
        if suite not in found:
            findings.append(
                f"{MANIFEST}:{n0}: {suite} reads no sweep property in any test source; "
                "delete the row, or the suite's sweep went"
            )
            continue
        kind, props, path = found[suite]
        hows = {r["how"] for _, r in group}
        if "shard" in hows and hows != {"shard"}:
            findings.append(
                f"{MANIFEST}:{n0}: {suite} mixes `shard` rows with `class` or `filter` rows"
            )
        if hows == {"class"} and len(group) > 1:
            findings.append(f"{MANIFEST}:{n0}: {suite} has more than one `class` row")
        for n, r in group:
            if r["kind"] != kind:
                findings.append(f"{MANIFEST}:{n}: {suite} is a {kind} suite, not {r['kind']}")
            if r["property"] not in props:
                findings.append(
                    f"{MANIFEST}:{n}: {suite} reads {', '.join(sorted(props))}, not {r['property']}"
                )
            how = r["how"]
            if how not in ("class", "filter", "shard"):
                findings.append(f"{MANIFEST}:{n}: how is `class`, `filter` or `shard`, not {how}")
            elif how == "class" and r["select"] != "-":
                findings.append(f"{MANIFEST}:{n}: a `class` row's select is `-`")
            elif how == "filter" and kind == "java":
                findings.append(
                    f"{MANIFEST}:{n}: {suite} is a JUnit suite, whose sweeps sbt's name filter "
                    "selects none of; list it as `class`, or as `shard` rows"
                )
            elif how == "shard" and kind != "java":
                findings.append(f"{MANIFEST}:{n}: only a JUnit suite has `shard` rows")
        if hows == {"shard"} and kind == "java":
            findings.extend(shard_findings(suite, group, path))
    for suite in sorted(found):
        if suite not in by_suite:
            props = ", ".join(sorted(found[suite][1]))
            findings.append(
                f"{MANIFEST}: {suite} reads a sweep property ({props}) and has no row; "
                "add one, naming the job that runs it or `unscheduled`"
            )
    return findings


def shard_findings(suite, group, path):
    """The findings on a suite's shards: each opt-in test selected once, each text selecting one."""
    findings = []
    opt_in = [n for n in display_names(path) if "opt-in" in n]
    texts = [(n, t) for n, r in group for t in r["select"].split(SHARD_SEP)]
    for test in opt_in:
        hit = {n for n, t in texts if t in test}
        if not hit:
            findings.append(f"{MANIFEST}: {suite}'s test `{test}` is selected by no shard")
        elif len(hit) > 1:
            findings.append(
                f"{MANIFEST}: {suite}'s test `{test}` is selected by the shards of lines "
                f"{', '.join(map(str, sorted(hit)))}"
            )
    for n, t in texts:
        if not any(t in test for test in opt_in):
            findings.append(
                f"{MANIFEST}:{n}: the shard text `{t}` selects no opt-in test of {suite}"
            )
    return findings


def matrix(root):
    """The scheduled jobs: one entry for each row whose job is not `unscheduled`."""
    rows, _ = manifest(root)
    found = discover(root)
    jobs = []
    for _, r in rows:
        if r["job"] == "unscheduled" or r["suite"] not in found:
            continue
        zs = [] if r["select"] == "-" else r["select"].split(SHARD_SEP)
        if r["how"] == "filter":
            zs = [r["select"]]
        jobs.append(
            {
                "suite": r["suite"],
                "class": class_name(found[r["suite"]][2]),
                "property": r["property"],
                "z": zs,
                "job": r["job"],
            }
        )
    return jobs


def self_test():
    failures = []

    def tree(files):
        d = tempfile.mkdtemp()
        for rel, text in files.items():
            path = os.path.join(d, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(text)
        return d

    base = "sql/catalyst/src/test/java/p/"
    sc = "sql/catalyst/src/test/scala/p/"
    head = "\t".join(COLUMNS) + "\n"

    def rows(*lines):
        return head + "".join("\t".join(line.split()) + "\n" for line in lines)

    java = 'class A { boolean on = "true".equals(System.getProperty("varka.sweep")); }\n'
    scala = 'class B { val on = sys.props.get("varka.sweep.long").contains("true") }\n'
    shards = (
        'class S { boolean on = "true".equals(System.getProperty("varka.sweep"));\n'
        '  @DisplayName("every pair for + in LEGACY (opt-in: -Dvarka.sweep=true)") void a() {}\n'
        '  @DisplayName("every pair for - in LEGACY "\n'
        '      + "(opt-in: -Dvarka.sweep=true)") void b() {}\n'
        '  @DisplayName("a case that runs in every build") void c() {} }\n'
    )
    both = {base + "A.java": java, sc + "B.scala": scala}
    good = rows(
        "A java varka.sweep class - unscheduled x", "B scala varka.sweep.long filter opt-in n x"
    )
    shard_files = {base + "S.java": shards}

    def shard_rows(*texts):
        return head + "".join(f"S\tjava\tvarka.sweep\tshard\t{t}\tunscheduled\tx\n" for t in texts)

    def expect(name, files, want):
        got = check(tree(files))
        ok = (not got) if want is None else any(want in g for g in got)
        if not ok:
            failures.append(f"{name}: wanted {want or 'no finding'}, got {got}")

    expect("a complete manifest", {**both, MANIFEST: good}, None)
    expect("a sweep with no row", {**both, MANIFEST: rows(good.split("\n")[1])}, "B reads a sweep")
    expect(
        "a row for a suite with no sweep", {base + "A.java": java, MANIFEST: good}, "reads no sweep"
    )
    expect(
        "a JUnit suite listed as filter",
        {**both, MANIFEST: good.replace("class\t-", "filter\topt-in")},
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
        "a bad how", {**both, MANIFEST: good.replace("\tclass\t", "\tsome\t")}, "`class`, `filter`"
    )
    expect(
        "a class row with a select",
        {**both, MANIFEST: good.replace("class\t-", "class\tx")},
        "is `-`",
    )
    expect("a duplicate", {**both, MANIFEST: good + good.split("\n")[1] + "\n"}, "listed twice")
    expect(
        "an empty field", {**both, MANIFEST: good.replace("unscheduled", "")}, "non-empty fields"
    )
    expect("a missing manifest", both, "missing")
    expect("a wrong header", {**both, MANIFEST: "suite\tkind\n"}, "header")
    expect(
        "a mention outside a property read reads nothing",
        {sc + "C.scala": "// -Dvarka.sweep=true\n", MANIFEST: head},
        None,
    )
    expect(
        "a string that names the property but reads nothing",
        {sc + "C.scala": 'val t = body.contains("varka.sweep")\n', MANIFEST: head},
        None,
    )
    expect(
        "a file outside the test tree reads nothing",
        {"sql/catalyst/src/main/java/p/D.java": java, MANIFEST: head},
        None,
    )
    expect(
        "shards that cover every opt-in test",
        {**shard_files, MANIFEST: shard_rows("for +", "for -")},
        None,
    )
    expect(
        "a test no shard selects",
        {**shard_files, MANIFEST: shard_rows("for +")},
        "selected by no shard",
    )
    expect(
        "a test two shards select",
        {**shard_files, MANIFEST: shard_rows("for +|for -", "LEGACY")},
        "selected by the shards of lines",
    )
    expect(
        "a shard text that selects nothing",
        {**shard_files, MANIFEST: shard_rows("for +", "for -", "for *")},
        "selects no opt-in test",
    )
    expect(
        "shards mixed with a class row",
        {
            **shard_files,
            MANIFEST: rows(
                "S java varka.sweep shard for+ unscheduled x",
                "S java varka.sweep class - unscheduled x",
            ),
        },
        "mixes `shard`",
    )
    expect(
        "shards of a Scala suite",
        {sc + "B.scala": scala, MANIFEST: rows("B scala varka.sweep.long shard x unscheduled x")},
        "only a JUnit suite",
    )
    fixture = tree(
        {
            **shard_files,
            MANIFEST: rows(
                "S java varka.sweep shard for+ nightly-x86 x",
                "S java varka.sweep shard for- unscheduled x",
            ),
        }
    )
    jobs = matrix(fixture)
    if [(j["class"], j["z"]) for j in jobs] != [("p.S", ["for+"])]:
        failures.append(f"the matrix of one scheduled shard: got {jobs}")
    for f in failures:
        print("FAIL", f)
    if not failures:
        print("varka_sweeps selftest: ok")
    return len(failures)


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--check", action="store_true", help="the default action")
    ap.add_argument("--list", action="store_true", help="print the manifest")
    ap.add_argument("--matrix", action="store_true", help="print the scheduled jobs as JSON")
    ap.add_argument("--self-test", action="store_true", help="run the check's cases")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    if args.list:
        rows, findings = manifest(ROOT)
        for _, r in rows:
            print("\t".join(r[c] for c in COLUMNS[:6]))
        return len(findings)
    if args.matrix:
        print(json.dumps(matrix(ROOT), indent=1))
        return 0
    findings = check(ROOT)
    for f in findings:
        print(f, file=sys.stderr)
    if not findings:
        rows, _ = manifest(ROOT)
        print(f"varka_sweeps: {len(rows)} rows listed, none missing")
    return min(len(findings), 100)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
