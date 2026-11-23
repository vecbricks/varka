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
# matrix-link.sh <branch> <run-url|run-id>: post (once) a comment on the branch's VARKA_REPO PR
# that links the option matrix run, then follow the run in the background and keep the comment's
# state current. The run's own status reads "queued" while its jobs execute, so the state comes
# from its jobs. The comment's id is kept in $VARKA_OPS_HOME/matrix-comments/<pr>.id, so a later
# run of the same PR edits that comment rather than adding one.
set -u
. "$(dirname "$0")/common.sh"
[ $# -ge 2 ] || { echo "usage: matrix-link.sh <branch> <run-url|run-id>"; exit 2; }
ops_fork
B=$1; R=${2##*/}
pr=$(gh api "repos/$REPO/pulls?head=$FORK_OWNER:$B&state=all" --jq '.[0].number' 2>/dev/null)
[ -n "$pr" ] && [ "$pr" != null ] || { echo "no PR for $B"; exit 1; }
D=$OPS/matrix-comments
url=https://github.com/$FORK/actions/runs/$R
body() { printf 'Option matrix run: %s (%s)' "$url" "$1"; }
edit() {
  gh api -X PATCH "repos/$REPO/issues/comments/$cid" -f body="$(body "$1")" >/dev/null
}
cid=$(cat "$D/$pr.id" 2>/dev/null)
if [ -z "$cid" ]; then
  cid=$(gh api "repos/$REPO/issues/$pr/comments" -f body="$(body started)" --jq .id)
  echo "$cid" > "$D/$pr.id"
else
  edit started
fi
echo "PR #$pr comment $cid -> $url"
( last=
  while :; do
    s=$(gh run view "$R" -R "$FORK" --json status,conclusion \
      --jq '"\(.status) \(.conclusion)"' 2>/dev/null)
    case "$s" in
      completed*) edit "${s#completed }"; exit 0;;
    esac
    j=$(gh api "repos/$FORK/actions/runs/$R/jobs" --paginate --jq '.jobs[]|.status' 2>/dev/null \
      | sort | uniq -c | awk '{printf "%s %s, ", $1, $2}')
    now="jobs: ${j%, }"
    [ "$now" != "$last" ] && [ -n "$j" ] && { edit "$now"; last=$now; }
    sleep 300
  done ) >/dev/null 2>&1 &
