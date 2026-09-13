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
# Regenerate one Varka benchmark's committed results, at both vector widths,
# with provenance, and show what moved.
#
#   dev/varka_bench_regen.sh catalyst VarkaEmitterParityBenchmark
#   dev/varka_bench_regen.sh core VarkaFilterBenchmark --no-narrow
#   dev/varka_bench_regen.sh catalyst VarkaEmitterParityBenchmark --narrow-only
#   dev/varka_bench_regen.sh catalyst VarkaEmitterParityBenchmark --no-pin
#   dev/varka_bench_regen.sh catalyst VarkaEmitterParityBenchmark --pin=0-3
#
# Writes three files beside each other under sql/<module>/benchmarks/:
#   <Class>-jdk25-results.txt          the wide run, exactly as Spark's harness
#                                      writes it (SPARK_GENERATE_BENCHMARK_FILES)
#   <Class>-jdk25-128bit-results.txt   the same run under -XX:MaxVectorSize=16,
#                                      with a provenance header; before this
#                                      script the narrow numbers lived only in
#                                      scratch logs, which breaks the rule that
#                                      every quoted number traces to a committed
#                                      file
#   <Class>-jdk25-provenance.txt       git SHA and dirty state, JDK, kernel, CPU,
#                                      load average at start, date
#
# It refuses to start on a busy machine (1-minute load average above 1.0) unless
# --force is given, and it records the CPU governor, energy preference and power
# profile, because the same code on the same machine has measured its
# memory-bound kernels 20-27% apart on different days with every compute-bound
# control flat (task 54's regeneration against master's committed file; a
# same-day run of master reproduced the gap). When it finishes it runs
# dev/varka_bench_diff.py against the committed wide file so the controls and
# the moved rows are in front of you before you commit - and if unrelated rows
# moved together while the controls held, re-run master the same day before
# reading anything into it.
set -euo pipefail

usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }
case "${1:-}" in -h|--help) usage 0 ;; esac
[ "$#" -ge 2 ] || usage
module="$1"; klass="$2"; shift 2
narrow=1; wide_run=1; force=0; pin=auto
for a in "$@"; do
  case "$a" in
    --no-narrow) narrow=0 ;;
    --narrow-only) wide_run=0 ;;
    --force) force=1 ;;
    --no-pin) pin=none ;;
    --pin=*) pin="${a#--pin=}" ;;
    *) usage ;;
  esac
done

# The CPU set the runs are pinned to. This machine is heterogeneous - the Ryzen AI 9 HX 370 has
# four Zen5 cores at 5.16 GHz and eight Zen5c at 3.29 GHz, on two separate 16 MB L3 slices - and
# an unpinned benchmark thread is rescheduled between them during a run. Two effects follow, both
# measured: the clock alone is worth 1.57x (a 1.5632 measured ratio against a 1.5680 clock ratio,
# so the datapath is the same width on both core types and only the clock differs), and a case
# whose working set fits one CCX's L3 loses it on migration - 154 GB/s becomes 37.7 GB/s, a 4x
# drop concentrated on the memory-bound rows. Pinning to the fast CCX removes both. It does not
# make the file reproducible on its own; see `dev/varka_bench_repeat.sh` for what is left.
#
# `auto` picks the cores sharing cpu0's L3 whose max frequency equals the package maximum, so it
# is right on a uniform machine too (every core qualifies, and the pin is a no-op in effect).
if [ "$pin" = auto ]; then
  maxf=0
  for d in /sys/devices/system/cpu/cpu[0-9]*/cpufreq; do
    f=$(cat "$d/cpuinfo_max_freq" 2>/dev/null || echo 0)
    [ "$f" -gt "$maxf" ] && maxf=$f
  done
  fast=""
  for d in /sys/devices/system/cpu/cpu[0-9]*/cpufreq; do
    c=$(basename "$(dirname "$d")"); c=${c#cpu}
    f=$(cat "$d/cpuinfo_max_freq" 2>/dev/null || echo 0)
    [ "$f" = "$maxf" ] && fast="${fast:+$fast,}$c"
  done
  pin="${fast:-none}"
fi
if [ "$pin" = none ] || ! command -v taskset > /dev/null; then
  runner=()
  pin_desc="none (numbers are not comparable with a pinned run)"
else
  runner=(taskset -c "$pin")
  pin_desc="taskset -c $pin"
fi
# A bare class name is resolved from the module's test sources, since the benchmarks are not
# all in one package (catalyst's sit in org.apache.spark.sql, sql/core's under
# org.apache.spark.sql.execution.benchmark); a dotted name is taken as given.
case "$klass" in
  *.*) fqcn="$klass"; klass="${klass##*.}" ;;
  *)
    case "$module" in catalyst) src="sql/catalyst/src/test" ;; *) src="sql/core/src/test" ;; esac
    file="$(find "$src" -name "$klass.scala" | head -1)"
    if [ -n "$file" ]; then
      fqcn="$(sed -n 's/^package \(.*\)$/\1/p' "$file" | head -1).$klass"
    else
      echo "no $klass.scala under $src; pass the fully qualified class name" >&2; exit 2
    fi ;;
esac
case "$module" in
  catalyst) dir="sql/catalyst/benchmarks" ;;
  core|sql) module="sql"; dir="sql/core/benchmarks" ;;
  *) echo "unknown module '$module' (catalyst or core)"; exit 2 ;;
esac
wide="$dir/$klass-jdk25-results.txt"
narrow_file="$dir/$klass-jdk25-128bit-results.txt"
prov="$dir/$klass-jdk25-provenance.txt"

load="$(cut -d' ' -f1 /proc/loadavg)"
if [ "$force" -eq 0 ] && awk -v l="$load" 'BEGIN { exit !(l > 1.0) }'; then
  echo "load average is $load: the machine is not idle. Wait, or pass --force." >&2
  exit 1
fi
# The canary: is the machine in the state the committed files were measured in? It says
# so in 35 seconds; the run it protects takes ten minutes per width.
canary_log="$(mktemp)"
if "$(dirname "$0")/varka_bench_canary.sh" > "$canary_log" 2>&1; then
  canary="ok ($(grep -E '^(compute|cache|memory) ' "$canary_log" \
    | awk '{ printf "%s %s ", $1, $4 }'))"
else
  cat "$canary_log" >&2
  canary="OFF"
  if [ "$force" -eq 0 ]; then
    echo "the canary says the machine is not in its baseline state; pass --force to run anyway" >&2
    rm -f "$canary_log"; exit 1
  fi
fi
rm -f "$canary_log"

sha="$(git rev-parse --short=11 HEAD)"
dirty=""
git diff --quiet HEAD -- . ':!sql/catalyst/benchmarks' ':!sql/core/benchmarks' \
  || dirty=" (working tree dirty)"
jdk="$(java -version 2>&1 | sed -n 2p)"
cpu="$(grep -m1 'model name' /proc/cpuinfo | sed 's/.*: //')"
{
  echo "benchmark:   $fqcn"
  echo "commit:      $sha$dirty"
  echo "date:        $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "jdk:         $jdk"
  echo "kernel:      $(uname -r)"
  echo "cpu:         $cpu"
  cpufreq=/sys/devices/system/cpu/cpu0/cpufreq
  echo "power:       governor=$(cat $cpufreq/scaling_governor 2>/dev/null || echo n/a)" \
    "epp=$(cat $cpufreq/energy_performance_preference 2>/dev/null || echo n/a)" \
    "profile=$(powerprofilesctl get 2>/dev/null || echo n/a)"
  echo "pinned to:   $pin_desc"
  echo "load at start: $load"
  echo "canary:      $canary"
  echo "wide run:    SPARK_GENERATE_BENCHMARK_FILES=1 ${runner[*]} build/sbt" \
    "$module/Test/runMain $fqcn"
  if [ "$narrow" -eq 1 ]; then
    echo "narrow run:  ${runner[*]} build/sbt \"project $module\"" \
      "'set Test/javaOptions += \"-XX:MaxVectorSize=16\"' \"Test/runMain $fqcn\""
  fi
} > "$prov"
cat "$prov"

if [ "$wide_run" -eq 1 ]; then
  echo "== wide run =="
  SPARK_GENERATE_BENCHMARK_FILES=1 "${runner[@]}" build/sbt -batch \
    "$module/Test/runMain $fqcn" > /dev/null
  [ -f "$wide" ] || { echo "the wide run wrote no $wide" >&2; exit 1; }
fi

if [ "$narrow" -eq 1 ]; then
  echo "== narrow run (-XX:MaxVectorSize=16) =="
  raw="$(mktemp)"
  "${runner[@]}" build/sbt -batch "project $module" \
    'set Test/javaOptions += "-XX:MaxVectorSize=16"' \
    "Test/runMain $fqcn" > "$raw"
  {
    echo "Narrow-width companion of $klass-jdk25-results.txt: the same benchmark under"
    echo "-XX:MaxVectorSize=16 (128-bit lanes), written by dev/varka_bench_regen.sh."
    sed 's/^  *//' "$prov" | sed 's/^/  /'
    echo
    # Only the harness's own output. Without SPARK_GENERATE_BENCHMARK_FILES the harness
    # prints no section rules, only a "Running benchmark:" line per table, so the
    # companion starts at the first of those; sbt's [info] prefixes are removed and its
    # own status lines dropped. dev/varka_bench_diff.py keys sections on each table's
    # header line, which both this format and the generated one carry.
    sed -E 's/^\[info\] ?//' "$raw" | awk '/^Running benchmark: / { on = 1 } on' \
      | sed -E '/^\[(success|error|warn)\]/d'
  } > "$narrow_file"
  rm -f "$raw"
fi

echo
echo "== what moved against the committed wide file =="
# The band, where one has been measured for this file, turns "moved 14.2%" into
# a verdict: a case's own tier says whether a move that size means anything. See
# dev/varka_bench_band.py. Without one the flat threshold applies, as before.
band="${wide%-results.txt}-band.txt"
band_arg=()
if [ -f "$band" ]; then
  band_arg=(--band "$band")
  echo "(classified against $band)"
fi
if [ "$wide_run" -eq 1 ] && git cat-file -e "HEAD:$wide" 2>/dev/null; then
  "$(dirname "$0")/varka_bench_diff.py" --git HEAD "$wide" "${band_arg[@]}"
else
  echo "(no committed version of $wide to compare against)"
fi
echo

# The invariants, which are not about the numbers moving. A fused kernel that has
# become slower than the passes it exists to beat is a broken measurement, not a
# slow row, and it is the failure task 77 was opened for: the 128-bit collapse
# went into three committed files before anyone remarked on it.
echo "== invariants =="
gate_rc=0
for f in ${wide_run:+"$wide"} ${narrow:+"$narrow_file"}; do
  [ -f "$f" ] || continue
  "$(dirname "$0")/varka_bench_gate.py" "$f" || gate_rc=1
done
[ "$gate_rc" -eq 0 ] || echo "the gate failed: do not commit this file until it is understood"
echo
[ "$wide_run" -eq 1 ] && echo "wrote: $wide"
[ "$narrow" -eq 1 ] && echo "wrote: $narrow_file"
echo "wrote: $prov"
