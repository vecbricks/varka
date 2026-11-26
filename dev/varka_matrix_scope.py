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
The suites a branch adds or changes, which the option matrix's first stage runs under every
configuration before the rest of the matrix (VARKA-637 3.1).

The rule is the local gate's (`dev/varka_ops/pre-pr.sh`, step 5): every `*Suite.scala` or
`*Suite.java` under `sql/catalyst/src/test` or `sql/core/src/test` that the diff from BASE to
HEAD adds or modifies, by its simple name, plus `VarkaMatrixSuite` when `VarkaEmitOption`,
`VarkaEmitOptions` or a `VarkaMatrix*` file changes, since that suite checks the option table.
Each suite is printed once, one a line, sorted. BASE is the merge base with Varka's master.

A `git diff` that fails prints a warning to stderr and no suite, and exits 0: the matrix then
runs no changed suite first, and its second stage runs everything, so a failure here costs only
the early stop, never coverage.

  dev/varka_matrix_scope.py BASE HEAD --suites
  dev/varka_matrix_scope.py --selftest
"""

import argparse
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

# The changed test files that are suites, by their simple names (pre-pr.sh's `suites`).
SUITE = re.compile(r"^sql/(?:catalyst|core)/src/test/.*/([^/]+Suite)\.(?:scala|java)$")
# A change to the option table or the matrix's own classes runs the suite that checks them.
OPTIONS = re.compile(r"codegen/varka/VarkaEmitOptions?\.java$|codegen/varka/VarkaMatrix")
OPTIONS_SUITE = "VarkaMatrixSuite"


def changed_paths(base, head, cwd=None):
    """The paths the diff adds or modifies, or None when `git diff` fails. Renames count as an
    add and a delete, as in pre-pr.sh."""
    got = subprocess.run(
        ["git", "diff", "--name-only", "--no-renames", "--diff-filter=AM", base, head],
        cwd=cwd,
        capture_output=True,
        text=True,
    )
    if got.returncode != 0:
        print(
            f"varka_matrix_scope: git diff {base} {head} failed, so no suite is listed:"
            f" {got.stderr.strip()}",
            file=sys.stderr,
        )
        return None
    return [p for p in got.stdout.splitlines() if p]


def suites(paths):
    """The changed suites of `paths`, each once, sorted."""
    out = set()
    for path in paths:
        m = SUITE.match(path)
        if m:
            out.add(m.group(1))
        if OPTIONS.search(path):
            out.add(OPTIONS_SUITE)
    return sorted(out)


class SelfTest(unittest.TestCase):
    def git(self, *args):
        subprocess.run(["git", *args], cwd=self.repo, check=True, capture_output=True)

    def commit(self, files):
        for path, text in files.items():
            p = Path(self.repo, path)
            if text is None:
                p.unlink()
                continue
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text, encoding="utf-8")
        self.git("add", "-A")
        self.git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "c")
        return subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=self.repo, check=True, capture_output=True, text=True
        ).stdout.strip()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = self.tmp.name
        self.git("init", "-q")
        self.cat = "sql/catalyst/src/test/scala/org/x/codegen/varka/"
        self.core = "sql/core/src/test/java/org/x/"
        self.main = "sql/catalyst/src/main/java/org/x/codegen/varka/"
        self.base = self.commit(
            {
                self.cat + "OldSuite.scala": "a",
                self.cat + "GoneSuite.scala": "a",
                self.cat + "VarkaMatrixSuite.scala": "a",
                self.main + "VarkaEmitOption.java": "a",
                "README.md": "a",
            }
        )

    def tearDown(self):
        self.tmp.cleanup()

    def scope(self, head):
        paths = changed_paths(self.base, head, cwd=self.repo)
        return None if paths is None else suites(paths)

    def test_a_planted_diff_lists_its_added_and_changed_suites_once(self):
        head = self.commit(
            {
                self.cat + "OldSuite.scala": "b",
                self.cat + "GoneSuite.scala": None,
                self.core + "NewSuite.java": "a",
                # A second suite of the same name in the other module is still one suite.
                "sql/core/src/test/scala/org/y/OldSuite.scala": "a",
                self.cat + "VarkaHelper.scala": "a",
                "sql/core/src/main/scala/org/x/MainSuite.scala": "a",
                "core/src/test/scala/org/x/SparkSuite.scala": "a",
                "README.md": "b",
            }
        )
        self.assertEqual(self.scope(head), ["NewSuite", "OldSuite"])

    def test_an_option_change_adds_the_matrix_suite_once(self):
        head = self.commit(
            {self.main + "VarkaEmitOption.java": "b", self.cat + "VarkaMatrixSuite.scala": "b"}
        )
        self.assertEqual(self.scope(head), [OPTIONS_SUITE])
        later = self.commit({self.cat + "VarkaMatrixRunsHelper.java": "a"})
        self.assertEqual(self.scope(later), [OPTIONS_SUITE])

    def test_a_failed_diff_lists_no_suite(self):
        self.assertIsNone(changed_paths("0" * 40, "HEAD", cwd=self.repo))
        self.assertEqual(main(["0" * 40, "HEAD", "--suites"], cwd=self.repo), 0)

    def test_no_change_lists_nothing(self):
        self.assertEqual(self.scope(self.base), [])


def main(argv, cwd=None):
    if argv[:1] == ["--selftest"]:
        result = unittest.main(argv=[sys.argv[0]], exit=False, verbosity=0).result
        ok = result.wasSuccessful()
        print("varka_matrix_scope selftest: " + ("ok" if ok else "FAILED"))
        return 0 if ok else 1
    parser = argparse.ArgumentParser()
    parser.add_argument("base")
    parser.add_argument("head")
    parser.add_argument(
        "--suites", action="store_true", required=True, help="print the changed suites"
    )
    args = parser.parse_args(argv)
    paths = changed_paths(args.base, args.head, cwd=cwd)
    for name in suites(paths or []):
        print(name)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
