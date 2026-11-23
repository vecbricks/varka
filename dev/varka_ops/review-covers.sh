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
# review-covers.sh <worktree>: exit 0 when the recorded high review (stamps/review-<dir>) covers
# HEAD: HEAD is the reviewed commit, or every first-parent commit after it is a merge (of master)
# or a commit whose subject starts "Regenerate the generated files" (what pre-pr.sh adds). Master's
# own commits, brought in by a merge, do not count. Anything else - a fix after the review - needs
# a new review. Prints why and exits 1 otherwise.
set -u
. "$(dirname "$0")/common.sh"
[ $# -ge 1 ] || { echo "usage: review-covers.sh <worktree>"; exit 2; }
w=$(ops_worktree "$1")
n=$(basename "$w"); s=$OPS/stamps/review-$n
[ -f "$s" ] || { echo "review-covers: no high review recorded for $n (run /code-review high," \
  "then $OPS_DIR/mark-reviewed.sh $n high)"; exit 1; }
r=$(cut -d' ' -f1 "$s"); h=$(git -C "$w" rev-parse HEAD)
[ "$r" = "$h" ] && exit 0
git -C "$w" merge-base --is-ancestor "$r" "$h" \
  || { echo "review-covers: the reviewed ${r:0:11} is not an ancestor of HEAD ${h:0:11}"; exit 1; }
other=$(git -C "$w" log --first-parent --no-merges --format='%h %s' "$r..$h" \
  | grep -v ' Regenerate the generated files')
[ -z "$other" ] && exit 0
echo "review-covers: commits after the reviewed ${r:0:11} need a new review:"
echo "$other" | head -5; exit 1
