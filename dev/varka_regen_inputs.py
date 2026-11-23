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
"""Whether a regeneration of the generated files can be skipped (VARKA-626).

    dev/varka_regen_inputs.py [--base REF] GENERATED_FILE...   # exit 0 and say why, or exit 1
    dev/varka_regen_inputs.py --selftest

The generated files of master are the regeneration of master's inputs, so a branch whose changes,
against its merge base with `origin/master`, are all to paths known to leave the regeneration
alone, and to no generated file, and which runs on the JDK the committed width audit was written
on, would regenerate what it already holds: `dev/varka_regen_generated.sh --all` skips the sbt
steps then (`--full` runs them anyway). The list is of what is known irrelevant, not of what is
read: the suites compile against several modules (sql/api, core, common), so a path that is on
no list may be an input. Irrelevant are documents, plans, results and benchmark files, the core
module's tests but the cast suites and the helpers that write their record, the workflows, and
the other dev scripts. Exit 1 prints the first reason it could not skip: a changed generated
file, a path not known to be irrelevant, or what it could not read.
"""

import json
import os
import re
import subprocess
import sys

BASE_REF = "origin/master"

# The paths a diff may name without the regeneration being owed. A generated file is caught before
# this list, so `docs/sql-varka.md` and the plans' generated tables are not exempt by it.
SAFE = [
    r"^docs/",
    r"^[^/]*\.md$",
    r"^sql/varka/[^/]+\.md$",
    r"^sql/varka/(plans|skills|bench)/",
    r"^sql/(core|catalyst)/benchmarks/",
    # The core module's tests, but for the cast suites and the helpers that write casts_suites.tsv.
    r"^sql/core/src/test/(?!.*/Varka(Suite(Check|Record|Arm)|ExpressionHarness"
    r"|CastWithAnsiO(ff|n)Suite|TryCastSuite)\.)",
    r"^\.github/",
    r"(^|/)[^/]*Benchmark\.(java|scala)$",
    r"^dev/(?!varka_(regen_generated\.sh|regen_inputs\.py|census\.py|generated_merge\.py)$)",
]
SAFE_RE = re.compile("|".join(f"(?:{p})" for p in SAFE))


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout


def changed_paths(base):
    """What the branch changed against `base`, committed or not, and what is untracked."""
    names = set()
    for out in (
        git("diff", "--name-only", base, "HEAD"),
        git("diff", "--name-only", "HEAD"),
        git("ls-files", "--others", "--exclude-standard"),
    ):
        names.update(line for line in out.split("\n") if line)
    return sorted(names)


def first_reason(changed, generated):
    """The first changed path that forbids a skip, as a reason; None when none does."""
    for path in changed:
        if path in generated:
            return f"the generated file {path} changed"
    for path in changed:
        if not SAFE_RE.search(path):
            return f"{path} is not known to leave the regeneration alone"
    return None


def running_jdk():
    """java.runtime.version of the JVM sbt would start: JAVA_HOME's, else the first on PATH."""
    home = os.environ.get("JAVA_HOME")
    java = os.path.join(home, "bin", "java") if home else "java"
    out = subprocess.run(
        [java, "-XshowSettings:properties", "-version"], capture_output=True, text=True
    ).stderr
    m = re.search(r"java\.runtime\.version = (\S+)", out)
    return m.group(1) if m else None


def committed_jdk():
    with open("sql/varka/width_audit.json", encoding="utf-8") as f:
        return json.load(f)["host"]["jdk"]


def decide(base, generated, jdk=running_jdk, committed=committed_jdk, changed=changed_paths):
    """(skip, reason): whether the regeneration can be skipped and why or why not."""
    try:
        base_sha = git("merge-base", "HEAD", base).strip()
    except subprocess.CalledProcessError:
        return False, f"no merge base with {base}"
    reason = first_reason(changed(base_sha), set(generated))
    if reason:
        return False, reason
    try:
        have, wrote = jdk(), committed()
    except (OSError, KeyError, ValueError) as e:
        return False, f"could not read the JDK or the committed audit's: {e}"
    if have != wrote:
        return False, f"the JDK is {have} and the committed width audit was written on {wrote}"
    return True, f"nothing the regeneration reads changed since {base_sha[:11]}, on JDK {have}"


def selftest():
    gen = {"sql/varka/width_audit.json", "docs/sql-varka.md", "sql/varka/plans/m8/census.tsv"}
    unknown = "is not known to leave the regeneration alone"
    cases = [
        (["sql/core/benchmarks/X-results.txt"], None),
        (["sql/varka/plans/m8/PLAN.md", "sql/varka/plans/m8/results/R.txt"], None),
        (["sql/core/src/test/java/org/apache/spark/sql/VarkaFooBenchmark.java"], None),
        (["sql/catalyst/src/test/java/org/apache/spark/sql/VarkaChainDepthBenchmark.java"], None),
        (["README.md", "docs/other.md", "sql/varka/VISION.md", "sql/varka/AGENTS.md"], None),
        (["sql/core/src/test/scala/x/VarkaFooSuite.scala", ".github/workflows/x.yml"], None),
        (["dev/varka_other.py"], None),
        (
            ["sql/core/src/test/java/x/VarkaSuiteRecord.java"],
            "sql/core/src/test/java/x/VarkaSuiteRecord.java " + unknown,
        ),
        (
            ["sql/catalyst/src/main/java/x/VarkaLoopEmitter.java"],
            "sql/catalyst/src/main/java/x/VarkaLoopEmitter.java " + unknown,
        ),
        (
            ["sql/api/src/main/scala/x/DataType.scala"],
            "sql/api/src/main/scala/x/DataType.scala " + unknown,
        ),
        (
            ["sql/catalyst/src/test/java/x/VarkaDecimalStoreBenchmarkSuite.java"],
            "sql/catalyst/src/test/java/x/VarkaDecimalStoreBenchmarkSuite.java " + unknown,
        ),
        (
            ["sql/catalyst/src/test/scala/x/VarkaWidthAuditSuite.scala"],
            "sql/catalyst/src/test/scala/x/VarkaWidthAuditSuite.scala " + unknown,
        ),
        (["sql/varka/coverage.json"], "sql/varka/coverage.json " + unknown),
        (["sql/varka/matrix/skips.tsv"], "sql/varka/matrix/skips.tsv " + unknown),
        (["sql/varka/width_audit.json"], "the generated file sql/varka/width_audit.json changed"),
        (
            ["sql/varka/plans/m8/census.tsv"],
            "the generated file sql/varka/plans/m8/census.tsv changed",
        ),
        (["pom.xml"], "pom.xml " + unknown),
        (["dev/varka_regen_generated.sh"], "dev/varka_regen_generated.sh " + unknown),
    ]
    for paths, want in cases:
        got = first_reason(paths, gen)
        assert got == want, (paths, got, want)
    # a generated file wins over an unknown path, whatever their order
    assert first_reason(["pom.xml", "sql/varka/width_audit.json"], gen).startswith("the generated")
    assert decide("no-such-ref", [])[0] is False
    # decide's two stages, with the changes and the JDKs given: nothing changed on a matching JDK
    # skips, and the same on another JDK, or with an unknown path changed, does not.
    base = git("rev-parse", "HEAD").strip()
    skip = decide(base, [], jdk=lambda: "1", committed=lambda: "1", changed=lambda b: [])
    assert skip[0] is True and "on JDK 1" in skip[1], skip
    other = decide(base, [], jdk=lambda: "1", committed=lambda: "2", changed=lambda b: [])
    assert other == (False, "the JDK is 1 and the committed width audit was written on 2"), other
    path = decide(base, [], jdk=lambda: "1", committed=lambda: "1", changed=lambda b: ["pom.xml"])
    assert path == (False, "pom.xml " + unknown), path
    print("varka_regen_inputs: self-test passed")


if __name__ == "__main__":
    args = sys.argv[1:]
    if args == ["--selftest"]:
        selftest()
        sys.exit(0)
    base = BASE_REF
    if args[:1] == ["--base"]:
        base, args = args[1], args[2:]
    skip, why = decide(base, args)
    print(why)
    sys.exit(0 if skip else 1)
