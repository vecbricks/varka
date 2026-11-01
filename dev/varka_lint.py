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
"""
The rules dev/varka_precommit.sh runs that need more than a line's regex: mistakes Varka made
more than once, each caught by CI, a reviewer or a red night after the commit (VARKA-492).

Each rule has a name, and a commit can skip named rules with VARKA_LINT_SKIP=name[,name] when a
finding is answered some other way (a new dependency declared in the pom, say). As in the
pre-commit script, a line-anchored finding is raised only for a line the commit writes, and
naming files on the command line turns that scoping off.

  license-header   a new Java, Scala, Python or shell file has no Apache license header and is
                   not excluded by dev/.rat-excludes (CI's license check fails on it)
  import-root      a Java or Scala file imports a third-party package no other file of its
                   module imports: sbt may resolve it transitively where Maven does not, so the
                   Maven build fails to compile (VarkaMaskOfWordSuite and ASM, VARKA-491)
  ci-module        a new file that no module of dev/sparktestsupport/modules.py claims and that
                   is neither ignored nor Varka-scoped, so a pull request runs Spark's whole
                   test matrix
  skips-tsv        a line of sql/varka/matrix/skips.tsv that names no option, no suite or no
                   test, has a kind other than fails or declines, has no reason, or repeats a
                   line (a merge that brings lines back leaves them twice)
  task-number      "task 236" where the house form is VARKA-236
  bench-ids        two literal case ids in one benchmark file (dev/varka_bench_ids.sh also sees
                   the computed ones)
  binding-policy   a spark.sql.codegen.varka conf built without .withBindingPolicy
  shell-usage      a script printing its usage with a hard-coded line range of "$0"
  shell-java-opts  JAVA_OPTS or SBT_OPTS given a -XX flag in a script that runs build/sbt: the
                   test JVMs sbt forks never see it (build-and-environment.md)
  guava            a Guava import in Varka code (sql/varka/AGENTS.md)

  dev/varka_lint.py [--cached | --working-tree | --range A..B | FILE...]
  dev/varka_lint.py --selftest

Exit status is the number of findings.
"""

import fnmatch
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LICENSE_MARK = "Licensed to the Apache Software Foundation"
SKIPS = "sql/varka/matrix/skips.tsv"
OPTIONS_FILE = (
    "sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/"
    "VarkaEmitOption.java"
)
# The files that predate the Guava rule; any other Varka file stays off Guava.
GUAVA_ALLOWED = (
    "VarkaShapeCacheImpl.java",
    "VarkaProofFiles.java",
    "VarkaEmitterYmDivideSuite.scala",
)
# Packages a module may import without declaring them: the JDK, Scala and Spark itself.
STANDARD_ROOTS = ("java.", "javax.", "jdk.", "sun.", "com.sun.", "scala.", "org.apache.spark.")
MATRIX_RUNS = (
    "sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/"
    "VarkaMatrixRuns.java"
)


def git(*args, check=True):
    out = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True)
    if check and out.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)}: {out.stderr.strip()}")
    return out.stdout


class Change:
    """The files a commit (or range, or explicit list) touches, and the lines it writes."""

    def __init__(self, argv):
        self.scoped = True
        self.range = None
        self.new = set()
        self.written = {}
        # Each parent the change is measured against: one, or during a merge both, since the
        # lines a merge brings in from the other side are not the commit's.
        self.bases = []
        if argv and argv[0] == "--range":
            self.range = argv[1]
            status = git("diff", "--name-status", "--diff-filter=ACMR", self.range)
            self.bases = [[self.range]]
        elif argv and argv[0] == "--working-tree":
            status = git("diff", "--name-status", "--diff-filter=ACMR", "HEAD")
            for f in git("ls-files", "--others", "--exclude-standard").split():
                status += f"A\t{f}\n"
            self.bases = [["HEAD"]]
        elif argv and argv[0] != "--cached":
            self.scoped = False
            self.files = [f for f in argv if os.path.isfile(os.path.join(ROOT, f))]
            return
        else:
            status = git("diff", "--cached", "--name-status", "--diff-filter=ACMR")
            self.bases = [["--cached"]]
            merge_head = git("rev-parse", "-q", "--verify", "MERGE_HEAD", check=False).strip()
            if merge_head:
                self.bases.append(["--cached", merge_head])
        self.files = []
        for line in status.splitlines():
            kind, _, path = line.partition("\t")
            path = path.split("\t")[-1]
            self.files.append(path)
            if kind.startswith("A"):
                self.new.add(path)
        if len(self.bases) > 1:
            # A file the other side already has is not this merge's, and neither is it new.
            theirs = git("diff", "--name-status", "--diff-filter=ACMR", *self.bases[1])
            changed = {line.split("\t")[-1] for line in theirs.splitlines()}
            added = {line.split("\t")[-1] for line in theirs.splitlines() if line[:1] == "A"}
            self.files = [f for f in self.files if f in changed]
            self.new &= added

    def wrote(self, path, lineno):
        if not self.scoped or path in self.new:
            return True
        if path not in self.written:
            written = None
            for base in self.bases:
                lines = set()
                diff = git("diff", "-U0", *base, "--", path, check=False)
                for m in re.finditer(r"^@@ -\S+ \+(\d+)(?:,(\d+))? @@", diff, re.M):
                    start, count = int(m.group(1)), int(m.group(2) or "1")
                    lines.update(range(start, start + count))
                written = lines if written is None else written & lines
            self.written[path] = written or set()
        return lineno in self.written[path]

    def is_new(self, path):
        return not self.scoped or path in self.new


def read(path):
    try:
        with open(os.path.join(ROOT, path), encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def is_varka(path):
    return "/varka/" in path or path.startswith("sql/varka/") or "Varka" in os.path.basename(path)


def rat_excluded(path):
    patterns = [p.strip() for p in read("dev/.rat-excludes").splitlines()]
    parts = path.split("/")
    for p in patterns:
        if not p or p.startswith("#"):
            continue
        if "/" in p:
            if any(fnmatch.fnmatch("/".join(parts[i:]), p) for i in range(len(parts))):
                return True
        else:
            # RAT matches a bare pattern against every name on the path, directories included.
            for part in parts:
                try:
                    if re.fullmatch(p, part):
                        return True
                except re.error:
                    if fnmatch.fnmatch(part, p):
                        return True
    return False


def rule_license_header(ch):
    for path in ch.files:
        if not ch.is_new(path) or not re.search(r"\.(java|scala|py|sh|proto|xml|ya?ml|R)$", path):
            continue
        if LICENSE_MARK in "\n".join(read(path).splitlines()[:40]) or rat_excluded(path):
            continue
        yield (
            f"{path}:1: license-header: no Apache license header; CI's license check (Apache "
            "RAT) fails on it - copy the header of a neighbouring file, or list a file that "
            "cannot carry one in dev/.rat-excludes"
        )


IMPORT = re.compile(r"^\s*import\s+(?:static\s+)?([A-Za-z_][\w.]*)")


def import_root(name):
    """The package an import comes from, cut at three names: org.objectweb.asm, org.scalatest."""
    parts = []
    for part in name.split("."):
        if not part or not part[0].islower():
            break
        parts.append(part)
    depth = 3 if parts and parts[0] in ("org", "com", "io", "net", "dev", "edu") else 2
    return ".".join(parts[:depth])


# Package names start with these; a Scala import of a value (`session.implicits._`) does not.
PACKAGE_STARTS = ("org", "com", "io", "net", "dev", "edu", "jakarta", "ai", "it", "de", "fr")


def declared_groups(module):
    """The groupIds a module depends on: its own pom's, and the root pom's own dependencies,
    which every module inherits. Not the root pom's dependencyManagement, which only pins a
    version, nor its plugins, nor the parent's groupId."""
    root_pom = read("pom.xml")
    root_pom = re.sub(r"<dependencyManagement>.*?</dependencyManagement>", "", root_pom, flags=re.S)
    root_pom = re.sub(r"<build>.*?</build>", "", root_pom, flags=re.S)
    root_pom = re.sub(r"<parent>.*?</parent>", "", root_pom, flags=re.S)
    module_pom = re.sub(r"<parent>.*?</parent>", "", read(f"{module}/pom.xml"), flags=re.S)
    module_pom = re.sub(r"<build>.*?</build>", "", module_pom, flags=re.S)
    groups = set(re.findall(r"<groupId>\s*([\w.-]+)\s*</groupId>", module_pom + root_pom))
    return groups - {"org.apache", "org.apache.spark"}


def declared(root, module):
    """Whether a groupId the module depends on is a prefix of the package, or the other way."""
    groups = declared_groups(module)
    return any(root == g or root.startswith(g + ".") or g.startswith(root + ".") for g in groups)


def rule_import_root(ch):
    seen = set()
    for path in ch.files:
        if not re.search(r"\.(java|scala)$", path) or "/src/" not in path:
            continue
        module = path.split("/src/")[0]
        for n, line in enumerate(read(path).splitlines(), 1):
            m = IMPORT.match(line)
            if not m or not ch.wrote(path, n):
                continue
            name = m.group(1)
            # A Scala import of a member of something already in scope (`import Analysis._`)
            # names no package.
            if name.startswith(STANDARD_ROOTS) or name.split(".")[0] not in PACKAGE_STARTS:
                continue
            root = import_root(name)
            if not root or declared(root, module):
                continue
            if (module, root) in seen:
                continue
            seen.add((module, root))
            pattern = r"^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?"
            pattern += re.escape(root) + r"[.;[:space:]{]"
            users = git(
                "grep", "-l", "-E", pattern, "HEAD", "--", f"{module}/src", check=False
            ).split()
            users = [u.split(":", 1)[1] for u in users if not u.endswith(":" + path)]
            if not users:
                yield (
                    f"{path}:{n}: import-root: the first import of {root} in {module}: sbt "
                    f"may resolve it transitively where Maven does not - declare its "
                    f"artifact in {module}/pom.xml, then commit with VARKA_LINT_SKIP=import-root"
                )


def rule_ci_module(ch):
    new = [p for p in ch.files if ch.is_new(p)]
    if not new:
        return
    sys.path.insert(0, os.path.join(ROOT, "dev"))
    try:
        from sparktestsupport import modules
    except Exception as e:  # a broken modules.py is CI's to report, not this rule's
        print(f"varka_lint: ci-module: cannot import modules.py ({e}); rule skipped")
        return
    for path in new:
        if modules.is_ignored_file(path) or modules.is_varka_scoped(path):
            continue
        if any(m.contains_file(path) for m in modules.all_modules):
            continue
        yield (
            f"{path}:1: ci-module: no module of dev/sparktestsupport/modules.py claims this "
            "file and it is not Varka-scoped, so a pull request with it runs Spark's whole "
            "test matrix - name the file in a module or in varka_scoped_regexes"
        )


def option_names():
    """The emit options, and the matrix's other axes (arrowBatchSize, say)."""
    names = set(re.findall(r'new\s+\w+\(\s*"(\w+)"', read(OPTIONS_FILE)))
    names.update(re.findall(r'static final String \w+ = "(\w+)"', read(MATRIX_RUNS)))
    return names


def joined(text):
    """A source with its string literals split by `+` joined back, so a test name written across
    lines reads as the one name the suite runs it under."""
    return re.sub(r'"\s*\+\s*s?"', "", text)


def suite_text(suite):
    """The suite's joined source (see `joined`), or None if absent."""
    hits = git("ls-files", f"*/{suite}.scala", f"*/{suite}.java").split()
    if not hits:
        return None
    return joined(read(hits[0]))


_TEST_SOURCES = {}


def inherited_test(test):
    """Whether some test source names the test: a suite may take its tests from a parent. The
    sources are read as they stand in the tree being committed and joined like the suite's own,
    so a name split across lines in a parent is found, and a rename and its skip line can land in
    one commit."""
    if ROOT not in _TEST_SOURCES:
        files = git("ls-files", "sql/*/src/test/*.scala", "sql/*/src/test/*.java").split()
        _TEST_SOURCES[ROOT] = [joined(read(f)) for f in files if "Varka" in f]
    return any(test in text or test in unescape(text) for text in _TEST_SOURCES[ROOT])


def names_tests_at_run_time(text):
    """Whether a suite builds some test names at run time, which a literal search cannot see: a
    ScalaTest name that is not a literal, or a JUnit dynamic test's computed display name."""
    return (
        re.search(r'\btest\((?!\s*")', text) is not None
        or 'test(s"' in text
        or re.search(r'\bdynamicTest\((?!\s*")', text) is not None
    )


def unescape(name):
    return name.replace('\\"', '"').replace("\\\\", "\\")


def rule_skips_tsv(ch):
    if SKIPS not in ch.files:
        return
    options = option_names()
    seen = {}
    for n, line in enumerate(read(SKIPS).splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        if line in seen:
            yield f"{SKIPS}:{n}: skips-tsv: repeats line {seen[line]}"
            continue
        seen[line] = n
        if not ch.wrote(SKIPS, n):
            continue
        fields = line.split("\t")
        if len(fields) != 5:
            yield f"{SKIPS}:{n}: skips-tsv: {len(fields)} tab-separated fields, not 5"
            continue
        config, suite, test, kind, reason = fields
        for part in config.split(","):
            key = part.split("=")[0].strip()
            if key and options and key not in options:
                yield f"{SKIPS}:{n}: skips-tsv: '{key}' is not an option of VarkaEmitOption"
        text = suite_text(suite)
        if text is None:
            yield f"{SKIPS}:{n}: skips-tsv: no suite named {suite}"
        elif (
            test not in text
            and test not in unescape(text)
            and not names_tests_at_run_time(text)
            and not inherited_test(test)
        ):
            yield f"{SKIPS}:{n}: skips-tsv: {suite} has no test named '{test}'"
        if kind not in ("fails", "declines"):
            yield f"{SKIPS}:{n}: skips-tsv: kind '{kind}' is neither fails nor declines"
        if not reason.strip():
            yield f"{SKIPS}:{n}: skips-tsv: no reason"


# Not inside quotes or backticks, where a document names the form the rule replaces.
TASK_NUMBER = re.compile(r"(?<![\"`])\b[Tt]asks? (\d{2,4})\b")


def rule_task_number(ch):
    for path in ch.files:
        if not is_varka(path) or not re.search(r"\.(java|scala|py|sh|md)$", path):
            continue
        if path.startswith("dev/"):
            continue
        for n, line in enumerate(read(path).splitlines(), 1):
            m = TASK_NUMBER.search(line)
            # Spark's own tasks ("task 1234 failed", "task 3 of stage 2") are not Varka's.
            if m and re.search(r"\b(stage|attempt|executor|failed|TID)\b", line, re.I):
                continue
            if m and ch.wrote(path, n):
                yield f"{path}:{n}: task-number: '{m.group(0)}'; a Varka task is VARKA-{m.group(1)}"


CASE_ID = re.compile(r"\bloader(?:\(\))?,\s*(\d+)\b(?!\s*[+*])")


def rule_bench_ids(ch):
    for path in ch.files:
        if not re.search(r"Benchmark\.(java|scala)$", path):
            continue
        first = {}
        for n, line in enumerate(read(path).splitlines(), 1):
            for m in CASE_ID.finditer(line):
                cid = m.group(1)
                if cid in first:
                    yield (
                        f"{path}:{n}: bench-ids: case id {cid} is also used on line "
                        f"{first[cid]}; run dev/varka_bench_ids.sh for the computed ones too"
                    )
                else:
                    first[cid] = n


def rule_binding_policy(ch):
    for path in ch.files:
        if not path.endswith(".scala"):
            continue
        text = read(path)
        for m in re.finditer(r'buildConf\(\s*s?"spark\.sql\.codegen\.varka', text):
            end = re.search(r"\.create\w*", text[m.end() :])
            block = text[m.end() : m.end() + end.start()] if end else text[m.end() :]
            if ".withBindingPolicy(" not in block:
                n = text.count("\n", 0, m.start()) + 1
                yield (
                    f"{path}:{n}: binding-policy: a Varka conf without .withBindingPolicy; "
                    "the config audit fails on it (run-config-audit-suite-after-adding-configs)"
                )


USAGE_RANGE = re.compile(r"""sed\s+-n\s+['"]?\d+,\d+p['"]?\s+["']?\$\{?0\}?""")
JAVA_OPTS = re.compile(r"\b(JAVA_OPTS|SBT_OPTS)=.*-XX")


def rule_shell(ch):
    for path in ch.files:
        if not (path.endswith(".sh") or path.startswith(".github/workflows/")):
            continue
        text = read(path)
        runs_sbt = "build/sbt" in text
        for n, line in enumerate(text.splitlines(), 1):
            if not ch.wrote(path, n):
                continue
            if USAGE_RANGE.search(line):
                yield (
                    f"{path}:{n}: shell-usage: usage printed from a fixed line range of the "
                    "script, which truncates as the comment grows; end it at the first "
                    "non-comment line instead: sed -n '17,/^[^#]/p'"
                )
            if runs_sbt and JAVA_OPTS.search(line):
                yield (
                    f"{path}:{n}: shell-java-opts: the test JVMs sbt forks never read "
                    "JAVA_OPTS or SBT_OPTS; pass the flag through Test/javaOptions "
                    "(build-and-environment.md)"
                )


def rule_guava(ch):
    for path in ch.files:
        if not re.search(r"\.(java|scala)$", path) or not is_varka(path):
            continue
        if os.path.basename(path) in GUAVA_ALLOWED:
            continue
        for n, line in enumerate(read(path).splitlines(), 1):
            if re.match(r"\s*import\s+com\.google\.common\b", line) and ch.wrote(path, n):
                yield (
                    f"{path}:{n}: guava: Varka code does not take new Guava dependencies "
                    "(sql/varka/AGENTS.md); use the JDK"
                )


RULES = {
    "license-header": rule_license_header,
    "import-root": rule_import_root,
    "ci-module": rule_ci_module,
    "skips-tsv": rule_skips_tsv,
    "task-number": rule_task_number,
    "bench-ids": rule_bench_ids,
    "binding-policy": rule_binding_policy,
    "shell": rule_shell,
    "guava": rule_guava,
}


def run(argv):
    skip = {s.strip() for s in os.environ.get("VARKA_LINT_SKIP", "").split(",") if s.strip()}
    ch = Change(argv)
    findings = []
    for name, rule in RULES.items():
        for finding in rule(ch):
            rule_name = finding.split(": ")[1] if ": " in finding else name
            if rule_name not in skip and name not in skip:
                findings.append(finding)
    for f in findings:
        print(f)
    # An exit status wraps at 256; a count past that would read as success.
    return min(len(findings), 100)


def selftest():
    """Each rule against a small repository: a case it must report and one it must not."""
    fails = 0
    # A hook runs with GIT_DIR and GIT_INDEX_FILE exported, and every git call below would
    # inherit them and read the repository being committed to instead of the fixture.
    for key in [k for k in os.environ if k.startswith("GIT_")]:
        del os.environ[key]
    env = dict(os.environ)
    lic = "// " + LICENSE_MARK + "\n"
    with tempfile.TemporaryDirectory() as tmp:

        def sh(*cmd):
            subprocess.run(cmd, cwd=tmp, env=env, check=True, capture_output=True)

        def write(path, text):
            full = os.path.join(tmp, path)
            os.makedirs(os.path.dirname(full) or tmp, exist_ok=True)
            with open(full, "w") as f:
                f.write(text)

        sh("git", "init", "-q", ".")
        sh("git", "config", "user.email", "t@example.com")
        sh("git", "config", "user.name", "t")
        os.makedirs(os.path.join(tmp, "dev"), exist_ok=True)
        write("dev/.rat-excludes", ".*\\.json\n")
        write("m/src/main/java/A.java", lic + "import org.known.lib.Thing;\nclass A {}\n")
        write("m/src/test/java/VarkaBenchmark.java", lic + "emit(r, 1, loader, 7);\n")
        sh("git", "add", "-A")
        sh("git", "-c", "core.hooksPath=/dev/null", "commit", "-qm", "base")

        cases = []

        def case(name, files, expect_rule, expect):
            nonlocal fails
            for path, text in files.items():
                write(path, text)
            sh("git", "add", "-A")
            global ROOT
            saved, ROOT = ROOT, tmp
            try:
                ch = Change(["--cached"])
                rule = RULES[expect_rule.split(":")[0]]
                got = any(expect_rule.split(":")[-1] in f for f in rule(ch))
            finally:
                ROOT = saved
            if got != expect:
                print(f"varka_lint selftest: {name}: expected {expect}, got {got}")
                fails += 1
            sh("git", "reset", "-q", "--hard", "HEAD")
            sh("git", "clean", "-qfd")
            cases.append(name)

        case(
            "a new Java file without a header",
            {"m/src/main/java/B.java": "class B {}\n"},
            "license-header",
            True,
        )
        case(
            "a new Java file with a header",
            {"m/src/main/java/B.java": lic},
            "license-header",
            False,
        )
        tables = "sql/m/src/test/java/VarkaShapeTables.java"
        suite = "sql/m/src/test/java/VarkaShapeSuite.java"
        split = lic + '@DisplayName("a name split "\n      + "across two lines")\nvoid t() {}\n'
        skip = "a=1\tVarkaShapeSuite\t%s\tfails\tthe reason\n"
        case(
            "a skip line naming a test its parent splits across lines",
            {
                tables: split,
                suite: lic + "class VarkaShapeSuite {}\n",
                SKIPS: skip % "a name split across two lines",
            },
            "skips-tsv",
            False,
        )
        case(
            "a skip line naming no test",
            {SKIPS: skip % "a name nobody wrote"},
            "skips-tsv",
            True,
        )
        case("a new excluded JSON file", {"m/x.json": "{}\n"}, "license-header", False)
        case(
            "a first import of a library",
            {"m/src/test/java/C.java": lic + "import org.objectweb.asm.Type;\n"},
            "import-root",
            True,
        )
        case(
            "an import the module already has",
            {"m/src/test/java/C.java": lic + "import org.known.lib.Other;\n"},
            "import-root",
            False,
        )
        case(
            "a JDK import",
            {"m/src/test/java/C.java": lic + "import java.util.List;\n"},
            "import-root",
            False,
        )
        case(
            "'task 236' in a Varka file",
            {"sql/varka/plans/X.md": "Done in task 236.\n"},
            "task-number",
            True,
        )
        case(
            "'VARKA-236' in a Varka file",
            {"sql/varka/plans/X.md": "Done in VARKA-236.\n"},
            "task-number",
            False,
        )
        case(
            "a duplicate literal case id",
            {
                "m/src/test/java/VarkaBenchmark.java": lic + "emit(r, 1, loader, 7);\n"
                "emit(q, 1, loader, 7);\n"
            },
            "bench-ids",
            True,
        )
        case(
            "a computed block after a literal",
            {
                "m/src/test/java/VarkaBenchmark.java": lic + "emit(r, 1, loader, 7);\n"
                "emit(q, 1, loader, 7 + i);\n"
            },
            "bench-ids",
            False,
        )
        conf = 'buildConf("spark.sql.codegen.varka.x")\n  .doc("d")\n'
        case(
            "a Varka conf without a binding policy",
            {"m/Conf.scala": conf + "  .createWithDefault(1)\n"},
            "binding-policy",
            True,
        )
        case(
            "a Varka conf with one",
            {"m/Conf.scala": conf + "  .withBindingPolicy(P)\n  .createWithDefault(1)\n"},
            "binding-policy",
            False,
        )
        case(
            "a hard-coded usage range",
            {"dev/x.sh": "usage() { sed -n '17,40p' \"$0\"; }\n"},
            "shell:shell-usage",
            True,
        )
        case(
            "a usage range ended by a pattern",
            {"dev/x.sh": "usage() { sed -n '17,/^[^#]/p' \"$0\"; }\n"},
            "shell:shell-usage",
            False,
        )
        case(
            "JAVA_OPTS with -XX before build/sbt",
            {"dev/x.sh": "JAVA_OPTS=-XX:MaxVectorSize=16 build/sbt test\n"},
            "shell:shell-java-opts",
            True,
        )
        # A merge: a file the other side brings in is not the merge's, however it breaks a rule.
        sh("git", "checkout", "-q", "-b", "side")
        write("m/src/main/java/FromSide.java", "class FromSide {}\n")
        sh("git", "add", "-A")
        sh("git", "-c", "core.hooksPath=/dev/null", "commit", "-qm", "side")
        sh("git", "checkout", "-q", "-")
        write("m/src/main/java/Ours.java", lic + "class Ours {}\n")
        sh("git", "add", "-A")
        sh("git", "-c", "core.hooksPath=/dev/null", "commit", "-qm", "ours")
        sh("git", "merge", "-q", "--no-commit", "--no-ff", "side")
        saved_root = ROOT
        globals()["ROOT"] = tmp
        try:
            got = any("FromSide" in f for f in rule_license_header(Change(["--cached"])))
        finally:
            globals()["ROOT"] = saved_root
        if got:
            print("varka_lint selftest: a file a merge brings in: expected False, got True")
            fails += 1
        cases.append("a file a merge brings in")
        sh("git", "merge", "--abort")
        sh("git", "reset", "-q", "--hard", "HEAD~1")
        case(
            "a Guava import in Varka code",
            {"m/src/main/java/VarkaX.java": lic + "import com.google.common.base.X;\n"},
            "guava",
            True,
        )
    if fails == 0:
        print(f"varka_lint selftest: ok ({len(cases)} cases)")
    return fails


if __name__ == "__main__":
    if sys.argv[1:2] in (["-h"], ["--help"]):
        print(__doc__)
        sys.exit(0)
    if sys.argv[1:2] == ["--selftest"]:
        sys.exit(selftest())
    sys.exit(run(sys.argv[1:]))
