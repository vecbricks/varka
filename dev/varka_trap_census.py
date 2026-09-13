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
# Split LogCompilation's uncommon_trap elements into the two different things
# that share that name, and report per-method deoptimisation and compile counts.
#
#   JAVA_OPTS="-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=jit.log" <run>
#   dev/varka_trap_census.py jit.log
#
# Why this exists. A self-closing `<uncommon_trap .../>` inside a `<parse>` tree
# is a guard the compiler INSERTED while building the method: it says a guard
# exists, not that anything failed. C2 emits a fixed block of them per counted
# loop compile - `predicate`, `profile_predicate`, `loop_limit_check`,
# `auto_vectorization_check` - so their counts move with the number of compiles
# and tie to each other, and a trivial summing loop that never deoptimises emits
# four. A runtime deoptimisation is a different element: an open
# `<uncommon_trap thread=... compile_id=... level=...>` carrying a nested
# `<jvms method='...'/>` frame, usually followed by `<make_not_entrant>`.
#
# Counting the first kind and reading it as the second is the mistake
# `PLAN_MILESTONE_4.md` 2.39 made and this script exists to make impossible; its
# own tell was that the per-method count equalled the compile count the same
# section reported two paragraphs earlier.
#
# The `<jvms>` frame carries a literal method name, so it is what attributes a
# deoptimisation. The numeric `<klass>`/`<method>` ids that need a per-unit
# lookup appear only inside the compile-time `<parse>` tree, which this script
# does not attribute.
import collections
import pathlib
import re
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

path = sys.argv[1]
ins = collections.Counter()  # compile-time insertions, by reason
rt = collections.Counter()  # runtime deopts, by reason
rt_frames = collections.Counter()  # runtime deopts, by the method they hit
queued = 0
nmethods = collections.Counter()  # compiles per method name
pending_reason = None

trap_re = re.compile(r"<uncommon_trap\b([^>]*?)(/?)>")
attr_re = re.compile(r"(\w+)='([^']*)'")
jvms_re = re.compile(r"<jvms\b([^>]*)/>")
nm_re = re.compile(r"<nmethod\b([^>]*)/>")

with open(path, errors="replace") as f:
    for line in f:
        if "<task_queued" in line:
            queued += 1
        m = nm_re.search(line)
        if m:
            a = dict(attr_re.findall(m.group(1)))
            if "method" in a:
                nmethods[a["method"]] += 1
        m = trap_re.search(line)
        if m:
            a = dict(attr_re.findall(m.group(1)))
            reason = a.get("reason", "?")
            if "thread" in a:  # runtime deoptimisation
                rt[reason] += 1
                pending_reason = reason
            else:  # compile-time insertion
                ins[reason] += 1
                pending_reason = None
        elif pending_reason:
            m = jvms_re.search(line)
            if m:
                a = dict(attr_re.findall(m.group(1)))
                rt_frames[(pending_reason, a.get("method", "?"))] += 1
                pending_reason = None


def show(title, counter, n=12):
    print(f"\n== {title} (total {sum(counter.values())})")
    for k, v in counter.most_common(n):
        print(f"{v:8d}  {k}")


show("compile-time trap INSERTIONS, by reason", ins)
show("runtime DEOPTIMISATIONS, by reason", rt)
print(f"\n== runtime deoptimisations, by frame (total {sum(rt_frames.values())})")
for (reason, meth), v in rt_frames.most_common(20):
    print(f"{v:8d}  {reason:24s} {meth}")
show("compiles per method (nmethod)", nmethods, 12)
print(f"\ntask_queued elements: {queued}")
