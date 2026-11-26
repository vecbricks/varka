# VARKA-291: the IR's storage, records against flat layouts

## 1. Where this came from

Row 291 of `m7/PLAN.md`, opened 6 October 2026 at the owner's request. Item 85 of `m10/SCOPE.md`
ends on a storage question: hold the IR's nodes as rows of flat arrays, in memory the client
supplies (jegg issue #92), instead of as the records of `VarkaVectorIR`. The owner then asked
whether the migration to jegg could come sooner, with "all Varka IRs as rows in flat arrays", and
whether Valhalla's value classes were worth an experiment. Item 85 records the probe that answered
the second question in part and the experiment that answers the rest (its "storage spike"), and
says the question is a measurement. This task is that measurement, scheduled in the milestone in
flight, with its plan written before any number is taken.

The decision it serves: whether any layout beats records by enough to justify losing the sealed
`switch` over the IR (item 85, "Where the nodes live"), and so whether the ports of rows 214 to 217
should build the IR through factory methods that a later representation could change under them.

## 2. The admission check, done

Checked on 6 October 2026, before any harness was written:

* **The IR builds outside sbt.** `VarkaVectorIR.java` imports only `java.util`, and plain `javac`
  compiles it alone to 45 classes. So the harness needs no Spark build, and it can run on any JDK.
* **The same file builds as value records.** With `value` added to its 37 record declarations it
  compiles on the early-access JDK `27-jep401ea3+1-1` (checksum verified). That is an arm that
  costs one `sed`: the real IR as value records, with nothing redesigned.
* **Nothing depends on a node's identity.** The one `IdentityHashMap` in the Varka code is over
  emitter `Label`s in `VarkaEmitterTestSupport`; no IR node is keyed or compared by identity, so
  value records change no behaviour the IR relies on.
* **A flat row of the width a node needs is not available through public APIs of a value class.**
  The probe table in item 85 (`isFlatArray` on the early-access build): a nullable value record
  flattens at 7 bytes and not at 8, and a 16-byte row flattens only as a null-restricted
  non-atomic array of a `@LooselyConsistentValue` class, through `jdk.internal`. What this would
  have rejected: arm 4 as first written, value-record rows of 16 bytes, which are not flat; it
  becomes the 63-bit packed row, which is, and the internal-API row as a labelled reference.
* **FFM struct rows work on JDK 25 without preview**, with a 16-byte row of opcode, payload and two
  child ids, bounds-checked, off heap or over an `int[]` (item 85).

Still to check, and the first step of the work: that a reflection over the 37 record kinds can
export any graph the compiler builds as a description (kind, payload components, child indices),
which every arm then builds from.

## 3. The design

### 3.1 The graphs, and one description for every arm

The compiler's graphs are Scala-built, so the harness cannot build them itself. An exporter in the
test tree, `VarkaIrLayoutExport`, walks the real IR by reflection over its record components and
writes each graph as a description: one line per node, its kind, its non-child components as the
payload, and its children as earlier lines' indices, shared subtrees written once. Reflection runs
once, outside any timing, and the description file is what every arm reads, so the arms build
exactly the same graphs.

The graphs: the shapes of `VarkaEmitCostCorpus` and the TPC queries through the compiler, and
projections grown to ten thousand nodes by composing the corpus's shapes over distinct inputs, the
size item 85 names as the benchmark's. The small shapes are committed; a ten-thousand-node graph
is regenerated from a seed.

### 3.2 The arms

All in `sql/varka/spikes/ir-layout/`, plain `javac` and `java`, outside the build:

1. **Records.** The real `VarkaVectorIR` built from the description through each record's canonical
   constructor, as the baseline.
2. **`int[]` columns**, jegg issue #92's layout: opcode, payload and child ids as columns indexed
   by node id, with the payload words and a side array for variable arity.
3. **FFM struct rows**, a `StructLayout` row with `static final` `VarHandle`s, in an `Arena` and
   over a heap array through `MemorySegment.ofArray`.
4. **Value-record rows on the early-access JDK**: the 63-bit packed row (opcode and up to three
   child ids in one word, about half a million nodes at most); and, labelled a reference, the
   16-byte row through the internal API, flat only there.
5. **The real IR as value records**, on the early-access JDK, the same file with `value` added.

### 3.3 What is measured, and how

For each arm and graph: build time from the description; bytes a node (records through
`Instrumentation.getObjectSize` over the whole graph, the flat layouts from their own sizes, and
whether an array is flat from `ValueClass.isFlatArray`, never from heap deltas, which were noisy);
structural hash and equality of every node, with hash-consing in the flat arms; one bottom-up
analysis, a `dayRange`-like interval pass over the graph; and the cold first run, a fresh JVM per
sample, at least twenty, with the first call's time, since the memory argument rests on the cold
compile and item 85 says the graphs are small. Warm times are five or more two-second iterations,
pinned as `dev/varka_bench_regen.sh` pins them, on an otherwise idle machine; a claim under 1.3x
is re-run and compared by minimums and read against a band (`sql/varka/AGENTS.md`).

### 3.4 Correctness first

A benchmark of wrong work measures nothing. Before a number is taken, every flat arm must agree
with the records: the number of distinct subtrees, the interval fact of every node, and a round
trip of each graph back to records whose `VarkaVectorIR.canonical` equals the original's.

### 3.5 What is deliberately unchanged

The IR, the compiler, the emitter and every test. No Varka main code is touched; the spike lives
beside the project, so the arms that need the preview JDK break nothing the build builds.

## 4. Files

| file | what |
|---|---|
| `sql/catalyst/src/test/.../VarkaIrLayoutExport.scala` | the exporter: the corpus and the grown projections as descriptions |
| `sql/varka/spikes/ir-layout/` | the harness for the five arms, a run script per JDK, a README |
| `sql/varka/spikes/ir-layout/results/` | each run's output with its provenance, and the bands |
| `m7/VARKA-291.md`, `m7/PLAN.md` | this plan, row 291 |

## 5. Tests, and what each is for

* The agreement check of 3.4 on every graph and every arm: the one that stops a layout winning by
  computing less.
* The exporter's round trip over the 37 kinds: every kind a graph can contain is described and
  rebuilt, so a kind the reflection misses fails the export, not a later arm.
* `isFlatArray` read for every array arm 4 claims is flat, so a layout the VM did not flatten is
  reported as it is.

## 6. The measurement

Arms against the measures of 3.3, on the corpus's shapes and the grown projections, at the two
sizes that matter: under a thousand nodes, where Varka's graphs are, and ten thousand.

### 6.1 Predictions, registered before the run

1. A flat layout is at least twice as small a node as a record, whose header and child references a
   row does not pay.
2. On a graph of a thousand nodes or fewer, every layout's time is within 1.3x of the records',
   either way, because the graphs are small; the gap, if there is one, opens past that and in
   hashing and equality, where ids replace a walk.
3. FFM rows cost more than `int[]` columns only where a `VarHandle` is not folded.
4. The real IR as value records (arm 5) is smaller a node than records by less than half, since
   the child references stay, and within 1.3x in time.
5. *The owner's, added 6 October 2026 before any run.* A flat layout beats records at every size,
   not only past a thousand nodes: from experience, flat arrays almost always win over trees of
   objects on the cache hierarchies of current CPUs. It is scored against prediction 2, which expects
   no gap at a thousand nodes or fewer, and on the warm and the cold measures separately, so that a
   win in steady state which the cold first compile loses is seen as it is.

### 6.2 The gate

A layout is worth losing the sealed `switch` over the IR for only if it beats records by 1.3x or
more on the cold first compile of a representative projection, or by a factor that grows with
depth on hash and equality. A layout that is not flat, or that needs a `jdk.internal` class or the
preview, is a finding about a later JDK and not a candidate. If a layout passes, the next decision
is whose schema the rows are: jegg's, generic over opcode, payload and child ids, with Varka's IR a
client of it, or Varka's.

## 7. Risks

1. **A `VarHandle` that is not folded** makes FFM look slower than it is. The arm's accessors are
   `static final` and a variant with a non-constant handle is run beside it, so the cost is seen
   either way.
2. **The corpus is small.** Item 85 expects Varka's graphs to be small, which is the point; the
   grown projections are synthetic, built from the corpus's own shapes, and are labelled so.
3. **Different hash-consing in each arm.** One algorithm, open addressing over ids, in every flat
   arm, and the records' own `hashCode` and `equals` as they ship, so each arm is measured as it
   would be used.
4. **The early-access JDK is unfinished.** Its results are a reference for a later JDK, never a
   claim about Varka's; any arm that needs it is read that way.
5. **Cold-start samples are noisy.** At least twenty fresh JVMs, minimums and medians both, and the
   band of the records arm read first.

## 8. Sequencing

1. The exporter and its round trip over the 37 kinds, the first check of section 2.
2. Arms 1 and 2, the agreement check, and the harness's run script, on JDK 25.
3. Arm 3, on JDK 25.
4. Arms 4 and 5 on the early-access JDK, with the run script for it.
5. The measurement of section 6 on the quiet machine, the bands first.
6. Section 9, the gate's verdict, and item 85's "Where the nodes live" updated from it.

## 9. Outcome

### 9.1 Step 1, the exporter, 6 October 2026

The last admission check of section 2 is done: a reflection over the IR's records describes every
graph the corpus and the grammar build, and rebuilds it into records equal to the original.

**What the 37 kinds are made of**, read by reflection: every one is a record, and a component is a
child node (45 of them across the kinds, six typed as the `Cond` subtype), one of five enums
(`CompareOp`, `IntOp`, `LaneType`, `Overflow`, `TruncLevel`), an `int` (six), a `long` (four), a
`boolean` (one), or a single `List<Integer>`, `InRanges.bounds`. So a description line needs only
the kind, its scalars as text and its children's ids; `VarkaIrDescription` writes it, rebuilds a
record through its canonical constructor by walking the components once, and holds equal subtrees
as one line.

**The checks.** `VarkaIrDescriptionSuite` round-trips every shape of the corpus and 400 draws of
each of the grammar's int and long shapes and 20 of each wide one, through the graph and through
the text, and requires the union to contain all 37 kinds. The corpus alone reaches all 37, as a
run with the draws removed showed; with nothing reached the test fails and lists the kinds, so the
assertion can fail. Equal subtrees being one node, and a description that names an unknown kind or
a child that does not come before its parent being refused, have a test each.

**What the corpus is, and what it lacks.** `VarkaIrLayoutExport` writes its families, and the
spike's gate asks for sizes it does not reach:

| family | graphs | nodes | largest graph |
|---|---|---|---|
| size ladder | 5 | 3860 | 2002 |
| make_date ladder | 2 | 150 | 123 |
| cheap tails | 2 | 176 | 130 |
| fuzz, int | 2000 | 18680 | 34 |
| fuzz, long | 2000 | 21773 | 26 |
| wide, int | 200 | 63055 | 609 |
| wide, long | 200 | 75872 | 799 |
| past the driver's ceiling | 2 | 10004 | 6002 |
| wide compositions, int | 20 | 16970 | 1057 |
| wide compositions, long | 20 | 20704 | 1240 |
| **grown ladder** (added) | 2 | 11004 | 10002 |
| **deep chain** (added) | 3 | 2339 | 2049 |

The corpus's largest graph is 6002 nodes and every entry in it is a tree four deep, so it has
neither the ten thousand nodes of section 3.1 nor any depth to read a hash's cost against. The
exporter adds two families of its own: the size ladder's entry repeated 200 and 2000 times, about a
thousand and ten thousand nodes, and one output nested 16, 128 and 1024 levels, which is where a
record's hash walks the whole chain on every lookup.

**Next**, step 2: arms 1 and 2 and the agreement check on JDK 25, and the small families committed
beside the harness.

### 9.2 Decisions for step 2, 6 October 2026

Made with the owner, after step 1, before any harness code. Sections 1 to 8 stand as written; this
says what changed and why.

**The order.** The owner judged FFM struct rows the more promising arm, so step 2 builds arms 1
(records) and 3 (FFM struct rows) and `int[]` columns (arm 2) move to step 3, as the plain-array
baseline FFM's cost is read against. Prediction 3, that FFM costs more than `int[]` only where a
`VarHandle` is not folded, is scored at step 3, when the baseline exists.

**Two row layouts for arm 3**, from the 244,587 nodes `VarkaIrLayoutExport` writes. A row is
addressed by id, row `i` at byte `16 * i` or `32 * i`, and a child id of -1 means none.

```
 Layout A: 32 bytes, fixed (the row of eight ints jegg issue #92 proposes)
  byte   0         4         8         12        16                  24                  32
         +---------+---------+---------+---------+-------------------+-------------------+
         |  kind   |   c0    |   c1    |   c2    |        p0         |        p1         |
         +---------+---------+---------+---------+-------------------+-------------------+
          int32     int32     int32     int32     int64               int64

 Layout B: 16 bytes, children in the row, only scalars spill
  byte   0         4         8         12        16
         +---------+---------+---------+---------+
         |  head   |   c0    |   c1    |   c2    |
         +---------+---------+---------+---------+
          int32     int32     int32     int32
```

`kind` is the record kind's index in a fixed table of the 37 kinds. `c0` to `c2` are child ids in
component order, so `IfElse` is condition, then, else and `MakeDate` year, month, day. In A, `p0`
and `p1` hold the scalars in component order, each widened to 64 bits (an enum's ordinal, an `int`,
a `long`, a `boolean`); `BoundedDivide`'s four ints are packed two to a long, and `InRanges` keeps
its list's offset and length in `p0` and `p1`, the only use of a pool in A.

In B, `head` holds the kind in bits 0 to 5 and the node's scalars in bits 6 to 31, or, for the four
kinds whose scalars do not fit, the offset of an entry in a pool of ints, interned so that equal
rows stay equal. The widths come from the enums (`CompareOp` has 5 constants, `IntOp` 3,
`LaneType` 2, `Overflow` 3, `TruncLevel` 3), `MAX_INPUTS` (64) and the largest literal index in the
corpus (1999):

| kind | in `head`, from bit 6 |
|---|---|
| `ColumnRef`, `LiteralSlot` | bit 6 = lane (INT or LONG), bits 7 to 31 = ordinal or literal index |
| `IntArith` | bits 6 to 7 = op (ADD, SUB, MUL), bits 8 to 9 = overflow mode (WRAP, FAIL, NULL) |
| `IntNeg` | bits 6 to 7 = overflow mode |
| `Compare` | bits 6 to 8 = op (LT, LE, GT, GE, EQ) |
| `TruncDate` | bits 6 to 7 = level (YEAR, MONTH, QUARTER) |
| `MakeDate` | bit 6 = `failOnError` |
| 26 other kinds | 0, they have no scalars |
| `BoundedDivide` | pool entry: divisor, bound, multiplier, shift |
| `ConstDivide` | pool entry: divisor and dividend bound, each as a low and a high int |
| `GuardedRange` | pool entry: lo and hi, each as a low and a high int |
| `InRanges` | pool entry: the count, then the bounds |

The pool never holds a child id, so nothing in it changes when an e-graph rewrites children. About
12% of the corpus's nodes spill, and the average is about 18 bytes a node for B, against 32 for A;
a hash-consing table adds about 8 bytes a node while a graph is built. What a record costs is the
experiment's to measure, so whether either layout meets prediction 1 is not yet known. An earlier
draft of B kept the third child of `IfElse` and `MakeDate` in the pool; that is wrong for an
e-graph, whose rebuild rewrites children in place, so all three children are in the row.

**One row format and one builder, not two copies of the graph.** An immutable IR is an e-graph
with no merges, so with a node's id as its class id the IR's rows are already valid e-graph rows,
with nothing converted on the way in. The compiler builds rows once; a shape that does not
saturate keeps them as the shape cache's key, and one that does appends rows, merges classes, and
extracts a compact immutable selection, the only copy, because a saturated graph is too large,
mutable and confined to one thread to be retained and read by many. Whether jegg stays a separate
library or becomes a part of Varka is open, and a change to jegg to adopt such a store is
postponed with it; the accessors are plain functions of a store and a row, with no interface for a
library to plug into.

**Where the e-graph's own state lives:** in the row, only what is the node: kind, scalars and
children, which rebuild rewrites to canonical ids. Everything else is a side array indexed by row
id and allocated per saturation, so a graph that never saturates pays nothing and the retained
selection carries nothing:

| state | where | why |
|---|---|---|
| union-find parent | side `int[]` | `find` runs on every child during matching and rebuild |
| members of a class | side `int[]` `next`, a circular list | merging two classes swaps two pointers |
| parent lists | side pool, 8 bytes a child edge | a node is in the list of each child, 1.59 children a node here |
| analysis facts | side arrays by class id | one array per analysis, allocated if it runs |
| repair marks | a bitset | a bit a row |
| hashcons table | side `int[]`, shared with the IR | the table hash-consing the IR already uses |
| extraction costs and choices | side arrays | transient |

The class-member `next` is the one candidate for the row, if matching turns out to wait on the
cache miss of reading it; that is a layout variant for step 5, not a decision now.

**Measures added.** A rebuild pass, which remaps every row's children through a permutation as
`find` would, rehashes and re-interns, since that is what an e-graph does to a layout and reads do
not show it; and the cost of adopting an IR's rows as singleton classes against extracting a
selection.

### 9.3 Step 2, the harness for records and the FFM rows, 7 October 2026

Built `sql/varka/spikes/ir-layout/`: arm 1 (records), arm 3 in layouts A and B, the agreement check,
the run script, and the small graphs beside it (320 graphs, 11,699 nodes, all 37 kinds). The
`int[]` columns are step 3, as decided in 9.2.

**Agreement.** On the committed graphs and on the full corpus (4,456 graphs, 244,587 nodes)
records, layout A and layout B agree on distinct nodes and interval facts, and both layouts round
trip to equal records. Six edge cases at the packing limits agree too, and B refuses the one index
past its limit.

**Sizes, full corpus.** Layout A takes 32.06 bytes a node and layout B 17.04, with interning. The
hash-consing tables take 12.16 and 13.27 bytes a node, not the 8 that 9.2 estimated: the table
rounds to a power of two, and B has a second table for its pool. Section 9.2's figure of 18 for B
was without interning.

**A bug the bounds check caught.** B's pool was sized for lists only, but B spills every wide
kind's scalars; the FFM segment's bounds check threw. It is sized from the wide scalars now, and so
is the pool's table.

**Not a measurement.** One cold pass in one JVM, from `results/step2-full-corpus.txt`: records
build 28 ms and analyze 91, A build 14 and analyze 8, B build 22 and analyze 8. Step 5 measures on
the quiet machine, with fresh JVMs.

### 9.4 Step 3, the `int[]` columns baseline, 7 October 2026

Added `ColumnRows` (layout C) to the harness: layout A's six fields as `kind`, `c0`, `c1`, `c2`
`int[]` and `p0`, `p1` `long[]` columns, with A's packing, so a node is the same 32 bytes and the
arms differ in where a field lives and how it is read. It is the plain-array baseline the FFM rows
are read against.

**Agreement.** Records and layouts A, B and C agree on the committed graphs and on the full corpus,
and C passes the six edge cases. C's sizes are A's, 32.06 bytes a node with 12.16 for the tables,
but that equality holds by formula: the harness computes bytes a node as rows times the row size
plus the pool, for all three layouts, and does not measure the heap. C's real footprint also has six
array headers a graph and arrays sized before deduplication.

**One departure from the plan.** C shares the off-heap pool of lists with the FFM arms. Only
`InRanges` has a list, so a heap pool would change one kind in 37; it is noted here so that step 5
does not read C as free of `MemorySegment` altogether.

**Not a measurement.** One cold pass, from `results/step3-full-corpus.txt`: records build 31 ms and
analyze 91, A build 14 and analyze 8, B build 21 and analyze 8, C build 6 and analyze 5. The order
confounds them: each graph is built as records, then A, then B, then C in one JVM, so C runs on code
the others have already warmed (the hash, the pool, the interval maths) and the JIT has seen it.
Prediction 3, that FFM costs more than `int[]` only where a `VarHandle` is not folded, is therefore
not scored here; it moves to step 5, which runs each arm in fresh JVMs.

### 9.5 Step 4, the early-access JDK arms, 7 October 2026

Added `run-ea.sh` (variants `plain` and `value`, JDK from `EA_JAVA_HOME`), `src-ea/` and a
`--layouts` option, so that an arm runs alone in its own JVM, as step 5 needs. The build is
`27-jep401ea3+1-1`. `plain` is the control: the same sources on the same JDK with ordinary records.

**Arm 5, the real IR as value records.** `VarkaVectorIR.java` compiles unchanged but for `value`
on its 37 records, which `run-ea.sh` generates and nothing commits. Nothing in the harness or the
IR depends on node identity (no identity maps, no `synchronized`, no `==` on nodes), and the
harness asserts that every node class is a value class. On the committed graphs, and on the full
corpus with escape analysis off, the value records, A, B, C, V16 and V63 agree.

**C2 crashes on value records.** With default options, the value variant on the full corpus
crashes the JVM in C2 (`SIGSEGV` in `ConnectionGraph::optimize_ideal_graph`), every time, even
with `--layouts none`, which runs the records alone. The method being compiled is a
`LambdaForm$MH::invoke` in all of the crash logs; the harness reaches those through reflection
(the description code builds and reads records that way), which is a hypothesis, not a finding.
`-XX:-DoEscapeAnalysis` and `-XX:TieredStopAtLevel=1` both avoid it, and the committed graphs, too
small to compile much, run clean. The plain variant on the same JDK is unaffected. The evidence is
`results/step4-ea-value-crash.txt`; the passing runs are `results/step4-ea-value-*.txt`.

**What this means for step 5.** Value types lean on escape analysis to be scalarized, so a value
arm measured with it off would understate them. Step 5 therefore measures arm 5 with escape
analysis on and the reflective agreement check out of that JVM (a `--no-check` mode, to add
there), and runs the check in a separate JVM with it off. If the arm still crashes, that is the
result for this build and the verdict says so.

**Arm 4 as built, and what changed from 3.2.**
- *V16* is layout B's row and packing in a null-restricted, non-atomic array of a
  `@LooselyConsistentValue` record, flat only through the internal API (the probe in item 85).
- *V63* is one `long` a node, 6 bits of kind and three 19-bit child ids stored plus one, in a
  null-restricted atomic array, which is flat at 8 bytes through the same internal API. The word
  has no room for scalars, so they go in a parallel `int[]`, holding what B has in the upper bits
  of its head, so a node is 8 + 4 bytes and the arm differs from B only in its container. It
  refuses a graph of more than 524,286 nodes; the largest in the corpus has 10,002, and no test
  builds one past the limit.
- The public-API alternative, a nullable 7-byte row, does flatten (the probe), but 16-bit ids cap
  a graph at 65,535 nodes, and it was not built.

**Sizes, full corpus, computed by the same formula as in 9.4, not measured.** V16 takes 17.04 bytes
a node, B's size, and V63 takes 13.04. Their tables are B's, 13.27 bytes a node.

**Still open for step 5.** The bytes of arm 1 and arm 5 from `Instrumentation.getObjectSize`
(section 3.3) are not taken here; the nodes' children are interface-typed fields, which stay
pointers, so the heap footprint of the value records is a measurement, not a formula.

### 9.6 Step 5, the measurement, 7 October 2026

Run from `bench.sh` at commit `9d823364030` plus the identity-memo arm, on the laptop at the
performance profile, pinned to the fastest cores (`taskset -c 0,12,13,14,15,1,2,3`), JDK
25.0.4.1 and the early-access build `27-jep401ea3+1-1`. The load average was 0.49 when the chain
started and about 1.0 for the later runs, which is the run itself; nothing else was running. The
files are `results/step5-*.txt`, and `results/step5-summary.txt` is `summarize.py`'s reading of
them. Six graphs: 46, 228, 502, 1,002 and 2,049 nodes (the last a chain 1,024 deep) and the
10,002-node ladder. Cold is a fresh JVM for each of 20 samples, a median; warm is five two-second
iterations after three of warm-up, a minimum, whose spread was at most 1.10 in every row. No run
failed. The value variant ran with escape analysis on, as 9.5 asked.

**The records baseline decides the cold result, so there are two.** `RecordsArm.analyze` memoizes by
the records' structural `hashCode`, which walks the whole subtree on every lookup, and the real
`VarkaRangeAnalysis` has no memo at all, so that arm is a stand-in and not the compiler's pass. A
second arm, `records-id`, memoizes by node identity. Both are below; the second is the fairer
reading, and no number is quoted from one without saying which.

**Bytes a node.** Records are about 24 on every graph over 500 nodes (22.95 to 27.65 on the smaller
ones), measured with `Instrumentation.getObjectSize`. Layouts A and C are 32.00, so larger than
records; B and V16 are 16.00; V63 is 12.00, exactly half. The value records are 32.00, larger than
plain records by a third. The flat layouts' sizes are computed, as in 9.4.

**Warm.**
- *analyze.* Against structural-memo records the flat layouts are 3.7 to 7 times faster up to 1,002
  nodes and 2191.41 us against 8.88 us on the chain; against `records-id` they are 2.8 to 3.7
  times faster up to 2,049 nodes and 6 to 7 times on the ladder. A, B and C are within 10% of each
  other, so FFM costs nothing warm: the `VarHandle`s fold.
- *build.* A and C are 0.71 to 1.01 of records. B and the two layouts that share its packing, V16
  and V63, are 4.75 to 6.9 times slower than records; the cause is not isolated (the packing, the
  pool, or the `int[]` the harness allocates for each node with a wide scalar), so B's build time
  is a finding about this implementation of B, not about 16-byte rows.
- *intern* (hash and equality of every node). Records walk the subtree: 4947.98 us on the chain
  against 17.05 us for A, a factor that grows with depth; on the 10,002-node ladder A and C are
  1.0 and 0.75 of records. The value records are 0.66 to 0.79 of plain records here and no
  different on analyze.

**Cold, build and analyze together, against `records-id`.**
- *C (plain columns):* 1.31, 2.00, 1.15, 1.01, 0.86 and 0.74 of records from the smallest graph to
  the ladder: slower on the small ones, equal at 1,002 nodes, 1.16 and 1.35 times faster on the
  chain and the ladder.
- *A and B (FFM):* 2.4 to 3.2 times slower at every size. The cost is in the build (7 to 8 ms on a
  46-node graph against 2 for records), the first use of the FFM `VarHandle`s and the arena,
  which the warm numbers do not see.
- *V16, V63 and the value records* are compared with the plain records on their own JDK, in the
  summary: V16 and V63 are 0.3 to 0.6 of structural-memo records up to 2,049 nodes and 0.74 and
  1.20 on the ladder; the value records are 0.97 to 1.05.
- One unexplained cold number: V63's analyze on the ladder is about 19.6 ms in all 20 samples,
  against 3.1 for V16, and warm they are equal; the cause is not established.

**Predictions.**
1. *Flat at least twice as small a node as records.* Refuted for A and C (larger), B and V16 (a
   third smaller); only V63 reaches it, and it needs the internal API.
2. *Within 1.3x of records at a thousand nodes or fewer.* Refuted: warm analyze is 2.8 to 7 times
   faster, and cold the FFM layouts are 2.4 to 3.2 times slower.
3. *FFM costs more than `int[]` only where the `VarHandle` is not folded.* Supported: A and C are
   within 10% warm and A is 2 to 3 times C cold, before the JIT has folded anything.
4. *Value records smaller than records by less than half, within 1.3x in time.* Refuted on size
   (a third larger); supported on time, with a 25 to 35% win on intern.
5. *The owner's: a flat layout beats records at every size.* Supported warm for analyze and
   intern at every size, and for build with A and C. Not supported cold, where against
   `records-id` C ties on small graphs and the FFM layouts lose; it holds cold only against the
   structural-memo records.

**Against the gate of section 6.2.** The cold leg (1.3 times faster on the first compile of a
representative projection) is met by none of the JDK 25 layouts against `records-id`: C reaches
1.35 only on the ladder and 1.16 on the chain, A and B lose. The depth leg (a factor that grows
with depth on hash and equality) is met by every flat layout against records' structural
`hashCode` and `equals`, which matters only where the compiler hashes or compares deep nodes, and
the range analysis does not. V16, V63 and the value records need the internal API or the preview,
so by 6.2 they are findings about a later JDK, not candidates.

**Threats to read it by.** One machine, one JDK build of each kind; the stand-in analysis, not
`VarkaRangeAnalysis`; the cold samples are 20; the C2 crash of 9.5 means the value arm is the
only one measured on a build that fails under other conditions; B's build time is partly the
harness; and the graphs are Varka's corpus, whose cold compile is a few milliseconds for the
records, small next to a Spark task.

### 9.7 Step 5, continued: more backends, warm-up, Arrow, and the direction, 7 October 2026

Raised by the owner after 9.6 and measured the same day with the same pinned setup. New files under
`results/`: `step5-coldprewarm-*`, `step5-warmrebuild-*`, `step5-alloc-*`, `step5-coldwarmup-*`,
`step5-vector-loops.txt`, `step5-arrow-spike.txt`, and the files for layout D; `step5-summary.txt`
has the tables.

**New measures.** The rebuild is what an e-graph does after a merge: remap every row's children,
rehash, re-intern (the harness checks it against a build of the substituted graph, on 178 real
substitutions). Prewarmed cold pays the one-time costs first. Alloc is heap bytes and GC over
3,000 builds. Layout D is layout A's fields as one off-heap segment for each field.

**Rebuild, warm.** A, B and C are 0.45 to 0.67 of the records' reconstruction (a lower bound:
records are not deduplicated), and within 10% of each other. V16 and V63 are no better than records.

**Allocation.** At 1,002 nodes a build allocates 13,408 bytes of heap in A, 45,496 in C and 43,352
in records; at 10,002 nodes 179,488, 499,576 and 432,152, with no garbage collection over 3,000
builds in A, one in C and two in records. B allocates 590,560 bytes at 1,002 nodes and 5,940,640
at 10,002 (21 collections), and V16 and V63 606,552 to 698,752 and about 6.1 million (41
collections): the per-node scratch arrays of this harness, which is why B's build was slow. D
allocates what A does.

**Cold, with the one-time costs paid first.** A 10-node warm-up graph removes the class
initialization (A on the 46-node graph falls from 8,156 us to 337 us) but not the interpreted phase:
against identity-memo records C is 0.13, 0.27, 0.35 and 0.30 of their time at 46, 1,002, 2,049 and
10,002 nodes, A is 1.11, 2.53, 2.95 and 2.26. How much warm-up the first compile needs, from
rounds of every measure on a 502-node graph (JDK 25, median of 10, ratio over `records-id`, at
1,002 and 10,002 nodes):

- 1 round: C 0.58 and 0.47, A 1.09 and 1.35, D 0.99 and 1.29.
- 30 rounds: C 0.95 and 1.09, A 1.21 and 1.49, D 1.29 and 1.51.
- 300 rounds: C 1.24 and 0.78, A 1.66 and 1.50, D 1.02 and 1.44.
- 3,000 rounds: A 0.49 and 0.56, C 0.57 and 0.73, D 0.61 and 0.78.

So the cold penalty of the FFM rows is a warm-up cost, not a property of the layout: with a thorough
start-up warm-up all three flat layouts beat the records, A by the most. Between a little and a
lot of warm-up there is a valley where A and D are 1.2 to 1.7 times slower than records; C is not
in it after the first round. This replaces the cold verdict of 9.6 for the case of a warmed JVM.

**Layout D (off-heap columns) is slower than A and C warm.** At 1,002 nodes build is 12.86 us
against 9.51 (A) and 9.33 (C), analyze 6.49 against 4.47 and 4.51, rebuild 18.46 against 6.69 and
6.44; at 10,002 nodes 132.75, 65.03 and 113.33 against 97.61, 44.03 and 67.32 for A. Six segments
cost more per access than one segment or an array, and D's rebuild allocates six arena segments.
Its sizes and heap allocation match A. The cause of the access cost is not isolated.

**Which layouts the JVM vectorizes** (`step5-vector-loops.txt`, a per-row hash of four int fields
over 65,536 rows, ns a row, C2's auto-vectorizer on, then off): heap columns 0.150 and 0.799,
off-heap columns 0.141 and 1.018, off-heap rows 32 bytes apart 0.837 and 0.837. Unit-stride columns
are vectorized on JDK 25 in the heap and off it, by 5 to 7 times; rows are not, in either place. The
Vector API adds nothing over the auto-vectorizer for heap columns (0.175) and about 12% for off-heap
columns (0.124). The deciding factor is rows against columns, not heap against off-heap. Most of the
compiler's passes are dependency chains that no vectorizer can touch; the candidates are hashing
every row, the rebuild's remap, and table clears.

**Arrow** (`step5-arrow-spike.txt`, layout D's six columns as an Arrow 19 batch; netty 4.1 is needed
ahead of a Spark 4.2 distribution's 4.2, whose empty buffer has no address). The IPC stream is
2,352 bytes for the 46-node graph (1,472 raw), 33,664 for 1,002 nodes (32,064) and 328,432 for
10,002 nodes (320,064); writing it takes 3,094 to 45,014 ns and reading it 2,188 to 8,062 ns.
Creating a batch costs 459,370 ns the first time and 1,871 to 12,265 ns warm, against 138 to 1,395
ns for an arena. Reading through Arrow's `IntVector.get` is about 30 times slower than raw reads
(5,321 ns against 171 for 1,002 rows); the raw buffer wrapped as a segment is as fast as an arena.
Arrow suits the wire and the native boundary; as the working store it needs raw-buffer access, at
which point it is layout D on Arrow-owned memory.

**The direction, from the owner, 7 October 2026.**
1. *Layout C, heap columns, is the primary store; layout A is the alternative.* They share their
   fields and packing, so the choice is reversible.
2. *Keep A open without an abstraction that costs anything:* one final class owns the columns and
   every read of a node goes through its methods; nothing outside it touches a column. Swapping to
   A is then a rewrite of that class's body with the same signatures, with no interface, no
   runtime selection and no bimorphic call. Define the schema once, next to the class.
3. *Do not build now* generated accessors, a configuration switch or two implementations in main;
   they are worth it only if A is adopted. The harness keeps A and D alive for the differential
   check.
4. *Arrow is the wire and the native-boundary format*, not the working store. A wire format must
   not depend on the in-memory layout, and needs stable kind ids and a schema version, since the
   kind index in this harness comes from sorted names.
5. *Native offload* (a Rust or C analysis called by downcall over the columns) was discussed and
   not measured: the break-even size, below which a downcall costs more than the Java pass, is the
   open question.

**What 9.6's gate reads as now.** The cold leg is met by C against any warm-up, and by A and D only
after a thorough one; the warm legs (analyze 2.8 to 7 times faster, rebuild about twice, the depth
factor on structural hashing) hold for A, B and C alike. The remaining decision is the migration
of the IR from records to the owning class, which is the work of item 85.

### 9.8 Step 6, the verdict, 7 October 2026

**By the gate of 6.2.** Of the layouts that need nothing beyond JDK 25, plain `int[]` columns (C)
pass the cold leg, a 1.3 times win on the first compile, at every warm-up tried, and A, B and D
pass it after a thorough warm-up (9.7). The depth leg, a factor that grows with depth on hash and
equality, holds against records' structural `hashCode` and `equals` for every flat layout. Warm,
analysis is 2.8 to 7 times faster than identity-memo records and the rebuild about twice as fast.
The V layouts and the value records need the internal API or the preview and are findings about a
later JDK, not candidates. The sealed `switch` is what is given up.

**What is decided.** Layout C is the store, behind one final class that owns the columns; layout A
is the alternative, reached by rewriting that class's body (9.7, the owner's direction). Arrow is
the wire and the native-boundary format and not the working store. Layout B is not a candidate:
its slow build is mostly this harness's allocation (9.7), but nothing measured favours it.

**What is not decided, and is the owner's.**
1. *Whose schema the rows are*: jegg's, generic over opcode, payload and child ids, or Varka's.
   Nothing measured separates them: the rows measured are Varka's (a kind, three children, packed
   scalars), which is already an e-graph row. Not filed as a jegg issue, as asked.
2. *Whether rows 214 to 216 build nodes through factory methods.* It costs the ports nothing and
   keeps the representation free to change, since a change under about seventy `new` expressions
   would otherwise be written twice. Recommended; rows 214 to 216 stand as written until it is
   decided.
3. *Native offload* is unmeasured; the break-even size is the open question, and no spike is
   queued.

**What this is not.** The stand-in analysis is not `VarkaRangeAnalysis`, which has no memo; the
migration of the IR from records to the owning class is item 85's work in milestone 8 and is not
started or scheduled here, and no row is added for it.

### 9.9 The experiment's code is removed, 7 October 2026

At the owner's request the code left the tree once the verdict was written: the harness and its
graphs and scripts under `sql/varka/spikes/ir-layout/`, and the exporter, `VarkaIrDescription` and
its suite in `sql/catalyst/src/test`. They are in the history, and the last commit that has them
all is `f77e5fb9ea6` (#654); the README beside `results/` gives the `git checkout` that restores
them. `results/` stays, since every figure above traces to it and `dev/varka_quote_check.py`
reads it. Sections 3, 4 and 9.1 to 9.8 describe files as they were and are left as written.

