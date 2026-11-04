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
"""The sql module's Varka suites with a kernel's two bodies held at different JIT tiers (VARKA-557).

    dev/varka_tier_split.py list                       # the configurations, one a line
    dev/varka_tier_split.py run CONFIG [ARG...]        # dev/varka_matrix.sh under CONFIG
    dev/varka_tier_split.py check CONFIG DIR...        # the runs' tier evidence and batch count
    dev/varka_tier_split.py --self-test                # check's reading of a fixture log

Every batch a kernel serves under test also runs through its other body, dense or masked, and
the two must agree (`VarkaKernelRunner`'s both-bodies check). The two bodies are the same
bytecode, so an answer that depends on the JIT tier shows only when a run puts them on different
tiers, which the suites never arrange: their sessions run the warm-up off, a kernel serves a few
batches and both bodies stay interpreted. Each configuration names one side held back and
compiles the other early, by C2 alone:

- `masked-interpreted` and `dense-interpreted`: the held side is excluded from compilation;
- `masked-c1` and `dense-c1`: C2 may not compile the held side, so C1 compiles it, at tier 1;
- in all four the other side and the dispatch `run` may not be compiled by C1, every kernel
  method's thresholds are scaled by THRESHOLD_FACTOR, so the first call or two cross them, and
  its compiles are synchronous; the held side is never inlined into a compiled caller, where it
  would be compiled with the caller (`exclude` does not stop inlining).

`run` passes the options to `dev/varka_matrix.sh --defaults --module sql` with the suites below
skipped and its JVMs balanced by WEIGHTS. `check` reads the run's logs: the directives print
every compile of a kernel method, held side included, so the forward side must show at least
MIN_FORWARD tier-4 compiles and none by C1, the held side none at tier 4, under `-c1` some by C1
and under `-interpreted` none at all (an excluded method is refused before a compile is printed);
the JVMs' summed both-bodies line must show batches compared; and every JVM must have finished,
since one the job's timeout killed leaves its log and no exit status. The
test JVMs carry `-XX:+IgnoreUnrecognizedVMOptions`, so an option or pattern that took no effect
passes silently; this is the check that it did. `VarkaMethodNamesSuite` checks that every
pattern here and in the directive files names what `VarkaMethodNames` builds.
"""

import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DIRECTIVES = ROOT / "sql" / "varka" / "ci" / "tier-split"
# Each suite's seconds under dense-interpreted on a runner, which the job's shares and each share's
# two JVMs are balanced by: a fresh runner has no earlier run's times (`m8/VARKA-557.md` 8.4).
WEIGHTS = DIRECTIVES / "suite-times.tsv"

# The kernel classes, in HotSpot's method-pattern syntax (`VarkaShapeCacheImpl.CLASS_NAME_PREFIX`
# with slashes, then a wildcard), and the method patterns of each side and of the dispatch.
KERNELS = "org/apache/spark/sql/varka/execution/VarkaFusedProjection_*"
SIDE = {"masked": f"{KERNELS}.*Masked*", "dense": f"{KERNELS}.*Dense*"}
DISPATCH = f"{KERNELS}.run"
EVERY_METHOD = f"{KERNELS}.*"

# The per-method threshold scale, at which every compiled-side method reached C2 within its first
# calls in the laptop trial, where 0.01 and 0.001 left most short of it (`m8/VARKA-557.md` 8.1).
THRESHOLD_FACTOR = "0.0001"

# The fewest tier-4 compiles of the forward side's methods a configuration's runs must show; the
# laptop trial's runs showed thousands (8.1), so a handful means the scaling did not take.
MIN_FORWARD = 10

CONFIGS = ["masked-interpreted", "dense-interpreted", "masked-c1", "dense-c1"]

# The sql suites whose subject is the JIT or a compiled kernel's allocations rather than an
# answer, which a side held interpreted or at C1 breaks by design. Each with its reason.
SKIPS = {
    "VarkaWarmupEndToEndSuite": "times a warmed kernel's climb to C2, which a held side never "
    "makes",
    "VarkaSizeLadderJitSuite": "asserts the C2 code of a size ladder, which a held side never gets",
}


def held(config):
    return config.split("-")[0]


def forward(config):
    return "dense" if held(config) == "masked" else "masked"


def jvm_args(config):
    """The test JVM's options for CONFIG, in the order dev/varka_matrix.sh passes them."""
    if config not in CONFIGS:
        raise SystemExit(f"unknown configuration {config}; one of {', '.join(CONFIGS)}")
    side = SIDE[held(config)]
    args = [
        "-XX:CompileCommand=quiet",
        "-XX:+UnlockDiagnosticVMOptions",
        f"-XX:CompilerDirectivesFile={DIRECTIVES / (config + '.json')}",
        f"-XX:CompileCommand=CompileThresholdScaling,{EVERY_METHOD},{THRESHOLD_FACTOR}",
        f"-XX:CompileCommand=BackgroundCompilation,{EVERY_METHOD},false",
        f"-XX:CompileCommand=dontinline,{side}",
    ]
    if config.endswith("-interpreted"):
        args.append(f"-XX:CompileCommand=exclude,{side}")
    # The tests whose subject is a body's C2 code read it (VarkaMatrixRuns.TIER_SPLIT).
    args.append(f"-Dvarka.tierSplit={config}")
    # A held side made a suite up to 124 times slower (VarkaSplitConditionSuite in the laptop
    # trial, VARKA-557.md 8.2) and its longest test take 348 seconds, about twice that on
    # a runner: past Varka's per-test watchdog (10 minutes) and near SparkFunSuite's (20).
    args.append("-Dvarka.test.watchdog.minutes=30")
    args.append("-Dspark.test.timeout=30")
    return args


def run(config, rest):
    cmd = [
        str(ROOT / "dev" / "varka_matrix.sh"),
        "--defaults",
        "--module",
        "sql",
        "--skip-suites",
        ",".join(SKIPS),
        "--split-weights",
        str(WEIGHTS),
    ]
    for a in jvm_args(config):
        cmd += ["--jvm-arg", a]
    os.execv(cmd[0], cmd + rest)


# A compile the directives print: the time, the compile id, flags, the tier, then the method.
# HotSpot prints a task as it starts and, if it fails, again with "COMPILE SKIPPED"
# (compileBroker.cpp 2178 and 2372), so a task is counted once by its id and a skipped one is no
# compile.
COMPILE = re.compile(
    r"^\s*\d+\s+(\d+)\s+[%sbn! ]*([0-4])\s+"
    r"org\.apache\.spark\.sql\.varka\.execution\.VarkaFusedProjection_\w+::(\w+)"
)
COMPARED = re.compile(r"Varka both-bodies check compared: (\d+) null-free, (\d+) nullable")
# ScalaTest's line as a suite starts, which the JUnit suites' runner prints too.
SUITE = re.compile(r"^(Varka\w*Suite):$")


def kind(method):
    if method == "run":
        return "run"
    if "Masked" in method:
        return "masked"
    return "dense" if "Dense" in method else None


def check(config, dirs):
    """Prints the evidence line for CONFIG's runs under DIRS; false when it shows no split."""
    tiers = {"masked": {}, "dense": {}, "run": {}}
    skipped = {"masked": 0, "dense": 0, "run": 0}
    null_free = nullable = jvms = 0
    unfinished = []
    for log in sorted(f for d in dirs for f in Path(d).rglob("run.log")):
        jvms += 1
        tasks, failed, suite = {}, set(), None
        for line in log.read_text(errors="replace").splitlines():
            m = COMPILE.match(line)
            if m and kind(m.group(3)):
                if "COMPILE SKIPPED" in line:
                    failed.add(m.group(1))
                tasks.setdefault(m.group(1), (kind(m.group(3)), m.group(2)))
            c = COMPARED.search(line)
            if c:
                null_free += int(c.group(1))
                nullable += int(c.group(2))
            s = SUITE.match(line)
            suite = s.group(1) if s else suite
        for task, (k, tier) in tasks.items():
            if task in failed:
                skipped[k] += 1
            else:
                tiers[k][tier] = tiers[k].get(tier, 0) + 1
        if not (log.parent / "exit").exists():
            unfinished.append(f"{log.parent.name} in {suite or 'no suite yet'}")
    fwd, back = tiers[forward(config)], tiers[held(config)]
    problems = []
    if fwd.get("4", 0) < MIN_FORWARD:
        problems.append(
            f"the {forward(config)} side reached C2 {fwd.get('4', 0)} times, under {MIN_FORWARD}"
        )
    if any(fwd.get(t) for t in "123"):
        problems.append(f"C1 compiled the {forward(config)} side")
    if back.get("4"):
        problems.append(f"C2 compiled the {held(config)} side")
    if config.endswith("-c1") and not any(back.get(t) for t in "123"):
        problems.append(f"C1 never compiled the {held(config)} side")
    if any(tiers["run"].get(t) for t in "123"):
        problems.append("C1 compiled the dispatch")
    if config.endswith("-interpreted") and (back or skipped[held(config)]):
        problems.append(f"the {held(config)} side was given to a compiler")
    if null_free + nullable == 0:
        problems.append("the both-bodies check compared no batch")

    def show(t):
        return ", ".join(f"tier {k}: {v}" for k, v in sorted(t.items())) or "none"

    print(
        f"{config}: {jvms} JVMs; compared {null_free} null-free and {nullable} nullable "
        f"batches; {forward(config)} compiles {show(fwd)}; {held(config)} compiles "
        f"{show(back)}; dispatch compiles {show(tiers['run'])}; skipped compiles "
        f"{sum(skipped.values())}"
    )
    for p in problems:
        print(f"{config}: no tier split: {p}")
    for u in unfinished:
        print(f"{config}: a JVM did not finish (timed out?): {u}")
    return not problems and not unfinished


def self_test():
    """check() over fixture logs: the lines are HotSpot's, from the laptop trial's run."""
    import tempfile

    k = "org.apache.spark.sql.varka.execution.VarkaFusedProjection_dee952db4f2a64e9::"
    ok = [f"{6624 + i} {14642 + i}    b  4       {k}loopMasked{i} (142 bytes)" for i in range(10)]
    done = "Varka both-bodies check compared: 5 null-free, 0 nullable"
    cases = [
        ("passes", ok + [done], True, True),
        ("a skipped task is no compile", ok + [done, ok[0] + "   COMPILE SKIPPED: x"], True, False),
        ("a held-side compile", ok + [done, f"7 150 b  4  {k}runDense"], True, False),
        ("a JVM with no exit status", ok + [done, "VarkaSlowSuite:"], False, False),
    ]
    bad = 0
    for name, lines, exited, want in cases:
        with tempfile.TemporaryDirectory() as d:
            jvm = Path(d) / "sql-0"
            jvm.mkdir()
            (jvm / "run.log").write_text("\n".join(lines) + "\n")
            if exited:
                (jvm / "exit").write_text("0\n")
            got = check("dense-interpreted", [d])
        if got != want:
            print(f"self-test failed: {name}: check gave {got}")
            bad += 1
    # Each directive file names its held side, so that check sees the held side's compiles.
    for config in CONFIGS:
        text = (DIRECTIVES / f"{config}.json").read_text()
        if SIDE[held(config)] not in text:
            print(f"self-test failed: {config}.json does not name the {held(config)} side")
            bad += 1
    print("self-test:", "failed" if bad else "passed")
    return 1 if bad else 0


def main(argv):
    if argv == ["--self-test"]:
        return self_test()
    if len(argv) >= 1 and argv[0] == "list":
        print("\n".join(CONFIGS))
    elif len(argv) >= 2 and argv[0] == "run":
        jvm_args(argv[1])
        run(argv[1], argv[2:])
    elif len(argv) >= 3 and argv[0] == "check":
        jvm_args(argv[1])
        return 0 if check(argv[1], argv[2:]) else 1
    else:
        print(__doc__, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
