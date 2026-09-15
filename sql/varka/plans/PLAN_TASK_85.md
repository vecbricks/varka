# Task 85: lane type as a parameter

*Milestone 5, section 2.16. Opened 8 September 2026 from task 63's review; planned
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
representation and not its Spark type. Task 67 delivered it - year-month intervals
are int32 months, the same lane as `DATE` and `INT` - so this task starts with its
example in hand and does not have to argue the point.

## 2. The admission check, done

**Where the 205 references actually are** - counted per method with a
brace-tracking scan, because the answer decides the design:

| region | refs | per-lane? |
| :--- | ---: | :--- |
| calendar kernels: `emitAddMonths` 20, `emitDaysFromCivil` 19, `emitChronoPrefix` 18, `emitMakeDate` 14, `emitFloorMod7` 13, `emitChronoLastDay` 10, `emitJulianYearOfEra` 6, `emitTruncQuarter` 6, `emitChronoYear`/`emitJanuaryDayOfYear`/`emitChronoTrunc` 5 each, `emitLeapFlag`/`emitEra`/`emitChronoMonth` 4 each, `emitFold`/`emitCarry` 3 each, and the small ones | ~140 | **no** - they consume an epoch-day lane, which is int32 by definition; at another lane they must not run, and a conversion node (task 28) is how a wider value reaches them |
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
would need a 128-bit one (task 104's problem under the lattice); the oracle said
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
| `IntArith` | `ADD`, `SUB`, `MUL` at `WRAP`; `ADD`, `SUB` at `FAIL` and `NULL` (the sign test is lane-generic) | `MUL` at `FAIL` or `NULL`: no arm exists at any lane; the compiler declines it and `emitIntArith` throws; a long lane needs a 128-bit product - task 104 |
| `IntNeg` | all three modes | - |
| `Compare`, `And`, `Or`, `Not`, `IsNotNull` | as at int | - |
| `IfElse`, `Greatest`, `Least` | as at int | - |
| every `Chrono` node, `AddDays`, `SubDays`, `DateDiff`, `NextDay`, `ThursdayOf`, `AddMonths`, `MakeDate`, `GuardedDay`, `DayOfWeek`, `WeekDay`, `DayOfWeekIso` | none | `INT` only, refused in the constructor; a wider day reaches them through 28's conversion |

That is the arithmetic-and-comparison core of task 104, at the long member's 2
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
part of task 119; **that part of 119 lands with this task**, and the milestone
plan's graph is corrected to say so.

### 3.2 What is deliberately unchanged

Every int32 emitted method body, byte for byte - the oracle. Every calendar
kernel's code. The `run` signature for int shapes. The validity-word machinery,
including its `lanes < 8` behaviour (task 92 lands beside 29, when four-lane
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
* `PLAN_MILESTONE_5.md` - row 85; 119's first part moved beside 85 in 3.1; the
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

### 6.1 Predictions, registered before the run

1. **Zero int32 bytes move**, across 64 inventory entries and ten thousand fuzz
   shapes at both widths, at every step of section 8.
2. **About forty sites change**, not 205; the calendar kernels gain one assertion
   line each and nothing else.
3. **The proof lane's parity matrix is green at lane counts 2 and 8** with no
   emitter arm beyond the descriptor's values - that is, no long-specific code
   in the generic sites. (The first draft predicted op counts and throughput
   here; both are 104's now, and the expectation that four-lane long vectors pay
   task 47's 6 to 9% on byte-granular validity writes travels with them as 92's
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

*To be written from the oracle and the proof lane's numbers.*
