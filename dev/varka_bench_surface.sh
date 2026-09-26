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
#   dev/varka_bench_surface.sh [--rows N] [--partitions P] [--cores C] \
#       [--driver-memory 16g] \
#       [--max-fixed-share PERCENT] [--force] [--only REGEX] [--replace] [--shard I/N] \
#       [--skip-build] \
#       [--benchmark surface|chains|time|timechains] [--table-columns all|dates|times] \
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
# --benchmark time runs Times, the TIME surface (PLAN_MILESTONE_5.md 2.40), writing
# TimeSurface-<label>-results.txt over its own table, varka_times: TIME, day-time
# interval and bigint columns, one 64-bit lane each. The driver switches
# spark.sql.timeType.enabled on for every arm, since the type is off by default in
# every distribution; the stock arm is the same 4.2.0 release, which carries the type
# and its functions. Its rows are 8 bytes wide where the date surface's are 4, so the
# row count the fixed-share rule wants fits half as many rows in the same memory.
#
# --benchmark timechains runs TimeChains over the same varka_times table, writing
# TimeChain-<label>-results.txt: the TIME lane's chains, composed until the arithmetic
# exceeds the memory floor, for the datapath question the TIME surface cannot answer.
# See the TimeChains javadoc for the op floor and what limits the composition.
#
# --only REGEX runs the matching entries only, and the file it writes holds just those:
# the driver replaces the file rather than merging into it. So an --only run under a
# label that already has a committed file would turn that label's fifty-two-entry
# coverage table into a three-entry one, which git reports as an ordinary
# modification. That is refused before the first arm runs, for every label whose
# results file is tracked by git; an untracked file is this run's own scratch and is
# replaced without comment. Give the run a label of its own, or use --shard, or pass
# --replace to overwrite a committed file deliberately. --replace is separate from
# --force on purpose: --force says the machine is not in its measured state, which is
# a common thing to say and has nothing to do with discarding a results file.
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
# The defaults, 500M rows in one partition on one core under a 16g driver, are
# the job-size rule of PLAN_MILESTONE_4.md 2.29 as PLAN_TASK_62.md 2.6 measured
# it: one partition because every task on local[1] costs about two milliseconds
# of scheduling and commit round trip, and 500M rows because the fastest Varka
# rows run near half a nanosecond per row and need 250 ms of executor time
# for a 12 ms job to be under 5% of them.
#
# --cores C runs the driver as local[C] instead of local[1], which is what makes
# an occupancy ladder possible: a headline number measured on one core says
# nothing about a machine whose executor runs one task per core, and the
# question the ladder answers is whether a win survives the memory system being
# shared (PLAN_TASK_134.md). Partitions and cores are separate knobs on purpose
# - P partitions on C cores is a queue C deep - and the sensible ladder sets
# them equal. Two things change meaning above one core and are recorded rather
# than papered over: the fixed-share rule, (wall - executor) / wall, goes
# negative once executor time is a sum over parallel tasks, so it stops being a
# guard and the executor-time column becomes the comparable one; and the cache
# build is parallel too, so the numbers below it are not comparable to a
# one-core run's except through their ratio.
#
# Each LABEL names one run and its results file,
# sql/varka/bench/benchmarks/<STEM>-<LABEL>-results.txt, where STEM is DateSurface,
# DateChain or TimeSurface by --benchmark. SPARK_HOME is a
# distribution's root - a downloaded release, or this checkout after
# `build/sbt package` (its bin/spark-submit runs the assembled jars). The third
# field is a comma-separated list of extra `--conf` settings; the word `varka`
# stands for what the fork needs: Varka on, the Arrow cache serializer, the
# The conf field's two bare tokens name what a distribution switches on. `varka`
# is the engine *and* the Arrow columnar cache the engine reads through, which the
# kernels need to run at all; `arrow-cache` is that cache alone, with the row
# engine above it. The pair is what separates the cache format's share of a
# published ratio from the kernels' - without the second, an arm labelled "the
# same fork with the flag off" differs in two things rather than one. Cache build
# time is outside every number here: the driver caches and materializes before it
# measures, so these are hot-cache figures for all three.
#
# `varka` puts the engine jar on the driver's class path, and `--expect-fused --max-fixed-share
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
rows=500000000; partitions=1; cores=1; force=0; only=""; build=1; memory=16g; share=5; dists=()
replace=0
shard=""; benchmark=surface; table_columns=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --rows) rows="$2"; shift 2 ;;
    --partitions) partitions="$2"; shift 2 ;;
    --cores) cores="$2"; shift 2 ;;
    --max-fixed-share) share="$2"; shift 2 ;;
    --driver-memory) memory="$2"; shift 2 ;;
    --force) force=1; shift ;;
    --replace) replace=1; shift ;;
    --only) only="$2"; shift 2 ;;
    --shard) shard="$2"; shift 2 ;;
    --benchmark) benchmark="$2"; shift 2 ;;
    --table-columns) table_columns="$2"; shift 2 ;;
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
  time)    main_class=org.apache.spark.sql.varka.bench.TimeSurfaceBenchmark; stem=TimeSurface ;;
  timechains) main_class=org.apache.spark.sql.varka.bench.TimeChainBenchmark; stem=TimeChain ;;
  *) echo "--benchmark wants surface, chains, time or timechains, got '$benchmark'" >&2; exit 2 ;;
esac

# The --only truncation guard (task 100). Checked before the machine checks below, not just
# before the first arm: it reads the arguments and one git index entry, and a run that may
# not legally write its file should not first spend ten seconds proving the machine is quiet.
# Failing on the fourth arm after three hours would be barely better than not failing at all.
# `git ls-files` is the test for "committed": an untracked file is this run's own scratch.
# --shard is exempt - its filenames carry a suffix and cannot collide with a committed one.
if [ -n "$only" ] && [ -z "$shard" ] && [ "$replace" -eq 0 ]; then
  for spec in "${dists[@]}"; do
    guard_label="${spec%%=*}"
    guard_out="sql/varka/bench/benchmarks/$stem-$guard_label-results.txt"
    git ls-files --error-unmatch "$guard_out" >/dev/null 2>&1 || continue
    guard_n="$(sed -n 's/^entries: *\([0-9][0-9]*\).*/\1/p' "$guard_out" | head -1)"
    {
      echo "$guard_label: --only would replace the committed ${guard_n:-?}-entry"
      echo "  $guard_out"
      echo "  with this run's subset, which is how a coverage table becomes three rows."
      echo "  Give the run a label of its own, or use --shard I/N (its files carry a suffix"
      echo "  and merge with dev/varka_bench_merge.py), or pass --replace to overwrite it."
    } >&2
    exit 1
  done
fi

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
  submit=(--master "local[$cores]" --driver-memory "$memory"
    --conf spark.ui.enabled=false --conf spark.sql.shuffle.partitions=1
    --conf spark.sql.adaptive.enabled=false)
  driver=()
  IFS=',' read -r -a extra <<< "$confs"
  for c in "${extra[@]}"; do
    case "$c" in
      "") ;;
      varka)
        # The warm-up off: the surface times kernels at steady state, and a new shape's
        # batches would otherwise run on the row path, uncounted as fallbacks, until its
        # kernel compiles.
        submit+=(--conf spark.sql.codegen.varka.enabled=true
          --conf spark.sql.codegen.varka.warmup.enabled=false
          --conf "spark.sql.cache.serializer=$arrow_serializer"
          --driver-class-path "$(engine_jar)")
        driver+=(--expect-fused --max-fixed-share "$share") ;;
      arrow-cache)
        # The cache format without the engine: the control that separates what the
        # columnar cache is worth from what the kernels are worth. No --expect-fused,
        # since nothing fuses here, and no engine jar, since the serializer is
        # sql/core's and does not need one.
        submit+=(--conf "spark.sql.cache.serializer=$arrow_serializer") ;;
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
    ${only:+--only "$only"} ${shard:+--shard "$shard"} \
    ${table_columns:+--table-columns "$table_columns"} "${driver[@]}" \
    --provenance "commit=$commit" --provenance "cores=$cores" \
    --provenance "datapath=$datapath" \
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
