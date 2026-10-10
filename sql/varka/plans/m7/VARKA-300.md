# VARKA-300: The facade's data model in Java (300a), the classifier after it (300b)

## 1. Where this came from

Row 300 of `m7/PLAN.md`, filed by VARKA-217 as its second half (217b): the facade's classifier
(`compilePartial`, the kernel rounds, the size admission, `compilePredicate`) and its data model
(`CompiledVarkaProjection`, the output specs, the declines) to Java, with a result type and
without `scala.Option` or the Scala tables, once their consumers were Java too. Row 267 made them
so: the three evaluators are Java and read the Scala model through converters - `asJava` on every
list, `isDefined`/`get` on every `Option`, `ResidualOutput$.MODULE$`, and in
`VarkaKernelEvaluator.layout` a pattern `switch` whose `default` is the one 267 left "that row
300 removes" (`VARKA-267.md` 9.5). Scope item 74.4 states the goal: the facade does
classification, size admission, bisection and several kernels "with triples for results and
shared tables rolled back by hand".

## 2. The admission check, done

Surveyed on 10 October 2026 at `96b5f30f5a7`. The row reads "medium"; the survey says it is the
largest port of the migration so far, and not one pull request:

| part | size | who uses it |
|---|---:|---|
| the data model: 13 Scala types (`CompiledVarkaProjection`, `PartialVarkaProjection`, `CompiledVarkaPredicate`, `VarkaConjunctSpec`, four output specs, `VarkaDecline`, `VarkaInputBound`, `VarkaDerivedInput` and its key helpers) | about 200 lines | 5 Java main files and 1 Scala exec node in `sql/core` (the evaluators, `VarkaKernelPart`, `VarkaFusionReport`, `VarkaColumnarToRowExec`), `DeclineSink`, `VarkaNodeCompiler`, and 11 test files by name |
| the classifier: `classifyKernels`, `classify` with the bisection and planned cuts, `classifyOnce`, `admitBySize`, the predicate passes and `splitPredicate` | 720 lines | the rule, the exec nodes, the evaluators, `VarkaFusionReport`, and about 20 test files |
| the families' boundary: `scala.Option` returns and `mutable.LinkedHashMap[Int, Int]` tables | about 170 `Option` and 150 table sites in five Java files | the classifier only |

`VarkaExpressionCompilerSuite` alone has about 330 references to the model, and the real size of
its port is the Scala operations on model fields, which a Java `List` or `Map` does not have: 224
`===` comparisons against a Scala `Seq` or `Map`, 48 applications (`outputs(0)`), and about 90
`head`, `forall`, `map`, `count` and the like. Scala 2.13 also cannot destructure a Java record,
so its 25 `case FusedOutput(i)`-style patterns become type patterns (217 2).

So, two pull requests, bottom up, neither writing code the other deletes:

* **300a (this pull request)**: the data model as Java records. The Scala classifier constructs
  them; the Java consumers lose their converters, and the Scala ones and the tests read Java
  collections.
* **300b (row 305)**: the classifier to Java with a result record for the triples, a table type
  for the Scala `LinkedHashMap`s and their hand-written `truncate`, the size admission in its
  own class, and the families and `DeclineSink` off `scala.Option` in the same pull request.
  Doing the families first would have a Scala classifier consume Java tables through a bridge
  300b deletes.

What the check would have rejected: one pull request of about 3,000 changed lines whose oracle,
the compiler suite, it rewrites.

## 3. The design (300a)

### 3.1 The records

Public Java records in `org.apache.spark.sql.catalyst.expressions.codegen`, under the Java rules
of `sql/varka/AGENTS.md`. `private[sql]` has no Java spelling, so each says in its doc that it is
internal, as `VarkaShapeCache` does (217 9).

* **`VarkaOutputSpec`**, a sealed interface of four records nested in it: `FusedOutput`,
  `KernelOutput` (its `kernel >= 1` check in the compact constructor), `ForwardedOutput` and
  `ResidualOutput`, a record with no components and one shared instance,
  `VarkaOutputSpec.RESIDUAL`. Every consumer is an exhaustive `switch` with no `default`.
* **`CompiledVarkaProjection`**: the seven components as `List`s, copied in the compact
  constructor, with the one-lane check; `lane()`, `numLiterals()`, and `derivedAt(int)` as an
  `Optional` found by a scan (the evaluators call it once per input at construction, never per
  batch, so the Scala `lazy val` map goes). A four-argument constructor stands for the Scala
  defaults.
* **`PartialVarkaProjection`**, **`CompiledVarkaPredicate`**, **`VarkaConjunctSpec`**,
  **`VarkaDecline`** (its `toString`, `"reason: expr"`, unchanged, since EXPLAIN prints it),
  **`VarkaInputBound`**, **`VarkaDerivedInput`** with its key helpers as static methods.
  `columnIndex` returns an `OptionalInt`; the declines are a `Map<Integer, VarkaDecline>`; a
  conjunct's decline an `Optional`.
* `VarkaDerivedInput.resolve` takes the classifier's Scala table, so it moves into the Scala
  classifier, which 300b ports with the table.

### 3.2 The consumers

* **Java** (`VarkaEvaluatorBase`, `VarkaKernelEvaluator`, `VarkaFilterEvaluator`,
  `VarkaKernelPart`, `VarkaFusionReport`, `DeclineSink`): the converters and `MODULE$` accesses on
  the model go; `layout`'s `default` and `VarkaFusionReport`'s `instanceof` ladder become
  exhaustive switches. The facade's `Option` results stay `scala.Option` until 300b.
* **Scala** (the classifier, `VarkaColumnarToRowExec`, the tests): construct with `new`, read
  with `asScala`, and match with type patterns.

### 3.3 What is deliberately unchanged

The classifier's logic, which 300b ports; the families and their tables; every decline reason
and the order entries are classified in, so every fusion decision.

### 3.4 Registered op counts

None move: nothing in the emitter changes.

## 4. Files

| file | what |
|---|---|
| `VarkaOutputSpec.java`, `CompiledVarkaProjection.java`, `PartialVarkaProjection.java`, `CompiledVarkaPredicate.java`, `VarkaConjunctSpec.java`, `VarkaDecline.java`, `VarkaInputBound.java`, `VarkaDerivedInput.java` | the records |
| `VarkaExpressionCompiler.scala` | the model removed; constructs the records |
| the five Java consumers, `DeclineSink.java`, `VarkaNodeCompiler.java` | converters gone |
| `VarkaColumnarToRowExec.scala`, `VarkaProjectExec.scala`, `VarkaColumnarRule.scala` | as needed |
| the compiler suite and the test, benchmark and probe files that read the model, about 20 | the model read as Java |

## 5. Tests, and what each is for

The compiler suite is rewritten here, so it is not the oracle on its own. The oracles this pull
request does not edit:

* **`coverage.json` and `emitted_bytes.json` byte-identical**: every fused expression and every
  emitted method.
* **`VarkaFamilyChainSuite`**, **`VarkaGoldenCorpusSuite`** (731 statements end to end) and
  **`VarkaDifferentialSuite`**, with its fusion classification.
* **The EXPLAIN assertions in `sql/core`**, which print `VarkaDecline` and the fusion report.
* **A before-and-after dump**, not committed: `VarkaFusionReport.lines` for every coverage row on
  the base and on the branch, diffed. Any difference in a classification or a reason fails it.
* **The gate**, both widths.

## 6. The measurement

None for 300a: the model is built once per plan and read once per evaluator, so neither the
compile benchmark nor the per-batch overhead can move. 300b, which ports the classifier's loops,
reruns `VarkaCompileBenchmark` as 217 did and commits the pair.

### 6.1 Predictions, registered before the run

1. Every oracle in 5 holds unchanged.
2. The Java model is longer than the Scala's 200 lines, about twice: 267 measured its port at 1.6
   to 1.7 times, and records here replace case classes that were one line each.

## 7. Risks

1. **A Scala comparison that silently turns false.** A Scala `Seq` is never `==` to a Java
   `List`, so a rewritten `assert(x === Seq(...))` that forgot `asScala` fails loudly, but an
   `assert(x != Seq(...))` would pass vacuously. Grep the diff for `!=` and `!==` on model fields.
2. **Nulls from Scala.** `List.copyOf` and `Map.copyOf` reject null, which a Scala caller could
   pass where it once passed `Nil`; the four-argument constructor covers the defaults.
3. **The coverage scan** reads the classifier as Scala; the records name no Catalyst class, so its
   file list does not change in 300a.

## 8. Sequencing

1. This plan; row 300 narrowed to 300a and row 305 filed for 300b.
2. The records, the consumers and the tests, in one commit.

## 9. Outcome (300a)

Done on 10 October 2026, as 3.1 and 3.2 describe.

* **Every fusion decision is unchanged.** The dump in 5 rendered, through one renderer that does
  not depend on how either model prints, every coverage row as a projection, as `compile`, and,
  for the predicate rows, as a predicate; all the projection rows together, with the child columns
  forwarded, with a nondeterministic entry, and twice over; all the predicate rows as one
  conjunction; and a 200- and 400-entry ladder and a 70-column projection under several kernels.
  Each ran under seventeen option sets: the defaults; four method budgets, each alone, with several
  kernels and with condition splitting; two fused ceilings with several kernels and one without;
  and a forced residual. The 4,436 lines are byte-identical on the base
  and on the branch. They hold 206 further-kernel columns, 47 method-budget declines, 6 split
  predicates, 17 nondeterministic declines, 3 forced residuals, input bounds and 64-bit literal
  tables. No coverage row has a derived input, so `derivedAt` is checked by the compiler suite's
  own test of it.
* **`coverage.json` and `emitted_bytes.json` are unchanged**: neither file is modified, and
  `VarkaCoverageSuite` and `VarkaEmittedBytesSuite`, which fail when the files they pin move, pass
  in the gate, which passes as a whole: every Varka suite at both widths, and lint.
* **What went from the consumers:** the converters on the model in the evaluators,
  `VarkaFusionReport` and `DeclineSink`; `ResidualOutput$.MODULE$`; the
  `default` in `VarkaKernelEvaluator.layout`, now an exhaustive switch; and
  `VarkaFusionReport`'s `instanceof` ladder, now one too.

**Predictions scored (6.1).**

1. **Held.**
2. **Held at the low end of "about twice".** The eight records are 492 lines against the Scala
   model's 204, 2.4 times, of which 136 are the license header each file carries and the Scala
   types shared; without the headers 356, 1.7 times, 267's ratio again.

**What the tests cost.** The Scala tests read the model through `asScala`, which is most of the
diff; a Scala `Seq` is never `==` to a Java `List`, so every comparison converts first. No
inequality on a model field was left comparing a Java collection (7.1).

**The dump is kept**, as an opt-in test in `VarkaCoverageSuite` beside the bytes suite's option
audit: with `VARKA_FUSION_DUMP` set to a file it writes the 4,436 lines, byte-identical to the
base's, and without it it cancels. 300b's admission check is running it on both sides.

**Corrections to sections 3.1, 3.2 and 4**, which are a record and are not rewritten: the
convenience constructors standing for the Scala defaults are not there, since every caller passes
every component; `VarkaKernelPart` and `VarkaProjectExec` did not change, since neither reads a
model collection, and `VarkaColumnarRule`, `VarkaSplitConditionFusionSuite` and
`VarkaCoverageSuite` did.
