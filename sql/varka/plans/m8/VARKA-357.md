# VARKA-357: the six hyperbolic functions, bit for bit

*Planned on 28 November 2026 for builders that have only this file and the repository's
`AGENTS.md` files. It stands alone. Its code facts were checked on master `6dafb8bd2f6`. The row
is **Started**: its baseline is merged, no function fuses. Every section of this file is new:
until today rows 356, 357 and 358 shared `VARKA-356.md`, which keeps the three rows' design.
No JVM was started for this plan; section 9 lists what only a JVM settles, with its command.*

*Revised the same day after review 1: 4.3's paragraph on depth, 5.1 command 2, 5.2 (steps 4 and
5, the paragraph after the steps, commands 3 and 4, the done-when) and sections 6, 9, 10 and 11
changed. No step of 5.2 ends in "stop and report" any more.*

## 1. What this row is, and what is left of it

Row 357 of `m8/PLAN.md` is `sinh`, `cosh`, `tanh`, `asinh`, `acosh` and `atanh` over `DOUBLE`,
exact by default: a kernel answers the bits vanilla Spark answers. None of the six reads the
ANSI setting, so one answer serves both.

**Merged.** The baseline: `VarkaMathBaselineBenchmark` holds the six functions' sections (#1576)
and their row-path results from the EPYC 7763 runners at both widths are committed (section 3,
line 5). The row's note in `PLAN.md` still says the baseline is "open as a pull request"; that
is stale.

**Left for the close.** Item 5 of `m8/PLAN.md` 1.1, "the math family is bit-exact to Spark by
default", is read by the close path (12.5, decision 3 of 27 November 2026) as met by the
per-lane calls and the ports that exist, and "the per-lane calls the item asks for and master
lacks stay owed here: ... the six hyperbolic functions (357)". The row's own done-when asks
that each fuses bit for bit, "the compositions reproduced branch by branch, `tanh`'s saturation
bit for bit". So the row owes two parts:

| part | what | counted lines |
| :--- | :--- | ---: |
| 357a1 | `sinh`, `cosh` and `tanh` as per-lane calls | about 220 |
| 357a2 | `asinh`, `acosh` and `atanh` as Spark's own expressions over nodes that exist | about 480 |

The runner measurement of the six sections is part 358r of `VARKA-358.md`, one run for the
whole family after its last fusing part merges; section 7 here registers this row's predictions.

**Not owed for the close, and not built from this plan** (defaults; section 10 asks the owner):

| piece | where it goes | why |
| :--- | :--- | :--- |
| the `sinh` and `cosh` `PORT` arms (357b) | `m10/SCOPE.md` item 140 | the owner's decision 3 of 27 November 2026 |
| "one call a lane" for `asinh` and `acosh` (`VARKA-356.md` 2.4) | not a gate | it needs a per-lane call that skips lanes, which no part builds; the bits do not depend on it |
| the opt-in `LIBRARY` entries | milestone 10 | they wait on row 367 |
| IR fuzz productions, vectors files | not a gate | `VARKA-358.md` section 10, questions 3 and 4 |

**What must be merged before it starts.** Nothing. Both parts use nodes that are on master:
`MathUnary` with `LOG` and `LOG1P` (#1643), the lanewise `SQRT`, the IEEE arithmetic and
compares, `IntNeg` and `IfElse`. 357a2 starts after 357a1 merges, since both edit the same lists.

## 2. What a builder must know

**Terms.** A *kernel* is the class Varka emits for one shape of expressions; it computes on
vector *lanes* and Spark's row engine answers whatever it declines. A *per-lane call* reads each
lane out of the vector, passes it to a scalar Java method and writes the result back. A kernel
has no branch: a branch of Spark's code becomes a *blend*, `IfElse(cond, then, otherwise)`,
with every branch computed in every lane. The *word* of a node is its validity, one bit a row.

**What Spark computes** (`mathExpressions.scala`, `ExpressionImplUtils.java`):

| function | Spark's generated code |
| :--- | :--- |
| `sinh`, `cosh`, `tanh` | `java.lang.Math.sinh(c)`, `cosh(c)`, `tanh(c)` |
| `asinh` | `ExpressionImplUtils.asinh(c)`: five branches over `StrictMath.log`, `StrictMath.log1p` and `Math.sqrt`, then `Math.copySign(w, c)` |
| `acosh` | `ExpressionImplUtils.acosh(c)`: five branches over the same three |
| `atanh` | `0.5 * (java.lang.StrictMath.log1p(c) - java.lang.StrictMath.log1p(- c))` |

Each is null only where its operand is null. `atanh` calls the bare `log1p`, with no domain
null: `atanh(1)` is `+inf`, `atanh(2)` is a NaN.

**The names the parts read** (all on master):

| name | where | meaning |
| :--- | :--- | :--- |
| `MathFn`, `MathUnary(MathFn fn, VarkaVectorIR child)` | `VarkaVectorIR` | the call node; a member names `owner()` and `method()` |
| `MathFn.LOG`, `LOG1P`, `SQRT` | `VarkaVectorIR` | `StrictMath.log`, `StrictMath.log1p` per lane, and the lanewise IEEE square root |
| `IntArith(IntOp op, Overflow.WRAP, a, b)` | `VarkaVectorIR` | IEEE `+ - * /` at the double lane |
| `IntNeg(Overflow.WRAP, x)`, `IntNeg(Overflow.WRAP, x, true)` | `VarkaVectorIR` | the sign flip, and `abs`: both are bit operations, so a NaN keeps its payload |
| `Compare(CompareOp.IEEE_LT / IEEE_LE / IEEE_EQ, a, b)` | `VarkaVectorIR` | Java's `<`, `<=`, `==` on doubles: false where either side is a NaN |
| `IfElse(Cond cond, then, otherwise)` | `VarkaVectorIR` | the blend; where `cond` is unknown (a null operand) the lane takes `otherwise` and that arm's validity |
| `VarkaNodeCompiler.doubleSlot(double, DeclineSink)` | catalyst main | a `DOUBLE` literal slot of the kernel's table |
| `FloatingFault`, `DeclineSink.floatingFault()` | `VarkaEmitOptions`, `DeclineSink` | the planted faults of the floating family, read by the compiler |
| `VarkaMathDomain` | catalyst main | the precedent: a tree builder shared by the compiler and `VarkaMathExactSuite` |

**The names the parts add:**

| name | part | meaning |
| :--- | :--- | :--- |
| `MathFn.SINH`, `COSH`, `TANH` | 357a1 | `java/lang/Math`'s `sinh`, `cosh`, `tanh` |
| `VarkaMathHyperbolic.asinh`, `acosh`, `atanh` | 357a2 | the three trees of section 4.3 |
| `FloatingFault.MATH_EDGE_2P28` | 357a2 | the plant: the `2^28` test of `asinh` and `acosh` one double low |

## 3. The admission check

Run from the repository root; stop if a line prints something else.

1. The six decline today, naming this row.

        grep -c 'new Owner("[a-z]*", 357)' sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/VarkaMathCompiler.java

   prints `6`.
2. Spark's branches are as section 4.3 restates them.

        grep -n 'ax < 1.0 / (1 << 28)\|ax > (1 << 28)\|x >= (1 << 28)\|Math.copySign(w, x)' sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/ExpressionImplUtils.java

   prints four lines.
3. The nodes exist: `grep -c 'LOG1P("java/lang/StrictMath", "log1p")'` on `VarkaVectorIR.java`
   prints `1`, and `grep -c 'public IntNeg(Overflow mode, VarkaVectorIR child, boolean abs)'`
   on the same file prints `1`.
4. No class of this plan exists: `git ls-files | grep -c VarkaMathHyperbolic` prints `0`, and
   `git branch --list 'varka-357a1*' 'varka-357a2*'` prints nothing.
5. The baseline is committed: `grep -c 'DOUBLE asinh(x), no nulls over'` on
   `sql/core/benchmarks/VarkaMathBaselineBenchmark-jdk25-runner-epyc7763-results.txt` prints
   `1`, and the same on the `-128bit-results.txt` file prints `1`.
6. The built copy exists and is not merged: commit `ad79d576ef9` of branch
   `varka-357-math-family-b` in `/home/max/proj/vecbricks-varka` (20 November 2026, ten files,
   320 added lines). It stands on a stack that master no longer has (`VarkaMathFault`, an older
   `MathBinary`), so no part starts from it: read it with `git show`, do not cherry-pick it.

## 4. The design

### 4.1 The three calls (357a1)

| member | owner (internal name) | method | on JDK 25 |
| :--- | :--- | :--- | :--- |
| `SINH` | `java/lang/Math` | `sinh` | delegates to `StrictMath.sinh`, fdlibm |
| `COSH` | `java/lang/Math` | `cosh` | delegates to `StrictMath.cosh`, fdlibm |
| `TANH` | `java/lang/Math` | `tanh` | the host's routine: an Intel stub on x86, fdlibm on aarch64 |

**Decision: each member names `java/lang/Math`, the class Spark's generated code names.**
`VARKA-345.md` 2.1 and the branch give `SINH` and `COSH` the owner `java/lang/StrictMath`,
"which `Math` delegates to". That is true of JDK 25 only. On this laptop:

    grep -a -c '_dsinh' /usr/lib/jvm/java-26-openjdk-amd64/lib/server/libjvm.so
    grep -a -c '_dsinh' /usr/lib/jvm/java-25-openjdk-amd64/lib/server/libjvm.so

The first prints a count above 0, the second `0` (28 November 2026): JDK 26 answers `Math.sinh`
from a routine of its own on x86. A kernel that called `StrictMath.sinh` there would answer
other bits than Spark's row engine, and no fallback catches a wrong answer. Calling `Math.sinh`
is Spark's bits on every JDK, because HotSpot answers one `Math` method from one routine in the
interpreter, C1 and C2 (`VARKA-345.md` item 0.3).

Each lowers through `emitMathLaneCall` with no emitter change beyond the list of members in
`emitMathUnary`'s operator switch. `tanh`'s saturation (`+-1.0` from about `abs(x) >= 22`) is
the method's own answer and is held by the fixed vectors at `+-22.0`.

### 4.2 The planted other class, per function

`FloatingFault.MATH_OTHER_CLASS` makes a call name the other of `Math` and `StrictMath`. On x86
with JDK 25 the suite expects, and asserts on x86 only:

| function | plant changes answers | why |
| :--- | :--- | :--- |
| `sinh`, `cosh` | no | `Math` delegates to `StrictMath` |
| `tanh` | yes | `Math.tanh` is the stub, `StrictMath.tanh` is fdlibm |
| `asinh`, `acosh` | yes | their `LOG` call becomes `Math.log`, a stub |
| `atanh` | no | `Math.log1p` delegates |

The branch's run of 20 November 2026 saw exactly this (`VARKA-356.md`, the branch's record of
357a: "failing `tanh`, `asinh` and `acosh`, leaving `sinh`, `cosh` and `atanh` unchanged").
The "no" lines are facts of JDK 25, which the build requires.

### 4.3 The three trees (357a2)

New file `VarkaMathHyperbolic.java` in
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/`. The
compiler and the suite build the same tree through it, so the suite checks the tree the compiler
emits and not a copy. `lit` makes a `DOUBLE` literal node of a value.

    public final class VarkaMathHyperbolic {
      private VarkaMathHyperbolic() { }

      /** {@code 1 << 28} as Spark's code compares it, or the double below it under the plant. */
      private static double twoP28(FloatingFault fault) {
        return fault == FloatingFault.MATH_EDGE_2P28 ? Math.nextDown(0x1p28) : 0x1p28;
      }
      private static VarkaVectorIR add(VarkaVectorIR a, VarkaVectorIR b) {
        return new IntArith(IntOp.ADD, Overflow.WRAP, a, b);
      }
      // sub, mul and div the same over IntOp.SUB, MUL and DIV.
      private static VarkaVectorIR sqrt(VarkaVectorIR a) {
        return new MathUnary(MathFn.SQRT, a);
      }
      private static Cond lt(VarkaVectorIR a, VarkaVectorIR b) {
        return new Compare(CompareOp.IEEE_LT, a, b);
      }
      private static Cond le(VarkaVectorIR a, VarkaVectorIR b) {
        return new Compare(CompareOp.IEEE_LE, a, b);
      }

      public static VarkaVectorIR asinh(VarkaVectorIR x, DoubleFunction<VarkaVectorIR> lit,
          FloatingFault fault) {
        VarkaVectorIR ax = new IntNeg(Overflow.WRAP, x, true);
        VarkaVectorIR one = lit.apply(1.0);
        VarkaVectorIR t = mul(x, x);
        Cond over2p28 = lt(lit.apply(twoP28(fault)), ax);
        Cond over2 = lt(lit.apply(2.0), ax);
        VarkaVectorIR farArg = add(mul(lit.apply(2.0), ax), div(one, add(sqrt(add(t, one)), ax)));
        VarkaVectorIR log = new MathUnary(MathFn.LOG, new IfElse(over2p28, ax, farArg));
        VarkaVectorIR log1p = new MathUnary(MathFn.LOG1P,
            add(ax, div(t, add(one, sqrt(add(one, t))))));
        VarkaVectorIR w = new IfElse(over2p28, add(log, lit.apply(StrictMath.log(2.0))),
            new IfElse(over2, log, log1p));
        VarkaVectorIR signed = new IfElse(lt(x, lit.apply(0.0)), new IntNeg(Overflow.WRAP, w), w);
        Cond computed = new And(le(lit.apply(0x1p-28), ax),
            lt(ax, lit.apply(Double.POSITIVE_INFINITY)));
        return new IfElse(computed, signed, x);
      }

      public static VarkaVectorIR acosh(VarkaVectorIR x, DoubleFunction<VarkaVectorIR> lit,
          FloatingFault fault) {
        VarkaVectorIR one = lit.apply(1.0);
        VarkaVectorIR two = lit.apply(2.0);
        Cond atLeast2p28 = le(lit.apply(twoP28(fault)), x);
        VarkaVectorIR farArg = sub(mul(two, x), div(one, add(x, sqrt(sub(mul(x, x), one)))));
        VarkaVectorIR log = new MathUnary(MathFn.LOG, new IfElse(atLeast2p28, x, farArg));
        VarkaVectorIR t = sub(x, one);
        VarkaVectorIR log1p = new MathUnary(MathFn.LOG1P,
            add(t, sqrt(add(mul(two, t), mul(t, t)))));
        VarkaVectorIR rest = new IfElse(new Compare(CompareOp.IEEE_EQ, x, one), lit.apply(0.0),
            new IfElse(lt(two, x), log, log1p));
        return new IfElse(lt(x, one), lit.apply(Double.NaN),
            new IfElse(atLeast2p28, add(log, lit.apply(StrictMath.log(2.0))), rest));
      }

      public static VarkaVectorIR atanh(VarkaVectorIR x, DoubleFunction<VarkaVectorIR> lit) {
        return mul(lit.apply(0.5), sub(new MathUnary(MathFn.LOG1P, x),
            new MathUnary(MathFn.LOG1P, new IntNeg(Overflow.WRAP, x))));
      }
    }

**`asinh`, branch by branch** (`ax = abs(x)`, Spark's order; each test excludes the lanes an
earlier test took):

| Spark's branch | lanes | Spark's `w` | in the tree |
| :--- | :--- | :--- | :--- |
| `isInfinite(ax) or isNaN(ax)` | not `computed` | `ax` | the answer is `x` itself |
| `ax < 1.0 / (1 << 28)` | not `computed` | `ax` | the answer is `x` itself |
| `ax > (1 << 28)` | `over2p28` | `log(ax) + log(2.0)` | `add(log, log(2.0) folded)` |
| `ax > 2.0` | `over2`, not `over2p28` | `log(2.0 * ax + 1.0 / (sqrt(x * x + 1.0) + ax))` | `log` over `farArg` |
| otherwise | the rest | `log1p(ax + t / (1.0 + sqrt(1.0 + t)))`, `t = x * x` | `log1p` |

Then Spark returns `copySign(w, x)`. In the first two branches `copySign(abs(x), x)` is `x`'s own
bits, a NaN's payload and a zero's sign included, so the tree answers the operand and needs no
sign operation. In the other three `x` is finite and not zero and `w` is positive, so
`copySign(w, x)` is `-w` where `x < 0` and `w` elsewhere. The two `log` branches share one call:
its argument is the blend of `ax` and `farArg`. `x * x` squares `x`, not `ax`, as Spark does.
`StrictMath.log(2.0)` is folded when the tree is built; it is the double Spark computes a row.

**`acosh`, branch by branch:**

| Spark's branch | lanes | Spark's answer | in the tree |
| :--- | :--- | :--- | :--- |
| `x < 1.0` | `lt(x, one)` | `Double.NaN` | the literal, bits `0x7ff8000000000000` |
| `x >= (1 << 28)` | `atLeast2p28` | `log(x) + log(2.0)` | `add(log, log(2.0) folded)` |
| `x == 1.0` | `IEEE_EQ` | `0.0` | the literal |
| `x > 2.0` | `lt(two, x)` | `log(2.0 * x - 1.0 / (x + sqrt(x * x - 1.0)))` | `log` over `farArg` |
| otherwise | the rest | `log1p(t + sqrt(2.0 * t + t * t))`, `t = x - 1.0` | `log1p` |

A NaN `x` fails all four tests and takes the last branch, as in Spark: every compare is written
so that a NaN makes it false (`twoP28 <= x`, `2.0 < x`).

**Special values** (the fixed vectors hold each; the bits are the methods' own):

| input | `asinh` | `acosh` | `atanh` |
| :--- | :--- | :--- | :--- |
| a NaN | the same NaN, payload and sign kept | `log1p` of a NaN: a NaN | a NaN |
| `+inf` | `+inf` | `+inf` (`log(inf) + log(2.0)`) | a NaN |
| `-inf` | `-inf` | `NaN` | a NaN |
| `+0.0`, `-0.0` | the same zero | `NaN` | the same zero |
| a subnormal, `abs(x) < 2^-28` | `x` | `NaN` | `0.5 * (log1p(x) - log1p(-x))` |
| `1.0` | by `log1p` | `+0.0` | `+inf` |
| `-1.0` | by `log1p` | `NaN` | `-inf` |
| `x > 1` | by its branch | by its branch | a NaN |
| `x < -1` | by its branch | `NaN` | a NaN |
| `2^28` | the `ax > 2.0` branch | the `log(x) + log(2.0)` branch | a NaN |
| a null | null | null | null |

**Nulls.** A null operand makes every test unknown, so its lane takes each `IfElse`'s last arm.
In all three trees that chain ends in a value computed from the operand (`x` itself, or `log1p`
of it), which is null with it. The two literal answers of `acosh` are taken only under a test
known true. This is the rule `VarkaMathPort.blend`'s comment states for a port.

**What it costs.** Every lane is called, so `asinh` and `acosh` make two calls a lane (`log` and
`log1p`) where Spark's row makes one, and `atanh` two as Spark does. The bits are Spark's either
way: the calls are pure and a lane's other value is discarded. Section 7 registers what this
does to the time.

**Depth, and what happens past the cap.** The emitter counts a tree's height as `Analysis`
(`analyzeOp`) and `VarkaLoopEmitter.budgetWalk` do: a column or a literal slot is 0, and every
other node, a condition too, is 1 plus its tallest child. A tree fuses where its height is at
most the cap `VarkaEmitBudget.chainDepthCap` gives it: the `maxChainDepth` emit option where
one is set, and by default 64 for a tree of the int lane alone or one that holds a port, and
`MAX_CHAIN_DEPTH`, 16, for any other. These three are double-lane trees, so their cap is 16.
Counted by that rule (the script of section 9, item 3):

| function | height over a column | fuses over an operand of height | first height that declines |
| :--- | ---: | :--- | ---: |
| `asinh` | 13 | 0 to 3 | 4 |
| `acosh` | 12 | 0 to 4 | 5 |
| `atanh` | 4 | 0 to 12 | 13 |

The height over an operand is the column's figure plus the operand's own height: a column is
0, `x + y` is 1, `(x + y) * x` is 2. `asinh`'s 13 is the far argument (six levels: `x * x`, the
add, the root, the add, the divide, the add), then the blend under the `log` call, the call,
the add of `log(2.0)`, the blend `w`, the negation, the sign's blend and the last blend:
6 + 7 = 13. `abs` and the compare with `2^28` are not on the tallest path.

Past the cap nothing new is needed. `VarkaExpressionCompiler` asks
`VarkaLoopEmitter.fitsBudgets` before it accepts an entry, and an entry that does not fit is
left to Spark with the reason `exceeds the emitter's fused budget`. So `asinh` over an operand
of height 4 declines by that reason and the row engine answers it: a decline, not an error.
Two tests of 5.2 step 4 hold the table and the reason. The cap is a measured policy
(`VARKA-620.md`) and no part of this plan raises it.

### 4.4 The compiler

In `VarkaMathCompiler.arm` (357a1 adds the first three, 357a2 the rest):

    case Sinh f -> () -> call(MathFn.SINH, f.child(), inputs, literals, sink);
    case Cosh f -> () -> call(MathFn.COSH, f.child(), inputs, literals, sink);
    case Tanh f -> () -> call(MathFn.TANH, f.child(), inputs, literals, sink);
    case Asinh f -> () -> operand(f.child(), inputs, literals, sink).map(v ->
        VarkaMathHyperbolic.asinh(v, d -> VarkaNodeCompiler.doubleSlot(d, sink),
            sink.floatingFault()));
    case Acosh f -> () -> operand(f.child(), inputs, literals, sink).map(v ->
        VarkaMathHyperbolic.acosh(v, d -> VarkaNodeCompiler.doubleSlot(d, sink),
            sink.floatingFault()));
    case Atanh f -> () -> operand(f.child(), inputs, literals, sink).map(v ->
        VarkaMathHyperbolic.atanh(v, d -> VarkaNodeCompiler.doubleSlot(d, sink)));

Nothing new declines by name: `operand` declines a value computed on another lane, and a call at
a width with no baked lane count declines in the kernel plan, as for every per-lane call.

## 5. The parts

Every sbt line below is run as

    JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 SPARK_LOCAL_IP=127.0.0.1 build/sbt '<task>'

and is done when sbt prints `[success]`. JDK 25 is required: section 4.2's table is JDK 25's.

### 5.1 Part 357a1

- **Title:** `[VARKA-357] Fuse sinh, cosh and tanh bit for bit`
- **Branch:** `varka-357a1-calls`, from master. **Builder:** Sonnet-class.

**B0.** Section 3's lines 1, 3 and 4 print what they say.

**Steps.**

1. `VarkaVectorIR.MathFn`: after `CBRT`, add `SINH("java/lang/Math", "sinh")`,
   `COSH("java/lang/Math", "cosh")` and `TANH("java/lang/Math", "tanh")`. Add to the enum's
   Javadoc: the three are the methods Spark's code names; `sinh` and `cosh` are fdlibm's on JDK
   25, where `Math` delegates, and `tanh` is the host's answer.
2. `build/sbt catalyst/Test/compile` now fails and names the switches that lack the members:
   `VarkaVectorWalk.emitMathUnary` (add the three to the arm that throws "is a per-lane call"),
   `VarkaReference.mathDouble` (`case SINH -> Math.sinh(v);`, `COSH`, `TANH` the same, by name)
   and `VarkaReference.mathFloat` (add them to the arm that throws).
3. `VarkaMathCompiler`: the three arms of 4.4; the class Javadoc says the three fuse.
4. `VarkaMathExactSuite`: a `Domain.HYPER` member whose `ownDraw` is
   `rnd.nextBoolean() ? rnd.nextDouble(-720.0, 720.0) : rnd.nextDouble(-30.0, 30.0)` (overflow
   is past 710, saturation at 22); three cells after `cbrt`:

        call("sinh", MathFn.SINH, (c, b) -> Math.sinh(c), Domain.HYPER, false),
        call("cosh", MathFn.COSH, (c, b) -> Math.cosh(c), Domain.HYPER, false),
        call("tanh", MathFn.TANH, (c, b) -> Math.tanh(c), Domain.HYPER, true),

   and four more values in `fixedVectors()`'s `edges`: `22.0, -22.0, 710.4758600739439,
   -710.4758600739439`. Extend the comment of `otherClassIsCaught` with section 4.2's lines for
   the three.
5. `VarkaMathCompilerSuite`: move `sinh`, `cosh` and `tanh` from the loop that fills `FUNCTIONS`
   to the stream that fills `FUSED`. `VarkaCoverageSuite`, family "Math functions":

        Row("sinh(x)", "exact: a per-lane Math.sinh call, as Spark's code makes",
          math = Some(MathCell(exactForms(2), operator = Some(VarkaMathBinding.Operator.SINH)))),
        Row("cosh(x)", "exact: a per-lane Math.cosh call",
          math = Some(MathCell(exactForms(2), operator = Some(VarkaMathBinding.Operator.COSH)))),
        Row("tanh(x)", "exact: a per-lane Math.tanh call, the host's answer as Spark's",
          math = Some(MathCell(exactForms(2), operator = Some(VarkaMathBinding.Operator.TANH)))),

6. `sql/varka/matrix/skips.tsv`: for each of the three rows two lines, copied from the two lines
   of `Math functions: expm1(x) matches the row engine under both consumers`
   (`validityByWidth=false` and `lanesOverride=16`), the SQL replaced, the reason's list of
   functions reading `(sinh, cosh, tanh)` and its row `(VARKA-357)`.
7. `VarkaMathBaselineBenchmark`: `sinh(X)`, `cosh(X)` and `tanh(X)` become `Fusion.WHOLE`.
8. `census_curated.txt`, three lines replaced:

        sinh|double|double|double|done|357|exact: a per-lane Math.sinh call, as Spark's code makes (VARKA-357)
        cosh|double|double|double|done|357|exact: a per-lane Math.cosh call, as Spark's code makes (VARKA-357)
        tanh|double|double|double|done|357|exact: a per-lane Math.tanh call, the host's answer as Spark's (VARKA-357)

9. The plan: add this file as `sql/varka/plans/m8/VARKA-357.md` with section 11's first entry
   filled, and open row 357's text in `PLAN.md` as section 11 gives it.

**The commands, in order.**

1. `catalyst/Test/compile`, then `sql/Test/compile`.
2. `catalyst/testOnly *VarkaMathExactSuite *VarkaMathCompilerSuite`: the JUnit summary reads
   `Failed 0, Errors 0`, and each suite runs as many tests as before the part: 357a1 adds
   cells and no test method. Read each count before the first edit with
   `grep -c '^  @Test$' <the suite's file>` (on master `6dafb8bd2f6`: `5` and `8`).
3. The planted check, run once and reverted: give `TANH` the owner `java/lang/StrictMath`; the
   sampled differential must fail naming `tanh` on an x86 host.
4. `catalyst/testOnly *VarkaCoverageSuite`: `All tests passed.`
5. `dev/varka_matrix.sh --config validityByWidth=false --config lanesOverride=16 --suites
   VarkaCoverageHarnessSuite,VarkaCoverageDifferentialSuite`: no failing test, no stale line.
6. `dev/varka_ops/pre-pr-slot.sh <worktree>`: `pre-pr: passed on <sha>`. In the regenerated
   files only the three rows, their shapes and the three census lines move.

**Done when** merged with a green Build and a green option matrix, and
`python3 dev/varka_census.py --summary` reads three more entries fused.

**Not here.** `asinh`, `acosh`, `atanh`. No new node, no emitter method, no port, no fuzz draw.

### 5.2 Part 357a2

- **Title:** `[VARKA-357] Fuse asinh, acosh and atanh as Spark writes them`
- **Branch:** `varka-357a2-inverse`, from master after 357a1 merged. **Builder:** Sonnet-class.

**B0.** `grep -c 'SINH("java/lang/Math", "sinh")'` on `VarkaVectorIR.java` prints `1`; section
3's lines 2 and 4 print what they say; and the check script of section 9 prints its last line
with three zeros.

**Steps.**

1. `VarkaEmitOptions.FloatingFault`: a member `MATH_EDGE_2P28` after `MATH_DOMAIN_LT`, with the
   comment "The `2^28` test of `asinh` and `acosh` (`VarkaMathHyperbolic`) one double low: at
   `2^28` itself the neighbouring branch answers one step away, which the fixed vectors hold."
2. `VarkaMathHyperbolic.java`: section 4.3's class, whole, with the licence header of
   `VarkaMathDomain.java`, a class Javadoc that restates 4.3's two branch tables in words, and
   the Javadoc of each method giving Spark's branches as a `<pre>` block. Explain no mock: there
   is none.
3. `VarkaMathCompiler`: the three arms of 4.4. After this part the `default` arm of `arm`
   declines only what other rows still own; leave it.
4. `VarkaMathExactSuite`:
   - a helper that builds a cell from a tree builder, with a literal table of its own:

            private static Fn tree(String name,
                BiFunction<VarkaVectorIR, DoubleFunction<VarkaVectorIR>, VarkaVectorIR> build,
                Code spark, Domain domain, boolean classesDiffer) {
              var values = new ArrayList<Long>();
              DoubleFunction<VarkaVectorIR> literal = v -> {
                long raw = Double.doubleToRawLongBits(v);
                int at = values.indexOf(raw);
                if (at < 0) {
                  at = values.size();
                  values.add(raw);
                }
                return lit(at);
              };
              VarkaVectorIR root = build.apply(X, literal);
              return new Fn(name, root, values.stream().mapToLong(Long::longValue).toArray(), 1,
                  spark, domain, classesDiffer, false);
            }

   - three cells, built under the `fault` that `functions(fault)` receives:

            tree("asinh", (x, l) -> VarkaMathHyperbolic.asinh(x, l, fault),
                (c, b) -> ExpressionImplUtils.asinh(c), Domain.ASINH, true),
            tree("acosh", (x, l) -> VarkaMathHyperbolic.acosh(x, l, fault),
                (c, b) -> ExpressionImplUtils.acosh(c), Domain.ACOSH, true),
            tree("atanh", (x, l) -> VarkaMathHyperbolic.atanh(x, l),
                (c, b) -> 0.5 * (StrictMath.log1p(c) - StrictMath.log1p(- c)), Domain.UNIT,
                false),

   - `Domain.ASINH`, own draw, one of four by `rnd.nextInt(4)`: a sign times
     `0x1p-28 * (1.0 + (rnd.nextDouble() - 0.5) * 1e-6)`; `rnd.nextDouble(-3.0, 3.0)`; a sign
     times `0x1p28 * (1.0 + (rnd.nextDouble() - 0.5) * 1e-6)`; a sign times
     `Math.scalb(1.0 + rnd.nextDouble(), rnd.nextInt(-40, 41))`.
   - `Domain.ACOSH`, one of four: `rnd.nextDouble(1.0, 3.0)`; `0x1p28 * (1.0 +
     (rnd.nextDouble() - 0.5) * 1e-6)`; `1.0 + Math.scalb(rnd.nextDouble(), -rnd.nextInt(53))`;
     `rnd.nextDouble(-2.0, 1.0)`.
   - `fixedVectors()`'s `edges` gain `0x1p28, -0x1p28, -0x1p-28, -2.0` (`0x1p-28` and `2.0` are
     there).
   - a new test, after `domainAtLessThanIsCaught` and in its form: over
     `functions(FloatingFault.MATH_EDGE_2P28)`, `fixedVectorFailures(f).size()` is above 0 for
     `asinh` and `acosh` and 0 for every other function.
   - a new test of 4.3's depth table, after that one (imports: `java.util.LinkedHashMap`,
     `java.util.function.UnaryOperator`, `java.util.function.DoubleFunction`):

            @Test
            @DisplayName("each inverse hyperbolic tree fits the chain cap up to the operand "
                + "height the plan counts, and no further")
            void inverseTreesFitTheChainCap() {
              // The cap is pinned: the option matrix also runs maxChainDepth=32, which admits
              // deeper trees.
              VarkaEmitOptions options =
                  VarkaMatrixRuns.BASE.withMaxChainDepth(VarkaEmitBudget.MAX_CHAIN_DEPTH);
              assumeTrue(Lane.emitLanes(options, Lane.DOUBLE) != 0, "no baked lane count");
              // Height does not depend on a literal's value, so one slot serves every literal.
              DoubleFunction<VarkaVectorIR> slot = v -> lit(0);
              var trees = new LinkedHashMap<String, UnaryOperator<VarkaVectorIR>>();
              trees.put("asinh", x -> VarkaMathHyperbolic.asinh(x, slot, FloatingFault.NONE));
              trees.put("acosh", x -> VarkaMathHyperbolic.acosh(x, slot, FloatingFault.NONE));
              trees.put("atanh", x -> VarkaMathHyperbolic.atanh(x, slot));
              var deepest = new ArrayList<String>();
              trees.forEach((name, build) -> {
                // The operand grows one sum a step: height 0, 1, 2 and so on.
                VarkaVectorIR operand = X;
                int height = 0;
                while (height <= 20 && VarkaLoopEmitter.fitsBudgets(
                    List.of(build.apply(operand)), 1, options)) {
                  operand = new IntArith(IntOp.ADD, Overflow.WRAP, operand, X);
                  height++;
                }
                deepest.add(name + " " + (height - 1));
              });
              assertEquals(List.of("asinh 3", "acosh 4", "atanh 12"), deepest);
            }

     A function that does not fit even over the bare column prints `-1`.
   - in `VarkaMathCompilerSuite`, a new test of the reason, after `callNeedsBakedLanes`:

            @Test
            @DisplayName("asinh and acosh over an operand past the chain cap decline naming "
                + "the emitter's budget")
            void inverseHyperbolicPastTheCapDeclines() {
              // Pinned to the default cap of a double-lane tree, 16, and to a width that bakes
              // a lane count: the matrix also runs maxChainDepth=32, validityByWidth=false and
              // lanesOverride=16, each of which gives another outcome.
              VarkaEmitOptions options = VarkaMatrixRuns.BASE.withMaxChainDepth(16)
                  .withValidityByWidth(true).withLanesOverride(0);
              // Eight sums: an operand of height 8, past both functions' room (3 and 4).
              String deep = "x + y + x + y + x + y + x + y + x";
              for (String f : List.of("asinh", "acosh")) {
                String why = reason(f + "(" + deep + ")", Set.of(), options);
                assertTrue(why.contains("exceeds the emitter's fused budget"), why);
              }
            }
5. Print the trees' depth and node counts, for the outcome:

        dev/varka_emit.sh "asinh(x)" --columns x:double --table
        dev/varka_emit.sh "acosh(x)" --columns x:double --table

   Each entry's line ends `FusedOutput(0)`, by 4.3's table: copy the depth and the node counts
   it prints into section 11. A line that ends `ResidualOutput` is case 2 of the paragraph
   after these steps.
6. `VarkaMathCompilerSuite`: move the three names to `FUSED`. `VarkaCoverageSuite`:

        Row("asinh(x)", "Spark's five branches as blends over StrictMath.log, log1p and sqrt, "
          + "the sign the operand's", math = Some(MathCell(exactForms.head))),
        Row("acosh(x)", "Spark's five branches as blends; NaN below 1.0, as Spark answers",
          math = Some(MathCell(exactForms.head))),
        Row("atanh(x)", "0.5 * (StrictMath.log1p(x) - StrictMath.log1p(-x)) as Spark writes "
          + "it, with no domain null", math = Some(MathCell(exactForms.head))),

7. `skips.tsv` (six lines, as 5.1 step 6, the functions `(asinh, acosh, atanh)`); the baseline's
   `asinh(X)`, `acosh(C)` and `atanh(U)` become `Fusion.WHOLE`; `census_curated.txt`:

        asinh|double|double|double|done|357|exact: Spark's branches as blends over per-lane StrictMath.log and log1p calls and sqrt (VARKA-357)
        acosh|double|double|double|done|357|exact: Spark's branches as blends over per-lane StrictMath.log and log1p calls and sqrt (VARKA-357)
        atanh|double|double|double|done|357|exact: 0.5 * (log1p(x) - log1p(-x)) over per-lane StrictMath.log1p calls, as Spark writes it (VARKA-357)

8. The plan: section 11's second entry, with step 5's counts.

**When a function does not come out as planned.** No JVM was started for this plan, so three
outcomes are possible that the plan cannot exclude. Each has a set answer; none stops the part.

1. *`inverseTreesFitTheChainCap` fails and every number it prints is 0 or more.* The failure
   message shows the list the emitter gave. Put that list in the test and in 4.3's table, and
   record both lists in section 11. This is not a test bent to pass: the list is the emitter's
   count of a tree, which the Python model of section 9 predicted, and no answer depends on it.
2. *A function cannot fuse as written*: it prints `-1` in that test (its tree over a bare
   column is over the cap, and step 5 shows `ResidualOutput`), or it is `atanh` and the fixed
   vectors fail at NaN inputs and nowhere else (section 9, item 2). Leave that function out of
   the part and merge the others: no arm for it in `VarkaMathCompiler` (it goes on declining
   "is row 357's"), its name stays in `FUNCTIONS` of `VarkaMathCompilerSuite`, its cell is
   taken out of `functions(fault)`, and it gets no coverage row, no `skips.tsv` line, no
   `Fusion.WHOLE` and no new census line. Its tree builder stays in `VarkaMathHyperbolic`.
   Section 11 names the function and holds the failing lines; `PLAN.md`'s row text says
   "left: <function>". Do not raise the cap and do not compare NaNs by hand in the suite: the
   repair is the lead's, as a part of its own.
3. *`inverseHyperbolicPastTheCapDeclines` fails.* Its message is the reason the compiler gave,
   or "fused". Leave that one test out, keep `inverseTreesFitTheChainCap`, which holds the
   numbers, and record the message in section 11.

**The commands, in order.**

1. `python3 /home/max/varka-ops/m8-row-plans/scratch-357/hyperbolic_tree_check.py` (no JVM):
   its line reads `mismatches asinh 0, acosh 0, atanh 0`. It is the check that the trees of 4.3
   select Spark's branch on every input; read it before changing a compare.
2. `catalyst/Test/compile`, then `sql/Test/compile`.
3. `catalyst/testOnly *VarkaMathExactSuite *VarkaMathCompilerSuite`: `Failed 0, Errors 0`; the
   first suite runs its count before the part plus 2 (the `2^28` plant and the depth table),
   the second its count before the part plus 1 (the decline's reason). The count before is
   `grep -c '^  @Test$' <the suite's file>`, read before the first edit: `5` and `8` on master
   `6dafb8bd2f6`, and unchanged by 357a1.
4. The planted checks, each run once and reverted; write what each did into section 11. Neither
   is an oracle, and neither outcome changes the part. In `asinh`, write `computed`'s first
   compare as `lt` in place of `le`: the suite may pass, since the two branches agree at `2^-28`
   itself. Write `over2` as `le(lit.apply(2.0), ax)`: the mutation moves exactly two inputs,
   `2.0` and `-2.0`, bits `0x4000000000000000` and `0xc000000000000000`, both among the fixed
   vectors' edges after step 4; no neighbour of them changes branch, so there is nothing to add
   to the edges. With Python's `log` and `log1p` the two branches give the same double there,
   `0x3ff719218313d087` (section 9, item 3), so the suite is expected to pass under this plant
   too: at `2.0` Spark's two formulas agree, and a compare that is off by that one double
   answers the same bits. The compare that is watched is the `2^28` one, by step 4's plant.
5. `catalyst/testOnly *VarkaCoverageSuite`, the matrix command of 5.1 step 5, then the gate,
   `dev/varka_ops/pre-pr-slot.sh <worktree>`: `pre-pr: passed on <sha>`.

**Done when** merged with a green Build and a green option matrix; the census reads three more
entries fused; and `grep -c 'new Owner("[a-z]*", 357)'` still prints `6` while no SQL function
of the six declines (`VarkaMathCompilerSuite`'s `FUNCTIONS` holds none of them). Where case 2
of the paragraph above left a function out, read "three" as the number merged; the row then
stays **Started** and section 11 says what is left.

**Not here.** No call that skips lanes (the "one call a lane" of `VARKA-356.md` 2.4). No change
to `MAX_CHAIN_DEPTH`. No port of `sinh` or `cosh`. No change to `ExpressionImplUtils`.

## 6. Tests, proofs, fuzzing and coverage

| suite | what it holds | the failure only it catches |
| :--- | :--- | :--- |
| `VarkaMathExactSuite`, sampled | each of the six against Spark's own code over 2^24 inputs | a wrong branch or operand order away from the edges |
| `VarkaMathExactSuite`, fixed vectors | the special values of 4.3's table, the edges `2^-28`, `2.0`, `2^28`, `+-1`, `+-22`, each with 64 neighbours, over four null patterns at 2 and 8 lanes | a compare off by one double; a literal answer on a null lane |
| the other-class plant | 4.2's table | a call of the wrong class |
| the `2^28` plant | `asinh` and `acosh` fail, nothing else moves | proves the edge is watched |
| `inverseTreesFitTheChainCap` | 4.3's table: the deepest operand each tree fuses over, under a cap pinned to 16 | a change to a tree that adds a level, which would silently turn a fused query into a declined one |
| `inverseHyperbolicPastTheCapDeclines` (`VarkaMathCompilerSuite`) | the reason past the cap | an over-deep entry that throws at emission instead of declining |
| `VarkaCoverageSuite` and the coverage differential | the six rows against the row engine | an operand cast, a session-level path |

No proof file: no lowering here is exact only over a bound. No fuzz draw is added.

## 7. Measurement, and predictions registered before any run

The six sections and their row-path baselines exist (section 3, line 5). Part 358r of
`VARKA-358.md` reruns the class on the EPYC 7763 at 256 and 128 bits and scores these:

1. No existing shape's bytes move (each part's regeneration: yes or no).
2. `sinh(x)`, `cosh(x)`, `tanh(x)`, with and without nulls: "varka on" within 0.8 to 1.3 times
   the row path's best time at both widths.
3. `atanh(u)`: within the same band, two calls a lane as the row path makes.
4. `asinh(x)` and `acosh(c)`: slower than 1.3 times the row path's best time at 256 bits, two
   calls a lane against the row path's one (registered on the branch on 20 November 2026 and
   kept).

**The decision rule for prediction 4**, so that the reader of the results computes and does not
judge: the functions stay fused whatever the number, since the census counts them and their
bits are Spark's. If "varka on" reads above 2.0 times the row path at 256 bits for either
function, 358r files one row in `m10/SCOPE.md` for the call that skips lanes, citing the
results line; below that, it files nothing.

## 8. Who builds it

| part | builder | why |
| :--- | :--- | :--- |
| 357a1 | Sonnet-class | three enum members and their list entries; the compiler names every switch |
| 357a2 | Sonnet-class | the trees are written out in 4.3 and checked in Python; the suite's helper is written out; nothing is new to the emitter |

Nothing of this row is marked full strength. The one decision that depends on JIT behaviour,
which class `SINH` names, is made in 4.1.

## 9. Risks, what was checked, and what only a JVM settles

1. **Checked in Python** (`/home/max/varka-ops/m8-row-plans/scratch-357/hyperbolic_tree_check.py`,
   run with 2000000 sampled inputs and 2333 edge inputs): the three trees against Spark's
   branches, bit for bit, with one `log`, `log1p` and `sqrt` on both sides. Last line:
   `hyperbolic trees: 2002333 inputs, mismatches asinh 0, acosh 0, atanh 0 []`. A mutation that
   writes `asinh`'s `2^28` test as `<=` is caught at `+-2^28`; one that writes the `2^-28` test
   as `<` is not, which is why command 4 of 5.2 calls it no oracle. The check is of structure:
   which branch, which operand order, which sign. It says nothing about `StrictMath`.
2. **Only a JVM settles:** that the kernel's lanes equal `ExpressionImplUtils`' answers on the
   JVM, NaN payloads included (command 3 of 5.2); and section 4.2's table. `atanh` of a NaN is a
   difference of two NaNs with different bits (`log1p(x)` keeps the payload, `log1p(-x)` may
   answer the canonical NaN). `VarkaSameAnswer` treats a subtraction of two NaNs as fixed, the
   left operand's on every tier. If the fixed vectors fail on `atanh` at a NaN input and nowhere
   else, do not compare NaNs by hand in the suite: `atanh` is left out of the part by case 2 of
   5.2's paragraph "When a function does not come out as planned", and the rule is the lead's.
   Also only a JVM settles: that the emitter's count equals the model's (the depth test; case
   1), that a sum of two double columns in SQL compiles to one node (the reason's test; case
   3), and that the 28 distinct operations of `asinh` at eight lanes fit the method byte
   budget, which the fixed vectors settle by emitting at `lanesOverride` 8: a kernel that did
   not emit fails them, and that is case 2.
3. **Checked in Python, the depth**
   (`/home/max/varka-ops/m8-row-plans/scratch-math-r2/depth_check.py`): the three trees built
   node for node as 4.3 writes them, their height and distinct operations counted as
   `budgetWalk` counts, over operands of height 0 to 39, against the cap 16; and the two
   branches the `over2` plant exchanges, at `2.0`. Its line:

        asinh height 13 ops 28 fuses to operand height 3; acosh height 12 ops 25 fuses to operand height 4; atanh height 4 ops 5 fuses to operand height 12; at 2.0 (0x4000000000000000) log1p branch 0x3ff719218313d087, log branch 0x3ff719218313d087, equal

   The operation counts matter only under a method byte budget of 0, where the emitter caps a
   kernel at 64 distinct operations: 28 leaves room.
4. **A matrix configuration declines a row** (a chain cap, a byte budget of 0). Held by the
   matrix on the pull request; each such configuration gets a `skips.tsv` line of kind
   `declines` with the reason the kernel plan prints.
5. **`Math.sinh` on a later JDK.** Held by 4.1's decision. The suite's "plant changes nothing
   for `sinh`" is a JDK 25 fact: on JDK 26 that assertion is expected to fail, which is the
   prompt to compare that JDK, not a defect of the kernel.

## 10. Questions for the owner

1. **The class `SINH` and `COSH` name.** This is `VARKA-358.md` section 10, question 1, the one
   question of the three plans on the matter; it is not asked twice. *Recommended and applied
   there: `java/lang/Math`, the class Spark's code names.*
2. **`asinh` and `acosh` at two calls a lane.** (a) Fuse them so, measure, and file the
   lane-skipping call for milestone 10 by section 7's rule. (b) Leave both declined until a call
   can skip lanes. (c) Build the lane-skipping call now, a full-strength part that changes what
   a per-lane call's word means. *Recommended: (a)*: the bits are Spark's, the census counts
   them, and the time is measured before anyone reads it. **Default applied: (a).**

**Decided, and not asked** (review 1: it is no question for the owner). `VARKA-356.md` section
9's "`asinh` and `acosh` making one call a lane" is not part of the close. The row's done-when
in `PLAN.md` does not ask it, and the close path (`m8/PLAN.md` 12.5, decision 3 of 27 November
2026) counts the per-lane calls as what item 5 owes. Section 1's second table lists it as no
gate, and section 7's rule files it for milestone 10 if the measured time asks for it.

## 11. Outcome

To be written by each part.

- 357a1 opens row 357's text in `PLAN.md` with: "**Started** (*<date>*: 357a1 merged as #<n>:
  `sinh`, `cosh` and `tanh` fuse as per-lane calls of `Math`'s methods, the class Spark's code
  names, `VARKA-357.md`; left: 357a2, the three inverse functions; the baseline is merged as
  #1576 with its runner results)".
- 357a2 turns it to "**Done in part**" naming what is left (the runner measurement, part 358r)
  and what moved to `m10/SCOPE.md` item 140 (the `sinh` and `cosh` ports), and records the depth
  and node counts step 5 printed, what each of command 4's two plants did, and anything the
  paragraph "When a function does not come out as planned" of 5.2 made it do.
