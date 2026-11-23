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
# Reads the fork logs of dev/varka_kernel_history.sh (VARKA-257) and says, per case - the vector
# width, the template prewarm on or off, and N, the kernels warmed before the kernel under test -
# how often that kernel landed in a slow mode, and how often the history's own kernels were
# released by the warm-up still allocating, by their position in the history.
#   dev/varka_kernel_history.py --dir target/varka-kernel-history/<date-time>
#   dev/varka_kernel_history.py --dir DIR --guard --max-slow 0   # the nightly's verdict
#   dev/varka_kernel_history.py --selftest                       # the case keying's fixture
# A fork's kernel under test is slow by its rate, as the inlining-cliff reader cuts it (the median
# of its last three seconds over --slow-at nanoseconds a row: the loop past C1's limit), or by its
# allocation (over --alloc-at bytes a call in its last second: a C2 compile that kept a vector box
# per loop iteration, VARKA-295), and both are reported apart. A fork whose log has no DONE line,
# or whose JVM exited with a status other than the probe's own (0, 1 for a wrong answer, 2 for a
# harness error), did not finish; a fork whose timed calls declined is "declined", since its rate
# measured no loop. A wrong answer or a harness error, from any kernel of any fork, is listed, and
# under --guard fails, as do an unfinished or declined fork and more slow forks than --max-slow.
import argparse
import glob
import os
import re
import statistics
import sys
from collections import defaultdict

PREFIX = "VARKA_HISTORY_"
NAME = re.compile(r"width-(\w+)-prewarm-(\w+)-after-(\d+)-fork-(\d+)\.log$")


def fields(text):
    return dict(part.split("=", 1) for part in text.split() if "=" in part)


def read_fork(path, slow_at, alloc_at):
    m = NAME.search(os.path.basename(path))
    fork = {
        "path": path,
        "width": m.group(1),
        "prewarm": m.group(2),
        "after": int(m.group(3)),
        "fork": int(m.group(4)),
        "bits": None,
        "kernels": [],
        "test": {},
        "rates": [],
        "alloc": None,
        "code": "",
        "wrong": [],
        "errors": [],
        "done": None,
        "skipped": 0,
    }
    with open(path, errors="replace") as log:
        for raw in log:
            line = raw.strip()
            if not line.startswith(PREFIX):
                continue
            key, _, rest = line[len(PREFIX) :].partition("=")
            if key == "BEGIN":
                fork["bits"] = fields(rest).get("bits")
            elif key == "KERNEL":
                f = fields(rest)
                f["position"] = int(rest.split()[0])
                fork["kernels"].append(f)
            elif key == "SKIPPED":
                fork["skipped"] += 1
            elif key == "TEST":
                fork["test"] = fields(rest)
            elif key == "RATE":
                fork["rates"].append(float(rest.split()[1]))
            elif key == "ALLOC":
                fork["alloc"] = float(rest.split()[0])
            elif key == "CODE":
                fork["code"] = rest
            elif key == "WRONG":
                fork["wrong"].append(rest)
            elif key == "ERROR":
                fork["errors"].append(rest)
            elif key == "DONE":
                fork["done"] = fields(rest)
    exit_path = path[: -len(".log")] + ".exit"
    exit_status = None
    if os.path.exists(exit_path):
        with open(exit_path) as f:
            exit_status = f.read().strip()
    fork["case_width"] = case_width(fork)
    last = fork["rates"][-3:]
    fork["ns_per_row"] = statistics.median(last) if last else None
    if (
        fork["done"] is None
        or fork["ns_per_row"] is None
        or fork["alloc"] is None
        or exit_status not in (None, "0", "1", "2")
    ):
        fork["verdict"] = "unfinished"
    elif fork["done"].get("runStatus", "0") != "0":
        fork["verdict"] = "declined"
    elif fork["ns_per_row"] > slow_at:
        fork["verdict"] = "slow-rate"
    elif fork["alloc"] > alloc_at:
        fork["verdict"] = "slow-alloc"
    else:
        fork["verdict"] = "fast"
    return fork


# Positions in the history, bucketed, for the history kernels' own release counts.
BUCKETS = [(0, 0), (1, 3), (4, 15), (16, 63), (64, 1 << 30)]


def bucket(position):
    for lo, hi in BUCKETS:
        if lo <= position <= hi:
            return "%d" % lo if lo == hi else "%d-%s" % (lo, hi if hi < 1 << 30 else "")
    return "?"


def label(fork):
    """A fork's width as shown: its preferred int vector bits, once it printed them."""
    return "%s bits" % fork["bits"] if fork["bits"] else fork["width"]


def case_width(fork):
    """The width a fork's case is keyed by. A `host` fork's is the bits it printed, since one
    directory can merge shards from machines of different widths (benchmark.yml's history mode
    with a blank history-bits), and their file names all say `host`; a host fork that printed none
    stays plain `host`, a case of its own, which can only be unfinished. A width given by
    --widths is the fork's MaxVectorSize, the same on every machine, so it keys as named."""
    if fork["width"] == "host" and fork["bits"]:
        return "host-%s" % fork["bits"]
    return fork["width"]


def group_cases(forks):
    """The forks by case: the width they ran at (case_width), the prewarm, and N."""
    cases = defaultdict(list)
    for f in forks:
        cases[(f["case_width"], f["prewarm"], f["after"])].append(f)
    return cases


def selftest():
    """Shards merged from a 256-bit and a 512-bit machine, file names alike `host`, must read as
    two cases, and a fork that printed no bits as a third; a --widths fork keys as named."""
    import tempfile

    begin = {0: "bits=256", 1: "bits=512", 2: "bits=256", 3: None}
    with tempfile.TemporaryDirectory() as d:
        for fork, bits in begin.items():
            with open(
                os.path.join(d, "width-host-prewarm-on-after-64-fork-%d.log" % fork), "w"
            ) as f:
                if bits:
                    f.write("%sBEGIN=%s\n" % (PREFIX, bits))
        with open(os.path.join(d, "width-32-prewarm-on-after-64-fork-0.log"), "w") as f:
            f.write("%sBEGIN=bits=256\n" % PREFIX)
        forks = [read_fork(p, 40.0, 2048.0) for p in sorted(glob.glob(os.path.join(d, "*.log")))]
    got = {c: sorted(f["fork"] for f in g) for c, g in group_cases(forks).items()}
    want = {
        ("host-256", "on", 64): [0, 2],
        ("host-512", "on", 64): [1],
        ("host", "on", 64): [3],
        ("32", "on", 64): [0],
    }
    if got != want:
        sys.exit("varka_kernel_history selftest: cases %s, expected %s" % (got, want))
    print("varka_kernel_history selftest: ok")


def main():
    if "--selftest" in sys.argv[1:]:
        selftest()
        return
    parser = argparse.ArgumentParser(description="The slow mode's frequency after N kernels.")
    parser.add_argument("--dir", required=True)
    parser.add_argument("--slow-at", type=float, default=40.0, help="ns a row (default 40)")
    parser.add_argument(
        "--alloc-at", type=float, default=2048.0, help="bytes a call (default 2048)"
    )
    parser.add_argument("--guard", action="store_true")
    parser.add_argument("--max-slow", type=int, default=0)
    args = parser.parse_args()
    paths = sorted(glob.glob(os.path.join(args.dir, "width-*-fork-*.log")))
    if not paths:
        sys.exit("no fork log in %s" % args.dir)
    all_forks = [read_fork(p, args.slow_at, args.alloc_at) for p in paths]

    print(
        "%-28s %-11s %8s %9s  %s"
        % ("case (bits, prewarm, N)", "verdict", "ns/row", "bytes/call", "warm-up | loop tiers")
    )
    for f in sorted(
        all_forks, key=lambda f: (f["case_width"], f["prewarm"], f["after"], f["fork"])
    ):
        print(
            "%-28s %-11s %8s %9s  %s %sms | %s"
            % (
                "%s, %s, N=%d #%d" % (label(f), f["prewarm"], f["after"], f["fork"]),
                f["verdict"],
                "%.1f" % f["ns_per_row"] if f["ns_per_row"] is not None else "-",
                "%.0f" % f["alloc"] if f["alloc"] is not None else "-",
                f["test"].get("state", "-"),
                f["test"].get("millis", "-"),
                f["code"] or "-",
            )
        )

    print()
    widths = {f["case_width"]: label(f) for f in all_forks if f["bits"]}
    print(
        "the kernel under test: slow by rate / slow by allocation / fast / unfinished or declined"
    )
    cases = group_cases(all_forks)
    slow = 0
    for case in sorted(cases, key=lambda c: (c[0], c[1], c[2])):
        group = cases[case]
        n = defaultdict(int)
        for f in group:
            n[f["verdict"]] += 1
        slow += n["slow-rate"] + n["slow-alloc"]
        fast = [f["ns_per_row"] for f in group if f["verdict"] == "fast"]
        released = sum(1 for f in group if f["test"].get("state") == "RELEASED")
        print(
            "  %-9s prewarm %-3s, N=%-3d %3d / %3d / %3d / %3d   slow %d of %d%s%s"
            % (
                widths.get(case[0], case[0]),
                case[1],
                case[2],
                n["slow-rate"],
                n["slow-alloc"],
                n["fast"],
                n["unfinished"] + n["declined"],
                n["slow-rate"] + n["slow-alloc"],
                len(group),
                "; fast %.1f to %.1f ns/row" % (min(fast), max(fast)) if fast else "",
                "; warm-up released %d" % released if released else "",
            )
        )

    print()
    print("the history's kernels: released by the warm-up / warmed, by lane and position")
    # By lane too: the history alternates the lanes, int at even positions, so position 0, the
    # control, is an int kernel, and a long kernel that releases alone shows at every position.
    by_position = defaultdict(lambda: [0, 0, 0])
    for f in all_forks:
        for k in f["kernels"]:
            key = (f["case_width"], f["prewarm"], k.get("lane", "?"), bucket(k["position"]))
            by_position[key][1] += 1
            if k.get("state") == "RELEASED":
                by_position[key][0] += 1
                # Every loop method with C2 code in use: compiled and still allocating.
                if "tiers" in k and ":none" not in k["tiers"]:
                    by_position[key][2] += 1
    for key in sorted(by_position, key=lambda k: (k[0], k[1], k[2], int(k[3].split("-")[0]))):
        released, total, compiled = by_position[key]
        print(
            "  %-9s prewarm %-3s, %-4s positions %-6s %4d / %4d (%.1f%%), %d with every loop C2"
            % (
                widths.get(key[0], key[0]),
                key[1],
                key[2],
                key[3],
                released,
                total,
                100.0 * released / total,
                compiled,
            )
        )

    wrong = [(f, w) for f in all_forks for w in f["wrong"]]
    errors = [(f, e) for f in all_forks for e in f["errors"]]
    unfinished = sum(1 for f in all_forks if f["verdict"] in ("unfinished", "declined"))
    skipped = sum(f["skipped"] for f in all_forks)
    compared = sum(len(f["kernels"]) + (1 if f["done"] else 0) for f in all_forks)
    print()
    for f, w in wrong:
        print("WRONG ANSWER in %s: %s" % (os.path.basename(f["path"]), w))
    for f, e in errors:
        print("HARNESS ERROR in %s: %s" % (os.path.basename(f["path"]), e))
    print(
        "verdict: %d of %d forks slow%s; %d wrong answers%s in %d kernels compared%s"
        % (
            slow,
            len(all_forks),
            ", %d unfinished or declined" % unfinished if unfinished else "",
            len(wrong),
            ", %d harness errors" % len(errors) if errors else "",
            compared,
            "; %d draws skipped (declined or repeated)" % skipped if skipped else "",
        )
    )
    if args.guard and (wrong or errors or unfinished or slow > args.max_slow):
        sys.exit(1)


if __name__ == "__main__":
    main()
