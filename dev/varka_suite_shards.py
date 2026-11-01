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
"""Spread suites over N JVMs, longest first (VARKA-286.md 3.1), or over N CI jobs.

    dev/varka_suite_shards.py N WEIGHTS_DIR SUITE...     # one line per JVM, suites space separated
    dev/varka_suite_shards.py --job I/N WEIGHTS_FILE [--head-start J:SECONDS] SUITE...
                                                  # CI job I's suites, one a line
    dev/varka_suite_shards.py --update-weights WEIGHTS_FILE JUNIT_DIR...
    dev/varka_suite_shards.py --check-weights WEIGHTS_FILE JUNIT_DIR [SUITES_FILE]
    dev/varka_suite_shards.py --self-test

Each suite's weight is its time in the JUnit reports under WEIGHTS_DIR (any depth, the files
ScalaTest's `-u` writes), and the median of the known weights for a suite that has none, so a new
suite is placed as an ordinary one. The heaviest unplaced suite goes to the lightest JVM, which
keeps the longest JVM near the longest suite when one suite dominates. Empty JVMs are not
printed.

`--job` splits a module's suites over the CI jobs that run them, the same way, with the weights
read from a committed file (`sql/varka/ci/suite-times.tsv`, a simple suite name and its seconds a
line) rather than from a run's reports. Each job computes its own share, so every job must see the
same weights: a committed file is the same in every job of a run, where a restored cache need not
be. A suite the file lacks takes the median, so a stale file costs balance and never coverage.
`--head-start J:SECONDS` counts SECONDS of other work against job J before any suite is placed, for
a job that does more than run its share (sql's first job builds the option matrix's classes).
`--update-weights` writes the file from the JUnit reports of a run of every suite; given several
runs, each a JUNIT_DIR, a suite takes the mean of the runs it appears in, since one run's time
moves with the runner it drew. `--check-weights` reads one job's JUnit reports and prints a GitHub
warning for each suite whose weight is stale: missing from the file, or off its weight by more
than STALE_FACTOR and STALE_SECONDS together; with SUITES_FILE, a simple suite name a line, only
the suites it names, since a job's output directory can hold an earlier run's reports. Two Builds
of the same tests saw a suite's time move by a median factor of 1.15 and at most 3.38
(`m8/results/VARKA-516-weights.txt`), so a factor below that would warn on noise; the check warns
and never fails a job.
"""

import heapq
import statistics
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def weights(root):
    """Seconds per suite (by simple name) from the JUnit reports under `root`."""
    times = {}
    for path in Path(root).rglob("*.xml") if root and Path(root).exists() else []:
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        name = suite.get("name", "").split(".")[-1]
        if name and suite.get("time"):
            times[name] = max(times.get(name, 0.0), float(suite.get("time")))
    return times


# A suite's time against its weight beyond both of these is a stale weight, not a runner's noise.
STALE_FACTOR = 3.5
STALE_SECONDS = 30.0


def mean_weights(runs):
    """Seconds per suite over several runs' weights, each suite the mean of the runs it is in.

    >>> mean_weights([{"A": 10.0, "B": 4.0}, {"A": 20.0}])
    {'A': 15.0, 'B': 4.0}
    """
    seen = {}
    for times in runs:
        for name, secs in times.items():
            seen.setdefault(name, []).append(secs)
    return {name: sum(v) / len(v) for name, v in sorted(seen.items())}


def stale(weights_known, measured):
    """The suites whose weight is stale against one run's times, each with a reason.

    >>> known = {"ASuite": 100.0, "BSuite": 10.0, "CSuite": 50.0}
    >>> stale(known, {"ASuite": 120.0, "BSuite": 20.0, "CSuite": 200.0, "DSuite": 40.0})
    [('CSuite', 'weight 50.0 s, measured 200.0 s'), ('DSuite', 'no weight, measured 40.0 s')]
    """
    out = []
    for name, secs in sorted(measured.items()):
        if not name.endswith("Suite") or "$" in name:
            continue
        known = weights_known.get(name)
        if known is None:
            if secs > STALE_SECONDS:
                out.append((name, f"no weight, measured {secs:.1f} s"))
            continue
        hi, lo = max(secs, known), max(min(secs, known), 0.1)
        if hi / lo > STALE_FACTOR and hi - lo > STALE_SECONDS:
            out.append((name, f"weight {known:.1f} s, measured {secs:.1f} s"))
    return out


def place(count, suites, times, head=None):
    """Suites over `count` bins, heaviest first onto the lightest; a bin may be empty. `head`
    maps a bin to the seconds of other work it starts with.

    >>> place(2, ["p.A", "p.B", "p.C"], {"A": 10.0, "B": 6.0, "C": 5.0}, {0: 8.0})
    [['p.B'], ['p.A', 'p.C']]
    """
    known = list(times.values())
    default = statistics.median(known) if known else 1.0
    weighted = sorted(suites, key=lambda s: (-times.get(s.split(".")[-1], default), s))
    heap = [((head or {}).get(k, 0.0), k) for k in range(count)]
    heapq.heapify(heap)
    placed = [[] for _ in range(count)]
    for suite in weighted:
        load, k = heapq.heappop(heap)
        placed[k].append(suite)
        heapq.heappush(heap, (load + times.get(suite.split(".")[-1], default), k))
    return placed


def shards(count, suites, times):
    """Suites (fully qualified) over `count` JVMs, heaviest first onto the lightest.

    >>> shards(2, ["p.A", "p.B", "p.C"], {"A": 10.0, "B": 6.0, "C": 5.0})
    [['p.A'], ['p.B', 'p.C']]
    >>> shards(3, ["p.A"], {})
    [['p.A']]
    >>> shards(2, ["p.A", "p.New"], {"A": 4.0})
    [['p.A'], ['p.New']]
    """
    return [p for p in place(count, suites, times) if p]


def job_shard(index, count, suites, times, head=None):
    """Job `index` of `count`: the suites the same placement gives it, empty when it has none.

    >>> job_shard(1, 3, ["p.A", "p.B", "p.C", "p.D"], {"A": 9.0, "B": 5.0, "C": 4.0, "D": 1.0})
    ['p.B']
    >>> job_shard(2, 3, ["p.A", "p.B", "p.C", "p.D"], {"A": 9.0, "B": 5.0, "C": 4.0, "D": 1.0})
    ['p.C', 'p.D']
    >>> job_shard(1, 2, ["p.A"], {})
    []
    """
    return place(count, suites, times, head)[index]


def read_weights(path):
    """Seconds per simple suite name from a weights file; # lines and blank lines are skipped."""
    times = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                name, secs = line.split("\t")
                times[name] = float(secs)
    return times


def write_weights(path, times):
    with open(path, "w", encoding="utf-8") as f:
        f.write(
            "# Seconds per Varka suite, the weights CI splits a module's suites over its jobs by\n"
            "# (dev/varka_suite_shards.py --job). Written by --update-weights from a run's JUnit\n"
            "# reports; a suite missing here takes the median.\n"
        )
        for name in sorted(times):
            f.write(f"{name}\t{times[name]:.1f}\n")


def main(argv):
    if argv == ["--self-test"]:
        import doctest

        failed, _ = doctest.testmod()
        return 1 if failed else 0
    if len(argv) >= 3 and argv[0] == "--job":
        index, count = (int(x) for x in argv[1].split("/"))
        if not 0 <= index < count:
            print(f"--job {argv[1]}: the index counts from zero, below the count", file=sys.stderr)
            return 2
        suites, head = argv[3:], {}
        if suites[:1] == ["--head-start"] and len(suites) >= 2:
            job, secs = suites[1].split(":")
            head[int(job)] = float(secs)
            suites = suites[2:]
        for suite in job_shard(index, count, suites, read_weights(argv[2]), head):
            print(suite)
        return 0
    if len(argv) >= 3 and argv[0] == "--update-weights":
        times = read_weights(argv[1]) if Path(argv[1]).exists() else {}
        times.update(mean_weights([weights(d) for d in argv[2:]]))
        # A report names fixtures and censuses too (a nested `Suite$Fixture`, a `...Census`);
        # only the suites a job is split by belong, or they drag the median a new suite takes.
        times = {k: v for k, v in times.items() if k.endswith("Suite") and "$" not in k}
        write_weights(argv[1], times)
        return 0
    if len(argv) in (3, 4) and argv[0] == "--check-weights":
        measured = weights(argv[2])
        if len(argv) == 4:
            mine = set(Path(argv[3]).read_text(encoding="utf-8").split())
            measured = {k: v for k, v in measured.items() if k in mine}
        for name, why in stale(read_weights(argv[1]), measured):
            print(
                f"::warning title=Stale suite weight::{name}: {why}; refresh {argv[1]} with "
                "dev/varka_suite_shards.py --update-weights"
            )
        return 0
    if len(argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    for shard in shards(int(argv[0]), argv[2:], weights(argv[1])):
        print(" ".join(shard))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
