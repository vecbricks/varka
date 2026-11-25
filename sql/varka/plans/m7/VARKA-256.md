# VARKA-256: Regenerate one benchmark section, and run a pair against the merge base

## 1. Where this came from

Row 256 of `m7/PLAN.md`, from `m10/SCOPE.md` item 72 and `m7/READING.md` 9. A runner regeneration of
a benchmark class rewrites its whole results file on whichever CPU the pool assigns, so a PR that
adds one section replaces every other section's numbers with that machine's: VARKA-200's run on an
EPYC 7763 rewrote `VarkaWideKernelBenchmark`'s VARKA-190 sections, measured on an EPYC 9V45, and
they were spliced back by hand (30 September 2026). The row adds DuckDB's method: a PR benchmarked
against its merge base in alternating batches, medians compared, a threshold, an early stop.

## 2. The admission check, done

Checked on 9 October 2026 on `master` (`996542e6a7e`):

* **The section is the unit already.** Spark's `BenchmarkBase.runBenchmark(name)` writes a rule of
  96 `=`, the name, a rule, and the results; `runBenchmark` is `final`, in Spark's own test
  sources, so a run-time filter would mean changing Spark's base class or editing the 31 Varka
  benchmark classes. A splice on the written file needs neither: the section rules are the
  boundaries. The cost is that the whole class still runs; the gain is that the committed bytes of
  every other section are untouched.
* **The existing tools parse what a pair needs.** `varka_bench_diff.py` keys a row by (table,
  case, occurrence) and reads a cold case's change from its time a row (`rate_change`), because a
  rate prints 0.0; the pair comparator reuses both.
* **A regeneration on one machine reads unchanged rows as moved.** Recorded in VARKA-77: 32.9% of
  rows of an unchanged file moved by more than the flat threshold at AVX-512 across two
  regenerations. That is the problem the alternating pair removes; the band files (VARKA-77, 90)
  are the other half and are unchanged.

What the check would have rejected: a run-time section filter in Spark's `BenchmarkBase` (a change
to a base class of the whole project for a convenience of this one) and one results file per
section (it moves every consumer of the file names, and the plans quote the file names).

## 3. The design

### 3.1 `dev/varka_bench_sections.py`

`list FILE`; `splice OLD NEW --sections ...` (OLD with the named sections from NEW, in OLD's order,
a section OLD lacks appended, everything else OLD's bytes); `restore --sections ...` (for each
changed results file, HEAD's version as OLD and the working tree as NEW, written back, a file with
no committed version left alone). A named section the new file lacks is an error that lists the
titles it has. A self-test covers replacing, appending, the empty set, the error, and a table's
dashes not opening a section.

### 3.2 `--sections` for `dev/varka_bench_regen.sh` and `sections` for the workflow

`--sections "a|b"` splices after the wide run, records the list in the provenance file, and implies
`--no-narrow`: the companion has no section rules. The benchmark workflow gains a `sections` input
and runs `restore` before it tars the results, so the commit holds only the named sections.

### 3.3 `dev/varka_bench_pair.sh` and `.py`

Runs the class on the merge base (a worktree it makes, or `--base-dir`) and the head, N rounds
(default 5) with the order swapped every round, wide width only, through the regeneration script's
idle check and canary; copies each result aside and restores the committed files. After at least
three rounds it stops when every row is within 3% of the other side. It prints each row's change in
the median rate (+ faster) and exits 1 if any is slower by 10%.

### 3.4 What is deliberately unchanged

The benchmark classes, Spark's `BenchmarkBase`, the band tools and the gate, and the narrow
companion's format.

### 3.5 Registered op counts

None.

## 4. Files

| file | what |
|---|---|
| `dev/varka_bench_sections.py` | the splice and restore |
| `dev/varka_bench_regen.sh` | `--sections` |
| `dev/varka_bench_pair.sh`, `dev/varka_bench_pair.py` | the pair |
| `.github/workflows/benchmark.yml` | the `sections` input |
| `sql/varka/skills/benchmarking.md` | the note |

## 5. Tests, and what each is for

* **`varka_bench_sections.py --selftest`** and **`varka_bench_pair.py --selftest`**: the splice's
  byte-for-byte guarantee, the comparator's medians (one disturbed batch does not move a median, a
  row on one side only is not compared, a cold case reads from its time).
* **A scratch-repository test of `restore`**: a regeneration that changed all 57 lines of a real
  file on another machine, restored to the two lines of the one named section.
* **A real splice of a file onto itself** is byte-identical.
* **A real paired run** (3 rounds of `VarkaCompileBenchmark` against a built sibling worktree
  at the same code) and **a real `--sections` regeneration**, below.

## 6. The measurement

The paired run on code that is the same on both sides is its own control: every row's median
change should be inside the noise, and none slower by 10%.

### 6.1 Predictions, registered before the runs

1. The paired run of identical code exits 0, and every row's median change is within 8%.
2. A `--sections` regeneration of one section leaves every other section byte for byte as
   committed (`cmp` of the other sections is empty), and the changed section's CPU line is this
   machine's.
3. The pair costs N times a regeneration's wall time per side; three rounds of the compile
   benchmark take under 25 minutes.

## 7. Risks

1. **The base worktree needs a build**, so the first round of a default `--base` is slow; the
   script says so and `--base-dir` avoids it.
2. **A section title with a comma** cannot be named in the comma-separated list.
3. **The narrow companion is not spliced**, so a PR that changes a narrow-only section regenerates
   that file whole.

## 8. Sequencing

One commit: the tools with their self-tests, the workflow input and the note; then the runs.

## 9. Outcome

Done on 9 October 2026.

**The splice.** A real `--sections "emitting a fused kernel: bytes only"` regeneration of
`VarkaEmissionBenchmark` replaced that section (8 of the file's lines) and left the other two
sections of the file byte for byte as committed; the provenance file gained a `sections:` line.
The regenerated numbers were discarded (`git checkout`): the run was the test of the mechanism and
no result of this row. A scratch-repository test of `restore` took a regeneration that changed all
57 lines of the parity file on another machine back to the two lines of the one named section.

**The pair.** Three rounds of `VarkaCompileBenchmark` on a base (the PR 297 branch) and the head
(master), code identical on both sides, the order swapped each round: every row's median change
within 4.1% (+0.3 to +4.1 for the cold ones, -0.1 to +1.9 for the rest), none slower or faster by
10%, exit 0, and 14 minutes for six runs.

**Predictions scored.**

1. **Held.** Exit 0, all eight rows' median changes within 4.1%.
2. **Held.** The other sections are identical; the named one is this machine's.
3. **Held.** Fourteen minutes for three rounds, under 25.

**What the first attempts showed, which the driver now handles.** The regeneration script refuses
a machine whose one-minute load is above 1.0 or whose canary is off its baseline. Both happen for a
minute or two after the previous benchmark JVM exits (the cache loop read 38 to 41% off), and the
canary's own run raises the load. The first three attempts of the pair failed on those refusals
between the base and the head. The driver now waits for the load to fall under 0.8 before each side
and retries a refusal five times a minute apart; any other failure ends the pair. A pair that
cannot get a settled machine says so and does not publish numbers from an unsettled one.

**Not done.** The 128-bit companion cannot be spliced (it has no section rules), so
`--sections` skips it; the section filter does not stop the rest of the class from running;
and the workflow's `sections` input is untested on a runner (it needs a dispatch, which is the
owner's to trigger).

### 9.1 Review of #683 (`/code-review high`), 9 October 2026

Findings fixed in the PR. **The separator was wrong, and the plan had called it rare**: 126 of the
480 committed section titles hold a comma, so `--sections` named by commas could not name most
sections of most classes (the usage example's own class has seven); titles are separated by `|` now
(none holds one). `restore` swallowed a misspelt title and the workflow went green with nothing kept;
it now fails when no regenerated file holds a requested title or one holds only some of them (a
file holding none, the 128-bit companion or another class's under a wildcard, goes back to HEAD).
A failed splice in `varka_bench_regen.sh` left the whole run on this machine in place of the
committed file; it restores the file. Sections are matched by title with the occurrences of a repeated
title replaced together, and a title named twice is placed once. `varka_bench_pair.sh` aborted when
the base ref did not track the results or provenance file, and left both trees dirty on a failure or
an interrupt; both files are restored on any exit (checked out where tracked, removed where not), the
merge base is read after a fetch, and the script says a new base worktree needs a build.
`varka_bench_pair.py` exited 0 with nothing compared; it exits 2 then, and warns about rows on one
side only. Risk 2 of section 7 ("a title with a comma") was the common case.

