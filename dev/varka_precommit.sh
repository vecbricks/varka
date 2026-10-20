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
# The house rules that slip most often, checked in a second over the files about
# to be committed, so they are caught here rather than by CI or a reviewer.
#
# A line-anchored finding is raised only for a line the commit itself writes: a
# file carries other people's lines - an upstream merge, prose written before a
# rule existed - and reporting those asks for an edit the committer did not come
# to make, whose only escape is --no-verify, which switches off every other check
# too. Naming files on the command line turns the scoping off, since that is a
# request to see everything in them. The whole-file checks below are unscoped.
#
#   * no non-ASCII byte in Scala, Java, Python or Markdown outside a string
#     literal (CLAUDE.md: typographic quotes, dashes and ellipses creep into
#     comments; benchmark results files are exempt, JMH writes its own +-, and
#     so are the third-party transcriptions under sql/varka/papers, whose Greek
#     letters and symbols are the papers' own text);
#   * no source line over 100 columns in Scala, Java or Python, imports, package
#     lines and URLs excepted (the linters enforce this; this is the cheap hint).
#     A Python line of one whitespace-separated chunk is exempt as well, because
#     nothing can wrap it and neither Python linter asks: ruff's E501 skips such a
#     line, and Spark's config does not select E501 at all, so `ruff format` is the
#     only Python authority on width and it cannot split a token either;
#   * no merge-conflict marker (a line starting `<<<<<<< ` or `>>>>>>> `) in any text file,
#     on any line, the commit's or not: a marker is never meant, and one a merge carried in
#     from master spreads to every branch that merges master next;
#   * no TODO or FIXME marker under sql/varka or in a Varka source directory
#     (sql/varka/AGENTS.md: open work is recorded in a plan, never left as a
#     marker);
#   * no comment that narrates history - `used to` or `the old`, case-insensitive, in the
#     comment part of a line the commit adds to a Varka source file (Scala, Java, Python or
#     shell under a Varka directory or named Varka*), since a comment describes the code as it
#     is and history lives in the plans and git (sql/varka/AGENTS.md). Two phrases only: in a
#     sample of Varka's comments nearly every hit of them was history, where "no longer" and
#     "previously" describe behaviour as often. The instrumental "is used to select" passes. A
#     present-tense "the old" (the old year of an ISO week) is reported, and is worded another
#     way ("the earlier year"). Markdown is out, since plans narrate on purpose and name the
#     phrases; so is dev/, as for the TODO check, which also keeps this script's own pattern
#     from tripping it;
#   * no raw `.reinterpret(` in the main Scala or Java of a Varka class: an address is mapped
#     through VarkaSegments.map (catalyst) or VarkaVectorSupport.ofAddress (the engine), so that
#     the memory sanitizer (VARKA-263) sees every mapping;
#   * SKILLS.md's generated index still matches the lesson files under
#     sql/varka/skills/ (dev/varka_toc.py), when either changed;
#   * the calibration table's entry files under sql/varka/skills/calibration/ are well formed
#     (dev/varka_calibration.py --check), when one of them or the tool changed;
#   * every number the documents quote traces to a committed results file
#     (dev/varka_quote_check.py), when a document changed;
#   * Python files pass `ruff check` and `ruff format --check`, the two halves of
#     CI's Python linter (dev/lint-python runs both; a hand-wrapped script that
#     passes the first still fails the second). A missing ruff is itself a
#     finding, because dev/lint-python skips ruff silently when it is not on PATH
#     and CI does not.
#
#   dev/varka_precommit.sh                 # the staged files
#   dev/varka_precommit.sh --working-tree  # staged, unstaged and untracked
#   dev/varka_precommit.sh FILE...         # these files
#   dev/varka_precommit.sh --install-hook  # run it from .git/hooks/pre-commit
#   dev/varka_precommit.sh --selftest      # check the column and history rules' cases
#
# Exit status is the number of findings.
set -uo pipefail
# Usage text is found rather than numbered: a hard-coded range silently truncates as the
# comment above it grows. Ends at the first line that is not a comment.
usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }
case "${1:-}" in -h|--help) usage 0 ;; esac

root="$(git rev-parse --show-toplevel)"; cd "$root"

if [ "${1:-}" = "--selftest" ]; then
  # The column rule is the one check here whose answer depends on the file's language, so
  # the three cases that distinguish it are pinned rather than reasoned about.
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  # Every command inside the throwaway repository runs with git's own environment stripped.
  # A pre-commit hook is invoked by `git commit` with GIT_DIR and GIT_INDEX_FILE exported, and
  # a nested `git init` / `git add` / `git commit` inherits them - so without this the fixture
  # commits itself into the repository being committed to, moving its HEAD and taking the
  # staged changes with it. `git rev-parse --show-toplevel`, which this script runs at start,
  # answers with the outer repository for the same reason.
  bare() {
    env -u GIT_DIR -u GIT_WORK_TREE -u GIT_INDEX_FILE -u GIT_PREFIX -u GIT_COMMON_DIR \
      -u GIT_OBJECT_DIRECTORY -u GIT_ALTERNATE_OBJECT_DIRECTORIES \
      -u GIT_AUTHOR_DATE -u GIT_COMMITTER_DATE "$@"
  }
  # The outer repository must be exactly as it was when this returns - see `bare` above for
  # what happens when it is not.
  outer_head="$(bare git -C "$root" rev-parse HEAD 2>/dev/null || echo none)"
  printf 'x = ["%s", "%s"]\n' "$(printf 'a%.0s' {1..60})" "$(printf 'b%.0s' {1..60})" \
    > "$tmp/wrappable.py"
  printf '    "%s",\n' "$(printf 'c%.0s' {1..105})" > "$tmp/single.py"
  printf '    "%s",\n' "$(printf 'd%.0s' {1..105})" > "$tmp/Single.scala"
  fails=0
  check_case() { # name file expected(yes|no)
    local got=no out
    # Captured, not piped into grep: this script exits with its finding count and `pipefail`
    # would hand that non-zero status to the pipeline even when grep matched, so every case
    # would read "no finding" however the rule behaved.
    out="$("$0" "$2" 2>&1)"
    case "$out" in *"line over 100 columns"*) got=yes ;; esac
    if [ "$got" != "$3" ]; then
      echo "varka_precommit selftest: $1: expected $3, got $got"
      fails=$((fails + 1))
    fi
  }
  # A Python line with a wrap point is reported; one whitespace-separated chunk is not,
  # because ruff's E501 exempts it and nothing could act on the report; Scala keeps no such
  # exemption, because scalastyle grants none.
  check_case "a wrappable Python line" "$tmp/wrappable.py" yes
  check_case "a single-chunk Python line" "$tmp/single.py" no
  check_case "a single-chunk Scala line" "$tmp/Single.scala" yes

  # The scoping, in a repository of its own: a violation already committed is not this
  # commit's to answer for, and one the commit adds is. Both directions, because a scope that
  # never reports and a scope that always reports are equally wrong and equally quiet.
  self="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
  repo="$tmp/repo"
  mkdir -p "$repo"
  (
    cd "$repo" || exit 1
    bare git init -q .
    bare git config user.email t@example.com
    bare git config user.name t
    printf 'val a = "%s"\n' "$(printf 'e%.0s' {1..110})" > Old.scala
    bare git add Old.scala
    bare git -c core.hooksPath=/dev/null commit -qm old
  ) > /dev/null 2>&1
  scope_case() { # name expected(yes|no) [finding text]
    local out got=no
    out="$(cd "$repo" && bare "$self" --working-tree 2>&1)"
    case "$out" in *"${3:-line over 100 columns}"*) got=yes ;; esac
    if [ "$got" != "$2" ]; then
      echo "varka_precommit selftest: $1: expected $2, got $got"
      fails=$((fails + 1))
    fi
  }
  printf 'val b = 1\n' >> "$repo/Old.scala"
  scope_case "a committed long line, under an unrelated edit" no
  printf 'val c = "%s"\n' "$(printf 'f%.0s' {1..110})" >> "$repo/Old.scala"
  scope_case "a long line this commit adds" yes

  # The history rule, on a Varka file of the fixture: a narrating comment already committed is
  # not this commit's, one it adds is, and the instrumental "is used to" is not history at all.
  (
    cd "$repo" || exit 1
    bare git checkout -q -- Old.scala
    printf '// The kernel used to copy the batch.\nclass VarkaOld {}\n' > VarkaOld.java
    bare git add VarkaOld.java
    bare git -c core.hooksPath=/dev/null commit -qm history
  ) > /dev/null 2>&1
  printf '// An unrelated line.\n' >> "$repo/VarkaOld.java"
  scope_case "a committed narrating comment, under an unrelated edit" no "narrates history"
  printf '// The mask is also used to select lanes.\nString s = "it used to be";\n' \
    >> "$repo/VarkaOld.java"
  scope_case "an added 'is also used to', and 'used to' in a string" no "narrates history"
  printf '// This replaces the old guard.\n' >> "$repo/VarkaOld.java"
  scope_case "an added 'the old'" yes "narrates history"
  bare git -C "$repo" checkout -q -- VarkaOld.java
  printf 'int x = 1; // The kernel used to copy.\n' >> "$repo/VarkaOld.java"
  scope_case "an added trailing comment with 'used to'" yes "narrates history"

  if [ "$outer_head" != "$(bare git -C "$root" rev-parse HEAD 2>/dev/null || echo none)" ]; then
    echo "varka_precommit selftest: the fixture moved this repository's HEAD"
    fails=$((fails + 1))
  fi
  if [ "$fails" -eq 0 ]; then echo "varka_precommit selftest: ok"; fi
  exit "$fails"
fi

if [ "${1:-}" = "--install-hook" ]; then
  # Hooks live in the main repository's .git/hooks and are shared by every worktree, so the
  # hook must find this script through the worktree it runs in, never through the path of the
  # worktree that installed it: a hardcoded root breaks every worktree's commits the day that
  # one is removed (which is exactly what happened when the merged task worktrees were pruned).
  hook="$(git rev-parse --git-path hooks)/pre-commit"
  printf '#!/usr/bin/env bash\nexec "$(git rev-parse --show-toplevel)/dev/varka_precommit.sh"\n' \
    > "$hook"
  chmod +x "$hook"
  echo "installed $hook"
  exit 0
fi

# Whether a line-anchored finding is reported only when this commit wrote the line. It is on
# for the two git-driven modes and off when files are named explicitly, since naming a file is
# a request to see everything in it.
scoped=1
diff_base="--cached"
if [ "$#" -gt 0 ] && [ "$1" != "--working-tree" ]; then
  files=("$@")
  scoped=0
elif [ "${1:-}" = "--working-tree" ]; then
  diff_base="HEAD"
  mapfile -t files < <({ git diff --name-only --diff-filter=ACM HEAD; git ls-files --others --exclude-standard; } | sort -u)
else
  mapfile -t files < <(git diff --cached --name-only --diff-filter=ACM)
fi
[ "${#files[@]}" -gt 0 ] || { echo "nothing to check"; exit 0; }

findings=0
note() { findings=$((findings + 1)); echo "$1"; }

# The lines this commit writes, per file, as an associative array keyed "path:line". A hook
# that reports every offending line in a file it merely touches reports work nobody in this
# commit did - an upstream merge, a file whose house style predates the rule - and the only
# way past it is --no-verify, which switches every other check off too. So a line-anchored
# finding is raised only for a line in the diff. Whole-file checks below are unaffected.
declare -A written
scope_file() {
  local f="$1" range start count
  if [ "$scoped" -eq 0 ]; then return; fi
  if ! git ls-files --error-unmatch -- "$f" > /dev/null 2>&1; then
    # Untracked: every line is this commit's.
    written["$f:*"]=1
    return
  fi
  while IFS= read -r range; do
    start="${range%%,*}"; count="${range##*,}"
    [ "$range" = "$start" ] && count=1
    for ((i = 0; i < count; i++)); do written["$f:$((start + i))"]=1; done
  done < <(git diff -U0 "$diff_base" -- "$f" | sed -n 's/^@@ -[^ ]* +\([0-9,]*\) @@.*/\1/p')
}
wrote_line() { # file line
  [ "$scoped" -eq 0 ] && return 0
  [ -n "${written["$1:*"]:-}" ] && return 0
  [ -n "${written["$1:$2"]:-}" ]
}
is_code() { [[ "$1" =~ \.(scala|java|py)$ ]]; }
is_text() { [[ "$1" =~ \.(scala|java|py|md|sh)$ ]] && [[ "$1" != */benchmarks/* ]] && [[ "$1" != sql/varka/papers/* ]]; }
is_varka() { [[ "$1" == sql/varka/* || "$1" == */varka/* ]]; }

docs_changed=0
for f in "${files[@]}"; do
  [ -f "$f" ] || continue
  scope_file "$f"
  [[ "$f" =~ \.md$ ]] && docs_changed=1
  if is_text "$f"; then
    # Non-ASCII outside string literals: drop "..." spans first, then look.
    while IFS= read -r line; do
      wrote_line "$f" "$line" && note "$f:$line: non-ASCII outside a string literal"
    done < <(sed -E 's/"([^"\\]|\\.)*"//g' "$f" | grep -n -P '[^\x00-\x7F]' | cut -d: -f1 \
      | while read -r n; do
          # Report the original line's number; sed kept line numbering.
          echo "$n"
        done)
  fi
  if is_code "$f"; then
    # `single_ok` is ruff's E501 exemption, and it applies to Python only: a line whose
    # content is one whitespace-separated chunk - a bare `"pyspark.sql.tests.very.long.name",`
    # in a module list, say - has no wrap point, so reporting it asks for an edit that cannot
    # be made. Scalastyle and checkstyle grant no such exemption, so Scala and Java stay
    # strict. Without this the scan fires on upstream files a merge merely carries along,
    # which teaches everyone to pass --no-verify.
    single_ok=0
    [[ "$f" =~ \.py$ ]] && single_ok=1
    while IFS= read -r hit; do
      wrote_line "$f" "${hit%%:*}" && note "$f:$hit: line over 100 columns"
    done < <(awk -v single_ok="$single_ok" 'length > 100 && $0 !~ /^[[:space:]]*(import|package) / && $0 !~ /https?:\/\// && !(single_ok && NF < 2) { print FNR ": " length " chars" }' "$f")
  fi
  if is_text "$f"; then
    # Every line, not only the commit's: see the header. `=======` alone is left out, since a
    # Markdown setext heading underlines with it.
    while IFS= read -r hit; do
      note "$f:$hit: merge-conflict marker"
    done < <(grep -n -E '^(<<<<<<<|>>>>>>>) ' "$f" | cut -d: -f1)
  fi
  if is_varka "$f" && is_text "$f"; then
    # In code any mention is a marker; in Markdown only the marker form is, since the notes
    # that state this rule have to name the words.
    if [[ "$f" =~ \.md$ ]]; then pattern='^[[:space:]]*(TODO|FIXME)\b|\b(TODO|FIXME):'
    else pattern='\b(TODO|FIXME)\b'; fi
    while IFS= read -r hit; do
      wrote_line "$f" "$hit" \
        && note "$f:$hit: TODO/FIXME marker; record it in the plan instead"
    done < <(grep -n -E "$pattern" "$f" | cut -d: -f1)
  fi
  if [[ "$f" =~ \.(scala|java|py|sh)$ ]] && [[ "$f" != dev/* ]] \
      && { is_varka "$f" || [[ "${f##*/}" == Varka* ]]; }; then
    # History in a comment: a line the commit adds whose comment says what the code used to do.
    # Only the comment is read - a whole comment line, or the text after `//` or `#` - so a
    # string literal or a test title is left alone. The instrumental "used to" passes: after a
    # form of "be", with at most one word between ("is also used to"), after "it's" or
    # "that's", and at the start of a comment line, where a wrapped sentence puts it.
    while IFS= read -r hit; do
      wrote_line "$f" "$hit" \
        && note "$f:$hit: a comment that narrates history ('used to', 'the old'); say what the \
code does, and leave the history to the plan and git (sql/varka/AGENTS.md)"
    done < <(perl -ne '
      my $c;
      if (/^\s*(?:\/\/|\/?\*|#)(.*)$/) { $c = $1 } elsif (/(?:\/\/|#)(.*)$/) { $c = $1 }
      next unless defined $c;
      my $u = $c;
      $u =~ s/\b(?:is|are|be|been|being|was|were|it.s|that.s)\s+(?:\w+\s+)?used to\b//gi;
      $u =~ s/^\s*used to\b//i;
      print "$.\n" if $u =~ /\bused to\b/i || $c =~ /\bthe old\b/i;
    ' "$f")
  fi
  if [[ "$f" == */src/main/* && "$f" =~ \.(scala|java)$ ]] \
      && { is_varka "$f" || [[ "${f##*/}" == Varka* ]]; } \
      && [[ ! "$f" =~ /(VarkaSegments|VarkaVectorSupport|VarkaMorsel)\.java$ ]]; then
    while IFS= read -r hit; do
      wrote_line "$f" "$hit" \
        && note "$f:$hit: raw .reinterpret(; map an address through VarkaSegments.map or \
VarkaVectorSupport.ofAddress, so the memory sanitizer sees it (VARKA-263)"
    done < <(grep -n -F '.reinterpret(' "$f" | cut -d: -f1)
  fi
done

py_files=()
for f in "${files[@]}"; do [ -f "$f" ] && [[ "$f" =~ \.py$ ]] && py_files+=("$f"); done
if [ "${#py_files[@]}" -gt 0 ]; then
  if command -v ruff > /dev/null 2>&1; then
    while IFS= read -r hit; do
      note "ruff check: $hit"
    done < <(ruff check --output-format concise "${py_files[@]}" 2>&1 \
      | grep -E '^[^ ]+:[0-9]+:[0-9]+:')
    while IFS= read -r hit; do
      note "ruff format: $hit would be reformatted; run ruff format on it"
    done < <(ruff format --check "${py_files[@]}" 2>&1 | sed -n 's/^Would reformat: //p')
  else
    note "ruff not found: CI runs ruff check and ruff format over ${#py_files[@]} Python file(s); \
install the version dev/lint-python pins"
  fi
fi

# The benchmark diff carries its own check of the one thing a reader cannot see
# it get wrong: a row key that collides silently drops a row from the requote,
# which is the closing step of every regeneration. The scheduled-failure reporter's
# selftest holds its issue/comment decision, which no run exercises until a night goes red.
for tool in varka_bench_diff varka_bench_gate varka_scheduled_failure; do
  if printf '%s\n' "${files[@]}" | grep -qx "dev/$tool.py"; then
    if ! out="$(python3 "dev/$tool.py" --selftest 2>&1)"; then
      echo "$out" | sed "s|^|$tool selftest: |"
      findings=$((findings + 1))
    fi
  fi
done

# This script's own check, for the same reason: the column rule answers differently per
# language, and a change to it is invisible until a commit in some other worktree fires.
if printf '%s\n' "${files[@]}" | grep -qx "dev/varka_precommit.sh"; then
  if ! out="$("$0" --selftest 2>&1)"; then
    echo "$out" | sed "s|^|self: |"
    findings=$((findings + 1))
  fi
fi

# A committed results file must satisfy its invariants whatever its numbers are.
if printf '%s\n' "${files[@]}" | grep -qE '^sql/.*/benchmarks/Varka.*-results\.txt$'; then
  while IFS= read -r f; do
    out="$(python3 dev/varka_bench_gate.py "$f" 2>&1)" || {
      echo "$out" | sed 's/^/bench gate: /'
      findings=$((findings + 1))
    }
  done < <(printf '%s\n' "${files[@]}" | grep -E '^sql/.*/benchmarks/Varka.*-results\.txt$')
fi

# SKILLS.md is generated from the lesson files under sql/varka/skills/, so a lesson added
# without regenerating it leaves the index wrong rather than merely short - and the index is
# the only way anything finds a lesson.
if printf '%s\n' "${files[@]}" | grep -qE '^(SKILLS\.md|sql/varka/skills/.*\.md)$' \
    && [ -x dev/varka_toc.py ]; then
  out="$(dev/varka_toc.py --check SKILLS.md --from sql/varka/skills 2>&1)"; rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "$out" | sed 's/^/contents: /'
    findings=$((findings + rc))
  fi
fi

# The calibration table is printed from one file per entry under sql/varka/skills/calibration/
# (VARKA-384), so a malformed entry file - a field missing, a name that is not its task's - would
# drop or garble its row in every plan's reading of the table without anyone seeing it.
if printf '%s\n' "${files[@]}" \
    | grep -qE '^(sql/varka/skills/calibration/.*|dev/varka_calibration\.py)$' \
    && [ -x dev/varka_calibration.py ]; then
  out="$(dev/varka_calibration.py --check 2>&1)"; rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "$out" | sed 's/^/calibration: /'
    findings=$((findings + rc))
  fi
fi

# Milestone 8 counts its progress in the census and the cast matrix, rendered from the registry,
# the coverage table and a curated half (VARKA-328): a change to any input that leaves the committed
# files stale moves the milestone's headline without anyone seeing it. The catalogue check fails
# while a plan still cites a path the scope catalogue moved away from.
census_inputs='^(dev/varka_census\.py|dev/varka_catalogue_move\.sh|sql/varka/coverage\.json'
census_inputs+='|sql/varka/plans/.*'
census_inputs+='|sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/analysis'
census_inputs+='/FunctionRegistry\.scala)$'
if printf '%s\n' "${files[@]}" | grep -qE "$census_inputs"; then
  for check in "dev/varka_census.py --check" "dev/varka_catalogue_move.sh --check 8 9"; do
    out="$($check 2>&1)" || {
      echo "$out" | sed 's/^/census: /'
      findings=$((findings + 1))
    }
  done
fi

if [ "$docs_changed" -eq 1 ] && [ -x dev/varka_quote_check.py ]; then
  out="$(dev/varka_quote_check.py 2>&1)"; rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "$out" | grep -E 'ORPHAN|orphan' | sed 's/^/quote check: /'
    findings=$((findings + rc))
  fi
fi

if [ "$findings" -eq 0 ]; then
  echo "varka pre-commit: ${#files[@]} file(s), no findings"
else
  echo "varka pre-commit: $findings finding(s)"
fi
exit "$findings"
