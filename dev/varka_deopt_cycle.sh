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
# Does a kernel's dense loop compile once, or enter the C2 deoptimization cycle
# (PLAN_MILESTONE_6.md 2.12, task 189)? Forks fresh JVMs of VarkaDeoptCycleProbe -
# the make_date ladder's kernel at a given width, in either epilogue form, driven over
# null-free batches - under -XX:+PrintCompilation and -Xlog:deoptimization=debug, and
# reads the verdict per fork from those logs with dev/varka_deopt_cycle.py.
#
#   dev/varka_deopt_cycle.sh                                  # 12 outputs, both forms, both widths, 20 forks
#   dev/varka_deopt_cycle.sh --outputs 13,16,60 --forms group --forks 10
#   dev/varka_deopt_cycle.sh --widths 16 --seconds 12
#   dev/varka_deopt_cycle.sh --paths warmup                   # task 212's path to C2
#   dev/varka_deopt_cycle.sh --forms group --forks 10 --fail-on-cycle   # the nightly guard
#
# --widths is in bytes, as the JVM's MaxVectorSize counts them: 64 is 512 bits, 16 is 128.
# --forms: single (one epilogue over every output, the emission before task 87) and group
# (the epilogue per group, the default since). --paths: batches, the kernel's batches from its
# first call, or warmup, the path task 212 gives a new kernel - C1 kept off the class and
# twelve thousand 32-row calls before the first batch. Each width is one sbt session with one
# forked JVM per case and fork; the logs and the summary go under
# target/varka-deopt-cycle/<date-time>/. Fresh JVMs because the cycle is decided at or
# near a method's first C2 compile and is then stable for the JVM's life, so forks, not
# iterations, are the sample. Not a benchmark: the rate the summary shows is a probe's
# reading, and the verdict is the JVM's own compile and trap log. --fail-on-cycle makes the exit
# status 1 when any fork cycles, fails to finish, or no fork is read at all.
# Usage text is found rather than numbered: a hard-coded range silently truncates as the
# comment above it grows, which had already happened to four of these scripts. Ends at the
# first line that is not a comment.
usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }
set -euo pipefail
outputs=12; forms=single,group; widths=64,16; forks=20; seconds=8; rows=1024; paths=batches
fail_on_cycle=()
while [ $# -gt 0 ]; do
  case "$1" in
    --outputs) outputs="$2"; shift 2 ;;
    --forms) forms="$2"; shift 2 ;;
    --widths) widths="$2"; shift 2 ;;
    --forks) forks="$2"; shift 2 ;;
    --seconds) seconds="$2"; shift 2 ;;
    --rows) rows="$2"; shift 2 ;;
    --paths) paths="$2"; shift 2 ;;
    --fail-on-cycle) fail_on_cycle=(--fail-on-cycle); shift ;;
    -h|--help) usage 0 ;;
    *) usage ;;
  esac
done
cd "$(dirname "$0")/.."
main=org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaDeoptCycleProbe
out="target/varka-deopt-cycle/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"
{
  echo "commit:  $(git rev-parse --short=11 HEAD)$(git diff --quiet HEAD -- sql dev || echo ' (dirty)')"
  echo "date:    $(date -u +%Y-%m-%dT%H:%M:%S+00:00)"
  echo "jdk:     $(java -version 2>&1 | head -1)"
  echo "cpu:     $(grep -m1 'model name' /proc/cpuinfo | sed 's/.*: //')"
  echo "load:    $(cut -d' ' -f1-3 /proc/loadavg) at start"
  echo "args:    outputs=$outputs forms=$forms paths=$paths widths=$widths forks=$forks seconds=$seconds rows=$rows"
} > "$out/provenance.txt"
cat "$out/provenance.txt"
IFS=',' read -r -a width_list <<< "$widths"
IFS=',' read -r -a form_list <<< "$forms"
IFS=',' read -r -a output_list <<< "$outputs"
IFS=',' read -r -a path_list <<< "$paths"
logs=()
for w in "${width_list[@]}"; do
  log="$out/width-$w.log"
  logs+=("$log")
  flags='set Test/javaOptions ++= Seq("-XX:+PrintCompilation", "-Xlog:deoptimization=debug",'
  flags+=" \"-XX:MaxVectorSize=$w\")"
  cmds=()
  for n in "${output_list[@]}"; do
    for f in "${form_list[@]}"; do
      for p in "${path_list[@]}"; do
        for ((i = 0; i < forks; i++)); do
          cmds+=("Test/runMain $main $n $f $seconds $rows $p")
        done
      done
    done
  done
  echo "== width $w: ${#cmds[@]} forks, log $log =="
  # `|| true`: a fork that dies is a finding the parser reports as unfinished, not an abort.
  build/sbt -batch "project catalyst" "$flags" "${cmds[@]}" > "$log" 2>&1 || true
done
status=0
dev/varka_deopt_cycle.py "${fail_on_cycle[@]}" "${logs[@]}" | tee "$out/summary.txt" || status=$?
echo "logs and summary under $out"
exit "$status"
