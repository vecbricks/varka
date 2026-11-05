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
# The pre-push hook (VARKA-492; dev/varka_precommit.sh --install-hook installs it). Git runs it
# with the remote's name and URL as arguments and one line per pushed ref on stdin.
#
#   * A branch pushed to vecbricks/varka itself is refused: pull request branches go to the
#     committer's fork, and a branch on vecbricks/varka starts its CI there besides. Master may
#     go there, which is how a merge lands, and so may a tag (a milestone's close).
#   * Every commit pushed is checked again: its message by dev/varka_msg_check.py and the lines
#     it writes by dev/varka_precommit.sh --range, since `git commit --no-verify`, a rebase or a
#     cherry-pick skips the commit-time hooks.
#
#   * A push of the branch checked out also runs dev/varka_push_check.py's rules that need no
#     JVM (VARKA-563), about 3 s on a typical branch and 25-54 s on the incident replays:
#     VARKA_PUSH_CHECK=fast, the default; full adds the suites the branch's changed files imply;
#     off runs nothing more.
#
#   * The line checks read the files from the working tree, so a push of a ref this checkout does
#     not hold is refused when the files its commits change differ here, with the fix named:
#     push from the worktree that has the branch checked out (VARKA-569).
#
# Only the branch's own commits are checked: never one Varka's master (origin/master) or Apache
# Spark's (upstream/master) already has, which a merge of either brings in and which follow their
# own rules; and the lines are the branch's change since its merge base with Varka's master.
set -uo pipefail
case "${1:-}" in -h|--help) sed -n '18,/^[^#]/p' "$0" | sed '$d'; exit 0 ;; esac
if [ "${1:-}" = "--selftest" ]; then
  # The wrong-checkout case, in a repository of its own, with git's hook environment stripped as
  # dev/varka_precommit.sh's self-test does: a branch whose commit adds a file is pushed from a
  # checkout of master, which lacks the file, and then from a checkout of the branch.
  self="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  bare() {
    env -u GIT_DIR -u GIT_WORK_TREE -u GIT_INDEX_FILE -u GIT_PREFIX -u GIT_COMMON_DIR \
      -u GIT_OBJECT_DIRECTORY -u GIT_ALTERNATE_OBJECT_DIRECTORIES "$@"
  }
  (
    cd "$tmp" || exit 1
    bare git init -q -b master .
    bare git config user.email t@example.com
    bare git config user.name t
    bare git config commit.gpgsign false
    mkdir dev
    printf '#!/bin/bash\necho ran >> "$(git rev-parse --show-toplevel)/precommit.ran"\n' \
      > dev/varka_precommit.sh
    chmod +x dev/varka_precommit.sh
    bare git add -A
    bare git commit -qm base
    bare git update-ref refs/remotes/origin/master HEAD
    bare git checkout -q -b feature
    printf 'x\n' > Pushed.scala
    printf 'x\n' > "$(printf 'caf\303\251.scala')"
    bare git add -A
    bare git commit -qm feature
    bare git checkout -q master
  ) > /dev/null 2>&1
  sha="$(bare git -C "$tmp" rev-parse feature)"
  fork=https://github.com/u/spark.git
  push() { # the refs line a push of the branch gives, as the hook reads it
    printf 'refs/heads/feature %s refs/heads/feature %s\n' "$sha" "$(printf '0%.0s' {1..40})" \
      | (cd "$tmp" && bare env VARKA_PUSH_CHECK=off "$self" origin "$fork") 2>&1
  }
  fails=0
  out="$(push)"; rc=$?
  if [ "$rc" -eq 0 ] || [[ "$out" != *"not what this checkout holds"* ]] \
      || [ -e "$tmp/precommit.ran" ]; then
    echo "varka_prepush selftest: the wrong checkout: expected a refusal, got $rc: $out"
    fails=$((fails + 1))
  fi
  bare git -C "$tmp" checkout -q feature
  out="$(push)"; rc=$?
  if [ "$rc" -ne 0 ] || [ ! -e "$tmp/precommit.ran" ]; then
    echo "varka_prepush selftest: the right checkout: expected the checks to run, got $rc: $out"
    fails=$((fails + 1))
  fi
  [ "$fails" -eq 0 ] && echo "varka_prepush selftest: ok"
  exit "$fails"
fi
remote="${1:-}"
url="${2:-}"
root="$(git rev-parse --show-toplevel)"
cd "$root" || exit 1

zero=0000000000000000000000000000000000000000
status=0
checked=0 # the push check failed, which VARKA_PUSH_CHECK can pass over without --no-verify
while read -r local_ref local_sha remote_ref remote_sha; do
  [ "$local_sha" = "$zero" ] && continue # a deletion pushes no commits
  if [[ "$url" =~ vecbricks/varka(\.git)?$ ]] && [[ "$remote_ref" == refs/heads/* ]] \
      && [ "$remote_ref" != "refs/heads/master" ]; then
    echo "varka pre-push: refusing to push $local_ref to $remote ($url), Varka's own repository:"
    echo "  push a pull request branch to your fork (CLAUDE.md, 'Pull Request Workflow')"
    status=1
    continue
  fi
  [[ "$local_ref" == refs/tags/* ]] && continue
  base="$(git merge-base "$local_sha" origin/master 2> /dev/null)" || base=""
  [ -n "$base" ] || continue
  range="$base..$local_sha"
  if [ "$(git rev-list --count "$range")" -eq 0 ]; then continue; fi
  # Commits the remote already has were checked when they were pushed.
  known=(origin/master upstream/master)
  if [ "$remote_sha" != "$zero" ] && git cat-file -e "$remote_sha" 2> /dev/null; then
    known+=("$remote_sha")
  fi
  if [ -x dev/varka_msg_check.py ]; then
    out="$(dev/varka_msg_check.py range "$range" --not "${known[@]}" 2>&1)" || {
      echo "$out" | sed 's/^/varka pre-push: /'
      status=1
    }
  fi
  # The files are read from the working tree, which is the pushed commit's in the usual push of
  # the branch checked out. A push of a ref this checkout does not hold would be checked against
  # the wrong files, and say that a file it adds is missing: it is refused where the files its
  # commits change differ here (VARKA-569).
  # NUL-separated and read in a loop, so that a name with non-ASCII bytes or a newline is not
  # quoted into a pathspec that matches nothing, and bash 3.2 has no mapfile to need.
  touched=()
  while IFS= read -r -d '' f; do
    touched+=("$f")
  done < <(git diff -z --name-only --diff-filter=ACMR "$range")
  if [ "${#touched[@]}" -gt 0 ] \
      && ! git diff --quiet "$local_sha" -- "${touched[@]}" 2> /dev/null; then
    echo "varka pre-push: $local_ref is not what this checkout holds: a file it changes differs"
    echo "  here, or has uncommitted edits (${touched[0]}...), so the line checks would read the"
    echo "  wrong files; push it from the worktree that has it checked out (git worktree list),"
    echo "  or with --no-verify"
    status=1
    continue
  fi
  out="$(dev/varka_precommit.sh --range "$range" 2>&1)" || {
    echo "$out" | sed 's/^/varka pre-push: /'
    status=1
  }
  case "${VARKA_PUSH_CHECK:-fast}" in
    fast|full)
      if [ "$local_sha" = "$(git rev-parse HEAD)" ]; then
        mode=(--no-precommit)
        [ "${VARKA_PUSH_CHECK:-fast}" = fast ] && mode+=(--no-jvm)
        # Its stdin is not the hook's: the refs still to read are there.
        dev/varka_push_check.py --base "$base" "${mode[@]}" < /dev/null || {
          status=1
          checked=1
        }
      else
        echo "varka pre-push: VARKA_PUSH_CHECK skipped for $local_ref, which is not checked out"
      fi ;;
  esac
done
if [ "$status" -ne 0 ]; then
  echo "varka pre-push: push refused; fix the findings above"
  if [ "$checked" -ne 0 ]; then
    echo "varka pre-push: to pass over the push check only, set VARKA_PUSH_CHECK_SKIP=<rule>"
    echo "  (the rule it names) or VARKA_PUSH_CHECK=off for the git push"
  fi
  echo "varka pre-push: git push --no-verify skips every check, the message and line checks too"
fi
exit "$status"
