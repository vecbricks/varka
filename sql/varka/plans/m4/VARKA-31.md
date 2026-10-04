# VARKA-31: assert the instructions, not the ratio

## 1. What you are building

`m4/PLAN.md` 2.2 / task row 31. A test that reads the machine code C2
produced for a Varka kernel and asserts that the *instruction family* the shape
should produce is present - so that "this loop vectorized" stops being an
inference from a throughput ratio and becomes an assertion that names the method
and the missing family when it fails.

The case for it is on record and is not a matter of taste. Every vectorization
claim this project makes today comes from the parity gate: "the emitted loop is
within 0.9x of the hand-written kernel", standing in for "C2 intrinsified the
Vector API calls". VARKA-24 measured how weak that inference is - the same
kernels moved 50-190% under
`-XX:CompileCommand=inline,jdk/incubator/vector/*.*` in the engine's JMH harness
and under 1% under the same flag in the catalyst harness. A ratio moves for
reasons that have nothing to do with the instructions emitted. Reading the
instructions cannot.

This task is scheduled before VARKA-25 because it is VARKA-25's instrument: an
unroll factor chosen from throughput alone is chosen from the same weak signal.

## 2. The feasibility check, done

Recorded here before any suite work, the way VARKA-26 and VARKA-53 record theirs,
because the whole task rests on one question: can the *product* JVM disassemble?

It can, and it was run rather than reasoned. `SKILLS.md`'s note that HotSpot's
fourth fallback for locating the disassembler is `hsdis-<arch>.so` on
`LD_LIBRARY_PATH` was exercised against the system JDK 25.0.4 product build,
with the `hsdis-amd64.so` built earlier for the fastdebug tree:

```
LD_LIBRARY_PATH=/home/max/proj/openjdk-build/jdk25/build/\
linux-x86_64-server-fastdebug/images/jdk/lib \
  java -XX:+UnlockDiagnosticVMOptions -XX:CompileCommand=print,Probe::add ...
```

over a trivial `o[i] = a[i] + b[i]` loop. The C2 nmethod came back disassembled,
and the body is exactly the shape this task asserts on:

```
vmovdqu32   0x10(%rdx, %rax, 4), %zmm0
vpaddd      0x10(%rsi, %rax, 4), %zmm0, %zmm0
vmovdqu32   %zmm0, 0x10(%rcx, %rax, 4)
```

Three facts fall out of that run and shape the design below.

**`CompileCommand=print,<class>::<method>` is the right flag, not
`-XX:+PrintAssembly`.** The milestone section names the latter. `print` is
per-method: it emits one nmethod's disassembly rather than the whole compilation
log, which is the difference between parsing a few hundred lines and parsing
tens of megabytes. `-XX:+UnlockDiagnosticVMOptions` is still required. This is a
deviation from 2.2 and is deliberate.

**Both a C1 and a C2 nmethod are printed for the same method**, headed
`C1-compiled nmethod` and `C2-compiled nmethod`. The assertion must read the C2
one and ignore C1, which is scalar by construction - asserting over the
concatenated output would pass or fail for the wrong reason.

**Mnemonic and operand are separated by a tab, not spaces**, in this hsdis
build's output. A regex written for spaces silently matches nothing, which is
the failure mode where a detector that never fires passes vacuously. Section 5's
self-test exists because of this.

## 3. What is asserted, and what is deliberately not

### 3.1 A family derived from the host, never a mnemonic

The lane width is a property of the machine - `zmm` under AVX-512, `ymm` under
AVX2, `xmm` under the narrow-vector run and under `-XX:MaxVectorSize=16`, NEON
on aarch64 - so a hard-coded mnemonic is a test that fails on the next runner.
The expected register class is derived at runtime from
`IntVector.SPECIES_PREFERRED.vectorBitSize()` in the *child* JVM (the one whose
flags are in force), reported back to the parent, and the assertion is:

> at least one instruction in the C2 nmethod for `<method>` whose mnemonic is in
> family F and one of whose operands names a register of the expected class.

Families are small named sets, defined once with their members:

| family | x86-64 members | what it proves |
|---|---|---|
| `PACKED_INT_ADD` | `vpaddd`, `paddd` | lanewise `add` intrinsified |
| `PACKED_INT_MUL` | `vpmulld`, `pmulld` | the magic multiplies did |
| `PACKED_INT_SHIFT` | `vpsrld`, `vpsrad`, `vpslld` | the magic shifts did |
| `PACKED_LOAD_STORE` | `vmovdqu32`, `vmovdqu`, `vmovdqa32` | the loop moves vectors, not ints |
| `PACKED_COMPARE` | `vpcmpd`, `vpcmpeqd`, `vpcmpgtd` | mask generation did |

aarch64 members are left unfilled with a recorded reason: nothing in this
project has run on aarch64, and inventing a mnemonic list that has never been
matched against real output is how a test acquires a wrong constant. The suite
skips on a host whose architecture has no table, and says so.

### 3.2 The interesting negative is a scalar body

The failure this test exists to catch is not "a different vector instruction" -
it is a body that came out scalar because an intrinsic did not fire. So the
failure message names the method, the family that was missing, the register
class that was expected, and the count of instructions that *were* found in the
scalar counterpart family (`addl`/`imull`/`sarl`), because "0 packed adds, 47
scalar adds" is a diagnosis and "assertion failed" is not.

### 3.3 What is not asserted

**Counts, ratios and code size.** A count is a promise about C2's scheduling
that this project cannot keep across JDK updates; VARKA-32's own bimodality
investigation found identical vector-op counts with a 2x instruction-count
difference, so a count assertion would have gone red on a register-allocation
roll with nothing wrong. Code size belongs to VARKA-50's JFR signal, not here.

**Anything about performance.** This suite reads instructions. It never times
anything, and it must not grow a timing assertion later - that is what the
parity gate is for, and the whole point of this task is that the two signals are
independent.

## 4. The mechanism

### 4.1 A forked JVM, driven from an ordinary suite

The suite cannot set JVM flags on itself, so each case forks a child:

```
java -XX:+UnlockDiagnosticVMOptions
     -XX:CompileCommand=print,<pattern>
     [-XX:MaxVectorSize=16]                  # the narrow-width run
     --add-modules jdk.incubator.vector
     -cp <the test classpath>
     org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaAssemblyProbe
     <case name>
```

`VarkaAssemblyProbe` is a new test-scope Java main class. It takes a case name,
builds the kernel for that case, runs it hot enough to reach C2 (a fixed
iteration count, not a timer - this is not a measurement), prints one machine
readable line giving the host's preferred vector bit size, and exits. The parent
captures stdout+stderr, splits it into nmethods, keeps the C2 one, and applies
section 3.1's rule.

The classpath is taken from the parent's own `java.class.path`, and the `java`
binary from `java.home`, so the child is the same JVM the suite runs under. No
`build/sbt` re-entry, no shelling out to a wrapper.

### 4.2 Locating hsdis, and proving it actually worked

Search order, first hit wins:

1. `-Dvarka.hsdis.dir=<dir>` on the parent (the explicit escape hatch),
2. `VARKA_HSDIS_DIR` in the environment,
3. the current `LD_LIBRARY_PATH`,
4. `<java.home>/lib/server/`, where HotSpot looks natively.

The directory is passed to the child through `LD_LIBRARY_PATH`.

Detection is **not** "the file exists". It is a property of the output: with no
disassembler HotSpot prints `Loading hsdis library failed` and degrades to
bytecode-level output, so the parent looks for an actual `[Disassembly]` section
containing hex-addressed instruction lines. If it is absent, the case **skips
with a message naming which of the two happened** - no disassembler found, or
found and refused to load. `assume`, not `fail`, per the milestone's clean-skip
requirement, because a CI runner without hsdis is the expected case and a gate
that goes red for missing tooling is a gate people delete.

### 4.3 Naming the methods

Two kinds of target, and the kernels come first so a regression is attributable:

* **Engine kernels** are ordinary named methods:
  `org/apache/spark/sql/varka/vector/ChronoVectorOps::<method>`.
* **Emitted loops** live in classes named for their shape hash -
  `org.apache.spark.sql.varka.execution.VarkaFusedProjection_<16 hex>`
  (`VarkaShapeCacheImpl.classNameFor`) - with method names VARKA-24 made stable
  (`loopDense0`, `loopMasked0`, `epilogueDense`, `epilogueMasked`). The
  `CompileCommand` pattern therefore wildcards the class and names the method
  exactly. **Corrected in commit 2:** HotSpot rejects `/` and `::` in the same
  pattern - `Method pattern uses '/' together with '::'`, and the VM refuses to
  start - so the spelling is
  `print,org.apache.spark.sql.varka.execution.VarkaFusedProjection_*::loopDense0`,
  dots throughout. The probe prints the hash it actually emitted so a failure can
  be tied to a shape.

## 5. Tests

The suite is the deliverable, so "tests" here means the cases and the one test
that tests the test.

1. **The self-test, and it comes first.** A deliberately scalar method in the
   probe - a plain `for` loop over an `int[]` with `-XX:-UseSuperWord` so it
   cannot auto-vectorize - asserted to contain **no** member of
   `PACKED_INT_ADD`, and a deliberately vector one asserted to contain one.
   Without this pair the whole suite can pass vacuously: a mnemonic regex that
   matches nothing looks exactly like a body with no vector instructions, and
   section 2 records that this build's tab separator makes that a live risk
   rather than a hypothetical one.
2. **The kernels**, one case each over `ChronoVectorOps` and `DateVectorOps`:
   `PACKED_LOAD_STORE` plus the family that shape's arithmetic needs.
3. **The emitted loops**, one per gating shape: the `dayofweek` magic lowering
   (mul and shift), a calendar extraction (`year`, so the prefix's mul/shift
   chain), and a comparison-producing shape (`PACKED_COMPARE`).
4. **Both widths.** Every case runs again with `-XX:MaxVectorSize=16` in the
   child, where the expected register class becomes `xmm` and the assertion must
   still pass. This is the case that catches a hard-coded `zmm`.

## 6. Files

| file | what |
|---|---|
| `sql/catalyst/src/test/java/.../varka/VarkaAssemblyProbe.java` | new; the child main, one method per case, the preferred-width line |
| `sql/catalyst/src/test/scala/.../varka/VarkaAssemblySuite.scala` | new; the fork, the nmethod split, hsdis detection, the family table, the cases |
| `sql/varka/plans/m4/VARKA-31.md` | this file, with section 9 filled in on landing |
| `sql/varka/plans/m4/PLAN.md` | row 31 marked planned; 2.2's mechanism corrected by section 2's update note |
| `SKILLS.md` | the four details that decide whether a disassembly-reading test works or quietly passes |

Nothing else. That is deliberate and is stated in section 8.

## 7. The second half: forcing C2 to inline Varka's own packages

`m4/PLAN.md` 2.2 gives this task a second question the owner deferred
into it: VARKA-24 measured `-XX:CompileCommand=inline,jdk/incubator/vector/*.*`
(the JDK half) and found the 50-190% swing was a fact about the JMH harness, not
about Varka. The same flag aimed at `org/apache/spark/sql/varka/**` and at the
emitted classes' package is untested.

It belongs here because both halves answer "what did C2 actually do" with
evidence rather than a ratio, and because this task's harness already forks a
JVM with arbitrary flags. The deliverable is **not** a flag: a JVM flag would
have to be set on every executor, so it cannot be the shipped answer. It is
either a documented recommendation in `docs/sql-varka.md` or a recorded decline,
and the evidence is instruction-level - does the flag change what is emitted, or
only what a benchmark reports.

This is commit 4 and is allowed to conclude "no change, declined".

## 8. Sequencing, and why this task touches so few files

Four commits, each green on its own:

1. **This plan**, with section 2's feasibility check.
2. **The harness and the self-test**: the probe, the fork, hsdis detection, the
   family table, and test 1 - the scalar/vector pair. Nothing about Varka yet;
   the instrument is proved before it is pointed at anything.
3. **The cases**: tests 2 through 4, kernels then emitted loops, both widths.
4. **The inline question** (section 7), and section 9.

`m4/PLAN.md`'s row 31 and the `SKILLS.md` entry land in commit 1, with
this plan. An earlier draft deferred both to a follow-up after PR #78 merges,
since that PR holds those two files and this task was chosen for being able to
run to completion without touching anything either open PR holds. **The owner
overruled the deferral** - "I won't merge it before #78" - and the reasoning is
worth keeping: merge ordering is the owner's to manage, and a textual conflict
already being sequenced around is not a cost worth restructuring a task for. A
deferred documentation commit is a promise rather than a fact, and this session
had already spent a separate PR (#87) correcting a plan that had been left
standing while it was known to be wrong.

Choosing a task that does not collide with in-flight PRs remains useful. Carving
the documentation out of that task once chosen does not.

## 9. Predictions, registered before the work

1. The kernels pass on the first run at both widths. They are hand-written over
   `IntVector` and the parity gate already implies they vectorize; if one of
   them does not, that is the single most valuable thing this task could find.
2. At least one **emitted** loop needs an adjustment to the `compileonly`/`print`
   pattern before it prints anything - the shape-hash wildcard is the part with
   no precedent in this repo.
3. The narrow-width run is where a bug in this suite shows up, not the wide one,
   because `xmm` is also what a *scalar-with-SSE* body uses for some operations;
   the family table must be checked against real narrow output rather than
   assumed to work by symmetry.
4. Section 7's inline flag changes nothing at the instruction level for the
   emitted classes, and the outcome is a recorded decline.

## 10. Risks

1. **A detector that never fires.** The single largest risk, and section 5's
   self-test is the entire mitigation. Any change to the parsing must keep that
   pair green.
2. **hsdis absent on CI.** Expected, and handled by skipping with a message that
   says which failure mode occurred. The suite is a developer-machine gate.
3. **Output volume.** `print` on one method is small; a mistaken pattern that
   matches every method is not. The parent caps captured output and fails with a
   clear message rather than buffering without bound.
4. **A flaky C2.** A method may not reach C2 within the probe's fixed iteration
   count on a slow runner. The probe asserts it saw a C2 nmethod at all before
   the family assertion runs, so the failure says "never compiled at tier 4"
   rather than "missing vpaddd".
5. **Reading the wrong nmethod.** C1's body is scalar by construction. Splitting
   on the nmethod headers and keeping only the C2 one is not optional.

## 11. Commit 2's outcome: what the self-test caught

Section 5 argued for building the instrument and proving it before pointing it
at anything Varka-specific. It found three defects, and the point worth
recording is that **not one of them was an error** - each produced a plausible
result that a suite without a self-test would have shipped.

1. **The `CompileCommand` pattern was malformed** (section 4.3, now corrected).
   `/` and `::` cannot be mixed; the child JVM refused to start. Because the
   suite checked only for disassembly, it reported "no disassembly in the child's
   output" - which reads as missing tooling. A child that dies is a bug, not a
   missing disassembler, so `requireHealthyChild` now checks the exit code first
   and fails rather than cancels.
2. **A hex dump parsed as instructions.** With no usable disassembler HotSpot
   prints the nmethod under `[MachCode]` as raw hex words, and those lines carry
   the same `0x<addr>:` prefix an instruction line does. 68 of them parsed as
   instructions, and all three cases reported "expected at least one packed
   integer add ... and found none. The intrinsic did not fire" - a confident,
   specific, entirely false diagnosis. Requiring the mnemonic to begin with a
   letter does *not* fix it, since hex words often do (`ff1f`, `e929`, `c349`);
   the fix is to collect instructions only inside a `[Disassembly]` block.
3. **The skip message contradicted itself.** `Loading hsdis library failed` is
   printed both when HotSpot found nothing and when it found something
   unloadable, so keying off it produced "a disassembler was found but HotSpot
   refused to load it (hsdis: NoHsdis)". The discrimination now uses this suite's
   own search result.

Defect 2 is the one that justifies the whole approach. Without the pair, the
scalar case would have passed (it found no packed add in garbage) while the
vector case failed, and the natural reading of that pattern is "the detector
works and the Vector API did not intrinsify" - a conclusion about HotSpot drawn
from a parser bug.

Both paths are now exercised: with `-Dvarka.hsdis.dir` pointed at a built
`hsdis-amd64.so` all three cases pass, and with nothing configured all three
cancel with a message naming what was searched. The instruction counts behind
the assertions were checked by hand against the same runs - `vectorAdd` carries
7 `vpaddd` and 24 `%zmm` operands, `scalarChain` carries none of either.

## 12. Commit 3's outcome: the cases, and the predictions scored

Nine cases, all green at both widths: the three self-tests, `vectorAddDays` and
`vectorFourFields` over the hand-written kernels, three emitted loops (`year`,
`dayofweek`, and a comparison), and a 128-bit run over three of them.

**The kernels and the emitted loops all vectorize**, which is the answer this
project has been assuming without evidence. Counts from the wide run, for the
record - the suite asserts presence, never these numbers:

| body | packed add | multiply | shift | compare | `%zmm` operands |
|---|---|---|---|---|---|
| `DateVectorOps::vectorAddDays` | 5 | 0 | 0 | 0 | 23 |
| `ChronoVectorOps::vectorFourFields` | 15 | 13 | 7 | 0 | many |
| emitted `year` `loopDense0` | 10 | 8 | 4 | 0 | 63 |
| emitted `dayofweek` `loopDense0` | 65 | 26 | 39 | 0 | many |
| emitted comparison `loopDense0` | 0 | 0 | 0 | 15 | many |

The `dayofweek` row is the one worth reading twice: VARKA-14's range-narrowed
magic exists precisely to avoid a scalar remainder, and its multiply and shift
are packed, so the lowering bought what it was supposed to buy.

### Predictions, scored

1. **Held.** Both kernels passed on the first run, at both widths.
2. **Held in substance, missed in place.** A `CompileCommand` pattern did need
   adjusting, but not for an emitted loop - the shape-hash wildcard worked the
   first time. It was the *probe's own* pattern, in commit 2, mixing `/` with
   `::` (section 11). The emitted-loop wildcard, the part with no precedent in
   this repo and the reason the prediction was written, was the part that worked.
3. **Missed.** The narrow-width run passed first try. The family-table bug
   appeared in the *wide* run instead, and on the case the prediction did not
   name: the comparison. `vpcmpd`/`vpcmpeqd`/`vpcmpgtd` matched nothing, because
   AVX-512 folds the predicate into the mnemonic and `a > b` on int lanes comes
   out `vpcmpnled`. The fix is a pattern rather than a longer list - enumerating
   a dozen predicate suffixes is a list that goes stale on the next lowering
   change. The prediction's *reasoning* was right (a family table written from
   one host's output is where this breaks) and its target was wrong.
4. Commit 4's question; not yet answered.

The rule that survives all three: derive the family from output actually read on
the host, never from a mnemonic list written from memory. That is now in
`SKILLS.md` beside the rest.

## 13. Commit 4's outcome: the inline question, and the decline

Section 7's question - whether forcing C2 to inline Varka's own packages
changes anything - is answered, and the answer is a **recorded decline**, but
not for the reason prediction 4 gave. The flag is not a no-op. It changes the
compiled shape, and the change is one this project would not want.

The directive is
`-XX:CompileCommand=inline,org.apache.spark.sql.varka.*::*` plus the same for
`org.apache.spark.sql.catalyst.expressions.codegen.varka.*::*`.

**The loop bodies do not move at all.** The standard C2 nmethod for the emitted
`year` loop is 327 instructions with 10 `vpaddd`, 8 `vpmulld`, 4 `vpsrld` and 8
`vpsubd` - identical under both configurations, over three runs each, with zero
variance between runs. `ChronoVectorOps.vectorFourFields` is likewise identical
at 1174 instructions, and the emitted `dayofweek` body at 532. So nothing about
vectorization improves, which is what prediction 4 anticipated.

**What does move is the method boundary, and it moves the wrong way.**

| method | baseline | with the directive |
|---|---|---|
| emitted `loopDense0` | 327 insns, 10/8/4/8 | 327 insns, 10/8/4/8 |
| emitted `runDense` | 243 insns | no standard nmethod at all |
| emitted `run` | 271 insns, **no vector ops** | 471 insns, **10/8/4/8** |

The driver absorbs the loop. `run` grows by 200 instructions and acquires the
entire year lowering's vector mix, `runDense` stops being compiled separately,
and the emitted code now exists in two places rather than one.

That is precisely the structure VARKA-24 built and `GROUP_BUDGET` exists to
control. The emitter splits bodies into sibling methods on purpose - to keep any
one method off C2's compile cliff and out of the register pressure that
`SKILLS.md`'s bimodality section traces to a 936-byte hand-written body at
128-bit lanes. A JVM flag that re-fuses them is working against the emitter's own
design, and it would have to be set on **every executor** to do so.

`-XX:+PrintInlining` says why the bodies themselves survive: forcing the
directive overrides the size heuristic but lands on a harder limit.
`VarkaVectorSupport::orValidityBitsAt` (212 bytes) goes from
`failed to inline: callee is too large` to
`failed to inline: NodeCountInliningCutoff`, and `epilogueDense` (490 bytes)
does the same. The reason changes; the outcome does not. Worth noting in
passing that `orValidityBitsAt` declining to inline is **VARKA-46's subject**,
observed here as a side effect rather than as this task's business - and this
run is evidence that VARKA-46 cannot fix it with a flag either.

**The decline, stated plainly.** No recommendation goes into
`docs/sql-varka.md`. The directive does not improve the instructions, it fuses
methods the emitter deliberately separates, and it would need setting on every
executor to have even that effect. If method-boundary fusion is ever worth
measuring, the emitter can produce it directly through `GROUP_BUDGET` - VARKA-32
step B2 already did exactly that with `withGroupBudget(200)` - which is
measurable, per-shape, and needs no JVM flag.

Two committed cases keep the finding from rotting: the emitted loop body and the
hand-written kernel are both asserted still vectorized *under* the directive. The
assertions are the weak ones - the families are present - deliberately, because
an equality assertion over instruction counts across two configurations is the
brittle shape this suite refuses everywhere else.

### Prediction 4, scored

**Half right, and wrong in the interesting half.** "Section 7's inline flag
changes nothing at the instruction level for the emitted classes, and the
outcome is a recorded decline." The outcome is a decline and the loop bodies are
untouched, so the visible half held. But "changes nothing" is false: the flag
inlines the driver into `run` and duplicates 200 instructions of vectorized body
with it. Had the measurement stopped at `loopDense0` - which is where it started,
and which is the method this suite was built to read - the conclusion would have
been right by accident, for a reason that is not true.
