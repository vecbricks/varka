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
# watch-any-ready.sh: every 3 minutes, look at each open VARKA_REPO PR (except those listed in
# SKIP, space-separated numbers); exit printing "READY #<pr> head <sha>" when one is CLEAN to merge
# and has a successful Build and option matrix on its head.
set -u
. "$(dirname "$0")/common.sh"
ops_fork
SKIP=" ${SKIP:-} "
while :; do
  for pr in $(gh pr list --repo "$REPO" --state open --json number -q '.[].number'); do
    case "$SKIP" in *" $pr "*) continue;; esac
    read -r sha b m <<<"$(gh pr view "$pr" --repo "$REPO" \
      --json headRefOid,headRefName,mergeStateStatus \
      -q '.headRefOid+" "+.headRefName+" "+.mergeStateStatus')"
    [ "$m" = CLEAN ] || continue
    ok=$(gh run list --repo "$FORK" --branch "$b" --limit 12 --json name,headSha,conclusion \
      -q "[.[]|select(.headSha==\"$sha\" and .conclusion==\"success\")|.name]|(index(\"Build\")!=null) and (index(\"Varka option matrix\")!=null)")
    [ "$ok" = true ] && { echo "READY #$pr head ${sha:0:11}"; exit; }
  done
  sleep 180
done
