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
"""Join the shard files of one sharded date-surface run back into one file per distribution.

    dev/varka_bench_merge.py SHARD_DIR... --out sql/varka/bench/benchmarks

Files are grouped by the `benchmark` and `label` their own provenance records, not by
their names, so shards of two benchmarks staged in one directory merge separately - and a
results file that is not a shard of this tool's making is skipped rather than merged.

`DateSurfaceBenchmark --shard I/N` runs entries I, I+N, I+2N ... of the surface, so N
dispatches between them cover it exactly once. That exists because the fixed-share rule
and GitHub's six-hour job limit pull in opposite directions (VARKA-62.md 11.11).
This puts the pieces back together.

**What it refuses to do, which is the point of it being a program rather than `cat`.**
The shards of one run are measured on different physical machines at different times, so
joining them is only legitimate when they agree about what they measured. Every shard of
a label must report the same commit, row count, partition count, shard count, surface
entry count, CPU model, MaxVectorSize and datapath ratio; the indices must together be
exactly 0..N-1 with no gap and no repeat; and the union of the entries must be the whole
surface, each exactly once. Anything else exits non-zero and says which file and which
field, because a silently mis-joined file is a published number nobody can reproduce and
the failure has no other symptom.

What it deliberately does *not* require is the same host or the same date - those are what
sharding trades away, and they are recorded per shard in the merged header instead of
being averaged into a single misleading line. A reader of the merged file can therefore
see that entry 3 and entry 4 were timed on different machines, which a concatenation would
have hidden.
"""

import argparse
import os
import re
import sys
from collections import OrderedDict

# "key:  value", the shape Provenance.format writes: key, colon, padding, value.
PROV = re.compile(r"^([a-zA-Z][a-zA-Z0-9 _.-]*):\s{2,}(.*)$")
# "date_add(d, 3) over 1000000000 rows:      Best Time(ms) ...", the first line of a block.
BLOCK = re.compile(r"^(?P<entry>.+?) over (?P<rows>\d+) rows(?P<rest>[:,].*)$")

# Fields every shard of one label must agree on. Not host/date/load: those differ by
# construction, and pretending otherwise is the error this tool exists to prevent.
MUST_MATCH = (
    "benchmark",
    "label",
    "spark",
    "commit",
    "rows",
    "partitions",
    "shard count",
    "entries",
    "cpu",
    "MaxVectorSize",
    "datapath",
)
# Recorded once per shard in the merged header rather than collapsed.
PER_SHARD = ("shard", "host", "date", "load at start", "canary", "spark home")


def read(path):
    """(provenance dict, [(entry name, block text)]) for one shard file."""
    text = open(path, encoding="utf-8").read()
    lines = text.splitlines(keepends=True)
    prov, i = OrderedDict(), 0
    while i < len(lines):
        m = PROV.match(lines[i].rstrip("\n"))
        if not m:
            if lines[i].strip() == "" and prov:
                i += 1
                break
            if not prov:
                raise SystemExit(f"{path}: no provenance block at the top")
            break
        prov[m.group(1)] = m.group(2).strip()
        i += 1
    blocks, current, name = [], [], None
    for line in lines[i:]:
        m = BLOCK.match(line.rstrip("\n"))
        # A block starts at the wall-time table; the executor-time table of the same entry
        # repeats the name with ", executor time" and must stay inside the same block.
        if m and not m.group("rest").startswith(","):
            if name is not None:
                blocks.append((name, "".join(current)))
            name, current = m.group("entry"), [line]
        elif name is not None:
            current.append(line)
    if name is not None:
        blocks.append((name, "".join(current)))
    if not blocks:
        raise SystemExit(f"{path}: no result blocks")
    return prov, blocks


def shard_of(prov, path):
    """(index, count) from the 'shard' provenance line, which the driver always writes."""
    raw = prov.get("shard")
    if raw is None:
        raise SystemExit(
            f"{path}: no 'shard' line - built before --shard existed, so it "
            f"cannot be told apart from a whole-surface file"
        )
    try:
        i, n = (int(x) for x in raw.split("/", 1))
    except ValueError:
        raise SystemExit(f"{path}: 'shard' is {raw!r}, wanted I/N") from None
    return i, n


def merge_label(label, files):
    """One merged file's text, or exit with what disagreed."""
    parts = []
    for path in files:
        prov, blocks = read(path)
        prov["shard count"] = str(shard_of(prov, path)[1])
        parts.append((path, prov, blocks))

    first_path, first, _ = parts[0]
    for path, prov, _ in parts[1:]:
        for key in MUST_MATCH:
            a, b = first.get(key), prov.get(key)
            if a != b:
                raise SystemExit(
                    f"{label}: shards disagree on {key!r}\n"
                    f"  {first_path}: {a!r}\n  {path}: {b!r}\n"
                    f"These shards did not measure the same thing and must not be joined."
                )

    count = int(first["shard count"])
    seen = {}
    for path, prov, _ in parts:
        i, _n = shard_of(prov, path)
        if i in seen:
            raise SystemExit(f"{label}: shard {i} given twice: {seen[i]} and {path}")
        seen[i] = path
    missing = sorted(set(range(count)) - set(seen))
    if missing:
        raise SystemExit(
            f"{label}: shard(s) {missing} missing of {count}; "
            f"a merged file with a hole in it is worse than none"
        )

    # Canonical order is the entry order the driver emits, which is the surface's own: shard
    # i holds entries i, i+N, i+2N, so interleaving the shards by index restores it exactly.
    ordered, by_index = [], {i: read(p)[1] for i, p in seen.items()}
    pos = {i: 0 for i in by_index}
    while any(pos[i] < len(by_index[i]) for i in by_index):
        for i in range(count):
            if pos[i] < len(by_index[i]):
                ordered.append(by_index[i][pos[i]])
                pos[i] += 1

    entries = [name for name, _ in ordered]
    if len(set(entries)) != len(entries):
        dupes = sorted({e for e in entries if entries.count(e) > 1})
        raise SystemExit(f"{label}: entry measured by more than one shard: {dupes}")
    expected = int(first.get("surface entries", "0"))
    if expected and len(entries) != expected:
        raise SystemExit(
            f"{label}: merged {len(entries)} entries but the surface has "
            f"{expected}; a shard ran a different surface than it claims"
        )

    out = OrderedDict((k, v) for k, v in first.items() if k not in PER_SHARD)
    out["shard"] = f"merged {count} shards"
    for i in range(count):
        prov = next(p for path, p, _ in parts if shard_of(p, path)[0] == i)
        out[f"shard {i}"] = "  ".join(
            f"{k}={prov[k]}" for k in PER_SHARD if k in prov and k != "shard"
        )
    width = max(len(k) for k in out) + 1
    header = "".join(f"{k + ':':<{width + 2}}{v}\n" for k, v in out.items())
    return header + "\n" + "".join(text for _, text in ordered)


def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("dirs", nargs="+", help="directories holding one run's shard files")
    ap.add_argument("--out", required=True, help="where the merged files are written")
    a = ap.parse_args()

    # Grouped by (benchmark, label), both read from the provenance rather than parsed out of
    # the file name, and a file carrying neither is not ours.
    #
    # This is not a stylistic preference. The workflow tars sql/varka/bench/benchmarks whole,
    # so every artifact also carries the committed whole-surface results files from the
    # checkout. A name-based rule gave those a self-consistent group of their own - one file,
    # agreeing with itself on everything this tool checks - whose output path was the committed
    # file, so a chains merge overwrote published surface results and exited 0. Reading the
    # benchmark from inside the file makes a stray whole-surface file join the surface group,
    # where the shard-index check rejects it loudly, or be skipped outright when it predates
    # the key. The same rule keeps DateTimeBenchmark and DateVectorOpsBenchmark files, which
    # also match Date*-results.txt and have no provenance block at all, from being parsed.
    groups = OrderedDict()
    skipped = []
    for d in a.dirs:
        for root, _sub, names in os.walk(d):
            for n in sorted(names):
                if not n.endswith("-results.txt"):
                    continue
                path = os.path.join(root, n)
                try:
                    prov, _ = read(path)
                except SystemExit:
                    skipped.append(path)
                    continue
                if "benchmark" not in prov or "shard" not in prov:
                    skipped.append(path)
                    continue
                groups.setdefault((prov["benchmark"], prov.get("label", "?")), []).append(path)
    for path in skipped:
        print(f"skipped (not a shard of this tool's making): {path}")
    if not groups:
        raise SystemExit(
            f"no results file with a 'benchmark' and 'shard' provenance line under "
            f"{', '.join(a.dirs)}"
        )

    stems = {"surface": "DateSurface", "chains": "DateChain", "time": "TimeSurface"}
    os.makedirs(a.out, exist_ok=True)
    for (benchmark, label), files in groups.items():
        if benchmark not in stems:
            raise SystemExit(f"unknown benchmark {benchmark!r} in {files[0]}")
        text = merge_label(label, sorted(files))
        dest = os.path.join(a.out, f"{stems[benchmark]}-{label}-results.txt")
        with open(dest, "w", encoding="utf-8") as fh:
            fh.write(text)
        print(f"{benchmark} {label}: {len(files)} shard(s) -> {dest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
