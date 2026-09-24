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
# Give a fork's CI to one pull request at a time, in merge order.
#
#   dev/varka_ci_queue.sh status          # each open PR: its Build run, and whether it is current
#   dev/varka_ci_queue.sh hold 341 342    # cancel their Build runs and queue them, in this order
#   dev/varka_ci_queue.sh run             # rerun the queue, one run at a time, until it is empty
#   dev/varka_ci_queue.sh drop 339        # take a PR off the queue and cancel its running Build
#   dev/varka_ci_queue.sh list            # the queue as it stands
#
# A fork's GitHub Actions runs at most twenty jobs at once and one Spark Build is
# about thirty-five, so two Builds in flight share the slots and neither finishes
# until both do. The rule is one Build at a time, in the order the PRs will merge,
# and this script keeps it: `hold` right after a push cancels the Build the push
# started and appends the PR to the queue, and `run` works through the queue,
# waiting until no Build is running anywhere on the fork before it reruns the
# next one, then waiting for that one to complete and printing its verdict and
# the failed steps of any failed job. A PR that merged or closed meanwhile is
# skipped, and a PR pushed again meanwhile has its newest run used. After a merge,
# `drop` the merged PR: its run is worth nothing and still holds slots.
#
# The queue is a file in the clone's git directory, shared by every worktree of
# the clone. Every wait keys on a run's completed status, never on `gh run watch`
# (which returns at once without a terminal), and has a deadline
# (VARKA_CI_WAIT_MINUTES, default 240). The script prints `EXIT <status>` on every
# path, so another script can wait on that line. Needs `gh` authenticated; the
# base repository is read from the `origin` remote (VARKA_BASE_REMOTE overrides),
# and each PR's fork and branch from GitHub.
set -euo pipefail

# Usage text is found rather than numbered: a hard-coded range silently truncates as the
# comment above it grows. Ends at the first line that is not a comment.
usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }
case "${1:-}" in -h|--help) usage 0 ;; "") usage ;; esac

trap 'echo "EXIT $?"' EXIT

remote="${VARKA_BASE_REMOTE:-origin}"
repo="$(git remote get-url "$remote" | sed -E 's#\.git$##; s#.*[:/]([^/]+/[^/]+)$#\1#')"
workflow="${VARKA_CI_WORKFLOW:-Build}"
poll="${VARKA_CI_POLL:-60}"
deadline_minutes="${VARKA_CI_WAIT_MINUTES:-240}"
state="$(git rev-parse --git-common-dir)/varka-ci-queue"
touch "$state"

now() { date '+%H:%M %Z'; }

# ---- The queue: one "<pr> <run id>" line per held PR, in order. Every change goes through
# a lock, because `hold` is typically run while a `run` is working through the file.

locked() { ( flock 9; "$@" ) 9>"$state.lock"; }
queue_put() { # replaces the PR's line in place if it has one, so it keeps its turn; else appends
  local tmp; tmp="$(mktemp)"
  awk -v pr="$1" -v run="$2" '
    $1 == pr { print pr " " run; found = 1; next }
    { print }
    END { if (!found) print pr " " run }' "$state" > "$tmp"
  mv "$tmp" "$state"
}
queue_del() {
  local tmp; tmp="$(mktemp)"
  awk -v pr="$1" '$1 != pr' "$state" > "$tmp"
  mv "$tmp" "$state"
}
queue_del_if() { # removes the PR's line only if it still names this run, so a re-hold survives
  local tmp; tmp="$(mktemp)"
  awk -v pr="$1" -v run="$2" '!($1 == pr && $2 == run)' "$state" > "$tmp"
  mv "$tmp" "$state"
}
queued_run() { awk -v pr="$1" '$1 == pr { print $2 }' "$state"; }

# ---- GitHub

# "<fork> <branch> <head sha> <state>" for a PR of the base repository; state is open,
# closed or merged. The sha is the fork branch's, not the PR's: GitHub updates a PR's head a
# few seconds after the push, and in that window the PR still names the old head, whose old
# run would pass for current.
pr_info() {
  local fork branch st
  read -r fork branch st <<<"$(gh api "repos/$repo/pulls/$1" --jq \
    '[.head.repo.full_name, .head.ref, (if .merged_at then "merged" else .state end)]
     | join(" ")')"
  echo "$fork $branch $(gh api "repos/$fork/branches/$branch" --jq '.commit.sha' \
    2>/dev/null || echo -) $st"
}

# "<id> <status> <conclusion> <head sha>" of the newest run of the workflow on a branch of a
# fork, or nothing when there is none.
newest_run() {
  gh run list --repo "$1" --branch "$2" --workflow "$workflow" --limit 1 \
    --json databaseId,status,conclusion,headSha \
    --jq '.[] | [.databaseId, .status, (if .conclusion == "" then "-" else .conclusion end),
                 .headSha] | map(tostring) | join(" ")'
}

run_state() { # "<status> <conclusion>" of one run
  gh api "repos/$1/actions/runs/$2" --jq '"\(.status) \(.conclusion // "-")"'
}

# Waits until the run is completed; prints its conclusion. Keyed on the run's own status, so
# a cancellation or a timeout ends the wait as surely as a pass. Fails at the deadline.
wait_completed() {
  local fork="$1" id="$2" end=$((SECONDS + deadline_minutes * 60)) s
  while [ "$SECONDS" -lt "$end" ]; do
    s="$(run_state "$fork" "$id" 2>/dev/null || echo "unknown -")"
    if [ "${s%% *}" = "completed" ]; then
      echo "${s#* }"
      return 0
    fi
    sleep "$poll"
  done
  echo "timeout"
  return 1
}

# Waits until no run of the workflow is queued or in progress anywhere on the fork. A run
# the queue does not know about - a push nobody held - still holds the slots.
wait_fork_idle() {
  local fork="$1" end=$((SECONDS + deadline_minutes * 60)) busy said=""
  while [ "$SECONDS" -lt "$end" ]; do
    busy="$(gh run list --repo "$fork" --workflow "$workflow" --limit 20 \
      --json databaseId,status,headBranch \
      --jq '.[] | select(.status != "completed") | "\(.databaseId) on \(.headBranch)"' \
      2>/dev/null | head -1 || true)"
    if [ -z "$busy" ]; then
      return 0
    fi
    if [ "$busy" != "$said" ]; then
      echo "  $(now): waiting for run $busy"
      said="$busy"
    fi
    sleep "$poll"
  done
  echo "  gave up waiting for the fork to go idle after $deadline_minutes minutes"
  return 1
}

failed_steps() {
  gh api "repos/$1/actions/runs/$2/jobs?per_page=100" \
    --jq '.jobs[] | select(.conclusion == "failure")
          | "  failed: \(.name) -> \([.steps[] | select(.conclusion == "failure") | .name]
          | join(", "))"'
}

# ---- Commands

cmd_hold() {
  [ "$#" -gt 0 ] || usage
  local pr fork branch sha st run id rstatus rconcl rsha end
  for pr in "$@"; do
    read -r fork branch sha st <<<"$(pr_info "$pr")"
    if [ "$st" != "open" ]; then
      echo "#$pr is $st: not queued"
      continue
    fi
    # The push that prompted this may not have started its run yet.
    end=$((SECONDS + ${VARKA_CI_APPEAR_SECONDS:-180}))
    while :; do
      run="$(newest_run "$fork" "$branch")"
      read -r id rstatus rconcl rsha <<<"${run:-- - - -}"
      if [ "$rsha" = "$sha" ] || [ "$SECONDS" -ge "$end" ]; then
        break
      fi
      sleep 10
    done
    if [ "$rsha" != "$sha" ]; then
      echo "#$pr: no $workflow run for its head ${sha:0:11} yet; queued, and run will find it"
      locked queue_put "$pr" "-"
      continue
    fi
    if [ "$rstatus" = "completed" ] && [ "$rconcl" = "success" ]; then
      echo "#$pr: run $id already passed at ${sha:0:11}; nothing to hold"
      continue
    fi
    if [ "$rstatus" != "completed" ]; then
      gh run cancel "$id" --repo "$fork" >/dev/null
      echo "#$pr: cancelled run $id and queued it"
    else
      echo "#$pr: run $id is $rconcl; queued for a rerun"
    fi
    locked queue_put "$pr" "$id"
  done
  cmd_list
}

cmd_run() {
  # One runner per clone: a second would start a run beside the first one's.
  exec 8>"$state.run"
  if ! flock -n 8; then
    echo "another dev/varka_ci_queue.sh run holds this queue"
    return 1
  fi
  local pr qid fork branch sha st run id rstatus rconcl rsha verdict failures=0
  while read -r pr qid < <(head -1 "$state") && [ -n "${pr:-}" ]; do
    read -r fork branch sha st <<<"$(pr_info "$pr")"
    if [ "$st" != "open" ]; then
      echo "#$pr is $st: skipped"
      locked queue_del "$pr"
      continue
    fi
    run="$(newest_run "$fork" "$branch")"
    read -r id rstatus rconcl rsha <<<"${run:-- - - -}"
    if [ "$id" = "-" ] || [ "$rsha" != "$sha" ]; then
      echo "#$pr: no $workflow run at its head ${sha:0:11}; push it again, then hold it again"
      locked queue_del "$pr"
      continue
    fi
    if [ "$rstatus" = "completed" ] && [ "$rconcl" = "success" ]; then
      echo "#$pr: run $id already passed; ready to merge"
      locked queue_del "$pr"
      continue
    fi
    if [ "$rstatus" = "completed" ]; then
      wait_fork_idle "$fork" || return 1
      # The wait can be long, and the queue may have changed under it: a PR dropped or held
      # again meanwhile is not this iteration's to rerun.
      if [ "$(queued_run "$pr")" != "$qid" ]; then
        echo "#$pr: changed on the queue while waiting; reading the queue again"
        continue
      fi
      gh run rerun "$id" --repo "$fork" >/dev/null
      echo "#$pr: $(now): reran run $id"
      # A rerun takes a few seconds to leave the completed state; without this the wait
      # below would read the old conclusion and return at once.
      sleep 30
    else
      echo "#$pr: run $id is already $rstatus; waiting on it"
    fi
    verdict="$(wait_completed "$fork" "$id")" || { echo "#$pr: run $id: $verdict"; return 1; }
    echo "#$pr: $(now): run $id finished: $verdict"
    if [ "$(queued_run "$pr")" != "$qid" ]; then
      # Held again or dropped while its run went: that run's verdict is not the PR's.
      echo "#$pr: changed on the queue while its run went; the verdict above is for an old head"
      continue
    fi
    if [ "$verdict" = "success" ]; then
      echo "#$pr: ready to merge"
    else
      failures=$((failures + 1))
      failed_steps "$fork" "$id" || true
    fi
    locked queue_del_if "$pr" "$qid"
  done
  echo "queue empty"
  return "$failures"
}

cmd_drop() {
  [ "$#" -gt 0 ] || usage
  local pr fork branch sha st id
  for pr in "$@"; do
    locked queue_del "$pr"
    read -r fork branch sha st <<<"$(pr_info "$pr")"
    for id in $(gh run list --repo "$fork" --branch "$branch" --workflow "$workflow" \
        --limit 10 --json databaseId,status \
        --jq '.[] | select(.status != "completed") | .databaseId'); do
      gh run cancel "$id" --repo "$fork" >/dev/null
      echo "#$pr: cancelled run $id"
    done
    echo "#$pr: off the queue"
  done
}

cmd_list() {
  if [ ! -s "$state" ]; then
    echo "queue: empty"
    return 0
  fi
  echo "queue, in order:"
  awk '{ print "  #" $1 "  run " $2 }' "$state"
}

cmd_status() {
  local pr fork branch sha st run id rstatus rconcl rsha where note pos
  printf '%-6s %-34s %-12s %-24s %s\n' "PR" "branch" "run" "state" "note"
  for pr in $(gh pr list --repo "$repo" --state open --json number --jq '.[].number' | sort -n); do
    read -r fork branch sha st <<<"$(pr_info "$pr")"
    run="$(newest_run "$fork" "$branch")"
    read -r id rstatus rconcl rsha <<<"${run:-- - - -}"
    where="$rstatus"
    [ "$rstatus" = "completed" ] && where="$rconcl"
    note=""
    if [ "$id" = "-" ]; then
      note="no run"
    elif [ "$rsha" != "$sha" ]; then
      note="run is for ${rsha:0:11}, the PR is at ${sha:0:11}"
    elif [ "$where" = "success" ]; then
      note="ready to merge"
    fi
    pos="$(awk -v pr="$pr" '$1 == pr { print NR }' "$state")"
    [ -n "$pos" ] && note="${note:+$note; }queued at $pos"
    printf '%-6s %-34s %-12s %-24s %s\n' "#$pr" "$branch" "$id" "$where" "$note"
  done
}

main() {
  local command="$1"
  shift
  case "$command" in
    status) cmd_status ;;
    hold) cmd_hold "$@" ;;
    run) cmd_run ;;
    drop) cmd_drop "$@" ;;
    list) cmd_list ;;
    *) usage ;;
  esac
}

# On one line, with the exit, so that bash has read the whole file before `run` starts its
# hours of waiting: bash reads a script as it executes it, and an edit to the file under a
# running `run` would otherwise be read as whatever bytes now sit past the old offset.
main "$@"; exit
