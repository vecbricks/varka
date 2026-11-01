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
"""Merge Varka's generated files per key, so that two branches adding rows never hand-merge.

  dev/varka_generated_merge.py driver BASE OURS THEIRS PATH  # the git merge driver
  dev/varka_generated_merge.py --check      # fail on a generated file missing a coverage row
  dev/varka_generated_merge.py --self-test  # the merge's cases, in a temporary repository

Every type row regenerates the same few files - coverage.json, emitted_bytes.json,
width_audit.json, types.tsv, the census and the cast tables, and the generated blocks of
docs/sql-varka.md - so a merge of master into a branch conflicted in them whenever both sides
added rows (VARKA-546). Most of each file is per row: a coverage expression keyed by its SQL,
a row's method digests, a shape's width-audit refusals, a TSV row keyed by its leading columns.
This driver merges those parts per key, exactly, and writes the file back in the form the suite
writes (Jackson's pretty printer for the JSON files, which `render_json` reproduces byte for
byte). What it cannot compute - a digest over every row (`option_arms`), the docs file's
counts, two rows added at the same place in a list whose order the suite's source decides - it
writes from one side, chosen so that the result is the same whichever side is "ours", and
lists as owed: on stderr, and in `$(git rev-parse --git-path varka-regen-owed)`, which
`dev/varka_regen_generated.sh` reads to run only the owed regenerations, in order.

So a generated file never ends a merge with conflict markers. A conflict in the docs file's
prose, outside its generated blocks, is a real one and is left with its markers.

Installed by `dev/varka_precommit.sh --install-hook`; `sql/varka/.gitattributes` names the
files. `--check` is the CI half: the width audit's own check is cancelled on any CPU but the
file's host, so CI could not see a width audit (or a bytes file) missing a coverage row.
"""

import json
import os
import subprocess
import sys
import tempfile

# --- the JSON form the suites write --------------------------------------------------------


def render_json(value, depth=0):
    """Jackson's DefaultPrettyPrinter as the suites use it: objects indent two spaces per
    object level, arrays stay on the object's line, and empty containers are `{ }` and `[ ]`.

    >>> print(render_json({"a": [1, {"b": []}], "c": {}}))
    {
      "a" : [ 1, {
        "b" : [ ]
      } ],
      "c" : { }
    }
    """
    pad = "  " * depth
    if isinstance(value, dict):
        if not value:
            return "{ }"
        items = [
            f"{pad}  {json.dumps(k, ensure_ascii=False)} : {render_json(v, depth + 1)}"
            for k, v in value.items()
        ]
        return "{\n" + ",\n".join(items) + "\n" + pad + "}"
    if isinstance(value, list):
        if not value:
            return "[ ]"
        return "[ " + ", ".join(render_json(v, depth) for v in value) + " ]"
    return json.dumps(value, ensure_ascii=False)


# --- the three-way merge -------------------------------------------------------------------

MISSING = object()


class Owed:
    """The parts of one file the merge could not compute, as dotted paths."""

    def __init__(self):
        self.paths = []

    def add(self, path):
        self.paths.append(path or "<root>")


def canon(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False)


def pick(ours, theirs):
    """One of two conflicting values, the same whichever is ours."""
    return ours if canon(ours) <= canon(theirs) else theirs


def is_sorted(keys):
    return all(a < b for a, b in zip(keys, keys[1:]))


def merge_value(base, ours, theirs, path, owed):
    """Three-way merge of one JSON value; `base` is MISSING when both sides added it."""
    if canon(ours) == canon(theirs):
        return ours
    if base is not MISSING and canon(base) == canon(ours):
        return theirs
    if base is not MISSING and canon(base) == canon(theirs):
        return ours
    if isinstance(ours, dict) and isinstance(theirs, dict):
        b = base if isinstance(base, dict) else {}
        items = merge_seq(
            list(b.items()),
            list(ours.items()),
            list(theirs.items()),
            key=lambda kv: kv[0],
            value=lambda kv: kv[1],
            make=lambda k, v: (k, v),
            path=path,
            owed=owed,
        )
        return dict(items)
    if isinstance(ours, list) and isinstance(theirs, list):
        b = base if isinstance(base, list) else []
        every = b + ours + theirs
        if every and all(isinstance(x, dict) and "sql" in x for x in every):
            return merge_seq(
                b,
                ours,
                theirs,
                key=lambda x: x["sql"],
                value=lambda x: x,
                make=lambda k, v: v,
                path=path,
                owed=owed,
            )
        if all(isinstance(x, str) for x in every) and all(is_sorted(s) for s in (b, ours, theirs)):
            # A sorted set (`catalyst_classes_admitted`): a three-way set merge, kept sorted.
            bs, os_, ts = set(b), set(ours), set(theirs)
            return sorted((bs & os_ & ts) | (os_ - bs) | (ts - bs))
    owed.add(path)
    return pick(ours, theirs)


def merge_seq(base, ours, theirs, key, value, make, path, owed):
    """Three-way merge of an ordered sequence of keyed entries.

    Entries in all three merge by value; an entry one side deleted is dropped unless the other
    changed it; each side's additions follow the base entry that precedes them on that side.
    Where both sides add after the same base entry, the two groups go in the order of their
    first keys, so the result does not depend on which side is ours, and the place is owed
    unless every side's keys are sorted, when the result is sorted too.
    """
    bmap = {key(e): value(e) for e in base}
    omap = {key(e): value(e) for e in ours}
    tmap = {key(e): value(e) for e in theirs}
    bkeys = [key(e) for e in base]
    okeys = [key(e) for e in ours]
    tkeys = [key(e) for e in theirs]
    merged = {}
    for k in bkeys:
        sub = f"{path}.{k}" if path else str(k)
        in_o, in_t = k in omap, k in tmap
        if in_o and in_t:
            merged[k] = merge_value(bmap[k], omap[k], tmap[k], sub, owed)
        elif in_o and canon(omap[k]) != canon(bmap[k]):
            owed.add(sub)
            merged[k] = omap[k]
        elif in_t and canon(tmap[k]) != canon(bmap[k]):
            owed.add(sub)
            merged[k] = tmap[k]
    for k in okeys + tkeys:
        if k in bmap or k in merged:
            continue
        sub = f"{path}.{k}" if path else str(k)
        if k in omap and k in tmap:
            merged[k] = merge_value(MISSING, omap[k], tmap[k], sub, owed)
        else:
            merged[k] = omap[k] if k in omap else tmap[k]
    for side in (okeys, tkeys):
        kept = [k for k in side if k in bmap]
        if kept != [k for k in bkeys if k in set(kept)]:
            owed.add(f"{path} (order)" if path else "order")
    if all(is_sorted(s) for s in (bkeys, okeys, tkeys)):
        order = sorted(merged)
    else:
        groups = {}
        for side_index, side in enumerate((okeys, tkeys)):
            anchor = None
            for k in side:
                if k in bmap:
                    anchor = k
                else:
                    groups.setdefault(anchor, [[], []])[side_index].append(k)
        order = []
        seen = set()

        def place(anchor):
            o, t = groups.get(anchor, [[], []])
            if o and t and o != t:
                owed.add(f"{path} (order after {anchor})" if path else f"order after {anchor}")
                first, second = (o, t) if o[0] <= t[0] else (t, o)
            else:
                first, second = o or t, []
            for k in first + second:
                if k in merged and k not in seen:
                    seen.add(k)
                    order.append(k)

        place(None)
        for k in bkeys:
            if k in merged and k not in seen:
                seen.add(k)
                order.append(k)
            place(k)
    return [make(k, merged[k]) for k in order]


def merge_json(base_text, ours_text, theirs_text, owed):
    base = json.loads(base_text) if base_text.strip() else {}
    merged = merge_value(base, json.loads(ours_text), json.loads(theirs_text), "", owed)
    return render_json(merged) + "\n"


# The leading columns that key a row of each generated TSV.
TSV_KEYS = {"types.tsv": 1, "census.tsv": 1, "casts.tsv": 2, "casts_fused.tsv": 2}


def merge_tsv(base_text, ours_text, theirs_text, key_columns, owed):
    """The same merge over a TSV's rows; the comment lines and the header are one value."""

    def split(text):
        lines = text.splitlines()
        n = 0
        while n < len(lines) and lines[n].startswith("#"):
            n += 1
        head = lines[: n + 1]
        rows = [(tuple(r.split("\t")[:key_columns]), r) for r in lines[n + 1 :]]
        return head, rows

    bh, br = split(base_text)
    oh, orows = split(ours_text)
    th, trows = split(theirs_text)
    head = merge_value(bh, oh, th, "header", owed)
    rows = merge_seq(
        br,
        orows,
        trows,
        key=lambda e: e[0],
        value=lambda e: e[1],
        make=lambda k, v: v,
        path="",
        owed=owed,
    )
    return "\n".join(head + rows) + "\n"


def merge_docs(base_path, ours_path, theirs_path, owed):
    """`git merge-file` over the docs file; a conflict inside a generated block takes one side
    and owes the coverage run that rewrites the block. Returns the text and whether a conflict
    outside every block is left with its markers."""
    # The parser below reads the two-section markers, so the style is pinned against a clone
    # whose merge.conflictStyle is diff3 or zdiff3.
    proc = subprocess.run(
        [
            "git",
            "-c",
            "merge.conflictStyle=merge",
            "merge-file",
            "-p",
            "-L",
            "ours",
            "-L",
            "base",
            "-L",
            "theirs",
            ours_path,
            base_path,
            theirs_path,
        ],
        capture_output=True,
        text=True,
    )
    if proc.returncode < 0 or proc.returncode > 127:
        raise RuntimeError(proc.stderr)
    out, real = [], False
    in_block = False
    lines = proc.stdout.splitlines(keepends=True)
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("<<<<<<< ours"):
            j = i + 1
            while not lines[j].startswith("======="):
                j += 1
            k = j + 1
            while not lines[k].startswith(">>>>>>> theirs"):
                k += 1
            o, t = lines[i + 1 : j], lines[j + 1 : k]
            markers = any("generated" in x and x.startswith("<!--") for x in o + t)
            if in_block and not markers:
                owed.add("generated block")
                out.extend(o if "".join(o) <= "".join(t) else t)
            else:
                real = True
                out.extend(lines[i : k + 1])
            i = k + 1
            continue
        if line.startswith("<!-- BEGIN generated"):
            in_block = True
        elif line.startswith("<!-- END generated"):
            in_block = False
        out.append(line)
        i += 1
    return "".join(out), real


def owed_file():
    path = subprocess.run(
        ["git", "rev-parse", "--git-path", "varka-regen-owed"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    return path


def driver(base_path, ours_path, theirs_path, repo_path):
    def read(p):
        with open(p, encoding="utf-8") as f:
            return f.read()

    name = os.path.basename(repo_path)
    owed = Owed()
    real = False
    try:
        if (
            name == "coverage.json"
            and len({read(base_path), read(ours_path), read(theirs_path)}) == 3
        ):
            # Both sides moved the coverage table: the census reads it, so it is owed even when
            # every row merged exactly.
            owed.add("the census, which reads coverage.json")
        if name.endswith(".json"):
            text = merge_json(read(base_path), read(ours_path), read(theirs_path), owed)
        elif name in TSV_KEYS:
            text = merge_tsv(
                read(base_path), read(ours_path), read(theirs_path), TSV_KEYS[name], owed
            )
        elif name.endswith(".md"):
            text, real = merge_docs(base_path, ours_path, theirs_path, owed)
        else:
            raise ValueError(f"no merge rule for {repo_path}")
    except (ValueError, KeyError, IndexError) as e:
        # A side that is not the generated form (markers, hand edits): git's text merge.
        print(f"varka merge: {repo_path}: {e}; falling back to a text merge", file=sys.stderr)
        return subprocess.run(
            [
                "git",
                "merge-file",
                "-L",
                "ours",
                "-L",
                "base",
                "-L",
                "theirs",
                ours_path,
                base_path,
                theirs_path,
            ]
        ).returncode
    with open(ours_path, "w", encoding="utf-8") as f:
        f.write(text)
    if owed.paths:
        shown = ", ".join(sorted(set(owed.paths))[:4])
        more = len(set(owed.paths)) - 4
        print(
            f"varka merge: {repo_path} merged; regeneration owed for {shown}"
            + (f" and {more} more" if more > 0 else "")
            + "; run dev/varka_regen_generated.sh",
            file=sys.stderr,
        )
        with open(owed_file(), "a", encoding="utf-8") as f:
            f.write(repo_path + "\n")
    else:
        print(f"varka merge: {repo_path} merged per key, nothing owed", file=sys.stderr)
    return 1 if real else 0


# --- the CI check --------------------------------------------------------------------------

AUDIT_REGEN = "VARKA_AUDIT_REGEN=true build/sbt 'catalyst/testOnly *VarkaWidthAuditSuite'"
BYTES_REGEN = "VARKA_BYTES_REGEN=true build/sbt 'catalyst/testOnly *VarkaEmittedBytesSuite'"


def check(root):
    """The coverage rows every per-row generated file must carry, read without a JVM."""

    def load(rel):
        with open(os.path.join(root, rel), encoding="utf-8") as f:
            return json.load(f)

    coverage = load("sql/varka/coverage.json")
    rows = [e["sql"] for e in coverage["expressions"]]
    errors = []
    audit = load("sql/varka/width_audit.json")
    host = audit.get("host", {}).get("cpu", "?")
    for width, shapes in audit["widths"].items():
        missing = [r for r in rows if r not in shapes]
        extra = [s for s in shapes if s not in rows and not s.startswith("construction: ")]
        if missing or extra:
            errors.append(
                f"sql/varka/width_audit.json at {width} bits is stale: "
                + (f"no entry for {missing[:3]}" if missing else "")
                + (" and " if missing and extra else "")
                + (f"entries for removed rows {extra[:3]}" if extra else "")
                + f". Regenerate it on its host ({host}) with\n  {AUDIT_REGEN}"
            )
    emitted = load("sql/varka/emitted_bytes.json")
    expected = [r for r in rows if r not in set(emitted.get("coverage_rows_skipped", []))]
    for lanes, sections in emitted["lanes"].items():
        have = sections.get("coverage", {})
        if list(have) != expected:
            missing = [r for r in expected if r not in have]
            extra = [r for r in have if r not in expected]
            what = (
                f"no digests for {missing[:3]}"
                if missing
                else f"digests for removed rows {extra[:3]}"
                if extra
                else "its rows in another order than coverage.json's"
            )
            errors.append(
                f"sql/varka/emitted_bytes.json at {lanes} lanes is stale: {what}. "
                f"Regenerate it with\n  {BYTES_REGEN}"
            )
    for e in errors:
        print("generated: " + e, file=sys.stderr)
    if errors:
        print(
            "generated: after a merge, dev/varka_regen_generated.sh --all runs every "
            "regeneration in order",
            file=sys.stderr,
        )
    return 1 if errors else 0


# --- the self-test -------------------------------------------------------------------------


def self_test():
    import doctest

    failed, _ = doctest.testmod()
    cases = 0

    def expect(cond, what):
        nonlocal cases, failed
        cases += 1
        if not cond:
            failed += 1
            print("self-test FAILED: " + what, file=sys.stderr)

    # Per-key merges, in both orders.
    # The coverage table's rows are in the suite's order, not sorted.
    base = {
        "expressions": [{"sql": "z", "v": 1}, {"sql": "a", "v": 1}],
        "arms": {"x": "0"},
        "classes": ["A", "C"],
    }
    left = {
        "expressions": [{"sql": "z", "v": 1}, {"sql": "b", "v": 2}, {"sql": "a", "v": 1}],
        "arms": {"x": "1"},
        "classes": ["A", "B", "C"],
    }
    right = {
        "expressions": [{"sql": "z", "v": 1}, {"sql": "c", "v": 3}, {"sql": "a", "v": 1}],
        "arms": {"x": "2"},
        "classes": ["A", "C", "D"],
    }
    o1, o2 = Owed(), Owed()
    m1 = merge_value(base, left, right, "", o1)
    m2 = merge_value(base, right, left, "", o2)
    expect(canon(m1) == canon(m2), "the merge depends on which side is ours")
    expect([e["sql"] for e in m1["expressions"]] == ["z", "b", "c", "a"], "row order")
    expect(m1["classes"] == ["A", "B", "C", "D"], "the sorted set merge")
    expect(sorted(o1.paths) == ["arms.x", "expressions (order after z)"], f"owed {o1.paths}")
    # A sorted map stays sorted and owes nothing.
    o = Owed()
    m = merge_value(
        {"a": [], "d": []}, {"a": [], "b": [], "d": []}, {"a": [], "c": [], "d": []}, "", o
    )
    expect(list(m) == ["a", "b", "c", "d"] and not o.paths, f"sorted map {list(m)} {o.paths}")
    # One side's change against an unchanged other side is taken, not owed.
    o = Owed()
    m = merge_value({"k": 1, "j": 1}, {"k": 2, "j": 1}, {"k": 1, "j": 3}, "", o)
    expect(m == {"k": 2, "j": 3} and not o.paths, "independent edits")
    # A deletion against an unchanged entry.
    o = Owed()
    m = merge_value({"k": 1, "j": 1}, {"j": 1}, {"k": 1, "j": 1}, "", o)
    expect(m == {"j": 1} and not o.paths, "a deletion")
    # TSV rows keyed by the leading columns.
    o = Owed()
    tb = "# c\nid\tx\nA\t1\nZ\t1\n"
    t = merge_tsv(tb, "# c\nid\tx\nA\t1\nB\t1\nZ\t1\n", "# c\nid\tx\nA\t2\nZ\t1\n", 1, o)
    expect(t == "# c\nid\tx\nA\t2\nB\t1\nZ\t1\n" and not o.paths, f"tsv {t!r} {o.paths}")
    # The committed files round-trip, so a merged file is written in the suites' form.
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    for rel in ("coverage.json", "emitted_bytes.json", "width_audit.json"):
        p = os.path.join(root, "sql", "varka", rel)
        if os.path.exists(p):
            with open(p, encoding="utf-8") as f:
                text = f.read()
            expect(render_json(json.loads(text)) + "\n" == text, f"{rel} does not round-trip")
    # A coverage row the width audit and the bytes file lack fails the check, with commands.
    if all(
        os.path.exists(os.path.join(root, "sql", "varka", r))
        for r in ("coverage.json", "emitted_bytes.json", "width_audit.json")
    ):
        with tempfile.TemporaryDirectory() as tmp:
            os.makedirs(os.path.join(tmp, "sql", "varka"))
            for rel in ("emitted_bytes.json", "width_audit.json"):
                with open(os.path.join(root, "sql", "varka", rel), encoding="utf-8") as f:
                    text = f.read()
                with open(os.path.join(tmp, "sql", "varka", rel), "w", encoding="utf-8") as f:
                    f.write(text)
            with open(os.path.join(root, "sql", "varka", "coverage.json"), encoding="utf-8") as f:
                doc = json.load(f)
            doc["expressions"].append({"sql": "a row only coverage.json has"})
            with open(
                os.path.join(tmp, "sql", "varka", "coverage.json"), "w", encoding="utf-8"
            ) as f:
                f.write(render_json(doc) + "\n")
            expect(check(tmp) == 1, "a stale width audit passed the check")
    end_to_end(expect)
    print(f"self-test: {cases} case(s), {failed} failed")
    return 1 if failed else 0


def end_to_end(expect):
    """Two branches each add a row at the same place in a coverage file, a TSV and the docs
    file; merged through git with this driver in both orders, there are no markers and the two
    trees are identical. A prose conflict in the docs file keeps its markers."""
    script = os.path.abspath(__file__)
    with tempfile.TemporaryDirectory() as tmp:

        def git(*args, check=True):
            return subprocess.run(
                ["git", "-C", tmp, *args], capture_output=True, text=True, check=check
            )

        def write(rel, text):
            p = os.path.join(tmp, rel)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "w", encoding="utf-8") as f:
                f.write(text)

        def cov(rows):
            return (
                render_json({"expressions": [{"sql": r} for r in rows], "digest": "-".join(rows)})
                + "\n"
            )

        def docs(rows, prose):
            return (
                "# Doc\n"
                + prose
                + "\n<!-- BEGIN generated coverage table -->\n"
                + "".join(f"| {r} |\n" for r in rows)
                + "<!-- END generated coverage table -->\n"
            )

        git("init", "-q", "-b", "master")
        git("config", "user.email", "t@example.com")
        git("config", "user.name", "t")
        git("config", "commit.gpgsign", "false")
        git("config", "core.hooksPath", "/dev/null")
        # A clone whose conflict style is diff3 must merge the same way.
        git("config", "merge.conflictStyle", "diff3")
        git(
            "config",
            "merge.varka-generated.driver",
            f"{sys.executable} {script} driver %O %A %B %P",
        )
        write(
            ".gitattributes",
            "c/coverage.json merge=varka-generated\n"
            "c/types.tsv merge=varka-generated\nd/doc.md merge=varka-generated\n",
        )
        write("c/coverage.json", cov(["a", "z"]))
        write("c/types.tsv", "id\tx\nA\t1\nZ\t1\n")
        write("d/doc.md", docs(["a", "z"], "prose"))
        git("add", "-A")
        git("commit", "-qm", "base")
        for name, row in (("left", "b"), ("right", "c")):
            git("checkout", "-qb", name, "master")
            write("c/coverage.json", cov(["a", row, "z"]))
            write("c/types.tsv", f"id\tx\nA\t1\n{row.upper()}\t1\nZ\t1\n")
            write("d/doc.md", docs(["a", row, "z"], "prose"))
            git("commit", "-qam", name)
        trees = []
        for first, second in (("left", "right"), ("right", "left")):
            git("checkout", "-q", "-B", "m-" + first, "master")
            git("merge", "-q", "--no-ff", "--no-edit", first)
            git("checkout", "-q", "-B", "s-" + second, second)
            merged = git("merge", "--no-edit", "m-" + first, check=False)
            expect(
                merged.returncode == 0,
                f"{second} merging master+{first}: {merged.stdout}{merged.stderr}",
            )
            expect(
                not git("diff", "--name-only", "--diff-filter=U").stdout, "an unmerged path is left"
            )
            trees.append(git("rev-parse", "HEAD^{tree}").stdout.strip())
        expect(len(set(trees)) == 1, "the two orders give different trees")
        with open(os.path.join(tmp, "c", "types.tsv"), encoding="utf-8") as f:
            expect(f.read() == "id\tx\nA\t1\nB\t1\nC\t1\nZ\t1\n", "the merged TSV")
        # A prose conflict outside the generated blocks is a real conflict.
        git("checkout", "-qb", "p1", "master")
        write("d/doc.md", docs(["a", "z"], "prose one"))
        git("commit", "-qam", "p1")
        git("checkout", "-qb", "p2", "master")
        write("d/doc.md", docs(["a", "z"], "prose two"))
        git("commit", "-qam", "p2")
        real = git("merge", "--no-edit", "p1", check=False)
        expect(real.returncode != 0, "a prose conflict was resolved silently")
        with open(os.path.join(tmp, "d", "doc.md"), encoding="utf-8") as f:
            expect("<<<<<<<" in f.read(), "the prose conflict lost its markers")
        git("merge", "--abort", check=False)


def main(argv):
    if len(argv) == 6 and argv[1] == "driver":
        return driver(*argv[2:])
    if argv[1:] == ["--check"]:
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        return check(root)
    if argv[1:] == ["--self-test"]:
        return self_test()
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
