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
against its merge base with `origin/master`, touch no input of the JVM regeneration steps and no
generated file, and which runs on the JDK the committed width audit was written on, would
regenerate what it already holds: `dev/varka_regen_generated.sh --all` skips the sbt steps then
(`--full` runs them anyway). An input is main source of catalyst or core, a test source of
catalyst but for a benchmark, a data file at the top of `sql/varka` or any file under its `fuzz`
or `matrix` directory, the build files, and the scripts of the regeneration; a plan, a results
file, a benchmark or a document is not. Exit 1 prints the first reason it could not skip, which
is the changed input, the changed generated file, or what it could not read.
"""

import json
import re
import subprocess
import sys

BASE_REF = "origin/master"

# What the JVM steps read, as the paths a diff names. A catalyst test source counts unless it is a
# benchmark: the suites and their probes live there, and a change to one may change a file.
INPUTS = [
    r"^sql/catalyst/src/main/",
    r"^sql/core/src/main/",
    r"^sql/catalyst/src/test/(?!.*Benchmark)",
    r"^sql/varka/[^/]+\.(json|tsv|txt|properties)$",
    r"^sql/varka/(fuzz|matrix)/",
    r"^pom\.xml$",
    r"^sql/(catalyst|core)/pom\.xml$",
    r"^project/",
    r"^build/",
    r"^dev/varka_(regen_generated\.sh|regen_inputs\.py|census\.py|generated_merge\.py)$",
]
INPUT_RE = re.compile("|".join(f"(?:{p})" for p in INPUTS))


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
        if INPUT_RE.match(path):
            return f"the input {path} changed"
    return None


def running_jdk():
    """java.runtime.version of the JVM sbt would start, read from `java`."""
    out = subprocess.run(
        ["java", "-XshowSettings:properties", "-version"], capture_output=True, text=True
    ).stderr
    m = re.search(r"java\.runtime\.version = (\S+)", out)
    return m.group(1) if m else None


def committed_jdk():
    with open("sql/varka/width_audit.json", encoding="utf-8") as f:
        return json.load(f)["host"]["jdk"]


def decide(base, generated, jdk=running_jdk, committed=committed_jdk):
    """(skip, reason): whether the regeneration can be skipped and why or why not."""
    try:
        base_sha = git("merge-base", "HEAD", base).strip()
    except subprocess.CalledProcessError:
        return False, f"no merge base with {base}"
    reason = first_reason(changed_paths(base_sha), set(generated))
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
    gen = {"sql/varka/width_audit.json", "docs/sql-varka.md"}
    cases = [
        (["sql/core/benchmarks/X-results.txt"], None),
        (["sql/varka/plans/m8/PLAN.md", "sql/varka/plans/m8/results/R.txt"], None),
        (["sql/core/src/test/java/org/apache/spark/sql/VarkaFooBenchmark.java"], None),
        (["sql/catalyst/src/test/java/org/apache/spark/sql/VarkaChainDepthBenchmark.java"], None),
        (["README.md", "docs/other.md", "sql/varka/VISION.md", "sql/varka/AGENTS.md"], None),
        (
            ["sql/catalyst/src/main/java/x/VarkaLoopEmitter.java"],
            "the input sql/catalyst/src/main/java/x/VarkaLoopEmitter.java changed",
        ),
        (
            ["sql/catalyst/src/test/scala/x/VarkaWidthAuditSuite.scala"],
            "the input sql/catalyst/src/test/scala/x/VarkaWidthAuditSuite.scala changed",
        ),
        (["sql/varka/coverage.json"], "the input sql/varka/coverage.json changed"),
        (["sql/varka/matrix/skips.tsv"], "the input sql/varka/matrix/skips.tsv changed"),
        (["sql/varka/width_audit.json"], "the generated file sql/varka/width_audit.json changed"),
        (["pom.xml"], "the input pom.xml changed"),
        (["dev/varka_regen_generated.sh"], "the input dev/varka_regen_generated.sh changed"),
        (["dev/varka_other.py"], None),
    ]
    for paths, want in cases:
        got = first_reason(paths, gen)
        assert got == want, (paths, got, want)
    # a generated file wins over an input, and the first input in path order is named
    assert first_reason(["pom.xml", "sql/varka/width_audit.json"], gen).startswith("the generated")
    assert decide("no-such-ref", [])[0] is False
    ok = decide(BASE_REF, [], jdk=lambda: "1", committed=lambda: "2")
    assert ok[0] is False
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
