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
# Where does the time go inside one Varka code path? Reads a JFR recording's execution
# samples and attributes each sample twice: to the phase of the emitter or compiler the stack
# is in, and to the nearest Varka frame to the top of the stack, so that work the JDK does on
# Varka's behalf (the Class-File API assembling a class, a HashMap under a slot planner) is
# charged to the Varka method that asked for it.
#
#   java -XX:StartFlightRecording=filename=emit.jfr,settings=profile ... VarkaEmitDump ...
#   jfr print --events jdk.ExecutionSample --stack-depth 64 emit.jfr | dev/varka_jfr_frames.py
#   dev/varka_jfr_frames.py --top 30 < samples.txt
#
# The phases are the emitter's, in the order they nest: a sample under Slots.plan is planning
# even though the planner is called from a body emitter, so the first phase whose pattern
# matches any frame of the stack wins. A sample with no Varka frame at all is "outside",
# which is the JVM's own work - compilation, GC - and the harness around the emission.
# Usage text is found rather than numbered: a hard-coded range silently truncates as the
# comment above it grows. Ends at the first line that is not a comment.
import re
import sys
from collections import Counter

VARKA = "org.apache.spark.sql.catalyst.expressions.codegen."
PHASES = [
    ("slot planning (Slots.plan)", r"\.Slots\.plan"),
    ("measuring the class (VarkaEmittedClass.measure)", r"\.VarkaEmittedClass\.measure"),
    (
        "debug info (renderLineMap, renderOutputs, debugElement)",
        r"\.(renderLineMap|renderOutputs|debugElement)",
    ),
    ("grouping (groupOutputs)", r"\.VarkaLoopEmitter\.groupOutputs"),
    ("analysis (Analysis.*)", r"\.Analysis\."),
    (
        "body emission (VarkaBodyEmitter, VarkaVectorWalk, lowerings)",
        r"\.(VarkaBodyEmitter|VarkaVectorWalk|Varka\w+Lowering|VarkaChrono)\.",
    ),
    ("class assembly (ClassFile.build under VarkaLoopEmitter.build)", r"\.VarkaLoopEmitter\.build"),
    ("the compiler (VarkaExpressionCompiler and families)", r"\.Varka\w*Compiler"),
    ("elsewhere in Varka", VARKA.replace(".", r"\.")),
]


def events(lines):
    """Yields each execution sample's frames, top of the stack first."""
    frames = None
    for line in lines:
        s = line.strip()
        if s.startswith("stackTrace = ["):
            frames = []
        elif frames is not None:
            if s.startswith("]"):
                yield frames
                frames = None
            elif s and s != "...":
                frames.append(s)


def frame_name(frame):
    """`pkg.Class.method(args) line: N` to `Class.method`, the package dropped."""
    head = frame.split("(")[0]
    parts = head.split(".")
    return ".".join(parts[-2:]) if len(parts) >= 2 else head


def main(argv):
    top = 25
    if "--top" in argv:
        top = int(argv[argv.index("--top") + 1])
    phases = Counter()
    owners = Counter()
    total = 0
    for frames in events(sys.stdin):
        total += 1
        phase = "outside Varka (JVM, harness)"
        for name, pattern in PHASES:
            if any(re.search(pattern, f) for f in frames):
                phase = name
                break
        phases[phase] += 1
        owner = next((frame_name(f) for f in frames if f.startswith(VARKA)), "(no Varka frame)")
        owners[owner] += 1
    if total == 0:
        sys.exit(
            "no jdk.ExecutionSample events read; print them with "
            "`jfr print --events jdk.ExecutionSample --stack-depth 64 <file>`"
        )
    print(f"{total} samples\n")
    print("by phase (the first phase whose frame the stack holds):")
    for name, n in phases.most_common():
        print(f"  {100.0 * n / total:5.1f}%  {n:6d}  {name}")
    print(f"\nby the nearest Varka frame to the top of the stack (top {top}):")
    for name, n in owners.most_common(top):
        print(f"  {100.0 * n / total:5.1f}%  {n:6d}  {name}")


if __name__ == "__main__":
    main(sys.argv[1:])
