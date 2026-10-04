# VARKA-153: what C2 refuses to lower, per shape and per width, read from its own log

## 1. Where this came from

`VarkaTimeBenchmark`'s 128-bit companion (VARKA-152, `VARKA-152.md` 6.5)
measured the 64-bit magic divide at one fiftieth of the conversion form and
`PrintIntrinsics` said why: at two 64-bit lanes this JVM has no lowering for a
masked long or double operation, or for a compare that produces a mask, and
the Vector API runs each as a Java loop over the lanes. Nothing throws, the
answer is right, the suites are green, and the rate is a per-lane rate. The
magic form is opt-in and could simply never be selected by width. The open
question was everything else the emitter builds the same way at the long lane
- the range guard, the checked add's overflow test, `CASE WHEN` over a 64-bit
comparison, every selection - all of which had shipped with 512-bit numbers and
no 128-bit number of their own, on a fleet whose NEON-only aarch64 hosts run at
exactly the species that refuses them.

`m5/PLAN.md` 2.89 opened this as an audit: one `PrintIntrinsics` run
per long-lane shape at `MaxVectorSize=16`, tabulated per node, and a home for
it in the gate so it cannot regress unseen.

## 2. What was built

**`VarkaWidthAuditSuite`**, with `VarkaWidthAuditShapes` and
`VarkaWidthAuditProbe`, in the catalyst test scope. The suite forks the probe
once per vector width (`-XX:MaxVectorSize=16`, `32`, `64`, and once at the
host's own) under `-Xbatch` and a `PrintIntrinsics` directive scoped to the
emitted classes. The probe emits every audited shape, runs each hot through
both its bodies - null-free and masked - and prints a marker before and after;
`-Xbatch` puts C2's compilation on the calling thread, so what C2 prints
between two markers belongs to the shape between them. The shapes are every
row of the coverage table as the compiler lowers it, keyed by its SQL, plus
fifteen hand-built constructions no row reaches: the checked long arithmetic
in its three modes, the range guard alone, both lowerings of the 64-bit
constant division, a long selection, a long `CASE`, a long three-valued
condition, `greatest` over longs, and int-lane twins of four of them as the
control.

Two things are done with what C2 said.

* **An invariant, on every host.** At the host's preferred width, no coverage
  row and no construction other than the opt-in magic form meets a
  `not supported` refusal. A failure names the shape, the operation and the
  CPU. This runs in the ordinary catalyst test shard and needs no
  disassembler, so unlike the assembly gate it runs on every runner class,
  which is the point: a refusal is a property of the machine.
* **A census, pinned.** `sql/varka/width_audit.json` records, per width the
  host can run, every line C2 printed for every shape, verbatim, with the host
  named. The comparison against the committed file runs only on the CPU model
  that took it and is cancelled with the reason elsewhere, since the census is
  a property of the CPU and the JDK. `VarkaCoverageSuite` renders the coverage
  table's new *128-bit lanes* column from it.

**Three kinds of line, one verdict.** C2 prints three kinds of line under
`PrintIntrinsics` for a vector call it did not inline on an attempt, and the
suite treats them differently on purpose (`isRefusal`):

* `** not supported: ...` is architectural: the matcher has no lowering for
  that operation at that lane count and element type on this machine, no later
  attempt changes it, and it is the kind behind the measured collapse. This is
  the only kind the invariant and the table's `per-lane` verdict rest on.
* `** missing constant: ...` says an argument was not yet a constant when a
  *late* inline was first attempted. C2 retries late inlines after further
  optimisation, so the line does not prove a fallback in the final code:
  `i + 1` prints two of them at 128 bits and the committed 128-bit parity
  results show it fully vectorised. Recorded, not asserted.
* `** unbox failed: ...` says a vector reached the call as a heap object,
  VARKA-55's boxing. In this census it appears only in the sequential run - one
  JVM through a hundred kernels - and never when the same shape runs alone,
  which is the second-species pollution the assembly gate's self-test
  demonstrates, now seen on shipped shapes. Recorded, not asserted.

## 3. What the census says

Laptop, AMD Ryzen AI 9 HX PRO 370, JDK 25.0.4, one JVM per width with the
hundred shapes run in file order.

**At 512 bits, the host's own width: no refusal in any shape.** The magic form
included. Four lines of the two non-verdict kinds appear (below).

**At 256 bits: no refusal in any shape**, the magic form included. So the
refusals are not "narrow"; they are two lanes.

**At 128 bits: every long-lane construction that touches a mask is refused,
and no int-lane one is.** Of the twenty long coverage rows, seventeen carry a
`not supported` line and three do not - `t - t2` and the two `time_diff`
rows, whose kernels are a conversion-form division and a subtraction with no
mask anywhere. The refused constructions, by C2's own names:

| construction | C2's line | where it is built |
|---|---|---|
| compare to mask | `op=comp/<n> vlen=2 etype=long ismask=usestore` | every `Compare` at the long lane: selections, `CASE` conditions, guards, overflow tests |
| blend | `op=blend vlen=2 etype=long ismask=useload` | `IfElse`, and the null-skipping `greatest`/`least` in the masked body |
| mask cast | `op=cast#462/3 vlen2=2 etype2=long` | the validity word to mask and back, in every masked body and every selection store |
| mask broadcast | `op=broadcast vlen=2 etype=long ismask=1` | `fromLong` of a validity word, `maskAll` |
| mask logic | `opc=430/432/434 vlen=2 etype=long ismask=1` | `And`, `Or`, `Not`, the guard accumulator |
| mask test | `op=test/4 vlen=2 etype=long ismask=1` | `anyTrue` on the condemning mask |
| masked operation | `opc=410/384 ... is_masked_op=1` | the magic form's masked `NEG` and masked `SUB` only |

The int-lane twins of the same constructions - the checked add, the selection,
the `CASE`, at four 32-bit lanes - print no refusal at 128 bits. The line is
two lanes of 64 bits, not masks in general.

**What that means for the fleet.** On this JVM, a NEON-only aarch64 host - two
64-bit lanes with no override - runs every long-lane selection, every long
`CASE`, every long guard and every checked long add as a per-lane Java loop,
correctly and at the rate VARKA-152 measured for the magic form. The
conversion-form division and the plain long arithmetic vectorise. Whether the
aarch64 JVM's matcher has the same table as this x86 one at `vlen=2` is not
established here - the audit runs where catalyst's tests run, which is x86 -
and section 5 says where that answer comes from. *It came on 20 September:
section 6. The aarch64 matcher refuses nothing, so this paragraph describes the
x86 JVM at a forced 128-bit species and not the fleet's aarch64 hosts.*

**The audit's first CI run, on the runner pool: the 64-bit converts below
AVX-512.** The invariant ran in #264's catalyst shard on an AMD EPYC 7763
(Zen 3, `UseAVX=2`, 256-bit species, four 64-bit lanes) and failed, naming six
shapes: `t - t2`, both `time_diff` rows, both `time_trunc` rows and the
construction `l / 3600000000000, conversion form` - every shape with a 64-bit
constant division and no other - each with two lines:

```
** not supported: arity=1 op=cast#510/3 vlen2=4 etype2=double ismask=0
** not supported: arity=1 op=cast#512/3 vlen2=4 etype2=long ismask=0
```

Those are `L2D` and `D2L`, the conversion form's two casts, refused at four
64-bit lanes. VARKA-88 built the magic form on the premise that "C2 does not
intrinsify the long-to-double casts under AVX2" and then left it opt-in,
because nothing had measured the premise on a real AVX2 host; this is that
premise, confirmed from C2's own log on the pool's commonest machine. So on
the AVX2 half of the fleet every `TIME` division kernel runs its conversions
per lane today, while the mask constructions - fine at four lanes - vectorise.
The invariant now expects exactly this refusal, in exactly these shapes, on a
host whose `UseAVX` is below 3 (`knownBelowAvx512`), the way the emitter's own
`convertsFallBack` reads the same level; what to do about it - select the
magic form by level after all, now that the evidence exists, or decline - is
VARKA-121's, and its section carries the lead. The census file's `host` block
records `use_avx` from here on.

**The two non-verdict kinds, at the preferred width.** `extract(YEAR FROM ym)`
and `extract(YEAR FROM ym) - 1` print `missing constant ... bitwise=ConI` at
every width, alone or in sequence: an int-lane `ConstDivide`'s conversion form
divides by `DoubleVector.div(double)`, whose broadcast of the divisor runs on
the vector instance's own species, which C2 cannot prove constant after a
half-width `convertShape`. That is the shape VARKA-149 found slower than a
scalar loop at 128 bits and only 1.30x at 512, and this is its first concrete
lead; it is written into 2.85 rather than fixed here, because the fix moves
emitted bytes. `add_months(d, i)`, `d + ym` and `d - ym` print `unbox failed`
at 512 bits in the sequential run and nothing when run alone - the pollution
case, which is real in a production JVM that has compiled many kernels and
belongs with VARKA-55's lineage.

## 4. Files

* `sql/catalyst/src/test/.../varka/VarkaWidthAuditShapes.scala`,
  `VarkaWidthAuditProbe.scala`, `VarkaWidthAuditSuite.scala` - new.
* `sql/varka/width_audit.json` - new, the census.
* `.../varka/VarkaCoverageSuite.scala` and `docs/sql-varka.md` - the *128-bit
  lanes* column, rendered from the census.
* `sql/varka/plans/m5/PLAN.md` - row 153 closed, section 2.89's
  outcome, the lead appended to 2.85; `sql/varka/skills/the-jit.md` - the
  lesson.

## 5. What this leaves

* **The mask-free forms, or the decline.** Per refused construction the choice
  2.89 named: a compare folded into arithmetic where the semantics allow, or a
  compile-time decline of long-lane shapes that need a mask when the species is
  two lanes. The audit makes the choice per construction rather than per
  benchmark; it does not make it. It belongs with the split form's guard
  (VARKA-102 group C), which is the next long-lane kernel to be designed and
  the first that can be designed knowing this.
* **The aarch64 answer.** *Answered, section 6:* the census is x86 at a forced
  width, and the arm runner's own census refuses nothing.
* **VARKA-149's lead**, above, and **the pollution boxing** on three shipped
  shapes, which the assembly gate's allocation check could cover if its probe
  ran the shapes in sequence rather than one per JVM.
* **VARKA-121's decision**, now evidence-backed: on AVX2 hosts the conversion
  form is per-lane and the magic form is the only vector lowering of the
  64-bit division; whether `useAVX` should select it, against the shape-hash
  argument that made `DEFAULTS` host-independent (`VARKA-88.md` 9.3), is
  the question the runner's numbers were always going to decide.
* **The census as a runner census.** The suite prints its table in the catalyst
  shard's log on every CI run, on whatever runner class the shard lands on, so
  the fork's logs accumulate the answer per machine class for free.

## 6. The aarch64 answer, 20 September 2026

`.github/workflows/varka-width-audit.yml` builds the catalyst test classes on
each runner architecture the pool offers and regenerates the census there; the
first run's `aarch64` job took it on a Neoverse N2 (`CPU part 0xd49`, GitHub's
`ubuntu-24.04-arm`, `asimd sve sve2`, JDK 25.0.4, `MaxVectorSize=16`, so a
128-bit species, `UseSVE=2`).

**C2 refused nothing.** Across the hundred shapes - every coverage row and the
fifteen constructions - the census at 128 bits holds no `not supported` line.
Every long-lane selection, `CASE`, guard, checked add and masked operation that
the x86 JVM runs as a per-lane Java loop at two 64-bit lanes is lowered by the
aarch64 matcher at the same lane count. What the file does hold is 42
`missing constant` lines and two `unbox failed`, C2's timing lines, which VARKA-154 removes from the census and which the workflow's summary at first counted
as refusals; the corrected summary counts `not supported` alone.

So section 3's fleet paragraph was the x86 matcher's table, not two-lane
arithmetic: the refusal of masked 64-bit operations at `vlen=2` is a property of
the x86 back end at a species below its own width, and the fleet's aarch64
hosts, whose native species *is* 128 bits, run the long-lane guards vectorised.
Three things follow.

- The coverage table's *128-bit lanes* column is this laptop's census at a
  forced width, which is what its caption now says; the column is right for an
  x86 host at `-XX:MaxVectorSize=16` and wrong as a statement about NEON.
- The mask-free forms or the decline that section 5 left to the next long-lane
  kernel design are an x86-at-128-bits question, and 128 bits is not where x86
  runs; the choice loses most of its urgency.
- VARKA-156's half-species store exists to escape a masked store's bounds branch
  at two lanes; on the host class that actually runs two 64-bit lanes the masked
  operations are lowered, and whether the branch and the lost unrolling still
  cost there is that task's measurement to take, not this census's to assume.

**The x86 runner, for the comparison.** The same run's `x86_64` job landed on an
AMD EPYC 7763 (Zen 3, `UseAVX=2`, `MaxVectorSize=32`, so a 256-bit species). At
a forced 128-bit species it refuses what this laptop refuses: 32 of the hundred
shapes, every one a long-lane mask construction at two lanes - compare to mask,
blend, the validity word's casts, mask broadcast - and no int-lane one. At its
own 256 bits it refuses only the 64-bit lane's `L2D` and `D2L`, in the six
division shapes, which is #264's finding reproduced from the census rather than
from the invariant's failure. So the two-lane table is the x86 back end's on Zen
3 as on Zen 5, and it is not aarch64's.

One qualification. The runner carries SVE2 and the JDK enables it
(`UseSVE=2`); the matcher's masked lowerings at 128 bits may be SVE's predicate
registers rather than NEON's, and a NEON-only host - Graviton2, Apple silicon
under a JVM without SVE - has not been audited. The workflow asks the question
of whatever arm runner the pool offers, and its answer names the part.

