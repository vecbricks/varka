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
# What does a kernel meet when it is compiled after N other kernels in the same JVM (VARKA-257,
# scope item 62)? Forks fresh JVMs of VarkaKernelHistoryProbe: each warms N kernels of the IR
# fuzz grammar through production's warm-up worker, one after another, then the inlining-cliff
# probe's 64 cheap tails, times that kernel for some seconds, reads its rate and its allocation
# per call, and compares every kernel's answers with the reference evaluator. The reader,
# dev/varka_kernel_history.py, gives the slow mode's frequency per N, and fails on a wrong answer.
#
#   dev/varka_kernel_history.sh                                  # N = 0, 4, 16, 64, 10 forks each
#   dev/varka_kernel_history.sh --after 0,16 --forks 5 --prewarm off   # the admission trial
#   dev/varka_kernel_history.sh --widths 16 --prewarm on,off     # at 128 bits, with and without
#   dev/varka_kernel_history.sh --jvm -XX:-UseProfiledLoopPredicate   # item 61's flag
#   dev/varka_kernel_history.sh --after 16 --forks 6 --guard     # the nightly's arm
#   dev/varka_kernel_history.sh --outputs 64                     # VARKA-209 12.3's 64 tails
#   dev/varka_kernel_history.sh --after 1 --forks 1 --jvm -Dvarka.history.from=13   # draw 13 alone
#   dev/varka_kernel_history.sh --after 64 --forks 2 --fork-base 4   # forks 4 and 5, one shard
#   dev/varka_kernel_history.sh --jvm -Dvarka.matrix.config=uncountedMainLoop=true   # an emit arm
#
# --widths is in bytes, as MaxVectorSize counts them, empty for the host's own; --prewarm says
# whether the warm-up worker runs its Vector API template warm-up first (VARKA-295), on as in
# production. Fork f of a case draws its history from seed --seed + f, so the forks of a case
# have different histories and a case's N forks replay with the same seed. --fork-base B numbers
# the forks B to B + --forks - 1, seeds and log names included, so that shards run on several
# machines (benchmark.yml's history mode) read as one run of their sum. --jvms JVMs run at
# once, each pinned to --cpus cores from --cpu-base on, so that a run beside other work stays on
# its own cores. --deadline is the warm-up's deadline in seconds (30; production's is 60): a
# kernel still allocating then is released, which is what the history kernels' RELEASED counts
# read. --outputs is the cheap-tail count of the kernel under test (24, the cliff guard's smaller
# case: the 64 of VARKA-209 12.3 takes 45 to 60 s to warm on three loaded cores). --guard makes
# the exit status 1 on a wrong answer or a harness error, a fork that did not finish or whose
# timed calls declined, or more slow forks than --max-slow (0): the nightly's arm
# (dev/varka_nightly.sh).
#
# Needs catalyst's test classes and exported classpath; builds them with dev/varka_matrix.sh
# --module catalyst --list when they are missing or under --build. Fork logs and the summary go
# under target/varka-kernel-history/<date-time>/ or --out.
usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }
set -euo pipefail
after=0,4,16,64; forks=10; seconds=12; widths=""; prewarm=on; seed=20260903; jvms=2; cpus=3
cpu_base=0; deadline=30; outputs=24; jvm_args=(); out=""; guard=0; max_slow=0; build=0
fork_base=0
while [ $# -gt 0 ]; do
  case "$1" in
    --after) after="$2"; shift 2 ;;
    --forks) forks="$2"; shift 2 ;;
    --fork-base) fork_base="$2"; shift 2 ;;
    --seconds) seconds="$2"; shift 2 ;;
    --widths) widths="$2"; shift 2 ;;
    --prewarm) prewarm="$2"; shift 2 ;;
    --seed) seed="$2"; shift 2 ;;
    --jvms) jvms="$2"; shift 2 ;;
    --cpus) cpus="$2"; shift 2 ;;
    --cpu-base) cpu_base="$2"; shift 2 ;;
    --deadline) deadline="$2"; shift 2 ;;
    --outputs) outputs="$2"; shift 2 ;;
    --jvm) jvm_args+=("$2"); shift 2 ;;
    --out) out="$2"; shift 2 ;;
    --guard) guard=1; shift ;;
    --max-slow) max_slow="$2"; shift 2 ;;
    --build) build=1; shift ;;
    -h|--help) usage 0 ;;
    *) usage ;;
  esac
done
cd "$(dirname "$0")/.."
root=$(pwd)
if [ "$build" -eq 1 ] || [ ! -s target/varka-matrix/classpath ]; then
  dev/varka_matrix.sh --module catalyst --list > /dev/null
fi
# The test JVM's options as sbt gives them. The JIT is what is measured, so the memory sanitizer
# stays off, as for the benchmarks and the other JIT probes (sql/varka/AGENTS.md): the options
# file does not carry it (dev/varka_matrix.sh adds it to its own runs), and nothing here adds it.
mapfile -t opts < target/varka-matrix/jvm.opts
cp="$root/sql/catalyst/target/scala-2.13/classes:$(cat target/varka-matrix/classpath)"
main=org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelHistoryProbe
out=${out:-target/varka-kernel-history/$(date +%Y%m%d-%H%M%S)}
case "$out" in /*) ;; *) out="$root/$out" ;; esac
mkdir -p "$out"
{
  dirty=$(git diff --quiet HEAD -- sql dev || echo ' (dirty)')
  echo "commit:  $(git rev-parse --short=11 HEAD)$dirty"
  echo "date:    $(date -u +%Y-%m-%dT%H:%M:%S+00:00)"
  echo "jdk:     $(java -version 2>&1 | head -1)"
  echo "cpu:     $(grep -m1 'model name' /proc/cpuinfo | sed 's/.*: //')"
  echo "load:    $(cut -d' ' -f1-3 /proc/loadavg) at start"
  echo "args:    after=$after forks=$forks seconds=$seconds widths=${widths:-host}" \
    "prewarm=$prewarm seed=$seed fork_base=$fork_base jvms=$jvms cpus=$cpus cpu_base=$cpu_base" \
    "deadline=$deadline outputs=$outputs jvm=${jvm_args[*]:-}"
} > "$out/provenance.txt"
cat "$out/provenance.txt"
IFS=',' read -r -a after_list <<< "$after"
IFS=',' read -r -a prewarm_list <<< "$prewarm"
if [ -n "$widths" ]; then IFS=',' read -r -a width_list <<< "$widths"; else width_list=(host); fi
# Every fork as "width prewarm after fork", forks of one case spread over the slots.
jobs=()
for ((f = fork_base; f < fork_base + forks; f++)); do
  for w in "${width_list[@]}"; do
    for p in "${prewarm_list[@]}"; do
      for n in "${after_list[@]}"; do
        jobs+=("$w $p $n $f")
      done
    done
  done
done
# One fork: its JVM pinned to the slot's cores, its log named by its case, its exit status in a
# file beside it. A fork that fails is the reader's finding, not the script's end.
run_fork() {
  local slot="$1" w="$2" p="$3" n="$4" f="$5"
  local first=$((cpu_base + slot * cpus))
  local name="width-$w-prewarm-$p-after-$n-fork-$f"
  local extra=()
  [ "$w" != host ] && extra+=("-XX:MaxVectorSize=$w")
  [ "$p" = off ] && extra+=("-Dvarka.warmup.vectorTemplates=false")
  mkdir -p "$out/tmp-$slot"
  (set +e; SPARK_TESTING=1 SPARK_SCALA_VERSION=2.13 \
    taskset -c "$first-$((first + cpus - 1))" \
    java "${opts[@]}" "-Djava.io.tmpdir=$out/tmp-$slot" \
    "-Dvarka.warmup.deadlineSeconds=$deadline" "${extra[@]}" "${jvm_args[@]}" -cp "$cp" \
    "$main" "$n" "$((seed + f))" "$seconds" "$outputs" < /dev/null > "$out/$name.log" 2>&1
    echo $? > "$out/$name.exit")
}
echo "== ${#jobs[@]} forks over $jvms JVMs at a time, logs under $out"
pids=()
for ((s = 0; s < jvms; s++)); do
  (for ((j = s; j < ${#jobs[@]}; j += jvms)); do
    # shellcheck disable=SC2086
    run_fork "$s" ${jobs[$j]}
  done) &
  pids+=("$!")
done
for pid in "${pids[@]}"; do wait "$pid"; done
reader=(dev/varka_kernel_history.py --dir "$out")
[ "$guard" -eq 1 ] && reader+=(--guard --max-slow "$max_slow")
status=0
"${reader[@]}" | tee "$out/summary.txt" || status=$?
echo "fork logs and summary under $out"
exit "$status"
