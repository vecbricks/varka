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
# pre-pr-slot.sh <worktree> [pre-pr.sh args]: run pre-pr.sh as soon as a slot is free. A slot is
# free while fewer than PRE_PR_SLOTS pre-pr.sh runs are going, no exclusive gate is waiting or
# running, and at least PRE_PR_MIN_GB of memory is available; the 20 Nov OOM came at about 14
# JVMs, and one run's arm sweep at -j 2 holds about 4. Each waiter wakes at a random offset so two
# do not take the last slot at once.
# 23 Nov 2026: every regen runs the width audit, whose 600 s probes time out beside other gates
# (348, 504, 358a, the skips PR). So gates run one at a time (PRE_PR_SLOTS=1) and the parallelism
# moves inside a gate: its arm sweep runs at -j PRE_PR_J (5, for 24 cores).
set -u
. "$(dirname "$0")/common.sh"
SLOTS=${PRE_PR_SLOTS:-1}; MIN_GB=${PRE_PR_MIN_GB:-20}; J=${PRE_PR_J:-5}
[ $# -ge 1 ] || { echo "usage: pre-pr-slot.sh <worktree> [pre-pr.sh args]"; exit 2; }
w=$(ops_worktree "$1"); shift; n=$(basename "$w")
# one gate per worktree: a second waiter for the same worktree exits at once (22 Nov: 617 was
# queued twice)
exec 9>"$OPS/locks/pre-pr-$n.lock"
flock -n 9 || { echo "pre-pr-slot: a gate for $n is already queued or running"; exit 3; }
# PRE_PR_EXCLUSIVE=1: run alone (a regen with the width audit times out beside other gates,
# 22 Nov): raise a marker at once so no other waiter starts, wait for zero runs, and drop the
# marker on exit.
X=$OPS/locks/pre-pr-exclusive
if [ "${PRE_PR_EXCLUSIVE:-0}" = 1 ]; then
  echo "$n" > "$X"; trap 'rm -f "$X"' EXIT
  while [ "$(ops_running pre-pr.sh)" -gt 0 ]; do sleep 15; done
  "$OPS_DIR/pre-pr.sh" "$w" -j 4 "$@"; exit $?
fi
sleep $((RANDOM % 20))
while :; do
  running=$(ops_running pre-pr.sh)
  avail=$(free -g | awk '/^Mem:/{print $7}')
  [ ! -e "$X" ] && [ "$running" -lt "$SLOTS" ] && [ "$avail" -ge "$MIN_GB" ] && break
  sleep $((20 + RANDOM % 20))
done
exec "$OPS_DIR/pre-pr.sh" "$w" -j "$J" "$@"
