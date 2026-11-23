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
# extra-gate.sh <worktree>: the checks CI runs that dev/varka_push_check.py does not, run on what
# the branch changed against origin/master: dev/lint-java and Error Prone (Java), dev/lint-scala
# (Scala), the invariant-site suite and the proof-file suite (catalyst or core main, or proofs,
# changed). Prints one line a step and "extra-gate: passed" or "extra-gate: FAILED"; exits 1 if a
# step fails. Log: $VARKA_OPS_HOME/logs/extra-gate-<dir>.log
set -u
. "$(dirname "$0")/common.sh"
[ $# -ge 1 ] || { echo "usage: extra-gate.sh <worktree>"; exit 2; }
w=$(ops_worktree "$1")
n=$(basename "$w"); L=$OPS/logs/extra-gate-$n.log; : > "$L"
export SPARK_LOCAL_IP=127.0.0.1 PATH=$VENV/bin:$PATH
cd "$w" || { echo "extra-gate: no worktree $w"; exit 2; }
base=$(git merge-base HEAD origin/master); ch=$(git diff --name-only --no-renames "$base" HEAD)
fail=0
run() {
  local what=$1; shift; echo "== $what" >> "$L"
  if timeout 3000 "$@" >> "$L" 2>&1; then echo "extra-gate: $what ok"
  else echo "extra-gate: $what FAILED (see $L)"; fail=1; fi
}
echo "$ch" | grep -qE '\.java$' && run lint-java dev/lint-java
# CI's "Linters" job also runs Error Prone and NullAway over Varka's Java (#1629 failed on it,
# 22 Nov 2026)
if echo "$ch" | grep -qE '\.java$' && [ -x dev/varka_errorprone.sh ]; then
  run errorprone dev/varka_errorprone.sh --master "$(git rev-parse origin/master)"
fi
echo "$ch" | grep -qE '\.scala$' && run lint-scala dev/lint-scala
if echo "$ch" | grep -qE '^sql/catalyst/src/main/|^sql/varka/proofs/|^sql/core/src/main/'; then
  run catalyst-invariant-proof-suites \
    build/sbt "catalyst/testOnly *VarkaInternalErrorSuite *VarkaProofFilesSuite"
fi
rm -f none
[ $fail = 0 ] && echo "extra-gate: passed" || echo "extra-gate: FAILED"
exit $fail
