# VARKA-178: bands on demand, the rule and the tooling

*Row 178 of `m6/PLAN.md`, from `m8/SCOPE.md` item 49 and VARKA-145's finding.
Written 1 October 2026, when the row closed; most of what it asked for had happened on the side
by then, and this file records what, and what the row itself added.*

## 1. Where this came from

A regeneration's diff prints "moved 14.2%" against nothing. An unchanged file moves too, and by
a different amount per case - over ten runs the parity file's median case moves 5%, its 90th
percentile 22%, its worst 227% - so a flat threshold either flags a third of the file or misses
a collapse, which is how a kernel once fell from 273 to 8.8 M rows/s across three regenerations
with nobody remarking (`sql/varka/skills/benchmarking.md`, "A band says which moves to read").
The answer, from VARKA-77 on, is the band: a committed per-case tier measured from repeated runs
of the unchanged file, which `dev/varka_bench_regen.sh`'s diff reads by itself when the file is
there. `dev/varka_bench_repeat.sh` runs a benchmark N times pinned and `dev/varka_bench_band.py
--write` turns the runs into the band.

At the start of milestone 6 six of eighteen families had a band. VARKA-145 showed what that
costs twice over: an apparent 23% regression that was a 30.6% band, and a family that could not
be banded at all because the repeat script passed its module straight to sbt. The row asked for
the rule that had emerged by accident to be written down - *a family gets its band the first
time someone has to read a move in it*, rather than every family against a day that may never
come - and for banding a family to be one command. Its origin, item 49, owed two measurements
on a quiet machine besides.

## 2. What happened on the side

* Item 49's two measurements were taken on 24 September 2026: `VarkaArithmeticBenchmark`'s band
  at both widths, and `VARKA-63.md` 9.7's 26.1% withdrawn as evidence.
* The repeat script's module bug was fixed in VARKA-145, the first time a band needed it.
* The rule was followed without being written: fifteen families carry a band today, twenty
  band files with both widths for some - the size ladder, the cold start, the range filter, the
  method sizes and the shared prefix among this milestone's - each added when a move in it had
  to be read. Fifteen more results families have none, and nothing has needed one.

## 3. What the row adds, 1 October 2026

* **The rule, in `sql/varka/AGENTS.md`** under "Measurements, not adjectives": what a band is,
  that the regen diff reads it, when a family gets one (the first time someone has to read a
  move in it), the command that makes it, and that until then the flat threshold applies and a
  move read against it is read with that said.
* **The one command.** `dev/varka_bench_repeat.sh <module> <Class> 10 --band` with no path now
  writes the band where the regen diff looks for it,
  `sql/<module>/benchmarks/<Class>-jdk25-band.txt`, or the `-128bit-band.txt` companion under
  `--narrow`; a path after `--band` still writes elsewhere. Before, the caller had to know the
  regen script's naming and type it. The regen script's band passage names the repeat script as
  where a band comes from.
* Checked on `VarkaVectorApiProbeBenchmark` with two runs: the script resolves and writes the
  default path; the two-run band was not committed, since no move in that family has needed one.

**Done.** The row's done-when was the rule written and the tooling making a band one command;
both are here, and the fifteen banded families are the rule applied before it was written.
