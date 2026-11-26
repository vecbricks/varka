# VARKA-686: The table form of a zone conversion

*Planned on 26 November 2026. This plan stands alone: a builder needs this file and the
repository's `AGENTS.md` files, nothing else. Not started: milestone 9 waits for the owner's
word (`m9/PLAN.md` decision 18). The code facts below were checked on master `176db41f369`.*

## 1. What this row is

Row 686 of `sql/varka/plans/m9/PLAN.md`: "The table form of a zone conversion". Its text: the
lowering with a packed bucket of 16 bytes and a clamped index, `zone_bucket.smt2`, and the form
as the ladder's last. Done when ten years of instants in no order decline no batch, a null
row's storage outside the table reads no memory outside it, and the packed table is measured
against three gathers.

It builds the form `TABLE` of the emit option `zoneForm`, for `ToLocal` (an instant to the
local time), and `TABLE_SCALAR`, the same table read with no gather. A batch whose range
holds more changes of offset than 8 steps cover takes `TABLE`.

What it gives the rows after it: to rows 690 and 693, the bucket's constants, the packing
and the kernel's read of a table (4.9); to row 691 (the forms measured), the form in
kernels, the benchmark class, the threshold `VarkaZoneTable.tableFromChanges`, and the form
`TABLE_SCALAR`, which that row puts on the ladder for a host with no gather if it wins (4.7).

Merged before it starts: rows 682 and 683 (`ToLocal`, the batch arguments, the lowering,
the runner's fill), row 657 with row 678 (the first zoned kernels, the production zone data),
row 684 for parts b and c (the sweep's harness, the property suite), and row 685 for part c
(the ladder). Part a adds one method to row 678's class, `toLocalTable` (4.5).

The goals it serves (`m9/PLAN.md` 1.1), each with the item of 1.2 that it moves:

- G2, item 2: "over ten years of instants in no order in a daylight-saving zone the kernels
  of the fields, of the casts and of the sum with an interval decline no batch". This row moves
  the fields and `CAST(ts AS DATE)`, which read `ToLocal`. The casts to `TIMESTAMP` and the sum
  read `ToInstant`: for them the item waits on row 693 (4.9, risk 7). "The every-zone sweep
  passes in CI": the sweep gains both table forms.
- G7, item 10 and G1, item 1: not moved. A year holds 2 changes and takes `STEPS_2`, and no
  entry fuses or stops fusing. The row serves G7 only through row 691.

## 2. What an agent must know

**The idea.** A `TIMESTAMP` is a count of microseconds since the epoch in UTC, a long, called
an instant here. The local time is the instant plus the zone's offset at that instant. Row
682's forms add one offset and then one difference for each change inside the batch's
range: `ONE` (no change), `STEPS_2`, `STEPS_8`. Ten years of instants in no order span 20
changes in `America/Los_Angeles` (`sql/varka/spikes/zone-offset/results/facts.txt`), more
than 8 steps cover, so every such batch declines to Spark's row path. The table form has one
cost at any number of changes. It cuts the time from 1900 to 2100 into buckets of 2^39
microseconds, about 6.4 days. The closest two changes of one zone are 601200 s apart
(`facts.txt`), more than a bucket, so a bucket holds at most one change. A bucket stores
that change and the offsets before and after it. The kernel computes each row's bucket,
reads it, compares the instant with the change and adds one of the two offsets.

**Terms.** A *change* is an instant at which a zone's offset changes; an *offset* is local
time less UTC, whole seconds within 18 hours (`java.time.ZoneOffset`). A *batch argument* is
an entry of the kernel's `long[] longArgs` that the runner writes, after the literals; a
*program* is the run of them that one zone node reads, its first index and its count. A
*form* is the value of the emit option `zoneForm`: one form serves every zone node of a
kernel and is part of its shape key. The *ladder* is row 685's rule: each batch runs the
least form that covers the range of every program. A *bucket* is 2^39 microseconds of the
time line, and its 16 bytes in the table. A *gather* is a vector load whose lanes come from
places of one array, by an index a lane. A *decline* sends a batch to Spark's row path and
counts the reason; a *refusal* builds no kernel and records why. *Two, four, eight lanes*
are 64-bit vectors of 128, of 256 (the EPYC 7763 runners, AVX2) and of 512 bits (the laptop).

**The names this row reads.** From `VARKA-657.md` section 3 and the plans `VARKA-682.md` and
`VARKA-684.md`. None is on master today; each sheet's B0 prints them.

| name | from | meaning |
| :--- | :--- | :--- |
| `VarkaVectorIR.ToLocal(instant, zone)` under `sealed interface Zoned` | 682 | the node; `zone` indexes `CompiledVarkaProjection.zones()` |
| `VarkaEmitOptions.ZoneForm { ONE, STEPS_2, STEPS_8 }` with `steps()`, the option `zoneForm`, `withZoneForm` | 682 | the form; row 685 adds `LADDER` |
| `VarkaBatchArgs.Program(node, first, count)`, `layout(outputs, numLiterals, options)`, `count(node, form)`, `neutral(program, args)`, `MAX_OFFSET_MICROS` | 682 | the layout; the first program starts at index `numLiterals` of `longArgs` |
| `VarkaZoneLowering.emitToLocal`, `laneCalls(form)` | 682 | the lowering and its count of lane calls |
| `VarkaBodyEmitter.emitBatchArgs`, `Slots.batchArgLocal`, `Slots.zoneTmp` | 682 | each argument loaded once into a long local; the vector local of a node |
| `VarkaZoneData` (`changes`, `toLocalArgs`), `VarkaZoneData.Provider`, `VarkaJavaTimeZoneData` | 682 | the zone data, and its reference over `java.time` in test scope |
| `VarkaKernelRunner.fill`, `STATUS_BATCH_ARGS`, the metric `batch-args`; `VarkaKernelPlan.Kind.ZONE_OPERAND` | 682 | the fill before each call, and its decline; the refusal of a zone node over a computed value |
| `VarkaZoneKernels` (`Kernel`, `Filler`, `Spec`, `sweep`, `production`), `VarkaZoneSweepSuite`, `VarkaZonePlantedFaultSuite`, `VarkaZoneFaults`; `VarkaTimestampZoneSuite`, `VarkaBatchArgRunnerSuite` | 684; 657, 682 | the sweep's harness and suites; the Spark-level suite, and the runner's suite |
| `VarkaKernelPlan.twoLaneMasksFallBack`, `VarkaHostIsa.host()` and its `Reading` (`arch`, `features`) | master | whether this x86 JVM has two 64-bit lanes or fewer; the JVM's own feature list |
| `VarkaZoneLadder` (`RUNGS`, `emitted`, `widest`), `VarkaZoneArgsPropertySuite` | 685, 684 | the ladder's forms in order and its last rung on a host; the property suite of the fill |

**The names this row adds.**

| name | where | meaning |
| :--- | :--- | :--- |
| `ZoneForm.TABLE`, `ZoneForm.TABLE_SCALAR` | `VarkaEmitOptions` | the form with gathers, and the form with scalar loads of each lane, for a host with no gather (4.7); `steps()` throws for both, so no reader takes one for `ONE` (4.4) |
| `VarkaZoneTable` | catalyst main, `codegen/varka` | the bucket's constants, the packing, the scalar lookup, `covers`, `tableFromChanges` (4.2) |
| `VarkaZoneData.toLocalTable(long[] args, int first)` | catalyst main | writes a zone's table; false when the zone has none |
| `VarkaZoneLowering.emitToLocalIndex`, `emitToLocalTable`, `emitToLocalTableScalar` | catalyst main | the index pass and the gathers (4.3); the scalar form (4.7) |
| `VarkaFusedKernel.indexMapIntsPerRow()`, `VarkaZoneTable.INDEX_SLACK`, `indexMapInts(perRow, length)` | catalyst main | the ints a row that a kernel's index map needs, 0 by default; the ints past them; the array's least length (4.1) |
| `VarkaZoneTable.gatherFallsBack(VarkaHostIsa.Reading)` | catalyst main | the host's flag: C2 compiles no gather of longs there (4.7) |
| `zone_bucket.smt2`, `VarkaProofFiles.zoneBucket()` | `sql/varka/proofs/`, catalyst tests | the proof (4.10) |
| `VarkaZoneTableSuite`, `VarkaZoneTableLanesSuite` | catalyst tests, Java, JUnit 5 | the table's data and the kernel; the kernel at lane overrides, in a JVM of its own (section 6) |
| `VarkaZoneTableBenchmark` | catalyst tests, Java | the measurement (section 7) |
| the census cells `long.gather`, `int.MIN`, `long.gather_then_int`, `long.int_then_gather` | `VarkaOperatorCensusCatalog` | whether C2 compiles the gather, and whether it makes an int gather box (4.1) |

**What the probe measured** (`sql/varka/spikes/zone-offset/results/forms.txt`: the laptop,
`America/Los_Angeles`, batches of 4096 instants in no order, nanoseconds a row by the best
round). A table of three arrays read by three gathers: 0.783 and 0.781 at eight lanes, 0.949
and 0.882 at four, 38.864 and 39.334 at two, over a year and over ten. Steps written out for
2 and for 8 changes: 0.086 and 0.289 at eight lanes, 0.148 and 0.755 at four, 13.020 and
62.542 at two. `java.time`'s offset: 10.293, 10.285 and 10.956.

**What the second probe measured** (`sql/varka/spikes/zone-offset/results/tables.txt` and
`tables-2.txt`, two runs; they land with pull request #1769 and may not be on master when
this plan merges). The same laptop, zone and batches; each number is the lesser of the two
runs, over ten years, at eight and at four lanes. 4.1 has the forms that hold a gather, and
4.7 the two-lane numbers. Every time is the laptop's Zen 5 core, and its four lanes are
`-XX:UseAVX=2`. The EPYC 7763 runners are not measured.

## 3. The admission check

Each fact was checked on `176db41f369` in `/home/max/proj/varka-657-plan-w`; the command
after it prints what its comment says. `V` is
`sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka`, `E` is
`sql/core/src/main/java/org/apache/spark/sql/execution`, `T` is `V`'s twin under
`src/test/java` and `TS` under `src/test/scala`, and `J` is
`/home/max/proj/openjdk-build/jdk25/src/jdk.incubator.vector/share/classes/jdk/incubator/vector`.

1. **A kernel takes addresses and two argument arrays, and nothing else:**
   `VarkaFusedKernel.run(srcData, srcValidity, srcNullCount, dstData, dstValidity, int[]
   scalarArgs, long[] longArgs, length)`. Its comment gives the reason the descriptor does not
   grow: a wider one "would change the descriptor and the loads of every 32-bit kernel already
   emitted". A long-lane kernel never reads `scalarArgs`: "callers may pass an empty array".
   `scratchBytesPerRow()` is the pattern for what a kernel needs a row: a default method
   that answers 0, read by the runner, the warm-up and the reproduction. The runner keeps
   `scalarArgs` in a public final field.
2. **JDK 25's Vector API gathers from an array only.** `LongVector.fromArray(species, long[]
   a, int offset, int[] indexMap, int mapOffset)` is the gather. `fromMemorySegment` has two
   forms, plain and masked, and neither takes an index map.
3. **The gather takes its indices from an `int[]`, in a second int species, and checks
   them.** It loads the index map as `IntVector.species(vsp.indexShape())`, the int species
   with as many lanes as the long species has: half the long species' bits. Then
   `VectorIntrinsics.checkIndex(vix, a.length)`, two compares, an OR of masks and `anyTrue`,
   throws `IndexOutOfBoundsException` for an index outside the array. The check is on unless
   the JVM runs with `-Djdk.incubator.vector.VECTOR_ACCESS_OOB_CHECK=0`.
4. **The emitter has no gather and no indexed load today.** Every column is read by
   `fromMemorySegment`; its only array loads are the constant tables of `VarkaMaskFromWord`.
   `VarkaGatherProbe`, in test scope, says "the emitter has no gather", with fact 2 as the
   reason.
5. **A long value becomes int lanes by one emitter method, into the int species of the same
   width.** `VarkaVectorWalk.emitShapeConversion(cb, Lane.LONG, Lane.INT, analysis)` emits
   `convertShape(L2I, <int species>, 0)`: the values land in the low half of the int lanes and
   the upper half is zero. The species comes from `emitConstantSpecies`, which is private. A
   second `IntVector` species in the JVM "makes the shared templates inline bimorphically"
   (`VarkaBodyEmitter.emitNarrowStore`'s comment).
6. **So the gather brings a second int species into the JVM, inside the JDK** (facts 3 and
   5), and the record says that can box. `sql/varka/skills/vector-api-and-width.md`: "an
   index-map gather boxes under either order at both widths". That is live on JDK 25:
   `VarkaAssemblySuite`'s self-test "the same gather boxes once a second species has been
   through the templates" asserts it at HEAD, and `VarkaAssemblyProbe.gatherLookup`'s comment
   says which shapes show it: "a bare add stays clean in a polluted JVM", an int index-map
   gather boxes. The operator census of JDK 25 on the laptop
   (`sql/varka/operator_census.json`) has no cell for a gather and none for an int `MIN`.
   Its cells `long.LSHR`, `long.ASHR` and `long.L2I` read `I` at 512, 256 and 128 bits;
   `long.GT`, a compare to a mask, reads `I` at 512 and 256 and `F` at 128.
7. **At two 64-bit lanes on x86 no mask compiles, of any element type.**
   `VarkaKernelPlan.twoLaneMasksFallBack` is true on x86 with two long lanes or fewer; its
   comment cites `match_rule_supported_vector` in `x86.ad`, which refuses the mask nodes at
   `vlen == 2`. The gather's own index check (fact 3) is a mask of two int lanes there, and a
   compare of the lookup is a mask of two long lanes. In `results/forms.txt` the probe's table
   ran at 38.864 and 39.334 ns a row there, slower than `java.time`'s 10.956.
8. **One lowering reads and replaces a lane at a time today:**
   `VarkaConversionLowering.emitScalarLanes`, with `VarkaDescriptors.LONG_LANE`; it needs a
   baked lane count. Emitted code calls a static method of a catalyst class
   (`VarkaScratch.forRows`), and the executor can read its own host: `VarkaHostIsa.host()`.
9. **A kernel's long arguments are copied in one place:** `VarkaKernelWarmup`'s job clones
   `longArgs` when a warm-up starts. A reproduction file holds the long list as text.
10. **The memory sanitizer sees mappings of native memory, not arrays:** "every mapping made
    on that thread". An index into a Java array is checked by the JVM instead (fact 3).
11. **The probe's table and its facts.** `ZoneForms.table` shifts `v.sub(BASE)` by `BUCKET`,
    converts with `L2I`, stores with `intoArray` and gathers three arrays, with no clamp.
    `facts.txt`: "11482 buckets, 89 KB at 8 bytes a bucket, 269 KB as ZoneForms' three
    arrays"; "closest two changes of one zone: 601200 s apart".
12. **Proof files.** A renderer of `VarkaProofFiles` writes a header, `rendered(logic)` and
    one `check(out, what, expect, body)` a check; `VarkaProofFilesSuite` rewrites a file
    under `VARKA_PROOFS_REGEN=true`; `dev/varka_prove.sh` pins Z3 5.1.0 and cvc5 1.4.1 and
    inserts `java.smt2`, which has `jlong.sub`, `jlong.ushr`, `jlong.shl`, `jlong.shr`,
    `jint.shl`, `l2i` and `jlong.and.low`.
13. **On AArch64 C2 has a gather only with SVE.** In JDK 25's `aarch64_vector.ad`,
    `Matcher::match_rule_supported_vector` returns false for `Op_LoadVectorGather` when
    `UseSVE == 0`, and `vm_version_aarch64.cpp` takes `sve` out of the feature list, which
    `VarkaHostIsa.Reading.features` holds; `hasFeature` reads one name of it. The fleet's
    Arm runner has SVE: the skills file records "Neoverse N2, ASIMD with SVE2", and that
    "the runner's JDK enables it".

The commands, by fact (`H` is `/home/max/proj/openjdk-build/jdk25/src/hotspot/cpu/aarch64`):

    grep -c 'a long-lane kernel never reads it' $V/VarkaFusedKernel.java; grep -c 'default int scratchBytesPerRow() {' $V/VarkaFusedKernel.java; grep -c 'public final int\[\] scalarArgs;' $E/varka/VarkaKernelRunner.java   # 1: 1, 1 and 1
    grep -c 'LongVector fromMemorySegment(VectorSpecies<Long> species,' $J/LongVector.java; grep -A4 'LongVector fromMemorySegment(VectorSpecies<Long> species,' $J/LongVector.java | grep -c indexMap   # 2: 2 and 0
    grep -c 'IntVector.IntSpecies isp = IntVector.species(vsp.indexShape());' $J/LongVector.java; grep -c 'vix = VectorIntrinsics.checkIndex(vix, a.length);' $J/LongVector.java; grep -c 'VECTOR_ACCESS_OOB_CHECK", 2' $J/VectorIntrinsics.java   # 3: 4, 4 and 1
    grep -rl 'indexMap\|loadWithMap' $V/.. $E/varka $E/Varka*.java | wc -l                    # 4: 0
    grep -c 'private static void emitConstantSpecies(CodeBuilder cb, Lane lane, Analysis analysis)' $V/VarkaVectorWalk.java   # 5: 1
    grep -ci 'gather' $T/VarkaOperatorCensusCatalog.java; grep -c '"bin.int"' $T/VarkaOperatorCensusCatalog.java   # 6: 0 and 0
    grep -c 'index-map gather boxes' sql/varka/skills/vector-api-and-width.md; grep -c 'the same gather boxes once a second species' $TS/VarkaAssemblySuite.scala; grep -c 'a bare add stays clean in' $T/VarkaAssemblyProbe.java   # 6: 1, 1 and 1
    grep -c 'return host.x86() && preferredLongLanes <= 2;' $V/VarkaKernelPlan.java; grep -c 'table, three gathers' sql/varka/spikes/zone-offset/results/forms.txt   # 7: 1 and 6
    grep -c 'private static void emitScalarLanes(CodeBuilder cb, int v, Analysis analysis)' $V/VarkaConversionLowering.java; grep -c 'static final MethodTypeDesc LONG_LANE =' $V/VarkaDescriptors.java; grep -c '"forRows"' $V/VarkaLoopEmitter.java; grep -c 'public static Reading host()' $V/VarkaHostIsa.java   # 8: 1, 1, 1 and 1
    grep -A2 'case Op_LoadVectorGather:' $H/aarch64_vector.ad | grep -c 'UseSVE == 0'; grep -A2 'if (UseSVE < 1)' $H/vm_version_aarch64.cpp | grep -c 'CPU_SVE;'; grep -c 'Neoverse N2, ASIMD with SVE2' sql/varka/skills/vector-api-and-width.md   # 13: 1, 1 and 1
    grep -c 'this.longArgs = longArgs.clone();' $V/VarkaKernelWarmup.java; grep -c 'every mapping made on that thread' $V/VarkaMemorySanitizer.java   # 9 and 10: 1 and 1
    grep -c '11482 buckets' sql/varka/spikes/zone-offset/results/facts.txt; grep -c 'static void check(StringBuilder' $T/VarkaProofFiles.java   # 11 and 12: 1 and 1

**The proof sketch was run.** The 16 checks of 4.10 ran on 26 November 2026 under the pinned
Z3 5.1.0 and cvc5 1.4.1 with the logic `ALL`: each answered as 4.10 expects, each solver in
under a second for the file. Under `QF_NIA` cvc5 gave no answer after two minutes.

**What could not be checked.**

- No name of rows 682, 684, 657 or 685 is on master (`git grep -w -c
  'ToLocal\|VarkaBatchArgs\|VarkaZoneData' -- sql/catalyst/src sql/core/src` prints nothing).
  Row 685's names `VarkaZoneLadder`, `RUNGS`, `emitted` and `widest` are from its plan.
- Whether C2 compiles the gather with no allocation beside an int kernel (fact 6): part a's
  first step reads it, and stops the row if not. Whether it compiles `lane` and `withLane`
  of a long vector so at two lanes (4.7): test 13 reads it.
- Every time of section 7; the gather's cost on the EPYC 7763 and on the Arm runner; row
  678's data as built; whether a driver of `VarkaBodyEmitter` can map a column's segment
  before its loop methods (4.3): part b's step 4 stops if not.

## 4. The design

### 4.1 Where the table lives: inside `longArgs`, after the batch arguments

The table of one zone node is its program: a run of `longArgs`. Under `TABLE` the count of a
`ToLocal` program is `VarkaZoneTable.LONGS`, 22964 longs, 183712 bytes. The kernel gathers
from `longArgs` itself, with the program's first index as the gather's `offset`.

| where | what it would take | verdict |
| :--- | :--- | :--- |
| inside `longArgs`, as the node's program | no new parameter; the layout of row 682 numbers it; the shape key does not change, since it holds `numLiterals` and the options | chosen |
| an array of its own, a new parameter of `run` | a new descriptor for every kernel, the interface's four `run` forms, every caller and every suite that calls a kernel (fact 1) | rejected |
| native memory, its address a batch argument | the Vector API has no gather from a segment (fact 2), so each lane would be read by a scalar load | rejected for this form; see 4.7 |

The runner owns `longArgs`, and a runner is one task's. A table is written once, when the
runner is built: it does not depend on the batch, and no task reads another's. The warm-up
clones the array when it starts (fact 9): 183712 bytes more for the life of one warm-up
job. A reproduction file holds the table as text, under half a megabyte.

**The index map.** The gather reads its indices from an `int[]` (fact 3). A `TABLE` kernel
takes that array as its `scalarArgs` parameter, which a long-lane kernel does not read (fact
1); every kernel with a zone node is a long-lane kernel. The runner owns the array:

- `VarkaFusedKernel.indexMapIntsPerRow()` is a default method that answers 0, as
  `scratchBytesPerRow()` does. A `TABLE` kernel answers its count of programs,
  `VarkaBatchArgs.layout(...).size()`: program `j` keeps row `i`'s index at `j * length + i`.
- `VarkaZoneTable.indexMapInts(perRow, length)` is `perRow * length + INDEX_SLACK`, and
  `INDEX_SLACK` is 16: the store of 4.3 writes a whole int vector, 16 ints at 512 bits, and
  an epilogue's gather reads a lane group of ints from its first row. An int past a
  program's run is a zero of a new array or an index that some batch wrote, so a gather of
  it reads inside the table.
- `VarkaKernelRunner` gains the field `private int[] indexMap`, empty at first. Before a
  call of a kernel whose count is not 0, it takes a new array of `indexMapInts(perRow, len)`
  ints where the field is shorter, and passes the field in the place of `scalarArgs`, in
  `run` and in `checkOtherBody`. So the map grows to the longest batch the runner has seen,
  and no batch after that one allocates: the house rule holds. It costs 4 bytes a row and a
  program, 16 KB at 4096 rows.
- The kernel assumes no length: its driver reads `length` and `scalarArgs.length`, and
  throws `IllegalArgumentException`, naming both, where the array is shorter than
  `indexMapInts`. The warm-up, the replay of a reproduction and `VarkaEmitterFixtures` size
  the array from the kernel's method, as they size the scratch.

**What the second probe measured and this row leaves out** (section 2; ns a row over ten
years at eight and at four lanes):

| form | ns a row | verdict |
| :--- | :--- | :--- |
| the indices of the whole batch, in a pass before the gathers | 0.621, 0.829 | chosen |
| the indices beside each gather, in a scratch of one lane group | 0.673, 0.871 | slower; the form for a computed operand, if row 688 needs one |
| the same, in a new array each call | 0.675, 0.866 | no faster than the scratch, and it allocates per batch |
| the indices of 16 lane groups first; of 4 | 0.639, 0.846; 0.664, 0.942 | slower than the whole batch |
| the store of the next group's indices issued one group ahead, two arrays | 0.820, 1.136 | a measured loss at both widths |
| three arrays, three gathers | 0.773, 0.882 | slower at eight lanes, and 1.001 against 0.704 over the whole table |

At batches of 64 rows the whole-batch form costs 0.913 and 1.102 against 0.970 and 1.152
beside the gather, and at 10000 rows 0.624 and 0.786 against 0.675 and 0.868
(`tables-64.txt`, `tables-10000.txt`, one run each). Not built, and the next knob only: the
compare and the last sum folded into the first pass, with a second buffer a batch long.

**The second int species** (fact 6) is the row's first risk. Part a's first step adds four
cells to the operator census and reads them. The row goes on only if the gather reads `I`
with no allocation, and an int index-map gather compiled after it does too, at 256 bits:
the shape that boxes whenever its templates are polluted (fact 6). If not, stop:
the lead decides between `TABLE_SCALAR` (4.7) at every width and leaving the gathers out.

### 4.2 The bucket and its constants: `VarkaZoneTable`

A new final class `V/VarkaZoneTable.java`, with no state:

    public static final int SHIFT = 39;                       // a bucket is 2^39 microseconds
    public static final long ORIGIN = -2_208_988_800_000_000L;   // 1900-01-01T00:00:00Z
    public static final long LIMIT = 4_102_444_800_000_000L;     // 2100-01-01T00:00:00Z
    public static final int BUCKETS = 11_482;
    public static final int LONGS = 2 * BUCKETS;              // 22964
    public static final int UNIT_SHIFT = 6;                   // an offset unit is 64 microseconds
    public static final int UNITS_A_SECOND = 15_625;          // 1_000_000 >> UNIT_SHIFT
    public static boolean covers(long least, long greatest)   // ORIGIN <= least, greatest < LIMIT
    public static int index(long instant)                     // the clamped bucket, 0 to BUCKETS - 1
    public static long word(int beforeSeconds, int afterSeconds)
    public static long offsetMicros(long word, boolean after)
    public static long toLocal(long[] args, int first, long instant)   // the scalar lookup
    public static boolean pack(long[] at, int[] offsetSeconds, long[] args, int first)
    public static int tableFromChanges(int longLanes)         // 9, see 4.8
    public static final int INDEX_SLACK = 16;                 // see 4.1
    public static int indexMapInts(int perRow, int length)    // perRow * length + INDEX_SLACK
    public static boolean gatherFallsBack(VarkaHostIsa.Reading host)   // see 4.7

- **The buckets.** `BUCKETS` is the probe's count, `((LIMIT - ORIGIN) >>> SHIFT) + 2`, where
  the shift gives 11480. A live instant, from `ORIGIN` to `LIMIT - 1`, has a bucket from 0 to
  11480. Bucket 11481 is spare: no live instant reads it, and a clamped index may land on it.
- **A bucket is two longs.** With `b` the bucket and `first` the program's first index, word
  0, `args[first + 2 * b]`, is `at`: the instant of the bucket's change, or `Long.MAX_VALUE`
  where the bucket holds none. Word 1, `args[first + 2 * b + 1]`, holds the offset before the
  change in its high 32 bits and the offset after it in its low 32 bits, each a signed int in
  units of 64 microseconds. A bucket with no change holds the offset in force in both halves.
- **Why units of 64 microseconds.** Two offsets in microseconds, 38 bits each, do not fit
  one long, and a multiply of 64-bit lanes is no single instruction below AVX-512. An offset
  is whole seconds, and a second is 15625 times 64 microseconds: so an offset is seconds
  times 15625, at most 1012500000 in size, an int, and a shift by 6 gives microseconds.
- `word(before, after)` is `((long) (before * UNITS_A_SECOND) << 32) | ((after *
  UNITS_A_SECOND) & 0xFFFFFFFFL)`; it throws `IllegalArgumentException` for a second count
  outside plus and minus 64800. `offsetMicros(word, after)` is `((after ? word : word >>> 32)
  << 32) >> 26`: the chosen half at the top, then a shift that extends its sign.
- `index(instant)` is `Math.min((int) ((instant - ORIGIN) >>> SHIFT), BUCKETS - 1)`. The
  subtraction wraps near an end of a long; the logical shift then gives 0 to 2^25 - 1 for
  every long, so the cast keeps it and the `min` brings it into the table (4.10).
- `toLocal(args, first, x)` is `x + offsetMicros(args[first + 2 * b + 1], x >= args[first + 2
  * b])` with `b = index(x)`: the definition the kernel, the tests and the benchmark share.
- `pack(at, offsetSeconds, args, first)` writes the `LONGS` longs. `at` holds the zone's
  changes inside `[ORIGIN, LIMIT)` in microseconds, ascending; `offsetSeconds` has one entry
  more, the offset before the first change, then the offset after each. It returns false, and
  the zone then has no table, when two changes fall in one bucket. It throws
  `IllegalArgumentException` for lengths that do not match and for a change outside the
  range or out of order. It runs once a runner or once a zone, never per batch.

### 4.3 The lowering

`VarkaZoneLowering` gains two methods for `TABLE`. `emitToLocalIndex` is the index pass: the
kernel's driver runs it once a batch for each program, before it calls a loop method.
`emitToLocalTable` is the arm of `emitToLocal` in every body. With `v` the operand's vector,
`LS` the long species and `IS` the int species of the same width (fact 5), `args` the
kernel's `longArgs`, `first` the program's first index, a constant of the emitted class,
`map` the `scalarArgs` parameter, `j` the program's place in the layout and `i` the first
row of a lane group, so that `m` is `j * length + i`. Calls 1 to 6 are the pass, calls 7 to
14 the body:

| # | Vector API call | gives |
| ---: | :--- | :--- |
| 1 | `v.sub(ORIGIN)` | `t`, wrapping |
| 2 | `t.lanewise(LSHR, 39L)` | `u`, from 0 to 2^25 - 1 |
| 3 | `u.convertShape(L2I, IS, 0)` | the same as ints, in the low half of `IS` |
| 4 | `.min(BUCKETS - 1)` | the clamped bucket |
| 5 | `.lanewise(LSHL, 1)` | twice the bucket: the index of word 0 inside the program |
| 6 | `.intoArray(map, m)` | the lane group's indices in the map |
| 7 | `LongVector.fromArray(LS, args, first + 1, map, m)` | `w`, word 1 of each lane's bucket |
| 8 | `w.lanewise(LSHR, 32L)` | `h`: the offset before, in the low half |
| 9 | `LongVector.fromArray(LS, args, first, map, m)` | `at`, word 0 |
| 10 | `v.compare(GE, at)` | the mask of lanes at or after their change |
| 11 | `h.blend(w, mask)` | the chosen half in the low 32 bits |
| 12 | `.lanewise(LSHL, 32L)` | the half at the top |
| 13 | `.lanewise(ASHR, 26L)` | the offset in microseconds |
| 14 | `.add(v)` | the local time |

So `laneCalls(TABLE)` is 14, and no call is on `VectorMask`.

**The pass.** The operand of a zone node is a column: a computed operand is refused under
every form (`ZONE_OPERAND`), and row 688 decides its form under `TABLE`. The driver maps the
column's segment as a body maps it and runs calls 1 to 6 over `length` rounded down to a
lane group. It writes the rows left one at a time: the long read from the segment,
`invokestatic VarkaZoneTable.index`, a shift by 1 and an `iastore`. A null row's storage is
read as any row's. Programs run in the layout's order, so the ints that a store writes past
program `j`'s run are written again by program `j + 1`'s pass or lie in the slack. The
driver's return for a batch with no live row comes first.

The body keeps `v` in the node's `zoneTmp` (`dup`, `astore`) and uses descriptors the
emitter has: `Lane.LONG.lanewiseVI` for the `sub` and `Lane.INT.lanewiseVI` for the `min`;
`Lane.LONG.integral().lanewiseBinaryI()` and the int lane's for the shifts;
`VarkaVectorWalk.emitShapeConversion(cb, Lane.LONG, Lane.INT, analysis)` for call 3;
`Lane.LONG.compareVV`, `Lane.LONG.blend` and `Lane.LONG.lanewiseVV` for calls 10, 11 and 14.
Two descriptors are new: `IntVector.intoArray(int[], int)` and the static
`LongVector.fromArray(VectorSpecies, long[], int, int[], int)`, whose species is
`emitConstantSpecies(cb, Lane.LONG, analysis)`. Stack order in the body: call 7 leaves `w`;
`dup`, call 8, `swap` leave `h, w`; `aload zoneTmp`, `getstatic GE`, call 9 and call 10
leave `h, w, mask`; then calls 11 to 13, `aload zoneTmp` and call 14.

The same bytecode serves every body: dense, masked and the epilogue. A lane the body's mask
leaves out holds zero or a null row's storage, and its clamped index reads inside the table.
The gather is never masked. An epilogue's gather reads a lane group of the map from its
first row, and the ints past the batch's end are in the slack (4.1).

### 4.4 The layout, and what the emitter must not do

- `VarkaBatchArgs.count(node, form)`: for a `ToLocal`, `VarkaZoneTable.LONGS` under either
  table form, else `1 + 2 * form.steps()`. `layout` is unchanged: programs follow each
  other, so the second zone node's table starts `LONGS` after the first's.
- **`steps()` of a table form throws** `IllegalStateException("the table form has no
  steps")`; the constants are `TABLE(-1)` and `TABLE_SCALAR(-1)`. A reader written for the
  step forms would take a count of 0 for `ONE`'s: the fill, the property suite's oracle, the
  ladder's rule. So every reader tests for a table form first.
- **No table entry becomes a local.** Row 682's `emitBatchArgs` loads every argument of a
  program into a long local: 22964 locals a node here. So under a table form `Slots.plan`
  gives a zone node an empty `batchArgLocal` and its `zoneTmp`, and `emitBatchArgs` emits
  no load. Under `TABLE` the driver emits, after its sizes and its return for a batch with
  no live row, the length check of 4.1 and one index pass a program (4.3).
  `Analysis.indexMapIntsPerRow()` is what the emitted class's method returns.
- **`VarkaBatchArgs.neutral` is never called for a table program, and refuses one:** its
  zero base and padding steps would overwrite buckets, and a batch with no live row needs
  nothing. The callers branch first, and `neutral` throws `IllegalArgumentException` for a
  program whose count is `VarkaZoneTable.LONGS`.
- `VarkaEmitBudget.weightOf` returns `laneCalls(form)` already. `emitConstantSpecies` of
  `VarkaVectorWalk` loses `private`, for the lowering.

### 4.5 Where the table's data comes from

`VarkaZoneData` gains one method:

    /** Writes this zone's bucket table, VarkaZoneTable.LONGS longs from first; false when the
     *  zone has no table, because two of its changes fall in one bucket. */
    boolean toLocalTable(long[] args, int first);

- **The reference**, `VarkaJavaTimeZoneData` in test scope: it walks
  `ZoneRules.nextTransition` from the instant of `ORIGIN` while the change's epoch second is
  below `LIMIT / 1_000_000`, takes `at` as the epoch second times 1000000 and the offsets from
  `getOffsetBefore()` and `getOffsetAfter()`, and calls `VarkaZoneTable.pack`.
- **Row 678's production data** must answer the same method: it gives `pack` its changes
  inside `[ORIGIN, LIMIT)` as sorted instants with their offsets, which row 678's line names
  ("its changes sorted on both time lines"). It may build the packed table once a zone in
  each JVM, lazily, and answer by `System.arraycopy`; it must not ship a table from the
  driver. `m9/PLAN.md` 2.2 prices the table at "89 KB at eight bytes a bucket"; the packed
  bucket is 16 bytes, so the table is twice that.
- **The runner** (`VarkaKernelRunner`, in `sql/core`). The record that `newRunner` builds for
  a program gains a last component `boolean table`. When the runner is built, each table
  program calls `zone.toLocalTable(longArgs, first)` once and keeps the answer in a
  `boolean[]` field. `fill`, for a table program: when no row is live, nothing; when the
  zone has no table, or `!VarkaZoneTable.covers(least, greatest)`, decline with
  `STATUS_BATCH_ARGS`; else nothing. It never calls `neutral`, `changes` or `toLocalArgs`.

### 4.6 Edge cases

| case | what happens | what holds it |
| :--- | :--- | :--- |
| a null row over any long | its lane runs: the index is clamped, the gather reads inside the table, the sum may wrap, and the output's validity hides the row | checks 2 and 3 of 4.10; the suite's null rows over storage outside the table |
| an instant before 1900 or from 2100, live | `covers` is false. Under the ladder the batch takes a step form if at most 8 changes are in its range, which holds for any batch inside one stretch of a few years; else it declines with `STATUS_BATCH_ARGS` and takes the row path. A forced `TABLE` declines it | the runner's tests; the sweep's test of both ends |
| the ends of a long | never live under a table form; as a null row's storage, see the first line | the same |
| a zone with a fixed offset | no node at all: row 657 folds it | - |
| a batch with no live row | no fill, the kernel runs, every row is null | the runner's test of an all-null batch between two live ones |
| two lanes of 64 bits on x86; AArch64 without SVE | the ladder takes no table form there until row 691 decides; a forced `TABLE_SCALAR` has no gather; a forced `TABLE` is right and slow: 4.7 | tests 14 and 15 |
| a batch longer than any before it | the runner takes a longer map before the call; a kernel given a short map throws by name | the runner's test of four batch lengths; test 14 |
| a batch shorter than a lane group | the pass writes every index one row at a time, and the epilogue's gather reads the slack past them | test 8's lengths |
| AVX2 against AVX-512 | the same bytecode. AVX2 has no 64-bit arithmetic shift and C2 emulates call 13; the gather is one AVX2 instruction on both | the census cells; the benchmark at both widths |
| a zone whose two changes share a bucket | `pack` answers false; every batch of a forced `TABLE` declines; the ladder never picks `TABLE` for it. No zone of time-zone data 2026f is one (`facts.txt`) | the suite's made-up changes; the runner's test with a wrapper |
| `-Djdk.incubator.vector.VECTOR_ACCESS_OOB_CHECK=0` | the JVM's check is off, and the clamp alone keeps a null row's index inside the table | checks 2 and 3 of 4.10 |

### 4.7 A host whose C2 has no gather: scalar loads, `TABLE_SCALAR`

At two lanes on x86 the gather's index check is a mask that C2 does not compile (fact 7),
and on AArch64 without SVE C2 has no gather (fact 13). In the second probe (`tables.txt`,
`tables-2.txt`) every gathered form at two lanes costs more than twice the 10.956 ns a row
of `java.time`'s offset (`forms.txt`), as the first probe's three gathers do (fact 7). So
the row builds a second table form, with no gather, no index map and no mask:

- **`ZoneForm.TABLE_SCALAR`**, lowered by `emitToLocalTableScalar`. The operand `v` stays in
  `zoneTmp` and the result starts as `v`. For each lane `k` of the long species: `v.lane(k)`,
  `invokestatic VarkaZoneTable.toLocal(args, first, x)`, and `withLane(k, <that>)` on the
  result. It has the shape of `VarkaConversionLowering.emitScalarLanes` (fact 8) and needs a
  baked lane count, as that does. `laneCalls(TABLE_SCALAR)` is 16, two calls for each of the
  most lanes. The table and its fill are `TABLE`'s; `indexMapIntsPerRow()` is 0.
- **The host's flag.** `VarkaZoneTable.gatherFallsBack(host)` is
  `VarkaKernelPlan.twoLaneMasksFallBack(host)`, or an `arch` of `VarkaHostIsa.AARCH64` whose
  list was read (`readable()`) and has no `sve` (`hasFeature`). A list not read answers false.
- **The choice is static.** The ladder (4.8) reads the flag once on the executor, with no
  probe at run time. Neither form is refused anywhere, so a suite and the option matrix emit
  both on any host; a forced `TABLE` on a host with no gather is right and slow.
- **Not on the ladder at two lanes yet.** The probe's 8.421 ns a row at two lanes is the
  offset alone, from a loop that still compares in vectors, and `java.time`'s offset costs
  10.956 there (`forms.txt`): a small margin. A whole kernel has more to gain: the row path
  of `hour` costs 25.360 at two lanes (`results/kernels.txt`). Row 691 measures the `TABLE_SCALAR`
  kernel against the row path and puts it on the ladder for such a host if it wins (P12).
  Until then the ladder stops at `STEPS_8` there, and a batch of more than 8 changes
  declines with `STATUS_BATCH_ARGS`. Where a gather is compiled the scalar loads lose, 1.686
  and 1.425 against 0.621 and 0.829.
- **The Arm runner** has SVE (fact 13), so the flag is false there and test 13 measures its
  `TABLE` kernel. No census of a gather was read there: a failure of test 13 on it is a stop.

### 4.8 The ladder, and the threshold

- **Order.** The ladder's forms, cheapest first: `ONE`, `STEPS_2`, `STEPS_8`, then `TABLE`
  where the host's flag (4.7) is false, and no table form where it is true. `TABLE` covers
  a program when the zone has a table and `VarkaZoneTable.covers(least, greatest)`; it does
  not look at the count of changes.
- **The threshold.** `VarkaZoneTable.tableFromChanges(longLanes)` is the count of changes
  from which the ladder takes `TABLE` although a step form covers. This row returns 9 for
  every lane count, one more than `STEPS_8` covers; row 691 measures the cross-over in
  kernels and sets the value for each width, which the house allows since row 600.
- **Why 9 until then.** In the probes the table loses to 8 steps at both widths: 0.829
  against 0.755 at four lanes and 0.621 against 0.289 at eight (the whole-batch form of
  `tables.txt`, the steps of `forms.txt`). A `STEPS_8` kernel runs its 8 steps whatever the
  batch holds, so the cross-over may lie below 8 changes. A prediction moves no default.

### 4.9 What this row leaves for row 690, and what declines by name

`ToInstant` and `ToInstantKept` read a local time, so their table is indexed on the local
time line, with the local thresholds of `VARKA-657.md` 2.4. Row 693 builds it, on this row's
constants, `word`, `offsetMicros`, `pack`, index map and read of a table program. `facts.txt`
has the fact a local bucket needs: "closest two local thresholds of one zone: 601200 s apart".

- If `ToInstant` exists when part b starts, its B0 stops and the lead adds the refusal of a
  table over it. If not, `VarkaBatchArgs.count`'s switch has the `ToLocal` arm alone, and row
  690's records fail to compile there until that row decides.
- A zone node over a computed operand stays refused under every form (`ZONE_OPERAND`). A
  batch that the table does not cover declines with `STATUS_BATCH_ARGS`; no new status.

### 4.10 `zone_bucket.smt2`

Rendered by `VarkaProofFiles.zoneBucket()`, logic `ALL`, over `java.smt2`. Its constants
come from `VarkaZoneTable`, and the renderer throws `IllegalStateException` where `SHIFT` is
not 39, `BUCKETS` is not `((LIMIT - ORIGIN) >>> SHIFT) + 2`, or `UNITS_A_SECOND << UNIT_SHIFT`
is not 1000000. The header says what 4.2 and 4.3 say, and why the logic is `ALL` (section 3).
The fragments:

    O = -2208988800000000   LIM = 4102444800000000   N = 11482   B = 549755813888 (2^39)
    U = 1012500000 (64800 * 15625)                  P = 4294967296 (2^32)
    idx(x)     = (ite (< I (- N 1)) I (- N 1)) with I = (l2i (jlong.ushr (jlong.sub x O) 39))
    low(a)     = (ite (>= a 0) a (+ a P))            signed32(l) = (ite (>= l 2147483648) (- l P) l)
    W          = (declare-const h Int) (declare-const l Int)
                 (assert (and (<= (- 2147483648) h) (<= h 2147483647) (<= 0 l) (< l P)))
                 (define-fun w () Int (+ (* h P) l))
    unit64(s)  = (jlong.shr (jlong.shl s 32) 26)
    AB         = (declare-const b Int) (declare-const a Int)
                 (assert (and (<= (- U) b) (<= b U) (<= (- U) a) (<= a U)))

| # | what | expect | body |
| ---: | :--- | :--- | :--- |
| 1 | the bucket of an instant from 1900 to 2100 is its floor quotient by 2^39 from the origin, from 0 to BUCKETS - 2 | unsat | `x` with `O <= x < LIM`; not (`idx(x) = (div (- x O) B)` and `0 <= idx(x) <= N - 2`) |
| 2 | the clamped bucket of every long is inside the table | unsat | `x` a long; not `0 <= idx(x) <= N - 1` |
| 3 | before the clamp the shifted difference of every long is from 0 to 2^25 - 1, so the cast to an int keeps it | unsat | `x` a long; not `0 <= (jlong.ushr (jlong.sub x O) 39) < 33554432` |
| 4 | twice the clamped bucket does not wrap an int, and it and its neighbour are inside the program's run | unsat | `x` a long; not (`(jint.shl idx(x) 1) = 2 * idx(x)` and `2 * idx(x) + 1 <= 2 * N - 1`) |
| 5 | with no clamp an index leaves the table | sat | `x` a long; `I >= N` |
| 6 | the last instants before 2100 are in bucket BUCKETS - 2, so a table two buckets shorter would clamp them into the bucket before; one bucket shorter loses the spare alone | sat | `x` with `O <= x < LIM`; `(div (- x O) B) > N - 3` |
| 7 | an offset in whole seconds within 18 hours, in units of 64 microseconds, fits the half and gives its microseconds | unsat | `s` with `-64800 <= s <= 64800`; not (`64 * (s * 15625) = s * 1000000` and `-U <= s * 15625 <= U`) |
| 8 | the low half of a packed word is the offset after's low 32 bits, the shifted offset before is a multiple of 2^32, and the word is a long | unsat | AB; not (`(jlong.and.low a P) = low(a)` and `(jlong.shl b 32) = b * P` and `(jlong.in (+ (* b P) low(a)))`) |
| 9 | the signed value of an offset's low 32 bits is the offset | unsat | `a` with `-U <= a <= U`; not `signed32(low(a)) = a` |
| 10 | a word's low half moved to the top and shifted back by 26 is 64 times its signed value | unsat | W; not `unit64(w) = 64 * signed32(l)` |
| 11 | the logical shift by 32 leaves the high half's low 32 bits | unsat | W; not `(jlong.ushr w 32) = low(h)` |
| 12 | the select: at or after the change the low half, before it the high half | unsat | W, `x` and `at` longs; `sel = (ite (>= x at) w (jlong.ushr w 32))`; not `unit64(sel) = 64 * (ite (>= x at) signed32(l) h)` |
| 13 | a half one past an int does not unpack | sat | `a = 2147483648`; not `signed32((jlong.and.low a P)) = a` |
| 14 | two changes 601200 s apart, the closest of any zone, are in two buckets | unsat | `t`, `u` with `O <= t`, `u < LIM`, `u - t >= 601200000000`; `(div (- t O) B) = (div (- u O) B)` |
| 15 | two changes 2^39 - 1 microseconds apart can share a bucket | sat | the same with `u - t = 549755813887` |
| 16 | an instant from 1900 to 2100 plus an unpacked offset does not wrap | unsat | `x` with `O <= x < LIM`, `a` with `-U <= a <= U`; not `(jlong.add x (* 64 a)) = x + 64 * a` |

Checks 1 to 6 are the index; 5 and 6 show that the clamp and the last live bucket are
needed. Checks 7 to 13 are the packed word, 14 and 15 the bucket's width against the data's
fact, and 16 the sum, emitted with no overflow check. A no-change bucket gives one offset
for every long by check 12, since its halves are equal. The equality with `java.time` is no
SMT claim: it is a property of the JVM's data, and the sweep holds it.

## 5. The parts

Four pull requests: the bucket and its proof; the kernel; the ladder and the sweep; the
measurement, apart because the lead dispatches it. Each sheet follows
`sql/varka/plans/m8/BUILD_SHEETS.md` in full. This file merges first, in a plan-only pull
request, so no part counts its lines. A JUnit run prints sbt's `Passed: Total N` line; in
"n + 3", n is the count B0 printed. No build branch is named `varka-686`, which held the
plan. Every shell starts with:

    export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
    V=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
    E=sql/core/src/main/java/org/apache/spark/sql/execution
    T=sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka
    TS=sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/expressions/codegen/varka
    S=sql/core/src/test/scala/org/apache/spark/sql/execution

### 5.1 Part a: the bucket, its data and its proof

Title `[VARKA-686] Pack a zone's offsets into 16-byte buckets, with their proof`; branch
`varka-686a-bucket`. About 600 counted lines.

**B0, the premises.** Each prints what its comment says, or the builder stops.

    ls $V/VarkaZoneTable.java sql/varka/proofs/zone_bucket.smt2 2>/dev/null | wc -l            # 0
    grep -c 'void toLocalArgs(long least, long greatest, int steps' $V/VarkaZoneData.java; ls $T/VarkaJavaTimeZoneData.java | wc -l   # 1 and 1
    git grep -l 'implements VarkaZoneData' -- sql/catalyst/src sql/core/src                   # every implementer: note them
    grep -c 'files.put("int_round.smt2"' $T/VarkaProofFiles.java                              # 1
    grep -c 'files.put("zone_step.smt2"' $T/VarkaProofFiles.java                              # 1, or 0 before row 684's part 2
    grep -ci 'gather' $T/VarkaOperatorCensusCatalog.java; grep -c 'private static final String TWO_SPECIES = "two species";' $T/VarkaOperatorCensusCatalog.java   # 0 and 1
    grep -c 'static void op_bin_int() {' $T/VarkaOperatorCensusProbe.java; grep -c 'case "bin.long" -> op_bin_long();' $T/VarkaOperatorCensusProbe.java   # 1 and 1

**Steps.**

1. **The census first; it can stop the row.** `$T/VarkaOperatorCensusProbe.java`: two loops
   beside `op_bin_long`, and their cases beside `"bin.long"`. `op_gather_long`, case
   `"gather.long"`: for each lane group, `LongVector.fromArray(LS, LA,
   i).lanewise(VectorOperators.LSHR, 54L).convertShape(VectorOperators.L2I, IS, 0)`, cast to
   `IntVector`, `.min(1023)`, `.intoArray` of a static `int[]` of `IS.length()`; then
   `LongVector.fromArray(LS, <a static long[1024]>, 0, <that int[]>, 0).intoArray(LO, i)`.
   `op_two_intgather`, case `"two.intgather"`: `IntVector.fromArray(IS, <a static
   int[1024]>, 0, <a static int[ROWS] of indices from 0 to 1023>, i).intoArray(IO, i)`, the
   shape of `VarkaAssemblyProbe.gatherLookup` (fact 6). `$T/VarkaOperatorCensusCatalog.java`:
   a family `GATHERS = "gathers"` and, after the last entry of the family `TWO_SPECIES`:

        of("long", "gather", GATHERS, "gather.long", "loadWithMap", "686"),
        of("int", "MIN", GATHERS, "bin.int", "binaryOp", "686"),
        wide("long", "gather_then_int", TWO_SPECIES, "gather.long+two.intgather", "binaryOp", "686", 256),
        wide("long", "int_then_gather", TWO_SPECIES, "two.intgather+gather.long", "loadWithMap", "686", 256),

   Run commands 1 and 2 now. **Stop** if a cell of command 2 does not read as written there,
   and report the four cells at each width.
2. `$V/VarkaZoneTable.java`: new, as 4.2 has it, each constant and method with a comment that
   a reader new to the code can follow, and the class comment naming `zone_bucket.smt2`.
3. `$V/VarkaZoneData.java`: the method of 4.5. `$T/VarkaJavaTimeZoneData.java`: the reference
   of 4.5. Every other implementer that B0 listed: a production class builds `at` and
   `offsetSeconds` from its sorted changes inside `[ORIGIN, LIMIT)` and calls `pack`; a
   wrapper in the tests delegates. If a production class does not hold its changes as sorted
   instants with their offsets, stop.
4. `$T/VarkaProofFiles.java`: `zoneBucket()`, after the last renderer of a hand-written file:
   the header, `rendered("ALL")` and the 16 checks of 4.10, in order, under the texts written
   there. Add `files.put("zone_bucket.smt2", zoneBucket());` after the `zone_step.smt2` line
   if B0 printed 1 for it, else after the `int_round.smt2` line. Render it (command 3) and add
   its row to the table of `sql/varka/proofs/README.md`.
5. `$T/VarkaZoneTableSuite.java`: new, the seven tests below.
6. Row 686 of `m9/PLAN.md`: it opens with `**Started**` and says parts b to d are left.

**Tests.** `VarkaZoneTableSuite`, JUnit 5, `@VarkaSuite(ownJvm = true)`, each test with a
`@DisplayName`. The zone ids and their grouping by rules are `VarkaZoneKernels`' where row 684
has merged; else `ZoneId.getAvailableZoneIds()` grouped by `ZoneId.of(id).getRules()`.

| # | test | the failure only it catches |
| ---: | :--- | :--- |
| 1 | the constants are the probe's: 11482 buckets of 2^39 microseconds from 1900, the last instant before 2100 in bucket 11480 | a table two or more buckets short, whose last live instants the clamp would fold into the bucket before in silence |
| 2 | `word` and `offsetMicros` give back every offset from -64800 to 64800 seconds, as the offset before and as the offset after, beside each other offset of a sample | a half that loses its sign, or the two halves swapped |
| 3 | `index` is inside the table for `Long.MIN_VALUE`, `Long.MAX_VALUE`, `ORIGIN - 1`, `LIMIT`, and 100000 random longs, and equals the floor quotient inside the range | a wrapped subtraction that leaves the table |
| 4 | for every set of rules of the JVM, the reference's table answers `VarkaZoneTable.toLocal` as `java.time` answers at each change before 2100 (a microsecond before, at it, a microsecond after), at the first and the last instant of every bucket below `LIMIT`, at `ORIGIN` and at `LIMIT - 1`; the count of changes read is positive | a bucket that keeps the offset of the bucket before; a change put in the wrong bucket |
| 5 | `pack` answers false for two made-up changes 2^39 - 1 microseconds apart inside one bucket, and throws for changes out of order, a change at `LIMIT`, and arrays whose lengths do not match | a second change of a bucket dropped in silence, which is a wrong answer for newer zone data |
| 6 | a made-up zone with no change and the offset 20700 s: every bucket is `Long.MAX_VALUE` and both halves the one offset | a no-change bucket whose halves differ, wrong at `Long.MAX_VALUE` |
| 7 | every implementer of `VarkaZoneData` in main code writes the reference's table for every zone id, long for long; where main code has none, the test asserts that and reads no zone | production data that differs from `java.time` |

**Commands**, in order.

    VARKA_CENSUS_REGEN=true flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaOperatorCensusSuite'
    python3 -c "import json;d=json.load(open('sql/varka/operator_census.json'))['widths'];[print(b,c,d[b][c]['verdict'],d[b][c].get('bytes_per_row')) for b in sorted(d) for c in ('long.gather','int.MIN','long.gather_then_int','long.int_then_gather') if c in d[b]]"
        # at 256 and 512 each of the four cells prints I with at most 1.0 bytes a row; at 128 long.gather prints F, int.MIN prints I
    git diff -- sql/varka/operator_census.json | grep -c '^-.*verdict'                        # 0: no other cell moved
    VARKA_PROOFS_REGEN=true flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaProofFilesSuite'
    git status --short sql/varka/proofs                                                       # ?? zone_bucket.smt2 and M README.md, nothing else
    grep -c ': expect unsat")' sql/varka/proofs/zone_bucket.smt2; grep -c ': expect sat")' sql/varka/proofs/zone_bucket.smt2   # 12 and 4
    dev/varka_prove.sh --solver both zone_bucket.smt2                                         # z3 and cvc5: 16 checks, <t> s, holds
    dev/varka_prove.sh --lint
    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaZoneTableSuite *VarkaProofFilesSuite'
        # [info] Tests: succeeded 1, failed 0, canceled 0, ignored 0, pending 0
        # [info] Passed: Total 8, Failed 0, Errors 0, Passed 8
    git diff --exit-code -- sql/varka/emitted_bytes.json sql/varka/coverage.json sql/varka/width_audit.json   # exit 0
    python3 dev/varka_push_check.py                                                           # exit 0

**Planted faults** (after the commit, each reverted): `>>> 38` for `>>> SHIFT` in `index`
(tests 1, 3 and 4 fail); the two halves swapped in `word` (tests 2 and 4 fail); `pack` keeping
the first of two changes in a bucket and answering true (test 5 fails).

**Done when** the commands print as above, the three faults fail the named tests, the Outcome
holds the four cells at each width and both solvers' times, and the size count is under 900.
A check that a solver answers `unknown`, or one over 2 s, is a stop. **Not here:** no emit
option value, no `TABLE` anywhere, no change in `sql/core` but an implementer's one method.

### 5.2 Part b: the kernel

Title `[VARKA-686] Lower ToLocal through the bucket table: the form TABLE`; branch
`varka-686b-kernel`. About 800 counted lines. Needs part a and row 684.

**B0, the premises.**

    grep -n 'enum ZoneForm' -A3 $V/VarkaEmitOptions.java                                      # ONE(0), STEPS_2(2), STEPS_8(8); LADDER after row 685
    grep -c 'public static boolean pack(' $V/VarkaZoneTable.java; grep -rc 'ZoneForm.TABLE' $V | grep -vc ':0'   # 1 and 0
    grep -c 'record ToInstant' $V/VarkaVectorIR.java                                          # 0; 1 is a stop (4.9)
    grep -c 'static int count(' $V/VarkaBatchArgs.java; grep -c 'public static void neutral(Program program, long\[\] args)' $V/VarkaBatchArgs.java   # 1 and 1
    grep -c 'static void emitToLocal(\|static int laneCalls(' $V/VarkaZoneLowering.java       # 2
    grep -c 'void emitBatchArgs(' $V/VarkaBodyEmitter.java; grep -c 'batchArgLocal' $V/Slots.java   # 1, and 1 or more
    grep -c 'private static void emitConstantSpecies(CodeBuilder cb, Lane lane, Analysis analysis)' $V/VarkaVectorWalk.java; grep -c 'private static void emitScalarLanes(CodeBuilder cb, int v, Analysis analysis)' $V/VarkaConversionLowering.java   # 1 and 1
    grep -c 'default int scratchBytesPerRow() {' $V/VarkaFusedKernel.java; grep -c 'public final int\[\] scalarArgs;' $E/varka/VarkaKernelRunner.java   # 1 and 1
    git grep -n 'scratchBytesPerRow()' -- sql/catalyst/src sql/core/src                        # every caller of a kernel that sizes its scratch: note them
    grep -n 'toLocalArgs(\|neutral(\|BatchProgram(' $E/varka/VarkaKernelRunner.java $E/VarkaEvaluatorBase.java   # the fill's two calls, the program's record, and where newRunner builds it
    git grep -n '\.steps()\|ZoneForm.values()' -- sql/catalyst/src sql/core/src                # every reader of a form's steps: note them; `ZoneForm.values()` in main code is a stop
    grep -n 'Filler production(' $T/VarkaZoneKernels.java; ls $T/VarkaZoneArgsPropertySuite.java   # one line each
    grep -c '  test("' $S/VarkaBatchArgRunnerSuite.scala                                      # n: note it
    dev/varka_matrix.sh --list | grep zoneForm; grep -rn 'zoneForm=STEPS_8' sql/catalyst/src/test sql/core/src/test sql/varka/matrix   # the arms before this part, and every place that lists them: note them

**Steps.**

1. `$V/VarkaEmitOptions.java`: `TABLE(-1)` and `TABLE_SCALAR(-1)` after `STEPS_8(8)` in
   `ZoneForm`, each with a comment: the bucket table of `VarkaZoneTable`, for a batch of any
   number of changes from 1900 to 2100, read by gathers or by scalar loads of each lane (4.7);
   `steps()` throws for both (4.4). One sentence more in the `@param zoneForm` text. Each
   reader of `steps()` that B0 printed tests for a table form first, in its file's step or here.
2. `$V/VarkaBatchArgs.java`: `count` and the guard of `neutral` (4.4).
3. `$V/VarkaFusedKernel.java`: `indexMapIntsPerRow()` beside `scratchBytesPerRow()`, with a
   comment that says what 4.1 says. `$V/Analysis.java`: its count. `$V/VarkaZoneTable.java`:
   `INDEX_SLACK`, `indexMapInts` and `gatherFallsBack`. `$V/Slots.java`, in `plan`: under a
   table form a zone node gets an empty `batchArgLocal` and its `zoneTmp` (4.4).
4. `$V/VarkaVectorWalk.java`: `emitConstantSpecies` is package-private.
   `$V/VarkaBodyEmitter.java`: `emitBatchArgs` emits no load under a table form; `emitDriver`,
   in each of its forms, emits under `TABLE` the length check and the index passes (4.3, 4.4).
   The emitted class answers `indexMapIntsPerRow()` where it answers `scratchBytesPerRow()`.
   If a driver cannot map a column's segment before its loop methods, stop and report.
5. `$V/VarkaZoneLowering.java`: `emitToLocalIndex` and `emitToLocalTable` (4.3) and
   `emitToLocalTableScalar` (4.7), the two arms reached from `emitToLocal` by a switch over
   the form with no `default`; `laneCalls(TABLE)` is 14 and `laneCalls(TABLE_SCALAR)` is 16.
6. `$E/varka/VarkaKernelRunner.java` and `$E/VarkaEvaluatorBase.java`: 4.5's last point, and
   the runner's map (4.1), with a package-private `indexMapLength()` for the tests. The table
   is written where the runner is built; the fill's table branch comes before the calls B0
   printed. Each caller of a kernel that B0's `scratchBytesPerRow()` line printed (the
   warm-up, the replay, `VarkaEmitterFixtures`) passes a map of `indexMapInts` ints.
7. `$T/VarkaZoneKernels.java`, the filler of `production`: for a kernel of a table form it
   calls `toLocalTable(longArgs, first)` on every fill and answers its result and
   `VarkaZoneTable.covers(least, greatest)`. It is the one filler of the sweep, the property
   suite and the IR fuzzer. `$T/VarkaZoneArgsPropertySuite.java`: its draw of a form includes
   both table forms, and each test gains their arm. Its oracle there: the fill answers
   exactly when the zone has a table and `covers`; `steps()` is not read; each row is
   `VarkaZoneTable.toLocal(args, first, x)` against `java.time`. The step forms' arm stays.
8. `$TS/VarkaEmitterBudgetSuite.scala`: two rows more, as its row of `STEPS_8`: `TABLE` with
   14 lane calls and `TABLE_SCALAR` with 16, each with 0 mask calls. Each place B0's last
   grep printed that lists the arms of `zoneForm` (a pinned list of configurations,
   `VarkaTestReach.FULL`): add `zoneForm=TABLE` and `zoneForm=TABLE_SCALAR`.
9. `$T/VarkaZoneTableSuite.java`: tests 8 to 14. `$T/VarkaZoneTableLanesSuite.java`, new,
   `@VarkaSuite(ownJvm = true)`: test 15. `$S/VarkaBatchArgRunnerSuite.scala`: the eight
   tests below, over the reference's data.
10. `docs/sql-varka.md`: in the sentence on `batch-args` that row 682 wrote, add that under
    the table form a batch with a value before 1900 or from 2100 on runs on Spark's own path.
11. The matrix run of the commands below. A test that fails under a table form's arm because
    it asserts the decline of a batch that spans more changes than a step form covers gets
    this line in `sql/varka/matrix/skips.tsv`, for each arm; any other failure is a stop:

        zoneForm=TABLE	<suite>	<test name>	fails	the table form covers a batch of any number of changes, so the decline this test asserts does not happen

**Tests.** Options from `VarkaMatrixRuns.BASE` with `withZoneForm(ZoneForm.TABLE)`, kernels
emitted and loaded by `VarkaEmitterFixtures`, a hand table from `VarkaZoneTable.pack` of
made-up changes, expected rows from `toLocal`, a map of `indexMapInts` ints. "Both forms"
is `TABLE` and `TABLE_SCALAR`. No test of `VarkaZoneTableSuite` emits at a lane override:
test 13's verdict is a JIT outcome, and a second species in its JVM spoils it.

| # | test | the failure only it catches |
| ---: | :--- | :--- |
| 8 | `(toLocal:0 col:0:long)` over a hand table of three changes, in both forms: rows before, at and after each change, at every length of `VarkaEmitterFixtures.LENGTHS`, dense and with nulls | the two gathers crossed; the blend's arms crossed; a row past the last lane group whose index the pass did not write |
| 9 | in both forms, null rows over storage `Long.MIN_VALUE`, `Long.MAX_VALUE`, `ORIGIN - 1`, `LIMIT + (3L << 39)` and -1, in a `longArgs` that ends with the table: the call returns 0 and throws nothing, null rows are null, live rows are right | an index with no clamp, which the JVM's check turns into an `IndexOutOfBoundsException` and a fallback of every such batch |
| 10 | `(bit:AND (toLocal:0 col:0:long) lit:0:long)`, and two zone nodes over two columns: the first table starts at `numLiterals`, the second 22964 later, each with its own made-up offsets; the kernel of two nodes answers 2 for `indexMapIntsPerRow()` | a gather whose `offset` forgets the literals or the first table; two programs that share one run of the map |
| 11 | `count` is 22964 under both forms, a program after it starts that much later, the shape keys of the two forms differ from each other and from each step form's, and `steps()` of both throws | a kernel that reads past its program; two forms sharing a cached class; a reader that takes a table form for `ONE` |
| 12 | under `TABLE`, `loopDense0`'s bytes and its lane calls are pinned: the 8 calls of the body, none on `VectorMask`, and no `intoArray`; tagged `PinsDefaults` | a lowering that grows unseen; the index computed again in the body |
| 13 | after 30000 calls of 1024 rows with one map, 1000 more calls allocate under one byte a row (`ThreadMXBean.getThreadAllocatedBytes`, as `VarkaAssemblySuite` measures), tagged `C2Code`; the form is `TABLE` where `gatherFallsBack(VarkaHostIsa.host())` is false and `TABLE_SCALAR` where it is true | a gather that boxes, the second int species of fact 6; a kernel that allocates its map; a lane read that boxes |
| 14 | a `TABLE` kernel called with a map one int shorter than `indexMapInts(1, length)` throws `IllegalArgumentException` whose text holds both lengths, and runs with one of that length whose first `length` ints are -1; a `TABLE_SCALAR` kernel and a `STEPS_2` kernel answer 0 for `indexMapIntsPerRow()` and run with an empty array; `gatherFallsBack` is true for an `aarch64` reading whose list has no `sve`, false with `sve` and for a list not read, and `twoLaneMasksFallBack`'s answer for an x86 reading | a short map that becomes an `ArrayIndexOutOfBoundsException` and a fallback with a misleading cause; a pass that leaves an int of the batch unwritten; a gather on a host with none |
| 15 | in `VarkaZoneTableLanesSuite`: both forms at a lane override of four and of two, emitted and run where `VarkaSpeciesGuard` allows, else emitted and verified: the rows of test 8 | a slip that only one width shows |

Added to `VarkaBatchArgRunnerSuite` (ScalaTest; the forced form is `TABLE`, and the first
test runs under `TABLE_SCALAR` too):

| test | the failure only it catches |
| :--- | :--- |
| ten years of instants in no order under `America/Los_Angeles`, one row in ten null: every batch is answered as the reference answers, and `batch-args` is 0 | a table that is not written, or written once for the wrong zone |
| a batch with a live instant of July 2100, and one with a live instant of 1899: declined, `batch-args` counts each, and the rows come from Spark's path and are right | a clamped index used for a live row: the spare bucket holds December 2099's offset, an hour off in July |
| a batch of null rows alone between two live batches: the third batch's rows are right | `neutral` writing steps over the table |
| batches of 64, 4096, 64 and 10000 rows through one runner: each is answered as the reference answers, and after each `indexMapLength()` is `indexMapInts(1, n)` with `n` the longest batch so far | a map sized once to the first batch; a map taken anew for each batch |
| the runner's `longArgs` is `numLiterals + 22964` longs; two runners of one shape under two zones each answer their own zone | a table shared by two tasks |
| the warm-up's kernel call sees the table, and a forced kernel failure's reproduction file, under one megabyte, replays to the same rows | a warm-up or a replay over an empty table |
| a zone data whose `toLocalTable` answers false: every batch declines and no row is wrong | a kernel run over a table that was never written |
| null rows over poison storage (`VarkaEmitterFixtures.makeLongInput`): no fallback, live rows right; run with the memory sanitizer too | a null row's storage that reaches the range or leaves the table |

**Commands**, in order.

    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/Test/compile' 'sql/Test/compile'            # [success]
    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaZoneTableSuite *VarkaZoneTableLanesSuite *VarkaZoneArgsPropertySuite *VarkaTwoLaneConditionSuite *VarkaBatchArgsSuite *VarkaEmitterZoneSuite *VarkaEmitterBudgetSuite *VarkaEmitOptionSuite *VarkaMatrixSuite *VarkaZoneSweepSuite *VarkaIrFuzzSuite'
        # no line with FAILED or Failed: [1-9]; VarkaZoneTableSuite's 14 tests and VarkaZoneTableLanesSuite's 1 are among the passed
    flock "$VARKA_HEAVY_LOCK" build/sbt 'sql/testOnly *VarkaBatchArgRunnerSuite *VarkaEvaluatorComponentsSuite'
        # VarkaBatchArgRunnerSuite: Tests: succeeded n + 8 of its own, failed 0, canceled 0
    flock "$VARKA_HEAVY_LOCK" build/sbt 'set LocalProject("sql") / Test / javaOptions += "-Dvarka.sanitizeMemory=true"' 'sql/testOnly *VarkaBatchArgRunnerSuite'   # failed 0, canceled 0
    dev/varka_matrix.sh --list | grep zoneForm                                                # B0's arms, zoneForm=TABLE and zoneForm=TABLE_SCALAR
    dev/varka_matrix.sh --config zoneForm=TABLE --config zoneForm=TABLE_SCALAR --module sql --suites VarkaTimestampZoneSuite,VarkaBatchArgRunnerSuite   # passes, or fails only in tests that step 11 gives a line
    dev/varka_emit.sh "hour(ts)" --columns ts:timestamp --zone America/Los_Angeles --options zoneForm=TABLE   # FusedOutput(0); note loopDense0's bytes and calls
    dev/varka_regen_generated.sh
    git diff --exit-code -- sql/varka/emitted_bytes.json sql/varka/coverage.json sql/varka/width_audit.json   # exit 0
    python3 dev/varka_push_check.py                                                           # exit 0

**Planted faults:** call 4 of 4.3 left out (test 9 fails by an exception); `first` and `first
+ 1` swapped between the two gathers (test 8 fails on every row); the `covers` test left out
of the runner's fill (the July 2100 test fails on wrong rows); the table branch of the fill
moved after the call of `neutral` (the all-null test fails by `neutral`'s guard); the pass's
rows past the last lane group left out (test 14 fails by an exception on a -1, test 8 on
rows at the lengths that are no multiple of a lane group); the runner's map taken once and
never grown (the test of four batch lengths fails by the kernel's named throw).

**Done when** the commands print as above, the faults fail as named, and the size count
is under 900; if it passes 850, move test 15 and the reproduction test to part c. **Not
here:** no change to the ladder or to a default, no entry in `pinnedArms`, no table for a
local time, no benchmark.

### 5.3 Part c: the ladder's last form, and the sweep

Title `[VARKA-686] Make the table the ladder's last form, and sweep it in every zone`; branch
`varka-686c-ladder`. About 430 counted lines. Needs part b and row 685.

**B0, the premises.**

    grep -c 'TABLE(-1)' $V/VarkaEmitOptions.java                                              # 1
    git grep -n 'LADDER\|RUNGS\|widest' -- sql/catalyst/src/main sql/core/src/main            # row 685's VarkaZoneLadder and its readers; nothing is a stop
    git grep -n 'ZoneForm.STEPS_8' -- sql/catalyst/src/main sql/core/src/main                 # among them the ladder's forms in order, and its cover test
    grep -c '@Test' $T/VarkaZoneSweepSuite.java; grep -c '@Test' $T/VarkaZonePlantedFaultSuite.java; grep -c '@Test' $T/VarkaZoneArgsPropertySuite.java   # n1, n2 and n4
    grep -c '  test("' $S/VarkaTimestampZoneSuite.scala                                       # n3
    grep -n 'no order' $S/VarkaTimestampZoneSuite.scala                                       # row 657's fixture, and row 685's test of the form a batch runs

**Steps.**

1. The ladder (4.8), at the lines B0 printed: `TABLE` is the last of `VarkaZoneLadder.RUNGS`;
   `widest` stops below it where `VarkaZoneTable.gatherFallsBack(VarkaHostIsa.host())`, read
   once when the evaluator is built, and `TABLE_SCALAR` is no rung (4.7: row 691 decides);
   its cover test, which reads no `steps()` of `TABLE`
   (4.4); `tableFromChanges` of the preferred long species' lane count. `emitted` does not
   change. If the ladder is no ordered list of forms with a cover test for each, stop and
   report its shape: this plan was written beside a draft of row 685's.
2. `$T/VarkaZoneKernels.java`: `forms()`, the three step forms, `TABLE_SCALAR`, and `TABLE`
   where the host's flag is false. `sweep` runs a table form over a run of eight changes as
   one batch, as it runs `STEPS_8`; a batch the table does not cover must be declined by the
   filler.
3. `$T/VarkaZoneSweepSuite.java`: each test "in each form" takes `forms()`, the test that
   every form gives the same rows compares the table forms too, and the first two tests below.
4. `$T/VarkaZoneFaults.java` and `$T/VarkaZonePlantedFaultSuite.java`: the next three tests.
5. `$S/VarkaTimestampZoneSuite.scala`: the last three tests, over row 657's four entries.
6. Row 686 of `m9/PLAN.md`: part d is left.

| suite | test | the failure only it catches |
| :--- | :--- | :--- |
| sweep | each table form of `forms()` answers one instant of every bucket, in no order, as `java.time` answers, for every set of rules | a bucket that only a batch spread over the whole table reads |
| sweep | the table form declines a batch with an instant before 1900, and one with the first microsecond of 2100, and answers one that ends a microsecond before 2100 | a range test one bucket or one microsecond off |
| planted | a table whose changes are one microsecond late fails at the change itself | a sweep that skips the instant of a change |
| planted | a table with its two offsets swapped fails on both sides of a change | a check that passes on rows that cross no change |
| planted | a table left from another zone fails the sweep (a filler that writes the first zone's table alone) | a table not written for the zone at hand |
| Spark | ten years of instants in no order under `America/Los_Angeles`, one row in ten null: the rows are Spark's; where the host's flag (4.7) is false no batch declines, and where it is true every batch declines with `batch-args` (or, where row 689 refuses the kernel there, none is compiled) | G2's item; and a gather taken by the ladder on a host with none |
| Spark | a year of instants in no order builds no table kernel, by the observable of row 685's test | a ladder that takes the table before the steps |
| Spark | ten years in no order with a live instant of July 2100 in each batch: the rows are Spark's and every batch declines | a batch outside the table answered from a clamped bucket |

**Commands**, in order.

    flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaZoneSweepSuite *VarkaZonePlantedFaultSuite *VarkaZoneTableSuite *VarkaZoneArgsPropertySuite'
        # [info] Passed: Total n1 + n2 + n4 + 19, Failed 0, Errors 0; note the sweep's printed time
    flock "$VARKA_HEAVY_LOCK" build/sbt 'set LocalProject("catalyst") / Test / javaOptions += "-Dvarka.sanitizeMemory=true"' 'catalyst/testOnly *VarkaZoneSweepSuite *VarkaZoneTableSuite'   # Failed 0, Errors 0
    flock "$VARKA_HEAVY_LOCK" build/sbt 'set LocalProject("catalyst") / Test / javaOptions ++= Seq("-XX:UseAVX=2", "-XX:MaxVectorSize=16")' 'catalyst/testOnly *VarkaZoneSweepSuite *VarkaZoneTableSuite'   # Failed 0, Errors 0: two lanes, where forms() has no TABLE
    flock "$VARKA_HEAVY_LOCK" build/sbt 'sql/testOnly *VarkaTimestampZoneSuite'               # Tests: succeeded n3 + 3, failed 0, canceled 0
    flock "$VARKA_HEAVY_LOCK" build/sbt 'set LocalProject("sql") / Test / javaOptions += "-Dvarka.sanitizeMemory=true"' 'sql/testOnly *VarkaTimestampZoneSuite'
    flock "$VARKA_HEAVY_LOCK" build/sbt 'set LocalProject("sql") / Test / javaOptions ++= Seq("-XX:UseAVX=2", "-XX:MaxVectorSize=16")' 'sql/testOnly *VarkaTimestampZoneSuite'
        # both: succeeded n3 + 3, failed 0, canceled 0
    dev/varka_regen_generated.sh
    git diff --exit-code -- sql/varka/emitted_bytes.json sql/varka/coverage.json sql/varka/width_audit.json   # exit 0
    python3 dev/varka_push_check.py                                                           # exit 0

**Planted faults:** `TABLE` before `STEPS_8` in the ladder, and `tableFromChanges` returning
2 (the year test fails for each); the host's flag ignored (the ten-year test fails in the
two-lane run); `covers` with `<=` for `<` at `LIMIT` (the sweep's range test fails).

**Done when** the commands print as above, the faults fail as named, and the Outcome holds
the sweep's time before and after. **Not here:** no change to a step form, no new status, no
default but the ladder's last form.

### 5.4 Part d: the measurement and the close

Title `[VARKA-686] Measure the packed table against three gathers and the steps`; branch
`varka-686d-measure`. About 320 counted lines. Needs part c. The lead dispatches the timed
runs; a builder commits what comes back.

**B0.** With `B=sql/catalyst/src/test/java/org/apache/spark/sql`: `ls
$B/VarkaZoneTableBenchmark.java` fails; `grep -c 'DRY_RUN_PROPERTY = "varka.bench.dryRun"'
$B/VarkaBenchmarkBase.java` and `grep -c 'TABLE(-1)' $V/VarkaEmitOptions.java` print 1.

**Steps.**

1. `$B/VarkaZoneTableBenchmark.java`: new, a Java class over `VarkaBenchmarkBase`, modelled
   on `VarkaLongLaneBenchmark`: the sections and data sets of section 7, the reference's zone
   data, the sanitizer off. The arm of three arrays is the probe's loop `ZoneForms.table`;
   the packed arms are the 14 calls of 4.3, with the whole batch's indices first and with
   calls 1 to 6 beside the gathers. Each arm's rows are checked against
   `VarkaZoneTable.toLocal` before it is timed. Where the host's flag (4.7) is true the
   `TABLE` kernel sections print that, untimed; the `TABLE_SCALAR` sections run everywhere.
2. The dry run (command 1), on the laptop. Then the lead dispatches, at both widths:

        gh workflow run benchmark.yml --repo vecbricks/varka --ref master -f pr=<n> -f create-commit=false -f class=VarkaZoneTableBenchmark -f jdk=25 -f expected-cpu="AMD EPYC 7763"
        gh workflow run benchmark.yml --repo vecbricks/varka --ref master -f pr=<n> -f create-commit=false -f class=VarkaZoneTableBenchmark -f jdk=25 -f expected-cpu="AMD EPYC 7763" -f extra-java-options=-XX:MaxVectorSize=16

   A run on another CPU is dispatched again. Commit both widths' results and provenance
   files under `sql/catalyst/benchmarks/`. A ratio under 1.3 is run again and read by
   minimums (`python3 dev/varka_bench_diff.py --within <file> --ab "<A>" "<B>"`).
3. `sql/varka/skills/vector-api-and-width.md`: a new `##` section on the index-map gather of
   a long array: its `int[]`, its second int species and the census, its index check, two
   lanes, SVE, the whole batch's map, and the measured losses of 4.1 with their files. Then
   `python3 dev/varka_toc.py SKILLS.md --from sql/varka/skills`.
4. This file's section 11 with every prediction scored, and row 686's line: `**Done**`.

**Commands.**

    flock "$VARKA_HEAVY_LOCK" build/sbt -batch "project catalyst" 'set Test/javaOptions += "-Dvarka.bench.dryRun=true"' "Test/runMain org.apache.spark.sql.VarkaZoneTableBenchmark" > target/dry-686.log 2>&1; echo $?   # 0
    grep -c 'Exception' target/dry-686.log                                                    # 0
    python3 dev/varka_plan_lint.py --check --offline; python3 dev/varka_push_check.py         # both exit 0

**Done when** the dry run passes, both widths' results are committed, and each prediction of
section 7 has a score. **Not here:** no timed run on a shared laptop, no change to main code
or to `tableFromChanges` (row 691's), no number in a document that no results file holds.

## 6. Tests, proofs, fuzzing and coverage

| suite | language, module | what it holds | the failure only it catches |
| :--- | :--- | :--- | :--- |
| `VarkaZoneTableSuite`; `VarkaZoneTableLanesSuite` | Java, catalyst, JUnit 5 | the packing, the index, the reference's table against `java.time` in every set of rules; both forms' kernels over hand tables, null rows over storage outside the table, the pins, the allocation, the map's length check; the kernels at lane overrides, apart | a clamp or a packing that is wrong where no real zone shows it |
| `VarkaZoneArgsPropertySuite`, extended | Java, JUnit 5, catalyst | random zones, ranges and instants through the fill and the kernels of both table forms | a range the sweep's windows do not hold |
| `VarkaZoneSweepSuite`, extended | Java, catalyst | `TABLE` beside the step forms at every change from 1900 to 2100 at one second; every bucket in one batch; both ends of the range | a zone whose table differs from `java.time` |
| `VarkaZonePlantedFaultSuite`, extended | Java, catalyst | three broken tables | a sweep that cannot fail on a table |
| `VarkaBatchArgRunnerSuite`, extended | Scala, sql/core | the table written once a runner, the map grown to the longest batch, the declines, the all-null batch, the warm-up and the reproduction | a stale, shared or overwritten table, which no fallback catches; a map of the wrong length |
| `VarkaTimestampZoneSuite`, extended | Scala, sql/core | ten years in no order under the ladder, at each host kind | the row's done-when |
| `VarkaOperatorCensusSuite`, four cells | Java, catalyst | the gather and the int `MIN` as C2 compiles them, alone and beside an int gather | a gather that allocates on this JDK |

- **Proof:** `zone_bucket.smt2` (4.10), rendered from `VarkaZoneTable`'s constants.
- **Memory:** the suites run with the memory sanitizer, which does not see an array (fact
  10). The clamp, proved for every long, keeps a null row inside the table; the JVM's own
  check shows a missing clamp, since test 9 puts the table at the array's end.
- **Fuzzing:** the IR fuzzer draws its options from `VarkaEmitOption.TABLE`, so from part b
  on it draws both table forms, fills them through `VarkaZoneKernels`' filler and compares
  with `VarkaReference`; a batch outside the table counts as declined. The Spark fuzzer
  draws the session zone, and the ladder picks the form. The matrix gains both forms.
- **Coverage and mutation:** `dev/varka_gen_coverage.sh` must show every branch of
  `VarkaZoneTable` and of the three new lowering methods reached. `dev/varka_pit.sh` reaches
  the lowering through `VarkaIrFuzzSuite`; the planted faults stand for the rest.

## 7. Measurement

`VarkaZoneTableBenchmark` (part d): batches of 4096 instants in no order, no nulls,
`America/Los_Angeles`; on the AMD EPYC 7763 runners at 256 and at 128 bits; the laptop adds
512 when it is idle. The control, listed first, is `java.time` a row. Data sets: 297 days (2
changes), 1389 days (8 changes), ten years (20 changes, 574 buckets, about 9 KB of the packed
table), and 1900 to 2100 (every bucket, 183712 bytes).

| A/B arm | sections |
| :--- | :--- |
| the packed table against three gathers, and the whole batch's indices against indices beside the gather, hand-written loops | "three arrays, three gathers", "packed, indices beside the gather" and "packed, indices of the whole batch", each over ten years and over 1900 to 2100 |
| a few kilobytes against the whole table | the same six sections, and "kernel TABLE" over ten years and over 1900 to 2100 |
| scalar loads against the control and against the gathers | "kernel TABLE_SCALAR" over ten years, at each width |
| the table against the steps, in kernels | "kernel STEPS_2" and "kernel TABLE" over 297 days; "kernel STEPS_8" and "kernel TABLE" over 1389 days |

**Predictions, registered on 26 November 2026, before any run of a kernel or of a runner's
CPU.** P5 to P7 and P12 were set after the second probe (section 2).

| # | prediction | scored by |
| ---: | :--- | :--- |
| P1 | the census reads `long.gather`, `int.MIN` and both two-species cells `I` with no allocation at 512 and at 256 bits on the laptop, and `long.gather` `F` at 128: yes | part a, command 2 |
| P2 | `zone_bucket.smt2`: 16 checks, each solver under 2 s for the file | part a, `dev/varka_prove.sh` |
| P3 | `loopDense0` of `(toLocal:0 col:0:long)` under `TABLE` at eight long lanes: 8 lane calls, no `VectorMask` call, between 170 and 240 bytes | part b's pin |
| P4 | no existing shape's emitted bytes move in parts a to c: yes | each part's `git diff --exit-code` |
| P5 | at 256 bits on the EPYC 7763, over ten years, the loop with the whole batch's indices costs less than the loop with the indices beside the gather: yes (0.829 against 0.871 on the laptop at four lanes); and the packed loop costs from 0.9 to 1.1 times the three-gather loop (0.871 against 0.882) | the first A/B arm |
| P6 | at 256 bits, over 1900 to 2100, the packed loop costs at most one and a half times its own cost over ten years | the second A/B arm |
| P7 | at 256 bits the `TABLE` kernel loses to `STEPS_2` at 2 changes: yes; at 8 changes it costs from 0.8 to 1.3 times the `STEPS_8` kernel, so this row moves no threshold. At 512 bits on the laptop `TABLE` loses to `STEPS_8` at 8 changes, as 0.621 against the probe's 0.289 says | the third A/B arm |
| P8 | a zone's table is 183712 bytes, 22964 longs, a runner and a zone node; a reproduction file with one table is under one megabyte | part b's runner tests |
| P9 | ten years in no order under `America/Los_Angeles` decline no batch at 256 bits and on the Arm runner, and every batch at 128 bits on x86 | part c's Spark test |
| P10 | test time: `VarkaZoneTableSuite` under 10 s; the sweep grows by under 3 s on the laptop; the seven runner tests add under 15 s | the suites' reported times |
| P11 | four pull requests, none over 850 counted lines | `python3 dev/varka_msg_check.py size` |
| P12 | at 128 bits on the EPYC 7763 the `TABLE_SCALAR` kernel over ten years costs less than the control, `java.time` a row: yes, by under two times. Whether the kernel of `hour(ts)` then beats Spark's row path, and the form goes on the ladder at two lanes, is row 691's to measure | the last A/B arm; row 691 |

P10 was set after reading `python3 dev/varka_calibration.py`: a JUnit class with no session
predicted at 5 s took 0.15 s (VARKA-328), the calendar sweep predicted at 20 s took 1.5 s
(VARKA-281), and a new sql/core suite's tests took 8.4 s (VARKA-244).
P5 to P7 and P12 rest on the laptop's probes: no results file has the EPYC 7763's gather.

## 8. Who builds it

`m9/PLAN.md` 1.4 has rows 682 to 692 built at full strength, and so is every part here: a
slip in the emitter's slots, the index pass or the runner's fill is a wrong answer that no
fallback catches. Part d is the lead's dispatches. No part serves G5.

## 9. Risks

1. **The gather boxes, or makes an int gather box** (fact 6: the record says it can). Part
   a's census cells and part b's test 13 hold it, and part a stops the row before any kernel
   is built. The lever is `TABLE_SCALAR` at every width (4.7); the owner decides.
2. **Row 685 is built otherwise than `VARKA-657.md` 3.5 says:** part c's B0 and its stop.
3. **A table overwritten, never written, or loaded as 22964 locals:** `neutral`'s guard, the
   runner's tests of an all-null batch and of a zone with no table, and the bytes' pin (4.4).
4. **A short or stale index map:** the kernel's length check throws by name, and an int that
   an earlier batch left is a clamped index, inside the table (4.1); test 14. With the JVM's
   index check switched off, the clamp and checks 2 to 4 of the proof hold.
5. **Memory:** 183712 bytes a runner and a zone node, the same again during a warm-up, a
   table of text in a reproduction file (P8), and 4 bytes a row for the map. A Java array is
   aligned to 8 bytes, so a bucket can lie across two cache lines; section 7 prices it.
6. **Newer zone data with two changes inside 2^39 microseconds:** `pack` answers false and
   the zone's batches decline; test 5 and the runner's wrapper test hold it.
7. **G2's item for the casts and the sum** needs row 693's table of the local time line
   (4.9). Until then those batches over ten years decline.
8. **The runners differ from the laptop.** `tableFromChanges` stays 9 until row 691
   measures. The whole-batch gain is a ratio under 1.1 with one sign in two runs and at
   three batch lengths; P5 scores it, and if it fails the indices beside the gather are the
   same calls in one loop. A host with no gather that the flag does not name is right and
   slow, and test 13 fails on it in CI.
9. **A forced table arm breaks tests that assert a decline:** part b's step 11 gives each
   its line in `skips.tsv`, and any other failure is a stop.

**Decided by the owner on 26 November 2026.** The table of the local time line is a new
row, 693, after row 690 (4.9). After the second probe: the runner owns an index map of the
batch's length, the kernel writes the whole batch's indices before its gathers (4.1), and a
host with no gather gets scalar loads, on the ladder when row 691 has measured them (4.7).

## 10. The house rules that bind this row

From `sql/varka/AGENTS.md` and `BUILD_SHEETS.md`, those a builder here is likeliest to break:

- "never introduce a second species of a lane type" (`skills/vector-api-and-width.md`). The
  lowering converts into the int species of the kernel's own width; only the JDK's gather
  uses the half-width one, and part a reads what that costs.
- "No allocation, streams, lambdas or boxing on a path that runs per batch or per row." The
  table is written when a runner is built, and the runner takes a new index map only for a
  batch longer than every one before it (4.1).
- "A new bounded lowering comes with its proof file": `zone_bucket.smt2` merges before the
  kernel. A switch over an enum has no `default`: `laneCalls`, `emitToLocal`, `count`.
- A new catalyst suite is JUnit 5 under `@VarkaSuite`, each test with a `@DisplayName`,
  from `VarkaMatrixRuns.BASE`; a pin of emitted structure takes `PinsDefaults`. No generated
  file is edited by hand, `operator_census.json` among them. ASCII only.

Do not: add a table for a local time; change `tableFromChanges` from 9; add a table form to
`pinnedArms`; put `TABLE_SCALAR` on the ladder; allocate in a kernel; pass a table or a map
as a new parameter of `run`; map the table as a segment; run a timed benchmark beside other
agents; push, open a pull request or dispatch a workflow.

## 11. Outcome

To be written when the row closes.
