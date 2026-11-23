# VARKA-489: Node properties as a descriptor

## 1. Where this came from

Milestone 8's row 489, `VARKA-475.md` 9.2 item 3, added 24 October 2026 from the lessons of rows
484, 486 and 488: a new IR node reached about twenty places (`VARKA-475.md` 2, finding 6), most
of them exhaustive switches over every node type that each ask one small property of the node.
The row gives each node one record of those properties, required at construction, and moves the
switches that only ask a property to read it.

The row was built once already. On 27 October 2026 commit `81f2553ea4e` ("Read node properties
from one descriptor per IR node") did the whole row over `4e09c1ecfce`: `VarkaNodeProps`, a
`props()` on each of the then 47 records, ten switches reading it, `VarkaNodePropsSuite`, an
equivalence test over 229417 distinct nodes (run, then deleted) and byte-identical oracles. It
never became a pull request. It is parked on the owner's fork as `attic/varka-489-descriptor`,
and its plan file on that branch holds the inventory, the design and the recipe
for a new node. `m9/SCOPE.md` item 47 (11 November 2026) records why it is not on master: the
branch predates #1329's cached hash on every node and the lane pair's nodes, so "finishing the
row means rebuilding it over master". This plan is that rebuild. It reuses the branch's design
and text where they still hold, and says below where master has moved.

## 2. The admission check, done

Read on `origin/master` at `e5d70012bdc`, 20 November 2026.

- **Nothing of the row is on master.** No `VarkaNodeProps` and no `props()`; the only file named
  for descriptors, `VarkaDescriptors.java`, is the emitter's `ClassDesc` table, a different thing.
  `git log --grep=VARKA-489` finds no commit, and no pull request of vecbricks/varka has the id in
  its title.
- **The switches the row names still exist and have grown.** The node types went from 47 to 56
  records: `BoolOutput`, `ConvertLane`, `CrossLane`, `DecimalColumn`, `DecimalWord`, `MathUnary`,
  `NullValue`, `RoundToIntegral` and `WidenDecimal` are new. Each switch is exhaustive, so it
  names all 56 records; what differs is how many outer `case` arms it spreads them over (one arm
  may list many records), counted in the main tree: `weightOf` 42 (`VarkaEmitBudget.java:616`;
  45 with `TruncDate`'s nested level switch), `kindOf` 25 (`VarkaEmitCost.java:114`), `admits` 8
  (`VarkaOperandPosition.java:79`; 12 with the two nested position switches) and
  `restrictedOperands` 6 (`:134`), `inputOrdinal` 5 (`VarkaVectorIR.java:227`), `isNarrowRing` 4
  (`Analysis.java:1076`), `ownerOf` 37 and `pureOf` 45 (`Analysis.java:1422`, `:1483`),
  `planWordRef` 36 and `propagate` 56 (`Slots.java:1173`, `:1757`). Each of the nine new records
  added a record name, and often an arm, to every one of them, which is the cost the row removes.
- **Three new per-node switches since 27 October.** `VarkaKernelPlan.longNarrowReader` (`:833`,
  11 arms, row 343's long-lane narrowing) answers one fixed value per node type from no field at
  all, class (a) in the branch's inventory; `longNarrowRole` (`:775`) reads the child's lane,
  class (c).
  `VarkaSameAnswer`'s NaN step (`:261`, row 593) is per-node behaviour over the children, class
  (b). Only `longNarrowReader` joins the descriptor (3.1).
- **The branch does not apply.** `git merge-tree` of the branch onto master conflicts in all six
  code files it touches (`Analysis`, `Slots`, `VarkaEmitBudget`, `VarkaEmitCost`,
  `VarkaOperandPosition`, `VarkaVectorIR`); 30 master commits touched those files since its base.
  So the code is rewritten over master with the branch as the reference text, not cherry-picked.
- **Too big for one pull request.** `dev/varka_msg_check.py size` counts the branch at 1385 lines
  against its base, over the cap, with 47 records; at 56 it is larger. Section 8 splits it.

What would have rejected the row: a master on which the property switches had already been
folded some other way, or on which no new node had arrived since. Neither holds: nine nodes
arrived, and each paid the arms the row removes.

## 3. The design

### 3.1 The descriptor

As built on the branch (its section 1), with one field added. `VarkaNodeProps` is a record beside
the IR with the components `Ring ring` (sealed: `Never`, `Pick`, `Arith(Overflow mode)`),
`Validity validity` (sealed: `Input(int ordinal)`, `AllTrue`, `Strict`, `SkipsNull`, `Own`,
`NoWord`), `int weight`, `String costKind`, `Set<VarkaOperandPosition> fills`,
`List<VarkaOperandPosition.Restricted> restricted`, and, new, `LongNarrowReader longNarrow`. The
row's five named properties map onto it as the branch's section 0 says: ring under narrow
reduction is `ring`, pure and skips a null input are both `validity`, budget weight is `weight`,
cost kind is `costKind`. Each answer reads the node's own fields and lane and the structure of
its operands (their record type, lane, a guard's mode and bounds), never the options or an
analysis. Master's (a) switches need that much: `admits` lets `WidenLane` sit at `DAY_OFFSET`
only over a narrow `ColumnRef` (#1389) and `NarrowLane` at `MONTH_COUNT` only over a `FAIL`
`GuardedRange` within the month bounds (#1442), and `weightOf` prices a `Compare` by its left
operand's lane, a `ConvertLane` by its child's and a `RoundToIntegral` by
`VarkaRoundingLowering.mayHoldOtherNaN`, which looks one level further down. Those tests move
into the record's `props()` as they stand, so a `WidenLane`'s `fills` is `{DAY_OFFSET}` or empty
by its child. What depends on the options stays the pass's (`isNarrowRing` asks
`loweredUnchecked` of an `Arith`'s mode), and what depends on the operands' own analysis (their
words, their owners) is folded by the pass over `childrenOf`.

`VarkaVectorIR` declares `VarkaNodeProps props()` with no default, and every record implements it
beside its constructor checks, so a record without it does not compile. `props()` is a method,
not a record component: the records' structural `equals` and `hashCode` are the memo keys and the
shape cache's identity, and since #1329 the hash is cached per node; a derived descriptor must not
enter either.

**Alternatives.** (a) One exhaustive `switch (node)` inside `VarkaNodeProps`: the compiler enforces
it equally and it is about a third of the lines (one arm per record against nine or ten), but a
new node is then written in two files, its record and the switch. (b) The per-record method, the
branch's choice, so a new node states its facts beside the constructor that checks its operands.
The plan takes (b); question 1 asks the owner. Building both is not worth it here: it is a
refactor whose output is identical bytes either way, so there is nothing to measure between them.

### 3.2 The switches that read it

From the branch's inventory, updated to master: the five (a) switches (`weightOf`, `kindOf`,
`admits`, `restrictedOperands`, `inputOrdinal`) and the new `longNarrowReader` read one component
each and lose every arm, the operand tests of 3.1 included, which move into `props()`;
`isNarrowRing` reads `ring`; the three hand-kept copies of the word rule
(`Analysis.ownerOf`, `Analysis.pureOf`, `Slots.planWordRef`) fold the one `validity` over the
operands; `Slots.propagate`, reached only by an own-word node, demands its value operands' words.
The branch's outcome records one trap to keep: `Validity.Input` must not refuse a negative ordinal,
since the analysis reads the descriptor before the emitter's admission, and the admission's own
error ("column ordinal -1 outside", `VarkaEmitterContractSuite`) must stay the one a caller sees.

### 3.3 What is deliberately unchanged

The node's behaviour stays exhaustive switches: lane and structure (`laneType`, `childrenOf`,
`withChildren`, both canonical forms), lowering (`Analysis.analyze`, `VarkaVectorWalk`'s walks,
the calendar family's), the compilers' rewrites, `VarkaRangeAnalysis.range`, `collectRefusals`,
`Slots.visit`, `narrowValuesIn`, `needsNarrowSpecies`, `longNarrowRole`, `VarkaSameAnswer`'s NaN
step, and the test harness's evaluators and grammars. Deriving further facts bottom-up from the
descriptor (a non-canonical NaN, may raise, reads a decimal slot) is `m9/SCOPE.md` item 47, after
this row; the node meanings row 506 keeps beside `VarkaReference` move into the descriptor in that
row's own follow-up (`VARKA-506.md` 2.2), not here.

### 3.4 Registered op counts

None: no kernel's bytes change, and the three byte oracles say so.

## 4. Files

| file | what |
|---|---|
| `VarkaNodeProps.java` (new) | the record, `Ring`, `Validity`, constants |
| `VarkaVectorIR.java` | `props()` on the interface and on all 56 records; `inputOrdinal` |
| `VarkaEmitBudget.java`, `VarkaEmitCost.java`, `VarkaOperandPosition.java` | (a) switches read it |
| `VarkaKernelPlan.java` | `longNarrowReader` reads it |
| `Analysis.java`, `Slots.java` | `isNarrowRing` and the word-rule folds |
| `VarkaNodePropsSuite.java` (new) | the guarantee and the drawn-shape checks |
| `VarkaNodePropsEquivalenceSuite.java` (new, gone in 489c) | old switches against `props()` |

## 5. Tests, and what each is for

- `VarkaNodePropsSuite`, kept: fails if `props()` gains a default on `VarkaVectorIR`, `Cond` or
  `Chrono` (which would let a family member inherit an answer), walks the sealed hierarchy checking
  each permitted record declares its own, and over 500 drawn shapes checks that a `Strict` or
  `SkipsNull` node's operands are values, an `Input` node is a leaf whose ordinal `inputOrdinal`
  reports, and `NoWord` belongs to conditions only.
- `VarkaNodePropsEquivalenceSuite`, temporary: while an old switch still exists, the live function
  against the descriptor over every distinct node of 4000 drawn shapes of all the IR fuzzer's
  grammars plus hand-built variants of every mode, lane, sign and level (the word rules compared
  operand by operand, the ring rule under both values of `checkIntOverflow`). Each pull request
  that deletes a switch first runs the suite and records the node count in 9, then deletes that
  switch's check, because a frozen copy of an old switch is a per-node list a new node would have
  to touch, the cost the row removes.
- The byte oracles: `VarkaEmittedBytesSuite`, `VarkaCoverageSuite` and `VarkaWidthAuditSuite`
  pass against `emitted_bytes.json`, `coverage.json` and `width_audit.json` unchanged, and the
  coverage suite's fusion dump (`VARKA_FUSION_DUMP`) is identical before and after each pull
  request, since the oracles pin acceptance only (`VARKA-475.md` 9.1, finding 5).

## 6. The measurement

No performance measurement: the row changes no emitted byte. The checks are the oracles and the
fusion dump above, and the scoped suites (`dev/varka_scoped_suites.sh catalyst`) on CI.

### 6.1 Predictions, registered before the run

1. The equivalence suite finds no disagreement on any of the 56 record types; the branch found
   none on 47. A disagreement is a mistake in a new record's `props()`, fixed there, not a reason
   to change a pass.
2. `VarkaNodePropsSuite` runs in under 5 s of test time: a catalyst suite with no session over
   500 drawn shapes, priced at VARKA-328's observed ratio of about 0.03 for such a suite. The
   equivalence suite runs in under 60 s: about 20 s estimated, read at VARKA-488's observed ratio
   of 2.9, the closer precedent, since its word-rule checks build each of the 4000 shapes'
   `Analysis` and `Slots`, the compile-side work 488's estimate priced as IR walks. The branch's
   229417-node run was not timed.
3. The fusion dump is byte-identical before and after each of 489a to 489c.
4. After 489c, adding a node touches no line of `VarkaEmitBudget`, `VarkaEmitCost`,
   `VarkaOperandPosition` or `VarkaKernelPlan.longNarrowReader`, and its word rule is one
   `Validity` rather than arms in three places; checked by listing the files the 56 records'
   arms leave, against section 2's counts.

## 7. Risks

1. A new record's `props()` answered wrongly, by hand, where the old arm was right: the
   equivalence suite (5) is what catches it, so no switch is deleted before that suite has passed
   over it.
2. A branch open on master when 489b or 489c lands, adding a node, conflicts in the switches. The
   branch's rebase recipe (its section 2's last paragraph) holds: delete the arms, move each
   answer into the record's `props()`; 489a's description says so for open branches.
3. The cost-kind strings must stay exactly what `VarkaEmitCostTable` is keyed by; a typo moves a
   group to "no prediction" without failing a byte oracle. `VarkaEmitCostSuite` and the
   equivalence suite's `kindOf` check cover it.

## 8. Sequencing

Three pull requests, each under the cap, the order fixed by what each deletes:

- **489a** (about 900 counted lines): `VarkaNodeProps`, `props()` on all 56 records,
  `VarkaNodePropsSuite`, and the equivalence suite against every live switch. No pass changes yet,
  but five of the switches are private (`Analysis.isNarrowRing`, `ownerOf` and `pureOf`, instance
  methods; `Slots.planWordRef` and `VarkaKernelPlan.longNarrowReader`, static; `propagate`, in
  `Slots`' inner demand walk), so 489a makes them package-private with a comment naming the
  suite, and the suite builds an `Analysis` and `Slots` per shape to call the instance ones; the
  widening goes with each switch in 489b or 489c.
  Done when `build/sbt 'catalyst/testOnly *VarkaNodeProps*'` passes. If the count is over the cap,
  the equivalence suite moves to 489b with the first deletions.
- **489b** (about 350): `weightOf`, `kindOf`, `admits`, `restrictedOperands`, `inputOrdinal`,
  `longNarrowReader` and `isNarrowRing` read the descriptor; their equivalence checks run, are
  recorded, and go. Done when the byte-oracle suites pass and the fusion dump diff is empty.
- **489c** (about 300): `ownerOf`, `pureOf` and `planWordRef` fold `validity`, `propagate` demands
  value operands; the equivalence suite is deleted; this file gets the recipe for adding a node
  (the branch's section 2, renamed to master's files) and its outcome. Done when the same checks
  pass and `dev/varka_scoped_suites.sh catalyst` is green on the PR Build.

Each takes row 489's status line forward; the row is Done at 489c's merge.

## Questions for the owner

1. **Per-record `props()` or one switch in `VarkaNodeProps`** (3.1)? *Recommended: per record*, as
   the branch built it: the done-when asks that a new node touch "its record and its lowering",
   which only (b) gives. It costs about 600 lines against about 200 and makes 489a the largest
   part. Not mine to decide: it is a code-layout choice the owner reviews in every later node.
2. **Take the row now, though "before decimal's lane pair (359)" is overtaken?** *Recommended:
   yes, before rows 360 to 363 add more nodes and before `m9/SCOPE.md` item 47, which depends on
   it.* Every node added first is one more arm to move. Not mine: the order of m8's rows against
   its type work (row priority is the owner's, the new types first).

## Decisions taken while the owner was away (20 November 2026)

The owner was away for the morning of 20 November 2026, and the build ran in an
implementation-first window: everything local, no pull request, no CI, tests written and only
compiled, with one targeted run. Each decision below is the option the plan or `sql/varka/AGENTS.md`
favours, and each can be reversed in review.

1. **Question 1, per-record `props()`**, as recommended: the done-when asks that a new node touch
   "its record and its lowering", which only the per-record method gives. The bodies are 495
   lines over 56 records against the branch's 488 over 47, about nine lines a record, written
   from master's arms and not from the branch's, whose weights, cost keys and positions master
   has since changed (`IntArith`'s floating division, the long-lane `abs`, the floating orders,
   `ConvertLane` from a long, `GuardedRange`'s `NULL` mode, the conditional positions of
   `WidenLane` and `NarrowLane`).
2. **Question 2, the row now**, as recommended: nine nodes arrived since the branch, and every
   one added before 489b is one more arm to move.
3. **`LongNarrowReader` moves into `VarkaNodeProps`** as a public nested enum, with its `takes`.
   It was a private enum of `VarkaKernelPlan`, which a public record's component cannot name; a
   package-private enum of `VarkaKernelPlan` would have been the other choice, but it would make
   the descriptor's public accessor return a type no caller outside the package can see.
   `LongNarrowRole` stays in `VarkaKernelPlan`: it is class (c), read from the child's lane, and
   the descriptor does not hold it (3.3).
4. **The equivalence suite moves to 489b**, the plan's own fallback in 8: 489a with the 56
   bodies, the record and its suite is already near the cap, and the equivalence suite is needed
   only from the first deletion on.
5. **The drawn corpus is every grammar of `VarkaIrGrammar`**, eighteen of them taken in turn, not
   the two the branch's suite drew: the decimal, floating, narrow-lane and cast grammars are
   where master's new records are drawn. `VarkaNodePropsSuite.drawnShapes` and `nodesOf` are the one corpus, and
   489b's equivalence suite reads it.
6. **The cost kind is built with `getClass().getSimpleName()`**, the expression `kindOf` uses,
   rather than a literal record name, so the key cannot drift from the class's name.

## Done when

The switches of 3.2 read the descriptor, a new node touches its record and its lowering rather
than about twenty files (6.1 prediction 4), and `emitted_bytes.json`, `coverage.json` and
`width_audit.json` are byte-identical, with the fusion dump identical too.

## 9. Outcome

The 27 October build's outcome is section 4 of the plan file on `attic/varka-489-descriptor`.

**489a, 20 November 2026** (branch `varka-489-node-descriptor`, over `6ac76f86cb3`). Built as 8
plans it, less the equivalence suite (decision 4): `VarkaNodeProps` with its seven components,
`LongNarrowReader` moved into it from `VarkaKernelPlan`, `props()` on all 56 records with no
default on the interface, and `VarkaNodePropsSuite` over a corpus of all eighteen grammars. No
pass reads the descriptor yet, so no emitted byte can change, and the byte oracles and the
fusion dump are owed with 489b, where they first can move.

- **Where the plan was wrong:** it counted the per-record bodies at about 600 lines for 56
  records, from the branch's 488 for 47; written from master's arms they are 495, because a
  record states one constructor call of seven arguments and keeps its arm's comment, while the
  branch had a line per argument.
- **Measured:** `build/sbt catalyst/Test/compile` passes. The suite's time (prediction 2) is not
  measured yet.

**Rebased onto master, 23 November 2026** (`f3b97c0b503`). Master had added five records since
the build, `ReinterpretLane`, `Fma`, `CanonicalFloat`, `CanonicalBits` and `HashStep`, each given
its `props()` from master's switch arms. `HashStep`'s word is its seed's alone (a null word leaves
the hash as it is), which none of the six validity kinds says, so a seventh, `Operand(index)`,
aliases one operand's word; `ownerOf`, `pureOf`, `planWordRef` and `inputOrdinal` fold it in
489b and 489c. The equivalence suite, with the five records added to its hand-built corpus, ran
in a scratch tree at 489b's first commit: it first found `MathUnary`'s weight stale (master's
per-lane calls weigh 16), then, fixed, **97536 distinct nodes of 61 record types** agreed, with
**7850 analyses** and 336564 word entries. Master's options overload of `weightOf` (row 600's
`L2F` forms) stays in `VarkaEmitBudget`, reading the descriptor for every other node.
