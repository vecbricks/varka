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
# What C2 did with one class's methods, from a -XX:+LogCompilation log: for each compilation of
# a method of the class, which compiler made it, how many bytes of callees it inlined, how many
# allocations and boxes escape analysis eliminated, and which calls it refused to inline and why.
#   java -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=run.xml ...
#   dev/varka_c2_report.py run.xml --holder SpecificUnsafeProjection
#   dev/varka_c2_report.py run.xml --holder SpecificUnsafeProjection --method 'Greatest_' --refusals
# The per-compile lines come first, then a summary per method: its C2 compiles and the
# allocations each eliminated. Comparing the summary of two runs is how a change to the code
# around a generated class shows up in what C2 made of it - the same inlining and fewer
# eliminated allocations is escape analysis failing, which a profile shows only as allocation.
# The log is read line by line: a C2 log of a few minutes' work is hundreds of megabytes.
import argparse
import re
import sys
from collections import Counter, defaultdict

ATTR = re.compile(r"(\w+)='([^']*)'")


def attributes(line):
    return dict(ATTR.findall(line))


def compilers(path):
    """compile_id -> (compiler, level) from the log's <nmethod> records."""
    found = {}
    with open(path, errors="replace") as log:
        for line in log:
            if "<nmethod " in line:
                a = attributes(line)
                if "compile_id" in a:
                    found[a["compile_id"]] = (a.get("compiler", "?"), a.get("level", "?"))
    return found


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
                        "osr": a.get("compile_kind", "") == "osr",
                        "inlined": 0,
                        "eliminated": 0,
                        "boxes": 0,
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
            elif line.startswith("<eliminate_allocation"):
                task["eliminated"] += 1
            elif line.startswith("<eliminate_boxing"):
                task["boxes"] += 1
            elif line.startswith("<task_done"):
                task["inlined"] = int(attributes(line).get("inlined_bytes", "0") or 0)
            elif line.startswith("</task>"):
                yield task
                task = None


def main():
    parser = argparse.ArgumentParser(description="What C2 made of one class's methods.")
    parser.add_argument("log", help="a -XX:+LogCompilation log")
    parser.add_argument("--holder", required=True, help="a substring of the class's name")
    parser.add_argument("--method", default="", help="a pattern the method names must match")
    parser.add_argument("--refusals", action="store_true", help="list every refused inline")
    args = parser.parse_args()
    made_by = compilers(args.log)
    summary = defaultdict(list)
    count = 0
    for task in compilations(args.log, args.holder, re.compile(args.method)):
        count += 1
        compiler, level = made_by.get(task["id"], ("?", "?"))
        refused = sum(task["refused"].values())
        print(
            "%-7s %-24s %-3s L%s  inlined %6d  eliminated %3d  boxes %2d  refused %3d"
            % (
                task["id"],
                task["method"][:24],
                "osr" if task["osr"] else "",
                level,
                task["inlined"],
                task["eliminated"],
                task["boxes"],
                refused,
            )
        )
        if args.refusals:
            for (callee, reason), n in task["refused"].most_common():
                print("        %3d  %-60s %s" % (n, callee[-60:], reason))
        if compiler == "c2":
            summary[task["method"]].append(task["eliminated"])
    if count == 0:
        sys.exit("no compilation of a method of %s in %s" % (args.holder, args.log))
    print("\nC2 compiles per method, and the allocations each eliminated:")
    for name in sorted(summary):
        print("  %-24s %s" % (name[:24], " ".join(str(n) for n in summary[name])))


if __name__ == "__main__":
    main()
