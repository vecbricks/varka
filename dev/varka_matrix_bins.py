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
The fanned-out option matrix's bins (VARKA-507): every (configuration, module, suite) the matrix
runs, packed into CI jobs of about the same length.

A unit is one runner JVM: a suite that runs alone (`@own` in `dev/varka_matrix.sh
--list-suites`), or a group of a module's other suites under one configuration, cut so that no
group outweighs a lane. A bin is one CI job with two lanes, two JVMs at a time on the runner's
four cores, and takes units longest first onto its lighter lane until both lanes reach the
target. The defaults are a configuration like any other, named `defaults`.

A unit's weight is its suites' seconds plus a JVM's start. A suite's seconds under one
configuration are its measured seconds under that configuration where
`sql/varka/matrix/config-suite-seconds.tsv` lists the pair, since a suite can run several times
longer under one configuration than under most (a fuzz suite that draws until it has emitted
enough bytes, say). Otherwise they are the suite's seconds under the defaults times the
configuration's weight over the defaults (`sql/varka/matrix/seconds.tsv`): the suite's seconds
from `sql/varka/matrix/suite-seconds.tsv` where a suite is listed there (each suite's median over
the configurations), from the pull-request jobs' `sql/varka/ci/suite-times.tsv` otherwise, and
for a suite neither file lists the median, so a new suite is placed as an ordinary one. `times`
writes both measured files from a fanned-out run's JUnit reports.

  dev/varka_matrix_bins.py plan --configurations FILE --suites MODULE=FILE... \\
      [--target SECONDS] > bins.json          # the job matrix, {"include": [...]}
  dev/varka_matrix_bins.py times [--reach FILE] [--per-configuration FILE] JUNIT_DIR... \\
      > suite-seconds.tsv
  dev/varka_matrix_bins.py select --base DIR --pr DIR --configurations FILE \\
      (--changed FILE | --trees OLD NEW) [--reach FILE --reach-out FILE] > units.tsv
  dev/varka_matrix_bins.py --selftest

Each bin's `units` is one line a JVM, `configuration|module|suite,suite,...`, the suites as
simple names, which the bin's job hands to `dev/varka_matrix.sh --suites`.

`select` (VARKA-508) compares master's `VarkaTestUse` record with a pull request's and prints
the (configuration, suite, test) units the pull request's matrix reruns, one
`configuration<TAB>suite<TAB>reason<TAB>test` line each (the test last, as it may hold any
character), or the single unit `*<TAB>*<TAB>reason<TAB>*` for everything. `--reach-out` writes
the same selection as a reach record, which `plan --reach` and the configurations' JVMs read.
"""

import argparse
import hashlib
import json
import re
import statistics
import sys
import tempfile
import unittest
from pathlib import Path

SUITE_TIMES = "sql/varka/ci/suite-times.tsv"
SUITE_SECONDS = "sql/varka/matrix/suite-seconds.tsv"
CONFIG_SUITE_SECONDS = "sql/varka/matrix/config-suite-seconds.tsv"
CONFIG_WEIGHTS = "sql/varka/matrix/seconds.tsv"
DEFAULTS = "defaults"
# A runner JVM's start and its first Spark session, before any suite's own time.
JVM_START = 20.0
# The settings a configuration may name that change only a Spark session's configuration, not
# an emit option: `VarkaMatrixRuns.parseOver` leaves the options as they are for them. No
# catalyst suite builds a session, so under a configuration of these alone the catalyst suites
# would run as the defaults run them, and the matrix leaves them out (VARKA-524).
SESSION_ONLY = ("arrowBatchSize",)


def reaches(config, module):
    """Whether `config` can change anything `module`'s suites run: not catalyst's, for a
    configuration that names only session settings."""
    if module != "catalyst" or config == DEFAULTS:
        return True
    names = {part.split("=", 1)[0] for part in re.split(r"[,_]", config) if "=" in part}
    return not names or not names <= set(SESSION_ONLY)


LANES = 2


def read_tsv(path):
    """`name<TAB>number` lines, `#` comments and blank lines aside; empty for a missing file."""
    out = {}
    p = Path(path)
    if not p.exists():
        return out
    for line in p.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        name, value = line.split("\t")[:2]
        out[name] = float(value)
    return out


def run_dir(config):
    """The directory `dev/varka_matrix.sh` runs a configuration in, under `runs/`: every character
    but letters, digits and `=_.-` made `_`, so the commas of a combined configuration are `_`
    too. The JUnit reports name a configuration only this way."""
    return re.sub(r"[^A-Za-z0-9=_.-]", "_", config)


def read_config_seconds(path):
    """`configuration<TAB>suite<TAB>seconds<TAB>runs` lines as {(run directory, suite): seconds},
    `#` comments and blank lines aside; empty for a missing file."""
    out = {}
    p = Path(path)
    if not p.exists():
        return out
    for line in p.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        config, suite, seconds = line.split("\t")[:3]
        out[(run_dir(config), suite)] = float(seconds)
    return out


def read_suites(path):
    """A module's suites as `--list-suites` prints them: (simple name, runs alone)."""
    out = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        alone = line.startswith("@own ")
        name = line[len("@own ") :] if alone else line
        out.append((name.rsplit(".", 1)[-1], alone))
    return out


def suite_weight(suite_seconds, suite_times):
    """The seconds of a suite under the defaults, by the measured file, the PR file, the median."""
    known = list(suite_seconds.values()) or list(suite_times.values()) or [30.0]
    fill = statistics.median(known)

    def weight(suite):
        if suite in suite_seconds:
            return suite_seconds[suite]
        return suite_times.get(suite, fill)

    return weight


def read_reach(path):
    """`VarkaTestReach`'s record: the configurations it covers, from its `#<TAB>covers<TAB>`
    lines, and for each suite the configurations that move each of its tests, with the suite's
    set-up line (`suite<TAB>*`) and the JVM's (`*<TAB>*`) joined to every test they cover. None
    for a record its recording marked broken."""
    covered, tests, setup, everywhere = set(), {}, {}, set()
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if line.startswith("#"):
            if len(parts) >= 2 and parts[1] == "broken":
                return None
            if len(parts) == 3 and parts[1] == "covers":
                covered |= {c for c in parts[2].split(",") if c}
            continue
        if len(parts) < 3:
            continue
        moved = set() if parts[-1] == "-" else set(parts[-1].split(","))
        suite, test = parts[0], "\t".join(parts[1:-1])
        if suite == "*":
            everywhere |= moved
        elif test == "*":
            setup.setdefault(suite, set()).update(moved)
        else:
            tests.setdefault(suite, []).append(moved)
    joined = {
        suite: [m | setup.get(suite, set()) | everywhere for m in moved]
        for suite, moved in tests.items()
    }
    return covered, joined


def reach_share(reach, config, suite):
    """The share of `suite`'s tests that `config` runs: all of them where there is no record, the
    record does not cover the configuration, or it has no test of the suite. A test marked `*`
    runs under every configuration."""
    if reach is None or config == DEFAULTS:
        return 1.0
    covered, tests = reach
    if config not in covered or suite not in tests:
        return 1.0
    moved = tests[suite]
    return sum(config in m or "*" in m for m in moved) / len(moved)


def units(configurations, suites, weight, config_weights, lane, reach=None, config_seconds=None):
    """Every (configuration, module, suites, seconds) JVM the matrix runs, the defaults first. A
    suite's whole run under a configuration takes its measured seconds there (`config_seconds`,
    keyed by the configuration's run directory) or else its weight times the configuration's
    factor. A suite whose tests a configuration's record leaves all to the defaults is not run
    under it, and one it runs some tests of weighs that share of its whole run, a tenth at least,
    for its set-up."""
    fill = statistics.median(config_weights.values()) if config_weights else 1.0
    config_seconds = config_seconds or {}
    out = []
    for config in [DEFAULTS] + list(configurations):
        factor = 1.0 if config == DEFAULTS else config_weights.get(config, fill)
        directory = run_dir(config)
        for module, listed in suites:
            if not reaches(config, module):
                continue
            shared = []
            for name, alone in listed:
                share = reach_share(reach, config, name)
                if share == 0.0:
                    continue
                whole = config_seconds.get((directory, name))
                if whole is None:
                    whole = weight(name) * factor
                seconds = whole * max(share, 0.1)
                if alone:
                    out.append((config, module, [name], JVM_START + seconds))
                else:
                    shared.append((name, seconds))
            # The shared suites in groups no heavier than a lane, longest first into the lightest.
            total = sum(s for _, s in shared)
            groups = max(1, int(-(-total // max(lane - JVM_START, 1.0))))
            loads = [[0.0, []] for _ in range(groups)]
            for name, seconds in sorted(shared, key=lambda u: (-u[1], u[0])):
                g = min(loads, key=lambda x: x[0])
                g[0] += seconds
                g[1].append(name)
            for load, names in loads:
                if names:
                    out.append((config, module, sorted(names), JVM_START + load))
    return out


def pack(all_units, lane):
    """Bins of two lanes each, units longest first onto the lighter lane, a bin closed when a
    unit would take its lighter lane past the target; a unit longer than a lane takes a bin."""
    bins = []
    for unit in sorted(all_units, key=lambda u: (-u[3], u[0], u[1], u[2])):
        placed = False
        for b in bins:
            lanes = b["lanes"]
            k = min(range(LANES), key=lambda i: lanes[i])
            if lanes[k] + unit[3] <= lane:
                lanes[k] += unit[3]
                b["units"].append(unit)
                placed = True
                break
        if not placed:
            bins.append({"lanes": [unit[3]] + [0.0] * (LANES - 1), "units": [unit]})
    return bins


def plan(
    configurations,
    suites,
    suite_seconds,
    suite_times,
    config_weights,
    lane,
    reach=None,
    config_seconds=None,
):
    weight = suite_weight(suite_seconds, suite_times)
    all_units = units(configurations, suites, weight, config_weights, lane, reach, config_seconds)
    bins = pack(all_units, lane)
    include = []
    for k, b in enumerate(bins):
        include.append(
            {
                "bin": f"b{k:03d}",
                "seconds": round(max(b["lanes"])),
                "units": "\n".join(f"{c}|{m}|{','.join(s)}" for c, m, s, _ in b["units"]),
            }
        )
    return {"include": include}


def whole_runs(junit_dirs, reach=None):
    """Each suite's seconds under each configuration that ran every test of it, the defaults
    among them, as {(run directory, suite): seconds}, from JUnit reports under
    `<dir>/<configuration>/<module>-.../junit`. With the run's reach record, a configuration
    that ran only the tests the record let through is left out: its time is a share of the
    suite's, and the planner applies that share to the weight itself (`units`), so counting it
    here too would shrink the weight twice (VARKA-508). The directories are one run's: a suite's
    seconds under a configuration add up over them."""
    per_config = {}
    for d in junit_dirs:
        for xml in Path(d).glob("**/*/*/junit/TEST-*.xml"):
            head = xml.read_text(encoding="utf-8", errors="replace")[:4000]
            tag = re.search(r"<testsuite\b[^>]*>", head)
            if not tag:
                continue
            name = re.search(r'\bname="([^"]+)"', tag.group(0))
            seconds = re.search(r'\btime="([0-9.]+)"', tag.group(0))
            if not name or not seconds:
                continue
            config = xml.parent.parent.parent.name
            key = (name.group(1).rsplit(".", 1)[-1], config)
            per_config[key] = per_config.get(key, 0.0) + float(seconds.group(1))
    return {
        (config, suite): seconds
        for (suite, config), seconds in per_config.items()
        if reach_share(reach, config, suite) == 1.0
    }


def times(junit_dirs, reach=None):
    """Each suite's seconds run whole, the median over the configurations that ran every test of
    it (`whole_runs`). The configurations' runs land on the pool's mix of runners, so the median
    is a run's typical cost of the suite rather than the cost on the fastest or the slowest
    machine."""
    runs = {}
    for (_, suite), seconds in whole_runs(junit_dirs, reach).items():
        runs.setdefault(suite, []).append(seconds)
    return {suite: statistics.median(seconds) for suite, seconds in runs.items()}


# ---------------------------------------------------------------------------------------------
# Selection: which (configuration, suite, test) units a pull request's matrix reruns
# ---------------------------------------------------------------------------------------------

# The compiler's and the emitter's classes, whose whole effect on a test's answer is what they
# compiled and emitted for it: a change to one reruns a unit only where the unit's digest moved.
# Every other class a test used reruns it under every configuration when it changes, so a class
# left off this list costs reruns and a class wrongly on it is a skip without a check. Kept to the
# lowering, the analyses, the plan and the emitter; the IR's own records, the shape cache, the
# warm-up, the errors and every class the evaluator calls stay off it. A class here that a kernel
# calls as it runs is not covered by the digest: the record names the classes the emitted kernels
# refer to (`#<TAB>kernel-refs`), and those are taken off this list.
COMPILER = "org.apache.spark.sql.catalyst.expressions.codegen."
DIGESTED_CLASSES = frozenset(
    [
        COMPILER + name
        for name in [
            "VarkaChronoCompiler",
            "VarkaConditionCompiler",
            "VarkaConjunctOrder",
            "VarkaExpressionCompiler",
            "VarkaIntervalCompiler",
            "VarkaNodeCompiler",
            "VarkaSizeAdmission",
            "VarkaSlotTable",
            "VarkaTimeCompiler",
        ]
    ]
    + [
        COMPILER + "varka." + name
        for name in [
            "Analysis",
            "Slots",
            "TruncLevelLeaf",
            "WeekdayLeaf",
            "VarkaBodyEmitter",
            "VarkaChronoLowering",
            "VarkaDescriptors",
            "VarkaDivisionLowering",
            "VarkaEmitBudget",
            "VarkaEmitCost",
            "VarkaEmitCostTable",
            "VarkaKernelPlan",
            "VarkaLoopEmitter",
            "VarkaMethodNames",
            "VarkaOperandPosition",
            "VarkaRangeAnalysis",
            "VarkaValueRange",
            "VarkaVectorWalk",
        ]
    ]
)


def digested_class(name):
    """Whether `name`, an inner class by its outer one, is on the compiler's list."""
    return name.split("$", 1)[0] in DIGESTED_CLASSES


EVERYTHING = ("*", "*", "*")


def read_use(path):
    """`VarkaTestUse`'s record, a directory of `use-<pid>.tsv` files or one file: for each
    (suite, test) its class set (None where the run kept none) and its digests by configuration,
    each suite's set-up as test `*` and the JVMs' own as suite `*`, test `*`, and the classes the
    emitted kernels refer to. None for a record that is missing, empty or marked broken."""
    p = Path(path)
    files = sorted(p.glob("use-*.tsv")) if p.is_dir() else [p] if p.is_file() else []
    classes, digests, refs = {}, {}, set()
    try:
        for f in files:
            for line in f.read_text(encoding="utf-8").splitlines():
                parts = line.split("\t")
                if parts[0] == "#":
                    if len(parts) >= 2 and parts[1] == "broken":
                        return None
                    if len(parts) == 3 and parts[1] == "kernel-refs":
                        refs |= {c for c in parts[2].split(",") if c}
                elif parts[0] == "U" and len(parts) >= 4:
                    key = (parts[1], "\t".join(parts[3:]))
                    names = parts[2]
                    got = (
                        {"*"} if names == "*" else set() if names == "-" else set(names.split(","))
                    )
                    classes.setdefault(key, set()).update(got)
                elif parts[0] == "D" and len(parts) >= 5:
                    key = (parts[1], "\t".join(parts[4:]))
                    digests.setdefault(key, {})[parts[2]] = parts[3]
    except (OSError, UnicodeDecodeError):
        return None
    if not classes and not digests:
        return None
    return {"classes": classes, "digests": digests, "refs": refs}


def class_name(path):
    """A class file's path to (class name, is a test class): the name is what follows the
    `classes/` or `test-classes/` directory, or the whole path for a bare name."""
    s = "/" + str(path).replace("\\", "/")
    test = "/test-classes/" in s
    for marker in ("/test-classes/", "/classes/"):
        if marker in s:
            s = s.split(marker, 1)[1]
            break
    s = s.lstrip("/")
    if s.endswith(".class"):
        s = s[: -len(".class")]
    return s.replace("/", "."), test


def tree_diff(old, new):
    """The class files that differ between two class trees, added and removed ones included, as
    paths relative to the trees' roots."""

    def sums(root):
        out = {}
        for f in Path(root).rglob("*.class"):
            out[str(f.relative_to(root))] = hashlib.sha256(f.read_bytes()).hexdigest()
        return out

    a, b = sums(old), sums(new)
    return sorted(k for k in set(a) | set(b) if a.get(k) != b.get(k))


def is_varka(name):
    simple = name.rsplit(".", 1)[-1]
    return ".varka." in name or "Varka" in simple


def select(base, pr, changed, configurations):
    """The (configuration, suite, test) units to rerun, and the reason for each, given master's
    record `base`, the pull request's `pr`, the changed class files `changed` (paths or names)
    and the matrix's configurations (the defaults among them). A unit reruns when its test is
    new or has no digest under the configuration, when its digest differs from master's, when a
    test class of its suite changed, or when a class its test used changed, the compiler's and
    the emitter's classes aside, which its digest stands for. Everything reruns, as the single
    unit `*`, when either record is missing or broken, or when a changed class is one neither the
    record nor the digest can place: a test class other than a suite's own, a class outside
    Varka, or a class some test's record could not see (a child JVM's)."""
    if base is None or pr is None:
        return {EVERYTHING: "no usable record"}
    configs = [DEFAULTS] + [c for c in configurations if c != DEFAULTS]
    refs = base["refs"] | pr["refs"]

    def digested(name):
        return digested_class(name) and name not in refs

    main_changed, digest_changed, test_changed = set(), set(), set()
    for path in changed:
        name, test = class_name(path)
        if test:
            test_changed.add(name)
        elif not is_varka(name):
            return {EVERYTHING: f"{name} is outside Varka's record"}
        elif digested(name):
            digest_changed.add(name)
        else:
            main_changed.add(name)
    # A changed test class reruns its own suite; any other test class (a harness, a fixture)
    # is used by tests the record cannot name.
    suites = {s for s, _ in pr["classes"]} | {s for s, _ in pr["digests"]}
    rerun_suites = set()
    for name in test_changed:
        top = name.rsplit(".", 1)[-1].split("$", 1)[0]
        if top in suites:
            rerun_suites.add(top)
        else:
            return {EVERYTHING: f"test class {name} is not a recorded suite's own"}

    def used(rec, suite, test):
        got = set(rec["classes"].get((suite, test), set()))
        got |= rec["classes"].get((suite, "*"), set())
        got |= rec["classes"].get(("*", "*"), set())
        return got

    # A test's digest joins its suite's set-up, but not the JVM's own: what a JVM compiled outside
    # every suite depends on which suites shared it, which the planner changes from run to run.
    # A test that asks for such a compile or kernel again reports it into its own digest; one
    # that only reuses an object built there does not, a limit the reach record shares.
    def digest(rec, suite, test, config):
        own = rec["digests"].get((suite, test), {}).get(config)
        if own is None:
            return None
        return own, rec["digests"].get((suite, "*"), {}).get(config, "")

    out = {}
    tests = sorted(
        {k for k in pr["classes"] if k[1] != "*" and k[0] != "*"}
        | {k for k in pr["digests"] if k[1] != "*" and k[0] != "*"}
    )
    for suite, test in tests:
        known = (suite, test) in base["classes"] or (suite, test) in base["digests"]
        reason = None
        if not known:
            reason = "new test"
        elif suite in rerun_suites:
            reason = "its suite's class changed"
        else:
            u = used(base, suite, test) | used(pr, suite, test)
            if "*" in u and (main_changed or digest_changed):
                reason = "ran a child JVM the record cannot see"
            elif u & main_changed:
                reason = "used " + min(u & main_changed)
            elif (suite, test) not in base["classes"] and main_changed:
                reason = "no class set recorded"
        for config in configs:
            why = reason
            if why is None:
                b, p = digest(base, suite, test, config), digest(pr, suite, test, config)
                if b is None or p is None:
                    # No digest under this configuration (one that runs every test, or a test the
                    # recording left out): only a change to no compiler class lets it stay.
                    why = "no digest" if digest_changed else None
                elif b != p:
                    why = "digest moved"
            if why is not None:
                out[(config, suite, test)] = why
    return out


def write_config_seconds(path, seconds, runs=None, header=None):
    """The file `read_config_seconds` reads, one line a (configuration, suite), the configuration
    as its run directory names it; `runs` the number of matrix runs each value is from, one for
    every pair where it is not given."""
    lines = header or [
        "# configuration\tsuite\tseconds\truns: a suite's seconds under a configuration that",
        "# ran all of it, as dev/varka_matrix_bins.py times --per-configuration computes them",
    ]
    for (config, suite), value in sorted(seconds.items()):
        lines.append(f"{config}\t{suite}\t{value:.1f}\t{(runs or {}).get((config, suite), 1)}")
    Path(path).write_text("\n".join(lines) + "\n", encoding="utf-8")


class SelfTest(unittest.TestCase):
    def test_every_unit_lands_once_and_no_lane_passes_the_target(self):
        suites = [
            ("sql", [("A", False), ("B", False), ("C", True)]),
            ("catalyst", [("D", False), ("E", True)]),
        ]
        weights = {"A": 100.0, "B": 50.0, "C": 300.0, "D": 10.0, "E": 30.0}
        configs = ["x=1", "y=2", "z=3"]
        out = plan(configs, suites, weights, {}, {"x=1": 2.0}, 700.0)["include"]
        lines = [line for b in out for line in b["units"].split("\n")]
        # Every configuration meets every suite exactly once, the defaults included.
        met = sorted(
            (c, s) for c, _, ss in (line.split("|") for line in lines) for s in ss.split(",")
        )
        expected = sorted((c, s) for c in ["defaults"] + configs for s in "ABCDE")
        self.assertEqual(met, expected)
        self.assertTrue(all(b["seconds"] <= 700 for b in out), out)

    def test_a_unit_longer_than_a_lane_takes_a_lane_of_its_own(self):
        bins = pack(
            [
                ("defaults", "sql", ["Big"], 920.0),
                ("defaults", "sql", ["S"], 25.0),
                ("x=1", "sql", ["S"], 25.0),
            ],
            300.0,
        )
        self.assertEqual(len(bins), 1)
        # The long unit fills one lane alone; the short ones share the other.
        self.assertEqual(sorted(bins[0]["lanes"]), [50.0, 920.0])

    def test_shared_suites_split_into_groups_no_heavier_than_a_lane(self):
        suites = [("sql", [(f"S{i}", False) for i in range(10)])]
        weights = {f"S{i}": 100.0 for i in range(10)}
        got = units([], suites, suite_weight(weights, {}), {}, 320.0)
        self.assertTrue(all(u[3] <= 320.0 + 100.0 for u in got), got)
        self.assertEqual(sorted(s for u in got for s in u[2]), sorted(weights))

    def test_times_are_the_median_over_configurations(self):
        def report(root, config, leaf, suite, seconds):
            d = Path(root, "runs", config, leaf, "junit")
            d.mkdir(parents=True, exist_ok=True)
            # ScalaTest writes the attributes on the line after the tag, hostname before name.
            Path(d, f"TEST-org.x.{suite}.xml").write_text(
                f'<?xml version="1.0"?>\n<testsuite\n hostname="h" name="org.x.{suite}"'
                f' time="{seconds}" tests="1">\n</testsuite>\n',
                encoding="utf-8",
            )

        with tempfile.TemporaryDirectory() as root:
            report(root, "defaults", "sql-b000-0", "A", 10)
            report(root, "x=1", "sql-b001-0", "A", 30)
            # One configuration's two leaves of a suite add up before the median.
            report(root, "y=2", "sql-b002-0", "A", 15)
            report(root, "y=2", "sql-b003-0", "A", 25)
            report(root, "x=1", "sql-b001-0", "B", 4)
            self.assertEqual(times([root]), {"A": 30.0, "B": 4.0})

    def test_times_count_only_whole_runs_of_a_recorded_suite(self):
        with tempfile.TemporaryDirectory() as root:
            for config, suite, secs in [
                ("defaults", "A", 100),
                ("x=1", "A", 20),
                ("w=4", "A", 120),
                ("defaults", "B", 10),
                ("x=1", "B", 30),
            ]:
                d = Path(root, "runs", config, "sql-0", "junit")
                d.mkdir(parents=True, exist_ok=True)
                Path(d, f"TEST-p.{suite}.xml").write_text(
                    f'<testsuite name="p.{suite}" time="{secs}" tests="2"/>'
                )
            # x=1 is covered and runs one of A's two tests; w=4 is not covered, so runs A whole;
            # B has no record, so every configuration ran it whole.
            f = Path(root, "reach.tsv")
            f.write_text("#\tcovers\tx=1\nA\tone\tx=1\nA\ttwo\t-\n")
            got = times([Path(root, "runs")], read_reach(f))
            self.assertEqual(got, {"A": 110.0, "B": 20.0})
            self.assertEqual(times([Path(root, "runs")]), {"A": 100.0, "B": 20.0})

    def test_times_per_configuration_write_only_whole_runs(self):
        with tempfile.TemporaryDirectory() as root:
            for config, suite, secs in [
                ("defaults", "A", 100),
                ("x=1", "A", 20),
                ("w=4", "A", 120),
                ("w=4_v=5", "B", 30),
            ]:
                d = Path(root, "runs", config, "sql-0", "junit")
                d.mkdir(parents=True, exist_ok=True)
                Path(d, f"TEST-p.{suite}.xml").write_text(
                    f'<testsuite name="p.{suite}" time="{secs}" tests="2"/>'
                )
            f = Path(root, "reach.tsv")
            f.write_text("#\tcovers\tx=1\nA\tone\tx=1\nA\ttwo\t-\n")
            got = whole_runs([Path(root, "runs")], read_reach(f))
            # x=1 ran one of A's two tests, so it has no whole run of A to record.
            self.assertEqual(
                got, {("defaults", "A"): 100.0, ("w=4", "A"): 120.0, ("w=4_v=5", "B"): 30.0}
            )
            out = Path(root, "config-suite-seconds.tsv")
            write_config_seconds(out, got)
            # The planner reads the comma configuration back by its run directory.
            self.assertEqual(read_config_seconds(out)[(run_dir("w=4,v=5"), "B")], 30.0)
            self.assertEqual(read_config_seconds(out), got)

    def test_a_measured_configuration_weighs_its_own_seconds(self):
        suites = [("sql", [("A", True), ("B", True)])]
        measured = {("defaults", "A"): 300.0, ("x=1", "A"): 400.0, ("x=1_y=2", "B"): 90.0}
        got = units(
            ["x=1", "x=1,y=2", "z=3"],
            suites,
            lambda s: 100.0,
            {"x=1": 2.0, "x=1,y=2": 1.5, "z=3": 3.0},
            480.0,
            config_seconds=measured,
        )
        ran = {(u[0], u[2][0]): u[3] - JVM_START for u in got}
        # A measured pair takes its seconds, the factor aside, the defaults included.
        self.assertEqual(ran[("defaults", "A")], 300.0)
        self.assertEqual(ran[("x=1", "A")], 400.0)
        self.assertEqual(ran[("x=1,y=2", "B")], 90.0)
        # A pair the file does not list falls back to the weight times the factor.
        self.assertEqual(ran[("defaults", "B")], 100.0)
        self.assertEqual(ran[("x=1", "B")], 200.0)
        self.assertEqual(ran[("x=1,y=2", "A")], 150.0)
        self.assertEqual(ran[("z=3", "A")], 300.0)

    def test_a_measured_configuration_still_runs_only_its_share(self):
        with tempfile.TemporaryDirectory() as root:
            f = Path(root, "reach.tsv")
            f.write_text("#\tcovers\tx=1\nA\tone\tx=1\nA\ttwo\t-\n", encoding="utf-8")
            reach = read_reach(f)
        suites = [("sql", [("A", True)])]
        got = units(["x=1"], suites, lambda s: 100.0, {}, 480.0, reach, {("x=1", "A"): 400.0})
        # The record lets one of A's two tests through: half of A's measured whole run.
        self.assertEqual({(u[0], u[2][0]): u[3] for u in got}[("x=1", "A")], JVM_START + 200.0)

    def test_a_session_only_configuration_leaves_catalyst_out(self):
        suites = [("sql", [("S", False)]), ("catalyst", [("C", False)])]
        got = units(["arrowBatchSize=1", "cse=false"], suites, lambda s: 10.0, {}, 480.0)
        modules = {(c, m) for c, m, _, _ in got}
        self.assertNotIn(("arrowBatchSize=1", "catalyst"), modules)
        self.assertIn(("arrowBatchSize=1", "sql"), modules)
        self.assertIn(("cse=false", "catalyst"), modules)
        self.assertIn(("defaults", "catalyst"), modules)
        self.assertFalse(reaches("arrowBatchSize=17", "catalyst"))
        self.assertTrue(reaches("arrowBatchSize=1,cse=false", "catalyst"))

    def test_a_configuration_drops_the_suites_its_record_leaves_to_the_defaults(self):
        with tempfile.TemporaryDirectory() as root:
            f = Path(root, "reach.tsv")
            f.write_text(
                "#\tcovers\tx=1,y=2,w=4\nA\tone\tx=1\nA\ttwo\t-\nB\tone\t-\nB\t*\tw=4\n",
                encoding="utf-8",
            )
            reach = read_reach(f)
        suites = [("sql", [("A", True), ("B", True), ("C", True)])]
        got = units(["x=1", "y=2", "z=3"], suites, lambda s: 100.0, {}, 480.0, reach)
        ran = {(u[0], u[2][0]): u[3] for u in got}
        # x=1 moves one of A's two tests: half of A, no B; y=2 moves nothing it covers; z=3 is
        # not covered, so it runs everything; C has no record, so every configuration runs it.
        self.assertEqual(ran[("x=1", "A")], JVM_START + 50.0)
        self.assertNotIn(("x=1", "B"), ran)
        self.assertNotIn(("y=2", "A"), ran)
        self.assertEqual({s for c, s in ran if c == "z=3"}, {"A", "B", "C"})
        self.assertEqual({c for c, s in ran if s == "C"}, {"defaults", "x=1", "y=2", "z=3"})
        # B's set-up moved under w=4, so all of B runs there.
        got = units(["w=4"], suites, lambda s: 100.0, {}, 480.0, reach)
        self.assertIn(("w=4", "B"), {(u[0], u[2][0]) for u in got})

    def test_a_broken_record_is_none(self):
        with tempfile.TemporaryDirectory() as root:
            f = Path(root, "reach.tsv")
            f.write_text("A\tone\t-\n#\tbroken\tboom\n", encoding="utf-8")
            self.assertIsNone(read_reach(f))

    def test_a_suite_no_file_lists_weighs_the_median(self):
        w = suite_weight({}, {"A": 10.0, "B": 30.0, "C": 50.0})
        self.assertEqual(w("New"), 30.0)

    # -- selection (VARKA-508) ------------------------------------------------------------------

    def _use(self, root, name, lines):
        d = Path(root, name)
        d.mkdir(parents=True, exist_ok=True)
        Path(d, "use-1.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")
        return read_use(d)

    K = "org.apache.spark.sql.varka.vector.Kernels"
    H = "org.apache.spark.sql.varka.vector.Helpers"
    E = "org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaLoopEmitter"

    def _records(self, root, pr_digest="d1", pr_extra=()):
        common = [
            f"U\tS\t{self.K},{self.E}\tuses kernels",
            f"U\tS\t{self.H},{self.E}\tuses helpers",
            f"U\tS\t{self.E}\t*",
            "U\t*\t-\t*",
            "U\tT\t*\tchild",
        ]
        base = self._use(
            root,
            "base",
            common
            + [
                "D\tS\tdefaults\td0\tuses kernels",
                "D\tS\tx=1\td1\tuses kernels",
                "D\tS\tdefaults\td0\tuses helpers",
                "D\tS\tx=1\td1\tuses helpers",
                "D\tT\tdefaults\td0\tchild",
            ],
        )
        pr = self._use(
            root,
            "pr",
            common
            + [
                "D\tS\tdefaults\td0\tuses kernels",
                f"D\tS\tx=1\t{pr_digest}\tuses kernels",
                "D\tS\tdefaults\td0\tuses helpers",
                "D\tS\tx=1\td1\tuses helpers",
                "D\tT\tdefaults\td0\tchild",
            ]
            + list(pr_extra),
        )
        return base, pr

    def test_a_changed_runtime_class_reruns_only_the_tests_that_used_it(self):
        with tempfile.TemporaryDirectory() as root:
            base, pr = self._records(root)
            path = "sql/varka/engine/target/scala-2.13/classes/" + self.K.replace(".", "/")
            got = select(base, pr, [path + ".class"], ["x=1", "fusedCeiling=200"])
        self.assertEqual(
            set(got),
            {
                (c, s, t)
                for c in ["defaults", "x=1", "fusedCeiling=200"]
                for s, t in [("S", "uses kernels"), ("T", "child")]
            },
        )
        self.assertEqual(got[("x=1", "S", "uses kernels")], f"used {self.K}")

    def test_an_inner_class_is_its_own_class(self):
        with tempfile.TemporaryDirectory() as root:
            base, pr = self._records(root)
            got = select(base, pr, [f"classes/{self.K.replace('.', '/')}$Inner.class"], ["x=1"])
        # No test ran the inner class; only the test whose child JVM the record cannot see reruns.
        self.assertEqual({(s, t) for _, s, t in got}, {("T", "child")})

    def test_a_compiler_change_reruns_only_where_the_digest_moved(self):
        with tempfile.TemporaryDirectory() as root:
            base, pr = self._records(root, pr_digest="d2")
            got = select(base, pr, [self.E], ["x=1", "fusedCeiling=200"])
        # x=1's digest of one test moved; fusedCeiling=200 has no digest, so a compiler change
        # reruns it; the child JVM's test reruns everywhere.
        self.assertEqual(got[("x=1", "S", "uses kernels")], "digest moved")
        self.assertNotIn(("x=1", "S", "uses helpers"), got)
        self.assertNotIn(("defaults", "S", "uses kernels"), got)
        self.assertEqual(got[("fusedCeiling=200", "S", "uses helpers")], "no digest")
        self.assertIn(("x=1", "T", "child"), got)

    def test_a_digest_that_moved_reruns_without_any_class_change(self):
        with tempfile.TemporaryDirectory() as root:
            base, pr = self._records(root, pr_digest="d2")
            got = select(base, pr, [], ["x=1"])
        self.assertEqual(set(got), {("x=1", "S", "uses kernels")})

    def test_a_suites_own_class_reruns_the_suite_and_another_test_class_everything(self):
        with tempfile.TemporaryDirectory() as root:
            base, pr = self._records(root)
            own = select(base, pr, ["x/test-classes/org/p/S$$anonfun$1.class"], ["x=1"])
            other = select(base, pr, ["x/test-classes/org/p/VarkaHarness.class"], ["x=1"])
        self.assertEqual(
            {(s, t) for _, s, t in own}, {("S", "uses kernels"), ("S", "uses helpers")}
        )
        self.assertEqual(set(other), {EVERYTHING})

    def test_a_kernel_referenced_compiler_class_is_not_digested(self):
        with tempfile.TemporaryDirectory() as root:
            base, pr = self._records(root, pr_extra=[f"#\tkernel-refs\t{self.E}"])
            got = select(base, pr, [self.E], ["x=1"])
        self.assertIn(("x=1", "S", "uses helpers"), got, "a kernel calls it as it runs")

    def test_a_new_test_and_a_class_outside_varka(self):
        with tempfile.TemporaryDirectory() as root:
            base, pr = self._records(root, pr_extra=["U\tS\t-\tnew one", "D\tS\tx=1\td9\tnew one"])
            got = select(base, pr, [], ["x=1"])
            self.assertEqual(got[("defaults", "S", "new one")], "new test")
            outside = select(base, pr, ["classes/org/apache/spark/sql/Dataset.class"], ["x=1"])
        self.assertEqual(set(outside), {EVERYTHING})

    def test_a_missing_or_broken_record_reruns_everything(self):
        with tempfile.TemporaryDirectory() as root:
            base, pr = self._records(root)
            broken = self._use(root, "broken", ["U\tS\t-\tt", "#\tbroken\tboom"])
            self.assertIsNone(broken)
            self.assertIsNone(read_use(Path(root, "absent")))
            self.assertEqual(set(select(None, pr, [], ["x=1"])), {EVERYTHING})
            self.assertEqual(set(select(base, broken, [], ["x=1"])), {EVERYTHING})

    def test_the_selection_as_a_reach_record_the_planner_reads(self):
        with tempfile.TemporaryDirectory() as root:
            base, pr = self._records(root, pr_digest="d2")
            got = select(base, pr, [], ["x=1"])
            reach = Path(root, "reach.tsv")
            reach.write_text("#\tcovers\tx=1\nS\tuses kernels\tx=1\nS\tuses helpers\t-\n")
            out = Path(root, "selected.tsv")
            out.write_text("\n".join(reach_record(got, pr, reach)) + "\n")
            covered, tests = read_reach(out)
            self.assertEqual(covered, {"x=1"})
            self.assertEqual(tests["S"], [set(), {"x=1"}], "helpers, then kernels, by name")
            # A selection of everything is a broken record, which reruns everything.
            out.write_text("\n".join(reach_record({EVERYTHING: "x"}, pr)) + "\n")
            self.assertIsNone(read_reach(out))

    def test_tree_diff_finds_changed_added_and_removed_class_files(self):
        with tempfile.TemporaryDirectory() as root:
            for side, files in [
                ("a", {"p/A.class": b"1", "p/B.class": b"2", "p/C.class": b"3"}),
                ("b", {"p/A.class": b"1", "p/B.class": b"9", "p/D.class": b"4"}),
            ]:
                for name, data in files.items():
                    f = Path(root, side, "classes", name)
                    f.parent.mkdir(parents=True, exist_ok=True)
                    f.write_bytes(data)
            got = tree_diff(Path(root, "a"), Path(root, "b"))
        self.assertEqual(got, ["classes/p/B.class", "classes/p/C.class", "classes/p/D.class"])


def reach_record(selected, pr, reach=None):
    """The selection as a `VarkaTestReach` record, which `plan --reach` and a configuration's JVM
    (`-Dvarka.matrix.reachFile`) read unchanged: each test's line names the configurations that
    rerun it, cut to those the pull request's own reach record says move it, since the defaults
    run in the pull request's Build checks a test that a configuration emits alike. Everything
    reruns, through a broken record, when the selection is everything."""
    if EVERYTHING in selected:
        return ["#\tbroken\tselect: " + selected[EVERYTHING]]
    covered = sorted({c for d in pr["digests"].values() for c in d} - {DEFAULTS})
    moving = None
    if reach is not None:
        moving = {}
        for line in Path(reach).read_text(encoding="utf-8").splitlines():
            parts = line.split("\t")
            if line.startswith("#\tbroken"):
                return ["#\tbroken\tthe pull request's reach record is broken"]
            if line.startswith("#") or len(parts) < 3:
                continue
            got = set() if parts[-1] == "-" else set(parts[-1].split(","))
            key = (parts[0], "\t".join(parts[1:-1]))
            moving.setdefault(key, set()).update(got)
    lines = ["#\tcovers\t" + ",".join(covered)]
    tests = sorted({(s, t) for s, t in pr["digests"] if s != "*" and t != "*"})
    for suite, test in tests:
        rerun = {c for (c, s, t) in selected if (s, t) == (suite, test) and c != DEFAULTS}
        if moving is not None:
            moved = moving.get((suite, test), {"*"}) | moving.get((suite, "*"), set())
            moved |= moving.get(("*", "*"), set())
            if "*" not in moved:
                rerun &= moved
        lines.append(f"{suite}\t{test}\t{','.join(sorted(rerun)) or '-'}")
    return lines


def select_main(args):
    configurations = [
        c.strip()
        for c in Path(args.configurations).read_text(encoding="utf-8").splitlines()
        if c.strip()
    ]
    if args.trees:
        changed = tree_diff(*args.trees)
    else:
        changed = [
            line.strip()
            for line in Path(args.changed).read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
    base, pr = read_use(args.base), read_use(args.pr)
    selected = select(base, pr, changed, configurations)
    for (config, suite, test), why in sorted(selected.items()):
        print(f"{config}\t{suite}\t{why}\t{test}")
    if args.reach_out:
        out = reach_record(selected, pr, args.reach) if pr is not None else ["#\tbroken\tno record"]
        Path(args.reach_out).write_text("\n".join(out) + "\n", encoding="utf-8")
    pairs = len({(s, t) for _, s, t in selected})
    print(
        f"{len(changed)} changed class files; {len(selected)} units of {pairs} tests rerun",
        file=sys.stderr,
    )
    return 0


def main(argv):
    if argv[:1] == ["--selftest"]:
        result = unittest.main(argv=[sys.argv[0]], exit=False, verbosity=0).result
        ok = result.wasSuccessful()
        print("varka_matrix_bins selftest: " + ("ok" if ok else "FAILED"))
        return 0 if ok else 1
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("plan")
    p.add_argument("--configurations", required=True)
    p.add_argument("--suites", action="append", required=True, metavar="MODULE=FILE")
    p.add_argument(
        "--target",
        type=float,
        default=480.0,
        help="a lane's seconds on a fast runner (default 480)",
    )
    p.add_argument("--reach", help="the Build's record of the tests each configuration moves")
    t = sub.add_parser("times")
    t.add_argument("dirs", nargs="+")
    t.add_argument("--reach", help="the run's record: only whole runs of a suite count")
    t.add_argument(
        "--per-configuration",
        metavar="FILE",
        help="also write each suite's whole runs under each configuration to FILE",
    )
    s = sub.add_parser("select")
    s.add_argument("--base", required=True, help="master's VarkaTestUse record (a directory)")
    s.add_argument("--pr", required=True, help="the pull request's record")
    s.add_argument("--configurations", required=True)
    changes = s.add_mutually_exclusive_group(required=True)
    changes.add_argument("--changed", help="a file of changed class files, one path a line")
    changes.add_argument("--trees", nargs=2, metavar=("OLD", "NEW"), help="two class trees")
    s.add_argument("--reach", help="the pull request's VarkaTestReach record, for --reach-out")
    s.add_argument("--reach-out", help="write the selection as a reach record the matrix reads")
    args = parser.parse_args(argv)
    if args.cmd == "select":
        return select_main(args)
    if args.cmd == "times":
        reach = read_reach(args.reach) if args.reach and Path(args.reach).is_file() else None
        for name, seconds in sorted(times(args.dirs, reach).items()):
            print(f"{name}\t{seconds:.1f}")
        if args.per_configuration:
            write_config_seconds(args.per_configuration, whole_runs(args.dirs, reach))
        return 0
    configurations = [
        c.strip()
        for c in Path(args.configurations).read_text(encoding="utf-8").splitlines()
        if c.strip()
    ]
    suites = []
    for entry in args.suites:
        module, path = entry.split("=", 1)
        suites.append((module, read_suites(path)))
    out = plan(
        configurations,
        suites,
        read_tsv(SUITE_SECONDS),
        read_tsv(SUITE_TIMES),
        read_tsv(CONFIG_WEIGHTS),
        args.target,
        read_reach(args.reach) if args.reach else None,
        read_config_seconds(CONFIG_SUITE_SECONDS),
    )
    json.dump(out, sys.stdout)
    print()
    total = sum(b["seconds"] for b in out["include"])
    print(
        f"{len(out['include'])} bins, the longest {max(b['seconds'] for b in out['include'])} s,"
        f" {total} s in all",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
