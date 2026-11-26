# VARKA-214: Port VarkaTimeCompiler to Java

## 1. Where this came from

Row 214 of `m7/PLAN.md`, from `m10/SCOPE.md` item 81 (moved to milestone 7 on 2 October 2026):
"the rest of the Java port, mechanical and meant for an agent". Milestone 6's VARKA-175 ported
the year-month interval family, `VarkaIntervalCompiler`, as the experiment that settled whether a
compiler family's matching over Catalyst's expressions reads as well in Java as in Scala, and
recorded the recipe (`m6/VARKA-175.md` 2.3, 2.4 and 9): the time family is next, then the
condition family (row 215) and the calendar family (row 216). The owner's rule since 4 October
2026 (`sql/varka/AGENTS.md`, "Java code") applies to every Scala-to-Java port.

`VarkaTimeCompiler.scala` is 409 lines: the long-lane leaves and casts, the TIME functions
matched through `timeTargets`, and the helpers that state each division's bound.

## 2. The admission check, done

A port has one admission check, that it changes nothing, and the proofs that decide it are fixed
before the code:

* **The emitted bytes.** `VarkaEmittedBytesSuite` against the committed `emitted_bytes.json`,
  not regenerated. The strongest single check: every shape the oracle emits, at both widths,
  must produce the same class bytes.
* **The coverage table.** `VarkaCoverageSuite` and `coverage.json` byte-identical. The suite scans
  each compiler file's patterns for the Catalyst classes the compiler admits (see 3.3).
* **The chain.** `VarkaFamilyChainSuite`: no node is claimed by two families, asked through
  `isDefinedAt` over the coverage table.
* **The compiler's own suites.** `VarkaExpressionCompilerSuite` (the TIME sections and
  `timeAddIntervalTruncates`) and the `timeTargets` check in it.
* **Emission times**, VARKA-191's: `VarkaEmissionBenchmark` regenerated on master before any
  change and again after, the same day on an idle machine, and the two compared row by row
  against the controls (6).
* **Decline reasons.** The text of every decline is compared against the Scala file's by a diff
  of the string constants, since EXPLAIN output and tests read them (7.2).

Baseline, taken on `origin/master` at `5407df066ee` on 8 October 2026 before any edit, on the
idle laptop with `dev/varka_bench_regen.sh catalyst VarkaEmissionBenchmark` (both widths, the
results kept outside the repository and the committed files restored). Against the committed
file of 29 September the whole benchmark reads 9 to 35% faster on the day (one row, the 400
outputs kernel-wide rung, 8% slower), every row together, so the same-day pair below is the only
comparison that means anything and the committed numbers are not the baseline.

What the measurement can and cannot show: the benchmark has no TIME shape. Its shapes are date
and int expressions, so it never compiles a node through this family's arms; what it does measure
is the chain, which `compileNode` rebuilds and walks for every node. The port changes the chain's
`"time"` entry from a Scala partial function to the Java adapter, so the emission times are a
check on that entry's cost per node and not on the lowering of a TIME expression. The lowering's
proof is the emitted bytes.

## 3. The design

### 3.1 The seam

The recipe is VARKA-175's, unchanged, and this task adds no decision to it:

* One package-private `final class VarkaTimeCompiler` in
  `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/`, the facade's
  package, with a private constructor and static members. The Scala file is deleted.
* `static Arm arm(Expression, LinkedHashMap<?, ?>, LinkedHashMap<?, ?>, DeclineSink)`: a `switch`
  over the node whose cases test and deconstruct and return the lowering as a deferred call, or
  `null` for a node the family does not claim. `familyChain`'s `"time"` entry becomes
  `javaFamily(VarkaTimeCompiler.arm(_, inputs, literals, sink))`, the `"interval"` entry's form.
  Testing has no side effects, so `VarkaFamilyChainSuite`'s `isDefinedAt` over every family
  keeps its meaning.
* `compileTime` stays a static method the facade's `compileRoot` calls with `atRoot = true`, and
  the arm calls with `false`. Java has no default arguments, so the facade passes both.
* `scala.Option` stays at the boundary, `LinkedHashMap<?, ?>` is cast once, and the facade is
  reached through `VarkaExpressionCompiler$.MODULE$`: the four costs VARKA-175 9 priced, which
  go away with the facade (row 217), not here.

*Correction, made while porting.* The arm's return type was a nested interface of
`VarkaIntervalCompiler`, and `javaFamily` took exactly that type, so the time family could not
return its own. Rows 215 and 216 would have borrowed the interval family's type in turn, so the
interface became one package-private `VarkaFamilyArm` that the three families and `javaFamily`
share. This touches `VarkaIntervalCompiler` (one nested interface removed, one return type
renamed), which 3.2 did not list.

### 3.2 What is deliberately unchanged

* **The decline texts, the order in which arguments are compiled, and the shapes that fall
  through.** In the Scala file each `for` compiles `end` before `start`, `unit` then `end` then
  `start`, and `time` before the interval, so that the input slots keep the expression's
  argument order. Compiling an argument fills the input and literal tables and can note a
  decline, so in Java each is an early return in source order; no argument is computed ahead of
  its turn. `long(e)` drops a non-long lane without a note, and the port adds none. A match on
  `Seq(a, b)` means exactly two arguments, and the `timeAddInterval` pattern fixes the types of
  its literals, so Java checks the size and the types and falls through to the same default.
* **The IR, the emitter and the analyses.** No lowering changes; `emitted_bytes.json` is the
  proof, and a regeneration of it fails the task.
* **`timeTargets`' meaning.** Its type moves from a Scala `Map[(Class[_], String), String]` to a
  Java map keyed by a record of the class and the function name, built the same way - each
  expression constructed once and asked for its own replacement - with the class-initialisation
  throw kept. Its three Scala readers (`compileRoot`, `VarkaExpressionCompilerSuite` twice) move
  with it.
* **The facade, `DeclineSink` and the tables**: row 217's.

The port uses `instanceof` and type patterns over Catalyst's case classes, not record patterns
and sealed switches. They are not records and not sealed, so this is the stated exception to the
sealed-switch rule of `sql/varka/AGENTS.md`, as it was for the interval family.

### 3.3 Registered op counts

None move: a port emits the same IR. The proof is `emitted_bytes.json` unchanged, not a count.

The one oracle that reads the compiler's source is `VarkaCoverageSuite.admittedByCompiler`, which
scans `.scala` files for `case` patterns and `.java` files for type patterns and `instanceof`
(VARKA-175 added the Java forms). `"VarkaTimeCompiler"` moves from its Scala list to its Java
list, and the Java scan's own non-empty check proves the new file is read. The proof that the
scan still sees the family is `coverage.json` byte-identical.

## 4. Files

| file | what |
|---|---|
| `sql/catalyst/src/main/java/.../codegen/VarkaTimeCompiler.java` | the port |
| `sql/catalyst/src/main/scala/.../codegen/VarkaTimeCompiler.scala` | deleted |
| `sql/catalyst/src/main/java/.../codegen/VarkaFamilyArm.java` | the arm type every Java family returns, hoisted out of the interval family |
| `sql/catalyst/src/main/java/.../codegen/VarkaIntervalCompiler.java` | its nested arm interface replaced by `VarkaFamilyArm` |
| `sql/catalyst/src/main/scala/.../codegen/VarkaExpressionCompiler.scala` | the `"time"` chain entry; `compileRoot`'s call and its `timeTargets` read |
| `sql/catalyst/src/test/scala/.../varka/VarkaCoverageSuite.scala` | the file moves from the Scala list to the Java one |
| `sql/catalyst/src/test/scala/.../codegen/VarkaExpressionCompilerSuite.scala` | the `timeTargets` and `timeAddIntervalTruncates` readers |
| `sql/varka/plans/m7/PLAN.md` | row 214 |

## 5. Tests, and what each is for

No test is added: every oracle the port needs exists, and a new one would only restate them.

| suite | what it catches that no other would |
|---|---|
| `VarkaEmittedBytesSuite` | any change in the bytes of any emitted shape, at both widths |
| `VarkaCoverageSuite` | a family dropped from the published coverage because the scan no longer reads it |
| `VarkaFamilyChainSuite` | a guard widened or narrowed so that two families claim one node |
| `VarkaExpressionCompilerSuite` | a wrong bound, a changed decline, a slot order that moved |
| `VarkaTimeBenchmark`'s correctness section and the coverage fuzzers | a lowering that is wrong on data, not only different in text |

Both vector widths run in the emitted-bytes oracle. No pinned fixture moves.

## 6. The measurement

`VarkaEmissionBenchmark` (VARKA-191's wide section), regenerated with
`dev/varka_bench_regen.sh catalyst VarkaEmissionBenchmark` on an idle machine, on master and on
the port the same day, both widths, then `dev/varka_bench_diff.py`. Every shape the benchmark
emits is a date or int expression, so the pair measures the per-node cost of the chain's `"time"`
entry and not a TIME lowering (2). There is no separate control row: every row is the same
measurement, and the benchmark's own spread between two same-day runs is the noise a difference
must clear.

### 6.1 Predictions, registered before the run

1. **The bytes do not move**: `VarkaEmittedBytesSuite` passes against the committed file with no
   regeneration. This is the rule that decides the task: a moved byte is a bug in the port.
2. **Emission times move by less than the noise between two same-day runs of master, on every
   row at both widths.** The chain walks the `"time"` entry for a node no earlier family claims,
   and the Java adapter replaces a partial function with `Function.unlift` over a `switch`, so
   it is one allocation and one type switch per node; if a row moves, the entry is the cause.
3. **The Java file is 400 to 480 lines against the Scala 409.** VARKA-175's port came out 409
   against 250, from the boundary costs and the arm bodies becoming named methods.

## 7. Risks

1. **Evaluation order.** A local computed eagerly, or in the wrong order, changes the input slot
   order or the decline's text. `VarkaEmittedBytesSuite` shows the first; the decline diff in 2
   shows the second.
2. **A decline string that differs.** Compared constant by constant, including the pieces of the
   `s"..." + "..."` concatenations.
3. **A shape that no longer falls through.** The wrong argument count or literal type reaching an
   arm instead of the default. The compiler suite's per-argument cases and the coverage fuzzers
   cover the lowered shapes; the default is covered by the timeNotLoweredYet cases.
4. **The coverage scan reading nothing.** It would drop the family from `coverage.json` without a
   failing test, which is why that file's byte-identity is part of the proof.

## 8. Sequencing

1. This plan, and the milestone row marked Planned.
2. The port with the three Scala call sites and the two test edits, in one commit: the Scala file
   cannot stay beside the Java one, so there is no smaller green step.
3. The measurement and section 9.

## 9. Outcome

Done on 8 October 2026. The port is `VarkaTimeCompiler.java`; the Scala file is deleted.

**The proof held.** `VarkaEmittedBytesSuite`, `VarkaCoverageSuite`, `VarkaFamilyChainSuite` and
`VarkaExpressionCompilerSuite` pass (145 tests, one cancelled, the option audit that is opt-in),
with `emitted_bytes.json` and `coverage.json` unchanged and nothing regenerated. The 44 Varka
suites of `catalyst` pass (542 tests, 36 cancelled). A comparison of the string constants of the
two files finds every decline text in the Java file.

**The emission times** were taken four times on the idle laptop the same morning, two of master
and two of the port, each a full `dev/varka_bench_regen.sh catalyst VarkaEmissionBenchmark` at
both widths. They are not committed: the committed file is master's from 29 September and reads
about a third slower than master does today, so a regenerated file would move every row of a
benchmark this task did not change. The comparisons are by `dev/varka_bench_diff.py`.

* Two runs of master agree to within 3% on every row at both widths. Two runs of the port differ
  by up to 6% on a row, so the benchmark's noise is wider than that pair alone showed.
* At 128 bits the port is within 4% of master on every row, in both pairs.
* At 256 bits the port reads faster, not slower: in the first pair five of the kernel-wide rows
  are 4 to 12% faster, growing with the output count, and in the second pair most rows are 3 to 6%
  faster. A row that reads slower in one pair (3 to 6%, a different one each time) does not read
  slower in the other.

**Predictions scored.**

1. **Held.** The bytes do not move.
2. **Half held.** No row is reproducibly slower, but "within the noise on every row" is not what
   the 256-bit file shows: it shows the port consistently a few percent faster. Nothing in this
   task explains it. Reading the code, the chain's `"time"` entry now builds a little more per
   node, not less, so the cause may be the JIT's treatment of the emitter around a different
   class layout rather than the port's own work; that was not checked. It is recorded as
   measured and not claimed as an improvement.
3. **Refuted.** The Java file is 668 lines against the Scala 409, past the 400 to 480 predicted.
   The interval port was 409 against 250, a ratio of about 1.6, and this one is 1.6 again: the
   imports, `{@code}` in the javadoc, one statement per line and the early returns that replace
   each `for` comprehension account for it. The Java also has pieces the Scala did not: the
   `table()` cast helper, `timeAddInterval`, `narrowedOperand`, `literalUnit` and `timeToUnit`
   extracted from the arms, the `TimeTarget` record and `Map.copyOf`.

**What review of the PR found.** The first version looked a missing table key up with `Map.get`,
where the Scala threw, so a node outside the table would have declined as "null is a TIME
expression..."; it now fails as the Scala did. The class-initialisation error named
`VarkaExpressionCompiler.timeTargets`, a member that no longer exists, in the Scala as well; it
now names `TIME_TARGETS`, and so does the facade's comment. One finding is not the port's and
became row 293: `Math.abs(Long.MIN_VALUE)` is negative, so the one-day guard on a literal interval
lets that value through; the Scala had it too.

**What the plan did not list.** The shared `VarkaFamilyArm` (3.1's correction). The benchmark has
no TIME shape, so it could not have measured the lowering whatever the task did; the emitted
bytes are the proof of that, as 2 said.

**Left for later.** Rows 215 and 216 follow the same recipe and now share the arm type.
Row 217 removes the boundary costs (`scala.Option`, the erased tables, the module access) from
all four families at once.

### 9.1 Correction, 8 October 2026 (made in VARKA-215)

Two statements in 9 are wrong, and the scoring of prediction 2 rests on them.

* **The sign.** `dev/varka_bench_diff.py` compares the benchmark's *rate*, so a minus is slower.
  The 256-bit kernel-wide rows that 9 calls "4 to 12% faster" were 4 to 11% *slower* in the first
  pair of the port against master and 4 to 6% slower in the second; at 128 bits the second pair
  read 3 to 4% slower on several rows. The absolute times, which should have been read first, say
  the same.
* **What the benchmark measures.** `VarkaEmissionBenchmark` builds IR by hand and times only
  `VarkaLoopEmitter.emit`. It never calls `VarkaExpressionCompiler`, so no change to a family
  compiler can move it, and 2 and 6 were wrong to say it measures "the chain's `time` entry". The
  rows moved between builds for a reason that is not the port's code (the next port moved the
  same rows the other way), and the benchmark cannot score prediction 2 either way.

The instrument that does reach the family is `VarkaCompileBenchmark`, added by VARKA-215. Run on
the commit before this port and on master after it, two runs each, interleaved on the idle laptop:
the TIME shape (`hour`, `minute`, `second`) compiles in about 10,100 ns a call before and after,
1.5% and 2% apart at the two widths, inside the 3 to 9% that two runs of one tree differ by, and
no shape moves reproducibly. **Prediction 2 held**, on the right benchmark.

