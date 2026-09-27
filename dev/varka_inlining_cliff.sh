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
# Does a kernel's loop method keep its vector intrinsics, or fall to the Vector API's scalar
# fallback - and does the same class land on either side from one JVM to the next
# (PLAN_TASK_209.md 2)? Forks fresh JVMs of VarkaInliningCliffProbe - task 198's cheap-tail
# shape, `year(d) + k` over one date, at a given output count and fused ceiling - under
# -XX:+LogCompilation and PrintInlining and PrintIntrinsics scoped to the emitted class, and
# reads each fork's verdict and C2's reasons with dev/varka_inlining_cliff.py.
#
#   dev/varka_inlining_cliff.sh                                   # the sweep: 16 to 64 outputs in one group, 20 forks each
#   dev/varka_inlining_cliff.sh --outputs 64 --ceilings 400,100,50 # one, two and six groups of the 64 tails
#   dev/varka_inlining_cliff.sh --outputs 64 --xbatch on,off       # with the order of compilation fixed, and not
#   dev/varka_inlining_cliff.sh --outputs 64 --c1 off              # C1 kept off the class, as the warm-up keeps it
#   dev/varka_inlining_cliff.sh --outputs 64 --jvm "-XX:NodeCountInliningCutoff=60000"   # a cutoff raised
#
# --widths is in bytes, as the JVM's MaxVectorSize counts them, and empty means the host's own.
# Each (width, xbatch, jvm) combination is one sbt session with one forked JVM per case and
# fork; the session's log, the per-fork compile logs (c2-<pid>.xml, for dev/varka_c2_report.py)
# and the summary go under target/varka-inlining-cliff/<date-time>/. Fresh JVMs because the
# verdict is decided at the loop method's C2 compile and then stable for the JVM's life, so
# forks, not iterations, are the sample. Not a benchmark: a fork's rate is a probe's reading, a
# factor of ten apart between the two sides, and the verdict on why is C2's own log.
# Usage text is found rather than numbered, for the reason dev/varka_deopt_cycle.sh gives.
usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }
set -euo pipefail
outputs=16,24,32,40,48,56,64; ceilings=400; widths=""; forks=20; seconds=6; rows=1024
xbatch=off; c1=on; jvm=""
while [ $# -gt 0 ]; do
  case "$1" in
    --outputs) outputs="$2"; shift 2 ;;
    --ceilings) ceilings="$2"; shift 2 ;;
    --widths) widths="$2"; shift 2 ;;
    --forks) forks="$2"; shift 2 ;;
    --seconds) seconds="$2"; shift 2 ;;
    --rows) rows="$2"; shift 2 ;;
    --xbatch) xbatch="$2"; shift 2 ;;
    --c1) c1="$2"; shift 2 ;;
    --jvm) jvm="$2"; shift 2 ;;
    -h|--help) usage 0 ;;
    *) usage ;;
  esac
done
cd "$(dirname "$0")/.."
main=org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaInliningCliffProbe
prefix='org.apache.spark.sql.varka.execution.VarkaCliffProbe_*::*'
out="target/varka-inlining-cliff/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"
{
  echo "commit:  $(git rev-parse --short=11 HEAD)$(git diff --quiet HEAD -- sql dev || echo ' (dirty)')"
  echo "date:    $(date -u +%Y-%m-%dT%H:%M:%S+00:00)"
  echo "jdk:     $(java -version 2>&1 | head -1)"
  echo "cpu:     $(grep -m1 'model name' /proc/cpuinfo | sed 's/.*: //')"
  echo "load:    $(cut -d' ' -f1-3 /proc/loadavg) at start"
  echo "args:    outputs=$outputs ceilings=$ceilings widths=${widths:-host} forks=$forks seconds=$seconds rows=$rows xbatch=$xbatch c1=$c1 jvm=$jvm"
} > "$out/provenance.txt"
cat "$out/provenance.txt"
IFS=',' read -r -a output_list <<< "$outputs"
IFS=',' read -r -a ceiling_list <<< "$ceilings"
IFS=',' read -r -a xbatch_list <<< "$xbatch"
IFS=',' read -r -a c1_list <<< "$c1"
if [ -n "$widths" ]; then IFS=',' read -r -a width_list <<< "$widths"; else width_list=(host); fi
logs=()
for w in "${width_list[@]}"; do
  for xb in "${xbatch_list[@]}"; do
    log="$out/width-$w-xbatch-$xb.log"
    logs+=("$log")
    flags='set Test/javaOptions ++= Seq("-XX:+UnlockDiagnosticVMOptions", "-XX:+LogCompilation",'
    flags+=" \"-XX:LogFile=$PWD/$out/c2-%p.xml\", \"-XX:CompileCommand=quiet\","
    flags+=" \"-XX:CompileCommand=PrintInlining,$prefix\", \"-XX:CompileCommand=PrintIntrinsics,$prefix\""
    [ "$xb" = on ] && flags+=', "-Xbatch"'
    [ "$w" != host ] && flags+=", \"-XX:MaxVectorSize=$w\""
    for j in $jvm; do flags+=", \"$j\""; done
    flags+=')'
    cmds=()
    for n in "${output_list[@]}"; do
      for c in "${ceiling_list[@]}"; do
        for c1mode in "${c1_list[@]}"; do
          for ((i = 0; i < forks; i++)); do
            cmds+=("Test/runMain $main $n $c $seconds $rows c1$c1mode")
          done
        done
      done
    done
    echo "== width $w, xbatch $xb: ${#cmds[@]} forks, log $log =="
    # `|| true`: a fork that dies is a finding the reader reports as unfinished, not an abort.
    build/sbt -batch "project catalyst" "$flags" "${cmds[@]}" > "$log" 2>&1 || true
  done
done
dev/varka_inlining_cliff.py "${logs[@]}" | tee "$out/summary.txt"
echo "logs, compile logs and summary under $out"
