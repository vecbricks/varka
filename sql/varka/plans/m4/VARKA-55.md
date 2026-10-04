# VARKA-55: assert no allocation inside a kernel loop

## 1. Where this came from

The intrinsic analysis recorded in `SKILLS.md` ("Every operator the plans rely
on is one instruction; two species in one JVM is a box per iteration", PR
#104). Every Vector API operator the six codebase reviews had put into plans
compiled to the expected single instruction - and the same probe found the
one failure mode that keeps every packed instruction in place and still costs
3-13x: a heap box per loop iteration, produced when two species of one lane
type have been through the shared `IntVector` templates in the same JVM and
C2 inlines them bimorphically.

VARKA-31's suite (`VarkaAssemblySuite`) asserts that packed instruction
families are *present*. It has no negative assertion, so a boxed kernel loop
passes it. This task adds the negative assertion, calibrated by a self-test
pair the way VARKA-31 calibrated its positive one: a case that must be clean
and a case that must trip.

## 2. The assertion is a measured rate, not a count of sites

The first design counted allocation sites in the disassembly and asserted
zero. It went red on `ChronoVectorOps.vectorFourFields`, which carries four
`NativeMemorySegmentImpl` views in its prologue that C2 does not
scalar-replace - one allocation per *call*, not per lane group - and the
disassembly cannot tell the two apart: an allocation's slow path (the TLAB
refill) jumps backwards to its retry point, so "inside a backward branch" is
not "inside the loop", and a per-call site sits in exactly such a range.

So the probe measures instead. After the `ROUNDS` calls that get the method
compiled, it reads the thread's allocated bytes
(`com.sun.management.ThreadMXBean.getThreadAllocatedBytes`) around
`MEASURE_ROUNDS` more calls and prints bytes per call and rows per call. The
suite asserts **at most one byte per row**. The line sits between the two
things it must separate with a margin of at least 5x on either side: a boxed
vector is a fresh object per lane group, at least 80 bytes for the payload
alone - 5 bytes per row at 16 lanes, 20 at 4, and the polluted self-test
measured 26 and 192 - while a per-call setup object over 1024 rows is under
0.25 bytes per row even four times over.

The static detector stays as the *diagnosis* printed with a failure: the
allocation prefetch (`prefetchnta`, three per site under the default
`AllocatePrefetchLines`) and the prototype mark-word store, `movq $1, (%reg)`,
the two instructions C2 emits for nothing else in a Varka loop. `Family` gains
an optional operand pattern for the second. The failure message gives the
bytes, the rows, the site counts, and the `-XX:+PrintInlining` tell ("callee
changed to" naming a second species class) as the next step.

## 3. The self-test, and what it took to make it trip

`VarkaAssemblyProbe` gains `gatherLookup`, an index-map gather out of a
64-entry table over `IntVector.SPECIES_PREFERRED` - one `vpgatherdd` and its
index check when it compiles cleanly - and `gatherLookupOtherSpecies`, the
same loop over a second int species (128 bits where the preferred species is
wider, 64 bits under `-XX:MaxVectorSize=16`). Two cases: `gatherLookup` runs
the first alone; `gatherLookupPolluted` interleaves the two. The method under
assertion is `gatherLookup` in both; the other method exists only so the
shared templates have seen two species when C2 compiles it.

The first version used a `selectFrom` lookup, the shape PR #104's probe had
boxed. It did not trip in the suite's harness. Measured standalone with the
same bytes-per-call instrument, over 200000 calls of 1024 rows:

| shape, pattern | bytes per call at 512 | at 128 |
|---|---|---|
| `selectFrom`, alone | 0 | 0 |
| `selectFrom`, interleaved with the other species | 0 | 0 |
| `selectFrom`, other species hot first, then alone | 5120 | - |
| gather, alone | 0 | 0 |
| gather, interleaved | 10240 | 16384 |

PR #104's probe had run the other species' methods to completion before the
preferred ones, which is the third row. Whether a bimorphic template boxes
depends on the shape and on the order the profiles filled in; the gather
boxes under both orders at both widths, so the self-test uses it. The
positive case of a negative assertion has to be a shape that trips whenever
the condition holds, or it proves nothing.

## 4. What is asserted, and where

`assertFamilies`, the helper every kernel and emitted-loop test goes through,
now ends with `assertNoBoxing`. That puts the assertion on
`DateVectorOps.vectorAddDays`, `ChronoVectorOps.vectorFourFields`, the emitted
`year`, `dayofweek` and comparison loops, all of those again at 128-bit lanes,
and the two inline-Varka cases. The self-test pair runs at both widths. Every
case also logs its measured rate with `info`, so a green run still shows the
numbers.

## 5. Predictions, registered before the run, and how they scored

1. *Every existing case stays green: zero allocations in every emitted loop
   method and both hand-written kernels, at both widths; the segment
   prologue's `MemorySegment` objects are scalar-replaced.* **Half right.**
   Every emitted loop reads 0 bytes per call at both widths, and so does
   `vectorAddDays`. `vectorFourFields` reads 160 bytes per call - four
   40-byte `NativeMemorySegmentImpl` views, its prologue's `ofAddress` calls,
   which C2 leaves in place while it eliminates the same objects in the
   emitted loops. 0.16 bytes per row, inside the ceiling, and now on record
   (section 8).
2. *`tableLookup` is clean and shows a permute.* Superseded with the shape
   change; the gather is clean and shows `vpgatherdd` at both widths.
3. *The polluted case allocates at both widths.* **Wrong for the shape as
   first written** (section 3), right for the gather: 26624 bytes per call at
   512 bits (26 per row), 196608 at 128 (192 per row).
4. *No pinned fixture moves.* Right; test code only.

## 6. Files

* `sql/catalyst/src/test/scala/.../varka/VarkaAssemblySuite.scala` - the
  `Family` operand pattern, `packedGather`, `allocationPrefetch`,
  `markWordStore`, `maxBytesPerRow`, `assertNoBoxing`, `assertBoxes`, the
  self-test pair at both widths, `ProbeRun.allocBytesPerCall` and
  `rowsPerCall`, and the one-line addition to `assertFamilies`.
* `sql/catalyst/src/test/java/.../varka/VarkaAssemblyProbe.java` - the cases
  restructured around a prepared `Hot` object so the measurement window holds
  nothing but calls of the method under test (the emitted cases' argument
  arrays used to be built per call); the allocation measurement and its two
  output lines; `TABLE`, `OTHER_SPECIES`, `gatherLookup`,
  `gatherLookupOtherSpecies`.
* `sql/varka/plans/m4/PLAN.md` - row 55.
* `SKILLS.md` - the rate-not-sites lesson and the order dependence, appended
  to the intrinsic-analysis section.

## 7. Verification

```
VARKA_HSDIS_DIR=<dir with hsdis-amd64.so> build/sbt 'catalyst/testOnly *VarkaAssemblySuite'
build/sbt 'catalyst/testOnly *Varka*'
dev/lint-java && dev/scalastyle
```

The suite cancels rather than fails without a disassembler, as before; the
new tests inherit that.

## 8. Outcome

Fourteen tests green with a disassembler present, at both widths. The rates
the run logged:

| method | bytes per call (1024 rows) |
|---|---|
| emitted `year`, `dayofweek`, comparison loops, both widths | 0 |
| `DateVectorOps.vectorAddDays`, both widths | 0 |
| `ChronoVectorOps.vectorFourFields` | 160 (four per-call segment views) |
| emitted `year` under the inline-Varka directives | 520 (per-call; 0 without them) |
| `gatherLookup`, clean, both widths | 0 |
| `gatherLookup`, polluted | 26624 at 512 bits, 196608 at 128 |

Two things on record for later that this task does not act on. First,
`vectorFourFields`' four unreplaced segment views: reference code
(`SKILLS.md`), 160 bytes per 1024 rows, harmless at this size, but the
emitted loops show C2 can eliminate the same objects, so whatever keeps these
alive in the hand kernel - a view reaching a call C2 did not inline is the
usual reason - is a small investigation with a known-good comparison. Second,
forcing C2 to inline Varka's packages makes the emitted `year` loop allocate
520 bytes per call where it otherwise allocates nothing: the inline directive
VARKA-31 already found to change nothing in the instructions does change the
allocation picture, another reason not to run production with it.
