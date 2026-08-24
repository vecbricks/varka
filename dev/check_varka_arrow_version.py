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
Checks that the Varka engine builds against the same Arrow as Spark.

    $ dev/check_varka_arrow_version.py
    arrow.version matches: 19.0.0 (pom.xml and sql/varka/engine/pom.xml)

The engine is a reactor module but keeps its own pom with no parent - its sources need
--add-modules jdk.incubator.vector and its tests need native-access flags the Spark build
does not set - so it has no `arrow.version` to inherit and must repeat the value. A mismatch
is not benign: the kernels write into Arrow buffers that Spark allocated, so the two sides
have to agree on the memory layout and on the ArrowBuf API.

Exits 0 when the versions match, 1 when they diverge or either cannot be read. Run from
anywhere inside the repository.
"""

import re
import subprocess
import sys
from pathlib import Path

PROPERTY = re.compile(r"<arrow\.version>([^<]+)</arrow\.version>")

ROOT_POM = "pom.xml"
ENGINE_POM = "sql/varka/engine/pom.xml"


def repo_root() -> Path:
    try:
        top = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        sys.exit("error: not inside a git repository (or git is unavailable)")
    return Path(top)


def arrow_version(pom: Path) -> str:
    try:
        text = pom.read_text(encoding="utf-8")
    except OSError as e:
        sys.exit(f"error: cannot read {pom}: {e}")
    found = PROPERTY.findall(text)
    if not found:
        sys.exit(f"error: no <arrow.version> property in {pom}")
    if len(set(found)) > 1:
        sys.exit(f"error: conflicting <arrow.version> values in {pom}: {sorted(set(found))}")
    return found[0]


def main() -> int:
    root = repo_root()
    spark = arrow_version(root / ROOT_POM)
    engine = arrow_version(root / ENGINE_POM)

    if spark == engine:
        print(f"arrow.version matches: {spark} ({ROOT_POM} and {ENGINE_POM})")
        return 0

    print(
        "error: arrow.version differs between Spark and the Varka engine\n"
        f"  {ROOT_POM}:   {spark}\n"
        f"  {ENGINE_POM}: {engine}\n"
        "\n"
        "The engine writes into Arrow buffers that Spark allocates, so both must build\n"
        f"against the same Arrow. Update <arrow.version> in {ENGINE_POM} to {spark}.",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
