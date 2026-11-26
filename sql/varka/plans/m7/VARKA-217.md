# VARKA-217: Port the facade's node compiler to Java (217a), the classifier with 267 (217b)

## 1. Where this came from

Row 217 of `m7/PLAN.md`: "Port the facade, `VarkaExpressionCompiler`, to Java with a result type,
the size admission apart from node compilation, and `VarkaShapeCache.scala` with it", from
`m10/SCOPE.md` items 81 and 74.4. The three family ports before it (214 to 216) each paid the same
costs at the boundary with the Scala facade, and VARKA-175 9 predicted they would go away when
the facade was Java: the module access (`VarkaExpressionCompiler$.MODULE$`), `scala.Option`, the
erased `mutable.LinkedHashMap[Int, Int]` tables, and the helpers each family copied (`decline`,
`table`, the `FACADE` constant), which the review of #671 counted as four copies.

## 2. The admission check, done

The facade is 1,367 lines and it is not one thing. Surveyed on 8 October 2026 at `6f0f5749f43`:

| part | lines | who uses it |
|---|---:|---|
| the node compiler: `compileNode`, the family chain, the date leaves, the int arithmetic arms, the fallback, `intSlot`, `columnRef`, `derivedRef`, `compileIntOperand`, `intOperand`, `arithOver` and their helpers | about 330 | the four Java families, the facade's own classifier, and `VarkaFamilyChainSuite` (`compileNode`, `familyChain`); nothing else |
| `DeclineSink` | 90 | the facade, the four families, two tests; `VarkaFusionReport` mentions it in a comment |
| the classifier: `compilePartial`, kernel rounds, size admission by bisection and planned cuts, `compilePredicate`, predicate splitting | about 600 | 8 main files and 13 to 28 test files, through the Scala data model below |
| the data model: `CompiledVarkaProjection`, `PartialVarkaProjection`, the output specs, `VarkaDecline`, `VarkaInputBound`, `VarkaDerivedInput` | about 200 | the evaluators in `sql/core` and their tests, which pattern-match on it |

Scala 2.13 cannot destructure a Java record in `case FusedOutput(i)`, so converting the data model
changes about 8 main and 20 test Scala files, and the evaluators among them are row 267's to port.
Doing both at once writes the evaluators' consumption of the model twice. So, at the owner's
choice on 8 October 2026 from three cuts:

* **217a (this task)**: the node compiler, `DeclineSink` and `VarkaShapeCache` to Java. No
  consumer changes: the classifier stays Scala and calls the Java node compiler.
* **217b (row 300)**: the classifier and the data model, with row 267, when their consumers are
  Java too and the result type, the `Option` and the tables at last have nothing to bridge.

What the check would have rejected: one PR that converts the data model under evaluators that are
about to be ported.

## 3. The design

### 3.1 `VarkaNodeCompiler`

A package-private `final class` in the facade's package with static members:

* `compileNode(expr, inputs, literals, sink)`. The chain is a list of families in the order the
  Scala `familyChain` gives, each a name and a claim function: "date leaves", "calendar" (Chrono),
  "interval", "time", "condition", "int arithmetic". A claim returns the family's `VarkaFamilyArm`
  or `null`, with no side effect; the first claimant compiles the node and nothing is composed.
  The Scala chain built six partial functions and `orElse`-ed them for every node compiled; the
  Java one allocates nothing for a claim that does not match.
* `fallback`, `compileRoot`, `laneOf`, `overflowOf`, `intOperand`, `compileIntOperand`,
  `intArith`, `arithOver`, `cannotOverflow`, `magnitude`, `literalAt`, `intSlot`, `columnRef`
  (two overloads: the lane defaults to INT), `derivedRef`, `truncate`, `table`, and
  `MaxInLiterals`.
* `families()`: the chain as a list, for `VarkaFamilyChainSuite`, which asked `isDefinedAt` of
  each group and now asks `claims`.

The tables stay `scala.collection.mutable.LinkedHashMap` at the boundary with the Scala classifier,
and `scala.Option` is what the families return; both go with 217b.

### 3.2 `DeclineSink`

A Java class with the same methods and the same behaviour: the first note wins, the bounds and the
long literals roll back by mark, `note` puts the child's attributes back in place of the bound
references before it renders the expression and caps it at 80 characters. It gains
`decline(reason, expr)`, `<T> Option<T>`, the helper each family copied: the four copies go.

### 3.3 `VarkaShapeCache`

The object becomes a Java class with static methods, in `codegen.varka` beside
`VarkaShapeCacheImpl`. It reads the cache capacity and the compilation-watch flag from
`SparkEnv.get().conf()`, else `SQLConf.get()`, as the Scala did. Its Scala callers (about 50 sites)
call it as before, since a Java static method is called the same way; `maxExecutionIdentityLength`
becomes the constant `MAX_EXECUTION_IDENTITY_LENGTH` at its four callers.

### 3.4 What is deliberately unchanged

* The classifier, the data model and the evaluators: 217b and 267.
* The families' arms and the IR: no lowering changes.
* The decline texts, the order of notes and the tables' order: `emitted_bytes.json` and
  `coverage.json` are the proof.

### 3.5 Registered op counts

None move.

## 4. Files

| file | what |
|---|---|
| `.../codegen/VarkaNodeCompiler.java` | the node compiler |
| `.../codegen/DeclineSink.java` | the sink; the Scala class is deleted |
| `.../codegen/varka/VarkaShapeCache.java` | the cache's entry; the Scala object is deleted |
| `.../codegen/VarkaExpressionCompiler.scala` | the classifier calls the Java; the moved members go |
| the four Java families | `FACADE.x` becomes `VarkaNodeCompiler.x`; their `decline`, `table` and `FACADE` copies go |
| `VarkaFamilyChainSuite.scala`, `VarkaExpressionCompilerSuite.scala` | read the Java chain and sink |

## 5. Tests, and what each is for

No test is added; 214's oracles are the proof. `VarkaFamilyChainSuite` is the one that changes
shape: it asks each family whether it claims a node, as before, and its duplicate-on-purpose test
still proves the check can fail.

## 6. The measurement

`VarkaCompileBenchmark`, committed results regenerated for the final form and compared with
master's, which are the committed files of #671 (the Java families on the Scala facade), by
minimums over three interleaved runs of each.

### 6.1 Predictions, registered before the run

1. **The bytes do not move**; `coverage.json` is byte-identical.
2. **Compile time falls**, because a node no longer builds and composes six partial functions
   before its first claim: the calendar projection of six and the sixty-output projection take 5 to
   20% less time, and no shape takes more than the noise (about 4%).
3. **The node compiler is under 700 lines of Java.**

## 7. Risks

1. **A family claim with a side effect.** The chain runs a claim for every family before it
   compiles, so a claim that mutates would show; the families' `arm` methods only test and return
   a deferred call, by the recipe of VARKA-175.
2. **The order of the chain.** It is the Scala list's order and `VarkaFamilyChainSuite` holds that
   no node is claimed twice, so the order is a convention and not a dependency; the suite is the
   guard.
3. **`SparkEnv.get()` in `VarkaShapeCache`** at class initialisation instead of first use: the Scala
   was a `lazy val`. The Java holds the instance behind a nested holder class, so it is created on
   first use as before.

## 8. Sequencing

1. This plan; row 217 narrowed to 217a, row 300 filed for 217b.
2. `DeclineSink`, `VarkaNodeCompiler`, the call sites and the families, in one commit.
3. `VarkaShapeCache`.
4. The measurement and section 9.

## 9. Outcome

Done on 8 October 2026 (217a; 217b is row 300).

**The proof held.** The 47 Varka suites of `catalyst` pass (560 tests, 36 cancelled, the count of
216), and so do the evaluator, warm-up and differential suites of `sql/core` that read the cache
(114 tests). `emitted_bytes.json` and `coverage.json` are unchanged; the coverage scan needed
`VarkaNodeCompiler` added to its list of Java files, as 216 predicted for any file that moves.
`VarkaFamilyChainSuite` now reads the Java chain, and its duplicate-on-purpose test still fails the
check when a group is claimed twice. Scalastyle, checkstyle and `dev/varka_precommit.sh` are clean.

**What went.** The four copies of `decline`, `table` and the `FACADE` constant; the Scala
`DeclineSink`, `VarkaShapeCache` and the node layer of the facade (about 420 lines); the
per-node construction of six partial functions. `magnitude` returns an `OptionalLong`, `laneOf` an
`Optional`, and `VarkaShapeCache.MAX_EXECUTION_IDENTITY_LENGTH` replaces the Scala val.

**Compile times.** Three runs of the base (216's head: Java families on the Scala facade) and three
of the port, interleaved on the idle laptop, by minimums, ns a call:

| shape | base | port | change | spread of base |
|---|---:|---:|---:|---:|
| calendar projection of six | 11750 | 9407 | -20% | 6.5% |
| TIME projection of three | 9726 | 9032 | -7% | 6.2% |
| conditionals | 22358 | 17219 | -23% | 4.5% |
| sixty mixed outputs | 518535 | 485855 | -6% | 8.0% |
| predicate: range, IS NOT NULL, comparison | 3867 | 3438 | -11% | 2.1% |
| predicate: IN over eight dates | 2500 | 2354 | -6% | 1.1% |
| predicate: three date ranges | 2161 | 2194 | +2% | 1.4% |
| predicate: NOT, OR, IS NULL | 2064 | 1864 | -10% | 3.1% |

The committed files are regenerated from the port (`VarkaCompileBenchmark-jdk25-results.txt` and
its 128-bit companion).

**Predictions scored.**

1. **Held.** The bytes do not move and `coverage.json` is byte-identical.
2. **Held, and larger than predicted for two shapes.** The calendar and conditional projections
   take 20 and 23% less; no shape is slower beyond the spread (the one +2%, on a call of 2 microseconds,
   is as large as the noise of its neighbours).
3. **Held.** `VarkaNodeCompiler` is 547 lines.

**What the port is.** The facade is 949 lines of Scala (the classifier and the data model) against
1,367; row 300 ports what is left.

**Review of the PR** (`/code-review high`) found the port faithful and these, fixed in the PR:

* `VarkaShapeCache`'s holder classes would fail for good if their initialiser threw once (a
  negative capacity, say), with a `LinkageError` that `NonFatal` handlers let through, where the
  Scala `lazy val` re-ran and rethrew an ordinary exception. The instance and the watch are now
  created under a lock and retried, as before.
* **Corrections to this plan, which is a record and is not rewritten:** 3.1 says
  `VarkaNodeCompiler` holds `compileRoot` and `MaxInLiterals`; `compileRoot` stays in the Scala
  classifier (it calls the Java `compileNode`) and the constant is `MAX_IN_LITERALS`. Section 4
  lists `VarkaExpressionCompilerSuite.scala` as changed; it is not. The facade is 946 lines, not 949.
* The compile-time table above is the interleaved wide-run minimums. The committed single runs
  differ: wide, 8763 / 9019 / 16677 / 476225 ns for the first four shapes against master's 12112 /
  10093 / 22526 / 531407; 128 bits, 9720 / 9394 / 17478 / 521992 against 11584 / 10063 / 22378 /
  490109. So the 60-output projection reads 6.5% slower in the committed 128-bit pair, a
  single-run difference inside that shape's 8% spread across the three base runs (the benchmark
  does not use the vector width), and the claim "no shape is slower beyond the spread" stands
  with that stated; the other seven shapes are 2 to 24% faster at both widths.
* Stale pointers to `familyChain` and "the facade's" helpers in the family docs, and a loop in
  `truncateLong` that copied the key set (now `pollLastEntry`) and a second copy of the erased-table
  cast in `DeclineSink` (now `VarkaNodeCompiler.table`).
* `VarkaShapeCache` is `public` where the Scala object was `private[sql]`: a Java class cannot say
  it, so the two internal methods (`invalidateAll`, `compilationWatchRunning`) say so in their docs.

