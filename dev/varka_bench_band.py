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
# How far apart do repeated runs of one benchmark land, per case?
#
#   dev/varka_bench_band.py run1.txt run2.txt ...            # the band, per case
#   dev/varka_bench_band.py --split-half run1.txt ...        # does the band reproduce?
#   dev/varka_bench_band.py --write FILE run1.txt ...        # the committed band file
#
# The band is a TIER per case, not a spread per case, and that is what the
# split-half check decided. Over ten runs per width, *which* cases are noisy
# reproduces strongly - the worst quartile of one half is 37 of 52 the same cases
# in the other, against a chance of 13 - while *how* noisy a given case is
# reproduces only weakly at the wide width (correlation 0.325 against 0.728 at the
# narrow one). A threshold only has to know which cases deserve a loose one, so
# tiers respect what reproduces and do not invent the rest. Tier agreement between
# independent halves is 0.52 and 0.59 by Cohen's kappa, and 96% of cases agree
# within one tier.
#
# The boundaries are 3, 10 and 25 percent, which are the thresholds this project
# already reads by. A case above the top tier is not marked with a bigger number
# but called unreadable: a row that swings 227% between runs of an unchanged file
# cannot be read from a diff at all, and saying so is more use than a threshold
# nobody could act on.
#
# A regeneration's diff prints "moved 14.2%" against nothing, so every task since
# task 52 has had to run the whole file twice to tell its own change from the
# run's noise. This measures what an unchanged file does, which is the number the
# diff needs in order to say whether a move means anything.
#
# `--split-half` is the question that decides what the band can be. It splits the
# runs in two, measures each half independently, and reports how well one half's
# per-case spread predicts the other's. If it predicts well, a per-case band is
# meaningful and a quiet case may be held to a tighter threshold than a noisy
# one. If it does not - which is what `PLAN_MILESTONE_4.md`'s debt register
# suggests, recording "a different cluster each run" - then only a single
# file-level threshold is defensible, and claiming per-case precision would be
# inventing it.
#
# Keys are (table, case, occurrence). The occurrence index is not decoration:
# four sections of the parity file share one table header, and two rows in it
# share a case name as well, so a (table, case) pair is not unique and a keying
# that assumes it is silently merges rows.

import datetime
import pathlib
import re
import statistics
import subprocess
import sys


def _usage(code: int = 2) -> None:
    """Print this file's header comment, the way the shell tools answer --help."""
    lines = pathlib.Path(__file__).read_text().splitlines()
    for line in lines[16:]:
        if not line.startswith("#"):
            break
        print(line[2:] if line.startswith("# ") else line[1:])
    sys.exit(code)


if "-h" in sys.argv[1:] or "--help" in sys.argv[1:]:
    _usage(0)

ROW = re.compile(r"^(.*?)\s+(\d+)\s+(\d+)\s+(\d+)\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)X\s*$")
HEADER = re.compile(r"^(.*?):\s+Best Time\(ms\)")


def parse(path):
    """{(table, case, occurrence): rate} for one run's output."""
    rates, seen, table = {}, {}, ""
    with open(path, errors="replace") as f:
        for line in f:
            line = re.sub(r"^\[info\] ?", "", line.rstrip())
            h = HEADER.match(line)
            if h:
                table = h.group(1).strip()
                continue
            m = ROW.match(line)
            if m:
                base = (table, m.group(1).strip())
                seen[base] = seen.get(base, 0) + 1
                rates[base + (seen[base],)] = float(m.group(5))
    return rates


def spreads(runs):
    """{key: (spread_pct, lo, hi)} over the keys every run has."""
    common = set(runs[0])
    for r in runs[1:]:
        common &= set(r)
    out = {}
    for k in common:
        vs = [r[k] for r in runs]
        out[k] = ((max(vs) - min(vs)) / min(vs) * 100, min(vs), max(vs))
    return out


def summarise(label, sp):
    ps = sorted(v[0] for v in sp.values())
    print(f"\n== {label}: {len(ps)} cases")
    med, p90, worst = statistics.median(ps), ps[int(0.9 * len(ps))], ps[-1]
    print(f"   median {med:6.2f}%   p90 {p90:6.2f}%   max {worst:6.2f}%")
    for t in (3, 10, 20):
        print(f"   over {t:2d}%: {sum(1 for p in ps if p > t):4d}")


TIER_BOUNDS = (3.0, 10.0, 25.0)
DELIM = " | "


def tier_of(spread):
    """0 quiet, 1 moderate, 2 noisy, 3 not readable from a diff at all."""
    for i, b in enumerate(TIER_BOUNDS):
        if spread <= b:
            return i
    return len(TIER_BOUNDS)


def provenance(nruns):
    def sh(*cmd, stderr=False):
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, check=True)
            return (r.stderr if stderr else r.stdout).strip()
        except Exception:
            return "?"

    cpu = "?"
    try:
        with open("/proc/cpuinfo") as f:
            for line in f:
                if line.startswith("model name"):
                    cpu = line.split(":", 1)[1].strip()
                    break
    except OSError:
        pass
    return [
        f"runs:    {nruns}",
        f"commit:  {sh('git', 'rev-parse', '--short', 'HEAD')}",
        f"date:    {datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='seconds')}",
        f"jdk:     {(sh('java', '-version', stderr=True).splitlines() or ['?'])[0]}",
        f"cpu:     {cpu}",
    ]


def write_band(path, runs, sp):
    """One line per case: its tier, the spread measured, and the key.

    Keys are written `table | case | occurrence`, and the delimiter is asserted
    absent from both names rather than hoped absent - a name that contained it
    would silently split into a different key and the band would apply to the
    wrong row.
    """
    lines = []
    for k, (spread, lo, hi) in sorted(sp.items()):
        table, case, occ = k
        assert DELIM not in table and DELIM not in case, f"delimiter inside a name: {k}"
        lines.append(f"{tier_of(spread)} {spread:8.2f} {occ:3d}  {table}{DELIM}{case}")
    body = [
        f"Band for {len(sp)} cases: how far apart repeated runs of an unchanged file land.",
        "Written by dev/varka_bench_band.py. Read by dev/varka_bench_diff.py --band, which",
        "uses a case's tier to decide whether its move in a regeneration means anything.",
        "",
        "tier 0 <= 3%   1 <= 10%   2 <= 25%   3 not readable from a diff at all",
        "",
    ]
    body += provenance(len(runs)) + ["", "tier   spread occ  table | case"] + lines
    with open(path, "w") as f:
        f.write("\n".join(body) + "\n")
    counts = [0] * (len(TIER_BOUNDS) + 1)
    for spread, _, _ in sp.values():
        counts[tier_of(spread)] += 1
    print(f"wrote {path}: {len(sp)} cases, tiers {counts}")


def read_band(path):
    """{(table, case, occurrence): (tier, spread)} from a band file."""
    out = {}
    with open(path) as f:
        for line in f:
            m = re.match(r"^(\d) +([\d.]+) +(\d+)  (.*)$", line.rstrip())
            if not m:
                continue
            table, _, case = m.group(4).partition(DELIM)
            out[(table, case, int(m.group(3)))] = (int(m.group(1)), float(m.group(2)))
    return out


def main():
    args = sys.argv[1:]
    split = "--split-half" in args
    write_to = None
    if "--write" in args:
        i = args.index("--write")
        write_to = args[i + 1]
        args = args[:i] + args[i + 2 :]
    paths = [a for a in args if not a.startswith("--")]
    runs = [parse(p) for p in paths]
    for p, r in zip(paths, runs):
        print(f"{p}: {len(r)} rows")
    if len(runs) < 2:
        sys.exit("need at least two runs")

    if not split:
        sp = spreads(runs)
        summarise(f"band over {len(runs)} runs", sp)
        if write_to:
            write_band(write_to, runs, sp)
        print(f"\n{'worst cases':70s} {'lo':>9s} {'hi':>9s} {'spread':>8s}")
        for k, (p, lo, hi) in sorted(sp.items(), key=lambda kv: -kv[1][0])[:15]:
            print(f"{(k[1] + ' | ' + k[0])[:70]:70s} {lo:9.1f} {hi:9.1f} {p:7.1f}%")
        return

    half = len(runs) // 2
    a, b = spreads(runs[:half]), spreads(runs[half:])
    summarise(f"half A (runs 1-{half})", a)
    summarise(f"half B (runs {half + 1}-{len(runs)})", b)
    common = sorted(set(a) & set(b))
    xs = [a[k][0] for k in common]
    ys = [b[k][0] for k in common]
    # Does a case that was noisy in one half come out noisy in the other? Reported
    # two ways: the correlation of the spreads, and the overlap of the two halves'
    # worst quartiles, which is what a per-case band would actually rely on.
    try:
        r = statistics.correlation(xs, ys)
    except statistics.StatisticsError:
        r = float("nan")
    q = max(1, len(common) // 4)
    wa = {k for k in sorted(common, key=lambda k: -a[k][0])[:q]}
    wb = {k for k in sorted(common, key=lambda k: -b[k][0])[:q]}
    print(f"\n== does the band reproduce, over {len(common)} shared cases")
    print(f"   correlation of per-case spread, half A vs half B : {r:.3f}")
    print(
        f"   worst-quartile overlap                           : {len(wa & wb)}/{q}"
        f"  (chance is about {q // 4})"
    )


if __name__ == "__main__":
    main()
