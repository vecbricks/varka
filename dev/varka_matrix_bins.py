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
for a suite neither file lists the median, so a new suite is placed as an ordinary one. A suite
a configuration's reach record lets only some tests of run is a part run, weighed by default by
the share of its whole run; `--part-weight part-seconds` weighs it by its measured seconds where
`sql/varka/matrix/config-suite-part-seconds.tsv` lists the pair (VARKA-617, decision 9). `times`
writes the measured files from a fanned-out run's JUnit reports.

  dev/varka_matrix_bins.py plan --configurations FILE --suites MODULE=FILE... \\
      [--target SECONDS] [--part-weight share|part-seconds|alone] > bins.json  # {"include": [...]}
  dev/varka_matrix_bins.py times [--reach FILE] [--per-configuration FILE] [--part-runs FILE] \\
      JUNIT_DIR... > suite-seconds.tsv
  dev/varka_matrix_bins.py select --base DIR --pr DIR --configurations FILE \\
      (--changed FILE | --trees OLD NEW) [--reach FILE --reach-out FILE] > units.tsv
  dev/varka_matrix_bins.py replay RUN_DIR --jobs JOBS --suites MODULE=FILE... [--reach FILE] \\
      [--plan-from REF] [--pairs FILE] [--suite-seconds FILE] [--part-seconds FILE]
  dev/varka_matrix_bins.py --selftest

Each bin's `units` is one line a JVM, `configuration|module|suite,suite,...`, the suites as
simple names, which the bin's job hands to `dev/varka_matrix.sh --suites`.

`replay` (VARKA-617) reads one run's downloaded bin artifacts and its jobs from the Actions API
and prints its measured bin phase beside the phase replayed from its own bins and from the bins
the planner cuts now, each unit at the seconds the run measured, so that the planner is checked
against its own run rather than against another run's bin count and slots.

`select` (VARKA-508) compares master's `VarkaTestUse` record with a pull request's and prints
the (configuration, suite, test) units the pull request's matrix reruns, one
`configuration<TAB>suite<TAB>reason<TAB>test` line each (the test last, as it may hold any
character), or the single unit `*<TAB>*<TAB>reason<TAB>*` for everything. `--reach-out` writes
the same selection as a reach record, which `plan --reach` and the configurations' JVMs read.
"""

import argparse
import contextlib
import hashlib
import io
import json
import re
import statistics
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

SUITE_TIMES = "sql/varka/ci/suite-times.tsv"
SUITE_SECONDS = "sql/varka/matrix/suite-seconds.tsv"
CONFIG_SUITE_SECONDS = "sql/varka/matrix/config-suite-seconds.tsv"
CONFIG_WEIGHTS = "sql/varka/matrix/seconds.tsv"
# A configuration's part runs of a suite, where its reach record let only some tests through
# (VARKA-617), and the suites whose part runs are planned as units of their own.
PART_SECONDS = "sql/varka/matrix/config-suite-part-seconds.tsv"
PART_ALONE = "sql/varka/matrix/part-alone.tsv"
# How `units` weighs a suite a configuration runs part of: `share`, its whole run times the share
# of its tests the record lets through; `part-seconds`, its measured part run where
# PART_SECONDS lists the pair and its share otherwise; `alone`, a suite PART_ALONE lists in a
# unit of its own at its whole run, every other suite by its share. A part run measured at a
# smaller share than today's weighs no less than its share (`units`). `share` stays the default:
# nothing in CI refreshes PART_SECONDS yet, and a stale default would drift on every plan.
PART_WEIGHTS = ("share", "part-seconds", "alone")
PART_WEIGHT = "share"
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

# The platform configurations (VARKA-505): the Varka suites at the defaults under a fixed
# instruction set, whatever the pool's machine is. Each is (runner, JVM flags). A platform changes
# what C2 does with the same bytes, so none of them is cut by the reach record, and a run takes
# its own measured seconds where the pair file has them, else the defaults'. The matrix's own bins
# run on `ubuntu-latest`; a platform's runs go in bins of their own, in the order below.
RUNNER = "ubuntu-latest"
PLATFORMS = {
    "isa=avx2": (RUNNER, "-XX:UseAVX=2 -XX:MaxVectorSize=32"),
    "isa=w128": (RUNNER, "-XX:MaxVectorSize=16"),
    "isa=arm": ("ubuntu-24.04-arm", ""),
}

# The most jobs a GitHub Actions matrix may hold. The bins, the platforms' among them, are one
# job's matrix, so `plan` refuses a cut of more bins than this rather than hand the workflow a
# matrix GitHub rejects.
MATRIX_CAP = 256


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


def read_part_seconds(path):
    """`configuration<TAB>suite<TAB>seconds<TAB>share` lines (`write_part_seconds`) as
    {(run directory, suite): (seconds, share)}, `#` comments and blank lines aside; empty for a
    missing file."""
    out = {}
    p = Path(path)
    if not p.exists():
        return out
    for line in p.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        config, suite, seconds, share = line.split("\t")[:4]
        out[(run_dir(config), suite)] = (float(seconds), float(share))
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


# Separates the configurations a reach record's line names, as `VarkaTestReach.SEPARATOR` does: a
# configuration name joins its settings with commas (`useAVX=2,wideConversionForm=MAGIC`), so a
# comma cannot separate names, and no option, arm or value holds a semicolon.
REACH_SEPARATOR = ";"

# The record's format, on the `#<TAB>format<TAB>` line each writer puts first, as
# `VarkaTestReach.FORMAT`: a record without one of this format, which an older recording wrote with
# commas between names, or with one of another, is broken to both readers, and every test runs.
REACH_FORMAT = "2"

# Where the record's format is written down for both languages' tests to hold to.
REACH_FIXTURE = Path(__file__).resolve().parent.parent / "sql/varka/matrix/reach-format.tsv"


def canonical(config):
    """A configuration's name as the record holds it, as `VarkaTestReach.canonical`: each setting's
    option and value trimmed, the settings in their order."""
    out = []
    for setting in config.split(","):
        name, eq, value = setting.partition("=")
        out.append(f"{name.strip()}={value.strip()}" if eq else setting.strip())
    return ",".join(out)


def reach_names(field):
    """The configurations a reach record's field names, in `canonical` form: none for `-`."""
    return set() if field == "-" else {canonical(c) for c in field.split(REACH_SEPARATOR) if c}


def reach_field(names):
    """The field a record's line names `names` in: `-` for none."""
    return REACH_SEPARATOR.join(sorted(names)) or "-"


def reach_head(covered):
    """A record's first lines: its format and the configurations it covers."""
    return ["#\tformat\t" + REACH_FORMAT, "#\tcovers\t" + REACH_SEPARATOR.join(covered)]


def reach_broken(lines):
    """Why a record's lines are no record, or None: a `#<TAB>broken` line, or no format line of
    `REACH_FORMAT` (a record joined from several recordings has one from each) or one of another
    format."""
    formats = set()
    for line in lines:
        parts = line.split("\t")
        if line.startswith("#") and len(parts) >= 2 and parts[1] == "broken":
            return "its recording marked it broken"
        if line.startswith("#") and len(parts) == 3 and parts[1] == "format":
            formats.add(parts[2])
    if formats != {REACH_FORMAT}:
        other = sorted(formats - {REACH_FORMAT})
        return (
            f"it has a line of format {other[0]}, not {REACH_FORMAT}"
            if other
            else f"it has no line of format {REACH_FORMAT}, so an older recording wrote it"
        )
    return None


def read_reach(path):
    """`VarkaTestReach`'s record: the configurations it covers, from its `#<TAB>covers<TAB>`
    lines, and for each suite the configurations that move each of its tests, with the suite's
    set-up line (`suite<TAB>*`) and the JVM's (`*<TAB>*`) joined to every test they cover. None
    for a record its recording marked broken."""
    covered, tests, setup, everywhere = set(), {}, {}, set()
    lines = Path(path).read_text(encoding="utf-8").splitlines()
    why = reach_broken(lines)
    if why is not None:
        # To stderr: `plan`'s and `times`' output is data.
        print(f"VARKA_REACH_BROKEN\t{path}\t{why}; every test runs", file=sys.stderr)
        return None
    for line in lines:
        parts = line.split("\t")
        if line.startswith("#"):
            if len(parts) == 3 and parts[1] == "covers":
                covered |= reach_names(parts[2])
            continue
        if len(parts) < 3:
            continue
        moved = reach_names(parts[-1])
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
    config = canonical(config)
    covered, tests = reach
    if config not in covered or suite not in tests:
        return 1.0
    moved = tests[suite]
    return sum(config in m or "*" in m for m in moved) / len(moved)


def units(
    configurations,
    suites,
    weight,
    config_weights,
    lane,
    reach=None,
    config_seconds=None,
    platforms=(),
    part_weight=PART_WEIGHT,
    part_seconds=None,
    part_alone=(),
):
    """Every (configuration, module, suites, seconds) JVM the matrix runs, the defaults first. A
    suite's whole run under a configuration takes its measured seconds there (`config_seconds`,
    keyed by the configuration's run directory) or else its weight times the configuration's
    factor. A suite whose tests a configuration's record leaves all to the defaults is not run
    under it, and one it runs some tests of weighs as `part_weight` says (PART_WEIGHTS): by
    default that share of its whole run, a tenth at least, for its set-up."""
    fill = statistics.median(config_weights.values()) if config_weights else 1.0
    config_seconds = config_seconds or {}
    part_seconds = part_seconds or {}
    apart = set(part_alone) if part_weight == "alone" else set()
    out = []
    for config in [DEFAULTS] + list(configurations) + list(platforms):
        platform = config in platforms
        factor = 1.0 if config == DEFAULTS or platform else config_weights.get(config, fill)
        directory = run_dir(config)
        for module, listed in suites:
            if not reaches(config, module):
                continue
            shared = []
            for name, alone in listed:
                share = 1.0 if platform else reach_share(reach, config, name)
                if share == 0.0:
                    continue
                whole = config_seconds.get((directory, name))
                if whole is None:
                    whole = weight(name) * factor
                seconds = whole * max(share, 0.1)
                part = part_seconds.get((directory, name))
                if share < 1.0 and part_weight == "part-seconds" and part is not None:
                    measured, at = part
                    # A record that lets more of the suite's tests through than the one the pair
                    # was measured under, at the file's three decimals, weighs no less than its
                    # share.
                    seconds = measured if round(share, 3) <= at else max(measured, seconds)
                elif share < 1.0 and name in apart:
                    # Its time follows the cases it drew, not the share of its tests that ran.
                    seconds = whole
                if alone or name in apart:
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
    platforms=(),
    part_weight=PART_WEIGHT,
    part_seconds=None,
    part_alone=(),
):
    """The job matrix. The platform configurations named in `platforms` (keys of PLATFORMS) are
    packed apart from the others, one group of bins for each runner and ahead of the matrix's own
    bins, so that they start at once and the matrix keeps its slots; a platform unit's line
    carries its JVM flags as a fourth field."""
    weight = suite_weight(suite_seconds, suite_times)
    all_units = units(
        configurations,
        suites,
        weight,
        config_weights,
        lane,
        reach,
        config_seconds,
        platforms,
        part_weight,
        part_seconds,
        part_alone,
    )
    # The platforms' bins come first, so that they start at once and hold their slots while the
    # matrix's own bins fill the rest.
    groups = []
    for runner in dict.fromkeys(PLATFORMS[p][0] for p in platforms):
        mine = [u for u in all_units if u[0] in platforms and PLATFORMS[u[0]][0] == runner]
        groups.append((runner, mine))
    groups.append((RUNNER, [u for u in all_units if u[0] not in platforms]))
    include = []
    for runner, group in groups:
        for b in pack(group, lane):
            lines = []
            for c, m, s, _ in b["units"]:
                line = f"{c}|{m}|{','.join(s)}"
                lines.append(f"{line}|{PLATFORMS[c][1]}" if c in platforms else line)
            include.append(
                {
                    "bin": f"b{len(include):03d}",
                    "seconds": round(max(b["lanes"])),
                    "runner": runner,
                    "units": "\n".join(lines),
                }
            )
    return {"include": include}


def over_cap(out, target, cap=MATRIX_CAP):
    """Why the job matrix `out` cannot run, or None: more bins than a matrix may hold."""
    count = len(out["include"])
    if count <= cap:
        return None
    return (
        f"{count} bins at a {target:.0f} s lane, over GitHub's cap of {cap} jobs a matrix; raise"
        f" --target until the cut fits, or run fewer configurations"
    )


def run_seconds(junit_dirs, reach=None):
    """Each suite's seconds under each configuration, with the share of its tests the run's reach
    record let the configuration run, as {(run directory, suite): (seconds, share)}, from JUnit
    reports under `<dir>/<configuration>/<module>-.../junit`. The directories are one run's: a
    suite's seconds under a configuration add up over them."""
    # A run directory names a combined configuration with `_` for its commas (`run_dir`), and the
    # record names it whole: the record's own names are mapped to their directories to match.
    named = {run_dir(c): c for c in reach[0]} if reach is not None else {}
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
            key = (config, name.group(1).rsplit(".", 1)[-1])
            per_config[key] = per_config.get(key, 0.0) + float(seconds.group(1))
    return {
        (config, suite): (seconds, reach_share(reach, named.get(config, config), suite))
        for (config, suite), seconds in per_config.items()
    }


def whole_runs(junit_dirs, reach=None):
    """Each suite's seconds under each configuration that ran every test of it, the defaults
    among them, as {(run directory, suite): seconds} (`run_seconds`). With the run's reach
    record, a configuration that ran only the tests the record let through is left out: its time
    is a share of the suite's, and the planner applies that share to the weight itself (`units`),
    so counting it here too would shrink the weight twice (VARKA-508)."""
    runs = run_seconds(junit_dirs, reach)
    return {key: seconds for key, (seconds, share) in runs.items() if share == 1.0}


def part_runs(junit_dirs, reach=None):
    """Each suite's seconds under each configuration that ran only some of its tests, as
    {(run directory, suite): (seconds, share)} (`run_seconds`): the part runs `whole_runs` leaves
    out, which `units` reads under `--part-weight part-seconds`. None without a reach record,
    under which every configuration runs every test."""
    runs = run_seconds(junit_dirs, reach)
    return {key: got for key, got in runs.items() if 0.0 < got[1] < 1.0}


def times(junit_dirs, reach=None):
    """Each suite's seconds run whole, the median over the configurations that ran every test of
    it (`whole_runs`). The configurations' runs land on the pool's mix of runners, so the median
    is a run's typical cost of the suite rather than the cost on the fastest or the slowest
    machine."""
    runs = {}
    for (config, suite), seconds in whole_runs(junit_dirs, reach).items():
        if config in PLATFORMS:
            continue  # a platform's runner (Arm) would skew the median; `units` times it by itself
        runs.setdefault(suite, []).append(seconds)
    return {suite: statistics.median(seconds) for suite, seconds in runs.items()}


# ---------------------------------------------------------------------------------------------
# Replay: a run's bin phase as it ran and as the planner would cut it now (VARKA-617)
# ---------------------------------------------------------------------------------------------

# A bin job's name in the Actions API, which carries the planner's bin.
BIN_JOB = re.compile(r"Option matrix: (b\d{3})")
RUN_STEP = "Run this bin's units"


def junit_suites(leaf):
    """{suite: seconds} of one JVM's directory, from its JUnit reports' `<testsuite>` tags."""
    out = {}
    for xml in sorted(Path(leaf, "junit").glob("TEST-*.xml")):
        head = xml.read_text(encoding="utf-8", errors="replace")[:4000]
        tag = re.search(r"<testsuite\b[^>]*>", head)
        if not tag:
            continue
        name = re.search(r'\bname="([^"]+)"', tag.group(0))
        seconds = re.search(r'\btime="([0-9.]+)"', tag.group(0))
        if name and seconds:
            suite = name.group(1).rsplit(".", 1)[-1]
            out[suite] = out.get(suite, 0.0) + float(seconds.group(1))
    return out


def read_bin_runs(root):
    """One run's bins from its downloaded bin artifacts under `root` (`gh run download` puts each
    in a directory of its own, the report job merges them; both layouts are read): for each bin,
    its units in the order the bin's job started them, each (k, configuration, module, {suite:
    JUnit seconds}, measured seconds). A unit's seconds are its JVM's wall time, which
    `dev/varka_matrix.sh` writes beside its reports."""
    found = {}
    for seconds in Path(root).glob("**/b[0-9][0-9][0-9]-u*/runs/*/*/seconds"):
        leaf = seconds.parent
        unit = leaf.parent.parent.parent.name
        bin_name, k = unit.split("-u", 1)
        if not k.isdigit():
            continue
        named = leaf.parent / "name"
        config = named.read_text(encoding="utf-8").strip() if named.exists() else leaf.parent.name
        module = leaf.name.split("-", 1)[0]
        key = (bin_name, int(k))
        _, _, _, suites, total = found.get(key, (0, config, module, {}, 0.0))
        for suite, s in junit_suites(leaf).items():
            suites[suite] = suites.get(suite, 0.0) + s
        found[key] = (int(k), config, module, suites, total + float(seconds.read_text().strip()))
    out = {}
    for (bin_name, _), unit in sorted(found.items()):
        out.setdefault(bin_name, []).append(unit)
    return out


def unfinished_units(root):
    """The JVMs under `root` that left a `run.log` but no `seconds`, as `b003-u2/config/module`:
    `dev/varka_matrix.sh` writes `seconds` once a JVM exits, failed or not, so these are the
    units a cancel or the job's timeout killed, which `read_bin_runs` cannot time."""
    out = set()
    for log in Path(root).glob("**/b[0-9][0-9][0-9]-u*/runs/*/*/run.log"):
        leaf = log.parent
        if not (leaf / "seconds").exists():
            out.add(f"{leaf.parent.parent.parent.name}/{leaf.parent.name}/{leaf.name}")
    return sorted(out)


def epoch(stamp):
    """An Actions API time stamp, `2026-11-14T21:20:38Z`, as seconds."""
    return datetime.fromisoformat(stamp.replace("Z", "+00:00")).timestamp()


def json_jobs(text):
    """The jobs in a saved jobs listing: one job a line (`--jq '.jobs[]'`), the API's pages
    (`{"total_count": N, "jobs": [...]}`) one after another as `--paginate` prints them, compact
    or pretty-printed, or a list of either."""
    decoder = json.JSONDecoder()
    values, at = [], 0
    while True:
        while at < len(text) and text[at].isspace():
            at += 1
        if at == len(text):
            break
        value, at = decoder.raw_decode(text, at)
        values.append(value)
    jobs = []
    while values:
        value = values.pop(0)
        if isinstance(value, list):
            values[:0] = value
        elif isinstance(value, dict) and isinstance(value.get("jobs"), list):
            jobs.extend(value["jobs"])
        elif isinstance(value, dict):
            jobs.append(value)
    return jobs


def read_bin_jobs(path):
    """The run's bin jobs from the Actions API's jobs of a run, saved by `gh api
    .../actions/runs/RUN/jobs?filter=all --paginate`, with or without `--jq '.jobs[]'`
    (`json_jobs`): for each bin its last attempt's created, started and completed times and its
    units step's start and end, as seconds. Bins whose last attempts differ, after a re-run of
    some jobs, are refused: the run's phase and slots would span both attempts."""
    try:
        jobs = json_jobs(Path(path).read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        raise SystemExit(f"replay: {path} is no saved jobs listing: {e}") from None
    out = {}
    for job in jobs:
        m = BIN_JOB.fullmatch(job.get("name", ""))
        if not m or not job.get("started_at") or not job.get("completed_at"):
            continue
        attempt = job.get("run_attempt", 1)
        if m.group(1) in out and out[m.group(1)]["attempt"] > attempt:
            continue
        step = next((s for s in job.get("steps", []) if s["name"] == RUN_STEP), None)
        ran = step is not None and step.get("started_at") and step.get("completed_at")
        out[m.group(1)] = {
            "attempt": attempt,
            "created": epoch(job["created_at"]),
            "started": epoch(job["started_at"]),
            "completed": epoch(job["completed_at"]),
            "run_start": epoch(step["started_at"]) if ran else None,
            "run_end": epoch(step["completed_at"]) if ran else None,
        }
    attempts = {}
    for b, job in sorted(out.items()):
        attempts.setdefault(job["attempt"], []).append(b)
    if len(attempts) > 1:
        kept = "; ".join(
            f"attempt {a}: {len(bs)} bins, {', '.join(bs[:4])}{', ...' if len(bs) > 4 else ''}"
            for a, bs in sorted(attempts.items())
        )
        raise SystemExit(
            f"replay: the bin jobs in {path} come from more than one attempt ({kept}), so their"
            " phase and slots would span them; replay a run none of whose jobs was re-run"
        )
    return out


def lanes_time(seconds, lanes=LANES):
    """The wall time of units run in this order on `lanes` lanes, each started as a lane frees,
    as a bin's job runs them (`wait -n` on its two JVMs)."""
    free = [0.0] * lanes
    for s in seconds:
        k = min(range(lanes), key=lambda i: free[i])
        free[k] += s
    return max(free)


def phase_time(durations, slots, wait):
    """The bin phase of jobs started in this order on `slots` slots, each waiting `wait` seconds
    for a runner once its slot is free: the first job's start to the last job's end."""
    if not durations:
        return 0.0
    free = [0.0] * slots
    end = 0.0
    for d in durations:
        k = min(range(slots), key=lambda i: free[i])
        free[k] += wait + d
        end = max(end, free[k])
    return end - wait


def run_shape(jobs):
    """A run's measured bin phase, its slots (the most bin jobs it ran at once, max-parallel where
    the run filled it) and its median wait for a runner once a slot was free: a job's start less
    the end that freed its slot, its creation for the first wave, as every bin job is created when
    the build ends and waits for a slot of `max-parallel` first."""
    starts = sorted(j["started"] for j in jobs.values())
    ends = sorted(j["completed"] for j in jobs.values())
    events = sorted([(s, 1) for s in starts] + [(e, -1) for e in ends])
    running = slots = 0
    for _, d in events:
        running += d
        slots = max(slots, running)
    created = sorted(j["created"] for j in jobs.values())
    waits = [starts[i] - (created[i] if i < slots else ends[i - slots]) for i in range(len(starts))]
    return ends[-1] - starts[0], slots, statistics.median(waits)


def job_overhead(job):
    """A bin job's seconds outside its units step: the set-up before it and the upload after."""
    if job["run_start"] is None:
        return job["completed"] - job["started"]
    return (job["run_start"] - job["started"]) + (job["completed"] - job["run_end"])


def replay(
    bins,
    jobs,
    suites,
    planner,
    target=480.0,
    reach=None,
    slots=None,
    part_weights=(PART_WEIGHT,),
):
    """The run's measured bin phase beside its replays. `bins` and `jobs` are the run's
    (`read_bin_runs`, `read_bin_jobs`), `suites` its modules' suites as `plan` takes them and
    `planner` the files the replanned cut reads: (suite seconds, PR suite times, configuration
    weights, configuration pair seconds, part-run seconds, suites planned apart). Each replay
    starts the jobs in the plan's order on the run's slots, each after the run's median wait for
    a runner.

    * `jobs as run` times the run's own jobs at their measured lengths: the slot model alone.
    * `as run` times the run's own bins, each its measured set-up and upload plus its units
      at their measured seconds on two lanes in the order they ran: the bin model too.
    * `replanned` cuts the run's configurations and suites afresh with `planner`, and times each
      unit at the run's measured JUnit seconds of its suites plus the run's median JVM overhead
      (a unit's seconds less its suites'), each bin at the run's median set-up and upload, once
      for each way of weighing a part run in `part_weights`. A suite the run has no seconds for
      under a configuration counts as none and is counted."""
    measured, run_slots, wait = run_shape(jobs)
    slots = slots or run_slots
    order = sorted(jobs)
    as_jobs = phase_time([jobs[b]["completed"] - jobs[b]["started"] for b in order], slots, wait)
    lanes_run = {b: lanes_time([u[4] for u in bins.get(b, [])]) for b in order}
    as_run = phase_time([job_overhead(jobs[b]) + lanes_run[b] for b in order], slots, wait)
    jvm = statistics.median(u[4] - sum(u[3].values()) for us in bins.values() for u in us)
    # Over the jobs that reached their units step: one that stopped short counts its whole length
    # as overhead, which is not a replanned bin's set-up (`replay_inputs` makes sure one did).
    setup = statistics.median(job_overhead(j) for j in jobs.values() if j["run_start"] is not None)
    pairs = {}
    for us in bins.values():
        for _, config, _, ran, _ in us:
            for suite, s in ran.items():
                pairs[(run_dir(config), suite)] = pairs.get((run_dir(config), suite), 0.0) + s
    configs = sorted({u[1] for us in bins.values() for u in us} - {DEFAULTS} - set(PLATFORMS))
    platforms = tuple(p for p in PLATFORMS if any(u[1] == p for us in bins.values() for u in us))
    replanned, lanes_plan, missing = {}, {}, {}
    for weighing in part_weights:
        cut = plan(
            configs,
            suites,
            *planner[:3],
            target,
            reach,
            planner[3],
            platforms,
            weighing,
            *planner[4:],
        )["include"]
        missing[weighing] = 0
        lanes_plan[weighing] = []
        for b in cut:
            seconds = []
            for line in b["units"].split("\n"):
                config, _, names = line.split("|")[:3]
                got = [pairs.get((run_dir(config), s)) for s in names.split(",")]
                missing[weighing] += sum(g is None for g in got)
                seconds.append(jvm + sum(g or 0.0 for g in got))
            lanes_plan[weighing].append(lanes_time(seconds))
        replanned[weighing] = phase_time([setup + s for s in lanes_plan[weighing]], slots, wait)
    return {
        "measured": measured,
        "slots": slots,
        "wait": wait,
        "setup": setup,
        "jvm": jvm,
        "jobs as run": as_jobs,
        "as run": as_run,
        "replanned": replanned,
        "bins as run": [lanes_run[b] for b in order],
        "bins replanned": lanes_plan,
        "missing pairs": missing,
        "no artifact": sorted(set(order) - set(bins)),
    }


def replay_inputs(run, jobs_path):
    """`replay`'s run: its bins (`read_bin_runs`), its bin jobs (`read_bin_jobs`) and the units a
    cancel or a timeout left untimed (`unfinished_units`). Inputs that match nothing, a wrong
    directory or another run's jobs, are refused with what was found where."""
    bins, jobs = read_bin_runs(run), read_bin_jobs(jobs_path)
    found = (
        f"{len(jobs)} bin jobs in {jobs_path}, {sum(map(len, bins.values()))} timed units"
        f" of {len(bins)} bins under {run}"
    )
    if not jobs or not bins:
        raise SystemExit(f"replay: nothing to replay: {found}")
    if not set(jobs) & set(bins):
        raise SystemExit(f"replay: no bin job has units, so the two are not one run's: {found}")
    if all(j["run_start"] is None for j in jobs.values()):
        raise SystemExit(f"replay: no bin job reached its step {RUN_STEP!r}: {found}")
    return bins, jobs, unfinished_units(run)


def planner_files(ref=None, pairs=None, suite_seconds=None, part_seconds=None, repo=None):
    """The files the planner reads, at git revision `ref` of the repository `repo` (this one by
    default) or as the working tree has them, with `pairs`, `suite_seconds` and `part_seconds`
    read from the files given instead. A file the revision lacks reads as empty, as a missing
    file does; a ref that is no commit, any other failure of git, and a given file that does not
    exist are refused, since each would otherwise plan by fill values alone and look like an
    answer."""
    for given in (pairs, suite_seconds, part_seconds):
        if given is not None and not Path(given).is_file():
            raise SystemExit(f"replay: no file {given}")
    repo = repo or Path(__file__).resolve().parent.parent

    def git(*argv):
        return subprocess.run(["git", *argv], cwd=repo, capture_output=True, text=True, check=False)

    if ref is not None:
        checked = git("rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}")
        if checked.returncode != 0:
            raise SystemExit(f"replay: --plan-from {ref} is no commit in {repo}")
        ref = checked.stdout.strip()
    with tempfile.TemporaryDirectory() as tmp:

        def at(path):
            if ref is None:
                return path
            f = Path(tmp, Path(path).name)
            if git("cat-file", "-e", f"{ref}:{path}").returncode != 0:
                f.write_text("", encoding="utf-8")
                return f
            shown = git("show", f"{ref}:{path}")
            if shown.returncode != 0:
                raise SystemExit(f"replay: git show {ref}:{path} failed: {shown.stderr.strip()}")
            f.write_text(shown.stdout, encoding="utf-8")
            return f

        return (
            read_tsv(suite_seconds or at(SUITE_SECONDS)),
            read_tsv(at(SUITE_TIMES)),
            read_tsv(at(CONFIG_WEIGHTS)),
            read_config_seconds(pairs or at(CONFIG_SUITE_SECONDS)),
            read_part_seconds(part_seconds or at(PART_SECONDS)),
            read_names(at(PART_ALONE)),
        )


def replay_lines(got, over=600.0, unfinished=()):
    """`replay`'s result as the lines `replay` prints, minutes for phases and seconds for bins,
    with the units a cancel or a timeout left untimed (`unfinished_units`), which "as run"
    leaves out."""

    def bins(seconds):
        return (
            f"{len(seconds)} bins, the longest {max(seconds, default=0):.0f} s,"
            f" {sum(s > over for s in seconds)} over {over:.0f} s"
        )

    head = [
        f"slots {got['slots']}, a runner's median wait {got['wait']:.0f} s",
        f"a bin job's median set-up and upload {got['setup']:.0f} s,"
        f" a JVM's median overhead {got['jvm']:.1f} s",
        f"measured bin phase       {got['measured'] / 60:6.1f} min",
        f"jobs as run              {got['jobs as run'] / 60:6.1f} min",
        f"as run                   {got['as run'] / 60:6.1f} min   {bins(got['bins as run'])}",
    ]
    replanned = [
        f"replanned, {w:13} {got['replanned'][w] / 60:6.1f} min   {bins(got['bins replanned'][w])}"
        for w in got["replanned"]
    ]
    missing = ", ".join(f"{w} {n}" for w, n in got["missing pairs"].items())
    return (
        head
        + replanned
        + [
            f"pairs the run has no seconds for: {missing}",
            f"bin jobs with no artifact: {', '.join(got['no artifact']) or 'none'}",
            f"units with a run.log but no seconds, left out of as run: {len(unfinished)}"
            + "".join(f"\n  {u}" for u in unfinished),
        ]
    )


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


def write_part_seconds(path, runs, header=None):
    """The file of part runs, `part_runs`' {(run directory, suite): (seconds, share)}, one line a
    pair with the share of the suite's tests the configuration ran, as `read_part_seconds` reads
    it."""
    lines = header or [
        "# configuration\tsuite\tseconds\tshare: a suite's seconds under a configuration that",
        "# ran only some of its tests, as dev/varka_matrix_bins.py times --part-runs computes them",
    ]
    for (config, suite), (seconds, share) in sorted(runs.items()):
        lines.append(f"{config}\t{suite}\t{seconds:.1f}\t{share:.3f}")
    Path(path).write_text("\n".join(lines) + "\n", encoding="utf-8")


def read_names(path):
    """A file's names, one a line, `#` comments and blank lines aside; empty for a missing file."""
    p = Path(path)
    if not p.exists():
        return ()
    lines = p.read_text(encoding="utf-8").splitlines()
    names = (line.strip() for line in lines)
    return tuple(name for name in names if name and not name.startswith("#"))


# A record's format line, which every record a test writes by hand starts with.
FORMAT_LINE = "#\tformat\t" + REACH_FORMAT + "\n"


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

    def test_the_platforms_run_every_suite_in_bins_of_their_own_with_their_flags(self):
        suites = [("sql", [("A", False), ("C", True)]), ("catalyst", [("D", False)])]
        weights = {"A": 100.0, "C": 300.0, "D": 10.0}
        # A record that says no test moves under x=1 leaves it out of the matrix's bins, and
        # every platform runs every suite all the same.
        reach = ({"x=1"}, {"A": [set()], "C": [set()], "D": [set()]})
        out = plan(["x=1"], suites, weights, {}, {}, 700.0, reach, None, tuple(PLATFORMS))
        out = out["include"]
        self.assertEqual({b["runner"] for b in out}, {RUNNER, "ubuntu-24.04-arm"})
        met = {}
        for b in out:
            for line in b["units"].split("\n"):
                parts = line.split("|")
                if parts[0] in PLATFORMS:
                    self.assertEqual(len(parts), 4, line)
                    self.assertEqual(parts[3], PLATFORMS[parts[0]][1])
                    self.assertEqual(b["runner"], PLATFORMS[parts[0]][0], line)
                    met.setdefault(parts[0], []).extend(parts[2].split(","))
                else:
                    self.assertEqual(len(parts), 3, line)
                    self.assertEqual(b["runner"], RUNNER, line)
        for name in PLATFORMS:
            self.assertEqual(sorted(met[name]), ["A", "C", "D"], name)
        # No bin mixes a platform with the matrix's own units.
        for b in out:
            first = [line.split("|")[0] in PLATFORMS for line in b["units"].split("\n")]
            self.assertEqual(len(set(first)), 1, b)
        # Without --platforms nothing changes: every unit has three fields on the default runner.
        plain = plan(["x=1"], suites, weights, {}, {}, 700.0)["include"]
        self.assertEqual({b["runner"] for b in plain}, {RUNNER})
        self.assertFalse(any("isa=" in b["units"] for b in plain))

    def test_a_platform_run_stays_out_of_the_suite_median_but_keeps_its_own_seconds(self):
        with tempfile.TemporaryDirectory() as d:
            for config, seconds in (("defaults", 10), ("isa=arm", 90)):
                junit = Path(d, config, "sql-b000-u0", "junit")
                junit.mkdir(parents=True)
                (junit / "TEST-p.A.xml").write_text(
                    f'<testsuite name="p.A" time="{seconds}"></testsuite>', encoding="utf-8"
                )
            self.assertEqual(times([d]), {"A": 10.0})
            self.assertEqual(whole_runs([d])[("isa=arm", "A")], 90.0)

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
            f.write_text(FORMAT_LINE + "#\tcovers\tx=1\nA\tone\tx=1\nA\ttwo\t-\n")
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
            f.write_text(FORMAT_LINE + "#\tcovers\tx=1\nA\tone\tx=1\nA\ttwo\t-\n")
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
            # The part run whole_runs leaves out is the one part_runs keeps, with its share.
            parts = part_runs([Path(root, "runs")], read_reach(f))
            self.assertEqual(parts, {("x=1", "A"): (20.0, 0.5)})
            out = Path(root, "config-suite-part-seconds.tsv")
            write_part_seconds(out, parts)
            self.assertEqual(read_part_seconds(out), {("x=1", "A"): (20.0, 0.5)})
            # Without a record every configuration ran every test, and no run is a part run.
            self.assertEqual(part_runs([Path(root, "runs")]), {})

    def _part_reach(self):
        with tempfile.TemporaryDirectory() as root:
            f = Path(root, "reach.tsv")
            lines = ["#\tcovers\tx=1;y=2"] + [
                f"F\tt{i}\t{'x=1' if i < 2 else '-'}" for i in range(10)
            ]
            lines += ["G\tone\tx=1", "G\ttwo\t-", "F\t*\t-"]
            f.write_text(FORMAT_LINE + "\n".join(lines) + "\n", encoding="utf-8")
            return read_reach(f)

    def test_part_seconds_weigh_a_listed_part_run_and_only_a_part_run(self):
        # x=1 runs two of F's ten tests and one of G's two; the defaults run both whole.
        suites = [("sql", [("F", True), ("G", True)])]
        reach = self._part_reach()
        measured = {("x=1", "F"): (150.0, 0.2), ("defaults", "F"): (160.0, 0.2)}
        args = (["x=1"], suites, lambda s: 200.0, {}, 480.0, reach, None, ())

        def ran(*more):
            return {(u[0], u[2][0]): u[3] - JVM_START for u in units(*args, *more)}

        share = ran("share", measured)
        self.assertEqual(share[("x=1", "F")], 40.0)
        got = ran("part-seconds", measured)
        # The listed part run weighs its measured seconds, an unlisted one its share.
        self.assertEqual(got[("x=1", "F")], 150.0)
        self.assertEqual(got[("x=1", "G")], 100.0)
        # A pair lighter than its share weighs its measured seconds at the share it was measured
        # at, and its share when today's record lets more of the suite through.
        light = ran("part-seconds", {**measured, ("x=1", "G"): (10.0, 0.5)})
        self.assertEqual(light[("x=1", "G")], 10.0)
        light = ran("part-seconds", {**measured, ("x=1", "G"): (10.0, 0.25)})
        self.assertEqual(light[("x=1", "G")], 100.0)
        # A pair of the part file is never read for a whole run, as the defaults run F, or as
        # every configuration runs every test without a record.
        self.assertEqual(got[("defaults", "F")], 200.0)
        bare = units(
            ["x=1"], suites, lambda s: 200.0, {}, 480.0, None, None, (), "part-seconds", measured
        )
        self.assertEqual({u[3] - JVM_START for u in bare if u[0] == "x=1"}, {200.0})

    def test_a_suite_planned_apart_is_a_unit_of_its_own_at_its_whole_run(self):
        suites = [("sql", [("F", False), ("G", False), ("H", False)])]
        reach = self._part_reach()
        args = (["x=1"], suites, lambda s: 200.0, {}, 480.0, reach, None, ())
        got = units(*args, "alone", None, ("F",))
        groups = {(u[0], tuple(u[2])): u[3] - JVM_START for u in got}
        # F runs alone under every configuration that runs it, at its whole run, and the other
        # suites share their JVMs as before; G under x=1 still weighs its share.
        self.assertEqual(groups[("defaults", ("F",))], 200.0)
        self.assertEqual(groups[("x=1", ("F",))], 200.0)
        self.assertEqual(groups[("x=1", ("G", "H"))], 300.0)
        # Under any other weighing the list is not read.
        shared = units(*args, "share", None, ("F",))
        self.assertNotIn(("x=1", ["F"]), [(u[0], u[2]) for u in shared])

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
            f.write_text(FORMAT_LINE + "#\tcovers\tx=1\nA\tone\tx=1\nA\ttwo\t-\n", encoding="utf-8")
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
                FORMAT_LINE
                + "#\tcovers\tx=1;y=2;w=4\nA\tone\tx=1\nA\ttwo\t-\nB\tone\t-\nB\t*\tw=4\n",
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

    def test_a_configuration_with_a_comma_is_one_name_in_the_record(self):
        # A configuration over its subject holds a comma, and a test both it and the subject
        # alone move names both on one line.
        over = "useAVX=2,wideConversionForm=MAGIC"
        with tempfile.TemporaryDirectory() as root:
            f = Path(root, "reach.tsv")
            f.write_text(
                FORMAT_LINE
                + f"#\tcovers\tuseAVX=2;{over}\nA\tboth\tuseAVX=2;{over}\nA\tover\t{over}\n"
                f"A\tneither\t-\n",
                encoding="utf-8",
            )
            reach = read_reach(f)
        self.assertEqual(reach[0], {"useAVX=2", over})
        self.assertEqual(reach[1]["A"], [{"useAVX=2", over}, {over}, set()])
        self.assertAlmostEqual(reach_share(reach, over, "A"), 2 / 3)
        self.assertAlmostEqual(reach_share(reach, "useAVX=2", "A"), 1 / 3)

    def test_the_selection_record_keeps_a_configuration_with_a_comma_whole(self):
        over = "useAVX=2,wideConversionForm=MAGIC"
        pr = {"digests": {("S", "t"): {"defaults": "d", over: "e", "x=1": "f"}}}
        with tempfile.TemporaryDirectory() as root:
            reach = Path(root, "reach.tsv")
            reach.write_text(FORMAT_LINE + f"S\tt\t{over};x=1\n", encoding="utf-8")
            out = Path(root, "selected.tsv")
            lines = reach_record(
                {(over, "S", "t"): "digest", ("x=1", "S", "t"): "digest"}, pr, reach
            )
            out.write_text("\n".join(lines) + "\n", encoding="utf-8")
            covered, tests = read_reach(out)
        self.assertEqual(covered, {over, "x=1"})
        self.assertEqual(tests["S"], [{over, "x=1"}])

    def test_a_combined_configurations_run_directory_finds_its_record(self):
        over = "useAVX=2,wideConversionForm=MAGIC"
        # A sibling pair the record does not cover, and the defaults, both run A whole.
        uncovered = "useAVX=2,cse=false"
        with tempfile.TemporaryDirectory() as root:
            for config in [over, uncovered, DEFAULTS]:
                junit = Path(root, "runs", run_dir(config), "sql-0", "junit")
                junit.mkdir(parents=True)
                Path(junit, "TEST-p.A.xml").write_text('<testsuite name="p.A" time="10.0">')
            f = Path(root, "reach.tsv")
            f.write_text(
                FORMAT_LINE + f"#\tcovers\t{over}\nA\tone\t{over}\nA\ttwo\t-\n", encoding="utf-8"
            )
            got = whole_runs([Path(root, "runs")], read_reach(f))
        self.assertEqual(
            got,
            {(DEFAULTS, "A"): 10.0, (run_dir(uncovered), "A"): 10.0},
            "the covered pair ran half of A, so its time is no whole run; the others' are",
        )

    def test_a_broken_record_is_none(self):
        with tempfile.TemporaryDirectory() as root:
            f = Path(root, "reach.tsv")
            f.write_text(FORMAT_LINE + "A\tone\t-\n#\tbroken\tboom\n", encoding="utf-8")
            self.assertIsNone(read_reach(f))

    def test_a_record_without_this_format_or_of_another_is_none(self):
        with tempfile.TemporaryDirectory() as root:
            f = Path(root, "reach.tsv")
            # The record before the format line, its names joined by commas: read as this format,
            # the pair would be one name no configuration has, and x=1 would skip `one`.
            f.write_text("#\tcovers\tx=1,y=2\nA\tone\tx=1,y=2\nA\ttwo\t-\n", encoding="utf-8")
            self.assertIsNone(read_reach(f))
            f.write_text(FORMAT_LINE + "#\tformat\t3\nA\tone\t-\n", encoding="utf-8")
            self.assertIsNone(read_reach(f), "joined from recordings of two formats")
            f.write_text(FORMAT_LINE + "A\tone\t-\n" + FORMAT_LINE, encoding="utf-8")
            self.assertIsNotNone(read_reach(f), "joined from two recordings of this format")
            # The selection's record reruns everything over a pull request's record of no format.
            f.write_text("#\tcovers\tx=1\nS\tt\t-\n", encoding="utf-8")
            pr = {"digests": {("S", "t"): {"defaults": "d", "x=1": "e"}}}
            out = Path(root, "selected.tsv")
            out.write_text("\n".join(reach_record({}, pr, f)) + "\n", encoding="utf-8")
            self.assertIsNone(read_reach(out))

    def test_a_configuration_the_record_does_not_cover_or_spells_with_spaces(self):
        over = "useAVX=2,wideConversionForm=MAGIC"
        with tempfile.TemporaryDirectory() as root:
            f = Path(root, "reach.tsv")
            f.write_text(
                FORMAT_LINE + f"#\tcovers\t{over}\nA\tone\t{over}\nA\ttwo\t-\n", encoding="utf-8"
            )
            reach = read_reach(f)
        self.assertEqual(canonical("useAVX=2, wideConversionForm = MAGIC"), over)
        self.assertEqual(reach_share(reach, "useAVX=2, wideConversionForm=MAGIC", "A"), 0.5)
        self.assertEqual(reach_share(reach, "useAVX=2,cse=false", "A"), 1.0)

    def test_the_reach_format_fixture(self):
        # VarkaTestReachSuite holds the JVM's writer and reader to the same file and values.
        lines = [
            line
            for line in REACH_FIXTURE.read_text(encoding="utf-8").splitlines()
            if not line.startswith("# ")
        ]
        over = "useAVX=2,wideConversionForm=MAGIC"
        covered = ["cse=false", "useAVX=2", over]
        self.assertEqual(
            lines,
            reach_head(covered)
            + [
                f"S\tboth\t{reach_field({over, 'useAVX=2'})}",
                f"S\tover\t{reach_field({over})}",
                f"S\tnone\t{reach_field(set())}",
                f"S\tcancelled\t{reach_field({'*'})}",
            ],
        )
        self.assertEqual(
            read_reach(REACH_FIXTURE),
            (set(covered), {"S": [{"useAVX=2", over}, {over}, set(), {"*"}]}),
        )

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
            reach.write_text(
                FORMAT_LINE + "#\tcovers\tx=1\nS\tuses kernels\tx=1\nS\tuses helpers\t-\n"
            )
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

    # -- replay (VARKA-617) ---------------------------------------------------------------------

    @staticmethod
    def _stamp(t):
        return datetime.fromtimestamp(t, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    def _run(self, root, bins, jobs):
        """A run's downloaded bin artifacts, `bins` as {bin: [(config, module, {suite: JUnit
        seconds}, seconds)]} in the order the job ran them, and its jobs file, `jobs` as {bin:
        (created, started, units step start, units step end, completed)}."""
        for b, us in bins.items():
            for k, (config, module, suites, seconds) in enumerate(us):
                runs = Path(root, f"varka-option-matrix-{b}", f"{b}-u{k}", "runs", run_dir(config))
                leaf = runs / f"{module}-{b}-{k}"
                (leaf / "junit").mkdir(parents=True)
                (runs / "name").write_text(config + "\n", encoding="utf-8")
                (leaf / "seconds").write_text(f"{seconds:.0f}\n", encoding="utf-8")
                for suite, t in suites.items():
                    xml = f'<testsuite name="p.{suite}" tests="1" time="{t}">\n</testsuite>\n'
                    (leaf / "junit" / f"TEST-p.{suite}.xml").write_text(xml, encoding="utf-8")
        f = Path(root, "jobs.jsonl")
        f.write_text("".join(json.dumps(j) + "\n" for j in self._jobs(jobs)), encoding="utf-8")
        return read_bin_runs(root), read_bin_jobs(f)

    def _jobs(self, jobs, attempt=1):
        """`jobs` as the API's job objects, a job that is not a bin's last; a job whose units
        step times are None never reached that step."""
        out = []
        for b, (created, started, run_start, run_end, completed) in jobs.items():
            step = {"name": "Set up job", "started_at": None, "completed_at": None}
            if run_start is not None:
                step = {
                    "name": RUN_STEP,
                    "started_at": self._stamp(run_start),
                    "completed_at": self._stamp(run_end),
                }
            out.append(
                {
                    "name": f"Option matrix: {b}",
                    "run_attempt": attempt,
                    "created_at": self._stamp(created),
                    "started_at": self._stamp(started),
                    "completed_at": self._stamp(completed),
                    "steps": [step],
                }
            )
        # A job that is not a bin's is read past.
        out.append({"name": "Option matrix: build and plan", "started_at": "x"})
        return out

    def test_the_jobs_file_reads_in_each_form_the_api_saves(self):
        jobs = self._jobs({"b000": (0, 2, 32, 52, 55), "b001": (0, 57, 87, 97, 100)})
        page = {"total_count": len(jobs), "jobs": jobs}
        forms = {
            "lines": "".join(json.dumps(j) + "\n" for j in jobs),
            "one page": json.dumps(page),
            "pretty": json.dumps(page, indent=2),
            "pages": json.dumps({"total_count": 3, "jobs": jobs[:1]})
            + json.dumps({"total_count": 3, "jobs": jobs[1:]}),
            "slurped": json.dumps([page]),
        }
        with tempfile.TemporaryDirectory() as root:
            got = {}
            for name, text in forms.items():
                f = Path(root, name)
                f.write_text(text, encoding="utf-8")
                got[name] = read_bin_jobs(f)
            f.write_text("1 bins, the longest 480 s\n", encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "is no saved jobs listing"):
                read_bin_jobs(f)
        self.assertEqual(sorted(got["lines"]), ["b000", "b001"])
        for name in forms:
            self.assertEqual(got[name], got["lines"], name)

    def test_bin_jobs_of_two_attempts_are_refused(self):
        jobs = self._jobs({"b000": (0, 2, 32, 52, 55)}) + self._jobs(
            {"b001": (0, 57, 87, 97, 100)}, attempt=2
        )
        with tempfile.TemporaryDirectory() as root:
            f = Path(root, "jobs.jsonl")
            f.write_text("".join(json.dumps(j) + "\n" for j in jobs), encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "attempt 1: 1 bins, b000; attempt 2"):
                read_bin_jobs(f)
            # A bin re-run whole keeps its last attempt alone.
            f.write_text(
                "".join(
                    json.dumps(j) + "\n" for j in jobs + self._jobs({"b000": (0, 1, 2, 3, 4)}, 2)
                ),
                encoding="utf-8",
            )
            self.assertEqual({j["attempt"] for j in read_bin_jobs(f).values()}, {2})

    def test_a_unit_a_cancel_killed_is_reported_and_inputs_that_match_nothing_refused(self):
        with tempfile.TemporaryDirectory() as root:
            self._run(
                root,
                {"b000": [("x=1", "sql", {"A": 9.0}, 10)]},
                {"b000": (0, 2, 32, 52, 55), "b001": (0, 57, 87, 97, 100)},
            )
            leaf = Path(root, "varka-option-matrix-b000", "b000-u1", "runs", "x=1", "sql-b000-1")
            leaf.mkdir(parents=True)
            (leaf / "run.log").write_text("killed\n", encoding="utf-8")
            bins, jobs, unfinished = replay_inputs(root, Path(root, "jobs.jsonl"))
            self.assertEqual(unfinished, ["b000-u1/x=1/sql-b000-1"])
            got = replay(bins, jobs, [("sql", [("A", True)])], ({}, {}, {}, {}, {}, ()), 480.0)
            lines = replay_lines(got, unfinished=unfinished)
            self.assertIn("left out of as run: 1\n  b000-u1/x=1/sql-b000-1", lines[-1])
            with tempfile.TemporaryDirectory() as empty:
                with self.assertRaisesRegex(SystemExit, "0 timed units of 0 bins under"):
                    replay_inputs(empty, Path(root, "jobs.jsonl"))
            other = Path(root, "other.jsonl")
            text = "".join(json.dumps(j) + "\n" for j in self._jobs({"b007": (0, 1, 2, 3, 4)}))
            other.write_text(text, encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "not one run's: 1 bin jobs in"):
                replay_inputs(root, other)
            text = "".join(json.dumps(j) + "\n" for j in self._jobs({"b000": (0, 1, None, 0, 4)}))
            other.write_text(text, encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "reached its step"):
                replay_inputs(root, other)

    def test_a_bin_job_that_stopped_short_stays_out_of_the_median_set_up(self):
        # b002 failed before its units step after 600 s; the set-up and upload of the two jobs that
        # ran their units are 33 s each, and stay the median however many jobs stop short.
        with tempfile.TemporaryDirectory() as root:
            bins, jobs = self._run(
                root,
                {
                    "b000": [("x=1", "sql", {"A": 9.0}, 10)],
                    "b001": [("x=1", "sql", {"A": 9.0}, 10)],
                },
                {
                    "b000": (0, 2, 32, 42, 45),
                    "b001": (0, 47, 77, 87, 90),
                    "b002": (0, 92, None, None, 692),
                    "b003": (0, 694, None, None, 1294),
                },
            )
        got = replay(bins, jobs, [("sql", [("A", True)])], ({}, {}, {}, {}, {}, ()), 480.0)
        self.assertEqual(got["setup"], 33.0)

    def test_the_planner_files_refuse_a_ref_that_is_no_commit_and_a_missing_file(self):
        git = ["git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false"]
        with tempfile.TemporaryDirectory() as repo:
            subprocess.run(["git", "init", "-q", repo], check=True)
            Path(repo, SUITE_SECONDS).parent.mkdir(parents=True)
            Path(repo, SUITE_SECONDS).write_text("A\t12.0\n", encoding="utf-8")
            subprocess.run([*git, "-C", repo, "add", "-A"], check=True)
            subprocess.run([*git, "-C", repo, "commit", "-q", "-m", "x"], check=True)
            got = planner_files("HEAD", repo=Path(repo))
            # A file the revision lacks reads as empty, as a missing file does.
            self.assertEqual(got, ({"A": 12.0}, {}, {}, {}, {}, ()))
            with self.assertRaisesRegex(SystemExit, "--plan-from HEAD~1 is no commit"):
                planner_files("HEAD~1", repo=Path(repo))
            with self.assertRaisesRegex(SystemExit, "no file"):
                planner_files("HEAD", pairs=Path(repo, "nope.tsv"), repo=Path(repo))
            with self.assertRaisesRegex(SystemExit, "no file"):
                planner_files(suite_seconds=Path(repo, "nope.tsv"), repo=Path(repo))
            with self.assertRaisesRegex(SystemExit, "no file"):
                planner_files(part_seconds=Path(repo, "nope.tsv"), repo=Path(repo))

    def test_plan_weighs_a_part_run_by_its_share_by_default(self):
        # The default must stay `share` until CI refreshes the part file (VARKA-617 decision 9).
        self.assertEqual(PART_WEIGHT, "share")
        parsed = []
        real = plan

        def spy(*a):
            parsed.append(a[9])
            return real(*a)

        with tempfile.TemporaryDirectory() as root:
            Path(root, "configurations").write_text("", encoding="utf-8")
            Path(root, "sql").write_text("p.A\n", encoding="utf-8")
            argv = ["plan", "--configurations", str(Path(root, "configurations"))]
            argv += ["--suites", f"sql={Path(root, 'sql')}"]
            globals()["plan"] = spy
            try:
                with contextlib.redirect_stdout(io.StringIO()):
                    with contextlib.redirect_stderr(io.StringIO()):
                        self.assertEqual(main(argv), 0)
            finally:
                globals()["plan"] = real
        self.assertEqual(parsed, ["share"])

    def test_plan_refuses_a_matrix_past_the_cap(self):
        # A lane of 10 s is shorter than any unit, so each suite run alone takes a bin.
        with tempfile.TemporaryDirectory() as root:
            Path(root, "configurations").write_text("", encoding="utf-8")
            for count in (MATRIX_CAP, MATRIX_CAP + 1):
                suites = "".join(f"@own p.S{i}\n" for i in range(count))
                Path(root, "sql").write_text(suites, encoding="utf-8")
                argv = ["plan", "--configurations", str(Path(root, "configurations"))]
                argv += ["--suites", f"sql={Path(root, 'sql')}", "--target", "10"]
                out, err = io.StringIO(), io.StringIO()
                with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                    code = main(argv)
                if count == MATRIX_CAP:
                    self.assertEqual(code, 0)
                    self.assertEqual(len(json.loads(out.getvalue())["include"]), MATRIX_CAP)
                else:
                    self.assertEqual((code, out.getvalue()), (1, ""))
                    self.assertIn(f"::error::varka_matrix_bins plan: {count} bins", err.getvalue())
                    self.assertIn("raise --target", err.getvalue())

    def test_as_run_replays_a_run_to_its_measured_phase(self):
        # One slot, each job 30 s of set-up and 3 of upload around its units, 2 s of wait for a
        # runner: b000's units of 10 and 20 s take 20 s on two lanes, b001's three of 5 s take 10.
        with tempfile.TemporaryDirectory() as root:
            bins, jobs = self._run(
                root,
                {
                    "b000": [("x=1", "sql", {"A": 9.0}, 10), ("defaults", "sql", {"B": 19.0}, 20)],
                    "b001": [("x=1", "catalyst", {"C": 4.0}, 5)] * 3,
                },
                {"b000": (0, 2, 32, 52, 55), "b001": (0, 57, 87, 97, 100)},
            )
        self.assertEqual([u[4] for u in bins["b000"]], [10.0, 20.0])
        got = replay(
            bins, jobs, [("sql", [("A", True), ("B", True)])], ({}, {}, {}, {}, {}, ()), 480.0
        )
        self.assertEqual((got["slots"], got["wait"], got["setup"], got["jvm"]), (1, 2.0, 33.0, 1.0))
        self.assertEqual(got["measured"], 98.0)
        # Every term of the model is taken from the run, so neither replay may drop one.
        self.assertEqual(got["jobs as run"], 98.0)
        self.assertEqual(got["as run"], 98.0)
        self.assertEqual(got["bins as run"], [20.0, 10.0])

    def test_replanned_on_a_runs_own_pairs_cuts_the_runs_own_bins(self):
        suites = [("sql", [("A", True), ("B", False), ("C", False)])]
        weights = {"x=1": 2.0}
        cut = plan(["x=1"], suites, {"A": 100.0, "B": 40.0, "C": 60.0}, {}, weights, 300.0)
        cut = cut["include"]
        self.assertEqual(len(cut), 2)
        whole = {"A": 100.0, "B": 40.0, "C": 60.0}
        bins, jobs = {}, {}
        for i, b in enumerate(cut):
            us = []
            for line in b["units"].split("\n"):
                config, module, names = line.split("|")
                factor = 2.0 if config == "x=1" else 1.0
                ran = {s: whole[s] * factor for s in names.split(",")}
                us.append((config, module, ran, JVM_START + sum(ran.values())))
            bins[b["bin"]] = us
            lanes = lanes_time([u[3] for u in us])
            jobs[b["bin"]] = (0, 1 + i, 31 + i, 31 + i + lanes, 34 + i + lanes)
        with tempfile.TemporaryDirectory() as root:
            bins, jobs = self._run(root, bins, jobs)
            pairs = whole_runs([root])
        self.assertEqual(pairs[("x=1", "A")], 200.0)
        got = replay(bins, jobs, suites, ({}, {}, {}, pairs, {}, ()), 300.0)
        # The planner cut by the seconds the run measured is the run's own cut, timed alike.
        self.assertEqual(got["jvm"], JVM_START)
        self.assertEqual(got["missing pairs"][PART_WEIGHT], 0)
        self.assertEqual(got["bins replanned"][PART_WEIGHT], got["bins as run"])
        self.assertEqual(got["replanned"][PART_WEIGHT], got["as run"])
        self.assertEqual(got["as run"], got["measured"])


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
        lines = Path(reach).read_text(encoding="utf-8").splitlines()
        why = reach_broken(lines)
        if why is not None:
            return [f"#\tbroken\tthe pull request's reach record is broken: {why}"]
        for line in lines:
            parts = line.split("\t")
            if line.startswith("#") or len(parts) < 3:
                continue
            got = reach_names(parts[-1])
            key = (parts[0], "\t".join(parts[1:-1]))
            moving.setdefault(key, set()).update(got)
    lines = reach_head(covered)
    tests = sorted({(s, t) for s, t in pr["digests"] if s != "*" and t != "*"})
    for suite, test in tests:
        rerun = {c for (c, s, t) in selected if (s, t) == (suite, test) and c != DEFAULTS}
        if moving is not None:
            moved = moving.get((suite, test), {"*"}) | moving.get((suite, "*"), set())
            moved |= moving.get(("*", "*"), set())
            if "*" not in moved:
                rerun &= moved
        lines.append(f"{suite}\t{test}\t{reach_field(rerun)}")
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


def replay_main(args):
    suites = []
    for entry in args.suites:
        module, path = entry.split("=", 1)
        suites.append((module, read_suites(path)))
    bins, jobs, unfinished = replay_inputs(args.run, args.jobs)
    got = replay(
        bins,
        jobs,
        suites,
        planner_files(args.plan_from, args.pairs, args.suite_seconds, args.part_seconds),
        args.target,
        read_reach(args.reach) if args.reach else None,
        args.slots,
        PART_WEIGHTS,
    )
    print("\n".join(replay_lines(got, unfinished=unfinished)))
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
    p.add_argument(
        "--platforms",
        action="store_true",
        help="also plan the platform configurations ("
        + ", ".join(PLATFORMS)
        + ") in bins of their own",
    )
    p.add_argument("--part-weight", choices=PART_WEIGHTS, default=PART_WEIGHT)
    t = sub.add_parser("times")
    t.add_argument("dirs", nargs="+")
    t.add_argument("--reach", help="the run's record: only whole runs of a suite count")
    t.add_argument(
        "--per-configuration",
        metavar="FILE",
        help="also write each suite's whole runs under each configuration to FILE",
    )
    t.add_argument(
        "--part-runs",
        metavar="FILE",
        help="also write each suite's part runs under each configuration to FILE",
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
    r = sub.add_parser("replay")
    r.add_argument("run", help="the run's bin artifacts, downloaded (gh run download)")
    r.add_argument("--jobs", required=True, help="the run's jobs from the Actions API, saved")
    r.add_argument("--suites", action="append", required=True, metavar="MODULE=FILE")
    r.add_argument("--reach", help="the run's reach record, its artifact varka-option-matrix-reach")
    r.add_argument("--plan-from", metavar="REF", help="the planner's files at this revision")
    r.add_argument("--pairs", help="a config-suite-seconds.tsv to plan by, a run's own say")
    r.add_argument("--suite-seconds", help="a suite-seconds.tsv to plan by")
    r.add_argument("--part-seconds", help="a config-suite-part-seconds.tsv to plan by")
    r.add_argument("--target", type=float, default=480.0)
    r.add_argument("--slots", type=int, help="the slots, the most jobs the run ran at once if not")
    args = parser.parse_args(argv)
    if args.cmd == "replay":
        return replay_main(args)
    if args.cmd == "select":
        return select_main(args)
    if args.cmd == "times":
        reach = read_reach(args.reach) if args.reach and Path(args.reach).is_file() else None
        for name, seconds in sorted(times(args.dirs, reach).items()):
            print(f"{name}\t{seconds:.1f}")
        if args.per_configuration:
            write_config_seconds(args.per_configuration, whole_runs(args.dirs, reach))
        if args.part_runs:
            parts = part_runs(args.dirs, reach)
            if not parts:
                # A run with no usable record ran no part run: keep the file it would empty.
                print(f"times: no part run found; {args.part_runs} left as it is", file=sys.stderr)
            else:
                write_part_seconds(args.part_runs, parts)
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
        tuple(PLATFORMS) if args.platforms else (),
        args.part_weight,
        # Each read only by the weighing that uses it, so the default plan reads no part file.
        read_part_seconds(PART_SECONDS) if args.part_weight == "part-seconds" else {},
        read_names(PART_ALONE) if args.part_weight == "alone" else (),
    )
    why = over_cap(out, args.target)
    if why is not None:
        # The workflow prints this file when the step fails, so the annotation shows.
        print(f"::error::varka_matrix_bins plan: {why}", file=sys.stderr)
        return 1
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
