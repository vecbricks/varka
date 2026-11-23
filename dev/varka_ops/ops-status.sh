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
# ops-status.sh: the overnight checklist in one command: each open VARKA_REPO PR with its merge
# state and the last verdict of its matrix-link comment, which watchers are alive, and the last
# lines of their logs. VARKA_OPS_WATCHERS lists the watcher scripts to look for (default
# "watch-ready.sh watch-failed.sh", kept in the ops home; their output is expected in
# $VARKA_OPS_HOME/logs/<name>.out). Run it at the start of every turn while a rule is active.
set -u
. "$(dirname "$0")/common.sh"
echo "== open PRs"
for n in $(gh pr list --repo "$REPO" --state open --json number --jq '.[].number'); do
  st=$(gh pr view "$n" --repo "$REPO" --json mergeStateStatus --jq .mergeStateStatus)
  last=$(gh pr view "$n" --repo "$REPO" --json comments \
    --jq '[.comments[].body|select(test("Option matrix run"))]|last // "no matrix link"' \
    | grep -oE '\((success|failure|cancelled)\)|no matrix link' | tail -1)
  echo "#$n $st matrix:${last:-pending}"
done
echo "== watchers"
logs=()
for w in ${VARKA_OPS_WATCHERS:-watch-ready.sh watch-failed.sh}; do
  if [ "$(ops_running "$w")" -gt 0 ]; then echo "$w alive"; else echo "$w NOT RUNNING"; fi
  logs+=("$OPS/logs/${w%.sh}.out")
done
tail -3 "${logs[@]}" 2>/dev/null
