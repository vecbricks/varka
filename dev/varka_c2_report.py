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
# What the JIT did with one class's methods, from a -XX:+LogCompilation log: for each
# compilation of a method of the class, which compiler made it or which failed, how many bytes
# of callees it inlined, how many intrinsics it applied and how many calls it left in the graph,
# how many allocations and boxes escape analysis eliminated, and which calls it refused to inline
# and why; and, per method, how often its C2 code was thrown away and made again.
#   java -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=run.xml ...
#   dev/varka_c2_report.py run.xml --holder SpecificUnsafeProjection
#   dev/varka_c2_report.py run.xml --holder SpecificUnsafeProjection --method 'Greatest_' --refusals
# The per-compile lines come first, then a summary per method: its C2 compiles, the allocations
# each eliminated, and the deoptimizations that hit its C2 code by reason. Comparing the summary
# of two runs is how a change to the code around a generated class shows up in what C2 made of
# it - the same inlining and fewer eliminated allocations is escape analysis failing, which a
# profile shows only as allocation; several C2 compiles of one method with traps between them
# is the deoptimization cycle, which a profile shows only as time. The calls left are the count
# that separates a vectorised loop from one running the Vector API's Java bodies: an intrinsic C2
# did not apply leaves a call, or the body it inlined in the intrinsic's place. A compilation that
# failed has no <nmethod> record, so its compiler is read from what it wrote: only C2 writes
# late-inline, loop-tree and register-allocation records. A <failure> inside a task whose
# <task_done> says success is a bailout C2 retried within the task - "retry without subsuming
# loads" is the usual one - and the task counts as a compile, with the retry noted.
# The log is read line by line: a C2 log of a few minutes' work is hundreds of megabytes.
import argparse
import re
import sys
from collections import Counter, defaultdict

ATTR = re.compile(r"(\w+)='([^']*)'")


def attributes(line):
    return dict(ATTR.findall(line))


def compilers(path):
    """compile_id -> (compiler, level) from the log's <nmethod> records, where they exist."""
    found = {}
    with open(path, errors="replace") as log:
        for line in log:
            if "<nmethod " in line:
                a = attributes(line)
                if "compile_id" in a:
                    found[a["compile_id"]] = (a.get("compiler", "?"), a.get("level", "?"))
    return found


def compiler_of(task):
    """Which compiler a task without an <nmethod> record - one that failed - belongs to."""
    if task["c2_marks"] or task["intrinsics"] or task["level"] == "4":
        return "c2"
    return "c1" if task["level"] in ("1", "2", "3") or task["failure"] else "?"


def compilations(path, holder, method):
    """Every compilation of a method of `holder` whose name matches `method`."""
    with open(path, errors="replace") as log:
        task = None
        klass, methods, pending = {}, {}, None
        for raw in log:
            line = raw.strip()
            if line.startswith("<task ") and "compile_id" in line:
                a = attributes(line)
                parts = a.get("method", "").split(" ")
                task = None
                if len(parts) > 1 and holder in parts[0] and method.search(parts[1]):
                    task = {
                        "id": a["compile_id"],
                        "method": parts[1],
                        "level": a.get("level", "?"),
                        "stamp": a.get("stamp", "?"),
                        "osr": a.get("compile_kind", "") == "osr",
                        "failure": None,
                        "retried": None,
                        "c2_marks": 0,
                        "inlined": 0,
                        "eliminated": 0,
                        "boxes": 0,
                        "intrinsics": 0,
                        "intrinsic_ids": Counter(),
                        "calls_left": 0,
                        "refused": Counter(),
                    }
                klass, methods, pending = {}, {}, None
            elif task is None:
                continue
            elif line.startswith("<klass "):
                a = attributes(line)
                klass[a["id"]] = a["name"].split("/")[-1]
            elif line.startswith("<method "):
                a = attributes(line)
                methods[a["id"]] = klass.get(a.get("holder"), "?") + "::" + a.get("name", "?")
            elif line.startswith("<call ") and "method=" in line:
                pending = methods.get(attributes(line)["method"], "?")
            elif line.startswith("<inline_fail"):
                reason = attributes(line).get("reason", "?").replace("&gt;", ">")
                task["refused"][(pending or "?", reason)] += 1
                pending = None
            elif line.startswith("<inline_success"):
                pending = None
            elif line.startswith("<failure"):
                task["failure"] = attributes(line).get("reason", "?")
            elif (
                line.startswith("<late_inline")
                or line.startswith("<loop_tree")
                or line.startswith("<regalloc")
            ):
                task["c2_marks"] += 1
            elif line.startswith("<intrinsic "):
                task["intrinsics"] += 1
                task["intrinsic_ids"][attributes(line).get("id", "?")] += 1
            elif line.startswith("<predicted_call") or line.startswith("<direct_call"):
                task["calls_left"] += 1
            elif line.startswith("<eliminate_allocation"):
                task["eliminated"] += 1
            elif line.startswith("<eliminate_boxing"):
                task["boxes"] += 1
            elif line.startswith("<task_done"):
                a = attributes(line)
                task["inlined"] = int(a.get("inlined_bytes", "0") or 0)
                if a.get("success") == "1" and task["failure"]:
                    task["retried"], task["failure"] = task["failure"], None
            elif line.startswith("</task>"):
                yield task
                task = None


def deoptimizations(path, compile_ids):
    """What happened to the compiled code of `compile_ids` at run time, outside any task.

    Returns a Counter of ("trap", reason, action) for the uncommon traps the running code hit,
    and of ("made not entrant",) for the code the JVM threw away - the events that, repeated,
    are the deoptimization cycle.
    """
    events = Counter()
    with open(path, errors="replace") as log:
        depth = 0
        for raw in log:
            line = raw.strip()
            if line.startswith("<task "):
                depth += 1
            elif line.startswith("</task>"):
                depth -= 1
            elif depth == 0 and line.startswith("<uncommon_trap ") and "compile_id=" in line:
                a = attributes(line)
                if a.get("compile_id") in compile_ids:
                    events[("trap", a.get("reason", "?"), a.get("action", "?"))] += 1
            elif depth == 0 and line.startswith("<make_not_entrant "):
                a = attributes(line)
                if a.get("compile_id") in compile_ids:
                    events[("made not entrant",)] += 1
    return events


def main():
    parser = argparse.ArgumentParser(description="What the JIT made of one class's methods.")
    parser.add_argument("log", help="a -XX:+LogCompilation log")
    parser.add_argument("--holder", required=True, help="a substring of the class's name")
    parser.add_argument("--method", default="", help="a pattern the method names must match")
    parser.add_argument("--refusals", action="store_true", help="list every refused inline")
    args = parser.parse_args()
    made_by = compilers(args.log)
    summary = defaultdict(list)
    c2_ids = defaultdict(set)
    count = 0
    for task in compilations(args.log, args.holder, re.compile(args.method)):
        count += 1
        compiler, level = made_by.get(task["id"], ("?", task["level"]))
        if compiler == "?":
            compiler = compiler_of(task)
        refused = sum(task["refused"].values())
        print(
            "%-7s %-24s %-3s %-2s L%s  inlined %6d  intrinsics %4d  calls left %5d"
            "  eliminated %3d  boxes %2d  refused %3d%s"
            % (
                task["id"],
                task["method"][:24],
                "osr" if task["osr"] else "",
                compiler,
                level,
                task["inlined"],
                task["intrinsics"],
                task["calls_left"],
                task["eliminated"],
                task["boxes"],
                refused,
                "  FAILED: %s" % task["failure"]
                if task["failure"]
                else "  retried: %s" % task["retried"]
                if task["retried"]
                else "",
            )
        )
        if args.refusals:
            for (callee, reason), n in task["refused"].most_common():
                print("        %3d  %-60s %s" % (n, callee[-60:], reason))
            for name, n in task["intrinsic_ids"].most_common(6):
                print("        %3d  intrinsic %s" % (n, name))
        if compiler == "c2" and not task["failure"]:
            summary[task["method"]].append(task["eliminated"])
            c2_ids[task["method"]].add(task["id"])
    if count == 0:
        sys.exit("no compilation of a method of %s in %s" % (args.holder, args.log))
    print("\nC2 compiles per method, the allocations each eliminated, and what hit the code:")
    for name in sorted(summary):
        events = deoptimizations(args.log, c2_ids[name])
        words = ", ".join(
            "%s x%d" % (" ".join(k), n) for k, n in sorted(events.items(), key=lambda kv: -kv[1])
        )
        print(
            "  %-24s %s%s"
            % (
                name[:24],
                " ".join(str(n) for n in summary[name]),
                ("  |  " + words) if words else "",
            )
        )


if __name__ == "__main__":
    main()
