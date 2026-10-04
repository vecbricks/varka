# VARKA-100: refuse an `--only` run that would replace a committed results file

*Milestone 5, section 2.35. Opened 14 September 2026, found while running VARKA-97's
measurement; started 15 September 2026.*

## 1. Where this came from

`dev/varka_bench_surface.sh --only <regex>` writes `<STEM>-<label>-results.txt` for
the entries it ran. It does not merge into the existing file and it does not refuse:
the file is replaced. A three-entry partial run under a committed label turns that
label's fifty-two-entry coverage table into a three-entry one, and `git status`
reports an ordinary modification.

This happened during VARKA-97. Its Varka arms used custom labels and wrote new files,
but its two stock arms were passed under the canonical `spark-4.2.0-jdk17` and
`spark-4.2.0-jdk25` names and truncated both committed files. They were restored
before staging, and only because the working tree was read before the commit rather
than after - which is luck standing in for a guard.

## 2. The admission check, done

*Does the sharded path have this problem too?* No, and that is the precedent.
VARKA-62 gave `--shard I/N` a filename suffix (`-shard1of4`) precisely so shards of
one run could share a directory without overwriting each other, and
`dev/varka_bench_merge.py` joins them back. `--only` never got the same treatment,
and it is the flag a person reaches for by hand.

*Can the script tell how many entries a run will write?* Not without asking the
driver, which owns the inventory. That decides the shape of the check below: it
refuses on the existence of a committed file rather than on a count comparison,
because the cost of a false refusal is typing a different label and the cost of a
missed one is a silently truncated coverage table.

*Is `--force` the right override?* No. `--force` today means "the machine is not in
its measured state, run anyway" - it is about measurement validity. Someone forcing
past a busy laptop is common; someone wanting to replace a committed table is not,
and coupling the two would silently remove this guard from the common case.

## 3. The design

### 3.1 The mechanism

A pre-flight check over every label, **before the first arm runs**. With `--only`
set and `--shard` unset, a label whose results file is tracked by git is refused:

    varka-jdk25: --only would replace the committed 52-entry
      sql/varka/bench/benchmarks/DateSurface-varka-jdk25-results.txt
      with this run's subset, which is how a coverage table becomes three rows.
      Use a label of your own, or --shard I/N (its files carry a suffix and merge
      with dev/varka_bench_merge.py), or --replace to overwrite deliberately.

Three things about that shape are deliberate:

* **Before the first arm**, not inside the per-arm loop. Failing on the fourth arm
  after three hours is barely better than not failing.
* **Tracked by git** is the test for "committed". An untracked file is this run's
  own scratch and replacing it harms nothing, so the guard stays out of the way of
  the scratch-label workflow that VARKA-97 actually used.
* **`--replace`, not `--force`.** The two overrides stay independent for the reason
  in section 2.

### 3.2 What is deliberately unchanged

`--shard` keeps its suffix and is not subject to the check: its filenames cannot
collide by construction. A full run - no `--only` - still replaces its file, because
that is what regenerating a results file *is*.

### 3.3 Registered op counts

Not applicable.

## 4. Files

`dev/varka_bench_surface.sh` - the pre-flight block, the `--replace` flag, and the
usage text that documents both.

## 5. Tests, and what each is for

The script has no suite; the checks are run by hand against the real committed files
and recorded in section 9:

* `--only` under a committed label is refused, and the committed file is still
  fifty-two entries afterwards.
* `--only` under a label with no committed file runs - the scratch workflow that
  VARKA-97 used must keep working.
* `--only --shard 1/4` is not refused, because the suffix already separates it.
* `--only --replace` is not refused.
* No `--only` is not refused.

## 6. The measurement

None. The guard runs before any measurement and costs one `git ls-files` per label.

### 6.1 Predictions, registered before the run

Not a measurement task. The one thing worth predicting: re-running VARKA-97's exact
command line - its two stock arms under canonical labels with `--only` - now fails
at the start instead of truncating two committed files.

## 7. Risks

* **A false refusal in a workflow that wants replacement.** `--replace` is the way
  out and the message names it.
* **`git ls-files` in a checkout without git.** The script already runs `git` for the
  commit provenance, so this adds no new dependency.

## 8. Sequencing

1. The pre-flight block and the flag.
2. The five checks in section 5, against the real committed files.
3. The milestone row and the outcome.

## 9. Outcome

*Implemented and checked 15 September 2026.*

The five checks of section 5, run against the real committed files with `--force` so
the machine checks do not mask the result, and counting occurrences of the refusal
message:

| case | guard fires | expected |
| :--- | ---: | ---: |
| `--only` under the committed `varka-jdk25` | 1 | 1 |
| `--only` under a label with no committed file | 0 | 0 |
| `--only --shard 1/4` under a committed label | 0 | 0 |
| `--only --replace` under a committed label | 0 | 0 |
| no `--only` | 0 | 0 |

`git status sql/varka/bench/benchmarks/` reports nothing modified afterwards, and the
committed file still holds its fifty-two entries. The refusal reads:

    varka-jdk25: --only would replace the committed 52-entry
      sql/varka/bench/benchmarks/DateSurface-varka-jdk25-results.txt
      with this run's subset, which is how a coverage table becomes three rows.
      Give the run a label of its own, or use --shard I/N (its files carry a suffix
      and merge with dev/varka_bench_merge.py), or pass --replace to overwrite it.

**One thing moved during implementation.** The guard was first placed immediately
before the per-arm loop, which is where section 3.1 said "before the first arm runs".
Running it showed why that is not early enough: the load and canary checks sit above,
so on a machine at load 1.01 the run refused for the machine's state and said nothing
about the file, and the operator would learn about the truncation risk only after
passing `--force` and waiting ten seconds for a canary. The guard reads two arguments
and one git index entry; it belongs above both, and that is where it is. The comment
in the script says so, since the ordering is the kind of thing a later edit would undo
without noticing.

**A note on how these checks were run**, because it cost a round trip. The first
harness built each case's flags into a shell variable and passed it unquoted, which in
zsh does not word-split, so every case received one argument reading `--only year` and
every case reported the guard silent - including the one that had fired correctly a
minute earlier. The checks above pass the flags literally. This is the same zsh
behaviour recorded in `sql/varka/skills/working-in-this-repo.md`, met in a new place.
