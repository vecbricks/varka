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
# to be committed, so they are caught here rather than by CI or a reviewer:
#
#   * no non-ASCII byte in Scala, Java, Python or Markdown outside a string
#     literal (CLAUDE.md: typographic quotes, dashes and ellipses creep into
#     comments; benchmark results files are exempt, JMH writes its own +-);
#   * no source line over 100 columns in Scala, Java or Python, imports, package
#     lines and URLs excepted (the linters enforce this; this is the cheap hint);
#   * no TODO or FIXME marker under sql/varka or in a Varka source directory
#     (sql/varka/AGENTS.md: open work is recorded in a plan, never left as a
#     marker);
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
#
# Exit status is the number of findings.
set -uo pipefail
root="$(git rev-parse --show-toplevel)"; cd "$root"

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

if [ "$#" -gt 0 ] && [ "$1" != "--working-tree" ]; then
  files=("$@")
elif [ "${1:-}" = "--working-tree" ]; then
  mapfile -t files < <({ git diff --name-only --diff-filter=ACM HEAD; git ls-files --others --exclude-standard; } | sort -u)
else
  mapfile -t files < <(git diff --cached --name-only --diff-filter=ACM)
fi
[ "${#files[@]}" -gt 0 ] || { echo "nothing to check"; exit 0; }

findings=0
note() { findings=$((findings + 1)); echo "$1"; }
is_code() { [[ "$1" =~ \.(scala|java|py)$ ]]; }
is_text() { [[ "$1" =~ \.(scala|java|py|md|sh)$ ]] && [[ "$1" != */benchmarks/* ]]; }
is_varka() { [[ "$1" == sql/varka/* || "$1" == */varka/* ]]; }

docs_changed=0
for f in "${files[@]}"; do
  [ -f "$f" ] || continue
  [[ "$f" =~ \.md$ ]] && docs_changed=1
  if is_text "$f"; then
    # Non-ASCII outside string literals: drop "..." spans first, then look.
    while IFS= read -r line; do
      note "$f:$line: non-ASCII outside a string literal"
    done < <(sed -E 's/"([^"\\]|\\.)*"//g' "$f" | grep -n -P '[^\x00-\x7F]' | cut -d: -f1 \
      | while read -r n; do
          # Report the original line's number; sed kept line numbering.
          echo "$n"
        done)
  fi
  if is_code "$f"; then
    while IFS= read -r hit; do
      note "$f:$hit: line over 100 columns"
    done < <(awk 'length > 100 && $0 !~ /^[[:space:]]*(import|package) / && $0 !~ /https?:\/\// { print FNR ": " length " chars" }' "$f")
  fi
  if is_varka "$f" && is_text "$f"; then
    # In code any mention is a marker; in Markdown only the marker form is, since the notes
    # that state this rule have to name the words.
    if [[ "$f" =~ \.md$ ]]; then pattern='^[[:space:]]*(TODO|FIXME)\b|\b(TODO|FIXME):'
    else pattern='\b(TODO|FIXME)\b'; fi
    while IFS= read -r hit; do
      note "$f:$hit: TODO/FIXME marker; record it in the plan instead"
    done < <(grep -n -E "$pattern" "$f" | cut -d: -f1)
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
