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
# watch-infra.sh: every 5 minutes, look at the latest fork Build (and option matrix) on each open
# VARKA_REPO PR's head. When it failed on its first attempt and EVERY failed job is a known runner
# or network failure, rerun only the failed jobs once, print what it did ("INFRA ..."), and exit
# (so the session is told; restart it after). A run with any other failure is left alone and
# printed ("REAL ..."), also exiting, so it gets looked at. The owner approved the reruns on
# 22 Nov 2026. A run is handled once: its id goes to $VARKA_OPS_HOME/watch-infra.seen.
# Known infrastructure failures:
#   - "The hosted runner lost communication with the server" (job annotation)
#   - a Docker image pull that failed: "failed to resolve reference", "toomanyrequests",
#     "pull access denied" (job log)
#   - a rate-limited download: Maven Central "HTTP response code: 429", "could not retrieve sbt"
#     (job log)
set -u
. "$(dirname "$0")/common.sh"
ops_fork
seen=$OPS/watch-infra.seen; touch "$seen"
infra_job() {  # <job id> -> exit 0 if the job failed for a known infrastructure reason
  gh api "repos/$FORK/check-runs/$1/annotations" --jq '.[].message' 2>/dev/null \
    | grep -q "lost communication with the server" && return 0
  gh api "repos/$FORK/actions/jobs/$1/logs" 2>/dev/null \
    | grep -qE 'failed to resolve reference|toomanyrequests|pull access denied|HTTP response code: 429|could not retrieve sbt' \
    && return 0
  return 1
}
while :; do
  for row in $(gh pr list --repo "$REPO" --state open --json number,headRefName,headRefOid \
      -q '.[]|"\(.number)|\(.headRefName)|\(.headRefOid)"'); do
    pr=${row%%|*}; rest=${row#*|}; b=${rest%%|*}; sha=${rest#*|}
    for run in $(gh run list --repo "$FORK" --branch "$b" --limit 8 \
        --json databaseId,name,headSha,conclusion,attempt \
        -q ".[]|select(.headSha==\"$sha\" and .conclusion==\"failure\" and .attempt==1 and (.name==\"Build\" or .name==\"Varka option matrix\"))|.databaseId"); do
      grep -qx "$run" "$seen" && continue
      echo "$run" >> "$seen"
      jobs=$(gh run view "$run" --repo "$FORK" --json jobs \
        -q '.jobs[]|select(.conclusion=="failure")|"\(.databaseId)|\(.name)"')
      all=1; names=""
      while IFS='|' read -r jid jn; do
        [ -n "$jid" ] || continue
        case "$jn" in *report*) continue;; esac   # a matrix report fails because a bin failed
        infra_job "$jid" || all=0; names="$names; $jn"
      done <<< "$jobs"
      if [ $all = 1 ]; then
        if gh run rerun "$run" --repo "$FORK" --failed >/dev/null 2>&1; then
          echo "INFRA #$pr run $run: reran failed jobs:${names}"
        else
          echo "INFRA #$pr run $run: rerun refused:${names}"
        fi
      else
        echo "REAL #$pr run $run failed:${names}"
      fi
      exit 0
    done
  done
  sleep 300
done
