# VARKA-215: Port VarkaConditionCompiler to Java

## 1. Where this came from

Row 215 of `m7/PLAN.md`, from `m10/SCOPE.md` item 81: the second of the three family ports after
VARKA-214 (`m7/VARKA-214.md`), which followed the recipe VARKA-175 set for the interval family
(`m6/VARKA-175.md` 2.3, 2.4 and 9). The owner's rule since 4 October 2026 (`sql/varka/AGENTS.md`,
"Java code") applies to every Scala-to-Java port.

`VarkaConditionCompiler.scala` is 443 lines: the conditional arms (`IF`, `CASE WHEN`,
`coalesce`), the three-valued condition compiler (`compileCond`: comparisons, `IN` over dates,
the connectives, the validity predicates), the balanced `andFold` and `orFold` the facade folds a
filter's conjuncts and a split predicate's pieces with, `foldPick` for `greatest` and `least`, and
`rangeSet`, which recognises a disjunction of ranges over one column.

## 2. The admission check, done

A port has one admission check, that it changes nothing, and the proofs that decide it are those
of 214, fixed before the code:

* **The emitted bytes**: `VarkaEmittedBytesSuite` against the committed `emitted_bytes.json`, not
  regenerated.
* **The coverage table**: `VarkaCoverageSuite` and `coverage.json` byte-identical. The suite scans
  each compiler file's patterns for the Catalyst classes it admits; the class names this file
  matches, `If`, `CaseWhen`, `Coalesce`, the five comparisons, `In`, `InSet`, `And`, `Or`, `Not`,
  `IsNull`, `IsNotNull`, `RuntimeReplaceable`, must all still be found in the Java form.
* **The chain**: `VarkaFamilyChainSuite`, no node claimed by two families.
* **The compiler's own suites**: `VarkaExpressionCompilerSuite`, `VarkaRangeSetCompilerSuite` (the
  one test that calls `rangeSet` directly) and the fuzzers that compose conditions.
* **The decline texts**: every string constant of the Scala file found in the Java file, by a
  comparison of the two.
* **Emission times**: two runs of master and two of the port of `VarkaEmissionBenchmark`, the same
  morning on an idle machine, both widths. *Correction, made while measuring:* this bullet first
  said the benchmark reaches the family, because it emits a predicate shape. It does not: it
  builds IR by hand and times only the emitter, so it cannot see this class (see 9, and the
  correction to 214 in `VARKA-214.md` 9.1). The instrument that does is
  `VarkaCompileBenchmark`, added with this port.

Baseline, taken on `origin/master` at `fe857dc6d4a` on 8 October 2026 before the port, on the idle
laptop with `dev/varka_bench_regen.sh catalyst VarkaEmissionBenchmark` (both widths, twice, the
results kept outside the repository and the committed files restored). The second run is 8
minutes after the first, and the two differ by up to 5% on a row: at 256 bits one row reads
4% slower the second time; at 128 bits nine rows read 3 to 5% *faster*, mostly the kernel-wide
rungs, as if the first run were still warming the machine. So the noise to clear is 5% on a
row, and the direction of a difference matters less than whether both port runs show it against
both master runs.

One caveat on the baseline. The Java file was written into the worktree while the baselines were
queued, so the second baseline and the first one's 128-bit half compiled it beside the Scala
object. Nothing referenced it (the facade still called the Scala object), so what ran was
master's code; the extra class is not a code path.

## 3. The design

### 3.1 The seam

214's, unchanged: one package-private `final class VarkaConditionCompiler` in the facade's
package; `static VarkaFamilyArm arm(...)`, a `switch` over the node that returns the lowering as
a deferred call or `null`; the facade's `familyChain` entry becomes
`javaFamily(VarkaConditionCompiler.arm(_, inputs, literals, sink))`; `scala.Option` and
`LinkedHashMap<?, ?>` stay at the boundary; the Scala file is deleted.

The class has more entry points than 214's, because the facade and a test call it directly:

| entry | caller | Java form |
|---|---|---|
| `compileCond` | the facade's predicate pass | `Option<Cond> compileCond(Expression, LinkedHashMap<?, ?>, LinkedHashMap<?, ?>, DeclineSink)` |
| `andFold`, `orFold` | the facade | take a `scala.collection.immutable.Seq<Cond>`, which is what its call sites pass |
| `foldPick` | `greatest` and `least` in the facade | takes the `scala.Function2` the facade passes |
| `rangeSet` | the arm, and one test | a Java record result: see 3.2 |

### 3.2 What is deliberately unchanged, and what moves

* **Evaluation order and short-circuiting.** `IF` compiles its condition, then both branches,
  and only then asks whether the lanes agree. `CASE WHEN` compiles *every* branch and the ELSE
  even after one declines, in query order, and only then checks the lanes, in the order
  `cond0, value0, cond1, value1, ..., else`, which fixes which two lanes a mismatch note names.
  `compare` compiles the left operand and stops if it declines. Each of these fills the input and
  literal tables or notes a decline, so each is written as early returns in source order, with
  no argument computed ahead of its turn.
* **The decline texts**, constant for constant, and the fall-through to "unsupported predicate".
* **The sort and the cap of an `IN` list**: distinct, sorted, at most `MaxInLiterals` (16),
  folded balanced, so the literal slots and the shape hash keep their order.
* **`rangeSet`'s result.** It is called by one Scala test as `rangeSet(e).map(_._2)`, a Scala
  tuple. In Java it returns an `Optional<RangeSet>`, a record of the column and the bounds, and
  the test's one helper reads the record. Its internals become small records (`Range`, `Side`)
  in place of tuples of options; the arithmetic, the strict-bound step, the sort and the merge
  are the same.
* **`sameLane`** was used only inside this file and becomes private.

The pattern matching over Catalyst's case classes is `instanceof` and type patterns, the stated
exception to the sealed-switch rule of `sql/varka/AGENTS.md`, as in 214.

### 3.3 Registered op counts

None move. The proof is `emitted_bytes.json` unchanged.

## 4. Files

| file | what |
|---|---|
| `sql/catalyst/src/main/java/.../codegen/VarkaConditionCompiler.java` | the port |
| `sql/catalyst/src/main/scala/.../codegen/VarkaConditionCompiler.scala` | deleted |
| `sql/catalyst/src/main/scala/.../codegen/VarkaExpressionCompiler.scala` | the `"condition"` chain entry |
| `sql/catalyst/src/test/scala/.../codegen/VarkaRangeSetCompilerSuite.scala` | reads `rangeSet`'s record |
| `sql/catalyst/src/test/scala/.../varka/VarkaCoverageSuite.scala` | the file moves to the Java list |
| `sql/varka/plans/m7/PLAN.md`, `VARKA-215.md` | the row and this plan |

## 5. Tests, and what each is for

No test is added; the oracles of 214 are the proof, plus `VarkaRangeSetCompilerSuite`, which is
the only one that exercises `rangeSet`'s arithmetic directly (bounds, strict steps, merging,
clamping at the int extremes) and so the only one that would catch a slip in its port.

## 6. The measurement

`VarkaEmissionBenchmark`, regenerated with `dev/varka_bench_regen.sh catalyst
VarkaEmissionBenchmark` on an idle machine: two runs of master, then two of the port, both widths,
compared with `dev/varka_bench_diff.py`. The files are not committed: the committed file is
master's from 29 September and reads about a third slower than master does today, so regenerating
it would move every row of a benchmark this task did not change (214 9).

### 6.1 Predictions, registered before the run

1. **The bytes do not move.** The rule that decides the task.
2. **No row of the emission benchmark is reproducibly slower than master**, at either width: a
   difference beyond the noise has to appear in both port runs against both master runs. The
   noise is the larger of 214's two readings, 6% on a row.
3. **The Java file is 480 to 560 lines against the Scala 443.** 214's came out 668 against 409
   (1.6 times), 175's 409 against 250; this file has more of its length in comments and in the
   `rangeSet` arithmetic, which become records but not more lines.

## 7. Risks

1. **Evaluation order.** A condition compiled early changes the input slot order or a decline
   text. The emitted bytes show the first and the decline comparison the second.
2. **`CASE WHEN` stops at the first decline** in a careless port, where the Scala compiles every
   branch. *Correction, made in review:* this is not observable and no test can catch it. The
   first decline note wins whichever branch compiles later, a declined entry's input and literal
   tables are rolled back by the caller, and the lane check, whose note does depend on the order,
   runs only when every branch compiled. The port compiles every branch to match the Scala
   exactly, and `emitted_bytes.json` shows any slot-order change in a shape that fuses.
3. **A class the coverage scan no longer finds**, dropping a Catalyst class from the published
   coverage without a failing test. The byte-identity of `coverage.json` catches it.
4. **`rangeSet`'s merge**, the one place with real arithmetic: sorting by lower bound must stay
   stable, and a strict bound must step before the clamp. `VarkaRangeSetCompilerSuite`.

## 8. Sequencing

1. This plan, and the row marked Planned.
2. The port with the call sites and the two test edits, in one commit.
3. The measurement and section 9.

## 9. Outcome

Done on 8 October 2026.

**The proof held.** `VarkaEmittedBytesSuite`, `VarkaCoverageSuite`, `VarkaFamilyChainSuite`,
`VarkaExpressionCompilerSuite` and `VarkaRangeSetCompilerSuite` pass (151 tests, one cancelled,
the opt-in option audit), with `emitted_bytes.json` and `coverage.json` unchanged and nothing
regenerated; the 44 Varka suites of `catalyst` pass (542 tests, 36 cancelled); and every decline
text of the Scala file is in the Java file.

**The emission benchmark could not measure this class.** It was run as planned, twice on master and
twice on the port. In all four port-against-master comparisons the 256-bit kernel-wide rows read 3
to 9% faster, growing with the output count, and the 128-bit file moved up to 10% between two runs
of the same tree. The cause is not the port: the benchmark never calls the compiler, which is also
what undid 214's reading of the same rows (`VARKA-214.md` 9.1), where they moved the other way.

**The measurement that does reach it.** `VarkaCompileBenchmark` times one `compile` or
`compilePredicate` call per shape. Its results are committed twice, both generated on the idle
laptop within minutes of each other: `VarkaCompileBenchmark-jdk25-master-results.txt` for master
at `fe857dc6d4a`, and `VarkaCompileBenchmark-jdk25-results.txt` for this port, each with its
128-bit companion and provenance. The 256-bit files, in nanoseconds a call:

| shape | master | port | less time by |
|---|---:|---:|---:|
| calendar projection of six | 12389 | 12334 | within noise |
| TIME projection of three | 10086 | 10117 | within noise |
| conditionals (IF, coalesce, greatest, a six-branch CASE WHEN) | 25972 | 23374 | see below |
| a projection of sixty mixed outputs | 563887 | 563576 | within noise |
| range, IS NOT NULL and an int comparison | 4301 | 3987 | 7% |
| IN over eight dates | 2823 | 2486 | 12% |
| a set of three date ranges | 2831 | 2196 | 22% |
| NOT, OR and IS NULL over two dates | 2306 | 2164 | 6% |

The conditionals row of the master file is an outlier: it is 10% above the five other runs of
master made that day, so it is not read as a speed-up. Every difference here is under 1.3 times,
so the table is not the evidence alone: the minimum over six runs of master and three of the port
(the repo's rule for such ratios) gives the same picture at both widths, the projections within
3% of each other and the four predicate shapes taking 4 to 28% less time, the set of ranges the
most. The projections reach the arms only and do not move; the predicates reach `compileCond`
and `rangeSet` and compile faster.

**What this took, and what the first reading got wrong.** In that day's runs, which are not
committed, the plain port was already faster on the predicates. Two steps made it faster still, each measured against master in an
interleaved pair: `compileCond` as a chain of `instanceof` tests in place of a pattern `switch`,
with `rangeSet` run once where the Scala ran it twice, and loops and a sorted set in place of
streams in `rangeSet` and `compileInList`. The results were first read with the sign reversed
(the diff tool's change column is a rate), and the "fixes" were first thought to be curing a 40%
slowdown; the absolute times, read at last, showed they were widening a speed-up. A scratch loop
that compiled one predicate over and over under JFR, not committed, agreed with the direction: the
range set took about a third less time per call than on master, and the IN list about a seventh
less.

**Predictions scored.**

1. **Held.** The bytes do not move.
2. **Not scorable on the emission benchmark, held on the right one.** No shape compiles
   reproducibly slower; four of the eight compile faster, by the minimums over the runs.
3. **Refuted.** The Java file is 789 lines against the Scala 443 (1.8 times), not 480 to 560. 214's
   was 1.6 times. The `rangeSet` records, the early returns in `CASE WHEN` and the loops that
   replaced the streams account for it.

**Review of the PR** (`/code-review high`) found no behaviour difference from the Scala, and
these: the mixed-lane decline text was written in two places (now one); `andFold` and `orFold` ran
over a view of a Scala list whose `get(i)` is linear (now a copy); `foldPick` used a stream (now
a loop); a comment in the code carried a performance claim and narrated the code it replaced (now
the code as it is, the claim here); and risk 2 below claimed tests the suite does not have.

**Left for later.** Row 216 follows. `VarkaCompileBenchmark` serves 216, 217 and 222 (which asks
for "compile time measured before and after"). A sixteen-branch `CASE WHEN` does not fuse as a
whole, which is why the benchmark's has six; that is the compiler's cap, not this task's.
`compileCond` asks `rangeSet` at every nested `OR`, so a long `OR` chain that is not a range set
is re-flattened at each level and costs quadratic time; the Scala did the same, a failure cannot
simply be remembered for the tree (a sub-disjunction may be a range set where its parent is not),
and it is left to a row if a query ever shows it.

**A finding on the way.** `VarkaWarmupEndToEndSuite`'s "a projection's first query takes the row
path and its next one the compiled kernel" failed on the fork's CI in two of the first five runs
this morning (#663's first run, #666's), each time with the warm-up in state `RELEASED` after
about sixty seconds and never `COMPILED`, and passed on a rerun. The branches under test did not
touch it. It is row 295.
