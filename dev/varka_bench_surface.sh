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
# Run the date-surface benchmark (task 62) on several Spark distributions, one
# after another on an idle machine, and print the table that compares them.
#
#   dev/varka_bench_surface.sh [--rows N] [--partitions P] [--driver-memory 16g] \
#       [--max-fixed-share PERCENT] [--force] [--only REGEX] [--shard I/N] [--skip-build] \
#       [--benchmark surface|chains] \
#       LABEL=SPARK_HOME:JAVA_HOME[:conf=value,conf=value...] ...
#
# --benchmark chains runs Chains through the same driver instead of Surface, writing
# DateChain-<label>-results.txt. The two answer different questions: the surface is
# one entry per expression and its lightest rows are bound by memory bandwidth rather
# than arithmetic, so a wider vector datapath cannot show on them; the chains compose
# the same operations three and four deep until the kernel is bound by what it
# computes. See the Chains javadoc. The chains also clear the fixed-share rule at 1e8
# rows, where the surface needs 5e8, because more work per row buys the same executor
# time as more rows without needing the memory to hold them.
#
# --shard I/N runs entries I, I+N, I+2N ... of whichever list --benchmark selected -
# 52 surface entries or 12 chains, so N is bounded by that list and not by the
# surface's length - and N runs between them cover it exactly once, with
# dev/varka_bench_merge.py joining their files back into one per distribution. It is
# for GitHub's six-hour job limit, which the whole surface at the row count the
# fixed-share rule wants does not fit; on a machine of your own, leave it alone. The
# shard's output file is named for it, so shards of one run can share a directory.
#
#   dev/varka_bench_surface.sh \
#       spark-4.2.0-jdk17=/opt/spark-4.2.0-bin-hadoop3:/usr/lib/jvm/java-17-openjdk-amd64 \
#       spark-4.2.0-jdk25=/opt/spark-4.2.0-bin-hadoop3:/usr/lib/jvm/java-25-openjdk-amd64 \
#       varka-off-jdk25=$PWD:/usr/lib/jvm/java-25-openjdk-amd64 \
#       varka-jdk25=$PWD:/usr/lib/jvm/java-25-openjdk-amd64:varka
#
# The defaults, 500M rows in one partition under a 16g driver, are the job-size
# rule of PLAN_MILESTONE_4.md 2.29 as PLAN_TASK_62.md 2.6 measured it: one
# partition because every task on local[1] costs about two milliseconds of
# scheduling and commit round trip, and 500M rows because the fastest Varka
# rows run near half a nanosecond per row and need 250 ms of executor time
# for a 12 ms job to be under 5% of them.
#
# Each LABEL names one run and its results file,
# sql/varka/bench/benchmarks/<STEM>-<LABEL>-results.txt, where STEM is DateSurface
# or DateChain by --benchmark. SPARK_HOME is a
# distribution's root - a downloaded release, or this checkout after
# `build/sbt package` (its bin/spark-submit runs the assembled jars). The third
# field is a comma-separated list of extra `--conf` settings; the word `varka`
# stands for what the fork needs: Varka on, the Arrow cache serializer, the
# engine jar on the driver's class path, and `--expect-fused --max-fixed-share
# 5` on the driver, so a run of the kernel fails when an entry the surface
# marks as fused is not, or when a Varka row's fixed share is over the
# job-size rule of PLAN_MILESTONE_4.md 2.29. Put the Varka run last: the
# table's ratios are the last file against the others.
#
# The engine jar: the fork's assembly does not ship sql/varka/engine (it is a
# test-scope dependency of the build), yet every emitted kernel links against
# its VarkaVectorSupport, so a distribution with Varka on and no engine jar
# falls back on every batch with a ClassNotFoundException in the log and
# measures the row engine under the kernel's name (ISSUES.md, "The engine jar
# is not in the distribution"). This script builds the jar if it is missing
# and passes it with --driver-class-path, which is the system class loader
# the kernels' loader delegates to; --jars would not reach it.
#
# Before the runs: the same load gate as dev/varka_bench_regen.sh, the machine
# canary, and the datapath probe - dev/varka_canary/Canary.java under the last
# distribution's JDK at -XX:MaxVectorSize=32 and =64, whose compute-rate ratio
# is near 2x on a full-width 512-bit unit and near 1x on a double-pumped one
# (SKILLS.md, "This machine's AVX-512 is 256 bits wide"). The ratio goes into
# every file's provenance as `datapath`, so a 512-bit claim is checkable from
# the file. The driver jar is built unless --skip-build.

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

# The header block, found rather than hard-coded: the range used to be '17,50p' and the
# comment above grew past it, so --help and every usage error stopped mid-sentence and dropped
# the only paragraph documenting the mandatory LABEL=SPARK_HOME:JAVA_HOME argument - which is
# precisely what a usage error is about. Ends at the first line that is not a comment.
usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }
rows=500000000; partitions=1; force=0; only=""; build=1; memory=16g; share=5; dists=()
shard=""; benchmark=surface
while [ "$#" -gt 0 ]; do
  case "$1" in
    --rows) rows="$2"; shift 2 ;;
    --partitions) partitions="$2"; shift 2 ;;
    --max-fixed-share) share="$2"; shift 2 ;;
    --driver-memory) memory="$2"; shift 2 ;;
    --force) force=1; shift ;;
    --only) only="$2"; shift 2 ;;
    --shard) shard="$2"; shift 2 ;;
    --benchmark) benchmark="$2"; shift 2 ;;
    --skip-build) build=0; shift ;;
    --help|-h) usage 0 ;;
    *=*) dists+=("$1"); shift ;;
    *) usage ;;
  esac
done
[ "${#dists[@]}" -ge 1 ] || usage
case "$benchmark" in
  surface) main_class=org.apache.spark.sql.varka.bench.DateSurfaceBenchmark; stem=DateSurface ;;
  chains)  main_class=org.apache.spark.sql.varka.bench.DateChainBenchmark;   stem=DateChain ;;
  *) echo "--benchmark wants surface or chains, got '$benchmark'" >&2; exit 2 ;;
esac

load="$(cut -d' ' -f1 /proc/loadavg)"
if [ "$force" -eq 0 ] && awk -v l="$load" 'BEGIN { exit !(l > 1.0) }'; then
  echo "load average is $load; wait for an idle machine or pass --force" >&2
  exit 1
fi
canary_log="$(mktemp)"
if dev/varka_bench_canary.sh > "$canary_log" 2>&1; then
  canary="ok ($(grep -E '^(compute|cache|memory) ' "$canary_log" \
    | awk '{printf "%s %s ", $1, $4}'))"
else
  cat "$canary_log" >&2
  canary="OFF"
  if [ "$force" -eq 0 ]; then
    echo "the canary says this machine is not in its measured state; pass --force to run anyway" >&2
    rm -f "$canary_log"; exit 1
  fi
fi
rm -f "$canary_log"

jar_dir="sql/varka/bench/target"
if [ "$build" -eq 1 ]; then
  echo "== building the driver: build/mvn -f sql/varka/bench/pom.xml -q -DskipTests package"
  build/mvn -f sql/varka/bench/pom.xml -q -DskipTests package
fi
jar="$(ls "$jar_dir"/varka-bench-*.jar 2>/dev/null | grep -v -- '-sources\|-tests' | head -1)"
[ -n "$jar" ] || { echo "no driver jar under $jar_dir; build it or drop --skip-build" >&2; exit 1; }

# The datapath probe, under the last distribution's JDK: dev/varka_canary/Datapath.java at
# two widths, reporting lanes per nanosecond, whose ratio is about 2 on a full-width unit and
# about 1 on a double-pumped one.
#
# This read `Canary.compute` until 11 September 2026, which was wrong and silently so: that
# loop is a scalar xorshift over one `long` - Canary's own javadoc calls it "the control" -
# so MaxVectorSize cannot touch it and the ratio was 1.00 on every machine ever measured,
# a full-width Intel Xeon 6973P-C included. Every `datapath` line committed before that date
# is a scalar rate wearing a vector label. See PLAN_TASK_62.md 11.9.
last="${dists[${#dists[@]}-1]}"
probe_java="$(echo "$last" | cut -d= -f2- | cut -d: -f2)/bin/java"
probe() {
  "$probe_java" --add-modules jdk.incubator.vector -XX:+IgnoreUnrecognizedVMOptions \
    "-XX:MaxVectorSize=$1" dev/varka_canary/Datapath.java 2>/dev/null \
    | sed -n 's/^lane_ops_per_ns=//p' | cut -d' ' -f1
}
c32="$(probe 32 || echo 0)"; c64="$(probe 64 || echo 0)"
if [ -n "$c32" ] && [ -n "$c64" ] && awk -v a="$c32" 'BEGIN { exit !(a > 0) }'; then
  datapath="$(awk -v a="$c32" -v b="$c64" \
    'BEGIN { printf "%s lane-ops/ns at 256 bits, %s at 512 bits, ratio %.2f", a, b, b / a }')"
else
  datapath="n/a"
fi
echo "== datapath probe: $datapath"

commit="$(git rev-parse --short=11 HEAD)"
git diff --quiet HEAD -- . ':!sql/varka/bench/benchmarks' || commit="$commit (working tree dirty)"
out_dir="sql/varka/bench/benchmarks"; mkdir -p "$out_dir"
arrow_serializer=org.apache.spark.sql.execution.columnar.ArrowCachedBatchSerializer
engine_jar() {
  local j
  j="$(ls sql/varka/engine/target/varka-engine-*.jar 2>/dev/null | grep -v -- '-tests' | head -1)"
  if [ -z "$j" ]; then
    echo "== building the engine jar (build/mvn -f sql/varka/engine/pom.xml package)" >&2
    build/mvn -f sql/varka/engine/pom.xml -q -DskipTests package >&2
    j="$(ls sql/varka/engine/target/varka-engine-*.jar 2>/dev/null | grep -v -- '-tests' | head -1)"
  fi
  [ -n "$j" ] || { echo "no engine jar under sql/varka/engine/target" >&2; exit 1; }
  echo "$PWD/$j"
}
files=()
for spec in "${dists[@]}"; do
  label="${spec%%=*}"; rest="${spec#*=}"
  spark_home="$(echo "$rest" | cut -d: -f1)"
  java_home="$(echo "$rest" | cut -d: -f2)"
  confs="$(echo "$rest" | cut -d: -f3- -s)"
  [ -x "$spark_home/bin/spark-submit" ] \
    || { echo "$label: no bin/spark-submit under $spark_home" >&2; exit 1; }
  [ -x "$java_home/bin/java" ] || { echo "$label: no bin/java under $java_home" >&2; exit 1; }
  submit=(--master 'local[1]' --driver-memory "$memory"
    --conf spark.ui.enabled=false --conf spark.sql.shuffle.partitions=1
    --conf spark.sql.adaptive.enabled=false)
  driver=()
  IFS=',' read -r -a extra <<< "$confs"
  for c in "${extra[@]}"; do
    case "$c" in
      "") ;;
      varka)
        submit+=(--conf spark.sql.codegen.varka.enabled=true
          --conf "spark.sql.cache.serializer=$arrow_serializer"
          --driver-class-path "$(engine_jar)")
        driver+=(--expect-fused --max-fixed-share "$share") ;;
      *=*) submit+=(--conf "$c") ;;
      *) echo "$label: conf '$c' is not key=value" >&2; exit 1 ;;
    esac
  done
  # The shard in the file name, not only in the provenance: shards of one run are
  # collected into one directory before merging, and two of them must not be the same path.
  suffix=""
  [ -n "$shard" ] && suffix="-shard${shard//\//of}"
  out="$out_dir/$stem-$label$suffix-results.txt"
  echo "== $label: $spark_home under $java_home -> $out"
  JAVA_HOME="$java_home" "$spark_home/bin/spark-submit" "${submit[@]}" \
    --class "$main_class" "$jar" \
    --label "$label" --rows "$rows" --partitions "$partitions" --out "$out" \
    ${only:+--only "$only"} ${shard:+--shard "$shard"} "${driver[@]}" \
    --provenance "commit=$commit" --provenance "datapath=$datapath" \
    --provenance "canary=$canary" --provenance "host=$(hostname -s)" \
    --provenance "spark home=$spark_home"
  files+=("$label=$out")
done

echo
if [ -n "$shard" ]; then
  echo "== shard $shard: no table, because a shard is a fraction of the run. Collect every"
  echo "   shard's files and run dev/varka_bench_merge.py, which checks they agree and joins them."
  exit 0
fi
echo "== the table (${files[*]##*/}) =="
dev/varka_bench_diff.py --table "${files[@]}"
