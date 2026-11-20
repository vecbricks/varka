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
"""List, rerun and base-check the Varka tests a pull request adds (VARKA-255.md 3.4).

    dev/varka_new_tests.py list catalyst|sql [--base REV]
    dev/varka_new_tests.py rerun catalyst|sql [--base REV] [--times 5] [--build-dir DIR]
    dev/varka_new_tests.py fails-on-base catalyst|sql [--base REV] [--build-dir DIR]
    dev/varka_new_tests.py --self-test

The base defaults to VARKA_DIFF_BASE, the merge base the checkout action computes; with no base,
or a base equal to the head (a run of master), there is nothing new and every command passes.

`list` prints the tests the diff from the base adds to the module's Varka suites - the
ScalaTest classes named `Varka...Suite` and the JUnit classes under `@VarkaSuite`, the set
`dev/varka_matrix.sh` runs - as `<suite>\\t<test name>`, one a line. A test is new when its name
appears in a file the diff changes and in no Varka suite of the base, so a renamed test is new
and a test moved between files is not. A name that is not a plain string literal (`s"..."`, a
concatenation with a value, a parameterized display name) cannot be listed; it is reported as
`unlistable` with its file and line, never silently dropped, and nothing reruns it.

`rerun` runs the module's new tests again, `--times` rounds of one JVM each, on the build the
job's suites step left in `--build-dir` (its classpath and JVM options), with the memory
sanitizer and both-bodies check on as the suites run them, and each round after the first under
another fuzz seed (`-Dvarka.fuzz.seed`), which is what a rerun is for in a seeded fuzz or sweep.
It fails on any failure, naming the test and the round. A listed test that ran in no round is a
notice: the listing could not match the runner's name for it.

`fails-on-base` runs only for a pull request labelled `fix` on vecbricks/varka (read with
GH_TOKEN; the push builds of the fork do not know their pull request). It builds, in a scratch
worktree, the base's main sources with the pull request's test sources over them - every changed
path outside `src/test/` checked out from the base, every such path the pull request adds
deleted - and runs the new tests there once. It passes when each fails, and fails naming each
that passes: a fix whose test passes before the fix has not shown the bug. When the overlay does
not compile, which a test helper using an API the fix adds causes, the step says "not shown:
the tests need the fix's main code" as a notice and passes.
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

ROOTS = {
    "catalyst": "sql/catalyst/src/test",
    "sql": "sql/core/src/test",
}
SCALA_SUITE = re.compile(r"^class (Varka[A-Za-z0-9]*Suite)\b", re.M)
JAVA_SUITE = re.compile(r"^(?:public )?(?:final )?class (Varka[A-Za-z0-9]*Suite)\b", re.M)
VARKA_SUITE_MARKER = re.compile(r"^@VarkaSuite\b", re.M)
# Suites that measure the JIT rather than answers run alone and unsanitized
# (dev/varka_scoped_suites.sh); their new tests are listed but not rerun here.
TIMING_SUITES = ("VarkaAssemblySuite", "VarkaTierWitnessSuite", "VarkaWarmupEndToEndSuite")
RUNNER = "org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteRunner"
BASE_REPO = "vecbricks/varka"
FIX_LABEL = "fix"
SUMMARY = os.environ.get("GITHUB_STEP_SUMMARY") or os.devnull


# Reading test names out of a source file.

STRING = re.compile(r'"((?:[^"\\\n]|\\.)*)"')


def literal_chain(text, pos):
    """The string a chain of literals joined by `+` spells from `pos`, or None if any part of
    the argument is not a plain literal. Stops at the `,` or `)` that ends the argument.

    >>> literal_chain('"a b " +\\n   "c")', 0)
    'a b c'
    >>> literal_chain('"x"\\n      + "y", Tag)', 0)
    'xy'
    >>> literal_chain('s"size $n")', 0) is None
    True
    >>> literal_chain('"n = " + n)', 0) is None
    True
    """
    parts = []
    i = pos
    while True:
        while i < len(text) and text[i] in " \t\r\n":
            i += 1
        m = STRING.match(text, i)
        if not m:
            return None
        parts.append(m.group(1))
        i = m.end()
        while i < len(text) and text[i] in " \t\r\n":
            i += 1
        if i < len(text) and text[i] == "+":
            i += 1
            continue
        if i < len(text) and text[i] in ",)":
            break
        return None
    return unescape("".join(parts))


def unescape(s):
    return re.sub(r'\\(["\\nt])', lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), s)


SCALA_TEST = re.compile(r"(?<![\w.])test\(\s*")
JAVA_NAME = re.compile(r"@DisplayName\(\s*")
JAVA_UNLISTABLE = re.compile(r"^\s*@(?:ParameterizedTest|RepeatedTest|TestFactory)\b", re.M)


def names_a_template(text, pos):
    """Whether the @DisplayName at `pos` sits on a @ParameterizedTest, a @RepeatedTest or a
    @TestFactory, whose own display name is no test's: its tests are keyed by their generated
    names (VARKA-477). The lines read are those from the blank line before it to the next."""
    start = text.rfind("\n\n", 0, pos)
    end = text.find("\n\n", pos)
    return bool(JAVA_UNLISTABLE.search(text, max(start, 0), end if end >= 0 else len(text)))


def tests_in(path, text):
    """([(suite, name)], [(line, raw)]) of one Varka suite file: its named tests, and the tests
    whose names cannot be read as a literal. Not a Varka suite file: ([], [])."""
    if path.endswith(".scala"):
        suites, call = SCALA_SUITE, SCALA_TEST
    elif path.endswith(".java"):
        if not VARKA_SUITE_MARKER.search(text):
            return [], []
        suites, call = JAVA_SUITE, JAVA_NAME
    else:
        return [], []
    declared = [(m.start(), m.group(1)) for m in suites.finditer(text)]
    if not declared:
        return [], []
    package = re.search(r"^package ([\w.]+)", text, re.M)
    prefix = package.group(1) + "." if package else ""
    named, unlistable = [], []

    def suite_at(pos):
        owner = [name for start, name in declared if start < pos]
        return prefix + (owner[-1] if owner else declared[0][1])

    for m in call.finditer(text):
        line = text.count("\n", 0, m.start()) + 1
        # A class-level @DisplayName names the class, not a test.
        if call is JAVA_NAME and re.match(r"[^\n]*\n\s*(?:public |final )*class ", text[m.end() :]):
            continue
        # A template's or a factory's name is not a test's; the template is reported below.
        if call is JAVA_NAME and names_a_template(text, m.start()):
            continue
        name = literal_chain(text, m.end())
        if name is None:
            raw = text[m.end() : text.find("\n", m.end())].strip()
            unlistable.append((line, raw))
        else:
            named.append((suite_at(m.start()), name))
    if path.endswith(".java"):
        for m in JAVA_UNLISTABLE.finditer(text):
            line = text.count("\n", 0, m.start()) + 1
            unlistable.append((line, m.group(0).strip() + ": its tests' names are generated"))
    return named, unlistable


def new_tests(base_files, head_files, changed):
    """(new [(suite, name)], unlistable [(path, line, raw)]) from {path: text} at the base and
    the head and the head paths the diff changed."""
    old_names = set()
    old_raw = set()
    for path, text in base_files.items():
        named, bad = tests_in(path, text)
        old_names.update(name for _, name in named)
        old_raw.update(raw for _, raw in bad)
    found, unlistable, seen = [], [], set()
    for path in sorted(changed):
        if path not in head_files:
            continue
        named, bad = tests_in(path, head_files[path])
        for suite, name in named:
            if name not in old_names and (suite, name) not in seen:
                seen.add((suite, name))
                found.append((suite, name))
        for line, raw in bad:
            if raw not in old_raw:
                unlistable.append((path, line, raw))
    return found, unlistable


# Git.


def git(*args, check=True):
    return subprocess.run(["git", *args], check=check, capture_output=True, text=True).stdout


def suite_files(rev, root):
    """The files under `root` at `rev` named for a Varka class, where its suites live."""
    out = git("ls-tree", "-r", "--name-only", rev, "--", root)
    return [p for p in out.splitlines() if re.search(r"/Varka[A-Za-z0-9]*\.(scala|java)$", p)]


def read_at(rev, paths):
    texts = {}
    for path in paths:
        result = subprocess.run(
            ["git", "show", "%s:%s" % (rev, path)], capture_output=True, text=True
        )
        if result.returncode == 0:
            texts[path] = result.stdout
    return texts


def listing(module, base, head="HEAD"):
    root = ROOTS[module]
    changed = [
        p
        for p in git(
            "diff", "--name-only", "--diff-filter=AMR", base, head, "--", root
        ).splitlines()
        if p.endswith((".scala", ".java"))
    ]
    # The base's Varka-named files and its versions of every changed file, so that a test moved
    # out of a file of another name is still found on the base.
    base_files = read_at(base, sorted(set(suite_files(base, root)) | set(changed)))
    head_files = read_at(head, changed)
    return new_tests(base_files, head_files, changed)


def resolve_base(arg):
    base = arg or os.environ.get("VARKA_DIFF_BASE", "")
    if not base:
        return None, "no diff base"
    head = git("rev-parse", "HEAD").strip()
    commit = git("rev-parse", "--verify", "--quiet", base + "^{commit}", check=False).strip()
    if not commit:
        return None, "the diff base %s is not in this checkout" % base
    if commit == head:
        return None, "the diff base is the head: no change to measure"
    return commit, ""


# Running tests through the matrix runner on an exported build.


def read_text(path):
    with open(path) as f:
        return f.read()


def write_text(path, text):
    with open(path, "w") as f:
        f.write(text)


def log_tail(path, lines=30):
    """The last lines of a log, for a step's output: the scratch directory is gone with the job."""
    try:
        return "\n".join(read_text(path).splitlines()[-lines:])
    except OSError:
        return "(no log at %s)" % path


def run_tests(build_dir, tests, workdir, extra_jvm=()):
    """({(suite, name): 'passed' | 'failed' | 'skipped'} for the tests that ran, the runner's
    exit status), from one JVM. The runner exits non-zero on any failure or aborted suite."""
    cp = read_text(os.path.join(build_dir, "classpath")).strip()
    opts = [o for o in read_text(os.path.join(build_dir, "jvm.opts")).splitlines() if o]
    os.makedirs(os.path.join(workdir, "tmp"), exist_ok=True)
    args = ["-oW", "-u", os.path.join(workdir, "junit")]
    # One -s a suite with its tests' -t after it: two -s of one suite would run it twice, and
    # the second run's report would replace the first's.
    by_suite = {}
    for suite, name in tests:
        by_suite.setdefault(suite, []).append(name)
    for suite, names in by_suite.items():
        args += ["-s", suite]
        for name in names:
            args += ["-t", name]
    env = dict(os.environ, SPARK_TESTING="1", SPARK_SCALA_VERSION="2.13")
    cmd = [
        "java",
        *opts,
        "-Dvarka.sanitizeMemory=true",
        "-Dvarka.checkBothBodies=true",
        *extra_jvm,
        "-Djava.io.tmpdir=" + os.path.join(workdir, "tmp"),
        "-cp",
        cp,
        RUNNER,
        *args,
    ]
    with open(os.path.join(workdir, "run.log"), "w") as log:
        status = subprocess.run(
            cmd, cwd=workdir, stdout=log, stderr=subprocess.STDOUT, env=env
        ).returncode
    return junit_results(os.path.join(workdir, "junit")), status


def junit_results(directory):
    results = {}
    if not os.path.isdir(directory):
        return results
    for entry in sorted(os.listdir(directory)):
        if not entry.endswith(".xml"):
            continue
        try:
            root = ET.parse(os.path.join(directory, entry)).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            key = (case.get("classname", ""), case.get("name", ""))
            if case.find("failure") is not None or case.find("error") is not None:
                results[key] = "failed"
            elif case.find("skipped") is not None:
                results.setdefault(key, "skipped")
            else:
                results.setdefault(key, "passed")
    return results


def outcome(results, suite, name):
    """A listed test's result, matched by name within its suite (the reports' class names are
    the suite's, sometimes without the package)."""
    simple = suite.rsplit(".", 1)[-1]
    for (cls, case), result in results.items():
        if case == name and (cls == suite or cls.rsplit(".", 1)[-1] == simple):
            return result
    return None


def say(*lines):
    with open(SUMMARY, "a") as summary:
        for line in lines:
            print(line)
            summary.write(line + "\n")


# Commands.


def cmd_list(args):
    base, why = resolve_base(args.base)
    if base is None:
        print("no new tests: " + why)
        return 0
    found, unlistable = listing(args.module, base)
    for suite, name in found:
        print("%s\t%s" % (suite, name))
    for path, line, raw in unlistable:
        print("unlistable\t%s:%d\t%s" % (path, line, raw))
    return 0


def rerunnable(found):
    return [(s, n) for s, n in found if s.rsplit(".", 1)[-1] not in TIMING_SUITES]


def in_share(found, build_dir):
    """The new tests of the suites this CI job ran, where the job is one of several that split
    the module (`job-suites` in the build directory, dev/varka_scoped_suites.sh --shard); every
    new test when the job ran them all.

    >>> d = tempfile.mkdtemp()
    >>> in_share([("p.A", "x"), ("p.B", "y")], d)
    [('p.A', 'x'), ('p.B', 'y')]
    >>> _ = open(os.path.join(d, "job-suites"), "w").write("B\\n")
    >>> in_share([("p.A", "x"), ("p.B", "y")], d)
    [('p.B', 'y')]
    """
    path = os.path.join(build_dir, "job-suites")
    if not os.path.exists(path):
        return found
    with open(path, encoding="utf-8") as f:
        mine = {line.strip() for line in f if line.strip()}
    return [(s, n) for s, n in found if s.rsplit(".", 1)[-1] in mine]


def report_unlistable(unlistable):
    for path, line, raw in unlistable:
        say(
            "::notice file=%s,line=%d::a new test whose name is not a literal cannot be listed "
            "or rerun: %s" % (path, line, raw)
        )


def cmd_rerun(args):
    base, why = resolve_base(args.base)
    if base is None:
        say("new tests of %s: none, %s" % (args.module, why))
        return 0
    found, unlistable = listing(args.module, base)
    report_unlistable(unlistable)
    found = in_share(found, args.build_dir)
    tests = rerunnable(found)
    for suite, name in sorted(set(found) - set(tests)):
        say("::notice::%s: %s is in a timing suite, which runs alone; not rerun" % (suite, name))
    if not tests:
        say("new tests of %s: none to rerun" % args.module)
        return 0
    if not os.path.exists(os.path.join(args.build_dir, "classpath")):
        say("no build in %s to rerun the new tests on; the suites step makes it" % args.build_dir)
        return 1
    say("== %d new test(s) of %s, %d rounds" % (len(tests), args.module, args.times))
    failed, aborted, ran = [], [], set()
    scratch = tempfile.mkdtemp(prefix="varka-new-tests-")
    for k in range(args.times):
        extra = () if k == 0 else ("-Dvarka.fuzz.seed=%d" % (0x5EED + k),)
        workdir = os.path.join(scratch, "round-%d" % (k + 1))
        results, status = run_tests(args.build_dir, tests, workdir, extra)
        round_failed = False
        for suite, name in tests:
            result = outcome(results, suite, name)
            if result is not None:
                ran.add((suite, name))
            if result == "failed":
                failed.append((k + 1, suite, name))
                round_failed = True
        # A non-zero exit with no failed test in the reports is a suite that aborted (a
        # fixture that threw) or a JVM that died: no report names it, and it is still a failure.
        if status != 0 and not round_failed:
            aborted.append((k + 1, status, os.path.join(workdir, "run.log")))
    for suite, name in tests:
        if (suite, name) not in ran:
            say(
                "::notice::%s: %s ran in no round; the runner did not match its name"
                % (suite, name)
            )
    for k, status, log in aborted:
        say(
            "round %d of %d: the runner exited %d with no new test failed in its reports, so a "
            "suite aborted or the JVM died" % (k, args.times, status)
        )
        print(log_tail(log))
    if failed:
        for k, suite, name in failed:
            say("failed in round %d of %d: %s: %s" % (k, args.times, suite, name))
        first = failed[0][0]
        print(log_tail(os.path.join(scratch, "round-%d" % first, "run.log")))
    if failed or aborted:
        return 1
    say("passed: every new test of %s, %d times" % (args.module, args.times))
    return 0


def labelled_fix(branch, owner):
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    head = urllib.parse.quote("%s:%s" % (owner, branch), safe=":")
    url = "https://api.github.com/repos/%s/pulls?state=open&head=%s" % (BASE_REPO, head)
    req = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json"})
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=60) as resp:
        pulls = json.loads(resp.read().decode())
    for pr in pulls:
        if any(label.get("name") == FIX_LABEL for label in pr.get("labels", [])):
            return pr.get("number")
    return None


def overlay_paths(base):
    """(restore, delete): the changed paths outside src/test the base has, and those it lacks."""
    restore, delete = [], []
    for line in git("diff", "--name-status", "--no-renames", base, "HEAD").splitlines():
        status, path = line.split("\t", 1)
        if "/src/test/" in "/" + path:
            continue
        (delete if status.startswith("A") else restore).append(path)
    return restore, delete


def build(tree, module, out):
    """Build the module's tests in `tree` and export its classpath and JVM options to `out`, as
    dev/varka_matrix.sh does; False when the build fails."""
    project = "catalyst" if module == "catalyst" else "sql"
    os.makedirs(out, exist_ok=True)
    log_path = os.path.join(out, "build.log")
    with open(log_path, "w") as log:
        status = subprocess.run(
            [
                "build/sbt",
                "-batch",
                "-Phive",
                project + "/Test/compile",
                "export %s/Test/fullClasspath" % project,
                "show %s/Test/javaOptions" % project,
            ],
            cwd=tree,
            stdout=log,
            stderr=subprocess.STDOUT,
        ).returncode
    if status != 0:
        return False
    plain = re.sub(r"\x1b\[[0-9;]*[A-Za-z]|\r", "", read_text(log_path)).splitlines()
    cp = [line for line in plain if re.match(r"^/[^ ]+:/", line)]
    opts = [line[len("[info] * ") :] for line in plain if line.startswith("[info] * ")]
    opts = [o for o in opts if not o.startswith("-Djava.io.tmpdir=")]
    if not cp or "scalatest" not in cp[-1]:
        return False
    write_text(os.path.join(out, "classpath"), cp[-1] + "\n")
    write_text(os.path.join(out, "jvm.opts"), "\n".join(opts) + "\n")
    return True


def cmd_fails_on_base(args):
    base, why = resolve_base(args.base)
    if base is None:
        print("fails-on-base: nothing to check, " + why)
        return 0
    branch = os.environ.get("GITHUB_HEAD_REF") or os.environ.get("GITHUB_REF_NAME") or ""
    owner = (os.environ.get("GITHUB_REPOSITORY") or "/").split("/")[0]
    if not args.force:
        try:
            pr = labelled_fix(branch, owner)
        except (OSError, ValueError) as e:
            say("::warning::could not read the pull request's labels (%s); not checked" % e)
            return 0
        if pr is None:
            print("fails-on-base: not a pull request labelled %s" % FIX_LABEL)
            return 0
        say("== #%s is labelled %s: its new tests must fail on the merge base" % (pr, FIX_LABEL))
    found, unlistable = listing(args.module, base)
    report_unlistable(unlistable)
    tests = rerunnable(in_share(found, args.build_dir))
    if not tests:
        say("fails-on-base: %s has no new test to run on the base" % args.module)
        return 0
    restore, delete = overlay_paths(base)
    scratch = tempfile.mkdtemp(prefix="varka-fix-")
    tree = os.path.join(scratch, "overlay")
    git("worktree", "add", "--detach", tree, "HEAD")
    try:
        if restore:
            subprocess.run(
                ["git", "checkout", base, "--", *restore], cwd=tree, check=True, capture_output=True
            )
        for path in delete:
            target = os.path.join(tree, path)
            if os.path.isdir(target):
                shutil.rmtree(target)
            elif os.path.exists(target):
                os.remove(target)
        say(
            "overlay: the base's %d changed main path(s), %d added by the pull request removed"
            % (len(restore), len(delete))
        )
        build_dir = os.path.join(scratch, "build")
        if not build(tree, args.module, build_dir):
            say(
                "::notice::not shown: the tests need the fix's main code (the overlay of the "
                "base's main sources does not compile; %s)" % os.path.join(build_dir, "build.log")
            )
            return 0
        # The runner's status is not read: the tests are meant to fail here.
        results, _ = run_tests(build_dir, tests, os.path.join(scratch, "run"))
    finally:
        git("worktree", "remove", "--force", tree, check=False)
    passed, unshown = [], []
    for suite, name in tests:
        result = outcome(results, suite, name)
        if result == "passed":
            passed.append((suite, name))
        elif result != "failed":
            unshown.append((suite, name))
    for suite, name in unshown:
        say("::notice::not shown: %s: %s did not run on the base" % (suite, name))
    if passed:
        for suite, name in passed:
            say("passes on the merge base, so it does not show the bug: %s: %s" % (suite, name))
        return 1
    say("shown: every new test of %s fails on the merge base %s" % (args.module, base[:11]))
    return 0


def main(argv):
    if argv[:1] == ["--self-test"]:
        import doctest

        failed, _ = doctest.testmod()
        result = unittest.main(argv=[sys.argv[0]], exit=False, verbosity=2).result
        return 1 if failed or not result.wasSuccessful() else 0
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("command", choices=["list", "rerun", "fails-on-base"])
    p.add_argument("module", choices=sorted(ROOTS))
    p.add_argument("--base")
    p.add_argument("--times", type=int, default=5)
    p.add_argument("--build-dir", default="target/varka-scoped")
    p.add_argument("--force", action="store_true", help="fails-on-base without the label")
    args = p.parse_args(argv)
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    args.build_dir = os.path.abspath(args.build_dir)
    return {"list": cmd_list, "rerun": cmd_rerun, "fails-on-base": cmd_fails_on_base}[args.command](
        args
    )


# The tests (VARKA-255.md 5, test 6), over recorded diffs.

SCALA_BASE = """package org.apache.spark.sql.catalyst.expressions.codegen.varka

class VarkaLaneSuite extends SparkFunSuite {
  test("the lane of an int tree is INT") {
  }

  test("a long tree's lane is " +
    "LONG") {
  }
}
"""

JAVA_BASE = """package org.apache.spark.sql.catalyst.expressions.codegen.varka;

@VarkaSuite
@DisplayName("the class's own name")
class VarkaPlatformSuite {

  @Test
  @DisplayName("a tier is named by its label")
  void tiers() {}
}
"""

PKG = "org.apache.spark.sql.catalyst.expressions.codegen.varka."
SCALA = "sql/catalyst/src/test/scala/x/VarkaLaneSuite.scala"
OTHER = "sql/catalyst/src/test/scala/x/VarkaOtherSuite.scala"
JAVA = "sql/catalyst/src/test/java/x/VarkaPlatformSuite.java"


class NewTestsTest(unittest.TestCase):
    def test_a_new_scalatest_test_is_listed(self):
        head = SCALA_BASE.replace("}\n}\n", '}\n\n  test("a new test") {\n  }\n}\n')
        found, bad = new_tests({SCALA: SCALA_BASE}, {SCALA: head}, [SCALA])
        self.assertEqual(found, [(PKG + "VarkaLaneSuite", "a new test")])
        self.assertEqual(bad, [])

    def test_a_concatenated_name_is_read_whole(self):
        named, _ = tests_in(SCALA, SCALA_BASE)
        self.assertIn((PKG + "VarkaLaneSuite", "a long tree's lane is LONG"), named)

    def test_a_new_junit_display_name_is_listed_and_the_class_name_is_not(self):
        head = JAVA_BASE.replace(
            "void tiers() {}\n",
            'void tiers() {}\n\n  @Test\n  @DisplayName("a new tier test, "\n'
            '      + "on two lines")\n  void more() {}\n',
        )
        found, _ = new_tests({JAVA: JAVA_BASE}, {JAVA: head}, [JAVA])
        self.assertEqual(found, [(PKG + "VarkaPlatformSuite", "a new tier test, on two lines")])

    def test_a_java_file_outside_varka_suite_is_not_read(self):
        plain = JAVA_BASE.replace("@VarkaSuite\n", "")
        self.assertEqual(tests_in(JAVA, plain), ([], []))

    def test_a_renamed_test_counts_as_new(self):
        head = SCALA_BASE.replace("is INT", "is INT at every node")
        found, _ = new_tests({SCALA: SCALA_BASE}, {SCALA: head}, [SCALA])
        self.assertEqual(
            found, [(PKG + "VarkaLaneSuite", "the lane of an int tree is INT at every node")]
        )

    def test_a_test_moved_between_files_is_not_new(self):
        moved = SCALA_BASE.replace("VarkaLaneSuite", "VarkaOtherSuite")
        trimmed = SCALA_BASE.replace('  test("the lane of an int tree is INT") {\n  }\n\n', "")
        found, _ = new_tests({SCALA: SCALA_BASE}, {SCALA: trimmed, OTHER: moved}, [SCALA, OTHER])
        self.assertEqual(found, [])

    def test_an_interpolated_name_is_reported_unlistable(self):
        head = SCALA_BASE.replace(
            "}\n}\n", '}\n\n  for (n <- 1 to 3) test(s"tree to size $n") {\n  }\n}\n'
        )
        found, bad = new_tests({SCALA: SCALA_BASE}, {SCALA: head}, [SCALA])
        self.assertEqual(found, [])
        self.assertEqual([(p, raw) for p, _, raw in bad], [(SCALA, 's"tree to size $n") {')])

    def test_an_unlistable_name_already_on_the_base_is_not_reported(self):
        both = SCALA_BASE.replace("}\n}\n", '}\n\n  test(s"size $n") {\n  }\n}\n')
        self.assertEqual(new_tests({SCALA: both}, {SCALA: both + "\n"}, [SCALA]), ([], []))

    def test_a_parameterized_junit_test_is_unlistable(self):
        head = JAVA_BASE.replace(
            "void tiers() {}\n", "void tiers() {}\n\n  @ParameterizedTest\n  void each(int n) {}\n"
        )
        _, bad = new_tests({JAVA: JAVA_BASE}, {JAVA: head}, [JAVA])
        self.assertEqual(len(bad), 1)

    def test_a_parameterized_tests_display_name_is_not_listed(self):
        head = JAVA_BASE.replace(
            "void tiers() {}\n",
            'void tiers() {}\n\n  @DisplayName("a template")\n  @ValueSource(ints = {1, 2})\n'
            '  @ParameterizedTest(name = "of {0}")\n  void each(int n) {}\n',
        )
        found, bad = new_tests({JAVA: JAVA_BASE}, {JAVA: head}, [JAVA])
        self.assertEqual(found, [])
        self.assertEqual(len(bad), 1)

    def test_junit_reports_are_matched_by_suite_and_name(self):
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "TEST-a.xml"), "w") as f:
                f.write(
                    '<testsuite><testcase classname="%sVarkaLaneSuite" name="ok"/>'
                    '<testcase classname="VarkaLaneSuite" name="bad"><failure/></testcase>'
                    "</testsuite>" % PKG
                )
            results = junit_results(d)
        self.assertEqual(outcome(results, PKG + "VarkaLaneSuite", "ok"), "passed")
        self.assertEqual(outcome(results, PKG + "VarkaLaneSuite", "bad"), "failed")
        self.assertIsNone(outcome(results, PKG + "VarkaLaneSuite", "absent"))

    def test_the_overlay_restores_changed_main_paths_and_drops_added_ones(self):
        here = os.getcwd()
        with tempfile.TemporaryDirectory() as d:
            os.chdir(d)
            try:
                git("init", "-q")
                git("config", "user.email", "t@example.com")
                git("config", "user.name", "t")
                for path in ("m/src/main/A.scala", "m/src/test/T.scala"):
                    os.makedirs(os.path.dirname(path), exist_ok=True)
                    write_text(path, "base\n")
                git("add", "-A")
                git("commit", "-qm", "base")
                base = git("rev-parse", "HEAD").strip()
                write_text("m/src/main/A.scala", "fixed\n")
                write_text("m/src/main/B.scala", "new helper\n")
                write_text("m/src/test/T.scala", "new test\n")
                write_text("m/src/test/U.scala", "another\n")
                git("add", "-A")
                git("commit", "-qm", "fix")
                self.assertEqual(
                    overlay_paths(base), (["m/src/main/A.scala"], ["m/src/main/B.scala"])
                )
            finally:
                os.chdir(here)

    def test_a_round_whose_runner_fails_with_clean_reports_fails(self):
        # A suite whose fixture throws, or a JVM that dies, leaves no report naming the new
        # test; the runner's exit status is then the only sign, and it fails the step.
        import contextlib
        import io

        g = globals()
        names = ("resolve_base", "listing", "run_tests", "SUMMARY")
        saved = {k: g[k] for k in names}
        test = (PKG + "VarkaLaneSuite", "t")
        try:
            g["SUMMARY"] = os.devnull
            g["resolve_base"] = lambda arg: ("b" * 40, "")
            g["listing"] = lambda module, base: ([test], [])
            with tempfile.TemporaryDirectory() as d:
                write_text(os.path.join(d, "classpath"), "cp\n")
                args = argparse.Namespace(base=None, module="catalyst", times=2, build_dir=d)
                with contextlib.redirect_stdout(io.StringIO()):
                    g["run_tests"] = lambda *a: ({}, 1)
                    self.assertEqual(cmd_rerun(args), 1)
                    g["run_tests"] = lambda *a: ({test: "passed"}, 0)
                    self.assertEqual(cmd_rerun(args), 0)
                    g["run_tests"] = lambda *a: ({test: "failed"}, 1)
                    self.assertEqual(cmd_rerun(args), 1)
        finally:
            g.update(saved)

    def test_timing_suites_are_not_rerun(self):
        found = [(PKG + "VarkaAssemblySuite", "a"), (PKG + "VarkaLaneSuite", "b")]
        self.assertEqual(rerunnable(found), [(PKG + "VarkaLaneSuite", "b")])


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
