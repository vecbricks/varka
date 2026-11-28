# VARKA-358: `pow`, `power` and `hypot` bit for bit, and what else row 358 holds

*Planned on 28 November 2026 for builders that have only this file and the repository's
`AGENTS.md` files. It stands alone. Its code facts were checked on master `6dafb8bd2f6`. The row
is **Started**: nine of its functions are on master since #1643. Every section of this file is
new: until today rows 356, 357 and 358 shared `VARKA-356.md`, which keeps the three rows' design
and the record of what was built. No JVM was started for this plan; section 9 lists what only a
JVM settles, each with its command.*

*Revised the same day after review 1: 5.1 (the builder's line, steps 4, 6, 7 and 9, command 2),
5.2 (steps 2 to 5), and sections 8, 9, 10 and 11 changed. Step 7 of 5.1 now holds the suite's
code itself, so no step of this file needs a branch.*

## 1. What this row is, and what is left of it

Row 358 of `m8/PLAN.md` is the exponential, logarithmic, power and root functions of Spark's math
family, exact by default. A kernel answers the bits vanilla Spark answers, under both ANSI
settings, because none of these functions reads the ANSI setting.

**Merged.** `exp`, `expm1`, `cbrt`, `ln`, `log`, `log10`, `log1p`, `log2` and `log(b, x)` fuse as
a per-lane call of the scalar method Spark's generated code calls (#1643, 23 November 2026).
The fdlibm ports of `exp`, `expm1`, `log` and `log10` exist as IR expansions in `VarkaMathPort`
(#1609, #1629, #1645), tested and timed (row 635), and wired to no SQL function.

**Left for the close.** The done-when item this row serves is item 5 of `m8/PLAN.md` 1.1, "the
math family is bit-exact to Spark by default", as the close path reads it (12.5, decision 3 of
27 November 2026): the item is met by the per-lane calls and by the ports that exist, and "the
per-lane calls the item asks for and master lacks stay owed here: `atan2` (356), the six
hyperbolic functions (357), and `pow`, `power` and `hypot` (358)". So this row owes:

| part | what | why it is owed |
| :--- | :--- | :--- |
| 358a2 | a two-operand call node, `MathBinary`, and `pow`, `power`, `hypot` on it | item 5: three of the 27 entries decline today |
| 358r | the math baseline's runner results once the family fuses, predictions scored | `VARKA-356.md` 9; the house rule that a claim traces to a committed results file |

`power` is a second SQL name of the class `Pow` (`FunctionRegistry`), so it needs no code of its
own, only its census line and its coverage row.

**Not owed for the close, and not built from this plan.** Each is a default this plan applies;
section 10 puts each to the owner.

| piece of the row's "left" list | where it goes | why |
| :--- | :--- | :--- |
| the `pow` and `hypot` `PORT` arms (358f, 358e) | `m10/SCOPE.md` item 140 | the owner's decision 3 of 27 November 2026 |
| 358b, an emit flag per built port (`mathPortExp`, `mathPortExpm1`) | stays on branch `varka-358b-exp-port`, not a gate | a port is an option arm; row 636, the port at two long lanes, moved to item 140 on the same reading |
| the IR fuzz productions of the per-lane calls | not a gate | section 10, question 3 |
| the vectors files `sql/varka/math/<fn>.vectors` | not a gate | their format is unbuilt and their second reader, row 367, moved to milestone 10 |
| the opt-in `LIBRARY` entries | milestone 10 | they wait on row 367's measured lines |

**What it gives the rows after it.** `MathBinary` is the node row 356's `atan2` is built on
(`VARKA-356.md` section 12). Row 357 needs nothing of this row that is not merged.

**What must be merged before it starts.** Nothing. 358a2 starts from master.

## 2. What a builder must know

**Terms.** A *kernel* is the class Varka emits for one shape of expressions; it computes on
vector *lanes* and falls back to Spark's row engine when it declines. A *per-lane call* (the
plans' `LANE_CALL`) reads each lane out of the vector, passes it to a scalar Java method and
writes the result back; the work around the call stays in vectors. A *port* (`PORT`) is fdlibm
5.3's C function written as IR nodes, so that no call is made. The *word* of a node is its
validity: one bit a row, 0 for null.

**How a per-lane call is exact.** The kernel calls the same static method that Spark's generated
code calls, in the same JVM. HotSpot answers a `java.lang.Math` method from one routine in the
interpreter, C1 and C2 (`VARKA-345.md` item 0.3), so the kernel's lanes are Spark's bits.

**The names this part reads** (all on master, each checked in section 3):

| name | where | meaning |
| :--- | :--- | :--- |
| `MathFn` | `VarkaVectorIR` | the function of a call node; each member names `owner()` and `method()` |
| `MathUnary(MathFn fn, VarkaVectorIR child)` | `VarkaVectorIR` | a one-operand call, or the lanewise `SQRT` |
| `emitMathLaneCall` | `VarkaVectorWalk` | unrolls `v.lane(k)`, the static call and `v.withLane(k, r)` over the baked lane count |
| `NO_BAKED_LANES_FOR_A_CALL`, `Kind.MATH_CALL` | `VarkaKernelPlan` | the refusal of a call at a width with no baked lane count |
| `FloatingFault.MATH_OTHER_CLASS` | `VarkaEmitOptions` | the planted fault: the call names the other of `Math` and `StrictMath` |
| `VarkaSameAnswer` | catalyst main | the one rule for "the same answer"; its `Step.Arith(left, right, eitherPayload)` says a NaN made from two NaN operands may carry either payload |
| `Fma(a, b, c)` | `VarkaVectorIR` | the template for a node of several operands whose word is the AND of theirs |

**The names this part adds:**

| name | meaning |
| :--- | :--- |
| `MathFn.POW`, `MathFn.HYPOT`, `MathFn.binary()` | the two-operand members and the flag that tells them |
| `record MathBinary(MathFn fn, VarkaVectorIR left, VarkaVectorIR right, int hash)` | `fn(left, right)` over two `DOUBLE` operands, a per-lane call, null where either operand is |
| `VarkaDescriptors.DOUBLE_FN2` | the `(DD)D` method type |
| canonical form `(math2:<FN> <left> <right>)` | the node's text in `VarkaVectorIR.canonical` and `VarkaCanonicalIr` |

## 3. The admission check

Each line is a fact the design rests on and the command that prints it. Run them from the
repository root; a builder stops if one prints something else.

1. `pow`, `power` and `hypot` decline today, naming this row.

        grep -c 'Map.entry(Pow.class, new Owner("pow", 358))' sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/VarkaMathCompiler.java

   prints `1`. The same grep with `Hypot.class, new Owner("hypot", 358)` prints `1`.
2. No two-operand call node exists: `git grep -c 'MathBinary' -- sql/catalyst/src sql/core/src`
   prints nothing.
3. Spark's code: `Pow` generates `java.lang.StrictMath.pow(c1, c2)` and `Hypot` takes the base
   class's `java.lang.Math.hypot(c1, c2)`.

        grep -n 'StrictMath.pow\|extends BinaryMathExpression(math.hypot' sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/expressions/mathExpressions.scala

   prints two lines for `Pow` (its base class's argument and its `doGenCode`) and one for
   `Hypot`.
4. `Fma` is in every switch a new value node must join: `git grep -lw 'Fma' -- sql/catalyst/src`
   prints 22 files (8 under `src/main`, 14 under `src/test`). These are the sites of step 4 of
   the build sheet.
5. The emitter's helper for a two-operand word exists:
   `grep -c 'static void emitAndWord(CodeBuilder cb, Slots s, int own, int a, int b)'` on
   `VarkaVectorWalk.java` prints `1`.
6. The built copy exists and is not merged: branch `varka-358a2-r2` of
   `/home/max/proj/vecbricks-varka`, seven commits from `b25a4f3c176` to `a933f982886` on top of
   `79d36b86965`.

        git -C /home/max/proj/vecbricks-varka diff --shortstat 79d36b86965..varka-358a2-r2

   prints `33 files changed, 468 insertions(+), 75 deletions(-)`.

What could not be checked without a JVM is in section 9.

## 4. The design

### 4.1 The members, and which class each names

| member | owner (internal name) | method | Spark's generated code |
| :--- | :--- | :--- | :--- |
| `POW` | `java/lang/StrictMath` | `pow` | `java.lang.StrictMath.pow(c1, c2)` |
| `HYPOT` | `java/lang/Math` | `hypot` | `java.lang.Math.hypot(c1, c2)` |

**Decision: a member names the class Spark's generated code names.** Branch `varka-358a2-r2` and
`VARKA-345.md` 2.1 give `HYPOT` the owner `java/lang/StrictMath`, "which `Math.hypot` delegates
to". That is exact on JDK 25 and is a statement about one JDK. The evidence that it does not
last: on this laptop the JDK 26 runtime holds an intrinsic for `Math.sinh` and the JDK 25
runtime does not.

    grep -a -c '_dsinh' /usr/lib/jvm/java-26-openjdk-amd64/lib/server/libjvm.so
    grep -a -c '_dsinh' /usr/lib/jvm/java-25-openjdk-amd64/lib/server/libjvm.so

The first prints a count above 0 and the second prints `0` (run on 28 November 2026; the JDK 26
library also holds the string `StubRoutines::dsinh`). So a method that delegates on one JDK is a
host routine on the next, and a kernel that calls `StrictMath` where Spark calls `Math` would
then answer other bits than Spark, silently: the fallback cannot catch a wrong answer. Calling
the class Spark's code names is exact on every JDK and costs nothing on JDK 25, where C2 inlines
the delegation. The exact `pow` is `StrictMath.pow`, fdlibm's weak branch included, so it needs
no guard and no exact-exponent nodes: those belong to the opt-in form (`m8/PLAN.md` 2.7.1).

### 4.2 The node

    record MathBinary(MathFn fn, VarkaVectorIR left, VarkaVectorIR right, int hash)
        implements VarkaVectorIR {
      public MathBinary {
        if (!fn.binary()) {
          throw VarkaErrors.illegalArgument(fn + " takes one operand, a MathUnary's");
        }
        if (left.laneType() != LaneType.DOUBLE || right.laneType() != LaneType.DOUBLE) {
          throw VarkaErrors.illegalArgument(fn + " takes DOUBLE operands, as Spark's function "
              + "does, not " + left.laneType() + " and " + right.laneType());
        }
        hash = hashOf(fn, left, right);
      }
      public MathBinary(MathFn fn, VarkaVectorIR left, VarkaVectorIR right) {
        this(fn, left, right, 0);
      }
      @Override public int hashCode() { return hash; }

      @Override
      public VarkaNodeProps props() {
        // Two extracts and an insert a lane, at most eight double lanes at 512 bits; the scalar
        // calls between them are no vector op.
        return new VarkaNodeProps(NEVER, false, STRICT, 24,
            getClass().getSimpleName() + '/' + fn + '/' + laneType(), FILLS_NOTHING,
            RESTRICTS_NOTHING, LongNarrowReader.NONE);
      }
    }

- It is its own record and not a second child on `MathUnary`: every walk that knows `MathUnary`'s
  one child would read such a node wrongly. The arity is `MathFn.binary()`, which both
  constructors check: `MathUnary` refuses a binary member with `fn + " takes two operands, a
  MathBinary's"`.
- `laneType()` is `LaneType.DOUBLE`. Operands are in Spark's order: `pow(x, y)` is
  `MathBinary(POW, x, y)`.
- Null-intolerant: `STRICT`, the word is the AND of the operands' words, as `Fma`'s is. No input
  nulls a lane: `pow(-1.0, 0.5)` is a NaN, not a null.
- Range analysis answers `VarkaValueRange.UNKNOWN`, as for `MathUnary`.
- The branch was written before row 489 moved a node's properties into `props()`. Its arms in
  `VarkaEmitBudget` (weight 24) and `VarkaEmitCost` (the cost kind) are not copied: `props()`
  above holds both.

### 4.3 The lowering

A `MathBinary` is refused at a width with no baked lane count exactly as a one-operand call is:
in `VarkaKernelPlan`, the loop that returns `Kind.MATH_CALL` also takes
`node instanceof VarkaVectorIR.MathBinary`. The emitter, in `VarkaVectorWalk` beside
`emitMathLaneCall`, takes no local variable. A double is two stack slots, so the stack moves
are the category-2 forms; the comment on each line is the stack after it:

    private static void emitMathBinary(CodeBuilder cb, MathBinary n, boolean dense,
        Analysis analysis, Slots s, Set<VarkaVectorIR> computed) {
      emitValue(cb, n.left(), dense, analysis, s, computed);
      emitValue(cb, n.right(), dense, analysis, s, computed);
      line(cb, analysis, n);
      Lane lane = analysis.laneOf(n);
      if (lane != Lane.DOUBLE || analysis.lanes == 0 || analysis.lane.bits != Long.SIZE) {
        throw VarkaErrors.illegalState("a per-lane " + n.fn() + " needs a double vector at a "
            + "baked lane count of a 64-bit drive lane, not " + lane + " at " + analysis.lanes);
      }
      String called =
          analysis.options.floatingFault() == VarkaEmitOptions.FloatingFault.MATH_OTHER_CLASS
              ? otherClass(n.fn().owner()) : n.fn().owner();
      ClassDesc owner = ClassDesc.ofInternalName(called);
      cb.swap();                                                  // [b, a]
      for (int k = 0; k < analysis.lanes; k++) {
        cb.dup2();                                                // [b, acc, b, acc]
        cb.loadConstant(k);
        cb.invokevirtual(lane.vector, "lane", DOUBLE_LANE);       // [b, acc, b, x]
        cb.dup2_x1();                                             // [b, acc, x, b, x]
        cb.pop2();                                                // [b, acc, x, b]
        cb.loadConstant(k);
        cb.invokevirtual(lane.vector, "lane", DOUBLE_LANE);       // [b, acc, x, y]
        cb.invokestatic(owner, n.fn().method(), DOUBLE_FN2);      // [b, acc, r]
        cb.loadConstant(k);                                       // [b, acc, r, k]
        cb.dup_x2();                                              // [b, acc, k, r, k]
        cb.pop();                                                 // [b, acc, k, r]
        cb.invokevirtual(lane.vector, "withLane", DOUBLE_WITH_LANE);  // [b, acc']
      }
      cb.swap();                                                  // [r, b]
      cb.pop();                                                   // [r]
      if (!dense && s.ownWord.contains(n)) {
        emitAndWord(cb, s, s.wordRef.get(n), s.wordRef.get(n.left()), s.wordRef.get(n.right()));
      }
    }

Why it is right: `acc` starts as `a` and lane `k` of `acc` is still `a`'s when it is read, since
only lanes below `k` have been replaced. Every lane is called, a null lane's too: the call is
pure and the word discards the answer. `VarkaDescriptors.DOUBLE_FN2` is
`MethodTypeDesc.of(CD_double, CD_double, CD_double)`.

### 4.4 Two NaN operands: the one place the bits are not fixed

fdlibm's `pow` and `hypot` answer the sum `x + y` where both operands are NaN. Which payload a
sum of two different NaNs keeps is the processor's rule over the order the JIT gives the
operands, and the interpreter and C2 do not agree on it. The branch measured it on one JVM:
`hypot(0xffffffffffffffc0, 0xffffffffffffffff)` gave `0x7fffffffffffffc0` in the kernel and
`0x7fffffffffffffff` in the suite's scalar call of the same method (`VARKA-356.md`, the branch's
record of 358a2). So Spark's own answer there is either NaN, by how its code was compiled, and
no kernel can match one of them on every run.

The rule, in `VarkaSameAnswer` and nowhere else (the pre-commit lint refuses a hand-written NaN
comparison in a suite):

- IR front: `case VarkaVectorIR.MathBinary m -> new Step.Arith<>(m.left(), m.right(), true);`
- Catalyst front, beside the test for `Rint`: an expression that is a `Pow` or a `Hypot` answers
  `new Step.Arith<>(p.left(), p.right(), true)`.

`eitherPayload = true` means: where both operands are NaN with different bits, the answer is a
NaN and either payload passes; every other lane is compared bit for bit. One NaN operand, or two
with equal bits, stays bit for bit.

### 4.5 The compiler

In `VarkaMathCompiler.arm`, two arms and one helper; nothing declines by name beyond what
`operand` already declines (a value computed on another lane):

    case Pow f -> () -> call2(MathFn.POW, f.left(), f.right(), inputs, literals, sink);
    case Hypot f -> () -> call2(MathFn.HYPOT, f.left(), f.right(), inputs, literals, sink);

    /** {@code fn(x, y)}, a per-lane call over the two double operands in Spark's order. */
    private static Optional<VarkaVectorIR> call2(MathFn fn, Expression x, Expression y,
        VarkaSlotTable inputs, VarkaSlotTable literals, DeclineSink sink) {
      Optional<VarkaVectorIR> left = operand(x, inputs, literals, sink);
      if (left.isEmpty()) {
        return left;
      }
      return operand(y, inputs, literals, sink).map(r -> new MathBinary(fn, left.get(), r));
    }

A literal exponent (`power(x, 3.0D)`) is a literal slot through `operand`, so two queries that
differ in the exponent share one class.

### 4.6 Edge cases

| case | answer | held by |
| :--- | :--- | :--- |
| a null in either operand | null | the fixed vectors over four null patterns, the second column nulled one row on |
| both operands NaN, different bits | a NaN, either payload | 4.4; `VarkaSameAnswer` |
| `pow(1, NaN)`, `pow(+-1, +-inf)` | NaN, Java's answer, not C99's 1 | the fixed vectors: the method itself is called |
| `pow` with `x` within `2^-20` of 1 and `abs(y)` above `2^31` (fdlibm's weak branch) | `StrictMath.pow`'s bits | the pair draw of 5.1 step 7 |
| negative `x`, integral and non-integral `y` | `StrictMath.pow`'s bits | the pair draw |
| two 64-bit lanes (128 bits), eight lanes | the same bits | the fixed vectors at `lanesOverride` 2 and 8 |
| a width with no baked lane count (`validityByWidth=false`, `lanesOverride=16`) | the compiler declines, Spark answers | `skips.tsv` lines of step 9 |
| a batch with no live row | no lane is read | the null pattern "all" |

## 5. The parts

### 5.1 Part 358a2

- **Title:** `[VARKA-358] Fuse pow and hypot bit for bit through a two-operand call`
- **Branch:** `varka-358a2-r3`, from master (`varka-358a2-pow-hypot` and `varka-358a2-r2` exist;
  do not reuse them).
- **Builder:** full strength, before 1 December, for step 6 and for 4.4 (section 8 says why
  those two and no other step).
- **Size:** about 480 counted lines (the branch's diff is 468 added and 75 removed over 33
  files, 107 of them its plan record, which is replaced by this file's section 11).
- **Starts from:** master. `git cherry-pick b25a4f3c176..a933f982886` from the main clone
  conflicts in every exhaustive switch, since master gained records and `props()` since
  23 November; resolve each by keeping master's arms and adding `MathBinary`'s. Or write the
  steps below by hand: every step is written out here and none needs the branch.

**B0, the premises.** Section 3's lines 1 to 5 print what they say, and
`git branch --list varka-358a2-r3` prints nothing.

**Steps.**

1. `VarkaVectorIR.MathFn`: a `binary` field, a three-argument constructor, the two-argument one
   passing `false`, `public boolean binary()`, and the members of 4.1 after `CBRT`. Extend the
   enum's Javadoc: `POW` is the method Spark's code names; `HYPOT` is `Math.hypot`, Spark's,
   which is fdlibm's on JDK 25.
2. `VarkaVectorIR.MathUnary`: refuse a binary member (4.2).
3. `VarkaVectorIR.MathBinary`: the record of 4.2, placed after `Fma`; add it to `permits`.
4. Every switch the compiler then names (`build/sbt catalyst/Test/compile` lists them). In each,
   `MathBinary` sits beside `Fma` and answers as this table says:

    | file | arm |
    | :--- | :--- |
    | `VarkaVectorIR` `laneType` | `LaneType.DOUBLE` |
    | `VarkaVectorIR` `childrenOf`, `withChildren` | `{n.left(), n.right()}`; `new MathBinary(n.fn(), children[0], children[1])` |
    | `VarkaVectorIR` both canonical forms | `"(math2:" + n.fn().name() + " " + left + " " + right + ")"` |
    | `VarkaVectorIR` the grouped arms that list `MathUnary _, Fma _` | add `MathBinary _` to the group |
    | `Analysis` | `case MathBinary n -> { }` in the refusal walk; `analyzeOp(node, false, n.left(), n.right())` in `analyze` |
    | `Slots` | `case MathBinary x -> { }` |
    | `VarkaVectorWalk` | `case MathBinary n -> emitMathBinary(...)`; add `POW, HYPOT` to the "is a per-lane call" arm of `emitMathUnary`'s operator switch |
    | `VarkaKernelPlan` | the `MATH_CALL` refusal (4.3); and the one grouped arm that holds `Fma _`, at the line that reads `VarkaVectorIR.BitCount _, VarkaVectorIR.MathUnary _, VarkaVectorIR.Fma _,` (`grep -c 'Fma _'` on the file prints `1`): add `VarkaVectorIR.MathBinary _` after `Fma _` |
    | `VarkaRangeAnalysis` | `VarkaValueRange.UNKNOWN` |
    | `VarkaSameAnswer` | 4.4, both fronts |
    | `VarkaChronoCompiler` | the grouped arm beside `Fma _` |
    | `VarkaDescriptors` | `DOUBLE_FN2` |

5. `VarkaVectorWalk.emitMathBinary` (4.3) and `VarkaMathCompiler` (4.5); rewrite the class
   Javadoc's paragraph on row 358 to say `pow` and `hypot` fuse.
6. Test fixtures of a new node, each beside `Fma`'s entry (`m8/VARKA-489.md`, "Adding a node,
   after 489c", steps 4 and 5): `VarkaReference` (a `mathDouble2(MathFn, double, double)` that
   calls `StrictMath.pow` and `Math.hypot` **by name**, never through `fn.owner()`, and a
   `MathBinary` arm that is null where either operand is); `VarkaIrGrammar.boundsOf`;
   `VarkaGuardSpecs`; `VarkaWordCensus` (pure over both operands); `VarkaSmtNodes` (an entry
   "a floating function of two operands: the prelude has no double as a value");
   `VarkaCanonicalIr` (`case "math2"`); `VarkaReproductionSuite` (one `MathBinary` in its tree
   of every node type); `VarkaVectorIRSharingSuite` (its test "every node with a child caches
   the hash its components give, last" builds one instance of every record by reflection, in
   `anyNodeOf`, trying the first six constants of each enum; `POW` and `HYPOT` come later, so
   with that bound no `MathBinary` is built and the test fails. Make the loop
   `for (int k = 0; k < 6; k++)` read `k < VarkaVectorIR.MathFn.values().length`, with the
   comment "Every constant of the IR's largest enum, MathFn, whose two-operand members come
   last and are the only ones MathBinary takes (VARKA-358)."); and in the Scala suites
   `VarkaLaneTypeSuite` (the floating-only lists, an instance recipe, an emit at `DOUBLE` with
   `withLanesOverride(8)`), `VarkaWithChildrenSuite` and `VarkaIrFuzzSuite` (the undrawn
   lists, and a rebuild test), `VarkaEmitCostSuite` (`awaitingRefit` gains `"MathBinary"`),
   `VarkaEmitterBudgetSuite` (a register row
   `(new MathBinary(MathFn.HYPOT, dc, dc), 1, 0, VarkaMatrix.base.withLanesOverride(8))`),
   `VarkaRangeAnalysisSuite` (`admissible` and the walk).
7. `VarkaMathExactSuite.java` (catalyst, `src/test/java`, package `...codegen.varka`). On
   master `6dafb8bd2f6` the file is byte for byte the one branch `varka-358a2-r2` started from
   (both compare equal to the copy at `79d36b86965`), so the branch's pieces go in as they are;
   the one thing adapted is the NaN comment of piece e (`hypot` calls `Math`, 4.1). The pieces,
   each with its place in the file. Another part may have merged first and added a member or a
   cell beside these; the places below are named by what they follow, and hold either way.

   a. Imports: `java.util.OptionalLong` after `java.util.Locale`, and
      `org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.MathBinary` after
      the import of `VarkaVectorIR.LiteralSlot`.
   b. A constant, after `BATCH`:

            /**
             * The sampled inputs a function under a planted fault, about 2^20: the other
             * class's method differs on thousands of inputs in a million where it differs at
             * all, so a sixteenth of the differential's draws sees it, and the suite stays
             * inside its watchdog with 20 functions.
             */
            private static final int PLANT_SAMPLES = SAMPLES >> 4;

   c. `enum Domain` gains `POW` as its last member. The comment of the column `B` becomes
      "The second column: the base of {@code log(b, x)}, or the second operand."
   d. Two cells at the end of the list that `functions(fault)` returns (on master, after the
      cell `log(b, x)`):

            new Fn("pow", new MathBinary(MathFn.POW, X, B), new long[0], 2,
                (c, y) -> StrictMath.pow(c, y), Domain.POW, true, false),
            new Fn("hypot", new MathBinary(MathFn.HYPOT, X, B), new long[0], 2,
                (c, y) -> Math.hypot(c, y), Domain.PLAIN, false, false)

      The fifth component is the domain, the sixth `classesDiffer`: `Math.pow` is a stub on
      x86 where Spark calls `StrictMath.pow`, and `Math.hypot` delegates to `StrictMath.hypot`
      on JDK 25, so the planted other class moves `pow` and not `hypot`.
   e. In `mismatch`: the text of `at` reads `", " + hex(b)` in place of `", base " + hex(b)`,
      and between the `if (!valid)` block and the last `return` goes:

            if (f.root() instanceof MathBinary && VarkaSameAnswer.sameAnswer(f.root(),
                Double.doubleToRawLongBits(want), got, operands(f.root(), want, x, b))) {
              // Two NaN operands: fdlibm's hypot and pow answer a sum of the two, and the same
              // method answered x's payload in the kernel and y's in this scalar call on one
              // JVM (VARKA-358.md 4.4), so Spark's own answer is either NaN: the open NaN of
              // VarkaSameAnswer's row level.
              return null;
            }

   f. The helper `operands`, a new method directly after `mismatch`. It is the row
      `VarkaSameAnswer` reads: the root's value is Spark's answer, the two columns' values
      are the row's inputs, any other node has no value, and no condition holds.

            /**
             * The row {@link VarkaSameAnswer}'s rule reads: Spark's answer {@code want} at
             * {@code root}, the two columns' values below it, and no condition.
             */
            private static VarkaSameAnswer.IrRow operands(VarkaVectorIR root, double want,
                double x, double b) {
              return new VarkaSameAnswer.IrRow() {
                @Override public OptionalLong value(VarkaVectorIR node) {
                  return node.equals(root) ? OptionalLong.of(Double.doubleToRawLongBits(want))
                      : node.equals(X) ? OptionalLong.of(Double.doubleToRawLongBits(x))
                      : node.equals(B) ? OptionalLong.of(Double.doubleToRawLongBits(b))
                      : OptionalLong.empty();
                }

                @Override public boolean holds(VarkaVectorIR.Cond cond) {
                  return false;
                }
              };
            }

      `VarkaSameAnswer.IrRow` and `sameAnswer(VarkaVectorIR root, long want, long got, IrRow
      row)` are on master (`grep -c 'public interface IrRow'` on `VarkaSameAnswer.java`
      prints `1`).
   g. `powDraw`, a new method directly before `ownDraw`:

            /**
             * {@code pow}'s own hard pairs: {@code x} within {@code 2^-20} of 1 with
             * {@code |y|} past {@code 2^31}, fdlibm's weak branch; a negative {@code x} with an
             * integral {@code y} and with a non-integral one; and an integral {@code y} over
             * any {@code x}.
             */
            private static double[] powDraw(SplittableRandom rnd) {
              double sign = rnd.nextBoolean() ? 1.0 : -1.0;
              return switch (rnd.nextInt(4)) {
                case 0 -> new double[] {
                    1.0 + sign * Math.scalb(rnd.nextDouble(), -20 - rnd.nextInt(33)),
                    (rnd.nextBoolean() ? 1.0 : -1.0)
                        * Math.scalb(1.0 + rnd.nextDouble(), 31 + rnd.nextInt(30))};
                case 1 -> new double[] {-rnd.nextDouble(0.0, 1e3), rnd.nextInt(-64, 65)};
                case 2 -> new double[] {-rnd.nextDouble(0.0, 1e3), rnd.nextDouble(-64.0, 64.0)};
                default -> new double[] {
                    Double.longBitsToDouble(rnd.nextLong()), rnd.nextInt(-1100, 1100)};
              };
            }

      In `ownDraw`, the arm `case UNIT, PLAIN ->` becomes `case UNIT, PLAIN, POW ->` (its
      comment gains "; pow's pairs are drawn by powDraw"): the arm only has to exist, since
      the loop of piece h never asks `draw` for `pow`'s own draw.
   h. `sampledMismatches` takes the number of inputs and draws `pow`'s pairs together. Its
      signature becomes

            private static long sampledMismatches(Fn f, VarkaEmitOptions options, int samples,
                List<String> firstFew) {

      `perDraw` reads `samples` in place of `SAMPLES`, and the loop that fills the columns,
      which on master fills one column after the other, becomes row by row:

            for (int i = 0; i < BATCH; i++) {
              if (f.domain() == Domain.POW && d == 4) {
                double[] xy = powDraw(rnd);
                cols[0].data().set(ValueLayout.JAVA_DOUBLE, i * 8L, xy[0]);
                cols[1].data().set(ValueLayout.JAVA_DOUBLE, i * 8L, xy[1]);
              } else {
                for (Col col : cols) {
                  col.data().set(ValueLayout.JAVA_LONG, i * 8L, draw(f, d, rnd));
                }
              }
            }

      `d == 4` is the last of the `DRAWS` draws, the one `draw` gives to the function's own
      domain. The two callers pass the count: `sampledDifferential` passes `SAMPLES`, and
      `otherClassIsCaught` passes `PLANT_SAMPLES`.
   i. `fixedVectors()`: the array `edges` gains `0x1p31, -0x1p31` after `0.5`.
   j. Comments, so that the file says what it does: the class Javadoc's sentence on raw bits
      gains "The one exception is a two-operand function over two NaNs, whose answer the
      same method gave as either operand's payload on one JVM; there two NaNs agree, by
      {@link VarkaSameAnswer}'s row level."; the Javadoc of `Code` and of `Fn` say "a
      two-operand function" where they say `log(b, x)` alone; the Javadoc of
      `fixedVectorFailures` says the second column is also `y` of `pow` and `hypot`; the
      Javadoc of `otherClassIsCaught` adds `Math.pow` against `StrictMath.pow` to the
      functions that must fail and `hypot` to those the plant leaves alone.
8. `VarkaMathCompilerSuite`: move `pow(x, y)` and `hypot(x, y)` from `FUNCTIONS` to `FUSED`
   (the test's count of 27 does not move). `VarkaCoverageSuite`, family "Math functions":

        Row("pow(x, x2)", "exact: a per-lane StrictMath.pow call, as Spark's code makes, "
          + "fdlibm's weak branch included",
          math = Some(MathCell(exactForms.head, operator = Some(VarkaMathBinding.Operator.POW)))),
        Row("power(x, 3.0D)", "the alias of pow; the constant exponent a literal slot",
          math = Some(MathCell(exactForms.head))),
        Row("hypot(x, x2)", "exact: a per-lane Math.hypot call, as Spark's code makes",
          math = Some(MathCell(exactForms(2), operator = Some(VarkaMathBinding.Operator.HYPOT)))),

9. `sql/varka/matrix/skips.tsv`: for each of the three rows, two lines, copied from the lines of
   `Math functions: expm1(x) matches the row engine under both consumers` (one under
   `validityByWidth=false`, one under `lanesOverride=16`) with the SQL replaced and the reason's
   list of functions reading `(pow, hypot)` and `(VARKA-358)`. The file is tab separated, five
   fields a line. The first of the six lines, with `<TAB>` standing for one tab character:

        validityByWidth=false<TAB>VarkaCoverageHarnessSuite<TAB>Math functions: pow(x, x2) matches the row engine under both consumers<TAB>fails<TAB>validityByWidth=false leaves no baked lane count, and a per-lane math call (pow, hypot) is unrolled over the baked double lanes, so the compiler declines the row (VARKA-358); the row engine answers it

   The test's name is the coverage row's SQL, exactly as step 8 writes it, between
   `Math functions: ` and ` matches the row engine under both consumers`.
10. `VarkaMathBaselineBenchmark`: `pow(P, Y)`, `hypot(X, Y)` and `pow(P, 3.0D)` become
    `Fusion.WHOLE`; correct the class comment's sentence that lists the `NONE` sections.
11. `sql/varka/plans/m8/census_curated.txt`, three lines replaced:

        pow|double|double|double|done|358|exact: a per-lane StrictMath.pow call, fdlibm's weak branch included; the guard and exact nodes are the opt-in form's (VARKA-358)
        power|double|double|double|done|358|alias of pow (VARKA-358)
        hypot|double|double|double|done|358|exact: a per-lane Math.hypot call, as Spark's code makes (VARKA-358)

12. The plan: this file replaces nothing on master, so add it as
    `sql/varka/plans/m8/VARKA-358.md` with section 11 filled; in `PLAN.md` open row 358's text
    with the part's line (section 11 gives it).

**The tests, and the failure only each catches.**

| suite | what it holds | the failure only it catches |
| :--- | :--- | :--- |
| `VarkaMathExactSuite` (JUnit, own JVM) | `pow` and `hypot` against Spark's code as scalar Java over 2^24 sampled pairs, the fixed vectors at 2 and 8 lanes over nulls, the planted other class | a call of the wrong class or with swapped operands; a word that forgets one operand |
| `VarkaMathCompilerSuite` | the fused and declined tables | a function that fuses with no test, or declines with another reason |
| `VarkaSameAnswerContractSuite` | the open-NaN rule at both fronts | a two-NaN lane compared bit for bit, which fails one run in some |
| the registry suites of step 6 | the node in every enumeration | a switch or a list that lacks the node |
| `VarkaCoverageSuite`, then the coverage differential in the gate | the three rows against the row engine | an operand cast or a null the unit suite does not build |

**The commands, in order.** Every sbt line is run as

    JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 SPARK_LOCAL_IP=127.0.0.1 build/sbt '<task>'

and is done when sbt prints `[success]`.

1. `catalyst/Test/compile`, then `sql/Test/compile`.
2. `catalyst/testOnly *VarkaMathExactSuite *VarkaMathCompilerSuite *VarkaReproductionSuite
   *VarkaSameAnswerContractSuite *VarkaVectorIRSharingSuite`: the JUnit summary reads
   `Failed 0, Errors 0`, and each of the five suites runs as many tests as before the part,
   since the part adds cells and cases and no test method. Read the count before the first
   edit, for each suite's file `<F>`: `grep -c '^  @Test$' <F>`. On master `6dafb8bd2f6` the
   five print `5`, `8`, `26`, `4` and `7`; a part of row 357 merged first makes the first
   larger. A suite that runs fewer tests than its count has skipped some: read why.
3. `catalyst/testOnly *VarkaLaneTypeSuite *VarkaWithChildrenSuite *VarkaIrFuzzSuite
   *VarkaEmitCostSuite *VarkaEmitterBudgetSuite *VarkaRangeAnalysisSuite *VarkaSmtNodesSuite
   *VarkaNodePropsSuite *VarkaCoverageSuite`: `All tests passed.`
4. `python3 dev/varka_push_check.py --no-jvm`: no finding of rule R1 names `MathBinary`.
5. `dev/varka_matrix.sh --config validityByWidth=false --config lanesOverride=16 --suites
   VarkaCoverageHarnessSuite,VarkaCoverageDifferentialSuite`: no failing test and no stale line.
6. The planted checks, each run once and reverted: with `HYPOT`'s arguments swapped in
   `emitMathBinary` (`x` and `y` read in the other order) the exact suite must fail `pow` and
   pass `hypot`, which is symmetric; with the `emitAndWord` call removed it must report
   "a null operand answered valid".
7. `dev/varka_ops/pre-pr-slot.sh <worktree>`: `pre-pr: passed on <sha>`. It regenerates
   `coverage.json`, the docs' blocks, `emitted_bytes.json` and `census.tsv`; read the diff and
   see that only the three rows, their shapes and the three census lines moved.

**Done when** the pull request is merged with a green Build and a green option matrix, and
`python3 dev/varka_census.py --summary` reads three more entries fused than before it.

**Not here.** No `atan2` (row 356's part). No change to `ASIN`, `ACOS` or `ATAN`'s owner
(part 356b of `VARKA-356.md` 12.6). No allocation window for a two-operand call (section 9,
item 5). No fuzz production: `MathBinary` joins the undrawn lists. No port, no
emit flag, no `LIBRARY` form. No results file: the three sections' numbers are 358r's.

### 5.2 Part 358r

- **Title:** `[VARKA-358] Refresh the math baseline's runner results with the family fused`
- **Branch:** `varka-358r-results`, from master, after 358a2, row 356's `atan2` part and row
  357's two parts are merged. **Builder:** Sonnet-class.
- **Size:** results and provenance files (not counted) and about 60 plan lines.

**B0.** This prints `0`:

    grep -c 'Fusion.NONE' sql/core/src/test/java/org/apache/spark/sql/execution/benchmark/VarkaMathBaselineBenchmark.java

That is, every section of the class fuses. If it prints more, a fusing part is not merged:
wait for it. The one exception: a function that `VARKA-357.md` section 11 records as left out
of 357a2 keeps its `Fusion.NONE`. Then the count must equal the number of functions that
section names, 358r goes on, and its outcome says which sections are still the row path's.

**Steps.**

1. Open the pull request with the plan lines only (section 7's predictions restated in section
   11 as "registered"), so that the runs have a pull request number.
2. Dispatch the class at both widths, as `sql/varka/skills/benchmarking.md` writes it:

        gh workflow run benchmark.yml -R vecbricks/varka -f pr=<n> -f jdk=25 -f class=VarkaMathBaselineBenchmark -f expected-cpu="AMD EPYC 7763"
        gh workflow run benchmark.yml -R vecbricks/varka -f pr=<n> -f jdk=25 -f class=VarkaMathBaselineBenchmark -f expected-cpu="AMD EPYC 7763" -f extra-java-options="-XX:MaxVectorSize=16"

   A run that drew another CPU fails before the build, at the step "Probe the machine's CPU
   and vector datapath", with `CPU mismatch!`. Do not dispatch again: the workflow
   `varka-bench-cpu-retry.yml` reruns the failed job an hour later, up to six attempts of the
   one run. Wait for it (`gh run watch <run> -R vecbricks/varka`, or look again later).
   Dispatch again only after its sixth attempt failed, which the retry workflow reports as
   "gave up: attempt 6 of 6 is the cap". A second dispatch before that measures twice.
3. Bring each green run's files into the tree. A run with `pr` set commits nothing; its files
   are the run's artifact, one tar. The run's id is the first column of
   `gh run list -R vecbricks/varka --workflow benchmark.yml --limit 5`. For each of the two
   runs, into a new empty directory `<dir>` outside the worktree:

        gh run download <run> -R vecbricks/varka -n benchmark-results-1-VarkaMathBaselineBenchmark-25-2.13-1 -D <dir>
        tar -xf <dir>/benchmark-results-25-2.13.tar -C <dir>
        ls <dir>/sql/core/benchmarks

   The artifact's name is `benchmark-results-`, the class's place in the dispatch and its
   name, the JDK, the Scala version and the split. `ls` prints two files, the plain names a
   CI run always writes: `VarkaMathBaselineBenchmark-jdk25-results.txt` and
   `VarkaMathBaselineBenchmark-jdk25-provenance.txt`. Read the provenance file first: its
   `cpu:` line must hold `AMD EPYC 7763`, and its `inputs:` line tells the two runs apart
   (`extra-java-options=<none>` or `extra-java-options=-XX:MaxVectorSize=16`). Then copy each
   file over the committed runner file of its width, under `sql/core/benchmarks/`:

    | the run's `inputs:` line ends | the tar's file | is committed as |
    | :--- | :--- | :--- |
    | `extra-java-options=<none>` | `...-jdk25-results.txt` | `VarkaMathBaselineBenchmark-jdk25-runner-epyc7763-results.txt` |
    | `extra-java-options=<none>` | `...-jdk25-provenance.txt` | `VarkaMathBaselineBenchmark-jdk25-runner-epyc7763-provenance.txt` |
    | `...-XX:MaxVectorSize=16` | `...-jdk25-results.txt` | `VarkaMathBaselineBenchmark-jdk25-runner-epyc7763-128bit-results.txt` |
    | `...-XX:MaxVectorSize=16` | `...-jdk25-provenance.txt` | `VarkaMathBaselineBenchmark-jdk25-runner-epyc7763-128bit-provenance.txt` |

   The whole file is replaced, on purpose: the run did not set `sections`, every section of
   the class was measured again on one machine, and the committed provenance's `spliced:`
   notes describe sections that are gone with it. Add no file under the plain name. Then
   `git status --short sql/core/benchmarks` lists exactly the four files as modified.
4. Commit the four files, and post each run's link on the pull request.
5. Compare with the committed baseline, and the same for the 128-bit file:

        python3 dev/varka_bench_diff.py --git master sql/core/benchmarks/VarkaMathBaselineBenchmark-jdk25-runner-epyc7763-results.txt

   Score section 7's predictions from the two outputs into
   section 11 of the three rows' plans, each as right, wrong or right in part with the numbers.

**Done when** the four files are committed from runs on an AMD EPYC 7763 and each prediction of
section 7 carries its score. **Not here:** no code; a prediction that fails is recorded, and a
repair is a new row.

## 6. Tests, proofs, fuzzing and coverage

No proof file: a per-lane call is exact by calling the method, not by a bound. The IR fuzzer
does not draw `MathBinary` (the undrawn lists; question 3). The Spark-level fuzzer draws no math
function today, and this part does not change that. Coverage: three rows, each
`"verified": "differential"` once regenerated. JaCoCo and the mutation run do not apply: the new
branches are one `if` in a constructor and one in the emitter, each with a planted check above.

## 7. Measurement, and predictions registered before any run

The sections exist and their row-path baselines are committed
(`VarkaMathBaselineBenchmark-jdk25-runner-epyc7763-results.txt` and its `-128bit-` companion:
62 sections each, `pow(p, y)`, `hypot(x, y)` and `pow(p, 3.0)` among them, each with and without
nulls). No arm is added: the per-lane call has no losing arm to keep. Registered now, for 358r
to score on the EPYC 7763 at 256 and at 128 bits:

1. No existing shape's bytes move (358a2's regeneration scores it: yes or no).
2. `pow(p, y)`, `hypot(x, y)` and `pow(p, 3.0)`, with and without nulls: "varka on" within 0.8
   to 1.3 times the row path's best time, at both widths (`VARKA-345.md` section 4, prediction
   2: a lone call gains nothing on the call).
3. `VarkaMathExactSuite`'s time grows by the two new functions' share: 20 cells against 18,
   so under 1.2 times its time before the part on the same machine.

## 8. Who builds it

| part | builder | why |
| :--- | :--- | :--- |
| 358a2 | full strength, before 1 December | two reasons remain after review 1. Step 6: a new IR record's test fixtures, in fourteen test files, are lists the type checker does not name, so a missed one shows only as a red suite whose message has to be traced to its list. And 4.4: the rule about what the JIT leaves open is added to `VarkaSameAnswer` at both fronts, and a builder that "fixes" a red two-NaN lane by comparing NaNs in a suite, or by widening the rule, would get it wrong without any test saying so. Steps 1 to 5 and 7 to 12 are mechanical: their code is written out, step 7's included |
| 358r | Sonnet-class | two dispatches, four files, a diff and a table |

## 9. Risks, and what only a JVM settles

1. **The cherry-pick's conflicts hide a missed arm.** Held by step 4's compile, by
   `dev/varka_push_check.py --no-jvm` (rule R1 reads the registries as text) and by command 3.
2. **`Math.hypot` on a later JDK.** With `HYPOT` naming `java/lang/Math` the kernel follows
   Spark on every JDK. Not settled here: that `Math.hypot` and `StrictMath.hypot` agree on
   JDK 25 at every tier. `VARKA-356.md` item 0.3 measured it on 2^20 inputs; the exact suite's
   other-class plant asserts it again (`hypot` must not move). Command 2 settles it.
3. **The two-NaN rule is too wide.** It opens only lanes where both operands are NaN with
   different bits. Held by the contract suite and by the fixed vectors, whose nine NaN patterns
   meet each other in the second column.
4. **A matrix configuration this plan did not name declines the rows.** Held by the option
   matrix on the pull request; a failing configuration gets its `skips.tsv` line with the
   reason, as step 9's.
5. **Only a JVM settles:** that the kernel's bits equal Spark's (command 2); that a warmed
   two-operand kernel allocates nothing a batch. The second is not asserted by the suite: the
   allocation test runs the first function of the list, a one-operand call. **Decided: 358a2
   does not add a `hypot` window.** A red window could only be answered by a JIT diagnosis
   (why C2 boxed the vector), which is no step of this part and would hold the three
   functions' merge for a cost question; the bits do not depend on it; and the cost is
   measured anyway, by 358r's sections, where a kernel that boxes a vector a lane shows as a
   time far outside prediction 2's band. If it does, 358r files the allocation window and the
   diagnosis as one row of `m10/SCOPE.md`, citing the results line. Section 11 records the
   gap.

## 10. Questions for the owner

1. **Which class a call names where Spark calls `Math` and JDK 25 delegates to `StrictMath`.**
   (a) The class Spark's code names, `Math`: exact on every JDK. (b) `StrictMath`, as
   `VARKA-345.md` 2.1 and master's `ASIN`, `ACOS` and `ATAN` have it: exact on JDK 25, and wrong
   without notice on a JDK that gives the method an intrinsic (JDK 26 has one for `sinh`).
   *Recommended: (a).* **Default applied: (a), everywhere**: for every new member (`HYPOT`
   here, `ATAN2` in row 356, `SINH`, `COSH` and `TANH` in row 357), and for master's `ASIN`,
   `ACOS` and `ATAN`, which part 356b of `VARKA-356.md` 12.6 flips (three tokens, three
   coverage notes and a regeneration). 356b is built and does not wait for the answer. This
   is the one question of the three plans on the matter: `VARKA-356.md` 12.7 and
   `VARKA-357.md` section 10 point here. An answer of (b) reverts 356b and the five new
   members' owners, one token each.
   **Answered on 28 November 2026: (a), everywhere.** The owner first answered (b) for master's
   `ASIN`, `ACOS` and `ATAN`, then agreed with the recommendation once the five new members
   were put beside them: every member names the class Spark's code names, and 356b is built.
2. **Is 358b, the per-port emit flags, owed for the close?** (a) No: item 5 is met by the calls
   and the ports that exist; the branch stays. (b) Yes: land `varka-358b-exp-port` rebuilt on
   master, with `mathPortExp` and `mathPortExpm1` off by default. *Recommended: (a)*, since row
   636, the port at two long lanes, was moved on the reading that an option arm's speed is no
   item of 1.1, and since no port may be a default before a JDK above 25 is compared
   (`m8/PLAN.md` 2.7.1). **Default applied: (a).**
3. **The IR fuzz productions of the per-lane calls.** (a) Not a gate: the three bit-identity
   tests are the sampled differential, the fixed vectors and the coverage differential.
   (b) A gate: one part draws `MathUnary`'s call members and `MathBinary` in the floating
   sequence. It moves committed fuzz seeds and needs the open-NaN rule in the fuzzer's
   compare, so it is a full-strength part. *Recommended: (a) now, (b) as a milestone 10 row.*
   **Default applied: (a).**
4. **The vectors files.** (a) The fixed vectors stay in the suites, as built. (b) Build the
   `sql/varka/math/<fn>.vectors` format now. *Recommended: (a)*: the second reader is row 367.
   **Default applied: (a).**

## 11. Outcome

To be written by each part. 358a2 opens row 358's text in `PLAN.md` with: "**Started**
(*<date>*: 358a2 merged as #<n>: `pow`, `power` and `hypot` fuse through `MathBinary`, a
two-operand per-lane call, bit for bit Spark's code, a lane of two different NaNs open;
`VARKA-358.md`; left for the close: 358r, the baseline's runner results)". 358a2 also records
here that no test holds a two-operand kernel to "allocates nothing a batch" (section 9, item
5). 358r turns it to
**Done**, naming what moved to `m10/SCOPE.md` item 140 as section 1's second table lists it.
