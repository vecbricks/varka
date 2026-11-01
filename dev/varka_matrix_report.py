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
"""Summarise a dev/varka_matrix.sh run: one line per configuration, then what failed.

    dev/varka_matrix_report.py target/varka-matrix/runs sql/varka/matrix/skips.tsv

A configuration fails when its runner JVM exits non-zero (a failed test or an aborted suite),
or when a test that fused under the defaults fuses nothing under it and the skip list has no
"declines" line for it - the row path answered alone, so the test passed without testing the
configuration. A "declines" line whose test fuses is stale and fails too. Exit status 1 when
anything failed. The `atdef` column counts the tests a configuration left to the defaults' run
because the Build's record says it changes nothing they compile or emit (VarkaTestReach): they
pass unrun, record no fused count, and are not in `ok`.
"""

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from varka_matrix_bins import reaches

ANSI = re.compile(r"\x1b\[[0-9;]*m")


MODULES = ("catalyst", "sql")

# How much of each line of a failure's message the report keeps. A message can carry a whole
# diagnostic on one line - the warm-up's JIT state and the JIT's account of the kernel, its
# compiles, deoptimizations, inlining and allocation sites, run to some ten thousand characters
# (VARKA-295) - and CI's log of this report may be the only copy of it, so a line is cut only
# where it would bury the rest of the report.
MESSAGE_WIDTH = 12000


def modules_run(runs):
    """The modules the runner was asked for (`--module`), both when it does not say."""
    path = runs / "modules"
    return tuple(path.read_text().split()) if path.exists() else MODULES


def jvm_dirs(run, module):
    """A module's JVM directories in one configuration's run: `module`, or `module-<k>` under
    the runner's `--split`."""
    return sorted(
        d for d in run.glob(f"{module}*") if d.name == module or d.name.startswith(f"{module}-")
    )


def fused(run):
    counts = {}
    for module in MODULES:
        for jvm in jvm_dirs(run, module):
            path = jvm / "fused.tsv"
            if path.exists():
                for line in path.read_text().splitlines():
                    suite, test, n = line.rsplit("\t", 2)
                    counts[(suite.split(".")[-1], test)] = int(n)
    return counts


def read(jvm, name, default=""):
    path = jvm / name
    return path.read_text(errors="replace") if path.exists() else default


def skip_lines(skips_path):
    entries = set()
    if skips_path.exists():
        for line in skips_path.read_text().splitlines():
            if not line.strip() or line.startswith("#"):
                continue
            config, suite, test, kind, _reason = line.split("\t")
            entries.add((kind, config, suite, test))
    return entries


def main(runs, skips_path):
    runs, skips_path = Path(runs), Path(skips_path)
    dirs = sorted(
        (d for d in runs.iterdir() if (d / "name").exists()),
        key=lambda d: (d.name != "defaults", d.name),
    )
    base = fused(runs / "defaults")
    marked = skip_lines(skips_path)
    bad = 0
    print(
        f"{'configuration':32} {'exit':>4} {'secs':>5} {'ok':>5} {'fail':>4} {'canc':>4} "
        f"{'abort':>5} {'unfused':>7} {'atdef':>5}"
    )
    details, notrun = [], []
    for d in dirs:
        name = (d / "name").read_text().strip()
        ok = failed = canceled = aborted = status = seconds = atdef = 0
        log = ""
        for module in modules_run(runs):
            # A module the plan leaves out under this configuration did not run, by design.
            if not reaches(name, module):
                continue
            jvms = jvm_dirs(d, module)
            if not jvms:
                notrun.append(f"  {name}: the {module} suites did not run")
            for jvm in jvms:
                text = ANSI.sub("", read(jvm, "run.log"))
                log += text
                tests = re.findall(r"Tests: succeeded (\d+), failed (\d+), canceled (\d+)", text)
                if tests:
                    ok, failed, canceled = (
                        a + int(b) for a, b in zip((ok, failed, canceled), tests[-1])
                    )
                # The tests the Build's record left to the defaults' run pass unrun, so they are
                # counted apart from the ones that ran (VarkaTestReach).
                # Every JVM prints its own line, an own-JVM suite's child into the parent's log.
                for n in re.findall(r"VARKA_REACH_TESTS\t[^\t]*\tskipped (\d+)", text):
                    atdef += int(n)
                    ok -= int(n)
                if not (jvm / "exit").exists():
                    notrun.append(f"  {name}: {jvm.name} did not finish")
                    continue
                suites = re.findall(r"Suites: completed (\d+), aborted (\d+)", text)
                aborted += int(suites[-1][1]) if suites else 1
                status = max(status, int(read(jvm, "exit", "1")))
                seconds = max(seconds, int(read(jvm, "seconds", "0")))
        unfused, stale = [], []
        if name != "defaults":
            mine = fused(d)
            for key, n in base.items():
                # A test listed as failing under this configuration says nothing by fusing.
                listed = any((kind, name, *key) in marked for kind in ("declines", "fails"))
                if n > 0 and mine.get(key) == 0 and not listed:
                    unfused.append(key)
            for kind, config, suite, test in marked:
                if kind == "declines" and config == name and mine.get((suite, test), 0) > 0:
                    stale.append((suite, test))
        failing = status != 0 or unfused or stale
        bad += bool(failing)
        print(
            f"{name:32} {status:>4} {seconds:>5} {ok:>5} "
            f"{failed:>4} {canceled:>4} {aborted:>5} {len(unfused):>7} {atdef:>5}"
        )
        lines = log.splitlines()
        for i, line in enumerate(lines):
            if "*** FAILED ***" in line or "*** ABORTED ***" in line:
                details.append(f"  {name}: {line.strip()}")
                # ScalaTest prints the message and the cause on the indented lines that follow;
                # without them a failure that CI cannot reproduce says only which test it was.
                for more in lines[i + 1 : i + 7]:
                    if not more.startswith("  ") or not more.strip():
                        break
                    details.append(f"      {more.strip()[:MESSAGE_WIDTH]}")
        details += [
            f"  {name}: fuses nothing, fused under the defaults: {s} / {t}" for s, t in unfused
        ]
        details += [f"  {name}: stale declines line, the test fuses: {s} / {t}" for s, t in stale]
    if details:
        print("\n" + "\n".join(details))
    if notrun:
        print("\n" + "\n".join(notrun))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:3]))
