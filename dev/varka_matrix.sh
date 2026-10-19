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
# The option configuration matrix (VARKA-248 step 2, sql/varka/plans/m7/VARKA-248.md 3.2):
# the Varka suites rerun once per configuration - the emit defaults with one option changed -
# and every kernel they emit carries it.
#
#   dev/varka_matrix.sh --config cse=false             # one configuration
#   dev/varka_matrix.sh --config cse=false --config groupBudget=8 -j 2
#   dev/varka_matrix.sh --all -j 8                     # every configuration, eight at a time
#   dev/varka_matrix.sh --list                         # print the configurations and stop
#   dev/varka_matrix.sh --all --skip-build             # reuse the last build and classpath
#   dev/varka_matrix.sh --all -j 8 --deadline 06:30    # start no JVM after 06:30
#   dev/varka_matrix.sh --all --shard 3/12 -j 2        # every 12th configuration from the 3rd
#   dev/varka_matrix.sh --module sql --config cse=false --sbt-arg -Phive   # as the PR job runs
#   dev/varka_matrix.sh --defaults --split 4 -j 8 --jvm-arg -XX:MaxVectorSize=16   # as the gate
#
# The gate's options (VARKA-286): --defaults runs no configuration, only the defaults, over
# every Varka suite including those the matrix leaves out; --split N spreads each module's
# suites over N JVMs, longest first by the previous run's JUnit times, beside the JVM each suite
# that runs alone takes; --jvm-arg puts an option
# on every test JVM's command line; --out names the working directory and --build-dir the one
# holding a shared build's classpath; --suites and --skip-suites take comma-separated simple
# names.
#
# CI's options (VARKA-287): --extra-suite MODULE:CLASS adds a suite the Varka-name search does not
# find (ArrowCachedBatchSerializerSuite, Varka's without the name) to that module's run;
# --defaults-from DIR takes a finished defaults run - the runs/defaults under DIR, which the
# declining check compares fused batches with - instead of running the defaults again, and
# balances the JVMs by its JUnit times.
#
# sbt builds once and exports the test classpath and JVM options; each configuration then runs
# as its own runner JVM (VarkaSuiteRunner: ScalaTest's Runner, then the JUnit suites) in its own
# directory under target/varka-matrix/, so parallel runs share no sbt lock, warehouse or temporary
# directory. The defaults always run too, as configuration zero: a test that fused under the
# defaults and fuses nothing under a configuration fails, unless sql/varka/matrix/skips.tsv marks
# it "declines" (see VarkaMatrix).
#
# On a laptop the runner also waits before each launch while the battery discharges below 30%,
# so an unattended run does not drain it (a charger can supply less than ten JVMs draw).
#
# The memory sanitizer (VARKA-263) is on in every test JVM, as -Dvarka.sanitizeMemory=true: each
# kernel's mappings are checked against the buffers the evaluator handed it, canaries sit past the
# buffers Varka allocates, and a suite that leaves Arrow memory allocated is aborted. --no-sanitizer
# leaves it off, for the suites that measure the JIT rather than answers, whose compiled code the
# sanitizer's branches would change. With it on, the run also fails when
# VarkaMemorySanitizerEndToEndSuite ran and cancelled a test, since a cancelled test there means
# the flag never reached the JVM.
#
# Exit status: 0 when every configuration passed, 1 otherwise; one line per configuration.

set -uo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd)
OUT=$ROOT/target/varka-matrix
jobs=1
build=1
list=0
deadline=""
modules=(sql catalyst)
shard=""
sbt_args=()
configs=()
defaults_only=0
split=1
jvm_args=()
sanitize=1
build_dir=""
only_suites=""
skip_suites=""
extra_suites=()
defaults_from=""

# The header is found, not numbered: a hard-coded range truncates as the comment grows.
usage() { sed -n '18,/^[^#]/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit "${1:-2}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --config) configs+=("$2"); shift 2 ;;
    --all) configs+=("ALL"); shift ;;
    -j) jobs="$2"; shift 2 ;;
    --skip-build) build=0; shift ;;
    --deadline) deadline=$(date -d "$2" +%s) || exit 2
      [ "$deadline" -lt "$(date +%s)" ] && deadline=$(date -d "tomorrow $2" +%s); shift 2 ;;
    --list) list=1; shift ;;
    --module) modules=("$2"); shift 2 ;;
    --shard) shard="$2"; shift 2 ;;
    --sbt-arg) sbt_args+=("$2"); shift 2 ;;
    --defaults) defaults_only=1; shift ;;
    --split) split="$2"; shift 2 ;;
    --jvm-arg) jvm_args+=("$2"); shift 2 ;;
    --no-sanitizer) sanitize=0; shift ;;
    --out) OUT=$(realpath -m "$2"); shift 2 ;;
    --build-dir) build_dir=$(realpath -m "$2"); shift 2 ;;
    --suites) only_suites="$2"; shift 2 ;;
    --skip-suites) skip_suites="$2"; shift 2 ;;
    --extra-suite) extra_suites+=("$2"); shift 2 ;;
    --defaults-from) defaults_from=$(realpath -m "$2"); shift 2 ;;
    -h|--help) usage 0 ;;
    *) echo "unknown argument: $1" >&2; usage ;;
  esac
done

# Suites whose subject is the defaults or the machine rather than an answer, so a configuration
# breaks them by construction. Each name with its reason.
EXCLUDED=(
  "VarkaEmittedBytesSuite:compares the emitted bytes with the defaults' committed oracle"
  "VarkaEmitCostAuditSuite:compares the cost audit with the defaults' committed oracle"
  "VarkaEmitOptionSuite:checks the defaults themselves"
  "VarkaMatrixSuite:checks the matrix's own machinery, not an answer"
  "VarkaSuiteHarnessSuite:checks the JUnit face of the matrix's machinery, not an answer"
  "VarkaAssemblySuite:reads the JIT's output, not an answer"
  "VarkaWarmupEndToEndSuite:times a kernel's compilation, which a loaded machine moves"
  "VarkaTierWitnessSuite:asserts which JIT tier ran a call, which a loaded machine moves"
)

# The gate runs every suite; the matrix leaves out the ones above.
[ "$defaults_only" = 1 ] && EXCLUDED=()
BUILD=${build_dir:-$OUT}
mkdir -p "$OUT" "$BUILD"
if [ "$build" = 1 ]; then
  # The catalyst suites alone need only catalyst's classes; anything with the SQL suites needs
  # sql's classpath, which holds catalyst's test classes too.
  project=sql
  [ "${modules[*]}" = catalyst ] && project=catalyst
  echo "== build: $project's test classes, its test classpath and JVM options"
  build/sbt -batch "${sbt_args[@]}" "$project/Test/compile" \
    "export $project/Test/fullClasspath" "show $project/Test/javaOptions" \
    > "$BUILD/build.log" 2>&1 || { echo "build failed; see $BUILD/build.log" >&2; exit 1; }
  # The exported classpath is the one line that is a colon-separated list of paths.
  # Under CI sbt colours its output, so its escape sequences and carriage returns are stripped
  # before the classpath and the JVM options are read off the log, as varka-fuzz.yml does.
  sed 's/\x1b\[[0-9;]*[A-Za-z]//g; s/\r//g' "$BUILD/build.log" > "$BUILD/build.plain.log"
  grep -E '^/[^ ]+:/' "$BUILD/build.plain.log" | tail -1 > "$BUILD/classpath"
  sed -n 's/^\[info\] \* //p' "$BUILD/build.plain.log" | grep -v '^-Djava.io.tmpdir=' \
    > "$BUILD/jvm.opts"
  grep -q scalatest "$BUILD/classpath" || {
    echo "no test classpath in $BUILD/build.log" >&2; exit 1; }
fi
[ -s "$BUILD/classpath" ] && [ -s "$BUILD/jvm.opts" ] || {
  echo "no classpath or JVM options in $BUILD; run without --skip-build" >&2; exit 1; }
CP=$(cat "$BUILD/classpath")
mapfile -t OPTS < "$BUILD/jvm.opts"
[ "$sanitize" = 1 ] && jvm_args+=("-Dvarka.sanitizeMemory=true")
# Each batch a kernel serves also runs through its other body, and the two must agree (VARKA-303).
jvm_args+=("-Dvarka.checkBothBodies=true")
OPTS+=("${jvm_args[@]}")

# The configurations, from VarkaMatrix itself so that the table stays their one source. The gate
# (--defaults) runs none and lists none.
if [ "$defaults_only" = 0 ] || [ "$list" = 1 ]; then
  MAIN=org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMatrixMain
  java "${OPTS[@]}" -cp "$CP" "$MAIN" \
    > "$OUT/configurations" 2> "$OUT/configurations.err" || {
      echo "could not list the configurations; see $OUT/configurations.err" >&2; exit 1; }
fi
if [ "$list" = 1 ]; then cat "$OUT/configurations"; exit 0; fi
if [ "$defaults_only" = 1 ]; then
  configs=()
else
  [ ${#configs[@]} -gt 0 ] || usage
  if [ "${configs[0]}" = "ALL" ]; then mapfile -t configs < "$OUT/configurations"; fi
fi
if [ -n "$shard" ]; then
  # --shard I/N keeps the configurations at positions I, I + N, I + 2N, ... (from zero).
  index=${shard%/*} count=${shard#*/}
  kept=()
  for k in "${!configs[@]}"; do (( k % count == index )) && kept+=("${configs[$k]}"); done
  configs=("${kept[@]}")
fi

# Every concrete Varka suite of a module, by its source file, minus the exclusions and the
# --suites / --skip-suites filters, one fully qualified name a line: the ScalaTest classes named
# Varka...Suite under src/test/scala, and the JUnit classes named Varka...Suite under @VarkaSuite
# in src/test/java (VARKA-312). Each module runs in JVMs of its own, as sbt runs it: a shape a
# catalyst suite compiled must not already be warm when a SQL suite asks for its first query.
# A suite that runs in a JVM of its own (VarkaOwnJvm, or @VarkaSuite(ownJvm = true)) is marked
# "@own " before its name: it gets a runner JVM to itself, below.
module_suites() {
  local root=$1 class_re=$2 marker=$3 own_re=$4 file pkg cls entry skip own
  [ -d "$root" ] || return 0
  while IFS= read -r file; do
    [ -n "$marker" ] && ! grep -qE "$marker" "$file" && continue
    own=""
    grep -qE "$own_re" "$file" && own="@own "
    pkg=$(sed -n 's/^package \([^;]*\);\{0,1\}$/\1/p' "$file" | head -1)
    for cls in $(grep -oE "$class_re" "$file" | awk '{print $NF}'); do
      skip=0
      for entry in "${EXCLUDED[@]}"; do [ "${entry%%:*}" = "$cls" ] && skip=1; done
      [ -n "$only_suites" ] && ! [[ ",$only_suites," == *",$cls,"* ]] && skip=1
      [ -n "$skip_suites" ] && [[ ",$skip_suites," == *",$cls,"* ]] && skip=1
      [ "$skip" = 0 ] && echo "$own$pkg.$cls"
    done
  done < <(grep -rlE "$class_re" "$root" | sort)
}
SCALA_SUITE='^class Varka[A-Za-z0-9]*Suite\b'
JAVA_SUITE='^(public )?(final )?class Varka[A-Za-z0-9]*Suite\b'
SCALA_OWN='^[^/*]*\b(with|extends) VarkaOwnJvm\b'
JAVA_OWN='^@VarkaSuite\(.*ownJvm *= *true'
declare -A module_root=([catalyst]=sql/catalyst/src/test [sql]=sql/core/src/test)
# Each module's suites over --split JVMs, longest first by the previous run's JUnit times: one
# line of space-separated suites per JVM, in shards/<module>. A suite that runs in a JVM of its own
# takes a line of its own, first, marked "@own ", and its JVM runs it directly
# (-Dvarka.ownJvm=true) instead of forking the child VarkaOwnJvm would start. That child's budget
# is one test's cap for the whole suite, nine minutes, and VarkaIrFuzzSuite takes about that long
# on a runner under any configuration, past it in two of them on 18 October (VARKA-394); a runner
# JVM that holds the suite alone is what the child exists to guarantee, and each test keeps its
# own cap. The Scala pattern skips comment lines, so that a suite which only mentions the trait
# stays in the shared JVM.
mkdir -p "$OUT/shards"
total=0
for module in "${modules[@]}"; do
  mapfile -t found < <(module_suites "${module_root[$module]}/scala" "$SCALA_SUITE" "" \
      "$SCALA_OWN";
    module_suites "${module_root[$module]}/java" "$JAVA_SUITE" '^@VarkaSuite\b' "$JAVA_OWN")
  for extra in "${extra_suites[@]}"; do
    [ "${extra%%:*}" = "$module" ] && found+=("${extra#*:}")
  done
  total=$((total + ${#found[@]}))
  shared=()
  for suite in "${found[@]}"; do [[ "$suite" == "@own "* ]] || shared+=("$suite"); done
  {
    for suite in "${found[@]}"; do [[ "$suite" == "@own "* ]] && echo "$suite"; done
    if [ ${#shared[@]} -gt 0 ]; then
      python3 dev/varka_suite_shards.py "$split" "${defaults_from:-$OUT}/runs" "${shared[@]}"
    fi
  } > "$OUT/shards/$module"
done
echo "== ${#configs[@]} configuration(s) and the defaults, $total suites," \
  "$split JVM(s) a module and one for each suite that runs alone, $jobs JVMs at a time"

# One JVM's suites under one configuration, in runs/<configuration>/<module>[-<k>]/. The runner
# takes ScalaTest's Runner's arguments and runs the JUnit suites after the ScalaTest ones, then
# prints one Suites: and one Tests: line over both, which the report reads (VARKA-312).
RUNNER=org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuiteRunner
run_one() {
  local config=$1 module=$2 part=$3 name=${1:-defaults}
  local leaf=$module
  [ "$(wc -l < "$OUT/shards/$module")" -gt 1 ] && leaf=$module-$part
  local dir=$OUT/runs/${name//[^A-Za-z0-9=_.-]/_}/$leaf
  local -a suites=() own=()
  local suite line
  line=$(sed -n "$((part + 1))p" "$OUT/shards/$module")
  if [[ "$line" == "@own "* ]]; then
    own=(-Dvarka.ownJvm=true)
    line=${line#@own }
  fi
  for suite in $line; do suites+=(-s "$suite"); done
  rm -rf "$dir"; mkdir -p "$dir/tmp"
  local start; start=$(date +%s)
  (cd "$dir" && SPARK_TESTING=1 SPARK_SCALA_VERSION=2.13 java "${OPTS[@]}" "${own[@]}" \
    -Djava.io.tmpdir="$dir/tmp" -Dvarka.matrix.config="$config" \
    -Dvarka.matrix.report="$dir/fused.tsv" \
    -cp "$CP" "$RUNNER" -oW -u "$dir/junit" "${suites[@]}" > "$dir/run.log" 2>&1)
  echo "$?" > "$dir/exit"
  echo "$(( $(date +%s) - start ))" > "$dir/seconds"
  echo "$name" > "$dir/../name"
}
# Waits while a laptop battery discharges below 30%; true unless the deadline has passed.
may_launch() {
  local bat=/sys/class/power_supply/BAT0
  while [ -r "$bat/status" ] && [ "$(cat "$bat/status")" = Discharging ] &&
      [ "$(cat "$bat/capacity")" -lt 30 ]; do
    sleep 60
  done
  [ -z "$deadline" ] || [ "$(date +%s)" -lt "$deadline" ]
}

# Each configuration's JVMs in the background, at most $jobs at once; the defaults first. The
# SQL suites take the longer, so each configuration starts them first.
rm -rf "$OUT/runs"
mkdir -p "$OUT/runs"
echo "${modules[*]}" > "$OUT/runs/modules"
all=("" "${configs[@]}")
if [ -n "$defaults_from" ]; then
  [ -d "$defaults_from/runs/defaults" ] || {
    echo "no defaults run under $defaults_from" >&2; exit 1; }
  cp -r "$defaults_from/runs/defaults" "$OUT/runs/defaults"
  all=("${configs[@]}")
fi
running=0
for config in "${all[@]}"; do
  for module in "${modules[@]}"; do
    parts=$(wc -l < "$OUT/shards/$module")
    for ((part = 0; part < parts; part++)); do
      may_launch || { echo "== deadline reached; no further configurations start"; break 3; }
      run_one "$config" "$module" "$part" &
      running=$((running + 1))
      if [ "$running" -ge "$jobs" ]; then wait -n; running=$((running - 1)); fi
    done
  done
done
wait

python3 dev/varka_matrix_report.py "$OUT/runs" sql/varka/matrix/skips.tsv
status=$?

# The proof that the sanitizer was on: its own suite cancels its tests without the flag, so any
# cancelled test there (JUnit's skipped) means a run that said it was sanitized was not.
if [ "$sanitize" = 1 ]; then
  while IFS= read -r xml; do
    if grep -q '<skipped' "$xml"; then
      echo "the memory sanitizer's own suite cancelled a test (${xml#"$OUT/"}):" \
        "the flag did not reach the JVM"
      status=1
    fi
  done < <(find "$OUT/runs" -name 'TEST-*VarkaMemorySanitizerEndToEndSuite.xml')
fi
exit "$status"
