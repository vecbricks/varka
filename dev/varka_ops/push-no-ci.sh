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
# push-no-ci.sh <worktree> <branch>: push a gated, reviewed head to the fork WITHOUT CI: refuse a
# head without a pre-pr.sh stamp or a covering high review, push, then for a minute cancel every
# run on the branch that is not completed, the ones the push started included.
set -u
. "$(dirname "$0")/common.sh"
[ $# -ge 2 ] || { echo "usage: push-no-ci.sh <worktree> <branch>"; exit 2; }
ops_fork
W=$(ops_worktree "$1"); b=$2; n=$(basename "$W")
[ "$(cat "$OPS/stamps/$n" 2>/dev/null)" = "$(git -C "$W" rev-parse HEAD)" ] \
  || { echo "$b: no pre-pr.sh pass on this head, not pushed"; exit 1; }
"$OPS_DIR/review-covers.sh" "$W" || { echo "$b: not pushed"; exit 1; }
out=$(git -C "$W" push "$FORK_REMOTE" HEAD:"$b" 2>&1); rc=$?
echo "$out" | tail -1
[ $rc = 0 ] || { echo "$b: push failed, nothing cancelled"; exit 1; }
for _ in 1 2 3; do sleep 20
  for id in $(gh run list --repo "$FORK" --branch "$b" --limit 10 --json databaseId,status \
      -q '.[]|select(.status!="completed")|.databaseId'); do
    gh run cancel "$id" --repo "$FORK" >/dev/null && echo "$b: cancelled $id"
  done
done
