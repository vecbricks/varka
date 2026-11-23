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
# pre-pr.sh <worktree> [--no-merge] [-j N]
# The one local gate before any push, fix push or PR open (22 Nov 2026: "prevent all the failures").
# Steps, in order, stopping at the first failure:
#   1. merge origin/master (stops on a conflict: resolve by hand, commit, rerun)
#   2. dev/varka_regen_generated.sh --all, and commit what it changed (a clean text merge can leave
#      generated files stale: 348's push check, 22 Nov); skipped for a branch that changes no
#      input of the generated files (below)
#   3. extra-gate.sh: lint-java, Error Prone, lint-scala, invariant and proof suites
#   4. dev/varka_push_check.py (its suites and the size cap)
#   5. the arm sweep: every test suite the branch adds or changes, under every option configuration
#      (dev/varka_matrix.sh --all); a change to the options also sweeps VarkaMatrixSuite. Three of
#      22 Nov's red matrices (#1617, #1619, #1625) were arms nobody had run locally.
# On success it writes $VARKA_OPS_HOME/stamps/<worktree-dir> with HEAD; open-pr.sh, push-ci.sh and
# push-no-ci.sh refuse a head without that stamp. Log: $VARKA_OPS_HOME/logs/pre-pr-<dir>.log
set -u
. "$(dirname "$0")/common.sh"
[ $# -ge 1 ] || { echo "usage: pre-pr.sh <worktree> [--no-merge] [-j N]"; exit 2; }
w=$(ops_worktree "$1"); shift
merge=1; J=4
while [ $# -gt 0 ]; do
  case $1 in
    --no-merge) merge=0;;
    -j) [ $# -ge 2 ] || { echo "pre-pr: -j needs a number"; exit 2; }; J=$2; shift;;
  esac
  shift
done
n=$(basename "$w"); L=$OPS/logs/pre-pr-$n.log; : > "$L"
export SPARK_LOCAL_IP=127.0.0.1 PATH=$VENV/bin:$PATH VARKA_HEAVY_LOCK=none
cd "$w" || { echo "pre-pr: no worktree $w"; exit 2; }
# regen and the push check leave a stray untracked file `none` (VARKA_HEAVY_LOCK=none); remove it
# on any exit
trap 'rm -f "$w/none"' EXIT
say() { echo "pre-pr: $*"; echo "pre-pr: $*" >> "$L"; }
die() { say "FAILED at $*"; exit 1; }
[ -z "$(git status --porcelain --untracked-files=no)" ] \
  || die "start: the worktree has uncommitted changes"

if [ $merge = 1 ]; then
  git fetch -q origin master || die "fetch"
  if git merge-base --is-ancestor origin/master HEAD; then
    say "master $(git rev-parse --short origin/master) already merged"
  else
    git merge -q --no-edit -m "Merge origin/master into $(git branch --show-current)

$TRAILER" origin/master >> "$L" 2>&1 \
      || { git diff --name-only --diff-filter=U; die "merge: conflict; resolve, commit, rerun"; }
    say "merged master $(git rev-parse --short origin/master)"
  fi
fi

# A branch whose own changes touch no input of the generated files cannot leave them stale
# (master's are kept current at each merge), so its regen, whose width audit alone can take an
# hour on a laptop, is skipped (23 Nov 2026); ops_regen_inputs in common.sh says what an input is.
inputs=$(ops_regen_inputs "$w")
if [ -z "$inputs" ] && [ "${PRE_PR_FORCE_REGEN:-0}" != 1 ]; then
  say "regen skipped: the branch changes no input of the generated files" \
    "(PRE_PR_FORCE_REGEN=1 forces it)"
  python3 dev/varka_census.py --check >> "$L" 2>&1 || die "census check (see $L)"
else
  dev/varka_regen_generated.sh --all >> "$L" 2>&1 || die "regen (see $L)"
fi
rm -f none
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  git add -u && git commit -q -m "Regenerate the generated files after merging master

$TRAILER" >> "$L" 2>&1 || die "regen commit"
  say "regen changed files; committed $(git rev-parse --short HEAD)"
else say "regen: nothing changed"; fi

"$OPS_DIR/extra-gate.sh" "$w" | tee -a "$L" | grep -q "extra-gate: passed" \
  || die "extra-gate (see $OPS/logs/extra-gate-$n.log)"
say "extra-gate passed"

python3 dev/varka_push_check.py >> "$L" 2>&1 || die "push check (see $L)"
rm -f none
say "push check passed ($(grep -o 'pull request size: [0-9]* counted lines' "$L" | tail -1))"

base=$(git merge-base HEAD origin/master)
ch=$(git diff --name-only --no-renames --diff-filter=AM "$base" HEAD)
suites() {
  echo "$ch" | grep -E "^$1/.*Suite\.(scala|java)$" | sed -E 's#.*/##; s#\.(scala|java)$##' \
    | sort -u | paste -sd, -
}
cat_s=$(suites sql/catalyst/src/test); sql_s=$(suites sql/core/src/test)
if echo "$ch" | grep -qE 'codegen/varka/VarkaEmitOption(s)?\.java$|codegen/varka/VarkaMatrix'; then
  cat_s=$(echo "$cat_s,VarkaMatrixSuite" | tr ',' '\n' | grep -v '^$' | sort -u | paste -sd, -)
  say "options changed: VarkaMatrixSuite joins the sweep"
fi
swept=0
for m in catalyst sql; do
  s=$cat_s; [ $m = sql ] && s=$sql_s
  [ -n "$s" ] || continue
  say "arm sweep, $m: $s (every configuration, -j $J)"
  out=target/pre-pr-sweep-$m
  dev/varka_matrix.sh --all -j "$J" --module $m --suites "$s" --out $out >> "$L" 2>&1 \
    || { sed -n '/^configuration/,$p' "$L" \
           | grep -E "FAILED|stale|^ *[a-zA-Z].* [1-9][0-9]* +[0-9]+ +[0-9]+ +[1-9]" | head -20
         die "arm sweep $m (see $L)"; }
  swept=1
done
[ $swept = 1 ] || say "arm sweep: the branch adds or changes no suite; nothing swept" \
  "(a main-code-only change: name its suites and run varka_matrix.sh --all --suites by hand)"
rm -f none

git rev-parse HEAD > "$OPS/stamps/$n"
say "passed on $(git rev-parse --short HEAD)"
