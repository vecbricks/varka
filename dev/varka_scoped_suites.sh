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
# One module's Varka suites under the defaults, as CI's varka-scoped job runs them
# (VARKA-287.md 3.1), or with --shard I/N one job's share of them, as CI's sharded jobs do
# (VARKA-483):
#
#   dev/varka_scoped_suites.sh catalyst
#   dev/varka_scoped_suites.sh sql          # the Varka suites and ArrowCachedBatchSerializerSuite
#
# The step lives here rather than in build_and_test.yml so that changing how the suites run is a
# change to a Varka script, whose pull request runs the varka-scoped jobs it changes; a change to
# the workflow runs Spark's module matrix instead (dev/varka_scope.py).
#
# The suites run through dev/varka_matrix.sh over two JVMs, the runner's four cores' worth, beside
# a JVM of its own for each suite that runs alone (VarkaOwnJvm), two at a time, in
# target/varka-scoped, which the job caches so the next run balances its JVMs by this run's JUnit
# times. The run records each test's fused batches too, as dev/varka_matrix.sh does for any run.
#
# The module's suites that measure the JIT rather than answers - VarkaAssemblySuite and
# VarkaTierWitnessSuite in catalyst, VarkaWarmupEndToEndSuite in sql - run after the others, alone and without the memory sanitizer,
# in target/varka-scoped-quiet, as the gate's quiet step runs them: beside the other JVM's suites
# the warm-up test's kernel has stayed mostly uncompiled past its two minutes (VARKA-295.md 9.2),
# and a rerun, restoring the same cached times, puts it beside the same suites again. A suite of
# that step that is known to fail by the JVM's luck is listed below with its reason and its row,
# and is run again in a fresh JVM when it fails, up to twice, with a warning annotation on the
# run each time (VARKA-390). The catalyst run ends with the forked arms' smoke (VARKA-272):
# dev/varka_platform.sh's interpreter arm on the same build, in target/varka-scoped-platform.

set -euo pipefail

cd "$(dirname "$0")/.."
module=${1:?usage: dev/varka_scoped_suites.sh catalyst|sql [--shard I/N]}
shard=""
[ "${2:-}" = --shard ] && shard=${3:?--shard takes I/N, I counted from zero}
extra=()
case "$module" in
  catalyst) timing=VarkaAssemblySuite,VarkaTierWitnessSuite ;;
  sql) timing=VarkaWarmupEndToEndSuite
       extra=(--extra-suite
         sql:org.apache.spark.sql.execution.columnar.ArrowCachedBatchSerializerSuite) ;;
  *) echo "unknown module: $module" >&2; exit 2 ;;
esac
matrix=${VARKA_MATRIX:-dev/varka_matrix.sh}   # the runner; a test puts a stub here

# --shard I/N: this job runs its share of the module's suites, one of N jobs that split them by
# the committed weights in sql/varka/ci/suite-times.tsv (dev/varka_suite_shards.py --job), so
# that every job of a run computes the same split from its own checkout. The share's simple
# names go to target/varka-scoped/job-suites, which the job's later step reads - the new tests
# (dev/varka_new_tests.py) - so it runs over the suites this job ran. The log prints a digest of
# the whole split: two jobs of one run that print different digests saw different inputs.
only=()
mkdir -p target/varka-scoped
# The cache restores this directory, so a share left by an earlier sharded run goes first.
rm -f target/varka-scoped/job-suites
if [ -n "$shard" ]; then
  mapfile -t all < <("$matrix" --defaults --list-suites --module "$module" "${extra[@]}" \
    | sed 's/^@own //')
  [ ${#all[@]} -gt 0 ] || { echo "the runner listed no suite of $module" >&2; exit 1; }
  weights=sql/varka/ci/suite-times.tsv
  count=${shard#*/}
  # A job that does more than run its share would take a head start here (--head-start J:SECONDS,
  # VARKA-516); none does since the option matrix's classes have a job of their own (VARKA-519).
  head=()
  digest=$(for ((k = 0; k < count; k++)); do
      echo "== $k"
      python3 dev/varka_suite_shards.py --job "$k/$count" "$weights" "${head[@]}" "${all[@]}"
    done | md5sum | cut -c1-12)
  mapfile -t mine < <(python3 dev/varka_suite_shards.py --job "$shard" "$weights" "${head[@]}" \
    "${all[@]}")
  printf '%s\n' "${mine[@]##*.}" > target/varka-scoped/job-suites
  echo "== job $shard of $module's Varka suites: ${#mine[@]} of ${#all[@]}," \
    "split digest $digest: $(tr '\n' ' ' < target/varka-scoped/job-suites)"
  # The JIT-measuring suites run below only where they fall in this share, and the extra suite
  # only where it does.
  timing=$({ grep -xF -f <(tr , '\n' <<< "$timing") target/varka-scoped/job-suites || true; } \
    | paste -sd, -)
  kept=()
  for e in "${extra[@]}"; do
    [ "$e" = --extra-suite ] && continue
    grep -qxF "${e##*.}" target/varka-scoped/job-suites && kept+=(--extra-suite "$e")
  done
  extra=("${kept[@]}")
  rest=$({ grep -vxF -f <(tr , '\n' <<< "${timing:-@none}") target/varka-scoped/job-suites \
    || true; } | paste -sd, -)
  [ -n "$rest" ] || { echo "job $shard of $module has no suite but the JIT-measuring ones" >&2
    exit 1; }
  only=(--suites "$rest")
fi
# With VARKA_REACH_RECORD naming a file, the run records which option configurations move each
# test (VarkaTestReach), for the option matrix dispatched on the same commit.
reach=()
if [ -n "${VARKA_REACH_RECORD:-}" ]; then
  reach=(--jvm-arg "-Dvarka.matrix.reachRecord=$VARKA_REACH_RECORD")
fi
# With VARKA_HARNESS_STORE naming a directory, the expression harness's JVMs write their plans
# and Spark's answers there (VarkaHarnessStore), for the same option matrix's configurations to
# read instead of planning and computing them again.
if [ -n "${VARKA_HARNESS_STORE:-}" ]; then
  reach+=(--jvm-arg "-Dvarka.harness.store=$VARKA_HARNESS_STORE")
fi
"$matrix" --defaults --module "$module" --split 2 -j 2 --sbt-arg -Phive \
  --out target/varka-scoped --skip-suites "${timing:-@none}" "${only[@]}" "${extra[@]}" \
  "${reach[@]}"
# A suite of this job's share whose time has moved far from its weight is reported as a warning,
# so the split by the committed weights does not drift out of balance unseen (VARKA-516).
if [ -n "$shard" ]; then
  python3 dev/varka_suite_shards.py --check-weights "$weights" target/varka-scoped/runs \
    target/varka-scoped/job-suites
fi

# The suites that measure the JIT may be given a fresh JVM when they fail, since what they
# measure is decided per JVM: a suite in this list that fails is run again, alone, in a new JVM,
# up to $retries times, and the job passes if one of those passes. Each entry carries the reason
# and where the fix is tracked; a suite leaves the list when its row lands. Any failure outside
# the list, or a failure that left no JUnit report (a crash), fails the job at once, and every
# retry is announced as a warning annotation so a pass that needed one is visible on the pull
# request. Only a suite that runs alone can be retried this way: a retry inside the same JVM
# would meet the same compiled code, which is the thing that failed.
flaky=(
  "VarkaWarmupEndToEndSuite: on a four-core 256-bit host a template's first compile lands at
   tier 2 when C2's queue is over ten entries at that instant, and the kernel that inlines it
   keeps a virtual call and boxes (m7/VARKA-389.md 9.5, m9 SCOPE item 103)"
)
retries=2

quiet() {   # the JIT-measuring suites $2, alone and without the sanitizer, under $1
  "$matrix" --defaults --module "$module" --skip-build --build-dir target/varka-scoped \
    --out "$1" --suites "$2" -j 1 --no-sanitizer
}
failed_suites() {   # simple names of the suites with a failed or errored test under $1
  # The counts are attributes of the report's <testsuite> tag, which ScalaTest writes on the line
  # after the tag itself, so they are looked for anywhere in the file; no other element has them.
  { grep -l -E '(failures|errors)="[1-9]' "$1"/runs/defaults/*/junit/TEST-*.xml 2>/dev/null \
    || true; } | sed -E 's#.*/TEST-(.*)\.xml$#\1#; s#.*\.##' | sort -u | tr '\n' ' '
}
failed_tests() {    # the failed tests' names under $1, one line, for the annotation
  # A <testcase> tag's attributes follow on the next line, as the suite tag's do, so the name is
  # the first name="..." after the tag, wherever the line break falls.
  { cat "$1"/runs/defaults/*/junit/TEST-*.xml 2>/dev/null || true; } |
    awk '/<testcase/ { n = ""; want = 1 }
         want && / name="[^"]*"/ { match($0, / name="[^"]*"/)
                                   n = substr($0, RSTART + 7, RLENGTH - 8); want = 0 }
         /<failure|<error/ { printf "%s\"%s\"", sep, n; sep = "; " }'
}
is_flaky() {        # whether $1 heads an entry of the list
  local entry
  for entry in "${flaky[@]}"; do [ "${entry%%:*}" = "$1" ] && return 0; done
  return 1
}

out=target/varka-scoped-quiet
suites=$timing
attempt=0
# A share with no JIT-measuring suite skips the step.
[ -z "$suites" ] || until quiet "$out" "$suites"; do
  failed=$(failed_suites "$out")
  for s in $failed; do
    is_flaky "$s" || { echo "$s failed and is not a suite CI retries" >&2; exit 1; }
  done
  [ -n "$failed" ] || { echo "the JIT-measuring suites failed without a JUnit report" >&2; exit 1; }
  attempt=$((attempt + 1))
  if [ "$attempt" -gt "$retries" ]; then
    echo "${failed% } failed in $((retries + 1)) JVMs" >&2; exit 1
  fi
  echo "::warning title=Flaky suite retried::${failed% } failed in its JVM" \
    "(attempt $attempt of $((retries + 1)): $(failed_tests "$out")); a fresh JVM follows"
  out=target/varka-scoped-quiet-retry$attempt
  suites=$(echo "$failed" | tr ' ' , | sed 's/,$//')
done
# The forked arms' smoke (VARKA-272): the interpreter arm over the IR fuzzer's first draws on
# this build, made to prove its tier (nothing is compiled, whatever the draw and the machine),
# a few seconds. Every other arm's proof is a fact about what the JIT did with a draw: on two
# loaded cores C1's `released` history was not reached in two runs of three at one compiled
# draw, because the case's body had no loop method or C1 installed nothing in 200 batches
# (VARKA-272.md 9.6). Those arms keep their strict rules over 20 draws in the nightly.
if [ "$module" = catalyst ] && { [ -z "$shard" ] || [ "${shard%/*}" = 0 ]; }; then
  dev/varka_platform.sh --arms int --iterations 20 --draws 1 --skip-build \
    --build-dir target/varka-scoped --out target/varka-scoped-platform
fi
