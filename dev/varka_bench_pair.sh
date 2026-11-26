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
# Run a benchmark class on a base and on the head in alternating batches, and compare medians
# (VARKA-256, m10/SCOPE.md item 72; DuckDB's regression runner is the model, m7/READING.md 9).
#
#   dev/varka_bench_pair.sh catalyst VarkaCompileBenchmark   # base: merge base with origin/master
#   dev/varka_bench_pair.sh core VarkaFilterBenchmark --base v0.9 --rounds 7 --threshold 8
#   dev/varka_bench_pair.sh catalyst VarkaCompileBenchmark --base-dir ../varka-base  # built base
#   dev/varka_bench_pair.sh catalyst VarkaCompileBenchmark --base HEAD  # A/A: the runner's spread
#   dev/varka_bench_pair.sh core VarkaRangeFilterBenchmark --base <sha> --force --out DIR  # CI
#
# One regeneration of a file on one machine cannot tell a pull request's change from the
# machine's day; alternating the two sides batch by batch cancels what drifts between them, and
# the median of each side discards the batch a neighbour disturbed. Each round runs the class on
# the base and on the head (the order swaps every round), with dev/varka_bench_regen.sh's idle
# check and canary, wide width only; the results are copied aside and the committed files put back,
# so neither tree is left dirty. After at least three rounds it stops early when every row is
# within --within percent of the other side. It then prints each row's change by the median and
# by the best run (+ faster, - slower), and exits 1 if any row is slower by --threshold percent or
# more by both. With --base HEAD it pairs the head with itself: an A/A run, whose widest change is
# the runner's own spread for the class, to read a real pair's changes against.
#
#   --base REF        the base: any ref; default the merge base of HEAD and origin/master.
#                     A worktree is made for it at /tmp and removed afterwards, and needs a build
#                     (the first round compiles it). Ignored with --base-dir.
#   --base-dir DIR    an existing worktree to use as the base (it is not removed).
#   --rounds N        at most N rounds (default 5).
#   --threshold P     a row slower by P percent fails (default 10).
#   --within P        the early stop's band (default 3).
#   --force, --no-pin passed to dev/varka_bench_regen.sh. With --force the wait for the load
#                     to settle before each side gives up after two minutes with a warning, not
#                     ten with an error: a GitHub-hosted runner may never read idle, and the
#                     pair's alternation, not the idle check, is its control there.
#   --out DIR         write each round's results file, provenance and log to DIR, kept
#                     afterwards (the benchmark workflow uploads it); default a temporary
#                     directory, removed unless --keep.
#   --keep            keep the base worktree and the result files, and print where they are.
set -euo pipefail

usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }
case "${1:-}" in -h|--help) usage 0 ;; esac
[ "$#" -ge 2 ] || usage
module="$1"; klass="$2"; shift 2
base_ref=""; base_dir=""; rounds=5; threshold=10; within=3; keep=0; force=0; out=""
passthru=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --base) base_ref="$2"; shift 2 ;;
    --base-dir) base_dir="$(realpath "$2")"; shift 2 ;;
    --rounds) rounds="$2"; shift 2 ;;
    --threshold) threshold="$2"; shift 2 ;;
    --within) within="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    --keep) keep=1; shift ;;
    --force) force=1; passthru+=("$1"); shift ;;
    --no-pin) passthru+=("$1"); shift ;;
    *) usage ;;
  esac
done

here="$(cd "$(dirname "$0")" && pwd)"
head_dir="$(git rev-parse --show-toplevel)"
case "$module" in
  catalyst) bdir="sql/catalyst/benchmarks" ;;
  core|sql) bdir="sql/core/benchmarks" ;;
  *) echo "unknown module '$module' (catalyst or core)" >&2; exit 2 ;;
esac
wide="$bdir/$klass-jdk25-results.txt"
prov="$bdir/$klass-jdk25-provenance.txt"

keep_out="$keep"
if [ -n "$out" ]; then
  mkdir -p "$out"; out="$(realpath "$out")"; keep_out=1
else
  out="$(mktemp -d -t varka-pair.XXXXXX)"
fi
made_worktree=0
# The regeneration script overwrites the results file and its provenance in the tree it runs in.
# They are put back after every run, and on any exit or interrupt: a file tracked at the tree's
# commit is checked out, one that is not (a base older than the benchmark) is removed. Only once
# both trees have been checked clean (`touched`): an exit before that, such as the refusal of a
# tree with uncommitted results, must leave those results alone, since keeping them is the
# refusal's whole point; restoring them anyway once threw away a fresh regeneration.
touched=0
restore_files() {
  local tree="$1" path
  for path in "$wide" "$prov"; do
    if git -C "$tree" cat-file -e "HEAD:$path" 2> /dev/null; then
      git -C "$tree" checkout -- "$path" 2> /dev/null || true
    else
      rm -f "$tree/$path"
    fi
  done
}
cleanup() {
  if [ "$touched" -eq 1 ]; then
    [ -z "${base_dir:-}" ] || restore_files "$base_dir"
    restore_files "$head_dir"
  fi
  if [ "$made_worktree" -eq 1 ] && [ "$keep" -eq 0 ]; then
    git -C "$head_dir" worktree remove --force "$base_dir" > /dev/null 2>&1 || true
  fi
  if [ "$keep_out" -eq 0 ]; then rm -rf "$out"; else echo "results kept in $out"; fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

if [ -z "$base_dir" ]; then
  # The merge base is read from a fresh origin/master, or the pair compares with an older commit
  # than the pull request's real base. A caller whose origin is not the project's repository (a
  # fork's checkout, as on the fork's runners) computes the base itself and passes --base.
  git fetch -q origin master 2> /dev/null || echo "warning: could not fetch origin/master" >&2
  [ -n "$base_ref" ] || base_ref="$(git merge-base HEAD origin/master)"
  base_dir="/tmp/varka-pair-base-$$"
  git worktree add --detach "$base_dir" "$base_ref" > /dev/null
  made_worktree=1
  echo "note: $base_dir is a new worktree and has no build; its first round compiles it" >&2
fi
echo "base: $(git -C "$base_dir" log -1 --format='%h %s' | cut -c1-80) ($base_dir)"
echo "head: $(git -C "$head_dir" log -1 --format='%h %s' | cut -c1-80) ($head_dir)"

# Modified or untracked: an untracked results file (a class not yet committed) is removed by
# restore_files as surely as a modified one is checked out.
for tree in "$base_dir" "$head_dir"; do
  if [ -n "$(git -C "$tree" status --porcelain -- "$wide" "$prov" 2> /dev/null)" ]; then
    echo "$tree has uncommitted changes to $wide or its provenance;" \
      "commit or restore them" >&2
    exit 1
  fi
done
touched=1

# The regeneration script refuses above a load of 1.0, and the run before this one leaves its own
# load behind for a minute, so each side waits for the one-minute average to fall (ten minutes at
# most) rather than failing on the previous side's tail.
wait_idle() {
  local waited=0 limit=600
  [ "$force" -eq 0 ] || limit=120
  until awk -v l="$(cut -d' ' -f1 /proc/loadavg)" 'BEGIN { exit !(l < 0.8) }'; do
    if [ "$waited" -ge "$limit" ]; then
      if [ "$force" -eq 1 ]; then
        echo "warning: the load did not settle in two minutes; running anyway (--force)" >&2
        return 0
      fi
      echo "the machine did not settle in ten minutes" >&2; exit 1
    fi
    sleep 10; waited=$((waited + 10))
  done
}

run_side() {  # side tree round
  local side="$1" tree="$2" r="$3" attempt
  echo "== round $r: $side =="
  # The regeneration script refuses a busy machine and one whose canary is off its baseline, and
  # both happen for a minute or two after the previous benchmark JVM exits, and the canary's own
  # run raises the load: such a refusal is waited out and tried again, five times; any other
  # failure ends the pair.
  for attempt in 1 2 3 4 5; do
    wait_idle
    if (cd "$tree" && "$here/varka_bench_regen.sh" "$module" "$klass" --no-narrow \
        "${passthru[@]}" > "$out/$side-$r.log" 2>&1); then
      cp "$tree/$wide" "$out/$side-$r.txt"
      cp "$tree/$prov" "$out/$side-$r-provenance.txt" 2> /dev/null || true
      restore_files "$tree"
      return 0
    fi
    if grep -q "the machine is not idle\|not in its baseline state" "$out/$side-$r.log"; then
      echo "  refused (attempt $attempt): the machine is not settled; waiting"
      sleep 60
    else
      cat "$out/$side-$r.log" >&2; echo "the $side run failed" >&2; exit 1
    fi
  done
  echo "the $side run was refused five times" >&2; exit 1
}

base_files=(); head_files=()
for r in $(seq 1 "$rounds"); do
  if [ $((r % 2)) -eq 1 ]; then first="base"; second="head"; else first="head"; second="base"; fi
  for side in "$first" "$second"; do
    if [ "$side" = base ]; then run_side base "$base_dir" "$r"
    else run_side head "$head_dir" "$r"; fi
  done
  base_files+=("$out/base-$r.txt"); head_files+=("$out/head-$r.txt")
  if [ "$r" -ge 3 ] && [ "$r" -lt "$rounds" ] \
      && [ "$("$here/varka_bench_pair.py" settled --base "${base_files[@]}" \
              --head "${head_files[@]}" --within "$within")" = settled ]; then
    echo "every row is within $within% after $r rounds; stopping"
    break
  fi
done
echo
"$here/varka_bench_pair.py" compare --base "${base_files[@]}" --head "${head_files[@]}" \
  --threshold "$threshold"
