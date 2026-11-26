# VARKA-216: Port VarkaChronoCompiler to Java

## 1. Where this came from

Row 216 of `m7/PLAN.md`, from `m10/SCOPE.md` item 81: the third and last family port, after the
interval family (VARKA-175), the TIME family (VARKA-214) and the predicate family (VARKA-215), on
the same recipe and under the owner's "Java code" rules in `sql/varka/AGENTS.md`.

`VarkaChronoCompiler.scala` is 670 lines and is the largest of the four: the calendar arms
(`date_add`, `datediff`, the day-of-week nodes, `next_day`, the civil-field extractions,
`make_date`, `last_day`, the ISO week, `trunc`, month arithmetic), the offset and month-count
admission, and the range analysis that decides where a day producer under a calendar node needs a
runtime guard (`admitCalendar`, `rearm`).

## 2. The admission check, done

As for 214 and 215, the port has one admission check, that it changes nothing, and the proofs are
fixed before the code:

* **The emitted bytes**: `VarkaEmittedBytesSuite` against `emitted_bytes.json`, not regenerated.
* **The coverage table**: `VarkaCoverageSuite` and `coverage.json` byte-identical. This is the
  proof most at risk here: the suite finds the Catalyst classes a compiler admits by scanning its
  source for `case X name ->` and `instanceof X`, so a class written in a form the scan does not
  read drops out of the published coverage without anything else failing. A file this size will
  have one.
* **The chain**: `VarkaFamilyChainSuite`. **The compiler's suites**: `VarkaExpressionCompilerSuite`
  and the calendar emitter suite, which pin the declines of the range analysis case by case.
* **The decline texts**: every string constant of the Scala file found in the Java file.
* **Compile times**: `VarkaCompileBenchmark` (VARKA-215), which times `compile` over a calendar
  projection and a sixty-output mixed one, run on master and on the port interleaved on the idle
  laptop. `VarkaEmissionBenchmark` does not reach the compiler (`VARKA-214.md` 9.1) and is not
  used.

## 3. The design

### 3.1 The seam

215's, unchanged: a package-private `final class VarkaChronoCompiler` in the facade's package;
`static VarkaFamilyArm arm(...)`, a pattern `switch` over the node returning the lowering as a
deferred call, or `null`; the facade's `familyChain` entry becomes
`javaFamily(VarkaChronoCompiler.arm(_, inputs, literals, sink))`; `scala.Option` and
`LinkedHashMap<?, ?>` stay at the boundary; the Scala file is deleted. The one other entry point
the facade calls is `isDayOfWeekIso(Add)`, which keeps its name and meaning.

### 3.2 What is deliberately unchanged, and what moves

* **Evaluation order.** `date_add` compiles its date and then its offset; `datediff` its end and
  then its start; `next_day` with a foldable weekday folds the weekday *first* and compiles the
  date second, and registers the weekday slot last; `make_date` its year, month and day in that
  order; `trunc` with a foldable format resolves the level first, and for WEEK allocates the slot
  for 7 before the one for Monday. Each is early returns in source order.
* **The decline texts**, constant for constant.
* **The range analysis**, `rearm` included. `VarkaValueRange.Range` is a sealed Java interface, so
  the Java `switch` over it is exhaustive and the Scala's `default` that threw
  `IllegalStateException("unexpected range")` has nothing left to catch: a third member is a
  compile error instead of a runtime one, which is the rule in `sql/varka/AGENTS.md` doing its job.
* **The two pattern objects** `DayIntervalOffset` and `MonthIntervalOffset` become predicate
  methods returning an `Optional` of the column. `DayIntervalOffset.wrap`, which rebuilt a cast so
  that `date - INTERVAL n DAY` could re-enter `compileOffset`, is not ported: the negated branch
  calls the helper `compileOffset` reaches for that form (the bound on the column, then the column)
  directly. The sequence of notes and table entries is the same; no expression is built to be
  taken apart again.
* **`NonFatal`** is Scala's own, called from Java as `NonFatal.apply`. The first draft rewrote it
  as a small `isFatal` without Scala's control-flow case and named `ThreadDeath`, which is
  deprecated for removal; review caught both, and the port now keeps the exact semantics.
* **The month and offset admission** is a chain of `instanceof` tests in Scala's order, since
  Catalyst's classes are not a sealed set.

The pattern matching over Catalyst's case classes is the stated exception to the sealed-switch
rule, as in 214 and 215.

### 3.3 Registered op counts

None move. The proof is `emitted_bytes.json` unchanged.

## 4. Files

| file | what |
|---|---|
| `sql/catalyst/src/main/java/.../codegen/VarkaChronoCompiler.java` | the port |
| `sql/catalyst/src/main/scala/.../codegen/VarkaChronoCompiler.scala` | deleted |
| `sql/catalyst/src/main/scala/.../codegen/VarkaExpressionCompiler.scala` | the `"calendar"` chain entry |
| `sql/catalyst/src/test/scala/.../varka/VarkaCoverageSuite.scala` | the file moves to the Java list |
| `sql/varka/plans/m7/PLAN.md`, `VARKA-216.md` | the row and this plan |

## 5. Tests, and what each is for

No test is added. The oracles of 214 and 215 are the proof, and the calendar emitter suite and
`VarkaExpressionCompilerSuite` pin the range analysis's admissions and declines (a bounded shift
admitted, a literal overflow declined, a column shift re-armed, an unknown producer declined),
which is the part of this port where a slip changes an answer and not a text.

## 6. The measurement

`VarkaCompileBenchmark` through `dev/varka_bench_regen.sh`, three runs of master and three of the
port interleaved, both widths, compared by minimums as the repo's rule asks for ratios under 1.3.
Only the regenerated files for the port are committed (the master side is 215's).

### 6.1 Predictions, registered before the run

1. **The bytes do not move**, and `coverage.json` is byte-identical.
2. **No shape compiles reproducibly slower**, by the minimum over the runs. The calendar
   projection, the one this family owns, is within 3% of master either way.
3. **The pattern `switch` in `arm`, over about twenty classes, costs nothing measurable.** If it
   does cost, the sixty-output mixed projection shows it first, and the fallback is a chain of
   `instanceof` tests, as in 215. (The file's size, 901 lines against the Scala's 670, was known
   when this was written and is not a prediction.)

## 7. Risks

1. **The coverage scan.** A class written as a qualified name, or in a form neither pattern
   reads, silently leaves `coverage.json`. The byte-identity of that file catches it; the first
   build of this port had one (`AddMonths`, which clashes with the IR node of the same name).
2. **A different order of table entries.** An offset or month count compiled before its date
   changes the input slot order. The emitted bytes show it for shapes that fuse.
3. **The `date - INTERVAL n DAY` path**, where `wrap` is not ported. The calendar emitter suite and
   `VarkaDifferentialSuite` run that shape.
4. **`eval` on a foldable expression** in `foldWeekday` and `foldTruncLevel`: the Scala calls
   `eval()` with the default empty row, the Java `eval(null)`. The same call.

## 8. Sequencing

1. This plan, and the row marked Planned.
2. The port with the call site and the coverage list, in one commit.
3. The measurement and section 9, then the compile benchmark's results regenerated once 215's
   have merged.

## 9. Outcome

Done on 8 October 2026.

**The proof held.** `VarkaEmittedBytesSuite`, `VarkaCoverageSuite`, `VarkaFamilyChainSuite`,
`VarkaExpressionCompilerSuite`, `VarkaRangeSetCompilerSuite` and the calendar emitter suite pass
(214 tests, five cancelled, all opt-in), with `emitted_bytes.json` and `coverage.json` unchanged;
the 44 Varka suites of `catalyst` pass (560 tests, 36 cancelled). Every string constant of the
Scala file is in the Java file except one, deliberately: the `IllegalStateException("unexpected
range")` of a `default` that a switch over a sealed type no longer needs.

**The coverage scan did what it is there for.** The first build of the port failed
`VarkaCoverageSuite`: `coverage.json` had lost `AddMonths`, because the arm was written with the
class's qualified name (the IR has a node of the same name) and neither of the scan's Java
patterns reads a qualified name. Importing the Catalyst class and writing the simple name restored
it. Risk 1 was this exactly.

**Compile times, this port alone.** Three runs of master and three of the port, interleaved on the
idle laptop with `VarkaCompileBenchmark`, compared by minimums; both trees were before 215, so the
predicate rows are unchanged code. Nanoseconds a call, the spread being the largest difference
between two runs of master:

| shape | master | port | spread of master |
|---|---:|---:|---:|
| calendar projection of six, 256 bits | 12217 | 12107 | 4% |
| calendar projection of six, 128 bits | 11968 | 12205 | 4% |
| a projection of sixty mixed outputs, 256 bits | 555754 | 509141 | 2% |
| a projection of sixty mixed outputs, 128 bits | 538392 | 526406 | 6% |

The calendar projection, the one this family owns, is within the spread at both widths. The
sixty-output projection reads 8% faster at 256 bits and 2% at 128: not the same at the two widths,
so no claim. The other shapes (TIME, conditionals, the four predicates) move by at most 5%, the
largest being the `NOT`/`OR`/`IS NULL` predicate at 128 bits against a spread of 5%. At 128 bits
seven of the eight rows read 2 to 5% slower and at 256 bits they read within 3% either way; a
difference that changes sign with the width of a benchmark that does not use the width is the
noise of the run, not of the port.

**The committed files.** `VarkaCompileBenchmark-jdk25-results.txt` and its companions are
regenerated for the final form, all three ports together. Against `-master-results.txt`, which is
master before 215 (`fe857dc6d4a`), the predicate rows take 8 to 26% less time (215's change), the
TIME projection is unchanged and the calendar projection takes 2% less at 256 bits and 4% less at
128, inside the noise above. The files were regenerated from the final commit after review, when
their first provenance named a commit that rewriting the branch had dropped.

**Review of the PR** (`/code-review high`) found no semantic divergence from the Scala, and these,
fixed in the PR: `truncFolded` consumed the sealed `TruncTarget` with `instanceof` and treated the
rest as WEEK, where a third target would have compiled as WEEK (now an exhaustive `switch`);
`rearm`'s switch over the sealed IR ended in a `default` that would swallow a new day-producing
node (the passthrough nodes are listed, so a new node is a compile error until classified);
`isFatal` (above); the `DAYOFWEEK_ISO` shape was tested in one place and unpacked with a blind cast
in another (one helper returns the `weekday` argument); and `dateAdd` called `get()` on a helper
that cannot decline today (it now returns the column). One finding is left as a row of the
facade's: the four Java families each carry their own copy of `decline`, `table` and the `FACADE`
constant, which row 217 removes with the boundary costs that make them necessary. And one is
traceability: the benchmark provenance named a commit that rewriting the branch's history had
dropped, so the committed results are regenerated from the final commit.

**Predictions scored.**

1. **Held.** The bytes do not move, and `coverage.json` is byte-identical.
2. **Held.** No shape compiles reproducibly slower; the 128-bit lean is inside the spread of
   master and is absent at 256 bits.
3. **Held.** The pattern `switch` in `arm`, over about twenty classes, costs nothing measurable,
   so the fallback to a chain of `instanceof` tests was not needed.

**What the port is.** 901 lines against the Scala's 670, 1.3 times; 214's was 1.6 and 215's 1.8.
This is the last of the three family ports: `VarkaExpressionCompiler.scala` is the only compiler
file left in Scala, which is row 217.
