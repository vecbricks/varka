# Task 125: a checksum per arm in the surface driver

*Written and built 18 September 2026. Section 2.60 of `PLAN_MILESTONE_5.md`
scoped this task on 16 September 2026, from the September surveys
(`SCOPE_MILESTONE_8.md` items 16 to 29, #223) and Raasveldt's pitfall 3.8.*

## 1. The hole

The surface driver checks a great deal about how a row was produced and nothing
about what it produced. Per shape it asserts that the plan carries a Varka node,
that no row-engine `Filter` or `Project` sits above it, that no batch fell back,
that some executor work actually ran, and that the fixed share is under its
ceiling. It never compares the arms' answers.

So a kernel that is fast and **wrong** publishes a rate like any other, and the
run that publishes it looks exactly like a run that did not. The differential
suites cover correctness over a thousand rows in a local session; they do not
cover the distribution, the row count, the partitioning, the cache format or the
Spark version the surface actually measures, and it is the surface's numbers
that reach the README and the public post.

There was a second, smaller instance of the same gap. `# selectivity:` was
already written per filter entry and already read by the table builder - through
`selected.update(...)`, which merges every arm into one dict and keeps the last.
The driver was therefore reading the same quantity from every arm and silently
discarding all but one, when comparing them costs a line.

## 2. What was built

**The checksum, in `DateSurfaceBenchmark`.** Per entry, once, outside the timed
loop, for each shape that produces rows:

    # checksum: projection rows=1000000000 nonnull=967741935 fold=483216677091,
               filter rows=241935658 nonnull=241935658 fold=192337740118

`rows` and `nonnull` catch the coarse failures; `fold` is
`sum(pmod(xxhash64(c), 1000000007))` over the shape's output column - the
projection's computed value, or the date a filter let through.

Three properties, each of which is a decision rather than an accident:

* **A sum, not an xor.** An xor cancels, and a date surface produces long runs of
  repeated values - `year(d)` over a year of dates is one number - so a pair of
  equal hashes would vanish and leave a checksum that agrees for the wrong
  reason. A sum of reduced hashes annihilates nothing.
* **Reduced before summing.** `pmod(..., 1000000007)` keeps every term under
  2^30, so the total cannot overflow an int64 within the driver's row range. A
  plain `sum(xxhash64(...))` would overflow at a few hundred million rows and
  either throw under ANSI or wrap - and a wrapping fold still compares equal
  between two arms that wrapped identically, which is a check that keeps passing
  while meaning less than it says. The row count above which the reduction stops
  being enough is computed rather than assumed, and a run above it is refused.
* **Order-independent.** Partitions finish in whatever order they finish in, so
  the fold is commutative and a test runs the same expression at two
  partitionings and requires the same answer.

**The comparison, in `dev/varka_bench_diff.py`.** It is the one place that sees
every arm. Before the table - not after, because a table printed above a
disagreement reads as the run's result - every pair of arms must agree on both
the checksum and the selectivity of every entry they both ran. A disagreement
names the entry and prints each arm's value, and exits non-zero.

Two details that keep the check usable rather than a nuisance:

* **Entries missing from an arm are skipped**, because `--only` and `--shard`
  runs are legitimate subsets. Arms are compared on what they have in common.
* **Comparing nothing is said out loud.** A results file written before this
  existed carries no checksum line, so an old arm beside a new one compares
  nothing - and a check that silently compares nothing is worse than no check,
  because the run looks identical either way. The tool prints a note and says to
  regenerate.

## 3. What it caught, and what it has yet to catch

Run against the two committed arms as they stand, the **selectivity** comparison
is live today and they agree. The **checksum** comparison prints its note,
because no committed file carries the line yet.

That is the task's one open end: section 2.60's "done when ... the committed
files carry the checksums" needs a surface regeneration, which is about an hour
and forty minutes per arm on an idle machine and is not something this change
can do for itself. Until then the machinery is in place, proven, and reports
honestly that it has not yet been given data to compare.

## 4. Tests

1. **The checksum separates two answers that count the same.** The failure being
   guarded against does not change the row count or the null count - an
   off-by-one kernel produces exactly as many rows, exactly as many non-null, and
   different values - so the test asserts the other two fields are *equal* and
   only the fold differs.
2. **The checksum separates two filters of equal selectivity**, which is what the
   selectivity line beside it cannot do: the right number of rows and the wrong
   ones.
3. **The fold does not depend on partition order**, at two partitionings of one
   table.
4. **The comparison fires, on real committed data.** A committed results file
   with one selectivity altered by seven rows makes the tool name the entry,
   print both arms' values and exit 1; the unaltered pair exits 0 and prints the
   table. That is the "deliberately wrong kernel fails the run" clause of 2.60,
   at the only level a test can reach it without a two-hour run.
5. **The selftest** covers the keying (the comment is attributed to the table
   above it), agreement, disagreement, and the empty-intersection case.

## 5. What this does not do

* **Regenerate the committed surface files**, section 3.
* **Check the chains benchmark**, which shares the driver but whose entries are
  composed expressions; the same helper applies and should land with the next
  chains regeneration rather than adding an unverified line to a second file.
* **Compare against a stored expected value.** The check is arm against arm, so
  two arms that are wrong in the same way still agree. That is the right scope
  here - the arms are different engines, and the differential suites are what
  hold either of them to a definition - but it is worth saying so rather than
  letting "checksum" imply more than it does.

## 6. Outcome

Landed 18 September 2026. The driver now fails rather than publishes when two
arms disagree about what they computed, and the selectivity half of that is
already active on the committed files.
