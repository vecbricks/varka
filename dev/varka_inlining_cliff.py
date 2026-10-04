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
# Reads the logs of dev/varka_inlining_cliff.sh - the forks' marker lines, their PrintInlining
# and PrintIntrinsics output, and the compile log each fork wrote beside them - and says, per
# fork, which side of the cliff the kernel landed on and what the JIT made of its loop method
# (VARKA-209.md 2).
#   dev/varka_inlining_cliff.py target/varka-inlining-cliff/<date>/width-host-xbatch-off.log ...
#   dev/varka_inlining_cliff.py --slow-at 40 <log>...    # the verdict's cut, in nanoseconds a row
#   dev/varka_inlining_cliff.py --fail-if-slow <log>...  # exit 1 on a slow fork: the nightly guard
# A fork is one VarkaInliningCliffProbe child; its lines run from its VARKA_CLIFF_BEGIN line to
# the next fork's, so the lines a JVM prints after its DONE line while shutting down are its own.
# Per fork it reads the case (outputs, fused ceiling, C1 on or off, -Xbatch or not), the loop
# methods with their bytes and vector call sites, the rate of the last seconds - the verdict is
# fast or slow by a cut an order of magnitude between the two sides VARKA-198 measured, about 4
# and 250 nanoseconds a row - the allocation per call, and C2's words: the reasons it printed for
# refusing an inline, and the three answers PrintIntrinsics gives for a vector call. From the
# fork's c2-pid<pid>.xml it reads what each compiler made of the dense loop method: whether C1
# compiled it or failed, how many times C2 compiled it and with what, whether C2 failed or never
# came, and the traps that hit C2's code - several C2 compiles with traps between them being the
# deoptimization cycle. The summary groups forks by case, and the point of it is the per-verdict
# split: a fact that holds in every slow fork and no fast one is the mechanism, and a case with
# both verdicts is the cliff's per-run face.
import argparse
import os
import re
import statistics
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import varka_c2_report

BEGIN = "VARKA_CLIFF_BEGIN="
METHODS = "VARKA_CLIFF_METHODS="
RATE = "VARKA_CLIFF_RATE="
ALLOC = "VARKA_CLIFF_ALLOC="
DONE = "VARKA_CLIFF_DONE="
LOOP = "loopDense0"

# The reasons C2 prints for refusing an inline, as substrings of PrintInlining's lines. The first
# four are budgets - a graph or a method grown past a limit - and the rest are about the callee.
REFUSALS = [
    "NodeCountInliningCutoff",
    "LiveNodeCountInliningCutoff",
    "DesiredMethodLimit",
    "too big",
    "already compiled into a big method",
    "callee is too large",
    "inlining too deep",
    "recursive inlining",
    "failed to inline (intrinsic)",
    "not inlineable",
    "low call site frequency",
]
INTRINSIC_ANSWERS = ["** not supported", "** missing constant", "** unbox failed"]


def strip(line):
    """A log line without sbt's prefix."""
    return re.sub(r"^\[(info|warn|error)\] ?", "", line.rstrip("\n"))


def forks(lines):
    """The forks of a log, each as (header, lines), split at the BEGIN lines."""
    found = []
    for raw in lines:
        line = strip(raw)
        if BEGIN in line:
            found.append((line.split(BEGIN, 1)[1].strip(), []))
        elif found:
            found[-1][1].append(line)
    return found


def read_fork(header, lines, slow_at):
    fields = dict(part.split("=", 1) for part in header.split() if "=" in part)
    fork = {
        "name": header.split()[0],
        "outputs": int(fields.get("outputs", "0")),
        "ceiling": int(fields.get("ceiling", "0")),
        "c1": fields.get("c1", "?"),
        "xbatch": fields.get("xbatch", "?"),
        "shape": fields.get("shape", "cheap"),
        "directive": fields.get("directive", "none"),
        # The emitter's call-site budget the fork emitted under (VARKA-209); logs from before
        # the budget existed carry no field, and their forks read as budget-less.
        "budget": fields.get("budget"),
        "heavy": fields.get("heavy"),
        "pid": fields.get("pid", "?"),
        "methods": [],
        "rates": [],
        "alloc": None,
        "done": False,
        "refusals": Counter(),
        "answers": Counter(),
    }
    for line in lines:
        text = line.strip()
        if METHODS in text:
            for item in text.split(METHODS, 1)[1].split(","):
                name, size, sites = item.split(":")
                fork["methods"].append((name, int(size), int(sites)))
        elif RATE in text:
            fork["rates"].append(float(text.split(RATE, 1)[1].split()[1]))
        elif ALLOC in text:
            fork["alloc"] = float(text.split(ALLOC, 1)[1].split()[0])
        elif DONE in text:
            fork["done"] = True
        else:
            for reason in REFUSALS:
                if reason in text:
                    fork["refusals"][reason] += 1
            for answer in INTRINSIC_ANSWERS:
                if text.startswith(answer):
                    fork["answers"][answer] += 1
    last = fork["rates"][-3:]
    fork["ns_per_row"] = statistics.median(last) if last else None
    # The second from which every later rate is fast, or None: when the kernel settled, which a
    # verdict read from the last seconds alone cannot say.
    fork["settled"] = None
    for i in range(len(fork["rates"])):
        if all(r <= slow_at for r in fork["rates"][i:]):
            fork["settled"] = i + 1
            break
    if not fork["done"] or fork["ns_per_row"] is None:
        fork["verdict"] = "unfinished"
    elif fork["ns_per_row"] > slow_at:
        fork["verdict"] = "slow"
    else:
        fork["verdict"] = "fast"
    return fork


def read_compile_log(fork, directory):
    """What the JIT made of the fork's dense loop method, from its c2-pid<pid>.xml.

    C1's outcome - compiled, or failed with the compiler's reason; every standard C2
    compilation, in order, with when it came, the intrinsics it applied, of them the vector
    operations, the calls it left in the graph, and the inlines it refused by reason, or its
    failure; and the traps and discards that hit C2's code at run time. A fork whose compile
    log is missing gets nothing, not an error: the census reads the same way with or without
    the compile logs.
    """
    path = os.path.join(directory, "c2-pid%s.xml" % fork["pid"])
    if not os.path.exists(path):
        return
    made_by = varka_c2_report.compilers(path)
    jit = {"c1": None, "c2": [], "osr": 0}
    for task in varka_c2_report.compilations(path, "VarkaCliffProbe_", re.compile("^%s$" % LOOP)):
        compiler = made_by.get(task["id"], ("?",))[0]
        if compiler == "?":
            compiler = varka_c2_report.compiler_of(task)
        if compiler == "c1":
            jit["c1"] = task["failure"] or "ok"
        elif compiler == "c2" and task["osr"]:
            jit["osr"] += 1
        elif compiler == "c2":
            ids = task["intrinsic_ids"]
            refused = Counter()
            for (_, reason), n in task["refused"].items():
                refused[reason] += n
            jit["c2"].append(
                {
                    "id": task["id"],
                    "stamp": task["stamp"],
                    "failure": task["failure"],
                    "retried": task["retried"],
                    "intrinsics": task["intrinsics"],
                    "vector_ops": ids["_VectorBinaryOp"]
                    + ids["_VectorUnaryOp"]
                    + ids["_VectorTernaryOp"],
                    "calls_left": task["calls_left"],
                    "refused": refused,
                }
            )
    compiled = {c["id"] for c in jit["c2"] if not c["failure"]}
    jit["events"] = varka_c2_report.deoptimizations(path, compiled) if compiled else Counter()
    fork["jit"] = jit


def describe_jit(jit):
    """One clause per compiler for the loop method's JIT record."""
    if jit is None:
        return "no compile log"
    c1 = jit["c1"]
    if c1 == "ok":
        words = ["C1 ok"]
    elif c1:
        words = ["C1 failed: %s" % c1]
    else:
        words = ["C1 none"]
    good = [c for c in jit["c2"] if not c["failure"]]
    failed = [c for c in jit["c2"] if c["failure"]]
    if not jit["c2"]:
        words.append("C2 none")
    else:
        if good:
            last = good[-1]
            retried = [c for c in good if c["retried"]]
            words.append(
                "C2 x%d%s%s, first at %ss: %d intrinsics, %d vector ops, %d calls left, refused %s"
                % (
                    len(good),
                    " (+%d OSR)" % jit["osr"] if jit["osr"] else "",
                    " (%d after a retry: %s)"
                    % (len(retried), "; ".join(sorted({c["retried"] for c in retried})))
                    if retried
                    else "",
                    good[0]["stamp"],
                    last["intrinsics"],
                    last["vector_ops"],
                    last["calls_left"],
                    "; ".join("%s x%d" % kv for kv in last["refused"].most_common(2)) or "nothing",
                )
            )
        if failed:
            words.append(
                "C2 failed x%d: %s"
                % (len(failed), "; ".join(sorted({c["failure"] for c in failed})))
            )
    if jit["events"]:
        words.append(
            "run time: "
            + ", ".join(
                "%s x%d" % (" ".join(k), n)
                for k, n in sorted(jit["events"].items(), key=lambda kv: -kv[1])[:4]
            )
        )
    return "; ".join(words)


def jit_facts(jit):
    """The facts the summary counts per fork about the loop method."""
    c1 = jit["c1"]
    good = [c for c in jit["c2"] if not c["failure"]]
    failed = [c for c in jit["c2"] if c["failure"]]
    facts = ["C1 " + ("ok" if c1 == "ok" else "failed" if c1 else "none")]
    if not good and not failed:
        facts.append("C2 none")
    if good:
        facts.append("C2 compiled once" if len(good) == 1 else "C2 recompiled")
        if any(c["retried"] for c in good):
            facts.append("after a retry")
    if failed:
        facts.append("C2 failed")
    if any(k[0] == "trap" for k in jit["events"]):
        facts.append("trapped")
    return facts


def case_of(fork):
    extras = ""
    if fork["shape"] != "cheap":
        extras += ", " + fork["shape"]
    if fork["directive"] != "none":
        extras += ", directive " + fork["directive"]
    if fork["budget"] is not None:
        extras += ", budget " + ("off" if fork["budget"] == "0" else fork["budget"])
    if fork["heavy"] is not None:
        extras += ", heavy groups " + ("split" if fork["heavy"] == "0" else "to " + fork["heavy"])
    return "%d outputs, ceiling %d, c1 %s, xbatch %s%s" % (
        fork["outputs"],
        fork["ceiling"],
        fork["c1"],
        fork["xbatch"],
        extras,
    )


def main():
    parser = argparse.ArgumentParser(description="Which side of the cliff each fork landed on.")
    parser.add_argument("logs", nargs="+")
    parser.add_argument(
        "--slow-at",
        type=float,
        default=40.0,
        help="nanoseconds a row above which a fork is slow (default 40)",
    )
    parser.add_argument(
        "--fail-if-slow",
        action="store_true",
        help="exit 1 when any fork is slow at the end or did not finish: the nightly guard",
    )
    args = parser.parse_args()
    all_forks = []
    for path in args.logs:
        with open(path, errors="replace") as log:
            found = [read_fork(h, ls, args.slow_at) for h, ls in forks(log)]
        for fork in found:
            read_compile_log(fork, os.path.dirname(os.path.abspath(path)))
        all_forks += found
    if not all_forks:
        sys.exit("no fork in %s" % ", ".join(args.logs))
    print(
        "%-44s %-10s %9s %12s  %s"
        % ("case", "verdict", "ns/row", "bytes/call", "loop methods | C2's words | the compile log")
    )
    for fork in all_forks:
        methods = " ".join("%s=%d:%d" % m for m in fork["methods"] if m[0].startswith("loop"))
        words = "; ".join("%s x%d" % kv for kv in fork["refusals"].most_common())
        answers = "; ".join("%s x%d" % kv for kv in fork["answers"].most_common())
        print(
            "%-44s %-10s %9s %12s  %s | %s | %s | %s"
            % (
                case_of(fork),
                fork["verdict"],
                "%.1f" % fork["ns_per_row"] if fork["ns_per_row"] is not None else "-",
                "%.0f" % fork["alloc"] if fork["alloc"] is not None else "-",
                (
                    "settled at %ds of %d; " % (fork["settled"], len(fork["rates"]))
                    if fork["settled"]
                    else "never settled in %ds; " % len(fork["rates"])
                )
                + methods,
                words or "no refusal",
                answers or "no intrinsic answer",
                describe_jit(fork.get("jit")),
            )
        )
    print()
    print("summary: slow / fast / unfinished forks per case, and what held in each verdict")
    by_case = defaultdict(list)
    for fork in all_forks:
        by_case[case_of(fork)].append(fork)
    for case in sorted(by_case, key=lambda c: (int(c.split()[0]), c)):
        group = by_case[case]
        counts = Counter(f["verdict"] for f in group)
        print(
            "  %-44s %3d / %3d / %3d" % (case, counts["slow"], counts["fast"], counts["unfinished"])
        )
        for verdict in ("slow", "fast"):
            reasons = Counter()
            answers = Counter()
            facts = Counter()
            traps = Counter()
            ops = []
            left = []
            rates = []
            settled = []
            for fork in group:
                if fork["verdict"] != verdict:
                    continue
                reasons.update(fork["refusals"])
                answers.update(fork["answers"])
                rates.append(fork["ns_per_row"])
                settled.append(fork["settled"] or 0)
                jit = fork.get("jit")
                if jit:
                    facts.update(jit_facts(jit))
                    for k, n in jit["events"].items():
                        if k[0] == "trap":
                            traps[k[1]] += n
                    good = [c for c in jit["c2"] if not c["failure"]]
                    if good:
                        ops.append(good[-1]["vector_ops"])
                        left.append(good[-1]["calls_left"])
            if not rates:
                continue
            print(
                "      %-5s %.1f to %.1f ns/row, settled at seconds %s | %s | %s"
                % (
                    verdict,
                    min(rates),
                    max(rates),
                    sorted(set(settled)) if verdict == "fast" else "-",
                    ", ".join("%s x%d" % kv for kv in reasons.most_common()) or "no refusal",
                    ", ".join("%s x%d" % kv for kv in answers.most_common()) or "no answer",
                )
            )
            if facts:
                line = "            %s: %s" % (
                    LOOP,
                    ", ".join("%s x%d" % kv for kv in sorted(facts.items())),
                )
                if ops:
                    line += "; C2's vector ops %s, calls left %s" % (
                        sorted(set(ops)),
                        sorted(set(left)),
                    )
                if traps:
                    line += "; traps %s" % ", ".join("%s x%d" % kv for kv in traps.most_common(4))
                print(line)
    # The guard's verdict, last and on its own line, as dev/varka_deopt_cycle.py prints its own:
    # a fork slow at the end is the cliff, and a fork that did not finish is unread, which the
    # guard treats as a failure too rather than as a pass by silence.
    slow = sum(1 for f in all_forks if f["verdict"] == "slow")
    unfinished = sum(1 for f in all_forks if f["verdict"] == "unfinished")
    print()
    print(
        "verdict: %d of %d forks slow%s"
        % (slow, len(all_forks), ", %d unfinished" % unfinished if unfinished else "")
    )
    if args.fail_if_slow and (slow or unfinished):
        sys.exit(1)


if __name__ == "__main__":
    main()
