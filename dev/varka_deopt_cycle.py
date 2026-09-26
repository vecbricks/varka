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
# Reads the log of dev/varka_deopt_cycle.sh - the forks' -XX:+PrintCompilation and
# -Xlog:deoptimization=debug output - and says, per fork, whether the kernel's dense loop
# methods compiled once or entered the C2 deoptimization cycle (PLAN_MILESTONE_6.md 2.12).
#
#   dev/varka_deopt_cycle.py target/varka-deopt-cycle/<date>/width-16.log ...
#   dev/varka_deopt_cycle.py --fail-on-cycle <log>...    # the nightly guard's form
#
# With --fail-on-cycle the exit status is 1 when any fork is in the cycle, when a fork did not
# finish, or when the logs hold no fork at all: a guard that read nothing must not pass.
#
# A fork is one VarkaDeoptCycleProbe child; its lines run from its VARKA_DEOPT_METHODS line to
# the next fork's, so the compiles its JVM prints after VARKA_DEOPT_DONE while shutting down are
# its own, and the class name in them carries the case. Per fork it counts,
# for the class's loopDense methods, the tier-4 compiles (standard and OSR), the "made not
# entrant" lines and the profile_predicate deoptimizations, and reads the last per-second rate
# the child printed. The verdict is from the JVM's words, never from the rate: a fork is in the
# cycle when a loopDense method was made not entrant three or more times and trapped at
# profile_predicate more than four times. A method that settles traps at most four times at its
# loop head (PerBytecodeTrapLimit), is made not entrant once and recompiled, and may see a third
# standard compile beside an OSR one while its callers warm up; the cycle traps again at the back
# edge on every version and is made not entrant every C2 compile time. The trap bcis are printed
# so the head and the back edge can be told apart, and the rate beside them.
import re
import sys
from collections import defaultdict

CLASS = re.compile(r"(VarkaDeoptProbe_\d+_[a-z]+(?:_[a-z0-9]+)?)")
# PrintCompilation: <ms> <cid> <flags> <tier> Class::method (bytes) [note]
COMPILE = re.compile(
    r"^\s*(\d+)\s+(\d+)\s+([%sbn! ]*?)\s*(\d)\s+"
    r"\S*(VarkaDeoptProbe_\d+_[a-z]+(?:_[a-z0-9]+)?)::(\w+)"
    r"(?: @ \d+)? \((\d+) bytes\)(.*)$"
)
DEOPT = re.compile(
    r"\[deoptimization\] cid=(\d+)\s+level=(\d)\s+"
    r"\S*(VarkaDeoptProbe_\d+_[a-z]+(?:_[a-z0-9]+)?)\.(\w+)\("
    r"[^)]*\)\S*\s+trap_bci=(\d+)\s+(\S+)\s+(\S+)"
)


def canon(name):
    """A fork's case: forks from before the path argument existed are the batches path."""
    return name if name.count("_") >= 3 else name + "_batches"


def forks(lines):
    """Yields (class, lines) per fork: from its METHODS line to the next fork's.

    A fork's JVM keeps printing after its DONE line - compiles still queued when it shuts down -
    and every fork of a case prints the same class name, so the boundary is the next fork's first
    line, not this fork's last. A fork without a DONE line is reported as unfinished.
    """
    found = []
    for line in lines:
        line = re.sub(r"^\[info\] ", "", line.rstrip("\n"))
        if "VARKA_DEOPT_METHODS=" in line:
            found.append({"lines": [], "done": None})
        if not found:
            continue
        found[-1]["lines"].append(line)
        if "VARKA_DEOPT_DONE=" in line:
            found[-1]["done"] = CLASS.search(line)
    for fork in found:
        if fork["done"]:
            yield canon(fork["done"].group(1)), fork["lines"]
        else:
            m = next((CLASS.search(l) for l in fork["lines"] if CLASS.search(l)), None)
            yield ((canon(m.group(1)) if m else "?") + " (unfinished)"), fork["lines"]


def judge(name, lines):
    tier4 = defaultdict(int)
    osr = defaultdict(int)
    not_entrant = defaultdict(int)
    traps = defaultdict(int)
    trap_bcis = defaultdict(set)
    rates = []
    for line in lines:
        m = COMPILE.match(line)
        if m and canon(m.group(5)) == name.split(" ")[0] and m.group(6).startswith("loopDense"):
            method, tier, flags, note = m.group(6), m.group(4), m.group(3), m.group(8)
            if "made not entrant" in note:
                not_entrant[method] += 1
            elif "COMPILE SKIPPED" in note:
                pass
            elif tier == "4":
                tier4[method] += 1
                if "%" in flags:
                    osr[method] += 1
            continue
        m = DEOPT.search(line)
        if m and canon(m.group(3)) == name.split(" ")[0] and m.group(4).startswith("loopDense"):
            if m.group(6) == "profile_predicate":
                traps[m.group(4)] += 1
                trap_bcis[m.group(4)].add(int(m.group(5)))
            continue
        if "VARKA_DEOPT_RATE=" in line:
            rates.append(float(line.split("VARKA_DEOPT_RATE=")[1].split()[1]))
    methods = sorted(set(tier4) | set(traps) | set(not_entrant))
    cycling = [m for m in methods if not_entrant[m] >= 3 and traps[m] > 4]
    return {
        "cycle": bool(cycling),
        "methods": methods,
        "tier4": tier4,
        "osr": osr,
        "not_entrant": not_entrant,
        "traps": traps,
        "bcis": trap_bcis,
        "rate": rates[-1] if rates else float("nan"),
        "cycling": cycling,
    }


def main(paths, fail_on_cycle=False):
    summary = defaultdict(lambda: [0, 0])
    print(
        "case                        fork  verdict  loopDense tier-4 compiles (osr) / "
        "not entrant / profile_predicate traps [bcis]   last rate M/s"
    )
    for path in paths:
        width = re.search(r"width-(\d+)", path)
        width = width.group(1) if width else "?"
        counts = defaultdict(int)
        with open(path) as f:
            for name, lines in forks(f):
                counts[name] += 1
                r = judge(name, lines)
                case = f"{name} @{width}"
                summary[case][0] += 1
                summary[case][1] += 1 if r["cycle"] else 0
                detail = "; ".join(
                    f"{m} {r['tier4'][m]}({r['osr'][m]})/{r['not_entrant'][m]}/{r['traps'][m]}"
                    f"{sorted(r['bcis'][m]) if r['bcis'][m] else ''}"
                    for m in r["methods"]
                )
                print(
                    f"{case:27} {counts[name]:4}  {'CYCLE' if r['cycle'] else 'once ':7} "
                    f"{detail}   {r['rate']:.1f}"
                )
    print()
    print("summary: forks in the cycle / forks")
    for case in sorted(summary):
        total, cyc = summary[case]
        print(f"  {case:27} {cyc:3} / {total}")
    forks_read = sum(total for total, _ in summary.values())
    cycling = sum(cyc for _, cyc in summary.values())
    unfinished = sum(total for case, (total, _) in summary.items() if "(unfinished)" in case)
    print(
        f"verdict: {cycling} of {forks_read} forks in the cycle"
        + (f", {unfinished} unfinished" if unfinished else "")
    )
    if fail_on_cycle and (cycling or unfinished or forks_read == 0):
        return 1
    return 0


if __name__ == "__main__":
    args = sys.argv[1:]
    fail = "--fail-on-cycle" in args
    logs = [a for a in args if a != "--fail-on-cycle"]
    if not logs:
        sys.exit("usage: dev/varka_deopt_cycle.py [--fail-on-cycle] <log>...")
    sys.exit(main(logs, fail))
