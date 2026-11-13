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
"""Replace some sections of a Spark benchmark results file and keep the rest as committed.

    dev/varka_bench_sections.py list FILE
    dev/varka_bench_sections.py splice OLD NEW --sections "title one,title two" [--out FILE]
    dev/varka_bench_sections.py restore --sections "title one" [FILE...]
        [--cpu-checked MODEL] [--java-options=OPTS]
    dev/varka_bench_sections.py --selftest

A results file is a run of sections, each opened by a rule of `=` characters, a title line (the
name the benchmark gave `runBenchmark`) and a second rule. A runner regeneration rewrites every
section of a class's file on whichever CPU the pool assigns, so a pull request that adds one
section replaces all the others' numbers with that machine's (VARKA-256, `m9/SCOPE.md` item 72;
it happened on 30 September 2026 and was spliced back by hand). This is the splice.

`splice` writes OLD with the named sections replaced by NEW's, in OLD's order; a named section
OLD lacks is appended; every other section is OLD's bytes, unchanged. `restore` does it for the
files a run just regenerated: for each results file that differs from HEAD (or the files named) it
takes HEAD's version as OLD and the working-tree file as NEW, and writes the result back, which is
what the benchmark workflow runs after the benchmark when a `sections` input is set. A file with
no committed version is left as it is, unless it is a plain file of a class whose committed file
is named for its runner (below). A named section the new file does not have is an error that
lists the titles it has, since a misspelt title would otherwise silently keep the old numbers.

A class whose committed file is named for the runner that measured it
(`<Class>-jdk25-runner-epyc7763-results.txt`, and `-128bit` for a run at -XX:MaxVectorSize=16)
gets its run's numbers under the plain name, `<Class>-results.txt` on JDK 21 and
`<Class>-jdk25-results.txt` on JDK 25. `restore` splices that file into the runner-named one the
run stands for, which must be committed, and removes it (or puts it back to HEAD where it is
tracked), but only when the run's CPU check passed against that runner's model (--cpu-checked,
which the benchmark workflow passes with the run's --java-options); any other run of such a class
is refused (VARKA-599).

Titles are matched exactly, ignoring surrounding space. The text before the first rule (the
provenance header of a file that has one) belongs to no section and is always OLD's.
"""

import argparse
import os
import re
import subprocess
import sys
import tempfile

RULE = re.compile(r"^={20,}\s*$")


def split_sections(text):
    """[(title, text)] in file order. The first entry's title is "" for the preamble, which is
    present only if there is text before the first rule."""
    lines = text.splitlines(keepends=True)
    starts = []
    i = 0
    while i + 2 < len(lines):
        if RULE.match(lines[i]) and not RULE.match(lines[i + 1]) and RULE.match(lines[i + 2]):
            starts.append((i, lines[i + 1].strip()))
            i += 3
        else:
            i += 1
    out = []
    if not starts:
        return [("", text)] if text else []
    if starts[0][0] > 0:
        out.append(("", "".join(lines[: starts[0][0]])))
    for k, (at, title) in enumerate(starts):
        end = starts[k + 1][0] if k + 1 < len(starts) else len(lines)
        out.append((title, "".join(lines[at:end])))
    return out


def parse_titles(spec):
    """The titles a `--sections` value names: separated by `|` (or newlines), since titles hold
    commas (126 of the 480 committed ones) and none holds a `|`. Blank entries and repeats go."""
    titles = []
    for part in spec.replace("\n", "|").split("|"):
        part = part.strip()
        if part and part not in titles:
            titles.append(part)
    return titles


def splice(old, new, titles):
    """OLD with the sections `titles` taken from NEW; see the module doc.

    A title may name several sections of a file (a loop that calls `runBenchmark` with one name):
    NEW's sections of that title, in order, take the place of OLD's first one, and OLD's others
    go. A title OLD lacks is appended.
    """
    new_by = {}
    for title, body in split_sections(new):
        if title:
            new_by.setdefault(title, []).append(body)
    missing = [t for t in titles if t not in new_by]
    if missing:
        raise SystemExit(f"no such section in the new file: {missing}; it has {list(new_by)}")
    out = []
    placed = set()
    for title, body in split_sections(old):
        if title in titles:
            if title not in placed:
                out.extend(new_by[title])
                placed.add(title)
        else:
            out.append(body)
    for title in titles:
        if title not in placed:
            out.extend(new_by[title])
            placed.add(title)
    return "".join(out)


def committed(path):
    r = subprocess.run(["git", "show", f"HEAD:{path}"], capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else None


def changed_results():
    """The results files a run wrote: those that differ from HEAD and the new, untracked ones (a
    class whose committed file is named for its runner gets its run's file under the plain name)."""
    out = []
    for cmd in (
        ["git", "diff", "--name-only", "--", "*benchmarks/*-results.txt"],
        ["git", "ls-files", "--others", "--exclude-standard", "--", "*benchmarks/*-results.txt"],
    ):
        r = subprocess.run(cmd, check=True, capture_output=True, text=True)
        out += [p for p in r.stdout.split() if p and p not in out]
    return out


# The CPU models whose runs have committed files named for them, by every slug their names use,
# the preferred spelling first.
RUNNER_SLUGS = {"AMD EPYC 7763": ("epyc7763", "7763")}
PLAIN = re.compile(r"^(?P<stem>.*)-results\.txt$")
VECTOR_SIZE = re.compile(r"-XX:MaxVectorSize=(\d+)")


def runner_target(path, is_committed, cpu_checked, java_options):
    """The committed runner-named file a run's plain results file `path` stands for, as
    (target, None); (None, None) when the class has no runner-named file, so the plain file is
    restored as before; (None, why) when the run must not write into one. `is_committed` answers
    whether a path has a version at HEAD; `cpu_checked` is the expected-cpu the run's CPU check
    passed against ("" when no check passed); `java_options` is the run's extra JVM options. The
    CPU names a runner when it contains the model's key string, as the probe's own check does."""
    m = PLAIN.match(path)
    if not m:
        return None, None
    stem = m.group("stem")
    held = [
        slug
        for slugs in RUNNER_SLUGS.values()
        for slug in slugs
        if is_committed(f"{stem}-runner-{slug}-results.txt")
        or is_committed(f"{stem}-runner-{slug}-128bit-results.txt")
    ]
    if not held:
        return None, None
    checked = (cpu_checked or "").lower()
    slug = next(
        (
            slug
            for model, slugs in RUNNER_SLUGS.items()
            if model.lower() in checked
            for slug in slugs
            if slug in held
        ),
        None,
    )
    if slug is None:
        return None, (
            f"{path}: the class's committed numbers are named for {held}, and this run's CPU "
            f"was checked against {cpu_checked or 'nothing'}; dispatch with expected-cpu set to "
            "that CPU"
        )
    if not VECTOR_SIZE.search(java_options or "") and "UseAVX" in (java_options or ""):
        return None, (
            f"{path}: the run sets -XX:UseAVX and no -XX:MaxVectorSize, so its vector width is "
            "not known here (-XX:UseAVX=0 forces 128 bits); pass -XX:MaxVectorSize=16 or =32"
        )
    sizes = VECTOR_SIZE.findall(java_options or "")
    width = sizes[-1] if sizes else "32"
    if width == "32":
        target = f"{stem}-runner-{slug}-results.txt"
    elif width == "16":
        target = f"{stem}-runner-{slug}-128bit-results.txt"
    else:
        return None, f"{path}: no runner-named file is kept at -XX:MaxVectorSize={width}"
    if not is_committed(target):
        return None, f"{path}: {target} has no committed version to splice into"
    return target, None


def restore(titles, files, cpu_checked="", java_options=""):
    """Splices each regenerated file against HEAD's. A file that holds none of the titles (another
    class's file under a wildcard) goes back to HEAD whole; a file that holds some and not all is
    an error, as is a run in which no file will be spliced. Every file is checked before any is
    removed or written, so a mistyped --sections leaves the run's output as it is. A plain file of
    a class whose committed file is named for its runner is spliced into that file instead
    (`runner_target`) and then removed, so only the runner-named file leaves the run; an unchecked
    run of such a class whose file holds none of the titles is removed too, and a checked one
    keeps its file."""
    plans = []
    writes = 0
    for path in files or changed_results():
        if not os.path.exists(path):
            print(f"{path}: no committed version, left as it is")
            continue
        target, refusal = runner_target(
            path, lambda p: committed(p) is not None, cpu_checked, java_options
        )
        with open(path, encoding="utf-8") as f:
            new = f.read()
        have = {t for t, _ in split_sections(new) if t}
        present = [t for t in titles if t in have]
        if refusal and present:
            raise SystemExit(refusal)
        if present and len(present) < len(titles):
            lacking = [t for t in titles if t not in have]
            raise SystemExit(f"{path}: lacks the sections {lacking}; it has {sorted(have)}")
        old = committed(target or path)
        if len(present) == len(titles) and old is not None:
            writes += 1
        plans.append((path, target, refusal, new, present, old))
    if writes == 0:
        raise SystemExit(f"no regenerated file holds the sections {titles}")
    held = 0
    for path, target, refusal, new, present, old in plans:
        if target and not present:
            # A class under a wildcard that holds none of the asked-for titles: the run is not
            # splicing it, so its file stays as the run wrote it rather than being removed.
            print(f"{path}: holds none of the sections; left as it is")
            continue
        if refusal or target:
            # A runner-named class: the plain file never leaves the run under its plain name. A
            # refusal only binds a file that holds sections the run was asked for (raised above).
            plain = committed(path)
            if plain is None:
                os.remove(path)
            else:
                with open(path, "w", encoding="utf-8") as f:
                    f.write(plain)
            if not target:
                print(f"{path}: holds none of the sections; removed")
                continue
        dest = target or path
        if old is None:
            print(f"{path}: no committed version, left as it is")
            continue
        if not present:
            print(f"{path}: holds none of the sections; restored to HEAD")
            result = old
        else:
            result = splice(old, new, titles)
            held += 1
            print(f"{dest}: sections {titles} taken from {path}, the rest as committed")
        with open(dest, "w", encoding="utf-8") as f:
            f.write(result)
    if held == 0:
        raise SystemExit(f"no regenerated file holds the sections {titles}")


def selftest():
    def sec(title, body):
        rule = "=" * 96 + "\n"
        return f"{rule}{title}\n{rule}\n{body}\n"

    a1, b1, c1 = sec("a", "old a\n"), sec("b", "old b\n"), sec("c", "old c\n")
    a2, b2, d2 = sec("a", "new a\n"), sec("b", "new b\n"), sec("d", "new d\n")
    old = "preamble\n" + a1 + b1 + c1
    new = a2 + b2 + d2
    assert [t for t, _ in split_sections(old)] == ["", "a", "b", "c"]
    # Only b replaced: a and c are old's bytes, in old's order, the preamble kept.
    assert splice(old, new, ["b"]) == "preamble\n" + a1 + b2 + c1
    # Two named, one of them new to old: appended last.
    assert splice(old, new, ["a", "d"]) == "preamble\n" + a2 + b1 + c1 + d2
    # Nothing named: old, byte for byte.
    assert splice(old, new, []) == old
    # A name the new file lacks is an error that names what it has.
    try:
        splice(old, new, ["c"])
        raise AssertionError("expected a refusal")
    except SystemExit as e:
        assert "['a', 'b', 'd']" in str(e), e
    # A file without rules is one preamble and nothing can be named in it.
    assert split_sections("plain\n") == [("", "plain\n")]
    # The rules must be a rule, a title, a rule: a table's dashes do not open a section.
    assert [t for t, _ in split_sections(sec("x", "-----\nrow\n"))] == ["x"]
    # Titles with commas are named by `|`; repeats and blanks go.
    assert parse_titles("x, y|z|| z |") == ["x, y", "z"]
    # A title that names several sections: the new ones, in order, take the first old one's place.
    old2 = a1 + sec("loop", "old 1\n") + b1 + sec("loop", "old 2\n")
    new2 = sec("loop", "new 1\n") + sec("loop", "new 2\n") + sec("loop", "new 3\n")
    assert splice(old2, new2, ["loop"]) == (
        a1 + sec("loop", "new 1\n") + sec("loop", "new 2\n") + sec("loop", "new 3\n") + b1
    )
    # Naming a section twice appends it once.
    assert splice(old, new, ["d", "d"]) == old + d2
    restore_cases(sec)
    print("varka_bench_sections: selftest passed")


def restore_cases(sec):
    """`restore` in a scratch repository: a class whose committed file is named for its runner and
    its legacy spelling, one with no runner-named file, and a narrow run with no companion."""
    for key in [k for k in os.environ if k.startswith(("GIT_", "GITHEAD_"))]:
        del os.environ[key]
    env = {**os.environ, "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1"}
    d = "sql/core/benchmarks/"
    wide = d + "XBenchmark-jdk25-runner-epyc7763-results.txt"
    narrow = d + "XBenchmark-jdk25-runner-epyc7763-128bit-results.txt"
    plain = d + "XBenchmark-jdk25-results.txt"
    other = d + "YBenchmark-jdk25-results.txt"
    legacy = d + "ZBenchmark-jdk25-runner-7763-results.txt"
    legacy_plain = d + "ZBenchmark-jdk25-results.txt"
    fresh = d + "WBenchmark-jdk25-runner-epyc7763-results.txt"
    fresh_plain = d + "WBenchmark-jdk25-results.txt"
    bare = d + "VBenchmark-runner-epyc7763-results.txt"
    bare_plain = d + "VBenchmark-results.txt"
    committed_text = "preamble\n" + sec("a", "old a\n") + sec("b", "old b\n")
    run_text = sec("a", "new a\n") + sec("b", "new b\n")
    spliced = "preamble\n" + sec("a", "old a\n") + sec("b", "new b\n")
    epyc = "AMD EPYC 7763"
    here = os.getcwd()

    def git(*args):
        subprocess.run(["git", *args], check=True, capture_output=True, env=env)

    def write(path, text):
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)

    def read(path):
        with open(path, encoding="utf-8") as f:
            return f.read()

    with tempfile.TemporaryDirectory() as tmp:
        os.chdir(tmp)
        try:
            os.makedirs(d)
            for path in (wide, narrow, other, legacy, fresh, bare):
                write(path, committed_text)
            git("init", "-q", ".")
            git("add", "-A")
            git("-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-qm", "base")
            # A checked run splices the wide or the narrow file; the probe's CPU check is a
            # substring one, so the long spelling of the model names the same runner.
            for cpu, options, written in (
                (epyc, "", wide),
                ("AMD EPYC 7763 64-Core Processor", "", wide),
                (epyc, "--add-modules jdk.incubator.vector -XX:MaxVectorSize=16", narrow),
                (epyc, "-XX:MaxVectorSize=32", wide),
            ):
                write(plain, run_text)
                restore(["b"], [], cpu, options)
                assert read(written) == spliced, (options, read(written))
                assert not os.path.exists(plain), options
                for path in (wide, narrow):
                    if path != written:
                        assert read(path) == committed_text, (options, path)
                git("checkout", "-q", "--", ".")
            # An unchecked CPU, another model, a width with no file and UseAVX without a width.
            for cpu, options in (
                ("", ""),
                ("AMD EPYC 9V74", ""),
                (epyc, "-XX:MaxVectorSize=64"),
                (epyc, "-XX:UseAVX=0"),
            ):
                write(plain, run_text)
                try:
                    restore(["b"], [], cpu, options)
                    raise AssertionError(f"expected a refusal at {cpu!r} {options!r}")
                except SystemExit as e:
                    assert "XBenchmark-jdk25-results.txt" in str(e), e
                    if "UseAVX" in options:
                        assert "UseAVX" in str(e), e
                assert read(wide) == committed_text and read(narrow) == committed_text
                os.remove(plain)
            # A class with no runner-named file: its own committed file, spliced as before; an
            # unchecked run's plain file of a runner-named class without the sections goes.
            write(other, run_text)
            write(plain, sec("z", "z\n"))
            restore(["b"], [], "", "")
            assert read(other) == spliced and not os.path.exists(plain)
            git("checkout", "-q", "--", ".")
            # A checked run under a wildcard that holds none of the titles keeps its own file.
            write(other, run_text)
            write(plain, sec("z", "z\n"))
            restore(["b"], [], epyc, "")
            assert read(other) == spliced and read(plain) == sec("z", "z\n")
            git("checkout", "-q", "--", ".")
            os.remove(plain)
            # The legacy spelling of the runner's name holds the same CPU's files.
            write(legacy_plain, run_text)
            restore(["b"], [], epyc, "")
            assert read(legacy) == spliced and not os.path.exists(legacy_plain)
            git("checkout", "-q", "--", ".")
            # The plain name without a -jdkNN segment (JDK 21) is accepted too.
            write(bare_plain, run_text)
            restore(["b"], [], epyc, "")
            assert read(bare) == spliced and not os.path.exists(bare_plain)
            git("checkout", "-q", "--", ".")
            # A narrow run whose -128bit companion is not committed is refused (the sheet's B1),
            # and the refusal comes before the run's file is removed.
            write(fresh_plain, run_text)
            try:
                restore(["b"], [], epyc, "-XX:MaxVectorSize=16")
                raise AssertionError("expected a refusal without a committed companion")
            except SystemExit as e:
                assert "WBenchmark-jdk25-runner-epyc7763-128bit-results.txt" in str(e), e
            assert os.path.exists(fresh_plain) and read(fresh) == committed_text
            os.remove(fresh_plain)
            # A path that does not exist is skipped with a message, not opened.
            try:
                restore(["b"], [d + "Missing-jdk25-results.txt"], epyc, "")
                raise AssertionError("expected a run with no splice")
            except SystemExit as e:
                assert "no regenerated file holds the sections" in str(e), e
        finally:
            os.chdir(here)


def main():
    if "--selftest" in sys.argv:
        selftest()
        return
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("list")
    p.add_argument("file")
    p = sub.add_parser("splice")
    p.add_argument("old")
    p.add_argument("new")
    p.add_argument("--sections", required=True)
    p.add_argument("--out")
    p = sub.add_parser("restore")
    p.add_argument("--sections", required=True)
    p.add_argument("--cpu-checked", default="")
    p.add_argument("--java-options", default="")
    p.add_argument("files", nargs="*")
    args = ap.parse_args()
    titles = parse_titles(getattr(args, "sections", ""))
    if args.cmd == "list":
        with open(args.file, encoding="utf-8") as f:
            for title, _ in split_sections(f.read()):
                if title:
                    print(title)
    elif args.cmd == "splice":
        with open(args.old, encoding="utf-8") as f:
            old = f.read()
        with open(args.new, encoding="utf-8") as f:
            new = f.read()
        result = splice(old, new, titles)
        if args.out:
            with open(args.out, "w", encoding="utf-8") as f:
                f.write(result)
        else:
            sys.stdout.write(result)
    else:
        restore(titles, args.files, args.cpu_checked, args.java_options)


if __name__ == "__main__":
    main()
