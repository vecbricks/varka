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
# reads each fork's verdict and C2's reasons with dev/varka_inlining_cliff.py. --shapes makedate
# forks the deopt guard's make_date shape instead, whose call sites are heavier; --directives
# inline installs a compiler directive on the emitted class that forces VarkaVectorSupport's
# helpers inline and raises its node limit.
#
#   dev/varka_inlining_cliff.sh                                   # the sweep: 16 to 64 outputs in one group, 20 forks each
#   dev/varka_inlining_cliff.sh --outputs 64 --ceilings 400,100,50 # one, two and six groups of the 64 tails
#   dev/varka_inlining_cliff.sh --outputs 64 --xbatch on,off       # with the order of compilation fixed, and not
#   dev/varka_inlining_cliff.sh --outputs 64 --c1 off              # C1 kept off the class, as the warm-up keeps it
#   dev/varka_inlining_cliff.sh --outputs 64 --jvm "-XX:NodeCountInliningCutoff=60000"   # a cutoff raised
#   dev/varka_inlining_cliff.sh --shapes makedate --outputs 8,12,16   # the deopt guard's make_date shape
#   dev/varka_inlining_cliff.sh --outputs 48 --directives none,inline # the helpers forced inline by directive
#   dev/varka_inlining_cliff.sh --outputs 22,48 --budgets 93,0      # the emitter's call-site budget, and off
#   dev/varka_inlining_cliff.sh --shapes makedate --outputs 8 --heavy 6,0   # heavy groups kept, and split
#   dev/varka_inlining_cliff.sh --outputs 24,40 --forks 10 --seconds 12 --fail-if-slow   # the nightly guard
#
# --widths is in bytes, as the JVM's MaxVectorSize counts them, and empty means the host's own.
# Each (width, xbatch, jvm) combination is one sbt session with one forked JVM per case and
# fork; the session's log, the per-fork compile logs (c2-<pid>.xml, for dev/varka_c2_report.py)
# and the summary go under target/varka-inlining-cliff/<date-time>/. Fresh JVMs because the
# verdict is decided at the loop method's C2 compile and then stable for the JVM's life, so
# forks, not iterations, are the sample. Not a benchmark: a fork's rate is a probe's reading, a
# factor of ten apart between the two sides, and the verdict on why is C2's own log. --budgets
# is the emitter's call-site budget per arm (VarkaEmitOptions.loopCallSiteBudget, 0 for off)
# and --heavy its heavy-group exemption (VarkaEmitOptions.heavyGroupOutputs, 0 to split every
# group over the budget); left out, every fork emits under the production defaults.
# --fail-if-slow makes the exit status 1 when any fork is slow at the end or did not finish,
# which is the nightly's guard on the cliff (dev/varka_nightly.sh).
# Usage text is found rather than numbered, for the reason dev/varka_deopt_cycle.sh gives.
usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }
set -euo pipefail
outputs=16,24,32,40,48,56,64; ceilings=400; widths=""; forks=20; seconds=6; rows=1024
xbatch=off; c1=on; jvm=""; shapes=cheap; directives=none; budgets=""; heavy=""
fail_if_slow=()
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
    --shapes) shapes="$2"; shift 2 ;;
    --directives) directives="$2"; shift 2 ;;
    --budgets) budgets="$2"; shift 2 ;;
    --heavy) heavy="$2"; shift 2 ;;
    --fail-if-slow) fail_if_slow=(--fail-if-slow); shift ;;
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
  echo "args:    outputs=$outputs ceilings=$ceilings widths=${widths:-host} forks=$forks seconds=$seconds rows=$rows xbatch=$xbatch c1=$c1 shapes=$shapes directives=$directives budgets=${budgets:-default} heavy=${heavy:-default} jvm=$jvm"
} > "$out/provenance.txt"
cat "$out/provenance.txt"
IFS=',' read -r -a output_list <<< "$outputs"
IFS=',' read -r -a ceiling_list <<< "$ceilings"
IFS=',' read -r -a xbatch_list <<< "$xbatch"
IFS=',' read -r -a c1_list <<< "$c1"
IFS=',' read -r -a shape_list <<< "$shapes"
IFS=',' read -r -a directive_list <<< "$directives"
# An empty --budgets or --heavy passes no such argument, so the probe emits under the default;
# --heavy alone passes the default budget in front of it, since the arguments are positional.
if [ -n "$budgets" ]; then IFS=',' read -r -a budget_list <<< "$budgets"; else budget_list=(""); fi
if [ -n "$heavy" ]; then
  IFS=',' read -r -a heavy_list <<< "$heavy"
  [ -n "$budgets" ] || budget_list=(93)
else
  heavy_list=("")
fi
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
          for shape in "${shape_list[@]}"; do
            for directive in "${directive_list[@]}"; do
              for budget in "${budget_list[@]}"; do
                for h in "${heavy_list[@]}"; do
                  for ((i = 0; i < forks; i++)); do
                    cmds+=("Test/runMain $main $n $c $seconds $rows c1$c1mode $shape $directive $budget $h")
                  done
                done
              done
            done
          done
        done
      done
    done
    echo "== width $w, xbatch $xb: ${#cmds[@]} forks, log $log =="
    # `|| true`: a fork that dies is a finding the reader reports as unfinished, not an abort.
    build/sbt -batch "project catalyst" "$flags" "${cmds[@]}" > "$log" 2>&1 || true
  done
done
status=0
dev/varka_inlining_cliff.py "${fail_if_slow[@]}" "${logs[@]}" | tee "$out/summary.txt" || status=$?
echo "logs, compile logs and summary under $out"
exit "$status"
