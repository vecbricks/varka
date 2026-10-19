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
# Move the scope catalogue forward one milestone, and every reference to it with it.
#
#   dev/varka_catalogue_move.sh 8 9          # move m8's catalogue to m9 and rewrite citations
#   dev/varka_catalogue_move.sh --check 8 9  # fail if any tracked file still cites the old path
#
# sql/varka/AGENTS.md: when a milestone's catalogue becomes a task plan, the furthest-out
# catalogue moves forward with its item numbers unchanged, and a plan may be renamed only if every
# inbound reference is updated with it. The files that move are the catalogue (SCOPE.md), its
# reading notes (READING_*.md) and the plans of the rows the catalogue holds, which live beside it
# (AGENTS.md, "Plans are records"): MOVED_PLANS below, derived from the old milestone's task
# table. Plans of rows the new milestone takes stay where they are.
#
# The script is idempotent: a second run moves nothing and rewrites only citations that arrived
# since the first, such as those a merge of master brings in. Run it again after every such merge,
# then run it with --check, which fails while any tracked file still names an old path.

set -euo pipefail

check=0
if [ "${1:-}" = "--check" ]; then
  check=1
  shift
fi
if [ $# -ne 2 ]; then
  echo "usage: $0 [--check] FROM TO   (milestone numbers, e.g. 8 9)" >&2
  exit 2
fi
from="$1"
to="$2"

cd "$(git rev-parse --show-toplevel)"
plans=sql/varka/plans

if [ ! -f "$plans/m$from/PLAN.md" ]; then
  echo "$plans/m$from/PLAN.md does not exist: a catalogue moves when its milestone has a plan" >&2
  exit 1
fi

# The plans of rows the catalogue holds move with it: a VARKA-<n>.md in the old folder whose row
# is not in that milestone's task table. After a first run they are in the new folder, where no
# task table holds them either, and are still named so their citations keep being rewritten.
holds_row() { # plan file, task file name
  local n="${2#VARKA-}"
  n="${n%.md}"
  [ -f "$1" ] && grep -qE "^\| *$n *\|" "$1"
}
MOVED_PLANS=()
for f in "$plans/m$from"/VARKA-*.md "$plans/m$to"/VARKA-*.md; do
  [ -e "$f" ] || continue
  name="$(basename "$f")"
  dir="$(dirname "$f")"
  if ! holds_row "$dir/PLAN.md" "$name"; then
    MOVED_PLANS+=("$name")
  fi
done

files_to_move=(SCOPE.md)
# The old folder's reading notes are the catalogue's only until it moves: once SCOPE.md has left,
# a READING_*.md there belongs to the plan that took the folder (m8's READING_SLEEF.md, written
# for its math rows), and neither moves nor counts as a stale citation.
# Before the move every note there is taken for the catalogue's, so a plan that writes a
# reading note of its own does so after its milestone's catalogue has moved on.
if [ -e "$plans/m$from/SCOPE.md" ]; then
  for f in "$plans/m$from"/READING_*.md; do
    [ -e "$f" ] && files_to_move+=("$(basename "$f")")
  done
fi
# After a first run the reading notes are already in the new folder; keep naming them.
for f in "$plans/m$to"/READING_*.md; do
  [ -e "$f" ] && files_to_move+=("$(basename "$f")")
done
files_to_move+=(${MOVED_PLANS[@]+"${MOVED_PLANS[@]}"})

# Every path that changes, as a literal "m<from>/<name>" to "m<to>/<name>" rewrite.
patterns=()
for name in $(printf '%s\n' "${files_to_move[@]}" | sort -u); do
  patterns+=("m$from/$name")
done

# Tracked text files that cite an old path, this script excluded (it names them on purpose).
cited() {
  local p
  for p in "${patterns[@]}"; do
    git grep -lIF -e "$p" -- . ':!dev/varka_catalogue_move.sh' || true
  done | sort -u
}

if [ "$check" -eq 1 ]; then
  left="$(cited)"
  if [ -n "$left" ]; then
    echo "these files still cite milestone $from's catalogue paths:" >&2
    echo "$left" >&2
    exit 1
  fi
  for name in "${files_to_move[@]}"; do
    if [ -e "$plans/m$from/$name" ]; then
      echo "$plans/m$from/$name has not moved" >&2
      exit 1
    fi
  done
  echo "milestone $from's catalogue is at m$to, and nothing cites the old paths"
  exit 0
fi

mkdir -p "$plans/m$to"
for name in "${files_to_move[@]}"; do
  if [ -e "$plans/m$from/$name" ]; then
    if [ -e "$plans/m$to/$name" ]; then
      echo "both $plans/m$from/$name and $plans/m$to/$name exist; resolve by hand" >&2
      exit 1
    fi
    git mv "$plans/m$from/$name" "$plans/m$to/$name"
    echo "moved $plans/m$from/$name to $plans/m$to/$name"
  fi
done

count=0
while IFS= read -r f; do
  [ -n "$f" ] || continue
  for name in "${files_to_move[@]}"; do
    # A literal rewrite: the dot is escaped, and the name holds no other pattern character.
    old="m$from/${name//./\\.}"
    sed -i "s#$old#m$to/$name#g" "$f"
  done
  count=$((count + 1))
done < <(cited)
echo "rewrote citations in $count files"
