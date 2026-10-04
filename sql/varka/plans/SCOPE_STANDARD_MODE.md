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

*Found 18 September 2026, VARKA-89.*

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

### 3.2 Day-time interval range: divisions that decline today would fuse

*Found 18 September 2026, from VARKA-103's scope.*

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

### 3.3 `time + interval`: a guard and a fallback channel removed

*Found 18 September 2026, VARKA-102. Upstream question still open.*

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
