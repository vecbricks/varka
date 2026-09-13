#!/usr/bin/env bash
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
# Which benchmark case ids are taken, and which is the next free one.
#
#   dev/varka_bench_ids.sh                        # every benchmark that has ids
#   dev/varka_bench_ids.sh VarkaArithmeticBenchmark
#
# A Varka benchmark names each emitted kernel by a case id, and two cases sharing
# one is otherwise a LinkageError from the class loader - or, since the emitters
# grew a `require`, a clear message twenty minutes into a regeneration, after
# every earlier case has been timed. This runs the same file with
# `-Dvarka.bench.dryRun=true`, which emits and registers every case and times
# none, so the collision is reported in about as long as a JVM takes to start.
#
# Use it before adding a case. Reading the file is not a reliable substitute:
# not every id is a literal - the parity file's trunc block computes `id` and
# `id + 1` from a tuple list - so a grep can miss one and hand you an id that is
# already taken. Ids stay explicit rather than auto-assigned on purpose: they
# become emitted class names that dumps and provenance refer to, so inserting a
# case must not renumber the ones after it.
#
# The id space is per file, not global: each benchmark names its kernels with its
# own class prefix - VarkaFusedBench for the parity file, VarkaArithBench for the
# arithmetic one - so the same number in two files is not a collision. Read each
# block below against the file it names.
#
# It measures nothing. A dry run says the file's structure is sound and says
# nothing whatever about its numbers; it is not a faster regeneration.
set -euo pipefail
# Usage text is found rather than numbered: a hard-coded range silently truncates as the
# comment above it grows, which had already happened to four of these scripts. Ends at the
# first line that is not a comment.
usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }
case "${1:-}" in -h|--help) usage 0 ;; esac

root="$(git rev-parse --show-toplevel)"; cd "$root"

benches=("$@")
if [ "${#benches[@]}" -eq 0 ]; then
  benches=(VarkaEmitterParityBenchmark VarkaArithmeticBenchmark)
fi

status=0
for b in "${benches[@]}"; do
  echo "== $b"
  out="$(build/sbt -batch "project catalyst" \
    'set Test/javaOptions += "-Dvarka.bench.dryRun=true"' \
    "Test/runMain org.apache.spark.sql.$b" 2>&1)" || {
      echo "$out" | grep -E 'already in use|Exception|error]' | head -5
      echo "   FAILED: see the message above"
      status=1
      continue
    }
  echo "$out" | grep -E 'varka-bench-ids:|varka-bench-next-free-id:' | sed 's/^\[info\] //;s/^/   /'
done
exit "$status"
