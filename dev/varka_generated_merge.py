#!/usr/bin/env python3
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
"""Merge Varka's generated files per key, so that two branches adding rows never hand-merge.

  dev/varka_generated_merge.py driver BASE OURS THEIRS PATH  # the git merge driver
  dev/varka_generated_merge.py --check      # fail on a generated file missing a coverage row
  dev/varka_generated_merge.py --check --no-audit  # the same, the width audit left owed
  dev/varka_generated_merge.py --owed       # the owed files of the current or last merge
  dev/varka_generated_merge.py --owed --keep PATH  # clear the owed list but for PATH
  dev/varka_generated_merge.py --self-test  # the merge's cases, in a temporary repository

Every type row regenerates the same few files - coverage.json, emitted_bytes.json,
width_audit.json, types.tsv, the census and the cast tables, and the generated blocks of
docs/sql-varka.md - so a merge of master into a branch conflicted in them whenever both sides
added rows (VARKA-546). Most of each file is per row: a coverage expression keyed by its SQL,
a row's method digests, a shape's width-audit refusals, a TSV row keyed by its leading columns.
This driver merges those parts per key, exactly, and writes the file back in the form the suite
writes (Jackson's pretty printer for the JSON files, which `render_json` reproduces byte for
byte). Only the structures a suite's writer sorts (`RULES`) are merged as sorted; every other
list keeps its sides' order. What it cannot compute - a digest over every row (`option_arms`),
a docs block both sides changed, two rows added at the same place in a list whose order the
suite's source decides, a value list inside one row that both sides changed, a TSV header both
sides changed, a file whose keys repeat - it writes from one side, chosen so that the result
is the same whichever side is "ours", and lists as owed: on stderr, and in
`$(git rev-parse --git-path varka-regen-owed)`, tagged with the merge that owed them, which
`dev/varka_regen_generated.sh` reads (through `--owed`, which skips the entries of an aborted
or unrelated merge) to run only the owed regenerations, in order.

So a generated file never ends a merge with conflict markers. A conflict in the docs file's
prose, outside its generated blocks, is a real one and is left with its markers, and so is any
conflict in a file the driver cannot read or fails on, which it hands to git's text merge.

Installed by `dev/varka_precommit.sh --install-hook`; `sql/varka/.gitattributes` names the
files. `--check` is the CI half: the width audit's own check is cancelled on any CPU but the
file's host, so CI could not see a width audit (or a bytes file) missing a coverage row.
"""

import contextlib
import io
import json
import os
import re
import subprocess
import sys
import tempfile

# --- the JSON form the suites write --------------------------------------------------------


def render_json(value, depth=0):
    """Jackson's DefaultPrettyPrinter as the suites use it: objects indent two spaces per
    object level, arrays stay on the object's line, and empty containers are `{ }` and `[ ]`.

    >>> print(render_json({"a": [1, {"b": []}], "c": {}}))
    {
      "a" : [ 1, {
        "b" : [ ]
      } ],
      "c" : { }
    }
    """
    pad = "  " * depth
    if isinstance(value, dict):
        if not value:
            return "{ }"
        items = [
            f"{pad}  {json.dumps(k, ensure_ascii=False)} : {render_json(v, depth + 1)}"
            for k, v in value.items()
        ]
        return "{\n" + ",\n".join(items) + "\n" + pad + "}"
    if isinstance(value, list):
        if not value:
            return "[ ]"
        return "[ " + ", ".join(render_json(v, depth) for v in value) + " ]"
    return json.dumps(value, ensure_ascii=False)


# --- the three-way merge -------------------------------------------------------------------

MISSING = object()


class Owed:
    """The parts of one file the merge could not compute, as dotted paths."""

    def __init__(self):
        self.paths = []

    def add(self, path):
        self.paths.append(path or "<root>")


def show(path, suffix=""):
    """A path (a tuple of keys) as the dotted text the owed list prints."""
    text = ".".join(str(k) for k in path)
    return f"{text} ({suffix})" if text and suffix else text or suffix


def canon(value):
    """A value as text to compare by, its maps' key order included: the file is written in that
    order, so two maps that differ only in it are two different files."""
    return json.dumps(value, ensure_ascii=False)


def pick(ours, theirs):
    """One of two conflicting values, the same whichever is ours."""
    return ours if canon(ours) <= canon(theirs) else theirs


def is_sorted(keys):
    return all(a < b for a, b in zip(keys, keys[1:]))


class Rules:
    """Which structures of one file its writer sorts, as path patterns: a tuple of regular
    expressions, one per key of the path, each matching the whole key. Only a declared
    structure is merged as sorted; any other keeps the anchor placement below, however its keys
    happen to fall, since two short key lists can be sorted by coincidence."""

    def __init__(self, sorted_maps=(), sorted_sets=()):
        self.sorted_maps = sorted_maps
        self.sorted_sets = sorted_sets

    @staticmethod
    def _matches(patterns, path):
        return any(
            len(p) == len(path) and all(re.fullmatch(r, str(k)) for r, k in zip(p, path))
            for p in patterns
        )

    def sorted_map(self, path):
        return self._matches(self.sorted_maps, path)

    def sorted_set(self, path):
        return self._matches(self.sorted_sets, path)


# What each file's writer sorts: the width audit's per-width shape maps
# (`VarkaWidthAuditSuite`, `verdicts(census).toSeq.sortBy(_._1)`) and the coverage table's
# admitted classes (`VarkaCoverageSuite`, `.toSeq.sorted`). A row's own value lists (a coverage
# row's `catalyst` or `switches`, a shape's refusals) are sorted too, but they are values of one
# row: both sides changing one is owed, never a union.
RULES = {
    "width_audit.json": Rules(sorted_maps=((r"widths", r"\d+"),)),
    "coverage.json": Rules(sorted_sets=((r"catalyst_classes_admitted",),)),
}


def merge_value(base, ours, theirs, path, owed, rules):
    """Three-way merge of one JSON value; `base` is MISSING when both sides added it."""
    if canon(ours) == canon(theirs):
        return ours
    if base is not MISSING and canon(base) == canon(ours):
        return theirs
    if base is not MISSING and canon(base) == canon(theirs):
        return ours
    if isinstance(ours, dict) and isinstance(theirs, dict):
        b = base if isinstance(base, dict) else {}
        items = merge_seq(
            list(b.items()),
            list(ours.items()),
            list(theirs.items()),
            key=lambda kv: kv[0],
            value=lambda kv: kv[1],
            make=lambda k, v: (k, v),
            path=path,
            owed=owed,
            rules=rules,
        )
        return dict(items)
    if isinstance(ours, list) and isinstance(theirs, list):
        b = base if isinstance(base, list) else []
        every = b + ours + theirs
        if every and all(isinstance(x, dict) and "sql" in x for x in every):
            return merge_seq(
                b,
                ours,
                theirs,
                key=lambda x: x["sql"],
                value=lambda x: x,
                make=lambda k, v: v,
                path=path,
                owed=owed,
                rules=rules,
            )
        if (
            rules.sorted_set(path)
            and all(isinstance(x, str) for x in every)
            and all(is_sorted(s) for s in (b, ours, theirs))
        ):
            # A sorted set (`catalyst_classes_admitted`): a three-way set merge, kept sorted.
            bs, os_, ts = set(b), set(ours), set(theirs)
            return sorted((bs & os_ & ts) | (os_ - bs) | (ts - bs))
    owed.add(show(path))
    return pick(ours, theirs)


def merge_seq(base, ours, theirs, key, value, make, path, owed, rules):
    """Three-way merge of an ordered sequence of keyed entries.

    Entries in all three merge by value; an entry one side deleted is dropped unless the other
    changed it; each side's additions follow the base entry that precedes them on that side.
    Where both sides add after the same base entry, the two groups go in the order of the
    groups themselves (their first differing key), so the result does not depend on which side
    is ours, and the place is owed - unless the file's writer sorts this structure (`Rules`)
    and every side's keys are sorted, when the result is sorted too. A side whose keys repeat
    is not a keyed sequence: the whole sequence is owed and taken from one side.
    """
    bkeys = [key(e) for e in base]
    okeys = [key(e) for e in ours]
    tkeys = [key(e) for e in theirs]
    if any(len(set(s)) != len(s) for s in (bkeys, okeys, tkeys)):
        owed.add(show(path, "keys not unique"))
        return pick(ours, theirs)
    bmap = {key(e): value(e) for e in base}
    omap = {key(e): value(e) for e in ours}
    tmap = {key(e): value(e) for e in theirs}
    merged = {}
    for k in bkeys:
        sub = path + (k,)
        in_o, in_t = k in omap, k in tmap
        if in_o and in_t:
            merged[k] = merge_value(bmap[k], omap[k], tmap[k], sub, owed, rules)
        elif in_o and canon(omap[k]) != canon(bmap[k]):
            owed.add(show(sub))
            merged[k] = omap[k]
        elif in_t and canon(tmap[k]) != canon(bmap[k]):
            owed.add(show(sub))
            merged[k] = tmap[k]
    for k in okeys + tkeys:
        if k in bmap or k in merged:
            continue
        if k in omap and k in tmap:
            merged[k] = merge_value(MISSING, omap[k], tmap[k], path + (k,), owed, rules)
        else:
            merged[k] = omap[k] if k in omap else tmap[k]
    for side in (okeys, tkeys):
        kept = [k for k in side if k in bmap]
        if kept != [k for k in bkeys if k in set(kept)]:
            owed.add(show(path, "order"))
    if rules.sorted_map(path) and all(is_sorted(s) for s in (bkeys, okeys, tkeys)):
        order = sorted(merged)
    else:
        groups = {}
        anchors = ({}, {})
        for side_index, side in enumerate((okeys, tkeys)):
            anchor = None
            for k in side:
                if k in bmap:
                    anchor = k
                else:
                    groups.setdefault(anchor, [[], []])[side_index].append(k)
                    anchors[side_index][k] = anchor
        # A key both sides added after different base entries goes after the first of the two
        # in base order, which neither side chose: its place is owed.
        for k in okeys:
            if k in anchors[1] and k in anchors[0] and anchors[0][k] != anchors[1][k]:
                owed.add(show(path, f"order of {k}"))
        order = []
        seen = set()

        def place(anchor):
            o, t = groups.get(anchor, [[], []])
            if o and t and o != t:
                owed.add(show(path, f"order after {anchor}"))
                # The whole groups, compared from their first differing key, so that two
                # groups starting with the same key (both sides added it) still order the same
                # whichever side is ours.
                first, second = (o, t) if o <= t else (t, o)
            else:
                first, second = o or t, []
            for k in first + second:
                if k in merged and k not in seen:
                    seen.add(k)
                    order.append(k)

        place(None)
        for k in bkeys:
            if k in merged and k not in seen:
                seen.add(k)
                order.append(k)
            place(k)
    return [make(k, merged[k]) for k in order]


def reject_duplicates(pairs):
    """A JSON object with a repeated key is not the suites' form: `json.loads` would keep one
    value silently, so the driver falls back to a text merge instead."""
    keys = [k for k, _ in pairs]
    if len(set(keys)) != len(keys):
        raise ValueError("an object repeats a key")
    return dict(pairs)


def merge_json(base_text, ours_text, theirs_text, owed, rules):
    def load(text):
        return json.loads(text, object_pairs_hook=reject_duplicates)

    base = load(base_text) if base_text.strip() else {}
    merged = merge_value(base, load(ours_text), load(theirs_text), (), owed, rules)
    return render_json(merged) + "\n"


# The leading columns that key a row of each generated TSV: the columns unique in the committed
# file, which `merge_tsv` checks again on every side of every merge. `casts_fused.tsv` has one
# row per form pair of a cast, so (from, to) repeats and its key takes the two type columns too.
TSV_KEYS = {"types.tsv": 1, "census.tsv": 1, "casts.tsv": 2, "casts_fused.tsv": 4}


def lines_of(text, keepends=False):
    r"""A file's lines, split at "\n" alone: `str.splitlines` also splits at "\r", a form feed,
    U+2028 and other characters a row or a docs line may hold. A final "\n" ends the last line
    rather than starting an empty one.

    >>> lines_of("a\fb\rc\nd\n"), lines_of("a\nb", keepends=True)
    (['a\x0cb\rc', 'd'], ['a\n', 'b'])
    """
    parts = text.split("\n")
    last = parts.pop()
    lines = [p + "\n" for p in parts] if keepends else parts
    if last:
        lines.append(last)
    return lines


def merge_tsv(base_text, ours_text, theirs_text, key_columns, owed):
    """The same merge over a TSV's rows. The comment lines and the header are text, merged
    whole: when they differ between two sides that both changed the file, or any side has a
    comment among its rows or a repeated key, the file is owed and written from one side,
    since rows under two headers are not one table. No writer sorts its TSV, so the rows keep
    the anchor placement."""
    if ours_text == theirs_text or base_text == theirs_text:
        return ours_text
    if base_text == ours_text:
        return theirs_text

    def split(text):
        lines = lines_of(text)
        n = 0
        while n < len(lines) and lines[n].startswith("#"):
            n += 1
        head, body = lines[: n + 1], lines[n + 1 :]
        if any(r.startswith("#") for r in body):
            return head, None
        return head, [(tuple(r.split("\t")[:key_columns]), r) for r in body]

    _, br = split(base_text)
    oh, orows = split(ours_text)
    th, trows = split(theirs_text)
    # Both sides changed the file, so a header or comment that differs between them is a
    # change one side made under rows the other side wrote to the old header.
    if oh != th or None in (br, orows, trows):
        owed.add("the file, whose header or comments a side changed")
        return pick(ours_text, theirs_text)
    head = oh
    rows = merge_seq(
        br,
        orows,
        trows,
        key=lambda e: e[0],
        value=lambda e: e[1],
        make=lambda k, v: v,
        path=(),
        owed=owed,
        rules=Rules(),
    )
    if rows and not isinstance(rows[0], str):
        # A repeated key: merge_seq took one side's entries whole.
        rows = [r for _, r in rows]
    return "\n".join(head + rows) + "\n"


def merge_text(name, base_text, ours_text, theirs_text, owed):
    """The merge of one generated JSON or TSV file, chosen by its file name."""
    if name.endswith(".json"):
        return merge_json(base_text, ours_text, theirs_text, owed, RULES.get(name, Rules()))
    return merge_tsv(base_text, ours_text, theirs_text, TSV_KEYS[name], owed)


def generated_blocks(path):
    """The text of each `BEGIN generated` block of a docs file, by the block's name."""
    with open(path, encoding="utf-8", newline="") as f:
        lines = lines_of(f.read(), keepends=True)
    blocks, name = {}, None
    for line in lines:
        if line.startswith("<!-- END generated"):
            name = None
        elif name is not None:
            blocks[name] += line
        elif line.startswith("<!-- BEGIN generated"):
            name = line.strip()[len("<!-- BEGIN generated") : -len("-->")].strip()
            blocks[name] = ""
    return blocks


def block_state(in_block, lines):
    """Whether a docs file is inside a generated block after `lines`, given that it was
    `in_block` before them."""
    for line in lines:
        if line.startswith("<!-- BEGIN generated"):
            in_block = True
        elif line.startswith("<!-- END generated"):
            in_block = False
    return in_block


def merge_docs(base_path, ours_path, theirs_path, owed):
    """`git merge-file` over the docs file; a conflict inside a generated block takes one side
    and owes the coverage run that rewrites the block. Returns the text and whether a conflict
    outside every block is left with its markers."""
    # The parser below reads the two-section markers, so the style is pinned against a clone
    # whose merge.conflictStyle is diff3 or zdiff3.
    proc = subprocess.run(
        [
            "git",
            "-c",
            "merge.conflictStyle=merge",
            "merge-file",
            "-p",
            "-L",
            "ours",
            "-L",
            "base",
            "-L",
            "theirs",
            ours_path,
            base_path,
            theirs_path,
        ],
        capture_output=True,
    )
    if proc.returncode < 0 or proc.returncode > 127:
        raise RuntimeError(proc.stderr.decode("utf-8", "replace"))
    # A block both sides changed is owed even when merge-file merged it cleanly: its lines are
    # rendered from every row (the verification counts), so a line-wise union of two renders is
    # neither side's, nor the merged rows'.
    blocks = [generated_blocks(p) for p in (base_path, ours_path, theirs_path)]
    for name in sorted(set().union(*blocks)):
        b, o, t = (x.get(name) for x in blocks)
        if o != b and t != b and o != t:
            owed.add(f"generated block {name}")
    out, real = [], False
    in_block = False
    # Decoded here rather than by text=True, whose universal newlines would turn a "\r" into
    # a line break.
    lines = lines_of(proc.stdout.decode("utf-8"), keepends=True)
    i = 0

    # The markers are matched whole, as merge-file writes them with the labels above: a line
    # that only starts with "=======" (a Markdown heading's underline) is content.
    def marker(line, text):
        return line.rstrip("\r\n") == text

    while i < len(lines):
        line = lines[i]
        if marker(line, "<<<<<<< ours"):
            j = i + 1
            while not marker(lines[j], "======="):
                j += 1
            k = j + 1
            while not marker(lines[k], ">>>>>>> theirs"):
                k += 1
            o, t = lines[i + 1 : j], lines[j + 1 : k]
            markers = any("generated" in x and x.startswith("<!--") for x in o + t)
            if in_block and not markers:
                owed.add("generated block")
                out.extend(o if "".join(o) <= "".join(t) else t)
            else:
                real = True
                out.extend(lines[i : k + 1])
                # A hunk holding a BEGIN or END line moves the block state on each side. Only
                # where both sides end inside a block do the lines after it count as inside;
                # where they disagree, a later hunk stays a real conflict until the next
                # BEGIN or END line outside every hunk settles the state again.
                in_block = block_state(in_block, o) and block_state(in_block, t)
            i = k + 1
            continue
        in_block = block_state(in_block, [line])
        out.append(line)
        i += 1
    return "".join(out), real


CENSUS = "the census"


def owed_file():
    path = subprocess.run(
        ["git", "rev-parse", "--git-path", "varka-regen-owed"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    return path


# The owed list outlives the merge that wrote it: it is one file per worktree, appended to by
# every merge, and nothing runs when a merge is aborted. So each line, "OURS THEIRS<tab>PATH",
# carries the merge that owed PATH: OURS is the commit merged into (HEAD while the driver
# runs), THEIRS the commit merged in (git names it in a GITHEAD_<sha> variable for `git merge`,
# --squash too; "-" where it does not, as in a cherry-pick, a revert, a rebase or a stash pop).
# MERGE_HEAD cannot serve, since git writes it only after the merge's files are merged. An entry
# is live:
#   * while its operation is in progress and HEAD is OURS: a merge (MERGE_HEAD exists and is
#     THEIRS), or a squash merge, cherry-pick, revert or rebase not yet committed or stopped on
#     a conflict (SQUASH_MSG, CHERRY_PICK_HEAD, REVERT_HEAD, rebase-merge or rebase-apply exists
#     under the git dir; git removes each when the operation is committed or aborted);
#   * while a rebase or a cherry-pick or revert of several commits is in progress, for the
#     picks it already committed: OURS lies between the commit it started from (`onto` under
#     rebase-merge or rebase-apply, `head` under sequencer) and HEAD, so every such commit is
#     one the operation made;
#   * once a merge is committed: a merge commit on HEAD's first-parent line has OURS as its
#     first parent and THEIRS among the others, which stays true through the commit of the
#     regenerated files and any later commit.
# Every other entry - an aborted operation's, one from another branch - is stale: `owed_live`
# skips it, and the regeneration script's rewrite of the list drops it. Some debt is not
# covered, since nothing left in the repository tells it from an aborted operation's: a
# squash merge, cherry-pick, revert or rebase once committed (a one-parent commit on OURS is
# also what any commit after an aborted merge is), a `git stash pop` (it leaves no state at
# all) and a `git checkout -m` (OURS is the HEAD before the checkout). For those the driver's
# message on stderr is the record: it names the file and dev/varka_regen_generated.sh. Some
# debt is over-counted: an operation aborted and redone on the same commits finds the first
# attempt's entries live, which costs at most a regeneration the second may not have owed.


def git_out(*args):
    """A git command's output, or None when it fails."""
    run = subprocess.run(["git", *args], capture_output=True, text=True)
    return run.stdout.strip() if run.returncode == 0 else None


def git_path(name):
    """A file under the git dir, as `git rev-parse --git-path` names it (a worktree's own)."""
    return git_out("rev-parse", "--git-path", name) or os.path.join(".git", name)


def is_ancestor(a, b):
    """Whether commit `a` is `b` or one of its ancestors; False for an unknown commit."""
    run = subprocess.run(["git", "merge-base", "--is-ancestor", a, b], capture_output=True)
    return run.returncode == 0


def merge_identity():
    """This merge's "OURS THEIRS" for the owed list."""
    theirs = [k[len("GITHEAD_") :] for k in os.environ if k.startswith("GITHEAD_")]
    ours = git_out("rev-parse", "-q", "--verify", "HEAD") or "-"
    return f"{ours} {theirs[0] if len(theirs) == 1 else '-'}"


def owed_live():
    """The owed list's live entries, as (merge identity, path) pairs, in the list's order."""
    path = owed_file()
    try:
        with open(path, encoding="utf-8", newline="") as f:
            lines = lines_of(f.read())
    except FileNotFoundError:
        return []
    head = git_out("rev-parse", "-q", "--verify", "HEAD")
    merge_head = git_out("rev-parse", "-q", "--verify", "MERGE_HEAD")
    in_progress = any(
        os.path.exists(git_path(n))
        for n in ("SQUASH_MSG", "CHERRY_PICK_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply")
    )
    # The commit a rebase, or a cherry-pick or revert of several commits, started from.
    started = None
    for n in ("rebase-merge/onto", "rebase-apply/onto", "sequencer/head"):
        with contextlib.suppress(OSError):
            with open(git_path(n), encoding="utf-8") as f:
                started = f.read().strip() or None
        if started:
            break
    live = {}

    def is_live(merge_id):
        ours, _, theirs = merge_id.partition(" ")
        if ours == "-" or not theirs:
            return False
        if merge_head and head == ours and theirs in ("-", merge_head):
            return True
        if in_progress and head == ours:
            return True
        # A merge-base check first, so that an entry whose OURS is not on HEAD's history (an
        # aborted merge's on another branch) is rejected before the first-parent walk.
        if not is_ancestor(ours, "HEAD"):
            return False
        if started and is_ancestor(started, ours):
            return True
        commits = git_out("rev-list", "--first-parent", "--parents", "HEAD", "^" + ours) or ""
        return any(
            len(c) > 2 and c[1] == ours and theirs in ["-"] + c[2:]
            for c in (line.split() for line in commits.splitlines())
        )

    entries = []
    for line in lines:
        merge_id, sep, rel = line.partition("\t")
        if sep:
            if merge_id not in live:
                live[merge_id] = is_live(merge_id)
            if live[merge_id]:
                entries.append((merge_id, rel))
    return entries


def owed_command(keep=None):
    """`--owed`: print the live owed files, one each. `--owed --keep PATH`: rewrite the list to
    PATH's live entries alone (no list when there are none) and print PATH if it is still owed;
    the regeneration script's way to clear the list but for a skipped width audit."""
    entries = owed_live()
    if keep is None:
        for rel in sorted({rel for _, rel in entries}):
            print(rel)
        return 0
    kept = [(m, rel) for m, rel in entries if rel == keep]
    path = owed_file()
    if kept:
        with open(path, "w", encoding="utf-8") as f:
            f.write("".join(f"{m}\t{rel}\n" for m, rel in kept))
        print(keep)
    elif os.path.exists(path):
        os.remove(path)
    return 0


def driver(base_path, ours_path, theirs_path, repo_path):
    def read(p):
        with open(p, encoding="utf-8", newline="") as f:
            return f.read()

    name = os.path.basename(repo_path)
    owed = Owed()
    real = False
    try:
        if (
            name == "coverage.json"
            and len({read(base_path), read(ours_path), read(theirs_path)}) == 3
        ):
            # Both sides moved the coverage table: the census reads it, so it is owed even when
            # every row merged exactly.
            owed.add(CENSUS + ", which reads coverage.json")
        if (
            name in ("census.tsv", "casts.tsv")
            and len({read(base_path), read(ours_path), read(theirs_path)}) == 3
        ):
            # Rendered from the inputs by dev/varka_census.py, which rewrites it in a second.
            owed.add(CENSUS + ", rendered from its inputs")
        if name.endswith(".json") or name in TSV_KEYS:
            text = merge_text(name, read(base_path), read(ours_path), read(theirs_path), owed)
        elif name.endswith(".md"):
            text, real = merge_docs(base_path, ours_path, theirs_path, owed)
        else:
            raise ValueError(f"no merge rule for {repo_path}")
        # The census is owed by reason, not by file: dev/varka_regen_generated.sh runs it last
        # on every run, so a file whose only debt is the census goes on no list, and in
        # particular owes coverage.json no run of the coverage suite.
        owes_file = any(not p.startswith(CENSUS) for p in owed.paths)
        # Found before the merged text is written, so that a failure here still falls back.
        owed_path, merge_id = (owed_file(), merge_identity()) if owes_file else (None, None)
    except Exception as e:
        # A side that is not the generated form (markers, hand edits), or any fault of this
        # driver: git's text merge, which leaves markers where it conflicts, so a merge never
        # ends on a clean-looking file the driver did not finish.
        print(f"varka merge: {repo_path}: {e}; falling back to a text merge", file=sys.stderr)
        return subprocess.run(
            [
                "git",
                "merge-file",
                "-L",
                "ours",
                "-L",
                "base",
                "-L",
                "theirs",
                ours_path,
                base_path,
                theirs_path,
            ]
        ).returncode
    with open(ours_path, "w", encoding="utf-8", newline="") as f:
        f.write(text)
    if owed.paths:
        shown = ", ".join(sorted(set(owed.paths))[:4])
        more = len(set(owed.paths)) - 4
        print(
            f"varka merge: {repo_path} merged; regeneration owed for {shown}"
            + (f" and {more} more" if more > 0 else "")
            + "; run dev/varka_regen_generated.sh",
            file=sys.stderr,
        )
        if owes_file:
            with open(owed_path, "a", encoding="utf-8") as f:
                f.write(f"{merge_id}\t{repo_path}\n")
    else:
        print(f"varka merge: {repo_path} merged per key, nothing owed", file=sys.stderr)
    return 1 if real else 0


# --- the CI check --------------------------------------------------------------------------

AUDIT_REGEN = "VARKA_AUDIT_REGEN=true build/sbt 'catalyst/testOnly *VarkaWidthAuditSuite'"
BYTES_REGEN = "VARKA_BYTES_REGEN=true build/sbt 'catalyst/testOnly *VarkaEmittedBytesSuite'"


def check(root, audit=True):
    """The coverage rows every per-row generated file must carry, read without a JVM. With
    `audit` false the width audit is not read: a regeneration on another CPU left it owed."""

    def load(rel):
        with open(os.path.join(root, rel), encoding="utf-8") as f:
            return json.load(f)

    coverage = load("sql/varka/coverage.json")
    rows = [e["sql"] for e in coverage["expressions"]]
    row_set = set(rows)
    errors = []
    widths = load("sql/varka/width_audit.json") if audit else {"widths": {}}
    host = widths.get("host", {}).get("cpu", "?")
    for width, shapes in widths["widths"].items():
        missing = [r for r in rows if r not in shapes]
        extra = [s for s in shapes if s not in row_set and not s.startswith("construction: ")]
        if missing or extra:
            errors.append(
                f"sql/varka/width_audit.json at {width} bits is stale: "
                + (f"no entry for {missing[:3]}" if missing else "")
                + (" and " if missing and extra else "")
                + (f"entries for removed rows {extra[:3]}" if extra else "")
                + f". Regenerate it on its host ({host}) with\n  {AUDIT_REGEN}"
            )
    emitted = load("sql/varka/emitted_bytes.json")
    skipped = set(emitted.get("coverage_rows_skipped", []))
    expected = [r for r in rows if r not in skipped]
    expected_set = set(expected)
    for lanes, sections in emitted["lanes"].items():
        have = sections.get("coverage", {})
        if list(have) != expected:
            missing = [r for r in expected if r not in have]
            extra = [r for r in have if r not in expected_set and r not in skipped]
            # A row the file lists as skipped (it emits no class) must carry no digests either.
            still = [r for r in have if r in skipped]
            what = (
                f"no digests for {missing[:3]}"
                if missing
                else f"digests for removed rows {extra[:3]}"
                if extra
                else f"rows it skips that still carry digests {still[:3]}"
                if still
                else "its rows in another order than coverage.json's"
            )
            errors.append(
                f"sql/varka/emitted_bytes.json at {lanes} lanes is stale: {what}. "
                f"Regenerate it with\n  {BYTES_REGEN}"
            )
    for e in errors:
        print("generated: " + e, file=sys.stderr)
    if errors:
        print(
            "generated: after a merge, dev/varka_regen_generated.sh --all runs every "
            "regeneration in order",
            file=sys.stderr,
        )
    return 1 if errors else 0


# --- the self-test -------------------------------------------------------------------------


def fixture_env():
    """The environment for the self-test's git commands in a temporary repository. A hook that
    runs the self-test exports GIT_DIR, GIT_INDEX_FILE or GIT_WORK_TREE, which would send those
    commands into the outer repository, so every GIT_ variable is dropped; and the global and
    system configs are not read, so neither the fixture nor the installer it runs sees (or
    writes through) the user's core.hooksPath or conflict style."""
    env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
    return {**env, "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1"}


def self_test():
    import doctest

    failed, _ = doctest.testmod()
    cases = 0

    def expect(cond, what):
        nonlocal cases, failed
        cases += 1
        if not cond:
            failed += 1
            print("self-test FAILED: " + what, file=sys.stderr)

    def merged(name, b, o, t):
        """`merge_text` on JSON values or TSV texts, with what it owed."""
        owed = Owed()
        if name.endswith(".json"):
            b, o, t = (render_json(x) + "\n" for x in (b, o, t))
            return json.loads(merge_text(name, b, o, t, owed)), owed.paths
        return merge_text(name, b, o, t, owed), owed.paths

    # Per-key merges, in both orders.
    # The coverage table's rows are in the suite's order, not sorted.
    base = {
        "expressions": [{"sql": "z", "v": 1}, {"sql": "a", "v": 1}],
        "arms": {"x": "0"},
        "catalyst_classes_admitted": ["A", "C"],
    }
    left = {
        "expressions": [{"sql": "z", "v": 1}, {"sql": "b", "v": 2}, {"sql": "a", "v": 1}],
        "arms": {"x": "1"},
        "catalyst_classes_admitted": ["A", "B", "C"],
    }
    right = {
        "expressions": [{"sql": "z", "v": 1}, {"sql": "c", "v": 3}, {"sql": "a", "v": 1}],
        "arms": {"x": "2"},
        "catalyst_classes_admitted": ["A", "C", "D"],
    }
    m1, o1 = merged("coverage.json", base, left, right)
    m2, _ = merged("coverage.json", base, right, left)
    expect(canon(m1) == canon(m2), "the merge depends on which side is ours")
    expect([e["sql"] for e in m1["expressions"]] == ["z", "b", "c", "a"], "row order")
    expect(m1["catalyst_classes_admitted"] == ["A", "B", "C", "D"], "the sorted set merge")
    expect(sorted(o1) == ["arms.x", "expressions (order after z)"], f"owed {o1}")
    # The width audit's per-width maps, which the suite sorts, stay sorted and owe nothing.
    m, o = merged(
        "width_audit.json",
        {"widths": {"128": {"a": [], "d": []}}},
        {"widths": {"128": {"a": [], "b": [], "d": []}}},
        {"widths": {"128": {"a": [], "c": [], "d": []}}},
    )
    expect(list(m["widths"]["128"]) == ["a", "b", "c", "d"] and not o, f"sorted map {m} {o}")
    review_cases(expect, merged)
    # One side's change against an unchanged other side is taken, not owed.
    o = Owed()
    m = merge_value({"k": 1, "j": 1}, {"k": 2, "j": 1}, {"k": 1, "j": 3}, (), o, Rules())
    expect(m == {"k": 2, "j": 3} and not o.paths, "independent edits")
    # A deletion against an unchanged entry.
    o = Owed()
    m = merge_value({"k": 1, "j": 1}, {"j": 1}, {"k": 1, "j": 1}, (), o, Rules())
    expect(m == {"j": 1} and not o.paths, "a deletion")
    # TSV rows keyed by the leading columns.
    o = Owed()
    tb = "# c\nid\tx\nA\t1\nZ\t1\n"
    t = merge_tsv(tb, "# c\nid\tx\nA\t1\nB\t1\nZ\t1\n", "# c\nid\tx\nA\t2\nZ\t1\n", 1, o)
    expect(t == "# c\nid\tx\nA\t2\nB\t1\nZ\t1\n" and not o.paths, f"tsv {t!r} {o.paths}")
    # The committed files round-trip, so a merged file is written in the suites' form.
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    for rel in ("coverage.json", "emitted_bytes.json", "width_audit.json"):
        p = os.path.join(root, "sql", "varka", rel)
        if os.path.exists(p):
            with open(p, encoding="utf-8") as f:
                text = f.read()
            expect(render_json(json.loads(text)) + "\n" == text, f"{rel} does not round-trip")
    # A coverage row the width audit and the bytes file lack fails the check, with commands.
    if all(
        os.path.exists(os.path.join(root, "sql", "varka", r))
        for r in ("coverage.json", "emitted_bytes.json", "width_audit.json")
    ):
        with tempfile.TemporaryDirectory() as tmp:
            os.makedirs(os.path.join(tmp, "sql", "varka"))
            for rel in ("emitted_bytes.json", "width_audit.json"):
                with open(os.path.join(root, "sql", "varka", rel), encoding="utf-8") as f:
                    text = f.read()
                with open(os.path.join(tmp, "sql", "varka", rel), "w", encoding="utf-8") as f:
                    f.write(text)
            with open(os.path.join(root, "sql", "varka", "coverage.json"), encoding="utf-8") as f:
                doc = json.load(f)
            doc["expressions"].append({"sql": "a row only coverage.json has"})
            with open(
                os.path.join(tmp, "sql", "varka", "coverage.json"), "w", encoding="utf-8"
            ) as f:
                f.write(render_json(doc) + "\n")
            expect(check(tmp) == 1, "a stale width audit passed the check")
            # 13. A row the bytes file skips yet still carries digests for is named as such.
            with open(
                os.path.join(tmp, "sql", "varka", "coverage.json"), "w", encoding="utf-8"
            ) as f:
                f.write(render_json({"expressions": [{"sql": "kept"}, {"sql": "skipped"}]}) + "\n")
            with open(
                os.path.join(tmp, "sql", "varka", "emitted_bytes.json"), "w", encoding="utf-8"
            ) as f:
                f.write(
                    render_json(
                        {
                            "coverage_rows_skipped": ["skipped"],
                            "lanes": {"4": {"coverage": {"kept": "d1", "skipped": "d2"}}},
                        }
                    )
                    + "\n"
                )
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                rc = check(tmp, audit=False)
            expect(
                rc == 1 and "skips that still carry digests ['skipped']" in err.getvalue(),
                f"13: a skipped row's digests were reported as {err.getvalue()!r}",
            )
    # 10. A hook's GIT_DIR and GIT_INDEX_FILE must not reach the fixture's repository: they
    # name paths no git command can create, so a fixture command that reads them fails.
    saved_env = dict(os.environ)
    os.environ["GIT_DIR"] = os.path.join(os.devnull, "git")
    os.environ["GIT_INDEX_FILE"] = os.path.join(os.devnull, "index")
    try:
        end_to_end(expect)
    except subprocess.CalledProcessError as e:
        expect(False, f"10: a fixture git command read the outer GIT_ variables: {e}")
    finally:
        os.environ.clear()
        os.environ.update(saved_env)
    print(f"self-test: {cases} case(s), {failed} failed")
    return 1 if failed else 0


def review_cases(expect, merged):
    """The cases a review of the driver reproduced, one or more per finding."""
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    # 1. casts_fused.tsv has one row per form pair, so (from, to) repeats; a row added to it
    # must keep every committed row.
    p = os.path.join(root, "sql", "varka", "plans", "m8", "casts_fused.tsv")
    if os.path.exists(p):
        with open(p, encoding="utf-8") as f:
            text = f.read()
        more = text + "zz\tzz\tZZ\tZZ\tdeclined\tdeclined\tdeclined\n"
        t, o = merged("casts_fused.tsv", text, more, text)
        expect(t == more and not o, "1: casts_fused.tsv lost rows to a key that is not unique")
    # 1. A TSV whose key repeats in the committed file is owed, never merged by that key.
    t, o = merged(
        "types.tsv", "id\tx\nA\t1\nA\t2\n", "id\tx\nA\t1\nA\t2\nB\t1\n", "id\tx\nA\t1\nA\t2\nC\t1\n"
    )
    expect(t.count("A\t") == 2 and o, f"1: a repeated key merged silently: {t!r} {o}")
    # 2. Two groups added after the same anchor whose first keys are equal.
    b = {"m": {"z": 1, "a": 1}}
    lft = {"m": {"z": 1, "b": 1, "c": 1, "a": 1}}
    rgt = {"m": {"z": 1, "b": 1, "d": 1, "a": 1}}
    m1, _ = merged("coverage.json", b, lft, rgt)
    m2, _ = merged("coverage.json", b, rgt, lft)
    expect(list(m1["m"]) == list(m2["m"]), f"2: order depends on ours: {m1} {m2}")
    # 3. Both sides add a column: one header line, and the file is owed.
    t, o = merged(
        "types.tsv", "# c\nid\tx\nA\t1\n", "# c\nid\tx\ty\nA\t1\t\n", "# c\nid\tx\tz\nA\t1\t\n"
    )
    heads = [x for x in t.splitlines() if x.startswith("id")]
    expect(len(heads) == 1 and o, f"3: header lines merged as rows: {t!r} {o}")
    # 3. Both sides add a different comment line: owed, not a union of the two.
    t, o = merged("types.tsv", "# c\nid\tx\n", "# c\n# d\nid\tx\n", "# c\n# e\nid\tx\n")
    expect(not ("# d" in t and "# e" in t) and o, f"3: comment lines merged as a set: {t!r}")
    # 3. A value list inside a row that both sides changed is owed, not a union.
    m, o = merged(
        "coverage.json",
        {"expressions": [{"sql": "q", "switches": ["a"]}]},
        {"expressions": [{"sql": "q", "switches": ["a", "b"]}]},
        {"expressions": [{"sql": "q", "switches": ["a", "c"]}]},
    )
    expect(o == ["expressions.q.switches"], f"3: a row's switches merged as a set: {m} {o}")
    m, o = merged(
        "width_audit.json",
        {"widths": {"128": {"s": []}}},
        {"widths": {"128": {"s": ["x"]}}},
        {"widths": {"128": {"s": ["y"]}}},
    )
    expect(o == ["widths.128.s"], f"3: a shape's refusals merged as a set: {m} {o}")
    # 6. Rows whose keys happen to be sorted in a structure the writer does not sort.
    t, o = merged("types.tsv", "id\nA\nZ\n", "id\nA\nB\nZ\n", "id\nA\nC\nZ\n")
    expect(o == ["order after ('A',)"], f"6: coincidentally sorted rows were sorted: {o}")
    m, o = merged(
        "coverage.json",
        {"columns": {"a": 1, "d": 1}},
        {"columns": {"a": 1, "b": 1, "d": 1}},
        {"columns": {"a": 1, "c": 1, "d": 1}},
    )
    expect(o == ["columns (order after a)"], f"6: a map's place was not owed: {o}")
    # 18. Both sides add the same key after different base entries: its place is owed, and
    # the result is the same in both orders.
    b = {"m": {"z": 1, "a": 1}}
    lft = {"m": {"z": 1, "k": 1, "a": 1}}
    rgt = {"m": {"z": 1, "a": 1, "k": 1}}
    m1, o = merged("coverage.json", b, lft, rgt)
    m2, _ = merged("coverage.json", b, rgt, lft)
    expect(
        o == ["m (order of k)"] and list(m1["m"]) == list(m2["m"]),
        f"18: a key added at two places owed {o}: {m1} {m2}",
    )
    # 20. A TSV row holding a form feed or U+2028 stays one row, and so does one holding a
    # "\r" through the driver, which reads and writes the files without newline translation.
    t, o = merged(
        "types.tsv",
        "id\tx\nA\t1\nZ\t1\n",
        "id\tx\nA\t1\nB\tf\x0cg\u2028h\nZ\t1\n",
        "id\tx\nA\t1\nZ\t2\n",
    )
    expect(t == "id\tx\nA\t1\nB\tf\x0cg\u2028h\nZ\t2\n" and not o, f"20: {t!r} {o}")
    with tempfile.TemporaryDirectory() as tmp:
        paths = [os.path.join(tmp, n) for n in ("b.tsv", "o.tsv", "t.tsv")]
        for path, text in zip(
            paths, ("id\tx\nA\t1\nZ\t1\n", "id\tx\nA\t1\nB\tf\rg\nZ\t1\n", "id\tx\nA\t1\nZ\t2\n")
        ):
            with open(path, "w", encoding="utf-8", newline="") as f:
                f.write(text)
        with contextlib.redirect_stderr(io.StringIO()):
            rc = driver(*paths, "sql/varka/types.tsv")
        with open(paths[1], encoding="utf-8", newline="") as f:
            t = f.read()
        expect(rc == 0 and t == "id\tx\nA\t1\nB\tf\rg\nZ\t2\n", f"20: the driver wrote {t!r}")
    # 7. Both sides change a generated block, on different lines, so merge-file is clean.
    with tempfile.TemporaryDirectory() as tmp:

        def doc(one, two):
            path = os.path.join(tmp, f"{one}{two}.md")
            with open(path, "w", encoding="utf-8") as f:
                f.write(
                    "# Doc\n\n<!-- BEGIN generated counts -->\n"
                    f"| a | {one} |\n| b | 0 |\n| c | 0 |\n| d | {two} |\n"
                    "<!-- END generated counts -->\n"
                )
            return path

        o = Owed()
        text, real = merge_docs(doc(0, 0), doc(1, 0), doc(0, 1), o)
        expect(
            "| a | 1 |" in text and "| d | 1 |" in text and not real and o.paths,
            f"7: a block both sides changed was not owed: {o.paths}",
        )

        # 11. A block line that only starts like a conflict separator is content.
        def underline_doc(name, line):
            path = os.path.join(tmp, name)
            with open(path, "w", encoding="utf-8") as f:
                f.write(f"# Doc\n\n<!-- BEGIN generated g -->\n{line}\n<!-- END generated g -->\n")
            return path

        o = Owed()
        text, real = merge_docs(
            underline_doc("ub.md", "| a | 0 |"),
            underline_doc("uo.md", "========"),
            underline_doc("ut.md", "| a | 2 |"),
            o,
        )
        expect(
            "\n========\n" in text
            and "=======\n" not in text.replace("========\n", "")
            and not real,
            f"11: a line starting with ======= was read as the separator: {text!r}",
        )

        # 16. A hunk holding a block's END line on one side only (ours dropped it) leaves the
        # lines after it outside the block, so a later prose conflict keeps both sides.
        def end_doc(name, row, end, prose):
            path = os.path.join(tmp, name)
            with open(path, "w", encoding="utf-8") as f:
                f.write(
                    f"# Doc\n\n<!-- BEGIN generated g -->\n| a | {row} |\n"
                    + ("<!-- END generated g -->\n" if end else "")
                    + "\none\n\ntwo\n\nthree\n\nfour\n\n"
                    + prose
                    + "\n"
                )
            return path

        o = Owed()
        text, real = merge_docs(
            end_doc("eb.md", 0, True, "prose"),
            end_doc("eo.md", 1, False, "prose ours"),
            end_doc("et.md", 2, True, "prose theirs"),
            o,
        )
        expect(
            real and "prose ours" in text and "prose theirs" in text,
            f"16: a prose conflict after a hunk with an END line lost a side: {text!r}",
        )
        # 8. Any failure inside the merge falls back to git's text merge, never a traceback.
        saved = globals()["merge_text"]

        def boom(*args):
            raise RuntimeError("boom")

        globals()["merge_text"] = boom
        try:
            paths = [os.path.join(tmp, n) for n in ("b.json", "o.json", "t.json")]
            for n, path in enumerate(paths):
                with open(path, "w", encoding="utf-8") as f:
                    f.write(f'{{ "k" : {n} }}\n')
            rc = driver(*paths, "sql/varka/coverage.json")
            with open(paths[1], encoding="utf-8") as f:
                ok = rc != 0 and "<<<<<<<" in f.read()
        except Exception as e:
            ok = False
            print(f"self-test: 8: {e!r}", file=sys.stderr)
        finally:
            globals()["merge_text"] = saved
        expect(ok, "8: an unexpected error escaped the driver")
        # 9. The installer: an info/attributes without a final newline, installed twice.
        repo = os.path.join(tmp, "repo")
        # A global core.hooksPath, which the installer must neither read nor write through.
        hooks_path = os.path.join(tmp, "elsewhere")
        global_config = os.path.join(tmp, "global.gitconfig")
        with open(global_config, "w", encoding="utf-8") as f:
            f.write(f"[core]\n\thooksPath = {hooks_path}\n")
        saved_env = dict(os.environ)
        os.environ["GIT_CONFIG_GLOBAL"] = global_config
        try:
            env = fixture_env()
        finally:
            os.environ.clear()
            os.environ.update(saved_env)
        subprocess.run(["git", "init", "-q", repo], check=True, env=env)
        attributes = os.path.join(repo, ".git", "info", "attributes")
        os.makedirs(os.path.dirname(attributes), exist_ok=True)
        with open(attributes, "w", encoding="utf-8") as f:
            f.write("x merge=foo")
        for _ in range(2):
            subprocess.run(
                [os.path.join(root, "dev", "varka_precommit.sh"), "--install-hook"],
                cwd=repo,
                env=env,
                capture_output=True,
            )
        with open(attributes, encoding="utf-8") as f:
            lines = f.read().splitlines()
        expect(
            lines == ["x merge=foo", "docs/sql-varka.md merge=varka-generated"],
            f"9: the installer wrote {lines}",
        )
        # 10. The installer ran against the temporary repository's own config, not the user's.
        expect(
            os.path.exists(os.path.join(repo, ".git", "hooks", "pre-commit"))
            and not os.path.exists(hooks_path),
            "10: the installer followed the global core.hooksPath",
        )

        # 12. The installed driver finds the script through the worktree's top, from any
        # directory; where there is none it warns and runs a text merge. Inner merges of a
        # criss-cross merge take the text merge.
        def config(key):
            return subprocess.run(
                ["git", "config", key], cwd=repo, env=env, capture_output=True, text=True
            ).stdout.strip()

        def run_driver(cwd):
            paths = [os.path.join(tmp, n) for n in ("db.txt", "do.txt", "dt.txt")]
            for n, path in enumerate(paths):
                with open(path, "w", encoding="utf-8") as f:
                    f.write(f"{n}\n")
            command = config("merge.varka-generated.driver")
            for placeholder, value in zip(("%O", "%A", "%B", "%P"), paths + ["a path"]):
                command = command.replace(placeholder, "'" + value + "'")
            run = subprocess.run(
                ["sh", "-c", command], cwd=cwd, env=env, capture_output=True, text=True
            )
            with open(paths[1], encoding="utf-8") as f:
                return run, f.read()

        run, text = run_driver(repo)
        expect(
            run.returncode != 0
            and "<<<<<<<" in text
            and "plain text merge of a path" in run.stderr,
            f"12: the fallback merge (exit {run.returncode}) {text!r} {run.stderr!r}",
        )
        stub = os.path.join(repo, "dev", "varka_generated_merge.py")
        os.makedirs(os.path.join(repo, "dev"))
        os.makedirs(os.path.join(repo, "sub"))
        with open(stub, "w", encoding="utf-8") as f:
            f.write('#!/bin/sh\necho "stub $1 $5" >&2\n')
        os.chmod(stub, 0o755)
        run, _ = run_driver(os.path.join(repo, "sub"))
        expect(
            run.returncode == 0 and run.stderr == "stub driver a path\n",
            f"12: the driver did not run the worktree's script: {run.stderr!r}",
        )
        expect(config("merge.varka-generated.recursive") == "text", "12: no recursive driver")
    # 4. The per-row files the check reads run the docs job, and still count as code.
    sys.path.insert(0, os.path.join(root, "dev"))
    try:
        from sparktestsupport import modules
    finally:
        sys.path.pop(0)
    for rel in ("sql/varka/width_audit.json", "sql/varka/emitted_bytes.json"):
        expect(
            modules.varka_change_scope([rel]) == "scoped" and modules.varka_docs.contains_file(rel),
            f"4: {rel} is {modules.varka_change_scope([rel])!r} to the build's precondition",
        )
    # 5. The width audit owed on another CPU is skipped with a reason and the rest still runs;
    # the CPU's name is trimmed as the suite trims it.
    with open(os.path.join(root, "sql", "varka", "width_audit.json"), encoding="utf-8") as f:
        host = json.load(f)["host"]["cpu"]
    with tempfile.TemporaryDirectory() as tmp:
        for cpu, skipped in (("Another CPU", True), (host + "  ", False)):
            info = os.path.join(tmp, "cpuinfo")
            with open(info, "w", encoding="utf-8") as f:
                f.write(f"processor\t: 0\nmodel name\t: {cpu}\n")
            run = subprocess.run(
                [os.path.join(root, "dev", "varka_regen_generated.sh"), "--all", "--dry-run"],
                cwd=root,
                env={**os.environ, "VARKA_CPUINFO": info},
                capture_output=True,
                text=True,
            )
            out = run.stdout + run.stderr
            expect(
                run.returncode == 0
                and ("owed on its host" in out) == skipped
                and ("VarkaWidthAuditSuite" in out) != skipped
                and "VarkaEmittedBytesSuite" in out,
                f"5: on {cpu!r} the regeneration printed (exit {run.returncode}): {out}",
            )
    # 19. The JVM steps run under the heavy lock even where its file and directory do not
    # exist yet. The script runs in a fixture repository whose build/sbt reports whether the
    # lock is held, and whose merge driver and census are stubs.
    with tempfile.TemporaryDirectory() as tmp:
        env = fixture_env()
        subprocess.run(["git", "init", "-q", tmp], check=True, env=env)
        stubs = {
            "dev/varka_regen_generated.sh": None,
            "dev/varka_generated_merge.py": (
                "import sys\nif sys.argv[1:] == ['--owed']:\n    print('sql/varka/types.tsv')\n"
            ),
            "dev/varka_census.py": "",
            "build/sbt": (
                '#!/bin/sh\nif flock -n "$VARKA_HEAVY_LOCK" true; then echo "sbt: unlocked"\n'
                'else echo "sbt: locked"; fi\n'
            ),
        }
        for rel, text in stubs.items():
            path = os.path.join(tmp, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            if text is None:
                with open(os.path.join(root, rel), encoding="utf-8") as f:
                    text = f.read()
            with open(path, "w", encoding="utf-8") as f:
                f.write(text)
            os.chmod(path, 0o755)
        lock = os.path.join(tmp, "locks", "heavy.lock")
        run = subprocess.run(
            [os.path.join(tmp, "dev", "varka_regen_generated.sh")],
            cwd=tmp,
            env={**env, "VARKA_HEAVY_LOCK": lock},
            capture_output=True,
            text=True,
        )
        expect(
            run.returncode == 0 and "sbt: locked" in run.stdout and os.path.exists(lock),
            f"19: the JVM steps ran outside the lock (exit {run.returncode}): "
            f"{run.stdout}{run.stderr}",
        )


def end_to_end(expect):
    """Two branches each add a row at the same place in a coverage file, a TSV and the docs
    file; merged through git with this driver in both orders, there are no markers and the two
    trees are identical. A prose conflict in the docs file keeps its markers."""
    script = os.path.abspath(__file__)
    with tempfile.TemporaryDirectory() as tmp:

        def git(*args, check=True):
            return subprocess.run(
                ["git", "-C", tmp, *args],
                capture_output=True,
                text=True,
                check=check,
                env=fixture_env(),
            )

        def write(rel, text):
            p = os.path.join(tmp, rel)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "w", encoding="utf-8") as f:
                f.write(text)

        def cov(rows):
            return (
                render_json({"expressions": [{"sql": r} for r in rows], "digest": "-".join(rows)})
                + "\n"
            )

        def docs(rows, prose):
            return (
                "# Doc\n"
                + prose
                + "\n<!-- BEGIN generated coverage table -->\n"
                + "".join(f"| {r} |\n" for r in rows)
                + "<!-- END generated coverage table -->\n"
            )

        git("init", "-q", "-b", "master")
        git("config", "user.email", "t@example.com")
        git("config", "user.name", "t")
        git("config", "commit.gpgsign", "false")
        git("config", "core.hooksPath", "/dev/null")
        # A clone whose conflict style is diff3 must merge the same way.
        git("config", "merge.conflictStyle", "diff3")
        git(
            "config",
            "merge.varka-generated.driver",
            f"{sys.executable} {script} driver %O %A %B %P",
        )
        write(
            ".gitattributes",
            "c/coverage.json merge=varka-generated\n"
            "c/types.tsv merge=varka-generated\nd/doc.md merge=varka-generated\n",
        )
        write("c/coverage.json", cov(["a", "z"]))
        write("c/types.tsv", "id\tx\nA\t1\nZ\t1\n")
        write("d/doc.md", docs(["a", "z"], "prose"))
        git("add", "-A")
        git("commit", "-qm", "base")
        for name, row in (("left", "b"), ("right", "c")):
            git("checkout", "-qb", name, "master")
            write("c/coverage.json", cov(["a", row, "z"]))
            write("c/types.tsv", f"id\tx\nA\t1\n{row.upper()}\t1\nZ\t1\n")
            write("d/doc.md", docs(["a", row, "z"], "prose"))
            git("commit", "-qam", name)
        trees = []
        for first, second in (("left", "right"), ("right", "left")):
            git("checkout", "-q", "-B", "m-" + first, "master")
            git("merge", "-q", "--no-ff", "--no-edit", first)
            git("checkout", "-q", "-B", "s-" + second, second)
            merged = git("merge", "--no-edit", "m-" + first, check=False)
            expect(
                merged.returncode == 0,
                f"{second} merging master+{first}: {merged.stdout}{merged.stderr}",
            )
            expect(
                not git("diff", "--name-only", "--diff-filter=U").stdout, "an unmerged path is left"
            )
            trees.append(git("rev-parse", "HEAD^{tree}").stdout.strip())
        expect(len(set(trees)) == 1, "the two orders give different trees")
        with open(os.path.join(tmp, "c", "types.tsv"), encoding="utf-8") as f:
            expect(f.read() == "id\tx\nA\t1\nB\t1\nC\t1\nZ\t1\n", "the merged TSV")

        # 15. The committed merge's owed files are live.
        def owed_now(*extra):
            return subprocess.run(
                [sys.executable, script, "--owed", *extra],
                cwd=tmp,
                env=fixture_env(),
                capture_output=True,
                text=True,
                check=True,
            ).stdout.split()

        expect("c/coverage.json" in owed_now(), f"15: the merge's debt is not live: {owed_now()}")
        # 14. Both sides add a coverage row at different places: every row merges exactly and
        # only the census is owed, which puts the file on no owed list.
        owed_list = os.path.join(tmp, git("rev-parse", "--git-path", "varka-regen-owed").stdout)
        owed_list = owed_list.strip()
        if os.path.exists(owed_list):
            os.remove(owed_list)
        for name, start, rows in (
            ("r0", "master", ["a", "m", "z"]),
            ("r1", "r0", ["a", "b", "m", "z"]),
            ("r2", "r0", ["a", "m", "n", "z"]),
        ):
            git("checkout", "-qb", name, start)
            write("c/coverage.json", render_json({"expressions": [{"sql": r} for r in rows]}))
            git("commit", "-qam", name)
        git("checkout", "-q", "r1")
        census = git("merge", "--no-edit", "r2", check=False)
        expect(
            census.returncode == 0
            and "owed for the census" in census.stderr
            and not os.path.exists(owed_list),
            f"14: a census-only debt owed the file: {census.stderr}",
        )
        # A prose conflict outside the generated blocks is a real conflict. Both sides also add
        # a coverage row, which owes the file.
        for name, row in (("p1", "b"), ("p2", "c")):
            git("checkout", "-qb", name, "master")
            write("d/doc.md", docs(["a", "z"], "prose " + name))
            write("c/coverage.json", cov(["a", row, "z"]))
            git("commit", "-qam", name)
        real = git("merge", "--no-edit", "p1", check=False)
        expect(real.returncode != 0, "a prose conflict was resolved silently")
        with open(os.path.join(tmp, "d", "doc.md"), encoding="utf-8") as f:
            expect("<<<<<<<" in f.read(), "the prose conflict lost its markers")
        # 15. The merge in progress owes coverage.json; once it is aborted, nothing is owed, and
        # the regeneration script's rewrite drops the stale entries.
        expect(owed_now() == ["c/coverage.json"], f"15: in progress, owed {owed_now()}")
        expect(owed_now("--keep", "c/coverage.json") == ["c/coverage.json"], "15: --keep")
        git("merge", "--abort", check=False)
        expect(owed_now() == [], f"15: after the abort, owed {owed_now()}")
        expect(
            owed_now("--keep", "c/coverage.json") == [] and not os.path.exists(owed_list),
            "15: the stale entries were kept",
        )
        # 17. A squash merge not yet committed owes its files, and nothing once it is reset.
        for name, row in (("q1", "b"), ("q2", "c")):
            git("checkout", "-qb", name, "master")
            write("c/coverage.json", cov(["a", row, "z"]))
            git("commit", "-qam", name)
        git("merge", "--squash", "q1", check=False)
        expect(owed_now() == ["c/coverage.json"], f"17: squash in progress, owed {owed_now()}")
        git("reset", "-q", "--hard")
        expect(owed_now() == [], f"17: after the squash is reset, owed {owed_now()}")
        # 17. A cherry-pick stopped on the prose conflict owes coverage.json; aborted, nothing.
        git("checkout", "-q", "p2")
        git("cherry-pick", "p1", check=False)
        expect(owed_now() == ["c/coverage.json"], f"17: cherry-pick stopped, owed {owed_now()}")
        git("cherry-pick", "--abort", check=False)
        expect(owed_now() == [], f"17: after the cherry-pick --abort, owed {owed_now()}")
        # 17. A rebase whose first pick owed coverage.json and committed cleanly, stopped on
        # its second pick's prose conflict, still owes it; aborted, nothing.
        git("checkout", "-qb", "x1", "master")
        write("c/coverage.json", cov(["a", "b", "z"]))
        git("commit", "-qam", "x1 row")
        write("d/doc.md", docs(["a", "z"], "prose x1"))
        git("commit", "-qam", "x1 prose")
        git("rebase", "p2", check=False)
        expect(owed_now() == ["c/coverage.json"], f"17: rebase stopped, owed {owed_now()}")
        git("rebase", "--abort", check=False)
        expect(owed_now() == [], f"17: after the rebase --abort, owed {owed_now()}")
        # 21. An entry whose OURS is not an ancestor of HEAD is rejected without the
        # first-parent walk, read in this process with the fixture's environment.
        with open(owed_list, "w", encoding="utf-8") as f:
            f.write(git("rev-parse", "p1").stdout.strip() + " -\tc/coverage.json\n")
        calls, saved_env, saved_cwd = [], dict(os.environ), os.getcwd()
        saved_git_out = globals()["git_out"]

        def recording_git_out(*args):
            calls.append(args[0])
            return saved_git_out(*args)

        globals()["git_out"] = recording_git_out
        try:
            os.environ.clear()
            os.environ.update(fixture_env())
            os.chdir(tmp)
            entries = owed_live()
        finally:
            os.chdir(saved_cwd)
            os.environ.clear()
            os.environ.update(saved_env)
            globals()["git_out"] = saved_git_out
        expect(
            entries == [] and "rev-list" not in calls,
            f"21: a stale entry was walked: {entries} {calls}",
        )


def main(argv):
    if len(argv) == 6 and argv[1] == "driver":
        return driver(*argv[2:])
    if argv[1:] in (["--check"], ["--check", "--no-audit"]):
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        return check(root, audit=len(argv) == 2)
    if argv[1:] == ["--owed"]:
        return owed_command()
    if len(argv) == 4 and argv[1:3] == ["--owed", "--keep"]:
        return owed_command(keep=argv[3])
    if argv[1:] == ["--self-test"]:
        return self_test()
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
