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
# push-ci.sh <worktree> <branch> <pr>: push a gated, reviewed head to the fork WITH CI, then arm
# the matrix waiter (start-waiter.sh) on the Build the push starts. Refuses a head without a
# pre-pr.sh stamp or without a recorded high review covering it (review-covers.sh). The only way a
# fix push goes out (22 Nov 2026).
set -u
. "$(dirname "$0")/common.sh"
[ $# -ge 3 ] || { echo "usage: push-ci.sh <worktree> <branch> <pr>"; exit 2; }
ops_fork
W=$(ops_worktree "$1"); b=$2; pr=$3; n=$(basename "$W")
h=$(git -C "$W" rev-parse HEAD) || exit 1
[ "$(cat "$OPS/stamps/$n" 2>/dev/null)" = "$h" ] \
  || { echo "$b: no pre-pr.sh pass on ${h:0:11}, not pushed"; exit 1; }
"$OPS_DIR/review-covers.sh" "$W" || { echo "$b: not pushed"; exit 1; }
out=$(git -C "$W" push -q "$FORK_REMOTE" HEAD:"$b" 2>&1) \
  || { echo "$out" | tail -1; echo "$b: push failed, not pushed"; exit 1; }
id=
for _ in 1 2 3 4 5 6; do sleep 20
  id=$(gh run list --repo "$FORK" --branch "$b" --limit 5 --json databaseId,name,headSha \
    -q ".[]|select(.name==\"Build\" and .headSha==\"$h\")|.databaseId" | head -1)
  [ -n "$id" ] && break
done
[ -n "$id" ] \
  || { echo "$b: pushed ${h:0:11}, but no Build appeared; arm the waiter by hand"; exit 1; }
"$OPS_DIR/start-waiter.sh" "$pr" "$id" "$b" | tail -1
echo "$b: pushed ${h:0:11}, Build $id"
