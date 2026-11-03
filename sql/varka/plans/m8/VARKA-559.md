# VARKA-559: Planning a shared IR node costs its node count, not its paths

*Planned and built 3 November 2026. Evidence not in the code is in
`results/VARKA-559-planning-cost.txt`: the probe's timings on master, on the branch of row 348's
third part, and on that branch with this change.*

## 1. Where this came from

Row 559 reopens `m7/VARKA-222.md`, closed on 10 October 2026 on "IR hashing is at most 5% of
emission because the fused budget caps every output's subtree". Its reopen trigger (3.1) is "a
profile of a real workload's emission showing IR hashing well past the shares above". Row 348's
third part (branch `varka-348a3-choices`, #1324) met it on 3 November 2026: planning a nested
decimal `coalesce`, `(coalesce(IF((coalesce(IF((...) > b, a, c), b) ...) > b, a, c), b)) > c`
over `DECIMAL(9, 2)` columns, cost 2^depth in `explainPredicate`, 738 of 739 JFR stack samples in
`Objects.hashCode` under the generated `hashCode` of `IfElse`, `CondIf`, `Compare` and
`IsNotNull`. Each level builds `IfElse(CondIf(cond, ..), IfElse(cond, a, c), b)` over one
compiled `cond`, a record holds no cache, so every hash walks a shared node once per path. The
payers run before the budget check and inside it, so the budget bounds none of it. The owner
decided on 3 November 2026 to reopen VARKA-222 as this row and to hold #1324 until it lands.

## 2. What exists now (admission check)

### 2.1 The chains, timed

Measured with `VarkaPlanningCostProbe`, `explainPredicate` at depths 2 to 20
(`results/VARKA-559-planning-cost.txt` 1 and 2):

- **The int lane on master does not build the coalesce's shared node.** `compileCoalesce` takes
  only a bare column before the last operand, so `coalesce(IF(...), b)` over `INT` declines at
  the first level ("coalesce operand before the last is not a bare column"), and the int `CASE`,
  null test and `nvl2` decline as soon as they test a non-column. `IF` and `greatest` nest
  without sharing a node and stay under 1 ms to depth 20, declined for the budget from depth 8
  and 6.
- **Master has the cost elsewhere.** Three lowerings read one operand several times: an
  interval's `abs` three (`VarkaIntervalCompiler.absolute`), its division by a literal more
  (the quotient, the remainder and the rounding's sign test each read `months`, and the rounding
  reads the remainder twice), a `DOUBLE` `signum` four
  (`VarkaFloatingCompiler.signum`). Nested, all three are declined for the budget, after
  planning that took 25544.44 ms at depth 16 (`abs`), 10017.80 ms at 8 (division) and
  17585.49 ms at 12 (`signum`).
- **On row 348's part** the decimal null test and `CASE` stay about 1 ms and the `coalesce`
  takes 4576.85 ms at depth 20, four times per two levels, as row 348 measured.

### 2.2 Every IR record and every structure keyed on IR nodes

The IR (`VarkaVectorIR.java`) is 53 records: 6 leaves (`ColumnRef`, `DecimalColumn`,
`LiteralSlot`, `NullValue`, `BoolColumn`, `CondConst`), whose hash reads no node, and 47 records
with a node child, every other one. No node is interned: the "IR's interned DAG" of
`m9/SCOPE.md` item 1204's text is the structural collapsing the records' `equals` gives, not a
table of canonical nodes.

Hash- or equality-keyed structures over IR nodes, all structural (`HashSet`, `HashMap`,
`LinkedHashMap`), in the `codegen/varka` package (`git grep` of `Set<VarkaVectorIR>` and
`Map<VarkaVectorIR, ...>`):

- **Planning, before any byte is emitted:** `VarkaVectorIR.lanesIn`, `narrowValuesIn` and
  `needsNarrowSpecies` (their `seen` sets); `VarkaKernelPlan.of` (`nodes`, `operands`,
  `storeNarrowings`), `conversionRefusal`, `loweredAtLong` and `holdsCheckedNarrowMultiply`
  (their `seen` sets); `VarkaLoopEmitter.fitsBudgets` and `budgetWalk` (`heights`). These are
  the payers of the coalesce: `predicateOnce` calls `VarkaKernelPlan.of` and then `fitsBudgets`,
  which calls it again, for every conjunct.
- **Emission, on an admitted shape only:** `Analysis` (about 25 maps and sets: `analyzed`,
  `lineNumbers`, `refusals`, `condReach`, `wordOwner`, `pureWord`, `height`, `materialized`...),
  `Slots` (about 30: `wordRef`, `kt`, `kf`, `condMask`, `sharedSlot`, the `*Tmp` maps...),
  `VarkaBodyEmitter`'s and `VarkaVectorWalk`'s `computed`/`done` sets, `VarkaLoopEmitter`'s
  grouping sets. An admitted output is at most `MAX_CHAIN_DEPTH` = 16 high, which is what bounded
  them in VARKA-222's profile.
- **One identity map already:** `VarkaRangeAnalysis.guardColumns` memoises its rebuild on an
  `IdentityHashMap`.

Identity sets are not a drop-in for the structural ones: `budgetWalk`'s node count and
`VarkaKernelPlan.of`'s `operands.contains(root)` rely on structurally equal nodes collapsing into
one, and the emitter's memo computes a subtree once per lane group "no matter how the caller built
the trees" (the IR's own doc).

### 2.3 Walks that recurse once per path, which a cached hash does not change

- **`VarkaVectorIR.canonical`**, the pinned rendering behind the shape hash, renders a shared
  subtree once per parent: its output is tree-sized by definition. It runs on admitted shapes
  (the shape key, the reproduction file, error messages), whose height the budget caps. Left as
  is: its text is the committed shape hashes.
- **The records' generated `toString`**, in a few `illegalArgument` / `illegalState` messages
  (`Analysis.java:1142`, `1690`, `1740`): error paths only.
- **The lane lifts** `VarkaConditionCompiler.onIntLane`, `onLongLane`,
  `VarkaIntegralCastCompiler.onLane` and `VarkaChronoCompiler.rearm` rebuild a condition
  without a memo, so a shared node is rebuilt once per path into distinct, equal objects. They
  run only where a predicate's conjuncts sit on two lanes (a narrow or int conjunct beside a
  long one), not on any chain above.
- **`equals` between two disjoint copies.** A record's `equals` tries identity first, so a node
  shared within one compile equals itself in one step; two equal DAGs that share no object - what
  the lifts above produce - compare a shared node once per path. Within one compile every shared
  node is one object (row 348's part reuses each compiled condition), and the cached hash makes
  unequal nodes differ in one comparison.
- **`VarkaVectorIR.reaches`** recurses per path; nothing in the main sources calls it.
- **`laneType()`** follows one child per level: linear in the depth, not exponential.

## 3. Design

### 3.1 The three options

- **(i) Cache the structural hash once per node.** A record holds no field but its components,
  so the hash is a last component, `int hash`, computed in the compact constructor from the
  other components and returned by an overriding `hashCode`; a constructor without it keeps
  every call site as it is, and the canonical one ignores the value it is given. Cost: three
  lines a record (47 records, about 175 lines), a trailing `_` in the 31 record patterns that
  destructure IR nodes (all in the test's `VarkaReference`), and `hash` in each record's
  accessors and `toString`. The records stay records, as `sql/varka/AGENTS.md`'s first Java rule
  asks; converting them to final classes with a cached field would give the same hash at several
  times the lines and lose the record patterns the IR is the model of.
- **(ii) Intern nodes at construction**, so structural equality becomes identity: a global or
  per-compile table every constructor goes through, `withChildren` and the lifts included, and a
  second notion of node identity through `Analysis` and `Slots` - what VARKA-222 priced as too
  complex. Nothing interns today (2.2).
- **(iii) Memoise per planning pass**, an `IdentityHashMap` from node to hash or result inside
  each walker. It cannot replace the structural sets (2.2), only sit beside them, and a structural
  set still hashes its keys, so each walker would need a structural hash of its own: the cache of
  (i), rebuilt per walk and per walker, eleven walkers in planning alone.

### 3.2 The choice: (i)

It is the smallest change that makes every structural set and map linear in the DAG's node count
at once, the planning payers and the emission maps alike, with no walker edited. The hash value
is the one the records' generated `hashCode` gives (`ObjectMethods.makeHashCode`:
`31 * h + hashCode(component)` from zero, in declaration order), so every set and map holds what
it held before. That is not what keeps the bytes: an enum's hash is its identity hash, which
differs from one JVM to the next, so the iteration order of a set of nodes already differs
between runs, and a committed `emitted_bytes.json` that does not move between runs already says
no emitted byte depends on it. The regeneration below is the proof for this change.

The generated `equals` compares components last first (`ObjectMethods.makeEquals` wraps each
component's test around the previous ones), so with `hash` last two nodes of different hashes
differ in one comparison.

### 3.3 What is deliberately unchanged

`canonical`, the lifts and `equals` over disjoint copies (2.3), and every walker. A lift memoised
on an `IdentityHashMap`, as `VarkaRangeAnalysis.guardColumns` is, would keep a shared node one
object through it; no chain here reaches one.

## 4. Files

| file | what |
|---|---|
| `VarkaVectorIR.java` | `hash` on the 47 records with a child, `hashOf` |
| `VarkaReference.java` (test) | the record patterns name the new component |
| `VarkaVectorIRSharingSuite.java` (test) | 5 below |
| `VarkaPlanningCostProbe.java` (test) | the timings of 2.1 and 6 |
| `results/VARKA-559-planning-cost.txt` | the probe's output |

## 5. Tests, and what each is for

`VarkaVectorIRSharingSuite`:

- A chain built as the coalesce builds it, 60 levels (2^60 paths), on the long, int and short
  lanes, through `VarkaKernelPlan.of`, `admit`, `lanesIn`, `narrowValuesIn`,
  `needsNarrowSpecies` and `fitsBudgets` within 20 s: a per-path walk could not finish, a
  per-node one takes milliseconds, so the bound decides it without timing anything close.
- The same chain built twice to depth 10: equal, equal hashes, and the union of their nodes in a
  structural set grows by exactly four a level - structurally equal nodes still collapse.
- By reflection over the sealed IR: every record with a node child has `int hash` last and
  declares `hashCode`, so a node type added later caches too; and each node's hash is the
  records' formula over its other components.
- The canonical constructor recomputes the hash it is given.

## 6. The measurement

Section 2.1's chains with this row's commit (`results/VARKA-559-planning-cost.txt`): every one
is flat to depth 20, each explain under 2 ms, every verdict - fused or its decline reason - the
same at every depth before and after.

| chain | depth | before | after |
| --- | ---: | ---: | ---: |
| interval `abs`, master | 16 | 25544.44 ms | 0.48 ms |
| interval division, master | 8 | 10017.80 ms | 0.55 ms |
| `DOUBLE` `signum`, master | 12 | 17585.49 ms | 0.84 ms |
| decimal `coalesce`, row 348's part | 20 | 4576.85 ms | 1.77 ms |

`env SPARK_LOCAL_IP=127.0.0.1 dev/varka_regen_generated.sh --all` regenerates every generated
file without a change: `emitted_bytes.json`, `coverage.json` and the width audit are byte for
byte master's, so no shape's bytes moved.

## 7. Open points for the lead

- **#1324's test can go back to depth 20.** `VarkaDecimalValueSuite.nestedConditionsCompileOnce`
  nests the `coalesce` 12 deep, not 20, for this cost; with this row merged first it can nest all
  three 20 deep, as its comment's last sentence expects.
- **The lifts are left unmemoised (2.3).** They meet a shared node only in a predicate whose
  conjuncts sit on two lanes; an `IdentityHashMap` memo per lift, as `guardColumns` has, would
  close it if a chain is found that reaches one.

## 9. Outcome

Built 3 November 2026 on branch `varka-559-ir-hash`, as one pull request, not opened.
