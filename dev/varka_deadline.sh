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
#
# Run a command under a deadline, and when the deadline passes stop it and everything it
# started: the command runs in a session of its own, and the whole process group gets SIGTERM
# at the deadline and SIGKILL a minute later.
#
#   dev/varka_deadline.sh 3600 build/sbt -batch 'catalyst/testOnly *Varka*'
#
# Exits with the command's status, or 124 when the deadline stopped it (the status timeout(1)
# uses). It exists because sbt waits forever on a forked test JVM that VarkaTestWatchdog has
# halted - the halt is meant to fail the suite, and instead the step hung (VARKA-283) - and a
# plain timeout(1) would signal sbt alone and leave its forked JVMs running.
set -uo pipefail
case "${1:-}" in ''|-h|--help) sed -n '19,30p' "$0"; exit 2 ;; esac
secs="$1"; shift
[[ "$secs" =~ ^[0-9]+$ ]] || { echo "the deadline is a number of seconds, got '$secs'" >&2; exit 2; }
[ "$#" -gt 0 ] || { echo "no command to run" >&2; exit 2; }

setsid "$@" &
pid=$!
expired="$(mktemp)"
rm -f "$expired"
(
  # Its sleeps are waited on, not run in the foreground, so that the TERM sent when the
  # command finishes in time ends them too instead of leaving them to run out the deadline.
  trap 'kill "$nap" 2>/dev/null; exit 0' TERM
  sleep "$secs" & nap=$!; wait "$nap"
  touch "$expired"
  echo "varka_deadline: $* ran past ${secs}s; stopping its process group" >&2
  kill -TERM -- "-$pid" 2>/dev/null
  sleep 60 & nap=$!; wait "$nap"
  kill -KILL -- "-$pid" 2>/dev/null
) &
watcher=$!
wait "$pid"
status=$?
if [ -e "$expired" ]; then
  # The leader is gone, but what it started may still be shutting down, or may have ignored
  # the TERM: give the group the watcher's minute, then kill whatever is left.
  for _ in $(seq 60); do
    kill -0 -- "-$pid" 2>/dev/null || break
    sleep 1
  done
  kill -KILL -- "-$pid" 2>/dev/null
  kill "$watcher" 2>/dev/null
  wait "$watcher" 2>/dev/null
  rm -f "$expired"
  exit 124
fi
kill "$watcher" 2>/dev/null
wait "$watcher" 2>/dev/null
exit "$status"
