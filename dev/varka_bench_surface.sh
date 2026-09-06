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
#       [--max-fixed-share PERCENT] [--force] [--only REGEX] [--skip-build] \
#       LABEL=SPARK_HOME:JAVA_HOME[:conf=value,conf=value...] ...
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
# sql/varka/bench/benchmarks/DateSurface-<LABEL>-results.txt. SPARK_HOME is a
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

usage() { sed -n '17,50p' "$0"; exit 2; }
rows=500000000; partitions=1; force=0; only=""; build=1; memory=16g; share=5; dists=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --rows) rows="$2"; shift 2 ;;
    --partitions) partitions="$2"; shift 2 ;;
    --max-fixed-share) share="$2"; shift 2 ;;
    --driver-memory) memory="$2"; shift 2 ;;
    --force) force=1; shift ;;
    --only) only="$2"; shift 2 ;;
    --skip-build) build=0; shift ;;
    --help|-h) usage ;;
    *=*) dists+=("$1"); shift ;;
    *) usage ;;
  esac
done
[ "${#dists[@]}" -ge 1 ] || usage

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

# The datapath probe, under the last distribution's JDK.
last="${dists[${#dists[@]}-1]}"
probe_java="$(echo "$last" | cut -d= -f2- | cut -d: -f2)/bin/java"
probe() {
  "$probe_java" --add-modules jdk.incubator.vector -XX:+IgnoreUnrecognizedVMOptions \
    "-XX:MaxVectorSize=$1" dev/varka_canary/Canary.java 2>/dev/null \
    | sed -n 's/^compute=//p' | cut -d' ' -f1
}
c32="$(probe 32 || echo 0)"; c64="$(probe 64 || echo 0)"
if [ -n "$c32" ] && [ -n "$c64" ] && awk -v a="$c32" 'BEGIN { exit !(a > 0) }'; then
  datapath="$(awk -v a="$c32" -v b="$c64" \
    'BEGIN { printf "compute %s at 256 bits, %s at 512 bits, ratio %.2f", a, b, b / a }')"
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
  out="$out_dir/DateSurface-$label-results.txt"
  echo "== $label: $spark_home under $java_home -> $out"
  JAVA_HOME="$java_home" "$spark_home/bin/spark-submit" "${submit[@]}" \
    --class org.apache.spark.sql.varka.bench.DateSurfaceBenchmark "$jar" \
    --label "$label" --rows "$rows" --partitions "$partitions" --out "$out" \
    ${only:+--only "$only"} "${driver[@]}" \
    --provenance "commit=$commit" --provenance "datapath=$datapath" \
    --provenance "canary=$canary" --provenance "host=$(hostname -s)" \
    --provenance "spark home=$spark_home"
  files+=("$label=$out")
done

echo
echo "== the table (${files[*]##*/}) =="
dev/varka_bench_diff.py --table "${files[@]}"
