# VARKA-85: lane type as a parameter

*Milestone 5, section 2.16. Opened 8 September 2026 from VARKA-63's review; planned
15 September 2026 as the second task on the milestone's spine, after 84 and
before 29.*

## 1. Where this came from

`VarkaLoopEmitter.java` is 5 924 lines and carries 204 references to its
`INT_VECTOR` class descriptor (section 2.16's count; `grep -c INT_VECTOR`), plus
the descriptor's own definition. The IR has a slot for a lane type - `enum
LaneType { INT }` with a default `laneType()` returning it - and one value in the
slot; it is read exactly once in the emitter, in the analysis pass's node walk
(`grep -n 'laneType()'` finds the one site), to throw on anything else. `ColumnRef(int
ordinal)` names a column and nothing more, and the emitter supplies int32 by
assumption at every load, store, broadcast, arithmetic op, comparison, blend and
address computation. That held while every node was int32; milestone 5's subject
is a second lane, and 28, 29, 88 and every `TIME` row sit behind this task.

Section 2.16 also asked for a forcing function before the refactor: three logical
types over one physical lane, to show the lane belongs to the node's physical
representation and not its Spark type. VARKA-67 delivered it - year-month intervals
are int32 months, the same lane as `DATE` and `INT` - so this task starts with its
example in hand and does not have to argue the point.

## 2. The admission check, done

**Where the 205 references actually are** - counted per method with a
brace-tracking scan, because the answer decides the design:

| region | refs | per-lane? |
| :--- | ---: | :--- |
| calendar kernels: `emitAddMonths` 20, `emitDaysFromCivil` 19, `emitChronoPrefix` 18, `emitMakeDate` 14, `emitFloorMod7` 13, `emitChronoLastDay` 10, `emitJulianYearOfEra` 6, `emitTruncQuarter` 6, `emitChronoYear`/`emitJanuaryDayOfYear`/`emitChronoTrunc` 5 each, `emitLeapFlag`/`emitEra`/`emitChronoMonth` 4 each, `emitFold`/`emitCarry` 3 each, and the small ones | ~140 | **no** - they consume an epoch-day lane, which is int32 by definition; at another lane they must not run, and a conversion node (VARKA-28) is how a wider value reaches them |
| class-level descriptors (`INT_VECTOR`, the `FROM`/`INTO_MEMORY_SEGMENT` and `LANEWISE_*` and `BROADCAST` method types) | 12 | **yes** - these *are* the lane |
| `emitValue`'s `ColumnRef` load and `LiteralSlot` broadcast, `emitIntArith`, `emitIntNeg`, `emitPick`, `IfElse`'s blend | ~24 | **yes** - the generic node arms |
| `emitBody`, `emitLaneGroup`, `emitRangeGuard`, the species prologue (`speciesField`, `PREFERRED_LANES`, `emitLanes`), and the two byte-offset shifts - `cb.lshl()` after an `i2l` in `emitBody` and in `emitValidityWrite` | ~10 | **yes** - species, lane count and stride. (A third `LSHL 2` in the days-from-civil arithmetic, `emitShift(cb, "LSHL", 2)`, is a lanewise multiply by four inside a calendar kernel, not a stride, and stays under the int assertion.) |

So 2.16's estimate holds and is now a list: about forty sites carry per-lane
behaviour, and the rest must instead *assert* the int lane, which they cannot do
today because nothing tells them which lane they are in.

**The Vector API surface the emitter uses**, from every `invokevirtual` on the
int vector: `add` 58, `sub` 39, `compare` 31, `mul` 23, `broadcast` 8, `blend` 8,
`lanewise` 6, `min` 4, `and` 4, `intoMemorySegment` 2, `fromMemorySegment` 2, `or`,
`max`, `div` once each. Every one exists on `LongVector` with the same name and the
same shape, differing only in the receiver class and the scalar argument type -
which is what makes a descriptor, rather than a second emitter, the right unit.

**Two facts outside the emitter that a lane parameter meets.** The kernel
interface is `int run(long[] srcData, long[] srcValidity, int[] srcNullCount,
long[] dstData, long[] dstValidity, int[] scalarArgs, int length)` - literals
cross into the kernel as `int[]`, read with `iaload`, and a long lane's literal
does not fit. And `VarkaShapeKey` is the record `(outputs, numInputs,
numLiterals, options)`, so a lane carried as a component of the IR nodes reaches
the cache key by record equality, and one carried anywhere else does not.

**One fact about the width knob.** `lanesOverride` is a lane *count*: `emitLanes`
accepts 2, 4, 8 or 16 and `speciesField` names `SPECIES_` followed by the count
times `Integer.SIZE`. The emitter suite's width matrix loops that count. At a long
lane the same count is twice the bits, so the knob's meaning has to be decided
before any loop is written; section 3.1 decides it.

**Two things this task cannot exercise, found by reading what the tools do.**
`dev/varka_emit.sh` goes from SQL text through the compiler to IR, and
`VarkaEmitDump` parses `date`, `int`, `short`, `byte` and the year-month interval
as column types and nothing else; since this task admits no `LongType` column in
the compiler, no long shape can reach the tool, and neither its verdicts nor its
`--table` op counts can be taken for the proof lane here. Likewise the evaluator:
no SQL shape reaches it at a long lane until 104's compiler arms exist, so
evaluator code written in this task would be code no test reaches. The first
draft of this plan asked for both; sections 3.1 and 6 now say where they go.

**Found by review before implementation, 15 September 2026.** Besides the two
above: the proof subset listed a checked multiply, which the emitter has no arm
for at any lane - the compiler declines it and `emitIntArith` throws if one
arrives, because the int lane has no 64-bit product to test, and a long lane
would need a 128-bit one (VARKA-104's problem under the lattice); the oracle said
"pinned against today's emitter" without a place to pin, and named the bench
module's inventory, which is not on catalyst's test classpath; the benchmark
baseline was placed in an existing benchmark's rows against the house rule that a
new feature family gets its own class and files; the shape key's handling of a
second literal table was left implicit; prediction 4 named an instrument
(`VarkaCompilationWatch`) that records re-emission counts, not emission time;
and the docs anchor named a section that does not exist. Each is folded in
below, and this paragraph is the record that they were missing.

**The measurement 2.16 asked for is settled by construction, and the plan says
so rather than running it.** The option space weighed (a) a descriptor against (b)
a generated per-lane emitter on the risk of "a megamorphic descriptor call in the
hot path". The descriptor is consulted when the class is *emitted*, in Java, and
the bytes it emits name `IntVector` or `LongVector` constants directly; the
emitted loop contains no descriptor and no dispatch on lane. There is no hot-path
cost to measure. What the choice can affect is emission time, about 80 us per
shape today and amortised by the shape cache, and the int32 bytes - and those are
the admission check: the int32 lane's emitted method bodies must be
**byte-identical** before and after, which is a stronger statement than any
timing and is checked mechanically.

## 3. The design

### 3.1 The mechanism

**The lane is a component of the leaf nodes, and derived everywhere else.**
`ColumnRef(int ordinal, LaneType lane)` and `LiteralSlot(int index, LaneType
lane)`; every other node's `laneType()` is a function of its children - `IntArith`,
`IntNeg`, `Greatest`, `Least` and `IfElse` take their operands' lane and refuse
mixed operands, `Compare` and the other `Cond` nodes answer the lane they compare
in, and every `Chrono` node and `AddDays`/`SubDays`/`DateDiff`/`MakeDate`/`GuardedDay`
answers `INT` and refuses a child that is not - the calendar consumes days. The
refusal is in the record constructors, so a malformed tree cannot be built, which
is the cheapest place to stop it. `LaneType` gains `LONG` in this task, for the
proof lane below; `BOOLEAN` and the rest wait for their tasks. Because the lane is
a record component, `VarkaShapeKey` distinguishes two shapes that differ only in
lane without a change of its own, and a test asserts that.

**The descriptor.** A Java enum `Lane` in the emitter with one member per
`LaneType`, carrying everything the forty sites read: the vector `ClassDesc`,
`speciesField(lanes)` (`SPECIES_` followed by lanes times the lane's bit width),
the bit width and the byte-offset shift (2 for int, 3 for long), the preferred
and permitted lane counts (int: 2, 4, 8, 16; long: 1, 2, 4, 8 - both are
`SPECIES_64` through `SPECIES_512`), the `MethodTypeDesc`s for load, store,
broadcast, lanewise and pick built once per member, the array-load opcode for a
scalar argument (`iaload` from `int[]` or `laload` from `long[]`), and a field
for the width-dependent lowerings later tasks fill - 88's division form per
`UseAVX`, left at its int value here. Table 3.4 gives every member's value for
both lanes, so the enum is filled from a table rather than derived. The forty
sites read the descriptor; the ~140 calendar sites call `Lane.INT.require(node)`
on entry and are otherwise untouched.

**The width knob, decided.** `lanesOverride` stays a lane count, as today, and
each `Lane` member validates it against its own permitted list and multiplies by
its own bit width in `speciesField`. So `lanesOverride = 2` is 64 bits at the int
lane and 128 at the long lane, and a test that wants "128 and 512 bits at the
long lane" loops the long member's counts 2 and 8, read from the descriptor, not
a literal list. The alternative - the knob in bits - would change the meaning of
every existing `lanesOverride=` in the suites and the emit options' history, and
fails the "int32 unchanged" rule for no gain.

**Literals for a long lane, without moving an int32 byte.** The kernel interface
keeps `int[] scalarArgs` exactly as it is for `INT` shapes, so their `run`
descriptor and body do not change. A shape whose lane is `LONG` is emitted against
a second interface method, `run(..., int[] scalarArgs, long[] longArgs, int
length)`; the int `run` on such a class throws with the lane named. Two slot
tables, one per lane. `VarkaShapeKey` does not change: `numLiterals` stays the
int table's size, and the long table's size is not a key component because it is
derivable from the IR the key already holds - one past the largest `LONG`
`LiteralSlot` index - and the emitter derives it that way. The alternative -
widening `scalarArgs` to `long[]` for everyone - changes every int32 `run`
descriptor and every `iaload` to `laload`/`l2i`, and fails the admission check on
purpose-built grounds. Which caller passes `longArgs` is 104's question: in this
task the only callers are the emitter suite and the fuzzer, which build the array
themselves.

**The proof lane, and where 85 stops.** 2.16's admission check needs "a second
lane type reaching the same green differential and fuzz matrices"; a descriptor
with one member proves nothing. So this task ships `LONG` for the lane-generic
subset only, as an explicit set:

| node type at `LONG` | operations | excluded, and whose it is |
| :--- | :--- | :--- |
| `ColumnRef`, `LiteralSlot` | load, store, broadcast | - |
| `IntArith` | `ADD`, `SUB`, `MUL` at `WRAP`; `ADD`, `SUB` at `FAIL` and `NULL` (the sign test is lane-generic) | `MUL` at `FAIL` or `NULL`: no arm exists at any lane; the compiler declines it and `emitIntArith` throws; a long lane needs a 128-bit product - VARKA-104 |
| `IntNeg` | all three modes | - |
| `Compare`, `And`, `Or`, `Not`, `IsNotNull` | as at int | - |
| `IfElse`, `Greatest`, `Least` | as at int | - |
| every `Chrono` node, `AddDays`, `SubDays`, `DateDiff`, `NextDay`, `ThursdayOf`, `AddMonths`, `MakeDate`, `GuardedDay`, `DayOfWeek`, `WeekDay`, `DayOfWeekIso` | none | `INT` only, refused in the constructor; a wider day reaches them through 28's conversion |

That is the arithmetic-and-comparison core of VARKA-104, at the long member's 2
and 8 lane counts, in the emitter's own parity matrix built from IR and in the
fuzzer, both of which drive kernels directly through memory segments. What it
does *not* ship is 29's and 104's: the `TIME`, day-time interval and timestamp
semantics, the division lowerings, the compiler arms that admit a `LongType`
column from SQL, and *any* evaluator change - two scalar tables, the `run`
chosen by lane, `BigIntVector` in and out were in this plan's first draft and
are moved to 104, because nothing in this task can reach them. The SQL-level
differential at the long lane therefore arrives with 104, and this plan says so
rather than claiming it.

**The reachability test.** `VarkaIrFuzzSuite` asserts today that its generator can
build every node type in the sealed hierarchy. It becomes node type times lane
type, with a compatibility table the generator obeys (calendar nodes at `INT`
only) - so a later lane arriving without an arm fails here first, in seconds. It
needs the reference evaluator to answer at `long` for the proof subset, which is
part of VARKA-119; **that part of 119 lands with this task**, and the milestone
plan's graph is corrected to say so.

### 3.2 What is deliberately unchanged

Every int32 emitted method body, byte for byte - the oracle. Every calendar
kernel's code. The `run` signature for int shapes. The validity-word machinery,
including its `lanes < 8` behaviour (VARKA-92 lands beside 29, when four-lane
vectors exist in production shapes). The emit options and the shape key's own
fields. The compiler's admission rules: no SQL shape fuses or declines differently
after this task, which the compiler suite, the coverage suite and the differential
enforce.

### 3.3 Registered op counts

No int32 shape changes an op count; the byte-identity check subsumes the table.
The proof lane's op counts are not registered here: `dev/varka_emit.sh` reaches
the emitter through the compiler, which admits no `bigint` column in this task,
so the table would have to be produced by a path the tool does not have. They
are registered by 104, whose first commit is the baseline the house rule asks
for.

### 3.4 The descriptor, as a table

Every value the `Lane` enum carries, for both members, read from the emitter's
class-level descriptors on master. `V` is the member's vector class descriptor.
Descriptors not listed (`FROM_LONG`, `TO_LONG`, `MASK_BINARY`, `MASK_UNARY`,
`ANY_TRUE`, `SPECIES_LENGTH`, and everything over `VECTOR_MASK` and
`VECTOR_SPECIES`) do not name the lane and stay class-level as they are.

| member field | `INT` (today's value) | `LONG` |
| :--- | :--- | :--- |
| vector class `V` | `jdk.incubator.vector.IntVector` | `jdk.incubator.vector.LongVector` |
| bit width | 32 | 64 |
| byte-offset shift | 2 | 3 |
| permitted lane counts | 2, 4, 8, 16 | 1, 2, 4, 8 |
| preferred lanes | `IntVector.SPECIES_PREFERRED.length()` | `LongVector.SPECIES_PREFERRED.length()` |
| `speciesField(n)` | `SPECIES_` + 32n, `SPECIES_PREFERRED` for 0 | `SPECIES_` + 64n, same |
| scalar array class, load opcode | `int[]`, `iaload` | `long[]`, `laload` |
| scalar constant type in descriptors | `CD_int` | `CD_long` |
| `BROADCAST` | `(VECTOR_SPECIES, int) V` | `(VECTOR_SPECIES, long) V` |
| `FROM_MEMORY_SEGMENT_DENSE` | `(VECTOR_SPECIES, MEMORY_SEGMENT, long, BYTE_ORDER) V` | same shape, `V` = `LongVector` |
| `FROM_MEMORY_SEGMENT_MASKED` | as above plus `VECTOR_MASK` | same shape |
| `INTO_MEMORY_SEGMENT_DENSE` / `_MASKED` | `(MEMORY_SEGMENT, long, BYTE_ORDER[, VECTOR_MASK]) void`, receiver `V` | same descriptor, receiver `LongVector` |
| `LANEWISE_VV` | `(VECTOR) V` | `(VECTOR) V` |
| `LANEWISE_VV_WRONG` | `(IntVector) IntVector` | `(LongVector) LongVector` |
| `LANEWISE_VI` | `(int) V` | `(long) V` |
| `LANEWISE_VI_MASKED` | `(int, VECTOR_MASK) V` | `(long, VECTOR_MASK) V` |
| `LANEWISE_BINARY_V` | `(VO_BINARY, VECTOR) V` | same shape |
| `LANEWISE_BINARY_I` | `(VO_BINARY, int) V` | `(VO_BINARY, long) V` |
| `COMPARE_VV` | `(VO_COMPARISON, VECTOR) VECTOR_MASK` | same |
| `COMPARE_VI` | `(VO_COMPARISON, int) VECTOR_MASK` | `(VO_COMPARISON, long) VECTOR_MASK` |
| `BLEND` | `(VECTOR, VECTOR_MASK) V` | same shape |
| `run` method | the existing `RUN` descriptor | `RUN` with `long[]` inserted before `int length` |
| 88's division form | unused at int | left empty here; 88 fills it |

The `broadcast` risk in section 7 is visible in this table: `IntVector` also has
`broadcast(VectorSpecies, long)`, so the int member must keep `CD_int` exactly.

## 4. Files

* `codegen/varka/VarkaVectorIR.java` - `LaneType.LONG`; the lane component on
  `ColumnRef` and `LiteralSlot`; `laneType()` derived per node, refusing mixed or
  wrong-lane children in the constructors.
* `codegen/varka/VarkaLoopEmitter.java` - the `Lane` enum; the ~40 generic sites
  read it; the ~140 calendar sites assert `INT`; the second `run` for `LONG`.
* `codegen/varka/VarkaFusedKernel.java` - the `longArgs` overload, with a
  default body for the int `run` on a long class that throws with the lane named.
* `codegen/varka/VarkaShapeKey.java` - no change; a test that lane reaches it.
* `sql/core/.../VarkaKernelEvaluator.scala` - **no change** (moved to 104).
* `VarkaExpressionCompiler.scala` - constructs leaves with `LaneType.INT`; admits
  nothing new.
* `sql/varka/emitted_bytes.json` - new, the oracle's committed digests (section
  5), regenerated only with `VARKA_BYTES_REGEN=true` on `coverage.json`'s
  precedent.
* Tests: `VarkaEmittedBytesSuite` (new, the oracle - see section 5);
  `VarkaLoopEmitterSuite` with the proof lane's parity cases at the long
  member's lane counts 2 and 8; `VarkaIrFuzzSuite`'s reachability at node x lane
  and its grammar over the proof subset at `LONG`; `VarkaReferenceEvaluator`
  gains `evalLong(node, row: Seq[Option[Long]], lits: Array[Long]): Option[Long]`
  for that subset (119's first part), beside `evalValue`, which stays as it is;
  `VarkaShapeCacheSuite`'s lane-in-key case.
* `docs/sql-varka.md` - the "Architecture" section's emitter paragraphs name the
  lane descriptor; the "Glossary" gains *lane type*.
* `m5/PLAN.md` - row 85; 119's first part moved beside 85 in 3.1; the
  evaluator's long-lane work and the proof lane's baseline benchmark named in
  row 104 as its first commits.

## 5. Tests, and what each is for

* **`VarkaEmittedBytesSuite` - the oracle, written first.** Two shape sets: every
  row of the coverage table, which `VarkaCoverageSuite` already compiles on
  catalyst's test classpath (the bench module's `Surface` and `Chains` entries are
  Java in another module and are not reachable from here - the first draft named
  them), and ten thousand fuzz shapes from the shared grammar at a fixed seed.
  For each shape, at `lanesOverride` 4 and 16 (128 and 512 bits at int), the
  emitted class is parsed with the ClassFile API the way
  `VarkaEmitterTestSupport.java` already does, and each method's `Code` attribute
  bytes - not the whole class, whose constant-pool order may legitimately move -
  are hashed. What is committed in `sql/varka/emitted_bytes.json`: one digest per
  width over the ordered fuzz sequence, and one hash per method per coverage row,
  so a difference on a named shape says which method moved. Step 1 generates the
  file from master's emitter; every later step byte-compares against it; a
  difference is a failed step, not a note, and is read from the two classes'
  bytecode before it is accepted or fixed.
* **The proof lane's parity matrix** in `VarkaLoopEmitterSuite`, built from IR
  with `checkMatrix`'s pattern: the subset of 3.1's table at the long member's
  lane counts 2 and 8, every null pattern the int cases use, against `evalLong`.
* **Reachability at node x lane** in the fuzz suite, with the compatibility table
  as the specification of which nodes exist at which lane.
* **Lane in the key**: two shapes differing only in `ColumnRef`'s lane produce two
  `VarkaShapeKey`s and two cache entries.
* **Malformed trees refused**: a calendar node over a `LONG` child, and an
  `IntArith` over mixed lanes, fail in the constructor with the lane named.
* **The wrong-overload case**: a `LONG` shape called through the int `run` throws
  with the lane named; a malformed tree names its lane too.
* **The standing oracles**: compiler suite, coverage suite with its byte-compared
  `coverage.json`, differential classification - all unchanged. (`dev/varka_emit.sh`
  cannot see the proof lane, section 2; it is not an oracle of this task.)

  The commands, per step:

      build/sbt -batch "catalyst/testOnly *VarkaEmittedBytesSuite"
      build/sbt -batch "catalyst/testOnly *VarkaLoopEmitterSuite *VarkaIrFuzzSuite"
      build/sbt -batch "catalyst/testOnly *VarkaExpressionCompilerSuite *VarkaCoverageSuite"
      build/sbt -batch "catalyst/testOnly *VarkaDifferentialSuite *VarkaShapeCacheSuite"
      dev/varka_gate.sh

## 6. The measurement

No committed number moves: the int32 bytes are identical, so the int32 kernels
are the same kernels. This task produces no number of its own. The first draft
put one throughput case for the proof lane into `VarkaEmitterParityBenchmark`;
that breaks the rule that a new feature family gets its own benchmark class and
results files, and the long lane is one. Its baseline is 104's first commit, in a
class of its own, before 104 adds anything - which is the baseline-as-its-own-PR
rule applied to the task that owns the number.

### 6.2 The emission baseline, measured before step 3

Section 2 says emission costs "about 80 us per shape" and amortises behind the
shape cache. That figure had no committed file behind it, and step 3 is the step
that could move it: a descriptor consulted at forty sites, with a
`MethodTypeDesc` built per lane member, is work done while emitting.
`VarkaEmissionBenchmark` is that baseline, measured on master before the
refactor, in a class and a results file of its own because it is a new question
rather than another section of a throughput benchmark.

**What it measures.** Two sections over the same seven shapes, smallest first:
emission alone, which is the emitter's own work - the IR walk, the analysis
pass, the bodies, the constant pool - and emission with the class defined, which
is what a cold query actually pays. Each case emits two hundred fresh classes
per iteration, so the shape cache is not involved; its hit path is VARKA-18's
subject and costs a map lookup.

**The numbers, 17 September 2026 on `aqua`.** Emission alone runs 17.4 us for a
bare column copy, 20.5 us for `d + 3`, 29.7 us for a checked int add, 31.1 us
for `year(d)`, 33.0 us for a two-input predicate, 66.0 us for
`make_date(year(d), month(d), 1)` and 78.1 us for four calendar outputs over one
date. Defining the class adds 12 to 20 us on top, near-constant across shapes.

**What that corrects.** The plan's "about 80 us" is the *ceiling* - the heaviest
shape in the set - and an ordinary one-output projection costs 17 to 33 us. The
refactor's budget is therefore against a 17 us floor rather than an 80 us one,
which is the tighter constraint. The split also says how much of a cold query's
compile cost is Varka's to improve: roughly two thirds emission, one third the
JVM defining the class. The second section's standard deviation is tens of
milliseconds against best times of the same order, which is class definition and
its garbage, not the emitter; the first section is stable to under a
millisecond.

### 6.1 Predictions, registered before the run

1. **Zero int32 bytes move**, across 64 inventory entries and ten thousand fuzz
   shapes at both widths, at every step of section 8.
2. **About forty sites change**, not 205; the calendar kernels gain one assertion
   line each and nothing else.
3. **The proof lane's parity matrix is green at lane counts 2 and 8** with no
   emitter arm beyond the descriptor's values - that is, no long-specific code
   in the generic sites. (The first draft predicted op counts and throughput
   here; both are 104's now, and the expectation that four-lane long vectors pay
   VARKA-47's 6 to 9% on byte-granular validity writes travels with them as 92's
   number.)
4. *(withdrawn)* Emission time was to be read from `VarkaCompilationWatch`, which
   records compilations and re-emissions, not durations; there is no instrument,
   and the claim - a static table consulted at emit time cannot cost more than
   the field reads it replaces - does not need one.

## 7. Risks

* **Byte identity broken by something other than semantics.** Rebuilding
  descriptors per lane can reorder the constant pool or rename a synthetic; the
  oracle therefore hashes method bodies, and a change it reports is read from
  `dev/varka_emit.sh --asm`-style bytecode diffs before it is accepted or fixed.
* **The `run` overload.** Two entry points on one interface is one more thing the
  evaluator must get right per shape; the lane-in-key test and a wrong-overload
  test (a `LONG` shape called through the int `run` fails with the lane named)
  cover it.
* **`broadcast` argument types.** `IntVector.broadcast(VectorSpecies, long)`
  exists beside the `int` form; the descriptor pins the exact `MethodTypeDesc` per
  lane so the int lane keeps emitting the call it emits today.
* **Scala 2.13 and a Java enum with per-member data.** The compiler and evaluator
  read the descriptor only through a small API; the emitter owns it.
* **Scope creep into 29 and 104.** The proof lane is the fixed set in 3.1;
  anything a `TIME` or interval semantic needs is 29's, anything the evaluator or
  the compiler needs to see a `bigint` column is 104's, and both are declined
  here with a pointer.
* **A checked multiply slipping into the proof set.** The parity matrix's cases
  are built from the 3.1 table; a `MUL` at `FAIL` or `NULL` at either lane is
  refused by `emitIntArith` today, and the table keeps it out.
* **The width knob read as bits.** A loop written as "128 and 512 bits" with the
  int lane's counts 4 and 16 would test the long lane at 256 and 1024 bits, the
  second of which has no species and falls to `SPECIES_PREFERRED`. Loops read the
  member's permitted counts from the descriptor.

## 8. Sequencing

1. `VarkaEmittedBytesSuite` against today's emitter at `lanesOverride` 4 and 16,
   `sql/varka/emitted_bytes.json` generated and committed. Green on a second run
   means the oracle exists.
2. `LaneType` as a component of the leaves, `INT` everywhere, derivations and
   constructor refusals; the compiler constructs leaves with it. Oracle green.
3. The `Lane` enum with the `INT` member only; the ~40 sites switched one method
   at a time, oracle after each; the calendar sites assert `INT`. Oracle green.
4. `LONG`: the member from table 3.4, the `longArgs` overload, the proof set's
   emitter arms (expected to be none beyond the descriptor); `evalLong` for the
   set; the parity matrix at counts 2 and 8 and the node x lane reachability.
   Oracle still green on int32.
5. The lane-in-key and wrong-overload tests; the standing oracles; the full gate.
6. Section 9, the milestone row, 119's first part recorded as landed, the docs.

## 9. Outcome

*Steps 2 to 6 are still to come; this section grows step by step.*

### 9.1 Step 1, landed: the oracle exists

`VarkaEmittedBytesSuite` and `sql/varka/emitted_bytes.json`, 16 September 2026,
built on VARKA-84's branch because the fuzz shapes come from the shared grammar
that branch extracted, and rebased onto master when that branch merged.

**What is pinned.** Every coverage row the compiler produces IR for - all 57
since VARKA-120 landed, see below - and ten thousand fuzz shapes at seed
20260916, drawn exactly as
`VarkaIrFuzzSuite.runOne` draws its trees, each at `lanesOverride` 4 and 16. Per
coverage row and width, one hash per emitted method and one for the class
around them (513 entries per width); per width, a hundred block digests over the fuzz sequence, so a
difference names a block of a hundred shapes rather than everything or nothing.
The file is 68 KB, 1281 lines.

**How a method is hashed.** Not the `Code` attribute's bytes, which section 5
first proposed: those embed constant-pool indices, and a refactor that builds
descriptors per lane would reorder the pool and move every index while changing
no instruction, which is the false alarm this oracle must not raise. Instead
`VarkaEmitterTestSupport.methodBodies` renders each method symbolically -
opcode, callee by owner, name and descriptor, constants by value, branches by
labels numbered in order of first appearance, exception ranges kept, line
number and local variable tables dropped - and the rendering is hashed. Two
classes that do the same thing render the same whatever their pools look like;
a difference in the rendering is a difference in what the method does. The
Class-File API stays in Java, per the house rule Scala cannot see it.

**Checked rather than assumed.** A second run without the regenerate switch
compares byte for byte and passes, so the generation is deterministic across
JVMs. A shape emitted at 4 and 16 lanes renders differently, so the width knob
reaches the bytes and the second width pins something. The whole suite takes
eight seconds, which is cheap enough that every step of section 8 runs it
without thought; the `wide` gate step already picks it up through `*Varka*`.

**Regenerated once, when VARKA-120 merged.** The oracle was written on a master
where the `InSet` row was a caption the compiler could not parse and the
coverage table carried `year(d) = 2021 AND i > 0`. VARKA-120 gave the caption an
`executable` spelling, which the suite reads, and replaced that conjunct row
with `year(d) = 2021 AND month(d) > 6`. Merging master in made the suite fail
with four lines, all of them "new row" at both widths, and the regeneration adds
the `IN` row's eight method hashes, replaces the renamed row's six, and empties
`coverage_rows_skipped`. No fuzz block digest moved, which is the statement that
matters: the emitter produces what it produced, and the file grew because the
table did.

**Reviewed, 16 September 2026, and five things came back.** The failure
diagnostic walked only the newly generated document, so a coverage row, a method
or a fuzz block that *vanished* - the more alarming direction, since it means a
shape the table claims stopped reaching the emitter - printed nothing at all; it
now walks both documents and reports the skip list's changes too. A row that
parses and then declines was recorded as skipped beside a row that does not
parse, which would let a regeneration bless a coverage regression; a decline now
fails the suite, since `VarkaCoverageSuite` guarantees it cannot happen. The
diagnostic dereferenced two nodes one line before the null check meant to guard
them. The fuzz draw was a verbatim copy of `VarkaIrFuzzSuite.runOne`'s preamble,
so one extra call to the generator in either would have split the two corpora
silently; it is one `VarkaIrGrammar.drawShape` now, and the oracle staying green
is the proof the sequence did not move. And the `InSet` row was pinned through
the compiler's `In` arm, because this suite has no optimizer - it applies that
one rewrite itself now, and fails if a row the table records as an `InSet` does
not become one.

**Reviewed again, at the highest effort, and five more came back - four of them
about claims the suite made and did not keep.** The two corpora were drawn from
different seeds, so the sentence that every pinned shape had been run against
the reference evaluator was simply false; one `fuzzSeed` and one `shapeRandom`
in `VarkaIrGrammar` make it true, and the oracle's ten thousand shapes now
extend the fuzzer's three hundred rather than diverging from them. The rendering
called a constant's opcode symbolic, but `LDC` and `LDC_W` are chosen by where
the constant lands in the pool, so adding a constant ahead of another would have
moved hashes with no instruction changing - the three forms render as one token
now, while `bipush`, `sipush` and `iconst` stay as they are, being chosen by the
value. The `InSet` guard added in the previous round threw inside the parse
`catch` that surrounds it, so it recorded a skip instead of failing; the catch
covers the parse and the resolve only. And the oracle never looked at the class
around the methods, so dropping the kernel interface or the telemetry attribute
would have left every hash identical - each shape carries a `<class>` entry now,
over the flags, the superclass, the interfaces, the attribute names and each
method's flags. The fifth was two scaladocs orphaned by members inserted between
them and what they describe, which is the repository's own named failure mode.

**What it does not yet pin.** Random emit options: every shape is emitted at
the defaults plus the width. VARKA-85's refactor touches the default path first;
an option-matrix oracle is a widening for a later step if a site turns out to
be reached only under a non-default option.

### 9.2 Step 2, landed: the lane is in the IR

`LaneType` is a component of the two leaves and derived everywhere else, 16
September 2026, with the int lane emitting exactly what it emitted.

**What carries a lane and what derives one.** `ColumnRef(int ordinal, LaneType
lane)` and `LiteralSlot(int index, LaneType lane)` carry it; a one-argument form
means the int lane, which keeps the several hundred int trees in the suites
reading as they did and is what the compiler's two leaf makers pass. Everything
else derives: a value node answers its operands' lane, a condition answers the
lane it compares in, and every calendar node answers `INT`. The derivation is
one exhaustive switch beside `canonical` and `canonicalShallow`, so a node type
added later does not compile until it says which lane it is on - the property
those two switches already had, now covering the lane as well.

**Where a malformed tree stops.** In the record constructors, as section 3.1
decided: `requireInt` on the twenty calendar and date nodes, whose children are
epoch days, and `requireSameLane` on the nine lane-generic ones. The reason to
refuse mixed operands rather than widen silently is that one node is emitted
over one species - an add needs its operands to agree as much as a blend needs
its mask and its values to - so a mixed node has no lowering at all; widening is
a conversion node, which is VARKA-28's.

**A consequence worth naming before VARKA-104 meets it.** Tying a condition's
lane to the lane it compares in, and requiring an `IfElse` to agree with its
condition, means `CASE WHEN <64-bit comparison> THEN <32-bit value> END` is
refused rather than built. That is the right refusal - the blend and the mask it
selects with are one species in the emitted code - but it is a shape the
compiler will meet as soon as a `TIME` predicate guards a date result, and the
answer is a mask conversion node, VARKA-28's, not a loosening here.

**The one design decision this step had to make.**
`VarkaShapeCacheImpl.shapeHash` is built from `canonical`, and the emitted class
is named after it, so a lane that rendered would move every committed hash while
an int lane that did not render would let two lanes collide on one class name.
The lane renders as nothing at `INT` and as `:long` otherwise - exactly the
elision `VarkaEmitOptions.canonical()` already makes for its defaults, and for
the same reason. `VarkaShapeCacheSuite`'s two pinned hashes are the check that
it worked, and they did not move.

**Checked.** `VarkaEmittedBytesSuite` green with no regeneration, which is the
step's own admission rule: no int32 method body moved. The compiler, coverage,
fuzz, range-analysis and loop-emitter suites green unchanged.
`VarkaLaneTypeSuite` is new and holds seven cases: every node type's derivation,
with the list checked against the sealed hierarchy's permitted subclasses so a
new node type cannot slip past it; the leaves' short form; a long subtree
deriving long; the calendar refusals; the mixed-operand refusals; the rendering
and the two shape hashes; and the emitter refusing `LONG` by name, which is what
tells "refused" apart from "emitted as int by accident" until step 4.

**Reviewed at the highest effort, and seven things in this step came back.**
Four were fixed here. The lane derivation caught the nine calendar extractions
with one `case Chrono` arm, which defeats the very forcing its comment claims: a
64-bit chrono node added later would compile unchanged and answer `INT` through
the umbrella while holding a wider child. They are enumerated one by one now, as
`canonical` and `canonicalShallow` already do. `requireInt`'s message rendered
`canonical(child)`, which recurses without a memo over what is a DAG in effect,
so a deep shared tree would spend exponential time building a message that names
only the node and the lane; it prints the child's type instead.
`requireSameLane` built its label on every successful construction of the two
hottest nodes the compiler makes, and re-derived the first operand's lane once
per comparison. And `LiteralSlot`'s new comment claimed a slot table per lane,
which nothing implements - there is one `int[] scalarArgs` and one literal count
in the key - so it now says the index space is shared and names the step that
splits it.

**The range analysis needed a guard of its own, and got one.**
`VarkaRangeAnalysis` is the IR's other consumer and it runs inside the compiler,
before the emitter could refuse anything: a wider column would have been handed
the int32 epoch-day contract and a wider literal read through an
`IntUnaryOperator` that cannot hold it, and the caller would have concluded that
a checked add cannot overflow and elided the guard. A node on a lane wider than
int32 answers `UNKNOWN`, which is the safe direction, and the class's premise -
"the IR is untyped" - is corrected to what this step made true.

**Two findings are real and deliberately not fixed here, because the code that
would make them reachable is step 4's.** A constructor refusal is an
`IllegalArgumentException`, and the compiler's contract is to decline through
`DeclineSink` rather than throw: `VarkaColumnarRule` catches nothing, so once a
`LONG` leaf exists a mixed-lane conjunct would fail a query at planning time
rather than fall back to the row engine, against the rule in
`sql/varka/AGENTS.md`. And `fitsBudgets`, which exists to mirror every emit-time
throw at admission time, was not extended with the lane refusal, so such a tree
would pass admission and produce the ghost fallback that function exists to
prevent. **Both are prerequisites of step 4**: no leaf maker outside `intSlot`,
`columnRef` and `derivedRef` may exist until the compiler turns a refusal into a
decline and `fitsBudgets` refuses a non-`INT` tree.

**One is a design question this step should not have settled, and step 4 will.**
`IsNotNull` inherits its operand's lane, and `IfElse` requires its condition to
agree, so `CASE WHEN bigint_col IS NOT NULL THEN d1 ELSE d2 END` is refused
although it has a good lowering: `IsNotNull` reads a validity bitmap, which has
no lane width of its own and can be materialised at whichever species the blend
wants. The refusal is right for a mask that is a comparison result and wrong for
one that is a validity read, and telling them apart needs the lane descriptor to
price the alternative. Recorded here rather than loosened blind.

**`LaneType.LONG` exists a step earlier than section 8 said.** The sequencing
put it in step 4, with steps 2 and 3 on `INT` alone. But a refusal that cannot
be built cannot be tested: with one member in the enum, every constructor check
above is unreachable code. The member is added here, the emitter refuses it, and
the refusals have tests; nothing else about step 4 moved.

### 9.3 Step 3, landed: the emitter reads a lane

`Lane` exists with its `INT` member, the emission carries one, and the int32
bytes did not move - 17 September 2026.

**The descriptor.** A nested enum in the emitter whose every field is derived
from two facts, the vector class and the scalar type: the load, store,
broadcast, lanewise, compare and blend shapes, the byte shift from a lane index
to an offset, the scalar's array type, and the species constant for a baked lane
count. Deriving rather than listing is what stops a second member disagreeing
with the first about the shape of `lanewise` or `compare`. One value is
deliberately not derived: `broadcast` takes `CD_int` at the int lane and must
keep it, because `IntVector` also declares `broadcast(VectorSpecies, long)` and
a widened descriptor would silently select it - the risk section 7 named.

**Where the lane lives.** `Analysis` carries it, one per emitted class rather
than one per node, because a kernel's loop, its epilogue and its stores are one
species. `analyze` used to refuse any node that was not `INT`; it now refuses
any node whose lane differs from the emission's, which is the same refusal today
and the right one when a second member exists.

**The twenty-eight sites converted**, in four batches with the oracle run after
each: the species prologue and the hoisted literal broadcasts; the column load
and the store, masked and dense, and the unhoisted broadcast; `IfElse`'s blend,
`IntArith`'s lanewise op and the four lanewise calls plus the compare of its
overflow test, and `IntNeg`'s compare and multiply; then the shared
`emitAndValidatedOp` receiver, `emitRangeGuard`'s two bound compares, and
`emitPick`'s op and blend, which are `greatest` and `least`. That is fewer sites
than section 2's estimate of about forty because several of them are one shared
helper reached from many arms - the estimate counted call sites, and the
conversion counts emitting ones.

**The calendar family keeps the int descriptors and says so.** `Lane.requireInt`
is called at the three entry points that carry the analysis - `emitChrono`,
`emitMakeDate`, `emitAddMonths` - and every one of the hundred and forty sites
below them is reached through those. Adding the check to each would have meant
plumbing an `Analysis` through helpers that take nothing but a `CodeBuilder` and
int slots, which is cost without cover: the tree cannot be wider by then,
because the IR's constructors refuse a calendar node over a wider child and
`analyze` refuses a node whose lane differs from the emission's. The check is
the third line of that defence and the one that speaks for the kernels.

**Checked.** `VarkaEmittedBytesSuite` green after every batch with no
regeneration, which is this step's whole admission rule: not one int32 method
body moved through a refactor of a 5 924-line file. `VarkaLaneTypeSuite` gained
two cases that pin every derived descriptor and every species name against
hand-written expectations, because a derivation checked against itself checks
nothing and the oracle cannot reach a field no site reads yet.
`dev/varka_gate.sh` green in all eight steps - compile, wide, narrow, sweep,
doc, bench, lint, quotes - which matters here for `doc` and `lint` in
particular, since the step adds a hundred and thirty lines of javadoc with new
`{@link}` targets.

**Reviewed, and the review found the step half-wired.** Eight of its fifteen
findings were sites this step meant to convert and did not, and the reliable
signal was a descriptor field with no reader. Fixed here: `emitCond`'s `Compare`
arm - the sole emission site for every comparison, including every `IfElse`
condition - was untouched; `emitPick` had four sites and two were converted, the
pair only a nullable input reaches; and both byte-stride computations still
emitted the literal `4L`. That last one also showed the descriptor carried the
wrong shape of its own fact: a `byteShift` cannot be used where the emitted code
multiplies without changing the int32 bytes, so it is a `byteStride` now and the
two sites read it. The dead half of the descriptor table - five constants whose
last reader had moved - is deleted, with the prose that recorded *why* the
masked load exists moved into the field that replaced it rather than deleted
with it, and the old `speciesField` static, which differed from the enum's
method only in using `Integer.SIZE`, is gone.

**Two claims in the step's own comments were false and are corrected.** The enum
said `broadcast` was "deliberately not derived" while the code derived it; the
truth is the opposite and sharper - `IntVector` declares both an int and a long
form of `broadcast`, `compare`, `blend` and `lanewise`, `LongVector` declares
only the long ones, so the scalar must be the lane's own type and neither a
pinned `CD_int` nor a pinned `CD_long` works. And it claimed
`VarkaLoopEmitterSuite` pinned the derivation, which no test did;
`VarkaLaneTypeSuite` does now, and the sentence names it. The `requireInt` claim
was overstated in the same way: six date arms emit int-only bytecode inline in
`emitValue` without passing any of the three entry points, so they have the
check now too, which is what makes "the third line of that defence" true.

**What the review leaves for step 4, recorded rather than fixed.** The lane
count is still the int lane's: `emitLanes` hardcodes 2, 4, 8 and 16 and
`PREFERRED_LANES` reads `IntVector.SPECIES_PREFERRED.length()`, so a long
emission at sixteen lanes would name `SPECIES_1024`, which does not exist - the
descriptor needs the permitted counts and the preferred count that table 3.4
lists and the enum does not yet carry. The scalar-argument path is int
throughout - `iaload`, one JVM local per literal, `int[]` in the `run`
descriptor - which is the `longArgs` overload section 3.1 already specifies. And
the int constants pushed into now lane-derived descriptors are a family of their
own: `Integer.MIN_VALUE` into `compareVI` in `IntNeg`, `0` in `IntArith`'s sign
test, `-1` in the negate. Widening the push is not the fix - `Integer.MIN_VALUE`
is the wrong sentinel at 64 bits - so the lane needs a way to push a lane-typed
constant, and that is the first thing step 4 should build.

### 9.4 Step 4, landed: the second lane

`LONG` exists, a long-lane kernel computes what the reference says at both its
widths, and the int32 bytes still have not moved - 17 September 2026.

**How little new emitter code it took is the result.** The arithmetic, the
comparisons, the blend and the hull ops needed no new arms at all: they read the
descriptor and got `LongVector`. That is what step 3 was for, and it is the
answer to 2.16's question about a descriptor against a generated per-lane
emitter.

**What was genuinely lane-specific, and each was a finding the step-3 review had
already named.** The lane's own permitted counts and `SPECIES_PREFERRED`, since
`emitLanes` hardcoded the int list and eight long lanes would have named
`SPECIES_1024`, which does not exist. The second scalar array, with `laload` and
two-wide locals, and the parameter and local slot layout it shifts - `length`
moves from slot 7 to 8 and the first local from 8 to 9, both now the lane's own.
And the constants: the negate's sentinel is the lane's most negative value,
`Long.MIN_VALUE` rather than `Integer.MIN_VALUE`, which is why widening the push
would not have been the fix.

**The kernel interface gained the eight-argument `run`.** Both forms are
defaults that throw with the lane named, and an emitted class overrides the one
its lane needs - so calling a long kernel through the int entry point is a named
failure rather than an `AbstractMethodError`. The int table stays `int[]`,
untouched, which is what keeps every 32-bit kernel's descriptor and every
`iaload` exactly as they were.

**The proof.** `checkLongMatrix` drives the emitted class through the long entry
point over 64-bit buffers and compares against `evalLong` - VARKA-119's first
part, landing here as the plan said it would. Ten shape families over four batch
lengths and every null-pattern combination, at the long lane's 2 and 8 counts,
which are 128 and 512 bits. The values straddle the int range on purpose: `1L <<
40` and its neighbours are numbers a 32-bit lane cannot hold, so a kernel that
had kept int descriptors would differ on the first row rather than agree by
accident.

### 9.5 Where step 4 departed from the plan, and why

**The reachability test is an enumeration, not a lane-parametric generator.**
Section 3.1 asked for `VarkaIrFuzzSuite`'s generator to obey a compatibility
table. What landed is a table in `VarkaLaneTypeSuite`: every concrete node type
at every lane is either refused by its constructor or emitted and verified, and
the long lane's set is asserted to be exactly the twelve the subset names. The
reason is that the long subset is twelve node types, which enumeration covers
exhaustively where a generator covers it by chance - and a lane-parametric
`Shapes` is what VARKA-104 needs for SQL-level shapes rather than what this task
needs for twelve. The claim the plan wanted is the one that holds: a later lane
arriving without an arm fails here, in milliseconds.

**What step 4 still does not ship, unchanged from section 3.1.** No compiler arm
admits a `LongType` column, so no SQL reaches the long lane and no evaluator
change was needed; `MUL` at `FAIL` or `NULL` has no arm at either lane, and the
long reference throws rather than inventing one. Both are VARKA-104's.

### 9.6 What the review of step 4 found

The step-4 review found one live defect, and it was in the one place the step
was supposed to be careful about.

**A baked lane count needs two different things, and the code checked one.**
`emitLanes` had been rewritten from the int lane's hardcoded list into a
per-lane `permittedLanes` table, and `Lane.LONG`'s table admitted one lane
because one long lane is 64 bits and `LongVector.SPECIES_64` exists. The
validity helpers in `VarkaVectorSupport` do not follow the width, they follow
the lane *count*: there are pairs at 2, 4, 8 and 16 and none at 1. So a
one-long-lane emission produced a class that passed verification and threw
`NoSuchMethodError: validityBitsAt1` on its first masked batch. It was reachable
two ways: `lanesOverride(1)`, and - with no override at all - on any JVM whose
widest vector is 64 bits, which `-XX:MaxVectorSize=8` reproduces exactly.

The fix separates the two questions rather than re-tabulating them by hand,
which is what let the table disagree with the helpers in the first place:
`Lane.hasSpecies(lanes)` computes whether the width has a named species constant
from the width itself, `hasValidityHelpers(lanes)` names the four counts
`VarkaVectorSupport` actually carries, and a width needs both or runs on
`SPECIES_PREFERRED` and the general helpers. The guard test asserts the
conjunction for every lane and count the emitter can reach, and names
`lanesOverride(1)` at the long lane as the case that used to slip through. The
suites pass under `-XX:MaxVectorSize=8`.

**The rest were narrower.** The range guard pushed its bounds with
`loadConstant`, an int push, immediately before a `compare` descriptor the lane
supplies - correct today because every caller is a calendar node on the int
lane, and a `VerifyError` the moment one is not, so it now goes through
`Lane.pushScalar` like every other scalar. `fitsBudgets` did not mirror the lane
agreement `emit` demands, so a mixed-lane entry would have been admitted by the
compiler and then refused by the emitter as an exception, which the evaluator
can only turn into a silent per-batch fallback with no decline reason. And the
long matrix drove the checked and nulling overflow modes over values far from
either extreme, so the 64-bit sign test they exist for never fired once: the
extremes matrix and the condemn-the-batch test are the int lane's own overflow
tests re-run at 64 bits, including `-Long.MIN_VALUE` and a `Long.MAX_VALUE + 1`
that an int lane's sign test would not see.

### 9.7 Closed, 20 September 2026

Step 6 of section 7's sequencing was this section, the milestone row and the
record of VARKA-119's first part. All three exist: 9.1 to 9.6 above, the row in
`m5/PLAN.md`, and `VARKA-119.md` for the long-lane oracle that
landed on its own (#262). The lane has since carried the long-lane division
(VARKA-88 step 3), the `TIME` expressions (VARKA-102) and the multiply-high
lowering (VARKA-149) without a change to the descriptor, which is the outcome
2.16 asked the design to make possible. The row is marked done.
