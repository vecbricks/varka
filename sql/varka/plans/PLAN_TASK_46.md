# Task 46: validity helpers that inline

## 1. Where this came from

`PLAN_MILESTONE_4.md` row 46 and section 2.17, the three-task sequence (45,
46, 47) that followed task 32's finding that more than half of a calendar
kernel's time is the validity write rather than the arithmetic. Task 45 took
the dense path's per-lane-group write away entirely; `PLAN_TASK_45.md` 11.3
then asked for this task's value to be "re-derived against the masked rows
before either is scheduled" rather than carried over from 2.17's estimate.

Section 2 is that derivation, done against the committed parity files before
any code. It changes the task in three ways: it prices one call, at both
widths, from numbers already in the tree; it finds that the read helper is not
the cost and the write helper is nearly all of it; and it finds one dense
shape that still pays on every batch, which `PLAN_TASK_45.md` 11.3 says does
not exist.

## 2. The admission check, done

### 2.1 The four helpers, and what they measure

`javap -c -p` over the built `VarkaVectorSupport`, which is the check
`SKILLS.md` prescribes for any helper in a lane path:

| helper | bytecode | shape |
|---|---|---|
| `validityBitsAt` | 153 bytes | whole group, read, four-arm switch on `groupBytes(lanes)` |
| `orValidityBitsAt` | 212 bytes | whole group, read-modify-write, the same switch |
| `partialValidityBitsAt` | 88 bytes | epilogue's partial group, byte loop |
| `orPartialValidityBitsAt` | 103 bytes | epilogue's partial group, byte loop |

C2's `MaxInlineSize` is 35 bytecodes and its `FreqInlineSize` is 325, so all
four sit in the band where inlining depends on the caller's node budget rather
than on the callee's size alone. Task 32 established with `-XX:+PrintInlining`
- not from timings - that the two whole-group helpers are refused inside a
wide loop, `NodeCountInliningCutoff` on one compilation and `callee is too
large` on another, and that `-XX:CompileCommand=inline` changes the refusal's
reason and nothing else (`SKILLS.md`, the method-fusion section). That
evidence predates task 45 and was taken on the shared four-field loop; section
5 re-establishes it per shape on today's tree, because the refusal is a
property of the caller.

### 2.2 Where the calls still are, after task 45

| body | per lane group | why it survives |
|---|---|---|
| dense, value root | nothing | task 45: the driver fills the bits once with `setValid` |
| dense, `Cond` root | one `orValidityBitsAt` | a selection bitmap is computed, not known; `fillsValidityOnce` excludes `Cond` by design |
| masked, per referenced input with nulls | one `validityBitsAt` | the input's word for this group |
| masked, value root | one `orValidityBitsAt` | which rows of this output are valid is what the masked body computes |
| masked, `Cond` root | one `orValidityBitsAt` | as the dense case |
| either, epilogue | the partial pair, once per batch | cold; amortised over the whole batch |

**A correction to `PLAN_TASK_45.md` 11.3.** That section says the dense path
has "no longer a per-lane-group validity call there at all". It has one, for
every `Cond` root: `VarkaLoopEmitter.fillsValidityOnce` returns false for a
condition root on purpose, because its slot holds a selection bitmap whose
bits mean "known true" and not "valid", so the driver cannot know them in
advance. Every fused filter therefore pays this call once per lane group on
every batch, null-free or not - and the filter is the shape the columnar path
runs most. This is the one beneficiary of task 46 that 2.17 and 11.3 both miss.

### 2.3 What one call costs, from the committed files

The `denseValidityOnce=false` variant is an exact A/B for a single
`orValidityBitsAt` per output per lane group: same kernel, same data, one call
added. Both arms are committed, at both widths, in
`sql/catalyst/benchmarks/VarkaEmitterParityBenchmark-jdk25-results.txt` and
its `-128bit-` sibling. Per-call cost is
`(1/with - 1/without) * rows_per_group / calls_per_group`:

| shape | width | without the call | with it | calls/group | ns per call |
|---|---|---|---|---|---|
| `year`, dense | AVX-512 | 3446.0 | 2425.1 | 1 | 1.95 |
| `year`, dense | 128-bit | 1336.2 | 823.0 | 1 | 1.87 |
| four fields shared, dense | AVX-512 | 1642.0 | 836.6 | 4 | 2.35 |
| four fields shared, dense | 128-bit | 789.0 | 289.8 | 4 | 2.18 |
| four fields, hand-written | AVX-512 | 1500.6 | 677.3 | 4 | 3.24 |
| four fields, hand-written | 128-bit | 634.1 | 242.4 | 4 | 2.55 |

**The cost is per call and not per row, and it does not vary with the vector
width.** Six measurements over three shapes and two widths land between 1.87
and 3.24 ns. That is the whole of 2.17's "pays most at narrow widths"
argument, now arithmetic rather than reasoning: the same call is amortised
over 16 rows at AVX-512 and over 4 at 128-bit, so its per-row cost is four
times larger there. It also says what the ceiling is - one call removed is
worth about 2 ns per lane group, no more.

### 2.4 It is the write, not the read

The masked body adds, over the dense one, the branchy per-input word
computation and one `validityBitsAt` read per lane group. If the read cost
what the write costs, a masked row would sit well below its dense-plus-write
A/B row. It does not:

| shape | width | dense + one write (A/B) | masked | gap |
|---|---|---|---|---|
| `year` | AVX-512 | 2425.1 | 2291.3 | 0.02 ns/row |
| `year` | 128-bit | 823.0 | 809.7 | 0.02 ns/row |
| four fields shared | AVX-512 | 836.6 | 829.9 | 0.01 ns/row |
| four fields shared | 128-bit | 289.8 | 286.6 | 0.04 ns/row |

The read plus the entire word machinery costs about 0.02 ns/row - well under
half a nanosecond per lane group, where one refused call costs about 2 ns. A
call that is not inlined cannot be that cheap. So the read helper is almost
certainly being inlined today (it is read-only: four `get` arms, no
read-modify-write, far fewer nodes than the write's four `get`/`set` pairs),
and the write helper is not. Section 6.1 registers that as a prediction for
`-XX:+PrintInlining` to confirm or refute, because the two outcomes lead to
different conclusions about what this task is worth.

**What this makes the task.** The prize is the write helper, wherever it
survives: every masked value output, and every `Cond` output in both bodies.
Specialising the read costs almost nothing to write and is kept for symmetry
and for the epilogue-free narrow widths, but it must not be predicted to pay.

### 2.5 Why a flag is not the fix, and why a microbenchmark is not the check

`-XX:CompileCommand=inline` was tested properly in task 32 and moves the
refusal from `callee is too large` to `NodeCountInliningCutoff` with no change
in outcome; Spark cannot require a `CompileCommand` on a user's JVM in any
case. The engine's JMH harness must not be used to price this either: in a
microbenchmark the helper's caller has none of the node pressure of a fused
loop, so it inlines and the harness measures itself. That is the trap
`SKILLS.md` records under "a hand-written kernel standing in for emitted code
must not introduce a method boundary the emitted code does not have", in the
other direction. The parity harness, whose callers are the emitted bodies, is
the only place this is measurable.

### 2.6 What the emitter knows at emit time, and what it emits today

`VarkaLoopEmitter` does not bake a width. It emits
`getstatic IntVector.SPECIES_PREFERRED`, then `invokeinterface length()` into
a local, so one emitted class is correct at whatever width the JVM has. A
width-named callee changes that: the emitter has to resolve the width when it
writes the bytes.

It can. `SelectionVectorOps`, in the same package and the same module, already
holds `IntVector.SPECIES_PREFERRED` as a static final, and catalyst compiles
with `--add-modules jdk.incubator.vector` (`sql/catalyst/pom.xml`,
`project/SparkBuild.scala`). And it is sound: an emitted class is defined by
the shape cache in the JVM that will run it and lives only in memory, so
emit-time and run-time widths are the same `SPECIES_PREFERRED` by
construction. Section 3.2 removes even that invariant rather than documenting
it, by baking the concrete species alongside the width.

### 2.7 What the pinned fixtures carry, and what they do not

`VarkaShapeCache.shapeHash` is a SHA-256 over `VarkaVectorIR.canonical` plus
the options' canonical rendering; it does not cover the emitted bytes and does
not need to carry the width, since the cache is per JVM. `pinnedLineMap` in
`VarkaLoopEmitterSuite` is the class's `LineNumberTable` keyed by IR node -
line numbers and renderings, not instructions. Neither moves for this task,
which the suites assert as they stand.

### 2.8 A defect found on the way: `canonical()` omits `truncDate`

`VarkaEmitOptions.canonical()` renders ten of the record's eleven components;
`truncDate` is missing. Two option values differing only in the `trunc`
lowering therefore render the same string and hash the same, while being
different keys in the cache's map - exactly the hazard the record's own class
doc names ("options in one but not the other would merge two variants'
execution identities"). No test asserts that every component reaches the
rendering. It is a telemetry and class-naming defect, not a wrong-bytes
defect, and it is one line plus a completeness test. Taken here because this
task adds components to the same record and would otherwise walk past it.

### 2.9 The helpers are reached by name, not by a compile-time link

`varka-engine` is a **test**-scope dependency of catalyst
(`sql/catalyst/pom.xml`: "production code never links it, and generated
bytecode reaches it by name through the context class loader"). Adding methods
there is not a compile-time coupling: the emitter names them as strings, so a
typo or an engine jar older than the emitter is a `NoSuchMethodError` on the
kernel's first execution. That is the failure mode `misdescribeAdd` already
pins a test on, and section 5 executes a kernel at every specialised width so
CI cannot miss it.

### 2.10 What the check would have rejected

A helper whose specialised form could not fit under `MaxInlineSize` (they fit;
section 3.1); a width the emitter cannot know when it writes the bytes (it
can; 2.6); a pinned fixture that would move (none do; 2.7); and a prize that
had already been taken by task 45 (it has not - 2.2 and 2.3 price what is
left, on the masked path and on every filter).

## 3. The design

### 3.1 The specialised helpers, in the engine

Eight new methods on `VarkaVectorSupport`, beside the four they specialise:
`validityBitsAt{2,4,8,16}` and `orValidityBitsAt{2,4,8,16}`, one per int lane
count the Vector API produces on hardware that exists (`SPECIES_64` through
`SPECIES_512`). Each drops the `lanes` parameter, and with it the switch, the
`laneMask` call and - for the byte-aligned widths - the shift:

    public static void orValidityBitsAt16(
        MemorySegment validity, long row, long laneBits) {
      // A whole group's row is a multiple of 16, so there is no shift.
      long off = row >>> 3;
      validity.set(UNALIGNED_SHORT, off,
          (short) (validity.get(UNALIGNED_SHORT, off) | laneBits));
    }

The `(short)` cast is the lane mask at 16 lanes and the `(byte)` cast is the
lane mask at 8, so those two widths need neither mask nor shift. At 4 and 2
lanes a group starts mid-byte, so those keep `row & 7` as the shift and a
constant mask (`0xF`, `0x3`) - still one byte access and no switch.

**The size gate.** Each specialised helper should come in at or under C2's
`MaxInlineSize` so that it inlines whether or not the call site is judged hot.
That is a property the build can check rather than hope for: section 5 adds a
test that reads each helper's `Code` length and compares it against the JVM's
own `MaxInlineSize` (`HotSpotDiagnosticMXBean`, falling back to 35). If a
helper does not fit, the test says so before any benchmark does.

**Update, when they were written.** `javap` over the built class, against the
general pair's 153 and 212 bytes:

| lanes | reader | writer |
|---|---|---|
| 2 | 18 | 48 |
| 4 | 25 | 48 |
| 8 | 18 | 33 |
| 16 | 18 | 33 |

Every reader fits `MaxInlineSize`, and so do the two byte-aligned writers. The
2- and 4-lane writers do not: they carry the lane mask and the shift the
aligned pair folds away, and the two `MemorySegment` accesses alone are 16 of
those bytes. The gate is therefore split rather than dropped - the aligned
writers and every reader are held to `MaxInlineSize`, all four writers to
`FreqInlineSize` (325, the bound C2 uses at a call site it judges hot, which a
kernel's inner loop is), and every writer to a third of the general form. What
this costs is the guarantee on a call site that has not been profiled yet; the
binding constraint on the general pair was never its size but the node count
of its four-arm switch, and these have a quarter of it. Section 6.1's
prediction 2 stands as registered and is scored in section 9 with this
paragraph as its evidence.

The partial (epilogue) pair is **not** specialised. It runs once per batch, so
its call cost is amortised over every row; specialising it would double the
new surface for nothing measurable.

### 3.2 The emitter: resolve the width once, bake it

Three changes in `VarkaLoopEmitter`, all inside the prologue and the two
name-choosing helpers:

* resolve `lanes` at emit time - `options.lanesOverride()` when set, otherwise
  `IntVector.SPECIES_PREFERRED.length()`;
* emit the **concrete** species (`SPECIES_64`/`128`/`256`/`512`) rather than
  `SPECIES_PREFERRED`, and load the lane count with `loadConstant` rather than
  `invokeinterface length()`. This is what makes the width a property of the
  class rather than of the JVM that runs it, so the specialised call names
  cannot disagree with the vectors beside them - the invariant is removed
  instead of documented, and an override becomes executable rather than only
  inspectable;
* `validityBits(s)` and `orValidityBits(s)` append the width at whole-group
  call sites, so `iload(s.lanes)` disappears from those three sites. The
  epilogue's `s.epilogueMask != null` branch is untouched and keeps the
  partial pair with its runtime row count.

Nothing else moves: the lane-group body, the word algebra, the fragment
mechanism, the guard, the status route and the driver are all as they are.

### 3.3 The switch, and what "off" means

`VarkaEmitOptions.validityByWidth`, default **true**, and false reproduces
today's bytes exactly - `SPECIES_PREFERRED`, `invokeinterface length()`, the
generic helper names - on the `FloorMod7` precedent that every lowering change
keeps its predecessor as a live reference variant. Both halves of 3.2 ride the
one switch on purpose: an A/B whose arms differ in two ways prices neither, so
"off" has to be the whole of today.

### 3.4 The fallback, which stays live

A width with no specialised sibling, or one with no named species constant -
SVE's 1024- and 2048-bit shapes reach 32 and 64 int lanes and have no
`SPECIES_1024` field - emits exactly the "off" form: `SPECIES_PREFERRED` and
the generic helpers. Varka runs correctly there and gains nothing, which is
the right trade for hardware nobody has measured this on. The fallback is not
dead code: section 5 drives it through `lanesOverride`.

### 3.5 `lanesOverride`, and why it is an option

A second new component, `int lanesOverride`, 0 meaning "the JVM's preferred
width", which is what production always emits with (so `DEFAULTS.canonical()`
stays `""` and production hashes do not move). It exists because the machine
this project measures on has one width per JVM and the suite runs at two, so
without it the 8-lane and 2-lane arms and the fallback would never be
exercised. It belongs on the options record rather than on a static test hook
for the reason the record exists at all: it changes the emitted bytes, so it
has to be part of the shape key (`VarkaEmitOptions`' class doc, task 23).

### 3.6 What is deliberately unchanged

* **The hand-written kernels** (`DateVectorOps`, `ChronoVectorOps`) keep the
  generic helpers. A Java caller cannot select a method by a lane count
  without writing the switch it is trying to avoid, and these are the
  reference for the semantics, not for the lowering. The consequence is real
  and should be reported rather than hidden: the emitted rows will pull
  further ahead of the hand-written rows on masked shapes, because an emitter
  can specialise per machine width and hand-written Java cannot.
* **The dense value path**: no calls there since task 45, nothing to do.
* **Task 47's per-word accumulation**, and task 44's epilogue work.
* **The IR, the node set, the fragment mechanism, both pinned fixtures.**

### 3.7 The neighbour this changes the value of, and does not take

There is a larger idea next to this one, and it is now **task 70**
(`PLAN_MILESTONE_4.md` section 2.34), added out of this admission check. It is
written down here too, because what this task is worth depends on which of the
two lands first.

For a value root whose validity word is a pure AND/OR over input bitmaps -
which is every single-input calendar extraction, and `datediff` and the
arithmetic over two - the destination bitmap is a **bytewise function of the
source bitmaps over the whole batch**. It could be computed once per batch in
the driver, in `length/8` bytes of vectorised work, instead of one read and
one write per lane group: task 45's move, applied to the masked path. Where it
applies it removes both calls rather than making one of them cheap, and 2.4's
table bounds what that is worth: the masked `year` row would approach its
dense row (2291.3 towards 3446.0 at AVX-512; 809.7 towards 1336.2 at 128-bit),
which is more than this task can reach.

It is a different task, not this one, for three reasons. It is a driver-side
data movement plus a new analysis (is this root's word pure bitmap algebra
over inputs?), where this task is a call-naming change with no new analysis.
It cannot serve `Cond` roots or `IfElse` blends, whose words are computed from
comparisons rather than copied from inputs - so it does not remove the call
this task makes cheap, it removes some of its call sites. And it changes what
task 47 is left with, which is a scope question for the milestone rather than
for a plan. Row 69 is sequenced after this one and measured against a tree
that already carries it, so the two prizes are never counted twice.

### 3.8 What the compiled code said, and the second half

Added after the first measurement, from the compiled loop rather than from
the plan. The A/B measured the width-named helpers at +10% to +13% at AVX-512
and +16% to +20% at 128-bit on the masked calendar shapes, reproducibly across
two runs; the plan's mechanism said that was the write helper inlining. Three
readings of the JVM's own output said otherwise, in order:

1. **PrintInlining, read against the source.** The refusals that looked
   size-related - `callee is too large`, `callee uses too much stack` - are
   C1's (`c1_GraphBuilder.cpp`, `C1MaxInlineSize` 35 and `C1InlineStackLimit`
   10); `inline (hot)` is set only in C2's `bytecodeInfo.cpp`, and the one C2
   refusal, `NodeCountInliningCutoff`, hit the 212-byte writer and the 33-byte
   one exactly twice each. C2 was refusing both.
2. **The loop, not the nmethod.** `-XX:CompileCommand=print` over the masked
   `year` body through `VarkaAssemblyProbe`, which now takes an emit variant
   and runs the masked path: the whole nmethod differed by nine instructions,
   the *loop* by seven scalar ones (23 against 30, 53 vector in both), and the
   write helper was a `callq` in the loop in **both** arms. The seven were the
   read side - the general reader's `>>> (row % 8)`, which C2 cannot prove is
   zero at 16 lanes, plus the `lanes` argument materialised for the call.
   That was the whole measured win: the read, not the write.
3. **The instrumented JVM.** A print at the refusal in a fastdebug build
   (`src/hotspot/share/opto/bytecodeInfo.cpp`, the `NodeCountInliningCutoff`
   branch) gave the number: `unique()` was 18250 to 18520 against the cutoff
   of 18000, `incremental=0`, in both arms, for the standard and the OSR
   compile alike. The masked `year` body's Vector API intrinsics alone parse
   to about the cutoff, so whichever call comes *last in program order* is
   refused, whatever its size. That is also why task 32's
   `-XX:LiveNodeCountInliningCutoff=400000` moved nothing: that flag governs
   the incremental branch, and this refusal is the initial parse's, a
   develop-only limit no product flag reaches.

**The second half, then, is program order.** A value root's validity word is
known before its subtree is emitted whenever it is an input word or the
all-true constant - every calendar extraction and every arithmetic node over
columns - and the OR into the destination bitmap does not depend on the vector
store. Emitted first, C2 meets it at a few hundred nodes and inlines it; the
compiled loop has no call left, in either arm. `wordKnownBeforeCompute` is
the exact test: not "the root computes no word of its own", because
`Year(IfElse(...))` aliases the blend's *computed* slot, and reading it early
is a frame with no such local - the verifier rejected the first attempt. The
order rides `VarkaEmitOptions.validityOrFirst`, default on, off being the
after-the-store order as the reference variant, and two parity pairs price it
alone (`year` and the shared four-field shape, OR first against OR after, both
with the width-named helpers).

## 4. Files

| file | what |
|---|---|
| `VarkaVectorSupport.java` (engine) | the eight specialised helpers, with the javadoc saying which width each serves and why the aligned pair needs no shift |
| `VarkaVectorSupportWidthTest.java` (engine, new) | each specialised helper against the generic one; the `MaxInlineSize` size gate |
| `VarkaEmitOptions.java` | `validityByWidth`, `lanesOverride`, their `with*` methods and `@param` docs; `truncDate` added to `canonical()` (2.8) |
| `VarkaLoopEmitter.java` | the emit-time width, the concrete species, the constant lane count, the width-suffixed names, the fallback |
| `VarkaLoopEmitterSuite.scala` | section 5's emitter tests |
| `VarkaShapeCacheSuite.scala` | the `canonical()` completeness test |
| `VarkaDifferentialSuite.scala` (sql/core) | both settings through `setEmitOptionsForTesting` |
| `VarkaEmitterParityBenchmark.scala` + the two results files | the selection-root case (PR A) and this task's A/B pairs (PR B) |
| `SKILLS.md` | the per-call cost of a refused helper, that it is width-independent, and 2.4's read/write split |
| `PLAN_MILESTONE_4.md`, this file | row 46, an update note on 2.17 carrying 2.2's correction, section 9 |

## 5. Tests, and what each is for

* **The helpers** (engine). Every specialised helper against its generic
  counterpart over every row offset in a byte and every lane-bit pattern,
  including the patterns that must not touch a neighbouring byte; the
  aligned pair asserted to leave byte `row/8 + groupBytes` alone. The failure
  it catches: a hand-folded mask or shift that is right at 16 lanes and wrong
  at 4.
* **The size gate** (engine). Each specialised helper's `Code` length at or
  under the JVM's `MaxInlineSize`. This is the test that keeps the task's
  premise true as the file is edited later.
* **The emitted call** (emitter suite). For each of 2, 4, 8 and 16 lanes
  through `lanesOverride`: the constant pool names `orValidityBitsAt<N>` and
  the matching `SPECIES_<width>`, and names neither `SPECIES_PREFERRED` nor
  the generic helper. For a width with no specialisation (32): the fallback's
  names, which is what keeps 3.4 honest.
* **Off is today** (emitter suite). With `validityByWidth=false`: the generic
  names and `SPECIES_PREFERRED` in the pool, no width-suffixed symbol, and
  `checkMatrix` results identical to the on-arm at every null pattern and both
  widths. Results identical under both settings is the correctness statement;
  the constant-pool assertions are what say the arms actually differ.
* **Execution at the emitted width** (emitter suite). A kernel emitted at the
  JVM's own width, run: this is what turns a mistyped helper name into a test
  failure rather than a production `NoSuchMethodError` (2.9).
* **`Cond` roots** (emitter suite). A selection-root kernel in the dense body
  under both settings, asserting the bitmap bit for bit - 2.2's shape, the one
  the sequence's earlier tasks did not cover.
* **Differential** (sql/core), at both widths, both settings via
  `setEmitOptionsForTesting`: the existing suite is the oracle, unchanged, and
  the option must not change a single answer or a single fallback count.
* **The fuzzer** with the default on, and one seeded run with it off.
* **The pinned fixtures**: unmoved, asserted as they stand (2.7).

## 6. The measurement

`dev/varka_bench_regen.sh catalyst VarkaEmitterParityBenchmark`, both widths,
on an idle machine, compared by minimums across a second run for anything
under 1.3x. The A/B pairs, all on cases that already exist except the last:

| pair | what it isolates |
|---|---|
| `year`, mixed nulls, on / off | one write per group on the smallest masked shape |
| four fields shared, mixed nulls, on / off | four writes per group, the shape 2.17 was written about |
| `dayofweek`, mixed nulls, on / off | a masked shape whose arithmetic dominates |
| `year`, dense, `denseValidityOnce=false`, on / off | the write alone, no masked machinery - the cleanest number, and directly comparable to 2.3's table |
| selection root, dense and mixed, on / off | the filter kernel of 2.2, whose baseline PR A commits |

Beside the throughput, the deliverable row 46 actually names:
`-XX:+PrintInlining` over the parity run, narrowed with
`-XX:CompileCommand=option,*::loopMasked*,PrintInlining`, showing no
`failed to inline` for the specialised helpers where the generic ones were
refused. The diagnostic is the deliverable; the timing is the corroboration.

### 6.1 Predictions, registered before the run

1. `-XX:+PrintInlining` on today's tree shows `orValidityBitsAt` refused in
   every masked loop body and in the dense body of a selection kernel, and
   shows `validityBitsAt` **inlined** in at least the single-input masked
   shapes - 2.4's inference from the committed numbers. If the read is also
   refused, 2.4 is wrong about where the cost is and the outcome section says
   so before anything else.
2. Every specialised helper is at or under `MaxInlineSize`, and each is
   inlined at every call site the generic one was refused at.
3. The gain is bounded by 2.3: about 2 ns per lane group per removed call, so
   no masked row can pass its dense counterpart, and the `year` mixed-null row
   cannot pass 3446.0 at AVX-512 or 1336.2 at 128-bit. Within that bound,
   expect at least 15% at AVX-512 and at least 25% at 128-bit on the masked
   `year` row; below those the specialisation did not inline and prediction 2
   is what failed.
4. Every measured row gains **more at 128-bit than at AVX-512**, in the same
   ratio 2.3 gives (the same call amortised over four rows instead of
   sixteen). This is 2.17's argument, and it is the third task in a row to
   test it.
5. No dense value row moves at all - there has been no call there since task
   45 - and no committed number outside the masked and selection rows moves by
   more than the file's resolution.

## 7. Risks

1. **The specialised helper is inlined and the win is still small**, because
   the read-modify-write itself, not the call, is what costs. 2.3's per-call
   figure includes both, so this is the way prediction 3 fails; the outcome
   would then be the number that tells task 47 (accumulate in a register,
   store once per word) what is left, and that is a useful result rather than
   a wasted one.
2. **A mistyped or missing helper name** reaches production as a
   `NoSuchMethodError` on first execution, because the emitter names the
   helpers as strings (2.9). Mitigated by executing a kernel at the emitted
   width in the suite, and by the size-gate test enumerating the same eight
   names by reflection.
3. **Baking the concrete species changes every emitted class's bytes.** No
   pinned fixture covers the bytes (2.7), and the differential is the oracle,
   but the whole suite at both widths is what has to be green before the
   benchmark is believed - not a subset.
4. **A width nobody here can run** (SVE at 32 or 64 int lanes): served by the
   fallback, driven in the suite by `lanesOverride` rather than left to
   inference.
5. **Two new option components** widen the shape key. `DEFAULTS` must still
   render `""`; the `canonical()` completeness test of 2.8 is what keeps a
   third component from being forgotten the way `truncDate` was.

## 8. Sequencing

1. **PR (A), the baseline.** The selection-root parity case, dense and
   mixed-null, at both widths, with its results committed and no behaviour
   change - the project's rule that a case measuring something new lands
   before the change that improves it. It regenerates the parity files, so it
   waits for PR #130 (task 61), which regenerates them too; results files are
   regenerated, never merged textually.
2. **PR (B), this task.** The helpers and the size gate; then the emitter, the
   options and the emitter tests; then the differential and the fuzzer; then
   `-XX:+PrintInlining` for prediction 1 and 2, then the regeneration, section
   9, row 46 and the `SKILLS.md` lesson.

The two are independent of everything else open: nothing in the current stack
touches `VarkaVectorSupport`, the option record or the emitter's prologue.

## 9. Outcome

Three parity regenerations at both widths on the idle machine, each with the
canary passing and the scalar controls within 1.5%; two with the helpers only
(run 1 and run 2, whose A/B ratios agreed within 2 points on every calendar
row), one with the OR moved before the compute (run 3, the committed files).
`sql/catalyst/benchmarks/VarkaEmitterParityBenchmark-jdk25-results.txt` and
its `-128bit-` sibling hold run 3; runs 1 and 2 are quoted as ratios only.

### 9.1 The helpers alone (runs 1 and 2): real, smaller than predicted, and
for the wrong reason

| pair, mixed nulls unless noted | AVX-512 (run 1 / run 2) | 128-bit (run 1 / run 2) |
|---|---|---|
| `year` | +12.3% / +11.6% | +18.4% / +16.3% |
| `year`, dense + per-group OR | +10.5% / +10.5% | +17.9% / +16.5% |
| four fields shared | +13.7% / +12.7% | +20.7% / +19.0% |
| `dayofweek` | +0.3% / +1.4% | -6.4% / -5.9% |
| selection kernel | +40.3% / +53.4% | +4.9% / +4.3% |
| selection kernel, null-free | +1.7% / +26.9% | +0.0% / +2.5% |

The ratios reproduce where the absolute rates do not: the null-free selection
row measured 2.6x apart between runs on identical code, the bimodality task 32
and task 50 documented, here on a filter. Section 3.8 says where the calendar
rows' gain came from: the read helper's seven scalar instructions per lane
group, not the write, which stayed a call in both arms.

### 9.2 The order (run 3): the write inlines, and every masked kernel moves

Within run 3, OR first against OR after, both arms width-named:

| shape, mixed nulls | AVX-512 | 128-bit |
|---|---|---|
| `year` | 3203.0 against 2701.5, **+18.6%** | 1199.1 against 955.1, **+25.5%** |
| four fields shared | 1028.7 against 956.5, **+7.5%** | 420.5 against 351.0, **+19.8%** |

Across runs 2 and 3, same code but the order, every masked row in the file
moved and nothing dense did: `year` +20.0% and +29.8%, `month` +22.8% and
+40.5%, `dayofmonth` +20.9% and +37.1%, `trunc YEAR` +20.4% and +15.2%,
`weekofyear` +19.3% and +5.1%, the depth-4 arithmetic chain +22.8% and
+12.3%, `dayofweek` +7.7% and +4.5%; the 64-op fused shape +75.2% and the
budget-24 single-method shape +83.2% at AVX-512 and +180.1% at 128-bit, the
bodies furthest over the cutoff having had the most calls refused. The dense
`year` row is 3468.7 and 1338.3, +0.5% - it has had no call since task 45.
Against the file this task started from, the masked `year` row is 2291.3 to
3203.0 at AVX-512 and 809.7 to 1199.1 at 128-bit.

### 9.3 The helpers, once the write inlines

With the order fixed in both arms, width-named against general, run 3:

| shape | AVX-512 | 128-bit |
|---|---|---|
| `year`, mixed nulls | 3203.0 against 3346.7, **-4.3%** | -5.1% |
| `year`, dense + per-group OR | 3386.0 against 3378.9, +0.2% | 1171.6 against 1268.6, **-7.6%** |
| four fields shared, mixed nulls | 1028.7 against 928.4, **+10.8%** | **+27.3%** |
| selection kernel, mixed nulls | 22016.0 against 15349.3, **+43.4%** | +5.5% |
| selection kernel, null-free | 28893.1 against 22132.4, **+30.5%** | 6498.7 against 6349.3, +2.4% |
| `dayofweek`, mixed nulls | 7766.5 against 7722.3, +0.6% | -2.9% |

A single write per group no longer pays for the name: `year` is 4% to 8%
*slower* width-named. Four writes per group and the selection kernel still pay
well. The probe's reordered loops say why the single-write case can lose:
with the call gone, C2 unrolled the width-named `year` loop (172 vector
instructions in the body, against 64 for the general arm), and a body that
size at four lanes is task 32's register file. `validityByWidth` stays on -
the multi-write and selection shapes are the ones the columnar path runs -
with the single-write loss recorded here as the open item it is.

### 9.4 Predictions, scored

1. **Held, and misread.** PrintInlining showed `orValidityBitsAt` refused in
   the masked loop bodies and `validityBitsAt` inlined, as registered - but
   the reason was the caller's node count, not the callee's size, and the
   33-byte replacement was refused at exactly the same sites. The reader
   inlining is what the helpers' whole gain came from.
2. **Failed.** Two writers came in over `MaxInlineSize` (section 3.1), and no
   specialised helper was inlined anywhere the general one was refused, because
   the refusal was never about the callee.
3. **Missed as written, then overtaken by a different change.** The helpers
   alone gave 11.6% to 12.3% and 16.3% to 18.4% on the masked `year` row,
   under the 15% and 25% floors. The bound held throughout: no masked row
   passed its dense counterpart (3203.0 against 3468.7; 1199.1 against 1338.3).
4. **Held for `year` and the four-field shape, inverted for `dayofweek` and the
   selection kernel** - `dayofweek` lost 6% at 128-bit with the helpers alone,
   3% once the order was fixed.
5. **Half held.** The dense value rows did not move. "Nothing else moves"
   failed in every run: the hand-written kernels, unchanged, moved 5% to 14% in
   both directions between runs, and the null-free selection row by 2.6x.

### 9.5 What moved that the plan did not list

* The premise. Task 32's "the helpers do not inline in a wide loop" is true,
  and its reading - size - is not; `SKILLS.md` carries the correction.
* `VarkaAssemblyProbe` takes an emit variant and a masked run, which is how
  every claim above was checked; `VarkaEmitterParityBenchmark.emit` refuses a
  reused case id, after one regeneration died on a collision with the trunc
  block's computed ids twenty minutes in.
* `VarkaEmitOptions.canonical()` renders `truncDate` (2.8).

### 9.6 What this leaves

* **Task 70** (`PLAN_MILESTONE_4.md` 2.34), unchanged by any of this: the
  masked `year` row is still 8% under its dense one at both widths, and the
  bitmap algebra removes the read and the write rather than inlining them.
* **The single-write loss with the width-named helpers** (9.3): unrolling and
  the register file, a task 50 question; the option is there to turn.
* **Cold code.** The 2- and 4-lane writers sit over `C1InlineStackLimit`
  (stack 8, locals 9, five parameter slots: 12 against 10), so C1 calls them
  where it inlines the aligned pair. Steady state does not see it; the first
  batches of a short query do. Shedding the `long off` local would fit.
* **The harness.** A filter row that measures 2.6x apart on identical code is
  a row the file cannot quote a magnitude for; the debt register has the
  parity harness's per-regeneration cluster already, and this is a second
  face of it.
* **The node budget itself.** 18250 to 18520 nodes for one calendar extraction
  is the cost of the Vector API's `@ForceInline` chains, and the reason a
  second late call in a body - task 63's overflow check, task 52's guard on a
  wider shape - will be refused the same way. The order is a lever, not a
  fix; task 43 and task 44 own the size.
