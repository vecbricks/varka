# Standard mode: where the SQL standard would buy a faster kernel

*Opened 18 September 2026. A register, not a plan: it accumulates across
milestones as tasks meet these cases, and nothing in it is built until a task
claims it.*

## 1. The rule this register exists under

**Varka's row answers follow vanilla Spark, always.** The correctness model is
that enabling Varka changes nothing a query returns: the differential suites
assert every covered expression "matches the row engine under both consumers",
and the ghost fallback routes a declined batch to the row engine *mid-query*, so
one query can be served partly by each and the two halves must agree row for row.
A kernel that computed a standard-conformant value where Spark computes something
else would break both.

*One deliberate exception, the owner's decision of 14 October 2026: the math family's ULP contract
(3.4), behind a configuration that is on by default. It is the only place a fused answer may
differ from Spark's, and it says by how much.*

*30 October 2026, by the owner's word: the exception is now opt-in. Every math function is bit-exact
to vanilla Spark by default, and the ULP contract of 3.4 is a mode a session turns on, its
configuration off by default (`m8/PLAN.md` 2.7.1, `m8/VARKA-345.md` 2.1 and 2.6). So the default has
no exception: with Varka enabled and the mode off, no fused answer differs from Spark's.*

**And the SQL standard is still worth reading first**, because Spark's limits are
often storage accidents rather than declared types, and a narrower *proven* range
is exactly what Varka's lowerings want. `YearMonthIntervalType` has no precision
parameter at all, and its own comment says the year bound exists "to fit to
`Int`"; the standard makes the range part of the type, through an *interval
leading field precision* that defaults to two digits. The standard's version is
both better typed and cheaper to compile.

So this file records, as they are found: what the standard permits, what Spark
permits instead, and what the difference would buy. Finding one is not licence to
act on it.

## 2. Why this need not change any answer

The obvious reading of "a flag that deviates from Spark" is a semantics switch,
and that reading is what section 1 rules out. There is a better one, and the
machinery for it already exists and is load-bearing.

Varka already carries **per-batch input bounds**: `VarkaInputBound(inputIndex,
lo, hi)`, checked before the kernel runs by `IntRangeOps.allWithin` over the live
lanes, declining the batch with `STATUS_INPUT_BOUND` when a lane is outside. The
row engine then recomputes that batch and returns Spark's answer. It is used
today for `CAST(i AS INTERVAL DAY)`, whose bound Spark's own cast throws past, and
its cost is already priced in `VarkaThroughputBenchmark` against the unbounded
row.

So standard mode is not a semantics switch. It is **an assumption about the data
plus a cheap runtime check**:

1. assume the operand respects the standard's declared range,
2. compile the cheaper lowering that the range makes exact,
3. register the range as an input bound,
4. and let a batch that violates it decline to the row engine.

**No answer ever differs**, the ghost fallback stays sound, and the differential
suites keep passing unchanged. What the flag costs a user whose data is outside
the standard's range is speed, not correctness - their batches decline - which is
why it is a flag and not a default.

The register below should therefore say, for each entry, *which bound would be
registered* as well as what it saves. An entry whose cheaper lowering cannot be
guarded this way is a different and much weaker proposition, and should say so.

## 3. The register

### 3.1 Year-month interval range: two lane ops instead of seven

*Found 18 September 2026, VARKA-89. The delta it reads as a speedup is
`interval_precision_unbounded` (`proofs/spec/DELTAS.md` 1.6, VARKA-244).*

| | range |
|---|---|
| **Standard** | leading field precision, default 2 digits: `INTERVAL YEAR` is years 0-99, so months within about `[-1199, 1199]`; wider needs an explicit `INTERVAL YEAR(9)` |
| **Spark** | years `[0, 178956970]`, months `[0, 11]`, the total bounded only by int32 |

**What it would buy.** `extract(YEAR FROM ym)` is `months / 12`. The int-lane
magic multiply for `/12` is exact over `0..49,151` - about one forty-thousandth
of int32 - which is why VARKA-89 had to lower it through VARKA-88's double-lane
route at **seven lane ops** (two `I2D`, two divides, two `D2I`, one `or`). Under
the standard's default precision the dividend is at most about 1199, far inside
49,151, so the magic serves: **two lane ops**, plus one compare for the bound.

**The bound to register:** the interval column, `[-1199, 1199]` for the default
precision, or `[-(10^p - 1) * 12 - 11, ...]` for a declared `INTERVAL YEAR(p)`.

**Caveat worth keeping.** Spark's documented maximum is not jointly attainable:
`INTERVAL '178956970-11' YEAR TO MONTH` is 2,147,483,651 months and
`Math.toIntExact` throws. And no literal can spell `Int.MinValue`, because the
sign is applied to a non-negative magnitude - but `CAST(i AS INTERVAL MONTH)` is
the identity on the lane, so a stored column can hold any int32 regardless. The
bound check is what makes that safe.

**The theorems** (`proofs/refinement.smt2`, VARKA-245). "extract(YEAR FROM ym), Spark mode and
standard mode: one statement" holds over every month count: the kernel today is the multiply-high
`int_mulhi_divide.smt2` proves exact over all of int32 (VARKA-149, after this entry was written), not
the seven-op double route above, so the standard's bound is no longer needed for the answer, only
for a cheaper form. That form's bound is `chrono_divide.smt2`'s `MONTH_ARITH`, exact to a dividend
of 98303, which a month count in `[-1199, 1199]` reaches after a bias; neither the form nor its saving
is built or measured.

### 3.2 Day-time interval range: divisions that decline today would fuse

*Found 18 September 2026, from VARKA-103's scope. The same delta as 3.1,
`interval_precision_unbounded` (`proofs/spec/DELTAS.md` 1.6).*

| | range |
|---|---|
| **Standard** | leading field precision, default 2 digits: `INTERVAL DAY` is days 0-99 |
| **Spark** | microseconds across the whole int64 |

**What it would buy.** `m5/PLAN.md` 2.38 records the consequence of
Spark's range plainly: "no range bound comes free, so a division is exact through
2.19 only where a bound proves the operand below 2^53, and is otherwise the
recorded decline VARKA-29 anticipated." Every `ExtractANSIIntervalDays/Hours/
Minutes/Seconds` is a division, and under the reciprocal form the floor is exact
only below about 52,000 days - "most intervals a query holds, and not all of
them."

Under the standard's default precision the operand is at most 99 days of
microseconds, about 8.6e12, which is under 2^53 by three orders of magnitude. So
**every one of those divisions becomes unconditionally exact**, and a family that
currently plans for recorded declines fuses instead. This is the largest entry in
the register: it converts declines into kernels rather than shaving ops off one.

**The bound to register:** the interval column, `[-99 days, 99 days]` in
microseconds for the default precision.

**The theorems** (`proofs/refinement.smt2`, VARKA-245). "standard mode, SCOPE_STANDARD_MODE.md 3.2"
proves the bound under `ConstDivide.EXACT_DIVIDEND_BOUND`, where `long_divide.smt2` proves both
long-lane forms exact, and its neighbour that Spark's own range is not. The interval's
precision is a range the kernels do not hold today: "d + CAST(i AS INTERVAL DAY), standard mode" is
`sat` at `CAST(100 AS INTERVAL DAY)` (`interval_precision_unbounded`), the bound standard mode would
register.

### 3.3 `time + interval`: a guard and a fallback channel removed

*Found 18 September 2026, VARKA-102. Upstream question still open. The delta is
`time_add_raises` (`proofs/spec/DELTAS.md` 1.8), with `time_add_day_field` (1.9) beside it.*

Vanilla's `timeAddInterval` is `addExact` plus a check that the result lies in
`[0, 24h)`, throwing `timeAddIntervalOverflowError` otherwise - it does not wrap.
A lane cannot throw, so `VARKA-102.md` 4.1 lowers it as `make_date`'s pattern:
a guard that fails the batch into the ghost fallback.

[SPARK-57853](https://issues.apache.org/jira/browse/SPARK-57853) is open on
whether ANSI's modulo-24 replaces that. If it does, the lowering becomes a
`floorMod` by `NANOS_PER_DAY` with **no guard, no decline channel and no batch
ever falling back** - and a declined batch costs the whole batch on the row
engine, which is far more than the guard's own compare.

This entry differs from the two above: it is not something a flag can buy, because
the difference is in the *result* and not in the range. It belongs here as a
standard-conformance question to follow upstream rather than to route around -
which is the honest place for it, and section 2's test is what says so.

Following it is now a task rather than a wait: `m5/PLAN.md` 2.82 (VARKA-146) takes reviewing the upstream patch, or writing one for ANSI's modulo-24 if
none exists. That is the only route open here, exactly because a flag is not.

**The theorem** (`proofs/refinement.smt2`, VARKA-245). "t + dt, standard mode" is the witness this
entry says no flag can buy: its query 5 is `sat` at `TIME '23:30' + INTERVAL '1' HOUR`, where the
standard gives 00:30 and the kernel declines because Spark raises, and `unsat` once the sums that
leave the day are excluded. Spark mode's refinement holds.

### 3.4 The math family within a stated ULP bound: the first deliberate departure

*Decided 14 October 2026 by the owner (item 36's first option, `m9/SCOPE.md`); built by
`m8/PLAN.md` rows 345 and 356 to 358, designed in its section 2.7.1.*

*30 October 2026, by the owner's word: the departure this entry records is an opt-in mode, off by
default. By default the family is exact, each function by a per-lane call of the function Spark
calls or an fdlibm port held to it bit for bit (`m8/VARKA-345.md` 2.1), so "fused only while the
configuration is set" and "the default is on" below are superseded: off, the family fuses in its
exact forms; on, a function takes the host library's operator only where its binding has a
contracted bound. The entry's full correction is row 345's.*

Section 1's rule holds everywhere else; here it is broken on purpose, and this entry is the
record. Spark computes `sin`, `exp`, `log` and the rest of the transcendental family with one scalar
call per row, `java.lang.Math` or `StrictMath`. The Vector API lowers the same operators to a host
library - SVML at AVX-512, SVML at AVX2, SLEEF on NEON - and no bound operator reproduces the row
engine's bits on any host: they differ on up to thirteen percent of ordinary inputs, by one
representable double, two for `log10` against the `StrictMath.log10` Spark calls, which is that
function's own error and not the library's (`SCOPE_FUNCTIONS.md` 3 and its dated correction; `tanh`
on x86 is one against the `Math.tanh` Spark calls). No range guard can fix that, so section 2's
construction does not apply: the difference is in the result.

**What is promised instead.** Each fused function states, per host library, its bound against
`java.lang.Math` in the coverage table and the user support table, measured and committed by
`dev/varka_canary/MathLaneProbe.java`; the tests compare within that bound; and the family is fused
only while `spark.sql.codegen.varka.math.ulpContract.enabled` is set. Every other expression stays
bit for bit. *30 October 2026: in the opt-in mode; by default the family fuses exact.*

**Why it is acceptable.** The speed is large and width-bound - 5x to 14x at eight lanes, 2.5x to 9x
at four, 1.1x to 3.9x at two - and the departure is of a kind a cluster already has: HotSpot's
`Math.sin` differs between x86 and aarch64 executors, so a `sin` column computed across a mixed
cluster is not one bit pattern today. **What it costs**: one query can mix the two answers. The
ghost fallback serves a declined batch on the row engine, so with the configuration on, rows of one
column may differ in the last bit by batch. The coverage table says so beside the bound.

*30 October 2026, by the owner's word, superseding this paragraph: the default is off and
exact.*

**The default is on**, the owner's decision of 14 October 2026. So with Varka enabled, these
functions' answers may differ from vanilla Spark's by up to their stated bound unless a user turns
the configuration off; enabling Varka stays answer-neutral for every other expression. The
differential suites compare within the bound by default and run a configuration-off arm that
asserts bit-identical answers, the fallback's per-batch mix is the default behaviour, and
`docs/sql-varka.md` and the user support table state the default and the switch where users look
(`m8/PLAN.md` 2.7.1).

### 3.5 NaN payloads and signs: what the oracles compare

*Added 14 October 2026 from the IEEE 754 reading for milestone 8 (`m8/PLAN.md` 2.7). Not a
departure from Spark's answers: a rule for what "the same answer" means where the standard leaves
it open.*

The standard leaves a NaN's sign and payload unspecified, and Spark's row engine does not
normalise them: a row carries raw bits, `abs` is a bit operation, negation flips the sign, and
Spark's own `rint(-NaN)` sign depends on the JIT tier that ran it (derived from the source;
`m8/PLAN.md` row 344 runs the probe). So Varka's oracles compare NaN lanes **bit for bit in a
pass-through shape** (a column or a literal delivered unchanged) and **by NaN-ness in a computed
one** (the result of an operation whose NaN the standard leaves to the implementation), the
fuzzers draw payload, negative and signalling NaNs, and the emitter never commutes a floating `+`
or `*`, which are not commutative at the bit level when both operands are NaN. The rule is stated
here so that a future reader does not mistake a payload difference in a computed NaN for a wrong
answer, or a bit difference in a pass-through one for an acceptable one.

### 3.6 The reference for a floating-to-decimal answer is JDK 19's `Double.toString`

*Added 14 October 2026 from the float-conversions reading for milestone 8 (`m8/PLAN.md` 2.7).*

Spark builds a decimal from a double through the double's decimal string, and `round`, `bround`
and `truncate` over a double act on that shortest decimal. `Double.toString` returns the shortest
decimal only from JDK 19 (JDK-4511638); the reading reports Ryu's measurement of 2,877
non-shortest outputs per million doubles in the older algorithm, so Spark on JDK 17 differs on
those inputs from Spark on JDK 19 and later. Varka's kernels and oracles take JDK 19's answer as
the reference - this fork builds on JDK 25 - and the oracle's header says so, so that a
differential run against an older row engine reads a disagreement on those inputs as the JDK's,
not the kernel's.

### 3.7 The standard's date range: a bound already wider than it is guarded

*Added 16 October 2026 by VARKA-244, from the delta `proleptic_range` (`proofs/spec/DELTAS.md`
1.4). Not a measured saving.*

| | range |
|---|---|
| **Standard** | YEAR 0001 to 9999: day counts `[-719162, 2932896]` |
| **Spark** | any int32 day count, years -5877641 to 5881580, wrapping past it (`day_count_wraps`) |

**What it would buy: nothing measured yet.** The calendar lowering already assumes a range and
guards it: `VarkaChrono.narrowed` is exact for days in `[NARROW_MIN_DAYS, NARROW_MAX_DAYS]`, 1 March
-12800 to 15 August 33134, a bound set by `w < 2^24` (`chrono_divide.smt2`, VARKA-242), and a batch
outside declines. The standard's range lies inside that one, about a fifth of its width, so
standard mode would change a guard's constants, not remove a guard. A cheaper lowering exact only
over 0001-9999 - a narrower multiply in the era or year step - is the thing to look for before this
entry claims a speedup, and none has been found.

**The bound to register:** the date column, `[-719162, 2932896]`.

**The theorems** (`proofs/refinement.smt2`, VARKA-245), and a correction to what this entry says
is bought. A bare date column is already taken to hold this range: it is the project's column
contract (`VarkaChrono.CONTRACT_MIN_DAYS` and `CONTRACT_MAX_DAYS`), assumed and not checked, so
`year(d)` over a column fuses with no guard. "year(d), month(d), day(d), Spark mode and standard
mode: one statement" holds under it, and "the column contract is load-bearing" is `sat`: a date
Spark holds outside it (`proleptic_range`) feeds the era step past its proof. And the guard
standard mode would need is on results, not inputs: "d + ym, standard mode" and "d +
CAST(i AS INTERVAL DAY), standard mode" are `sat` at `DATE '9999-12-15' + INTERVAL '1' MONTH` and
`DATE '9999-12-31' + INTERVAL '1' DAY`, whose results the standard raises on and the kernels, with no
guard on their results, answer.

## 4. What would have to be built, when a task claims one of these

Nothing here is designed yet. The shape is sketched so an entry can be costed:

* **A configuration surface**, following the project's two-surface model: a
  user-facing `spark.sql.codegen.varka.*` string and a typed emit option, with
  the mapping a separate one-time action.
* **The option in the shape key.** It changes emitted bytes, so it belongs in
  `VarkaEmitOptions` and must reach `canonical()`, on the `division` and
  `floorMod7` precedent; `VarkaShapeCacheSuite` already fails a component that
  cannot change the rendering.
* **A decline-rate assertion.** An input bound that declines most batches is
  correct and slower than not fusing at all, and nothing in the current output
  would say so. Whatever lands first should measure the decline rate, not only
  the kernel rate - the same lesson VARKA-125 had to apply to the surface driver,
  where a benchmark that never compared answers could publish a fast wrong
  kernel.
* **The standard's precision is not expressible in Spark.** There is no
  `INTERVAL YEAR(9)` in the grammar and no precision field on the type, so the
  bound cannot be read off the column's declared type. It would have to come from
  the flag, or from statistics (milestone 6's VARKA-64), which is the more
  principled source and is already scoped.
