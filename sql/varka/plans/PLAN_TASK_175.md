# Task 175: Port `VarkaIntervalCompiler` to Java, one family

*Scoped 25 September 2026 (milestone 6 section 2.7, row 175, from
`SCOPE_MILESTONE_7.md` item 42); planned 26 September 2026.*

## 1. The question

The project's direction is modern Java, with a Java Catalyst as the long-term
goal, and the compiler is still Scala: a 1164-line facade,
`VarkaExpressionCompiler`, and four expression families it dispatches to, of
678 (calendar), 443 (condition), 409 (time) and 250 lines (interval). Nobody
has ported one yet, so nobody knows what a port costs, and the milestone takes
this task to find out on the smallest family before anything commits to the
others. Its row says so: one family only, with the friction recorded.

The question is sharper than the row makes it. `sql/varka/CLAUDE.md` tells a
contributor to "reach for Scala only at surfaces that force it", and names
"the Catalyst rule and expression matching" among them. A compiler family is
exactly expression matching - Scala `match` arms with guards over Catalyst's
case classes - so this task tests that sentence. Either the Java form of the
matching reads as well as the Scala one and the sentence narrows, or it does
not and the port stops at one family with the reason recorded.

`VarkaIntervalCompiler` is the right family to ask with: 250 lines, four
members, self-contained, and covered by complete oracles - the compiler suite,
the coverage suite and the emitted-bytes oracle - so a port that changes
nothing can be shown to change nothing.

## 2. The change

### 2.1 The inventory

Generated with `dev/varka_members.py --callees` and `dev/varka_callgraph.py
--by-file` over the five compiler files, on master at `f65928c1d68`:

| member | lines | called from |
| :-- | --: | :-- |
| `arms(inputs, literals, sink)`: the family's `PartialFunction[Expression, Option[VarkaVectorIR]]` | 44-201 | `VarkaExpressionCompiler.familyChain`, and so `compileNode` |
| `intervalOperand(e, position, inputs, literals, sink)` | 203-220 | `arms`, `intervalArith`, `VarkaChronoCompiler.compileMonths` |
| `intervalArith(op, l, r, whole, inputs, literals, sink)`, private | 222-245 | `arms` |
| `twelve(literals)` | 247-249 | `arms`, `VarkaChronoCompiler.compileMonths` |

It calls nine members of `VarkaExpressionCompiler`, all through the facade's
import: `compileNode`, `intOperand`, `compileIntOperand`, `arithOver`,
`magnitude`, `intSlot`, `columnRef`, `truncate`, and `DeclineSink.note`.

`arms` has fifteen cases, in this order:

1. a year-month interval column; 2. a year-month interval literal;
3. `CAST(int AS INTERVAL MONTH)`, the identity; 4. a cast between two
year-month units ending in MONTH, the identity; 5. `CAST(int AS INTERVAL
YEAR)`, a checked multiply by twelve; 6. a cast to a YEAR-ended unit, which
declines; 7. `CAST(ym AS INT)` over a MONTH interval, the identity;
8. interval `+`; 9. interval `-`; 10. interval negate; 11. interval `abs`;
12. `make_ym_interval`; 13. `extract(YEAR FROM ym)`; 14. `extract(MONTH FROM
ym)`, which declines; 15. interval times a number, which declines unless the
number is an int.

### 2.2 What the port touches besides the file

* **`VarkaExpressionCompiler.familyChain`**: its `"interval"` entry becomes an
  adapter over the Java class (2.3). Nothing else in the facade changes.
* **`VarkaChronoCompiler.compileMonths`**: no change. A Java static method is
  called from Scala as `VarkaIntervalCompiler.intervalOperand(...)`, the same
  spelling as a Scala object's method.
* **`VarkaCoverageSuite`**, which the scope item did not foresee. Its
  `admittedByCompiler` reads the five compiler files as `.scala` source and
  scans their `case` patterns for the Catalyst classes the compiler admits,
  which is what `coverage.json` publishes. Pointed at a deleted file it would
  fail; pointed at Java with Scala's regexes it would find none of the
  family's classes and drop them from the published coverage without a
  failure. The scan learns the Java forms - a type pattern in a `switch`
  (`case Add a`) and `instanceof Add` - and reads each file in its own
  language. This is the one oracle the port has to change, so the proof that
  it still works is that `coverage.json` comes out byte-identical.

### 2.3 The one design decision: where a family's guard lives

`familyChain` hands each family's arms to `compileNode` as a `PartialFunction`,
and `VarkaFamilyChainSuite` calls `isDefinedAt` on every family over the
coverage table to prove no two families claim the same node. So "which
expressions this family claims" must stay exactly as it is, and asking it must
have no side effects: a `case` in Scala only tests, and the body runs only on a
match. Three Java forms:

| form | guards written | `isDefinedAt` has side effects? |
| :-- | :-- | :-- |
| A. The Java class subclasses Scala's `AbstractPartialFunction`, with `isDefinedAt` and `applyOrElse` | twice, once in each | no, but the two copies can drift |
| B. Two static methods, `claims(e)` and `compile(e, ...)` | twice | no, same drift |
| C. One static method, `arm(e, inputs, literals, sink)`, a `switch` over `e` whose cases test and deconstruct and return the case's body as a deferred call, or `null` when no case matches; the Scala side lifts it with `Function.unlift` and runs the deferred call | once | no: the `switch` only tests and binds, and the body runs when called |

C is built: one place for every guard, the same order as the Scala arms, and a
`switch` with `when` guards is the house style the IR and the emitter already
use. The adapter in `familyChain` is one expression:
`Function.unlift((e: Expression) => Option(VarkaIntervalCompiler.arm(e, inputs,
literals, sink))).andThen(_.compile())`.

### 2.4 The translation, rule by rule

* **Deconstruction.** Catalyst's expressions are Scala case classes, not Java
  records, so `MakeYMInterval(y, mo)` cannot be a record pattern. Each arm
  becomes a type pattern plus accessor calls: `case MakeYMInterval m ->` and
  then `m.years()` and `m.months()`, the names the class declares.
* **Guards** become `when` clauses, in the Scala order, so a cast whose target
  and child type match two arms still reaches the same one.
* **Nested patterns** - `Cast(child, YearMonthIntervalType(_, MONTH), _, _)`
  and `Literal(months: Int, _: YearMonthIntervalType)` - become a type pattern
  with a `when` over the accessors: `c.dataType() instanceof
  YearMonthIntervalType ym && ym.endField() == YearMonthIntervalType.MONTH()`.
* **Scala objects** are reached as the Java API names them where one exists
  (`DataTypes.IntegerType`) and through the companion's static forwarders
  otherwise (`YearMonthIntervalType.MONTH()`).
* **`Option`.** The class calls Scala methods that return `scala.Option` and is
  called by Scala code that expects one, so it keeps `scala.Option` at its
  boundary rather than converting to `java.util.Optional` and back at every
  call. The `for` comprehensions become early returns on `isEmpty()`, which is
  the clearer Java. Java-native signatures belong to the port of the facade,
  when both sides of the boundary are Java.
* **The literal and input tables** stay `scala.collection.mutable.LinkedHashMap`,
  which Java sees with its `Int`s erased to `Object`. The class only passes
  them on and reads their size, so the erasure costs a type argument and
  nothing else.
* **Comments** move with their arms, as they are: they explain why each
  lowering is sound, and the arms are the same.

The class is a package-private `final class` in
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/`,
the same package as the Scala facade, with a private constructor and static
members. Its javadoc says what the family is for, in the present tense.

## 3. Predictions, registered before the port

1. **The oracles do not move.** `VarkaExpressionCompilerSuite`,
   `VarkaFamilyChainSuite`, `VarkaCoverageSuite` and `VarkaEmittedBytesSuite`
   pass with the same test counts, and `coverage.json` and
   `emitted_bytes.json` regenerate byte-identical.
2. **Every decline reason is unchanged**, character for character: the four
   reasons the Scala file notes and the seven operand positions it names, which
   other reasons quote, are found verbatim in the Java one.
3. **The Scala side changes in three places only**: the `familyChain` entry,
   the coverage suite's scan, and the deleted file. `VarkaChronoCompiler` does
   not change.
4. **No build change.** sbt's and Maven's mixed Scala-Java compile of
   `sql/catalyst` handles the cycle - the Java class calls the Scala facade and
   the facade calls the Java class - and `build/sbt catalyst/doc` passes.
5. **The Java file is 300 to 400 lines** against the Scala 250, comments
   included: the deconstruction and the early returns cost lines, the arms'
   comments do not change.
6. **The friction is the five items of 2.4 and the coverage scan, and nothing
   else.** Any sixth kind found on the way is the finding this task exists for,
   and goes into section 5.

## 4. Verification

* The four suites of prediction 1, and the rest of the Varka catalyst suites.
* `coverage.json` and `emitted_bytes.json` regenerated and diffed.
* A diff of the decline strings between the two files.
* `build/sbt catalyst/doc`, and a Maven compile of `sql/catalyst`, the two
  builds `sql/varka/CLAUDE.md` names as the ones where Java in this module has
  failed before.
* `dev/scalastyle`, `dev/lint-java`, the 100-column and non-ASCII scans.

No benchmark: the compiler runs at planning, and identical emitted bytes mean
identical kernels.

## 5. Outcome

*To be written.* It scores the predictions, lists the friction with its cost
in lines and in reading time, and says what the port means for the three other
families and for the facade: whether they follow, in what order, and whether
`sql/varka/CLAUDE.md`'s sentence about expression matching narrows.

## 6. Explicitly out of this task

* The other three families and the facade. The outcome recommends; the ports
  are later rows.
* `java.util.Optional` signatures, and moving `DeclineSink` and the tables to
  Java: they belong to the facade's port.
* Item 47, one place per node: its own text reopens it only when a real
  second case arrives.
