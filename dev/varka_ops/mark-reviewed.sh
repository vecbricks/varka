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
# mark-reviewed.sh <worktree> <level> [findings-file]: record that /code-review <level> ran on the
# worktree's HEAD and its findings were triaged. Writes $VARKA_OPS_HOME/stamps/review-<dir> as
# "<sha> <level> <date> <findings-file>". Run it after the review AND its one triaged fix round,
# on the head that round produced. A matrix should start only after the review, so open-pr.sh,
# push-ci.sh and push-no-ci.sh refuse a head that no high review covers (review-covers.sh).
set -u
. "$(dirname "$0")/common.sh"
[ $# -ge 2 ] || { echo "usage: mark-reviewed.sh <worktree> <level> [findings-file]"; exit 2; }
w=$(ops_worktree "$1"); lvl=$2; f=${3:-}
case $lvl in
  high|xhigh|max) ;;
  *) echo "mark-reviewed: level must be high, xhigh or max"; exit 2;;
esac
n=$(basename "$w"); sha=$(git -C "$w" rev-parse HEAD) || exit 2
echo "$sha $lvl $(date +%F_%H:%M) $f" > "$OPS/stamps/review-$n"
echo "mark-reviewed: $n at ${sha:0:11} ($lvl)"
