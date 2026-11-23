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
# start-waiter.sh <pr> <build-run> <branch>: start matrix-after-build-link.sh for a PR in the
# background and record its pid in $VARKA_OPS_HOME/pids/matrix-<pr>.pid. An older waiter of the
# same PR is stopped by its RECORDED pid only (never by name). Waiter log:
# $VARKA_OPS_HOME/logs/link-matrix-<pr>.log
set -u
. "$(dirname "$0")/common.sh"
[ $# -ge 3 ] || { echo "usage: start-waiter.sh <pr> <build-run> <branch>"; exit 2; }
pr=$1; run=$2; br=$3; P=$OPS/pids/matrix-$pr.pid
if [ -f "$P" ]; then
  old=$(cat "$P")
  if [ -n "$old" ] && ps -p "$old" -o args= 2>/dev/null | grep -q "matrix-after-build-link.sh"
  then kill "$old"; fi
fi
nohup "$OPS_DIR/matrix-after-build-link.sh" "$run" "$br" "matrix-$pr" \
  > "$OPS/logs/link-matrix-$pr.log" 2>&1 &
echo $! > "$P"; echo "waiter for #$pr started, pid $(cat "$P")"
