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
"""Run before a push what the branch's changed files imply (VARKA-563).

    dev/varka_push_check.py              # the branch against its merge base with origin/master
    dev/varka_push_check.py --base REV   # against REV instead
    dev/varka_push_check.py --dry-run    # print the findings and steps, start no JVM
    dev/varka_push_check.py --no-jvm     # the rules that need no JVM
    dev/varka_push_check.py --lint       # add Error Prone, scalastyle and lint-java
    dev/varka_push_check.py --full       # add the width audit, which is one CPU's census
    dev/varka_push_check.py --branch B   # read B's reflog for its rebases (default HEAD's branch)
    dev/varka_push_check.py --self-test  # each rule over fixture diffs and trees

The rules, each a lesson of a lost CI round (m8/VARKA-563.md 1):

  R1  a record of the IR added, removed or with its components changed runs every suite that
      enumerates node types, found by a grep of the test trees at run time, and fails at once
      on a registry whose text names the record nowhere it must (`REGISTRIES`) (#1338);
  R2  a string literal a removed line of a Varka main file held, still in a test, the coverage
      file or the docs, fails at once; the suites holding the old or the new text run (#1317);
  R3  the generated files: nothing owed by a merge, nothing a committed rebase text-merged
      left unregenerated (the branch's reflog), no coverage row missing, and when a Varka main
      or generated file changed, their suites run without REGEN variables (#1323);
  R4  the house checks a push owes: the pre-commit rules over the range, the plan lint, the
      quote check, the census check and the size cap.

The suites run in one dev/varka_matrix.sh call, under flock on VARKA_HEAVY_LOCK (default
~/proj/.varka-locks/heavy.lock; `none` runs without a lock) with SPARK_LOCAL_IP=127.0.0.1, and a
failed suite is reported under the rules that chose it. Exit status 1 when a rule failed.
VARKA_PUSH_CHECK_SKIP=R2 (or "R2,R3") drops a rule's findings, suites and steps for one run.
"""

import argparse
import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sparktestsupport.modules import is_varka_scoped

TEST_TREES = ("sql/catalyst/src/test", "sql/core/src/test")
MAIN_TREES = ("sql/catalyst/src/main", "sql/core/src/main", "sql/varka/engine/src/main")
MODULES = {"sql/catalyst/": "catalyst", "sql/core/": "sql"}
IR_FILE = "sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/"
IR_FILE += "VarkaVectorIR.java"
# A suite that enumerates node types says so in one of these words; the three named suites walk
# every node type too, whatever words they use.
REGISTRY_PATTERN = (
    "getPermittedSubclasses|concreteNodeTypes|a node type was added|never drew|NOT_PARSED_YET"
)
REGISTRY_NAMED = ("VarkaEmitCostSuite", "VarkaIrFuzzSuite", "VarkaVectorIRSharingSuite")
REASON_PLACES = TEST_TREES + ("sql/varka/coverage.json", "docs")
GENERATED_SUITES = (
    "VarkaCoverageSuite",
    "VarkaEmittedBytesSuite",
    "VarkaTypesSuite",
    "VarkaCastMatrixSuite",
)
AUDIT_SUITE = "VarkaWidthAuditSuite"
# The generated files are written by catalyst's compilers and the engine's kernels.
GENERATING_TREES = ("sql/catalyst/src/main", "sql/varka/engine/src/main")
REGEN = "dev/varka_regen_generated.sh --all"
WINDOW = 5  # words of a removed literal looked for together
MIN_CHARS = 20  # a shorter window is too common to say anything
OUT = "target/varka-push-check"
RULES = ("R1", "R2", "R3", "R4")
SUITE_CLASS = re.compile(r"^(?:public )?(?:final )?class (\w+Suite)\b", re.M)
RECORD = re.compile(r"\brecord\s+(\w+)\s*(\([^)]*\))\s*(implements\b[^{]*)?\{")
IMPLEMENTS_IR = re.compile(r"\bimplements\b[^{;]*\b(VarkaVectorIR|Cond)\b")
LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
JOIN = re.compile(r'"\s*\+\s*"')
COMMENT = re.compile(r"/\*.*?\*/|//[^\n]*", re.S)
# A test file's literals (a text block, a string, a character) and comments, in one pass.
CODE = re.compile(
    r'"""(?s:.*?)"""|"(?:[^"\\\n]|\\.)*"|\'(?:[^\'\\\n]|\\.)\''
    r"|/\*(?s:.*?)\*/|//[^\n]*"
)


def collapse(text):
    """A file's text with adjacent literals joined (`"a " + "b"` reads `"a b"`), across lines,
    and its whitespace runs made one space, so a sentence is found whatever line breaks the
    source and the test gave it."""
    return re.sub(r"\s+", " ", JOIN.sub("", text))


def module_of(path):
    return next((m for p, m in MODULES.items() if path.startswith(p)), None)


def is_varka_main(path):
    return "/src/main/" in path and is_varka_scoped(path)


# --- the repository the rules read ---------------------------------------------------------


class Repo:
    """The branch's diff and trees, read through git: the base at BASE, and as HEAD the working
    tree, which is what the suites run over, read from disk because it is fast."""

    def __init__(self, root, base):
        self.root, self.base, self.env = root, base, None

    def git(self, *args, check=False):
        return subprocess.run(
            ["git", *args], cwd=self.root, capture_output=True, text=True, check=check, env=self.env
        )

    def ancestor(self, a, b):
        return self.git("merge-base", "--is-ancestor", a, b).returncode == 0

    def diff(self):
        return self.git("diff", "-U0", "--no-renames", "--no-color", self.base, check=True).stdout

    def text(self, rev, path):
        if rev == "HEAD":
            try:
                with open(os.path.join(self.root, path), encoding="utf-8", errors="replace") as f:
                    return f.read()
            except OSError:
                return ""
        out = self.git("show", f"{rev}:{path}")
        return out.stdout if out.returncode == 0 else ""

    def files(self, pattern, places, rev="HEAD", fixed=True, words=()):
        """The paths under places, at rev, holding pattern (or, with words, all of them)."""
        args = ["grep", "-l", "-I", "-F" if fixed else "-E"]
        if words:
            args += ["--all-match", *[a for w in words for a in ("-e", w)]]
        else:
            args += ["-e", pattern]
        at = [] if rev == "HEAD" else [rev]
        out = self.git(*args, *at, "--", *places)
        lines = out.stdout.splitlines()
        return [line.split(":", 1)[1] if at else line for line in lines]

    def tracked(self, places):
        return self.git("ls-files", "--", *places).stdout.split()


def parse_diff(text):
    """{path: (removed lines, added lines)} of a `git diff -U0`."""
    files, path = {}, None
    for line in text.splitlines():
        if line.startswith("diff --git "):
            path = line.split(" b/", 1)[1]
            files[path] = ([], [])
        elif path and line.startswith(("--- ", "+++ ")):
            continue
        elif path and line[:1] in "-+" and line[:1]:
            files[path][0 if line[0] == "-" else 1].append(line[1:])
    return files


# --- R1: the IR registry -------------------------------------------------------------------


def records(text):
    """{record name: its components and interfaces, spacing normalized} of a Java file."""
    plain = COMMENT.sub(" ", text)
    return {m[1]: " ".join((m[2] + (m[3] or "")).split()) for m in RECORD.finditer(plain)}


def ir_records_changed(repo, changed):
    """The IR records the branch adds, removes or changes, by name."""
    moved = []
    for path in changed:
        if not (path.endswith(".java") and is_varka_main(path)):
            continue
        base, head = repo.text(repo.base, path), repo.text("HEAD", path)
        if path != IR_FILE and not (IMPLEMENTS_IR.search(base) or IMPLEMENTS_IR.search(head)):
            continue
        old, new = records(base), records(head)
        moved += sorted(n for n in old.keys() | new.keys() if old.get(n) != new.get(n))
    return moved


def suites_in(repo, paths, rev="HEAD"):
    """{simple name: (module, fully qualified name)} of the test suites the files declare."""
    found = {}
    for path in paths:
        module = module_of(path)
        if module is None:
            continue
        text = repo.text(rev, path)
        pkg = re.search(r"^package ([\w.]+)", text, re.M)
        for name in SUITE_CLASS.findall(text):
            found[name] = (module, f"{pkg[1]}.{name}" if pkg else name)
    return found


def registry_suites(repo):
    paths = repo.files(REGISTRY_PATTERN, TEST_TREES, fixed=False)
    named = [
        p for p in repo.tracked(TEST_TREES) if os.path.basename(p).split(".")[0] in REGISTRY_NAMED
    ]
    return suites_in(repo, sorted(set(paths) | set(named)))


# What a registry the grep finds must say of a record, read as text (comments dropped) so that
# no JVM is needed: a pattern over the suite and the files it draws from, {n} the record's name.
# None marks a registry that names no record, enumerating them reflectively; a registry missing
# here is reported as unchecked, which the JVM run covers. One line a registry.
BUILT = r"new (?:VarkaVectorIR\.)?{n}\(|\b{n}\.of\("
TEST_JAVA = "sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/"
REGISTRIES = {
    "VarkaWithChildrenSuite": ("undrawn entry or draw", r'"{n}"|' + BUILT, ("IrGrammar",)),
    "VarkaIrFuzzSuite": ("unbuilt entry or draw", r'"{n}"|' + BUILT, ("IrGrammar",)),
    "VarkaLaneTypeSuite": ('instance recipe (case "{n}")', r'case "{n}"', ()),
    "VarkaEmitterBudgetSuite": ("register row", BUILT, ()),
    "VarkaReproductionSuite": ("round trip or NOT_PARSED_YET entry", BUILT + r"|\b{n}\.class", ()),
    "VarkaEmitCostSuite": ("feature or awaiting-refit entry", r'"{n}["/]', ("EmitCostFit",)),
    "VarkaVectorIRSharingSuite": None,
}


def registry_findings(repo, names):
    """(findings, notes): the registries the grep finds that do not name a record of names, and
    those this check cannot read."""
    found, notes = [], []
    for path in repo.files(REGISTRY_PATTERN, TEST_TREES, fixed=False):
        suite = os.path.basename(path).split(".")[0]
        if suite not in REGISTRIES:
            notes.append(f"{path} enumerates node types unread here; unchecked, the JVM run is")
            continue
        if REGISTRIES[suite] is None:
            continue
        what, pattern, extra = REGISTRIES[suite]
        texts = [
            repo.text("HEAD", p) for p in [path] + [f"{TEST_JAVA}Varka{e}.java" for e in extra]
        ]
        text = COMMENT.sub(" ", "\n".join(texts))
        for n in names:
            if not re.search(pattern.replace("{n}", n), text):
                found.append(f"{path} names {n} in no {what.replace('{n}', n)}")
    return found, notes


# --- R2: a removed reason still pinned -----------------------------------------------------


def windows(line):
    """The word windows of a line's string literals, comments and interpolations skipped."""
    if line.strip().startswith(("//", "*", "/*")):
        return []
    out = []
    for lit in LITERAL.findall(line):
        words = lit.split()
        spans = (
            [words]
            if len(words) < WINDOW
            else [words[i : i + WINDOW] for i in range(len(words) - WINDOW + 1)]
        )
        for span in spans:
            w = " ".join(span)
            if len(w) >= MIN_CHARS and not re.search(r"\$|%[sd]|\{\}", w):
                out.append(w)
    return out


def code_of(text):
    """A test file's text without its comments, which pin nothing; a `//` in a literal stays."""
    return CODE.sub(lambda m: " " if m[0][0] == "/" else m[0], text)


def holding(repo, window, places, rev="HEAD"):
    """The files under places holding window, joins collapsed and test files' comments dropped;
    a document is read whole, a stale example in it being as wrong as a stale pin."""
    words = [w for w in window.split() if len(w) > 2] or window.split()
    hits = []
    for p in repo.files("", places, rev, words=words[:8]):
        text = repo.text(rev, p)
        if window in collapse(code_of(text) if p.startswith(TEST_TREES) else text):
            hits.append(p)
    return hits


def reason_findings(repo, diff):
    """(findings, suites): a removed literal's text the tests or documents still hold, and the
    suites holding the old or the new text of a changed literal."""
    main = {p: d for p, d in diff.items() if is_varka_main(p)}
    findings, suites, seen = [], {}, set()
    for path, (removed, added) in sorted(main.items()):
        head = collapse(repo.text("HEAD", path))
        base = collapse(repo.text(repo.base, path))
        for side, lines, other, rev in (
            ("old", removed, head, "HEAD"),
            ("new", added, base, repo.base),
        ):
            for w in dict.fromkeys(w for line in lines for w in windows(line)):
                # A window the other side still has moved or stayed; it was not reworded.
                if w in other or repo.files(w, MAIN_TREES, rev):
                    continue
                hits = holding(repo, w, REASON_PLACES)
                suites.update(suites_in(repo, hits))
                for hit in hits if side == "old" else ():
                    if (hit, path) not in seen:
                        seen.add((hit, path))
                        what = hit if hit.startswith(TEST_TREES) else f"the document {hit}"
                        findings.append(f'{what} still holds "{w}", which {path} no longer says')
    return findings, suites


# --- R3: the generated files ---------------------------------------------------------------


def generated_findings(owed, check_rc, check_out):
    """What the merge driver's two checks report, with the command that settles it."""
    out = []
    if owed:
        out.append(f"a merge owes {', '.join(owed)}; run {REGEN}")
    if check_rc != 0:
        tail = check_out.strip().splitlines()[-1:] or ["failed"]
        out.append(f"dev/varka_generated_merge.py --check: {tail[0]}; run {REGEN}")
    return out


REBASED = re.compile(r"\(finish\): \S+ onto ([0-9a-f]{40})")
# A reflog entry committing the author's own change; a merge is --owed's, not a regeneration.
COMMITTED = re.compile(r"(commit|commit \(amend\)|cherry-pick): ")


def rebase_debt(repo, branch, generated):
    """{generated file: its rebase}: the files a rebase of branch text-merged, both sides having
    changed them, which the merge driver owes (a rebase names no other side) but records nowhere.
    Read from branch's reflog in order; paid only by what follows the debt: a commit whose change
    from the entry before touches the file, or dev/varka_regen_generated.sh --all run since. A
    later rebase, rewriting the debt's commit, pays nothing; a file now as the base has it owes
    nothing."""
    log = repo.git("reflog", "show", "--format=%H %gs", branch, "--").stdout.splitlines()[::-1]
    marker = repo.git("rev-parse", "--git-path", "varka-regen-all").stdout.strip()
    try:
        with open(os.path.join(repo.root, marker), encoding="utf-8") as f:
            regenerated = f.read().strip()
    except OSError:
        regenerated = ""
    shas = [line.split(" ", 1)[0] for line in log]
    # A replay of an old tip (--branch beside a detached HEAD) reads the reflog up to it only.
    head = repo.git("rev-parse", "HEAD").stdout.strip()
    if head in shas:
        log = log[: len(shas) - shas[::-1].index(head)]
        shas = shas[: len(log)]
    regen_at = max((i for i, s in enumerate(shas) if s == regenerated), default=-1)
    debt = {}
    for i, line in enumerate(log[1:], 1):
        sha, _, subject = line.partition(" ")
        before = shas[i - 1]
        if COMMITTED.match(subject):
            for path in repo.git("diff", "--name-only", before, sha).stdout.split():
                debt.pop(path, None)
        onto = REBASED.search(subject)
        if not onto or repo.ancestor(sha, repo.base):
            continue
        old = repo.git("merge-base", before, onto[1]).stdout.strip()
        sides = [
            set(repo.git("diff", "--name-only", old, s).stdout.split()) for s in (before, onto[1])
        ]
        both = sorted(sides[0] & sides[1] & generated) if old else []
        later = (
            regenerated and repo.ancestor(sha, regenerated) and repo.ancestor(regenerated, "HEAD")
        )
        if not (regen_at >= i or (regen_at < 0 and later)):
            debt.update(dict.fromkeys(both, f"{sha[:11]} onto {onto[1][:11]}"))
    if debt:
        changed = set(repo.git("diff", "--name-only", repo.base, "--", *debt).stdout.split())
        debt = {p: r for p, r in debt.items() if p in changed}
    return debt


def touches_generated(changed, generated):
    return any(
        p in generated or (is_varka_main(p) and p.startswith(GENERATING_TREES)) for p in changed
    )


# --- the plan and the run ------------------------------------------------------------------


def plan(repo, args):
    diff = parse_diff(repo.diff())
    changed = sorted(diff)
    findings = []  # (rule, text)
    suites = {}  # simple name -> [module, fully qualified name, rules]

    def want(found, rule):
        for name, (module, fq) in found.items():
            suites.setdefault(name, [module, fq, set()])[2].add(rule)

    moved = ir_records_changed(repo, changed)
    if moved:
        print(f"R1: the IR records {', '.join(moved)} changed; the registry suites run")
        want(registry_suites(repo), "R1")
        ir = COMMENT.sub(" ", repo.text("HEAD", IR_FILE))
        found, notes = registry_findings(repo, [n for n in moved if f"record {n}(" in ir])
        findings += [("R1", f) for f in found]
        for note in notes:
            print(f"R1: {note}")
    found, chosen = reason_findings(repo, diff)
    findings += [("R2", f) for f in found]
    want(chosen, "R2")
    py = [sys.executable, "dev/varka_generated_merge.py"]
    owed = subprocess.run([*py, "--owed"], cwd=repo.root, capture_output=True, text=True)
    check = subprocess.run([*py, "--check"], cwd=repo.root, capture_output=True, text=True)
    findings += [
        ("R3", f)
        for f in generated_findings(
            owed.stdout.split(), check.returncode, check.stdout + check.stderr
        )
    ]
    listed = subprocess.run([*py, "--list"], cwd=repo.root, capture_output=True, text=True)
    branch = args.branch or repo.git("symbolic-ref", "-q", "--short", "HEAD").stdout.strip()
    debt = rebase_debt(repo, branch, set(listed.stdout.split())) if branch else {}
    if not branch:
        print("R3: HEAD is detached, so no reflog shows a rebase's debt; unchecked, pass --branch")
    for rebase in sorted(set(debt.values())):
        files = ", ".join(p for p, r in sorted(debt.items()) if r == rebase)
        findings.append(("R3", f"the rebase {rebase} text-merged {files}; run {REGEN}"))
    if touches_generated(changed, set(listed.stdout.split())):
        names = GENERATED_SUITES + ((AUDIT_SUITE,) if args.full else ())
        tests = repo.tracked(TEST_TREES)
        want(
            suites_in(repo, [p for p in tests if os.path.basename(p).split(".")[0] in names]), "R3"
        )
    rng = f"{repo.base}..HEAD"
    steps = [
        ("R4", cmd)
        for cmd in (
            [] if args.no_precommit else ["dev/varka_precommit.sh", "--range", rng],
            ["dev/varka_plan_lint.py", "--check", "--offline"],
            ["dev/varka_quote_check.py"],
            ["python3", "dev/varka_census.py", "--check"],
            ["python3", "dev/varka_msg_check.py", "size", "--base", repo.base],
        )
        if cmd
    ]
    jvm = []
    if args.lint and not args.no_jvm:
        jvm += [
            ("R4", ["dev/varka_errorprone.sh"]),
            ("R4", ["dev/scalastyle"]),
            ("R4", ["dev/lint-java"]),
        ]
    return findings, steps, jvm, suites


def heavy(cmd):
    """cmd under the heavy slot's lock, with the loopback address the house binds to."""
    lock = os.environ.get("VARKA_HEAVY_LOCK", os.path.expanduser("~/proj/.varka-locks/heavy.lock"))
    wrapped = ["env", "SPARK_LOCAL_IP=127.0.0.1", *cmd]
    if lock and lock != "none":
        os.makedirs(os.path.dirname(lock), exist_ok=True)
        wrapped = ["flock", lock, *wrapped]
    return wrapped


def matrix_command(suites):
    modules = {m for m, _, _ in suites.values()}
    cmd = ["dev/varka_matrix.sh", "--defaults", "--out", OUT, "-j", "2"]
    varka = sorted(n for n in suites if n.startswith("Varka"))
    if varka:
        cmd += ["--suites", ",".join(varka)]
    else:
        cmd += ["--suites", "@none"]
    for name, (module, fq, _) in sorted(suites.items()):
        if not name.startswith("Varka"):
            cmd += ["--extra-suite", f"{module}:{fq}"]
    cmd += ["--module", "catalyst"] if modules == {"catalyst"} else ["--sbt-arg", "-Phive"]
    return cmd


def reported_suites(out):
    """(ran, failed): the simple names of the suites the run's JUnit reports name, and of those
    with a failed or errored test."""
    ran, failed = set(), set()
    for xml in glob.glob(os.path.join(out, "runs", "defaults", "*", "junit", "TEST-*.xml")):
        try:
            suite = ET.parse(xml).getroot()
        except ET.ParseError:
            continue
        name = suite.get("name", "").rsplit(".", 1)[-1]
        ran.add(name)
        if int(suite.get("failures", 0) or 0) + int(suite.get("errors", 0) or 0):
            failed.add(name)
    return ran, failed


def show(cmd):
    return " ".join(cmd)


def run(args):
    root = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True
    ).stdout.strip()
    os.chdir(root)
    base = (
        args.base
        or subprocess.run(
            ["git", "merge-base", "HEAD", "origin/master"], capture_output=True, text=True
        ).stdout.strip()
    )
    if not base:
        print("varka push check: no merge base with origin/master; pass --base", file=sys.stderr)
        return 2
    repo = Repo(root, base)
    if subprocess.run(
        ["git", "status", "--porcelain", "-uno"], capture_output=True, text=True
    ).stdout.strip():
        print(
            "varka push check: note: the tests run over the working tree, which has changes "
            "beyond HEAD"
        )
    findings, steps, jvm, suites = plan(repo, args)
    # A rule's findings, suites and steps dropped for one push, for a finding read and judged
    # wrong (an R2 hit on a reason that moved, say): VARKA_PUSH_CHECK_SKIP=R2 (or "R2,R3").
    skip = set(RULES) & set(os.environ.get("VARKA_PUSH_CHECK_SKIP", "").replace(",", " ").split())
    if skip:
        print(f"varka push check: skipping {', '.join(sorted(skip))} (VARKA_PUSH_CHECK_SKIP)")
        findings = [f for f in findings if f[0] not in skip]
        steps = [s for s in steps if s[0] not in skip]
        jvm = [j for j in jvm if j[0] not in skip]
        suites = {n: [m, q, r - skip] for n, (m, q, r) in suites.items() if r - skip}
    failed = list(findings)
    for rule, text in findings:
        print(f"{rule}: {text}")
    if suites:
        print(
            "suites: "
            + ", ".join(f"{n} ({'+'.join(sorted(r))})" for n, (_, _, r) in sorted(suites.items()))
        )
    if not args.no_jvm and suites:
        jvm.append(("suites", matrix_command(suites)))
    for rule, cmd in steps + [(r, heavy(c)) for r, c in jvm]:
        print(f"{'would run' if args.dry_run else 'run'} [{rule}]: {show(cmd)}", flush=True)
        if args.dry_run:
            continue
        if rule == "suites":
            shutil.rmtree(os.path.join(OUT, "runs"), ignore_errors=True)
        rc = subprocess.run(cmd, stdin=subprocess.DEVNULL).returncode
        if rule == "suites":
            ran, bad = reported_suites(OUT)
            # A suite the matrix's search does not find runs nowhere, which must not read as a pass.
            for name in sorted(set(suites) - ran):
                failed.append(("+".join(sorted(suites[name][2])), f"{name} did not run"))
        if rc == 0:
            continue
        if rule != "suites":
            failed.append((rule, f"{show(cmd)} exited {rc}"))
            continue
        if not bad:
            failed.append(("suites", f"the suites' run failed without a failed report; see {OUT}"))
        for name in sorted(bad):
            rules = sorted(suites.get(name, [None, None, {"?"}])[2])
            hint = f"; run {REGEN} if the files are stale" if "R3" in rules else ""
            failed.append(("+".join(rules), f"{name} failed{hint}"))
    if failed:
        print("varka push check: FAILED")
        for rule, text in failed:
            print(f"  {rule}: {text}")
        rules = sorted({r for rule, _ in failed for r in rule.split("+") if r in RULES})
        only = f"VARKA_PUSH_CHECK_SKIP={','.join(rules)} (those only) or " if rules else ""
        print(
            f"varka push check: to push past a finding judged wrong, {only}"
            "VARKA_PUSH_CHECK=off (the whole check); git push --no-verify skips the message "
            "and line checks too"
        )
        return 1
    print("varka push check: " + ("planned, nothing run" if args.dry_run else "passed"))
    return 0


# --- self-test -----------------------------------------------------------------------------


class FakeRepo(Repo):
    """Two trees held in maps, for the rules to read without git."""

    def __init__(self, base_files, head_files):
        super().__init__(None, "BASE")
        self.trees = {"BASE": base_files, "HEAD": head_files}

    def text(self, rev, path):
        return self.trees[rev].get(path, "")

    def files(self, pattern, places, rev="HEAD", fixed=True, words=()):
        def has(t):
            if words:
                return all(w in t for w in words)
            return pattern in t if fixed else re.search(pattern, t)

        return sorted(p for p, t in self.trees[rev].items() if p.startswith(places) and has(t))

    def tracked(self, places):
        return sorted(p for p in self.trees["HEAD"] if p.startswith(places))


IR_BASE = """package x;
public sealed interface VarkaVectorIR {
  record Not(Cond child, int hash) implements Cond {
  }
}
"""
PLAN = "sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/P.java"
SUITE = "sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaFilterSuite.scala"
OLD = """    return refusal("kernel meets on an integral lane, and a mask crossing from a floating "
        + "lane is not built");
"""
NEW = """    return refusal("kernel meets on an integral lane, and its conjuncts do not cross "
        + "from a floating lane to it yet");
"""
PINNED = """package org.apache.spark.sql.execution
class VarkaFilterSuite {
  val reason = "its kernel meets on an integral lane, and a mask crossing " +
    "from a floating lane is not built"
}
"""


def diff_of(base, head):
    """A -U0 diff of maps, enough for parse_diff: every line of a changed file removed and
    added."""
    out = []
    for path in sorted(base.keys() | head.keys()):
        if base.get(path) != head.get(path):
            out.append(f"diff --git a/{path} b/{path}")
            out += ["-" + x for x in base.get(path, "").splitlines()]
            out += ["+" + x for x in head.get(path, "").splitlines()]
    return "\n".join(out)


class SelfTest(unittest.TestCase):
    def test_r1_fires_on_a_new_record_and_not_on_a_comment(self):
        added = IR_BASE.replace(
            "}\n}", "}\n  record CrossLane(Cond child, int hash) implements Cond {\n  }\n}"
        )
        repo = FakeRepo({IR_FILE: IR_BASE}, {IR_FILE: added})
        self.assertEqual(ir_records_changed(repo, [IR_FILE]), ["CrossLane"])
        comment = IR_BASE.replace("package x;", "package x;\n// a record Foo(int a) {")
        repo = FakeRepo({IR_FILE: IR_BASE}, {IR_FILE: comment})
        self.assertEqual(ir_records_changed(repo, [IR_FILE]), [])

    def test_r1_fires_on_a_changed_component_wrapped_over_lines(self):
        wrapped = IR_BASE.replace("(Cond child, int hash)", "(Cond child,\n      int hash, int z)")
        repo = FakeRepo({IR_FILE: IR_BASE}, {IR_FILE: wrapped})
        self.assertEqual(ir_records_changed(repo, [IR_FILE]), ["Not"])

    def test_r1_finds_the_registry_suites_by_grep(self):
        t = "sql/catalyst/src/test/scala/a/"
        repo = FakeRepo(
            {},
            {
                t + "VarkaWithChildrenSuite.scala": "package a\nclass VarkaWithChildrenSuite {\n"
                "  getPermittedSubclasses\n}\n",
                t + "VarkaIrFuzzSuite.scala": "package a\nclass VarkaIrFuzzSuite {}\n",
                t + "VarkaOtherSuite.scala": "package a\nclass VarkaOtherSuite {}\n",
            },
        )
        self.assertEqual(
            sorted(registry_suites(repo)), ["VarkaIrFuzzSuite", "VarkaWithChildrenSuite"]
        )

    def test_r1_names_the_registry_that_lacks_a_record_without_a_jvm(self):
        t = "sql/catalyst/src/test/scala/a/"
        repo = FakeRepo(
            {},
            {
                t + "VarkaWithChildrenSuite.scala": 'getPermittedSubclasses - "Not"',
                t + "VarkaLaneTypeSuite.scala": 'a node type was added case "CrossLane" =>',
                t + "VarkaEmitterBudgetSuite.scala": "getPermittedSubclasses BoundedDivide.of(c)",
                t + "VarkaNewSuite.scala": "never drew",
            },
        )
        found, notes = registry_findings(repo, ["CrossLane", "BoundedDivide"])
        self.assertCountEqual(
            [f.split("/")[-1] for f in found],
            [
                "VarkaWithChildrenSuite.scala names CrossLane in no undrawn entry or draw",
                "VarkaWithChildrenSuite.scala names BoundedDivide in no undrawn entry or draw",
                "VarkaLaneTypeSuite.scala names BoundedDivide in no instance recipe "
                '(case "BoundedDivide")',
                "VarkaEmitterBudgetSuite.scala names CrossLane in no register row",
            ],
        )
        self.assertEqual(len(notes), 1)
        self.assertIn("VarkaNewSuite.scala enumerates node types unread here", notes[0])

    def test_r3_a_committed_rebase_owes_what_both_sides_changed_until_regenerated(self):
        with tempfile.TemporaryDirectory() as d:
            repo = Repo(d, "master")
            env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
            repo.env = {**env, "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1"}

            def commit(path, text, msg):
                with open(os.path.join(d, path), "w", encoding="utf-8") as f:
                    f.write(text)
                repo.git("add", path, check=True)
                repo.git("commit", "-q", "-m", msg, check=True)

            repo.git("init", "-q", "-b", "master", check=True)
            for k, v in (("user.name", "t"), ("user.email", "t@t"), ("core.hooksPath", "/n")):
                repo.git("config", k, v, check=True)
            commit("g.json", "1\n2\n3\n4\n5\n", "base")
            repo.git("checkout", "-q", "-b", "topic", check=True)
            commit("g.json", "1 topic\n2\n3\n4\n5\n", "topic")
            commit("x.txt", "x\n", "topic's own file")
            repo.git("checkout", "-q", "master", check=True)
            commit("g.json", "1\n2\n3\n4\n5 master\n", "master")
            repo.git("checkout", "-q", "topic", check=True)
            repo.git("rebase", "-q", "master", check=True)
            head = repo.git("rev-parse", "HEAD").stdout.strip()
            debt = rebase_debt(repo, "topic", {"g.json", "x.txt"})
            self.assertEqual(list(debt), ["g.json"])
            self.assertTrue(debt["g.json"].startswith(head[:11]))
            marker = repo.git("rev-parse", "--git-path", "varka-regen-all").stdout.strip()
            with open(os.path.join(d, marker), "w", encoding="utf-8") as f:
                f.write(head + "\n")
            self.assertEqual(rebase_debt(repo, "topic", {"g.json"}), {})
            os.remove(os.path.join(d, marker))
            # A second rebase, master not changing the file, rewrites the debt's commit: still owed.
            repo.git("checkout", "-q", "master", check=True)
            commit("y.txt", "y\n", "master again")
            repo.git("checkout", "-q", "topic", check=True)
            repo.git("rebase", "-q", "master", check=True)
            self.assertFalse(repo.ancestor(head, "HEAD"))
            self.assertEqual(rebase_debt(repo, "topic", {"g.json"}), debt)
            commit("g.json", "1 topic\n2\n3\n4\n5 master regenerated\n", "regenerate")
            self.assertEqual(rebase_debt(repo, "topic", {"g.json"}), {})

    def test_r2_fires_on_a_removed_reason_a_test_pins_split_elsewhere(self):
        base = {PLAN: OLD, SUITE: PINNED}
        head = {PLAN: NEW, SUITE: PINNED}
        found, suites = reason_findings(FakeRepo(base, head), parse_diff(diff_of(base, head)))
        self.assertTrue(found and SUITE in found[0], found)
        self.assertEqual(list(suites), ["VarkaFilterSuite"])

    def test_r2_is_quiet_when_the_test_holds_the_new_text_but_runs_it(self):
        repinned = PINNED.replace(
            'a mask crossing " +\n    "from a floating lane is not built',
            "its conjuncts do not cross from a floating lane to it",
        )
        base = {PLAN: OLD, SUITE: PINNED}
        head = {PLAN: NEW, SUITE: repinned}
        found, suites = reason_findings(FakeRepo(base, head), parse_diff(diff_of(base, head)))
        self.assertEqual(found, [])
        self.assertEqual(list(suites), ["VarkaFilterSuite"])

    def test_r2_is_quiet_when_the_text_only_moved_or_rewrapped(self):
        rewrapped = OLD.replace('a floating "\n        + "lane', 'a "\n        + "floating lane')
        base = {PLAN: OLD, SUITE: PINNED}
        head = {PLAN: rewrapped, SUITE: PINNED}
        found, _ = reason_findings(FakeRepo(base, head), parse_diff(diff_of(base, head)))
        self.assertEqual(found, [])

    def test_r2_reads_no_comment_of_a_test_but_a_whole_document(self):
        text = "a mask crossing from a floating lane is not built"
        noted = (
            f'class VarkaFilterSuite {{\n  // {text}\n  /* or\n   * {text} */ "http://x" // {text}'
        )
        doc = "docs/sql-varka.md"
        base = {PLAN: OLD, SUITE: noted, doc: f"the reason, {text[:30]}\n{text[30:]}.\n"}
        head = dict(base, **{PLAN: NEW})
        found, _ = reason_findings(FakeRepo(base, head), parse_diff(diff_of(base, head)))
        self.assertTrue(found and all(f.startswith(f"the document {doc} ") for f in found), found)

    def test_r2_skips_comments_and_short_literals(self):
        self.assertEqual(windows('  // "a mask crossing from a floating lane is not built"'), [])
        self.assertEqual(windows('  x("built", "lane")'), [])

    def test_r3_fires_on_an_owed_or_failed_check_with_the_command(self):
        found = generated_findings(["sql/varka/emitted_bytes.json"], 0, "")
        self.assertTrue(found and REGEN in found[0])
        self.assertTrue(REGEN in generated_findings([], 1, "missing a coverage row\n")[0])
        self.assertEqual(generated_findings([], 0, "ok"), [])

    def test_r3_runs_the_suites_for_a_varka_main_or_generated_file_only(self):
        gen = {"sql/varka/emitted_bytes.json"}
        self.assertTrue(touches_generated([PLAN], gen))
        self.assertTrue(touches_generated(["sql/varka/emitted_bytes.json"], gen))
        self.assertFalse(touches_generated(["sql/varka/plans/m8/PLAN.md", SUITE], gen))

    def test_the_matrix_call_builds_catalyst_alone_when_it_can(self):
        cat = {"VarkaIrFuzzSuite": ["catalyst", "a.VarkaIrFuzzSuite", {"R1"}]}
        self.assertIn("--module", matrix_command(cat))
        both = dict(cat, VarkaFilterSuite=["sql", "b.VarkaFilterSuite", {"R2"}])
        self.assertIn("-Phive", matrix_command(both))
        self.assertNotIn("--module", matrix_command(both))

    def test_the_lock_can_be_turned_off(self):
        saved = os.environ.get("VARKA_HEAVY_LOCK")
        try:
            os.environ["VARKA_HEAVY_LOCK"] = "none"
            self.assertEqual(heavy(["x"])[:2], ["env", "SPARK_LOCAL_IP=127.0.0.1"])
            with tempfile.TemporaryDirectory() as d:
                os.environ["VARKA_HEAVY_LOCK"] = os.path.join(d, "l", "heavy.lock")
                self.assertEqual(heavy(["x"])[0], "flock")
        finally:
            os.environ.pop("VARKA_HEAVY_LOCK", None)
            if saved is not None:
                os.environ["VARKA_HEAVY_LOCK"] = saved


def main(argv):
    if argv[1:2] == ["--self-test"]:
        result = unittest.main(argv=argv[:1], exit=False, verbosity=1).result
        return 0 if result.wasSuccessful() else 1
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--base")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--no-jvm", action="store_true")
    p.add_argument("--lint", action="store_true")
    p.add_argument("--full", action="store_true")
    p.add_argument("--no-precommit", action="store_true", help="the pre-push hook ran it already")
    p.add_argument("--branch", help="the branch whose reflog records its rebases (default HEAD's)")
    return run(p.parse_args(argv[1:]))


if __name__ == "__main__":
    sys.exit(main(sys.argv))
