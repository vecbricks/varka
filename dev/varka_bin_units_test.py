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
Tests the option matrix's bin step (VARKA-505): the shell of `Run this bin's units` in
`.github/workflows/varka-option-matrix.yml`, which reads each unit line, picks the mode of
`dev/varka_matrix.sh` for it and keeps a failing unit from going unrecorded. The step is the one
part of the platform axis that no other check runs before CI does, and a wrong branch there ran
every platform bin as the defaults without a message.

The test takes the step's `run:` text from the workflow, runs it in a scratch directory against a
stub `dev/varka_matrix.sh` that records its arguments, and compares them with what each kind of
unit line must pass.

The same is done for the sweep workflow's `Run the sweep` step (VARKA-573): its JVM options and
runner arguments come from `dev/varka_sweeps.py`, and it must pass a failing sweep's status on.
And for its `Run this share under AVX2 at 128 bits` step (VARKA-564): the three shares of a module
must run every suite once but the JIT-measuring ones, under both flags, and a failing share must
fail its job.

  dev/varka_bin_units_test.py --selftest
"""

import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github/workflows/varka-option-matrix.yml"
STEP = "Run this bin's units"

# Records every argument on one line, tab separated, in the file named by `--out` plus `.args`,
# and exits 3 for a unit whose suites say FAIL.
STUB = r"""#!/bin/bash
out="" suites=""
args=("$@")
for ((i = 0; i < $#; i++)); do
  case "${args[$i]}" in
    --out) out="${args[$((i + 1))]}" ;;
    --suites) suites="${args[$((i + 1))]}" ;;
  esac
done
(IFS=$'\t'; echo "$*") > "$out.args"
case "$suites" in *FAIL*) exit 3 ;; esac
exit 0
"""


def step_script():
    """The `run:` text of the bin step."""
    doc = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
    for step in doc["jobs"]["bins"]["steps"]:
        if step.get("name") == STEP:
            return step["run"]
    raise SystemExit(f"no step named {STEP!r} in {WORKFLOW}")


SWEEPS = ROOT / ".github/workflows/varka-sweeps.yml"

# A stub `java` that records its arguments, one a line, and exits with $STUB_EXIT.
JAVA_STUB = """#!/bin/bash
printf '%s\\n' "$@" > "$STUB_ARGS"
exit "${STUB_EXIT:-0}"
"""


def run_sweep_step(suite, exit_status=0):
    """Runs the sweep workflow's `Run the sweep` step for the first matrix row of `suite`;
    returns (exit status, the arguments the stub `java` got)."""
    doc = yaml.safe_load(SWEEPS.read_text(encoding="utf-8"))
    script = next(
        s["run"] for s in doc["jobs"]["sweep"]["steps"] if s.get("name") == "Run the sweep"
    )
    listed = subprocess.run(
        [sys.executable, str(ROOT / "dev/varka_sweeps.py"), "--matrix", "--all", "--suite", suite],
        capture_output=True,
        text=True,
        check=True,
        cwd=ROOT,
    ).stdout
    entry = json.loads(listed)[0]
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        (tmp / "dev").symlink_to(ROOT / "dev")
        (tmp / "sql").symlink_to(ROOT / "sql")
        build = tmp / "target/varka-sweeps"
        build.mkdir(parents=True)
        (build / "classpath").write_text("/a/b:/c/d\n", encoding="utf-8")
        (build / "jvm.opts").write_text("--add-modules=jdk.incubator.vector\n", encoding="utf-8")
        bin_dir = tmp / "bin"
        bin_dir.mkdir()
        java = bin_dir / "java"
        java.write_text(JAVA_STUB, encoding="utf-8")
        java.chmod(0o755)
        env = {
            "PATH": f"{bin_dir}:{os.environ['PATH']}",
            "ENTRY": json.dumps(entry),
            "STUB_ARGS": str(tmp / "java.args"),
            "STUB_EXIT": str(exit_status),
            "GITHUB_STEP_SUMMARY": str(tmp / "summary.md"),
        }
        script = script.replace("${{ matrix.sweep.label }}", entry["label"]).replace(
            "${{ matrix.runner }}", "ubuntu-latest"
        )
        done = subprocess.run(
            ["bash", "-e", "-c", script],
            cwd=tmp,
            env=env,
            capture_output=True,
            text=True,
            timeout=60,
        )
        args = (tmp / "java.args").read_text(encoding="utf-8").splitlines()
        return done.returncode, args


# The step's environment but the units: its plan and deadline as the workflow sets them.
STEP_ENV = {
    "PATH": os.environ["PATH"],
    "BIN": "b000",
    "PLAN_SECONDS": "480",
    "DEADLINE_FLOOR": "1920",
    "GRACE": "30",
    "PLANT": "",
}


def run_step(units, reach=True, store=True):
    """Runs the step over `units` (one line each); returns (exit status, {unit k: [arguments]})."""
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        (tmp / "dev").mkdir()
        stub = tmp / "dev/varka_matrix.sh"
        stub.write_text(STUB, encoding="utf-8")
        stub.chmod(0o755)
        matrix = tmp / "target/varka-matrix"
        (matrix / "harness-store").mkdir(parents=True)
        if reach:
            (matrix / "reach.tsv").write_text("# covers\n", encoding="utf-8")
        if store:
            (matrix / "harness-store/harness-0.bin").write_bytes(b"x")
        env = dict(STEP_ENV, UNITS="\n".join(units) + "\n")
        done = subprocess.run(
            ["bash", "-e", "-c", step_script()],
            cwd=tmp,
            env=env,
            capture_output=True,
            text=True,
            timeout=60,
        )
        args = {}
        for path in sorted((tmp / "target/varka-units/b000").glob("b000-u*.args")):
            k = int(path.name.split("-u")[1].split(".")[0])
            args[k] = path.read_text(encoding="utf-8").rstrip("\n").split("\t")
        return done.returncode, args


def has_run(args, *sequence):
    """Whether `sequence` appears in `args` as consecutive elements."""
    n = len(sequence)
    return any(tuple(args[i : i + n]) == sequence for i in range(len(args) - n + 1))


class BinUnits(unittest.TestCase):
    def test_the_defaults_run_alone(self):
        status, args = run_step(["defaults|sql|A,B"])
        self.assertEqual(status, 0)
        a = args[0]
        self.assertIn("--defaults", a)
        self.assertNotIn("--config", a)
        self.assertNotIn("--platform", a)
        self.assertFalse(any("reachFile" in x or "harness.store" in x for x in a), a)
        self.assertTrue(has_run(a, "--module", "sql", "--suites", "A,B"), a)

    def test_an_option_configuration_takes_the_reach_and_the_harness_store(self):
        status, args = run_step(["cse=false|catalyst|C"])
        self.assertEqual(status, 0)
        a = args[0]
        self.assertTrue(has_run(a, "--config", "cse=false"), a)
        self.assertNotIn("--defaults", a)
        self.assertNotIn("--platform", a)
        self.assertTrue(any(x.startswith("-Dvarka.matrix.reachFile=") for x in a), a)
        self.assertTrue(any(x.startswith("-Dvarka.harness.store=") for x in a), a)

    def test_an_option_configuration_without_the_files_passes_neither(self):
        _, args = run_step(["cse=false|catalyst|C"], reach=False, store=False)
        self.assertFalse(any("reachFile" in x or "harness.store" in x for x in args[0]), args)

    def test_a_platform_runs_the_defaults_under_its_flags_with_no_reach_and_no_store(self):
        status, args = run_step(
            [
                "isa=avx2|catalyst|D|-XX:UseAVX=2 -XX:MaxVectorSize=32",
                "isa=w128|sql|E|-XX:MaxVectorSize=16",
            ]
        )
        self.assertEqual(status, 0)
        a, b = args[0], args[1]
        self.assertTrue(has_run(a, "--defaults", "--platform", "isa=avx2"), a)
        self.assertTrue(has_run(a, "--jvm-arg", "-XX:UseAVX=2"), a)
        self.assertTrue(has_run(a, "--jvm-arg", "-XX:MaxVectorSize=32"), a)
        self.assertTrue(has_run(b, "--defaults", "--platform", "isa=w128"), b)
        self.assertTrue(has_run(b, "--jvm-arg", "-XX:MaxVectorSize=16"), b)
        for x in (a, b):
            self.assertNotIn("--config", x)
            self.assertFalse(any("reachFile" in y or "harness.store" in y for y in x), x)

    def test_a_platform_with_no_flags_is_still_a_platform(self):
        status, args = run_step(["isa=arm|sql|F|"])
        self.assertEqual(status, 0)
        a = args[0]
        self.assertTrue(has_run(a, "--defaults", "--platform", "isa=arm"), a)
        self.assertNotIn("--config", a)
        # Only the step's own series size is passed as a JVM argument.
        self.assertEqual([x for x in a if x.startswith("-")].count("--jvm-arg"), 1, a)

    def test_every_unit_runs_and_a_failing_one_fails_the_step(self):
        status, args = run_step(["defaults|sql|OK1", "cse=false|sql|FAIL1", "isa=arm|sql|OK2|"])
        self.assertNotEqual(status, 0)
        self.assertEqual(sorted(args), [0, 1, 2], "a failing unit must not stop the others")


# A unit that sleeps the seconds its suites name (`S<n>`), and one whose suites hold HANG
# ignores TERM, as a JVM stuck outside Java's own handler would, so that only the KILL stops it.
SLEEP_STUB = r"""#!/bin/bash
out="" suites=""
args=("$@")
for ((i = 0; i < $#; i++)); do
  case "${args[$i]}" in
    --out) out="${args[$((i + 1))]}" ;;
    --suites) suites="${args[$((i + 1))]}" ;;
  esac
done
touch "$out.started"
case "$suites" in *HANG*) trap '' TERM ;; esac
sleep "${suites//[^0-9]/}"
"""


def run_watched(units, **env):
    """Runs the step over `units` against SLEEP_STUB; returns (exit status, seconds taken, the
    step's output, straggler.txt or None, the units started, the process groups still alive)."""
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        (tmp / "dev").mkdir()
        stub = tmp / "dev/varka_matrix.sh"
        stub.write_text(SLEEP_STUB, encoding="utf-8")
        stub.chmod(0o755)
        out = tmp / "target/varka-units/b000"
        (out / "b000-platform").mkdir(parents=True)
        (out / "b000-platform/platform.txt").write_text(
            "CPU: AMD EPYC 9V74 80-Core Processor, 4 cores\n", encoding="utf-8"
        )
        full = dict(STEP_ENV, UNITS="\n".join(units) + "\n")
        full.update(env)
        start = time.monotonic()
        # The output is captured: a watchdog or a sleep left behind holds the pipe open, so the
        # call returns only when every process of the step is gone.
        done = subprocess.run(
            ["bash", "-e", "-c", step_script()],
            cwd=tmp,
            env=full,
            capture_output=True,
            text=True,
            timeout=120,
        )
        took = time.monotonic() - start
        marker = out / "b000-straggler/straggler.txt"
        text = marker.read_text(encoding="utf-8") if marker.exists() else None
        started = sorted(int(f.name.split("-u")[1].split(".")[0]) for f in out.glob("*.started"))
        alive = []
        for f in out.glob("*-u*.pgid"):
            try:
                os.killpg(int(f.read_text()), 0)
                alive.append(f.name)
            except ProcessLookupError:
                pass
        return done.returncode, took, done.stdout, text, started, alive


class Watchdog(unittest.TestCase):
    def test_a_bin_past_its_deadline_is_stopped_named_and_starts_nothing_more(self):
        # Two slots, a deadline of 3 s and a grace of 1 s: the first two units outlive it, one of
        # them deaf to TERM, and the third is never started.
        status, took, stdout, text, started, alive = run_watched(
            ["defaults|sql|S60", "cse=false|sql|HANG60", "w=4|catalyst|S1"],
            PLAN_SECONDS="1",
            DEADLINE_FLOOR="3",
            GRACE="1",
        )
        self.assertEqual(status, 3, stdout)
        self.assertLess(took, 15)
        self.assertEqual(started, [0, 1])
        self.assertEqual(alive, [])
        self.assertIsNotNone(text, stdout)
        self.assertIn("b000: planned 1 s, deadline 3 s, stopped at", text)
        self.assertIn("CPU: AMD EPYC 9V74 80-Core Processor, 4 cores", text)
        self.assertIn("running, stopped: defaults|sql|S60", text)
        self.assertIn("running, stopped: cse=false|sql|HANG60", text)
        self.assertIn("not run: w=4|catalyst|S1", text)
        self.assertIn(
            "::error title=Straggler::b000 ran 0 min against a plan of 0 min on"
            " AMD EPYC 9V74 80-Core Processor, 4 cores; stopped",
            stdout,
        )

    def test_a_finished_unit_is_listed_with_its_exit_and_seconds(self):
        status, _, stdout, text, started, alive = run_watched(
            ["defaults|sql|S0", "cse=false|sql|S60"],
            PLAN_SECONDS="1",
            DEADLINE_FLOOR="3",
            GRACE="1",
        )
        self.assertEqual((status, started, alive), (3, [0, 1], []), stdout)
        self.assertRegex(text, r"finished, exit 0 in \d+ s: defaults\|sql\|S0")

    def test_a_bin_on_time_ends_with_its_units_not_at_its_deadline(self):
        status, took, stdout, text, started, alive = run_watched(
            ["defaults|sql|S1", "cse=false|sql|S1", "w=4|catalyst|S1"],
            PLAN_SECONDS="1",
            DEADLINE_FLOOR="30",
        )
        self.assertEqual(status, 0, stdout)
        self.assertIsNone(text)
        self.assertEqual((started, alive), ([0, 1, 2], []))
        # Two rounds of 1 s units: the step waits on neither the watchdog nor its sleep.
        self.assertLess(took, 4)

    def test_a_planted_straggler_runs_no_unit_and_is_stopped(self):
        status, took, stdout, text, started, alive = run_watched(
            ["defaults|sql|S1", "cse=false|sql|S1"],
            PLAN_SECONDS="1",
            DEADLINE_FLOOR="2",
            GRACE="1",
            PLANT="b000",
        )
        self.assertEqual(status, 3, stdout)
        self.assertLess(took, 12)
        self.assertEqual((started, alive), ([], []))
        self.assertIn("running, stopped: planted straggler", text)
        self.assertIn("not run: defaults|sql|S1", text)
        self.assertIn("not run: cse=false|sql|S1", text)

    def test_another_bins_plant_changes_nothing(self):
        status, _, stdout, text, started, _ = run_watched(["defaults|sql|S0"], PLANT="b001")
        self.assertEqual((status, text, started), (0, None, [0]), stdout)


class SweepStep(unittest.TestCase):
    def test_a_junit_shard_runs_in_this_jvm_with_its_property_and_its_texts(self):
        status, args = run_sweep_step("VarkaEmitterNarrowArithSuite")
        self.assertEqual(status, 0)
        self.assertIn("-Dvarka.sweep=true", args)
        self.assertIn("-Dvarka.ownJvm=true", args)
        self.assertIn("--add-modules=jdk.incubator.vector", args)
        self.assertTrue(has_run(args, "-cp", "/a/b:/c/d"), args)
        i = args.index("org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteRunner")
        runner = args[i + 1 :]
        self.assertTrue(
            has_run(
                runner,
                "-s",
                "org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterNarrowArithSuite",
            ),
            runner,
        )
        self.assertTrue(has_run(runner, "-z", "pair for + in"), runner)
        self.assertTrue(has_run(runner, "-u", "target/varka-sweep-run/junit"), runner)

    def test_a_scalatest_suite_is_selected_by_its_filter_and_forks_no_child_jvm(self):
        status, args = run_sweep_step("VarkaEmitterBitwiseSuite")
        self.assertEqual(status, 0)
        self.assertNotIn("-Dvarka.ownJvm=true", args)
        self.assertTrue(has_run(args, "-z", "opt-in"), args)

    def test_a_long_sweep_sets_the_long_property(self):
        _, args = run_sweep_step("VarkaTimeFractionSuite")
        self.assertIn("-Dvarka.sweep.long=true", args)
        self.assertNotIn("-Dvarka.sweep=true", args)

    def test_a_failing_sweep_fails_the_step(self):
        status, _ = run_sweep_step("VarkaEmitterBitwiseSuite", exit_status=3)
        self.assertEqual(status, 3)


# The runner for the AVX2 step: `--list-suites` prints $STUB_SUITES, one a line; a run records its
# arguments, one a line, in $STUB_ARGS and exits 3 when its suites hold FAIL.
AVX2_STUB = r"""#!/bin/bash
for a in "$@"; do [ "$a" = --list-suites ] && { printf '%s\n' "$STUB_SUITES"; exit 0; }; done
printf '%s\n' "$@" > "$STUB_ARGS"
suites=""
args=("$@")
for ((i = 0; i < $#; i++)); do [ "${args[$i]}" = --suites ] && suites="${args[$((i + 1))]}"; done
case "$suites" in *FAIL*) exit 3 ;; esac
exit 0
"""

AVX2_STEP = "Run this share under AVX2 at 128 bits"


def run_avx2_step(part, suites, module="catalyst"):
    """Runs the sweep workflow's AVX2 step for share `part` of `module` over `suites` (fully
    qualified, `@own ` before one that runs alone); returns (exit status, the runner's
    arguments)."""
    doc = yaml.safe_load(SWEEPS.read_text(encoding="utf-8"))
    steps = doc["jobs"]["avx2-128"]["steps"]
    script = next(s["run"] for s in steps if s.get("name") == AVX2_STEP)
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        (tmp / "dev").mkdir()
        (tmp / "dev/varka_suite_shards.py").symlink_to(ROOT / "dev/varka_suite_shards.py")
        (tmp / "sql").symlink_to(ROOT / "sql")
        (tmp / "target").mkdir()
        stub = tmp / "dev/varka_matrix.sh"
        stub.write_text(AVX2_STUB, encoding="utf-8")
        stub.chmod(0o755)
        env = {
            "PATH": os.environ["PATH"],
            "MODULE": module,
            "PART": part,
            "STUB_SUITES": "\n".join(suites),
            "STUB_ARGS": str(tmp / "runner.args"),
            "GITHUB_STEP_SUMMARY": str(tmp / "summary.md"),
        }
        done = subprocess.run(
            ["bash", "-e", "-c", script],
            cwd=tmp,
            env=env,
            capture_output=True,
            text=True,
            timeout=60,
        )
        path = tmp / "runner.args"
        args = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
        return done.returncode, args


class Avx2Step(unittest.TestCase):
    SUITES = ["p.VarkaIrFuzzSuite", "@own p.VarkaAvx2SelectSuite", "@own p.VarkaAssemblySuite"] + [
        f"p.VarkaS{i}Suite" for i in range(7)
    ]

    def test_the_three_shares_run_every_suite_once_under_both_flags(self):
        ran = []
        for part in ("0/3", "1/3", "2/3"):
            status, args = run_avx2_step(part, self.SUITES)
            self.assertEqual(status, 0, args)
            for sequence in (
                ("--jvm-arg", "-XX:UseAVX=2"),
                ("--jvm-arg", "-XX:MaxVectorSize=16"),
                ("--defaults", "--skip-build", "--build-dir", "target/varka-sweeps"),
                ("--module", "catalyst"),
            ):
                self.assertTrue(has_run(args, *sequence), (sequence, args))
            ran.extend(args[args.index("--suites") + 1].split(","))
        # Every suite once, but the one that measures the JIT.
        expected = sorted(s.rsplit(".", 1)[-1] for s in self.SUITES if "Assembly" not in s)
        self.assertEqual(sorted(ran), expected)

    def test_the_sql_extra_suite_runs_in_one_share_only(self):
        arrow = "org.apache.spark.sql.execution.columnar.ArrowCachedBatchSerializerSuite"
        given = []
        for part in ("0/3", "1/3", "2/3"):
            status, args = run_avx2_step(part, self.SUITES + [arrow], module="sql")
            self.assertEqual(status, 0, args)
            given.append(has_run(args, "--extra-suite", f"sql:{arrow}"))
        self.assertEqual(given.count(True), 1, given)

    def test_a_failing_share_fails_the_step(self):
        statuses = [
            run_avx2_step(part, self.SUITES + ["p.FAILSuite"])[0] for part in ("0/3", "1/3", "2/3")
        ]
        self.assertEqual(sorted(statuses), [0, 0, 3])


def main(argv):
    if argv[:1] != ["--selftest"]:
        print(__doc__)
        return 2
    result = unittest.main(argv=[sys.argv[0]], exit=False, verbosity=0).result
    ok = result.wasSuccessful()
    print("varka_bin_units_test selftest: " + ("ok" if ok else "FAILED"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
