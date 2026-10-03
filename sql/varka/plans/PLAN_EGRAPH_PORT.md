# Plan: an e-graph library for Java 25, ported from egg

<!-- Not a numbered task, and not Varka source: a library that Varka does not
     use yet, planned so that scope item 11 of SCOPE_MILESTONE_8.md has an
     engine to pick up when its time comes. It lives in a dedicated repository
     under github.com/vecbricks (owner's decision, 5 September 2026); this
     file is Varka's record of the decision and moves to that repository, as
     its plan, when the repository is created. The sections follow
     TEMPLATE_TASK.md where they apply; 3.3 registers size and effort rather
     than op counts, and 6 measures the library rather than a kernel. -->

## 1. Where this came from

`SCOPE_MILESTONE_8.md` item 11, "Physical representation as a compiler
decision", concluded that the engine Varka is meant to become - every Spark
type and expression, several physical forms per logical value - needs an
extractor over equivalence classes: conversion nodes as ordinary nodes,
semilattice analyses carrying type, encoding, nullability and range, and a
cost-driven choice of representation over a whole projection. The owner's
question of 5 September 2026: there is no e-graph library in Java, so port
egg (Willsey et al., POPL 2021; `github.com/egraphs-good/egg`, MIT) to Java
25+ for Varka's future use - as a product of its own, in a dedicated
repository under `github.com/vecbricks`, not as a module of the fork. The
library has no Spark dependency and is usable outside Varka; Varka consumes
it the way it consumes Arrow, as a versioned dependency, when item 11 needs
it.

The ecosystem check behind the premise: no maintained pure-Java e-graph
library was found. The JVM has Risegg, the Scala engine of sketch-guided
equality saturation for RISE (a research artifact, not a library); Julia has
Metatheory.jl; everything else is egg and its successor egglog, both Rust,
both MIT, both active (egg last updated December 2025). egglog is the more
powerful system - Datalog-based, with a query planner, incremental execution
and composable analyses - and the wrong size for this: its strengths are
multi-pattern joins over large fact sets and re-saturation of a changing
database, neither of which a 64-node IR with a rule set in the dozens needs.
egg's model is the one item 11 asked for, and its paper gives the pseudocode
for the parts that matter.

## 2. The admission check, done

**egg's four mechanisms are item 11's four needs.** Read against the paper
(sections 2-5) and the item: a conversion between physical forms is an e-node
in the class of the logical value; `dayRange` and `inputBounds` are e-class
analyses (make, join = hull, modify); representation selection is extraction
under a cost function, which the paper shows is itself an analysis when the
cost is local (section 4.3); and the Runner's node and iteration limits give
the deterministic bound item 11 requires instead of a timeout. Nothing item
11 lists needs a mechanism egg lacks.

**The size is a port, not a project.** egg is about 5000 lines of Rust
including tests and documentation (paper, footnote 8). Its core - union-find,
hashcons, e-class map with parent lists, `add`/`merge`/`find`, deferred
`rebuild`/`repair`, the analysis hooks, e-matching, the Runner with its
backoff scheduler, the extractor - is the part this plan ports; proof
production (`Explain`), s-expression pattern parsing and the ILP extractor
are not needed by a client that builds patterns from an IR and measures its
costs.

**Java 25 fits the data structures.** E-nodes are records over int e-class
ids (structural equality, the property Varka's IR records already rely on);
the language is a sealed hierarchy with exhaustive `switch`; the union-find is
an `int[]`; the read-only match phase parallelises over rules or classes
without locks. Nothing in Rust's ownership model is load-bearing in egg.

**One property egg does not give and Varka needs: determinism.** egg accepts
that iteration order over hash maps varies run to run; its results are
set-valued (the e-class a term lands in), so that is harmless there. Varka's
pinned renderings, shape hashes and CI reproducibility require the extracted
term to be a function of the input. The port therefore fixes iteration order
at the three points where it reaches the output (section 3.1) - a deliberate
departure from egg, recorded here so it is not "fixed" back.

**What the check would have rejected:** a need for egglog's relational
engine (it is not there at Varka's graph sizes); a language-specific port
tied to today's `VarkaVectorIR` (the library must outlive the Java-Catalyst
rewrite, so its language is generic and the IR mapping is a client); or a
port whose cost of use is boxing every id (the ids are `int` throughout, or
the point of the flat design is lost).

## 3. The design

### 3.1 The library

A pure-Java library in its own repository: Maven, Java 25, JUnit 5, JMH, no
Spark and no Arrow dependency, its own GitHub Actions and release cadence, so
it builds, tests, benchmarks and ships alone. `sql/varka/engine` is the
precedent for the build shape (Java 25 release level, surefire and JMH
wiring), not for the location: that module is Varka's own kernel code, this
is a library Varka merely uses.

| | |
|---|---|
| repository | a dedicated one under `github.com/vecbricks`, named when created |
| artifact | a `vecbricks` group id, versioned and published like any dependency |
| package | the repository's own namespace, not `org.apache.spark` |
| public surface | `EGraph<L, A>`, `Language<L>`, `Analysis<L, D>`, `Pattern`, `Rewrite`, `Condition`, `Applier`, `Runner`, `RunLimits`, `Scheduler`, `Extractor`, `CostFunction` |
| Varka's side | nothing until item 11: then one dependency on a pinned version, and the client mapping |

The egg-to-Java mapping, component by component:

| egg | Java 25 | note |
|---|---|---|
| `Id`, `UnionFind` | `int` ids; `int[] parent` with path compression | ids are never boxed on any hot path |
| `Language` trait, `define_language!` | `interface Language<L>`: operator symbol, children as an immutable int list, `equals`/`hashCode` over both, `mapChildren`; a client's language is a sealed hierarchy of records implementing it | the one real trap: a record with an `int[]` component compares the array by reference, so children live in an immutable `IntList` (or the record overrides `equals`/`hashCode`), or hashconsing silently fails |
| hashcons `H` | `HashMap<L, Integer>` first; an open-addressing map on the record hash later if measured | egg's canonicalise-then-lookup ports verbatim |
| `EClass`: nodes, parents, data | node list kept sorted by operator (egg does, for binary search in matching), parent list of `(node, class)` pairs, analysis data | plus a classes-by-operator index for the matcher |
| `add`, `merge`, `find`, `canonicalize` | paper Fig. 4, lines 1-27, line for line | `merge` only unions and enqueues; nothing is repaired in it |
| `rebuild`, `repair` | paper Fig. 4, lines 27-53; analysis maintenance per Fig. 9 | the worklist dedup is the whole speedup; the phase split (read every match, then write, then rebuild once) is the equality-saturation loop of Fig. 5b |
| `Analysis` trait | `interface Analysis<L, D> { D make(EGraph, L); D join(D, D); void modify(EGraph, int) }` | `join` must be a semilattice join and `modify` idempotent, or `rebuild` may not terminate (paper 4.1.1) |
| `Pattern`, `Subst`, `ematch` | a pattern tree of operator nodes and variables; a naive recursive matcher first, egg's compiled backtracking machine (`machine.rs`) behind a measurement later | the largest piece; at 64-node graphs the naive matcher is likely enough (prediction 5) |
| `Rewrite`, `Applier`, `Condition` | a name, a left pattern, and a right-hand side that is a pattern or an `Applier` function; conditions read analysis data | dynamic rewrites are functions of `(EGraph, matched class, Subst)` |
| `Runner`, `BackoffScheduler`, `StopReason` | an iteration loop with `RunLimits` (nodes, classes, iterations; no wall-clock limit by default) and per-rule backoff | the limit that makes extraction a function of the input |
| `Extractor`, `CostFunction` | bottom-up fixed point over local costs, per paper 4.3 | the client's cost table is Varka's measured register |
| `Explain`, `RecExpr` parsing, `LpExtractor`, `dot` | out | proofs are Herbie's need; Varka builds patterns from IR; no ILP dependency |

**Determinism, fixed at three points.** Ids are assigned by insertion order;
rewrites are searched in declared order and their matches sorted by (rule,
canonical class id, substitution) before the write phase; the extractor
breaks cost ties by the lowest node id. Every map whose iteration order can
reach an id assignment or a merge order is insertion-ordered
(`LinkedHashMap`/`ArrayList`), and section 5 has a test that runs the same
saturation in fresh JVMs and compares the serialised graphs.

### 3.2 What is deliberately unchanged

* Varka's IR, compiler and emitter: this plan builds a library and touches
  none of them. The client - `VarkaVectorIR` mirrored as a `Language` with
  e-class ids for children, the conversion nodes, the analyses, the cost
  table - is item 11's work, scheduled there (milestone 7, or earlier if
  item 1 lands a second physical representation).
* egg's algorithms: `add`, `merge`, `rebuild`, `repair` and the analysis
  invariant are ported as the paper states them; the departures are
  determinism (3.1) and omissions (`Explain`, parsing, ILP), each named.
* Java's standard collections for the first version; specialised maps only
  where the JMH numbers in section 6 say so.

### 3.3 Size and effort, registered by component

| component | lines (estimate) | acceptance |
|---|---|---|
| ids, union-find | 100 | unit tests; path compression keeps `find` idempotent |
| `Language`, `IntList`, e-node equality | 200 | equality and hashing over children; the array trap covered by a test |
| hashcons, e-classes, `add`/`find`/`canonicalize` | 300 | hashcons invariant checked after every `add` (paper Def. 2.7) |
| `merge`, worklist, `rebuild`, `repair` | 150 | congruence invariant after `rebuild` (Theorem 3.1) |
| `Analysis` and its maintenance | 150 | analysis invariant after `rebuild` (paper 4.1) |
| patterns, substitution, naive matcher | 250 | ported `simple` and `prop` tests |
| `Rewrite`, `Condition`, `Applier` | 150 | conditional and dynamic rewrites from the `lambda` suite |
| `Runner`, `RunLimits`, backoff scheduler | 250 | saturation detection; limits hit deterministically |
| `Extractor`, `CostFunction` | 150 | brute-force comparison on small graphs |
| ported test suites (`math`, `lambda`, `prop`, `simple`) | 800-1200 | same right-hand sides land in the same classes as in egg |
| JMH harness and results | 150 | section 6 |
| **total** | **2500-3000** | |

Weeks of one careful engineer, or several cheaper agents by component with
the ported suites as the oracle; the components above are ordered so each
can be built and accepted before the next (section 8).

## 4. Files

In the library's repository:

| file | what |
|---|---|
| `pom.xml` | the build, on `sql/varka/engine/pom.xml`'s shape minus Arrow; publishing configured from the start |
| `.github/workflows/` | build, test at the Java 25 release level, JMH on demand |
| `EGraph.java`, `UnionFind.java`, `EClass.java`, `IntList.java` | the graph |
| `Language.java`, `Analysis.java` | the two client interfaces |
| `Pattern.java`, `Subst.java`, `Matcher.java` | matching |
| `Rewrite.java`, `Condition.java`, `Applier.java` | rules |
| `Runner.java`, `RunLimits.java`, `Scheduler.java`, `BackoffScheduler.java`, `StopReason.java` | the loop |
| `Extractor.java`, `CostFunction.java` | extraction |
| `src/test/java/` | invariant tests, determinism test, the four ported suites |
| `src/jmh/java/` and `benchmarks/` | section 6, with the provenance header Varka's result files carry |
| `PLAN.md` (this file, moved) and `README.md` | the record and the front door |

In Varka, now: `SCOPE_MILESTONE_8.md` item 11 pointing here. In Varka, at
item 11's time: the dependency on a pinned version and the client mapping,
planned there.

## 5. Tests, and what each is for

* **Invariants, as debug checks and as tests.** The hashcons invariant after
  every `add` and `merge`+`rebuild`; the congruence invariant after
  `rebuild` (no two congruent nodes in different classes); the analysis
  invariant (each class's data equals the join of `make` over its nodes).
  The failure they catch: a `repair` that forgets to re-canonicalise a
  parent, the paper's whole subject.
* **Deferred against eager rebuilding.** The same saturation run with
  `rebuild` after every `merge` and once per iteration must yield identical
  graphs (paper 3.4 ran this over its suite); the failure is a merge that
  reads a stale hashcons.
* **The ported suites.** egg's `tests/simple.rs`, `prop.rs`, `math.rs` (with
  its constant-folding analysis) and `lambda.rs` (the paper's Figures 10-11:
  an analysis carrying free variables and constants, conditional rewrites,
  and a dynamic capture-avoiding substitution). Each test adds a term, runs
  the rules, and asserts the right-hand side is in the term's class - the
  same assertions egg makes, so a divergence names the component.
* **Determinism.** One saturation serialised (nodes, classes, data,
  extracted term) from several fresh JVM invocations and from repeated
  in-process runs must be byte-identical; the failure it catches is hash
  iteration order reaching an id or a merge.
* **The array trap.** Two structurally equal e-nodes built from separate
  child lists must hashcons to one class; the failure is reference equality
  on children.
* **Extraction.** Against brute-force enumeration of all represented terms
  up to a depth on small graphs; ties broken by lowest node id, asserted.
* **Limits and scheduling.** Associativity plus commutativity over a small
  term: the Runner must stop at the node limit deterministically, and the
  backoff scheduler must ban and re-admit the expansive rule the way egg's
  does (its iteration log is the fixture).
* **A Varka-shaped smoke test**, no Varka dependency: a toy date language
  with `AddDays`, `Lit`, `Col`, one folding rule for literal offsets, and a
  cost function that reads a table, extracting `date_add(d, 3)` from
  `date_add(date_add(d, 1), 2)`. The failure it catches is an API that
  cannot express item 11's first use.

## 6. The measurement

A library's measurement is its own harness, committed under the repository's
`benchmarks/` with the provenance header Varka's result files carry, on an
idle machine. Cross-language comparison with egg's Rust
numbers is not attempted; the ratios the paper established are.

* **Deferred against eager rebuilding**, on the ported `math` and `lambda`
  suites: time to saturation with `rebuild` once per iteration against after
  every `merge`, and the count of `repair` calls in each mode, per test.
* **Absolute cost at Varka's size**: a 64-node graph over the toy date
  language with 20 rules, time to saturation or limit, and extraction time.
* **Naive matcher against the compiled machine**, if and when the machine is
  built: the same suites, both matchers.

### 6.1 Predictions, registered before the run

1. Deferred rebuilding is at least 5x faster than eager on the largest
   ported tests and near 1x on the smallest - the paper's Fig. 7 shape, a
   speedup that grows with rewrites applied - and the `repair` count tracks
   the time (its Fig. 8).
2. Every ported egg test passes with the right-hand side in the expected
   class; none needs a semantic change to pass.
3. The determinism test passes across ten fresh JVM runs with no seed
   control.
4. A 64-node graph with 20 rules saturates or hits its limit in under 5 ms
   and extracts in under 1 ms, so per-shape compilation cost stays
   negligible beside emission.
5. The naive matcher is within 3x of the compiled machine at that size, so
   the machine can wait for a client that needs it.

## 7. Risks

1. **Reference equality on children** in a record-based language - the
   hashcons never finds anything and the graph is a tree. The array-trap
   test, and `IntList` as the only child container.
2. **Nondeterminism leaking through iteration order** - correct results,
   different ids, different extraction. The multi-JVM determinism test; the
   three fixed points in 3.1.
3. **Expansive rules** (associativity, commutativity, distributivity)
   growing the graph past any bound - the note's own open question. Node
   and iteration limits plus backoff, tested in section 5; the client's rule
   sets are small by design.
4. **An analysis whose `join` is not a semilattice join or whose `modify` is
   not idempotent** - `rebuild` cycles. The analysis-invariant test, and a
   debug-mode check that `join(a, b) == join(b, a)` and `join(a, a) == a`
   over the data seen.
5. **Matching cost at larger graphs** if a client grows past Varka's sizes.
   Prediction 5 says when the compiled machine is due; it is a contained
   component.
6. **Scope creep toward egglog** (multi-patterns, incremental runs, proofs).
   Section 3.2 names what is out; a need for any of it is a new plan.
7. **A second repository's overhead**: its own CI, publishing and
   versioning, and a dependency Varka's build must resolve without a snapshot
   repository in the way. Configure publishing in the first commit, release
   from tags, and pin the version on Varka's side when item 11 adds it;
   never a snapshot dependency in the fork.

## 8. Sequencing

Each commit green alone, with its tests:

1. The repository: build, CI, publishing configuration, licence and
   attribution to egg (MIT), this plan moved in as `PLAN.md`; then ids,
   union-find, `IntList`, `Language`, hashcons, `add`/`find`/`canonicalize`;
   the array-trap and hashcons-invariant tests.
2. `merge`, the worklist, `rebuild`/`repair`; the congruence-invariant and
   deferred-against-eager tests.
3. `Analysis` and its maintenance; the constant-folding analysis and the
   `math` suite.
4. Patterns, substitution, the naive matcher; `Rewrite`, `Condition`,
   `Applier`; the `simple` and `prop` suites.
5. `Runner`, `RunLimits`, the backoff scheduler, the determinism fixes and
   test; the limits-and-scheduling test.
6. `Extractor` and `CostFunction`; the brute-force and tie-break tests; the
   Varka-shaped smoke test.
7. The `lambda` suite (dynamic and conditional rewrites end to end).
8. The JMH harness, one regeneration on an idle machine, section 9.
9. Optional, by prediction 5: the compiled matching machine behind a
   measurement.

The client - Varka's IR as a `Language`, conversion nodes, the analyses and
the cost table - is not in this sequence; it starts under item 11 when a
second physical representation exists to choose between.

## 9. Outcome

<!-- Filled in when the measurement lands: the numbers with the committed file
     they trace to, 6.1's predictions scored one by one, what moved that the
     plan did not list, and what the port leaves for later - which goes to
     item 11 or the milestone's debt register, never to a code comment. -->
