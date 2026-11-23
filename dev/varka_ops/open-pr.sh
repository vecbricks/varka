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
# open-pr.sh <worktree> <branch> <body-file> <lock> <title>: check the title and body
# (dev/varka_msg_check.py pr), refuse a dirty worktree, a body that records no /code-review high
# pass, a head no high review covers or a head without a pre-pr.sh stamp; then push to the fork and
# open the PR on VARKA_REPO. <lock> names $VARKA_OPS_HOME/locks/<lock>.lock, the heavy-work lock
# the message check's tools take. Push log: $VARKA_OPS_HOME/logs/push-<dir>.log
set -u
. "$(dirname "$0")/common.sh"
[ $# -ge 5 ] || { echo "usage: open-pr.sh <worktree> <branch> <body-file> <lock> <title>"; exit 2; }
ops_fork
export PATH=$VENV/bin:$PATH SPARK_LOCAL_IP=127.0.0.1
W=$(ops_worktree "$1"); n=$(basename "$W"); b=$2; f=$(realpath "$3") || exit 1
export VARKA_HEAVY_LOCK=$OPS/locks/$4.lock; t=$5
cd "$W" || exit 1
[ -z "$(git status --porcelain --untracked-files=no)" ] || { echo "$n dirty"; exit 1; }
grep -qiE "/code-review (high|xhigh|max)|code-review high" "$f" \
  || { echo "$n: body records no /code-review high pass; review the branch before opening the PR"
       exit 1; }
python3 dev/varka_msg_check.py pr --title "$t" --body-file "$f" > /dev/null 2>&1 \
  || { echo "$n msg check failed"
       python3 dev/varka_msg_check.py pr --title "$t" --body-file "$f" 2>&1 | tail -n 3; exit 1; }
"$OPS_DIR/review-covers.sh" "$W" || { echo "$n: not opened"; exit 1; }
[ "$(cat "$OPS/stamps/$n" 2>/dev/null)" = "$(git rev-parse HEAD)" ] \
  || { echo "$n: no pre-pr.sh pass on this head; run $OPS_DIR/pre-pr.sh $n first"; exit 1; }
git push -q "$FORK_REMOTE" HEAD:"$b" > "$OPS/logs/push-$n.log" 2>&1 \
  || { echo "$n push failed"; tail -n 5 "$OPS/logs/push-$n.log"; exit 1; }
gh pr create --repo "$REPO" --base master --head "$FORK_OWNER:$b" --title "$t" --body-file "$f"
