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
# matrix-after-build-link.sh <build-run> <branch> <out> [--size-ok]: wait for the Build run on the
# fork, dispatch the option matrix on success (with --size-ok also when the pull-request size step
# is the only failure), post the matrix run's link on the PR and keep the comment's state current
# (matrix-link.sh). A failure only in the assembly gate (runner DNS) waits up to 3 h for its rerun.
# Writes its verdict to $VARKA_OPS_HOME/logs/<out>; exits 0 once the matrix is dispatched, 1 if
# the Build failed otherwise. Started by start-waiter.sh.
set -u
. "$(dirname "$0")/common.sh"
[ $# -ge 3 ] || { echo "usage: matrix-after-build-link.sh <build-run> <branch> <out> [--size-ok]"
  exit 2; }
ops_fork
R=$1; B=$2; O=$OPS/logs/$3; SIZE_OK=${4:-}; t0=$(date +%s)
dispatch() {
  gh workflow run varka-option-matrix.yml -R "$FORK" --ref "$B" >> "$O" 2>&1; rc=$?
  echo "$(date +%H:%M) $B $1, matrix dispatched rc=$rc" >> "$O"
  url=$(grep -o "https://github.com/$FORK/actions/runs/[0-9]*" "$O" | tail -1)
  [ -n "$url" ] && "$OPS_DIR/matrix-link.sh" "$B" "$url" >> "$O" 2>&1
}
while :; do
  s=$(gh run view "$R" -R "$FORK" --json status,conclusion \
    --jq '"\(.status) \(.conclusion)"' 2>/dev/null)
  case "$s" in
    completed\ success) dispatch "build ok"; exit 0;;
    completed*)
      f=$(gh run view "$R" -R "$FORK" --json jobs \
        --jq '[.jobs[]|select(.conclusion=="failure")|.name]|join(",")' 2>/dev/null)
      if [ "$f" = "Run / Varka assembly gate" ] && [ $(( $(date +%s) - t0 )) -lt 10800 ]; then
        sleep 120; continue
      fi
      if [ "$SIZE_OK" = "--size-ok" ]; then
        steps=$(gh api "repos/$FORK/actions/runs/$R/jobs" --paginate \
          --jq '.jobs[]|select(.conclusion=="failure")|.steps[]|select(.conclusion=="failure")|.name' \
          2>/dev/null | sort -u)
        if echo "$steps" | grep -qi size && [ "$(echo "$steps" | wc -l)" = 1 ]; then
          dispatch "only the size step failed"; exit 0
        fi
      fi
      echo "$(date +%H:%M) $B build $s failed=[$f], no matrix" >> "$O"; exit 1;;
  esac
  sleep 120
done
